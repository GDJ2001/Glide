import Foundation
import CryptoKit

/// Errors encountered during quarantine staging, digest verification, or commitment.
public enum QuarantineError: Error, Equatable, Sendable {
    case digestMismatch(expected: String, actual: String)
    case quarantineFileNotFound(String)
    case cannotCreateStagingDirectory(String)
    case ioError(String)
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
        let stagingDir = quarantineUrl.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: stagingDir, withIntermediateDirectories: true)

        if !FileManager.default.fileExists(atPath: quarantineUrl.path) {
            FileManager.default.createFile(atPath: quarantineUrl.path, contents: nil)
        }

        guard let handle = FileHandle(forWritingAtPath: quarantineUrl.path) else {
            throw QuarantineError.ioError("Could not open file handle for \(quarantineUrl.path)")
        }
        self.fileHandle = handle
    }

    /// Appends a streamed data chunk to the quarantine file and updates the incremental SHA-256 hash.
    public func writeChunk(_ chunk: Data) throws {
        guard let handle = fileHandle else {
            throw QuarantineError.ioError("File handle is not open.")
        }

        hasher.update(data: chunk)
        try handle.write(contentsOf: chunk)
    }

    /// Finalizes the SHA-256 digest, verifies against expected hash, and atomically moves
    /// the quarantine file to the destination directory with sanitized filename and collision resolution.
    public func finalizeAndCommit(
        expectedSha256Hex: String,
        destinationDirectory: URL,
        rawFilename: String
    ) throws -> URL {
        try fileHandle?.close()
        fileHandle = nil

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
        try FileManager.default.createDirectory(at: destinationDirectory, withIntermediateDirectories: true)

        // Resolve filename collisions in destination directory
        let existingFiles = (try? FileManager.default.contentsOfDirectory(atPath: destinationDirectory.path)) ?? []
        let finalFilename = DarwinPathSanitizer.resolveCollision(filename: sanitizedName, existingNames: Set(existingFiles))

        let destinationUrl = destinationDirectory.appendingPathComponent(finalFilename)

        // Atomic move from quarantine to committed storage
        try FileManager.default.moveItem(at: quarantineUrl, to: destinationUrl)

        // Clean up parent session staging folder if empty
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

    private func constantTimeCompare(_ a: String, _ b: String) -> Bool {
        guard a.utf8.count == b.utf8.count else { return false }
        var result: UInt8 = 0
        for (byteA, byteB) in zip(a.utf8, b.utf8) {
            result |= byteA ^ byteB
        }
        return result == 0
    }
}
