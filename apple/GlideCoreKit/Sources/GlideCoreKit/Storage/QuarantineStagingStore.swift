import Foundation
import CryptoKit
import Darwin

/// Errors encountered during quarantine staging, digest verification, or commitment.
public enum QuarantineError: Error, Equatable, Sendable {
    case digestMismatch(expected: String, actual: String)
    case quarantineFileNotFound(String)
    case cannotCreateStagingDirectory(String)
    case ioError(String)
    case lengthMismatch(expected: Int64, actual: Int64)
    case invalidStagingIdentifier
    case oversizedChunk
}

/// Manages isolated quarantine staging and atomic commitment for incoming streams on Apple platforms.
/// Conforms to Section 7 of APPLE_PLATFORMS_SPECIFICATION.md and ADR-0020.
public final class QuarantineStagingStore: @unchecked Sendable {
    public let baseDirectory: URL
    public let sessionId: String
    public let itemId: String
    public let quarantineUrl: URL

    private var hasher = SHA256()
    private var fileHandle: FileHandle?
    private var isFinalized = false
    private var finalizedDigest: String?
    private var receivedBytes: Int64 = 0

    public init(baseDirectory: URL, sessionId: String, itemId: String) {
        self.baseDirectory = baseDirectory
        self.sessionId = sessionId
        self.itemId = itemId

        let stagingDir = baseDirectory
            .appendingPathComponent("staging", isDirectory: true)
            .appendingPathComponent(sessionId, isDirectory: true)

        self.quarantineUrl = stagingDir.appendingPathComponent("\(itemId).quarantine")
    }

    /// Prepares quarantine storage for writing.
    public func open() throws {
        func safeId(_ value: String) -> Bool {
            return !value.isEmpty && value.utf8.count <= 64 &&
                value.utf8.allSatisfy { ($0 >= 48 && $0 <= 57) ||
                    ($0 >= 65 && $0 <= 90) || ($0 >= 97 && $0 <= 122) || $0 == 45 }
        }
        guard safeId(sessionId) && safeId(itemId) else {
            throw QuarantineError.invalidStagingIdentifier
        }
        try rejectSymlink(baseDirectory)
        try rejectSymlink(baseDirectory.appendingPathComponent("staging", isDirectory: true))
        let stagingDir = quarantineUrl.deletingLastPathComponent()
        try rejectSymlink(stagingDir)
        try FileManager.default.createDirectory(
            at: stagingDir, withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700])
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: stagingDir.path)
        try rejectSymlink(stagingDir)

        let fd = Darwin.open(quarantineUrl.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0o600)
        guard fd >= 0 else { throw QuarantineError.ioError("Could not create private staging file.") }
        self.fileHandle = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
    }

    /// Appends a streamed data chunk to the quarantine file and updates the incremental SHA-256 hash.
    public func writeChunk(_ chunk: Data) throws {
        guard let handle = fileHandle else {
            throw QuarantineError.ioError("File handle is not open.")
        }

        guard chunk.count <= 65_536 else { throw QuarantineError.oversizedChunk }
        let (nextSize, overflow) = receivedBytes.addingReportingOverflow(Int64(chunk.count))
        guard !overflow && nextSize <= 68_719_476_736 else { throw QuarantineError.oversizedChunk }
        try handle.write(contentsOf: chunk)
        hasher.update(data: chunk)
        receivedBytes = nextSize
    }

    /// Verifies the private quarantine, then rehashes a destination-volume temporary copy
    /// before moving it to a visible name. A cross-volume move directly to the final name
    /// would expose a partially copied file if the destination fills or the process dies.
    public func finalizeAndCommit(
        expectedSha256Hex: String,
        expectedLengthBytes: Int64,
        destinationDirectory: URL,
        rawFilename: String
    ) throws -> URL {
        try fileHandle?.close()
        fileHandle = nil

        guard expectedLengthBytes >= 0, expectedLengthBytes <= 68_719_476_736,
              receivedBytes == expectedLengthBytes else {
            purge()
            throw QuarantineError.lengthMismatch(expected: expectedLengthBytes, actual: receivedBytes)
        }

        let computed = getOrComputeDigest()
        let expected = expectedSha256Hex.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()

        guard constantTimeCompare(computed, expected) else {
            // Strict Invariant: Immediate purge on digest mismatch
            purge()
            throw QuarantineError.digestMismatch(expected: expected, actual: computed)
        }

        // Sanitize destination filename
        let sanitizedName = DarwinPathSanitizer.sanitizeFilename(rawFilename)

        // Ensure destination directory exists
        try rejectSymlink(destinationDirectory)
        try FileManager.default.createDirectory(at: destinationDirectory, withIntermediateDirectories: true)
        try rejectSymlink(destinationDirectory)

        let commitDirectory = destinationDirectory.appendingPathComponent(
            ".glide-commit-\(UUID().uuidString)", isDirectory: true)
        guard Darwin.mkdir(commitDirectory.path, 0o700) == 0 else {
            throw QuarantineError.ioError("Could not create a private destination staging directory.")
        }
        try rejectSymlink(commitDirectory)
        let temporaryUrl = commitDirectory.appendingPathComponent(UUID().uuidString + ".part")
        defer {
            try? FileManager.default.removeItem(at: temporaryUrl)
            if let contents = try? FileManager.default.contentsOfDirectory(atPath: commitDirectory.path),
               contents.isEmpty {
                try? FileManager.default.removeItem(at: commitDirectory)
            }
        }

        let source = try FileHandle(forReadingFrom: quarantineUrl)
        let fd = Darwin.open(temporaryUrl.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0o600)
        guard fd >= 0 else {
            try? source.close()
            throw QuarantineError.ioError("Could not create a private destination staging file.")
        }
        let target = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        var copiedBytes: Int64 = 0
        do {
            while let chunk = try source.read(upToCount: 65_536), !chunk.isEmpty {
                let (next, overflow) = copiedBytes.addingReportingOverflow(Int64(chunk.count))
                guard !overflow && next <= expectedLengthBytes else {
                    throw QuarantineError.lengthMismatch(expected: expectedLengthBytes, actual: next)
                }
                try target.write(contentsOf: chunk)
                copiedBytes = next
            }
            try source.close()
            guard copiedBytes == expectedLengthBytes else {
                throw QuarantineError.lengthMismatch(expected: expectedLengthBytes, actual: copiedBytes)
            }
            guard Darwin.fsync(fd) == 0 else {
                throw QuarantineError.ioError("Could not sync the verified destination copy.")
            }
            try target.close()
        } catch {
            try? source.close()
            try? target.close()
            throw error
        }

        // Read back the destination file itself. Hashing only the source chunks
        // passed to write() would not verify the bytes stored in this new file.
        let verifyFd = Darwin.open(temporaryUrl.path, O_RDONLY | O_NOFOLLOW)
        guard verifyFd >= 0 else { throw QuarantineError.ioError("Could not verify the destination copy.") }
        let verificationSource = FileHandle(fileDescriptor: verifyFd, closeOnDealloc: true)
        defer { try? verificationSource.close() }
        var destinationHasher = SHA256()
        var verifiedBytes: Int64 = 0
        while let chunk = try verificationSource.read(upToCount: 65_536), !chunk.isEmpty {
            let (next, overflow) = verifiedBytes.addingReportingOverflow(Int64(chunk.count))
            guard !overflow && next <= expectedLengthBytes else {
                throw QuarantineError.lengthMismatch(expected: expectedLengthBytes, actual: next)
            }
            destinationHasher.update(data: chunk)
            verifiedBytes = next
        }
        guard verifiedBytes == expectedLengthBytes else {
            throw QuarantineError.lengthMismatch(expected: expectedLengthBytes, actual: verifiedBytes)
        }
        let destinationDigest = destinationHasher.finalize().map { String(format: "%02x", $0) }.joined()
        guard constantTimeCompare(destinationDigest, expected) else {
            throw QuarantineError.digestMismatch(expected: expected, actual: destinationDigest)
        }
        try verificationSource.close()

        let existingFiles = try FileManager.default.contentsOfDirectory(atPath: destinationDirectory.path)
        let finalFilename = DarwinPathSanitizer.resolveCollision(filename: sanitizedName, existingNames: Set(existingFiles))
        let destinationUrl = destinationDirectory.appendingPathComponent(finalFilename)
        // Both paths are on the destination volume. FileManager will refuse a collision.
        try FileManager.default.moveItem(at: temporaryUrl, to: destinationUrl)
        try? FileManager.default.removeItem(at: quarantineUrl)
        cleanupSessionFolderIfEmpty()

        return destinationUrl
    }

    /// Immediately deletes the quarantine file and any unverified bytes.
    public func purge() {
        try? fileHandle?.close()
        fileHandle = nil

        if FileManager.default.fileExists(atPath: quarantineUrl.path) {
            try? FileManager.default.removeItem(at: quarantineUrl)
        }

        cleanupSessionFolderIfEmpty()
    }

    private func getOrComputeDigest() -> String {
        if let existing = finalizedDigest {
            return existing
        }
        let digest = hasher.finalize()
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        finalizedDigest = hex
        isFinalized = true
        return hex
    }

    private func cleanupSessionFolderIfEmpty() {
        let sessionDir = quarantineUrl.deletingLastPathComponent()
        if let contents = try? FileManager.default.contentsOfDirectory(atPath: sessionDir.path), contents.isEmpty {
            try? FileManager.default.removeItem(at: sessionDir)
        }
    }

    private func rejectSymlink(_ url: URL) throws {
        if (try? url.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink) == true {
            throw QuarantineError.ioError("A symbolic link is not allowed in private staging.")
        }
    }

    private func constantTimeCompare(_ a: String, _ b: String) -> Bool {
        guard a.utf8.count == b.utf8.count else { return false }
        var result: UInt8 = 0
        for (byteA, byteB) in zip(a.utf8, b.utf8) {
            result |= byteA ^ byteB
        }
        return result == 0
    }
}
