import Foundation
import Darwin

/// A share extension may only hand files to the app through their common App Group container.
/// A batch becomes visible to the app only after its complete manifest is atomically written.
public enum AppGroupShareQueue {
    public static let groupIdentifier = "group.dev.glide.shared"
    private static let queueDirectoryName = "ShareQueue"
    private static let manifestName = "manifest.json"
    private static let maxManifestBytes = 1_048_576
    private static let staleInterval: TimeInterval = 7 * 24 * 60 * 60

    public enum QueueError: Error {
        case groupUnavailable
        case invalidFile
        case tooManyItems
        case itemTooLarge
        case batchTooLarge
        case incompleteCopy
    }

    private struct Entry: Codable {
        let name: String
        let storedName: String
        let sizeBytes: Int64
    }

    private struct Manifest: Codable {
        let createdAt: Date
        let entries: [Entry]
    }

    public final class PendingBatch {
        private let directory: URL
        private var entries: [Entry] = []
        private var totalBytes: Int64 = 0
        private var published = false

        fileprivate init(directory: URL) { self.directory = directory }

        /// Must run before NSItemProvider's completion handler returns: its URL is temporary.
        public func appendTemporaryFile(_ source: URL, suggestedName: String?) throws {
            guard !published else { throw QueueError.invalidFile }
            guard entries.count < ShareExtensionCoordinator.maxItemCount else { throw QueueError.tooManyItems }
            let values = try source.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
            guard source.isFileURL, values.isRegularFile == true, values.isSymbolicLink != true else {
                throw QueueError.invalidFile
            }
            let rawName = (suggestedName?.isEmpty == false ? suggestedName! : source.lastPathComponent)
            let name = DarwinPathSanitizer.sanitizeFilename(rawName)
            let storedName = UUID().uuidString
            let itemDirectory = directory.appendingPathComponent(storedName, isDirectory: true)
            try FileManager.default.createDirectory(at: itemDirectory, withIntermediateDirectories: false,
                                                    attributes: [.posixPermissions: 0o700])
            let target = itemDirectory.appendingPathComponent(name)
            let input = try FileHandle(forReadingFrom: source)
            let fd = Darwin.open(target.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0o600)
            guard fd >= 0 else {
                try? input.close()
                throw QueueError.invalidFile
            }
            let output = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
            var copied: Int64 = 0
            do {
                while let chunk = try input.read(upToCount: 65_536), !chunk.isEmpty {
                    let (itemNext, itemOverflow) = copied.addingReportingOverflow(Int64(chunk.count))
                    let (batchNext, batchOverflow) = totalBytes.addingReportingOverflow(itemNext)
                    guard !itemOverflow, itemNext <= ShareExtensionCoordinator.maxItemSizeBytes else {
                        throw QueueError.itemTooLarge
                    }
                    guard !batchOverflow, batchNext <= ShareExtensionCoordinator.maxBatchSizeBytes else {
                        throw QueueError.batchTooLarge
                    }
                    try output.write(contentsOf: chunk)
                    copied = itemNext
                }
                try input.close()
                try output.close()
                let attributes = try FileManager.default.attributesOfItem(atPath: target.path)
                guard (attributes[.size] as? NSNumber)?.int64Value == copied else {
                    throw QueueError.incompleteCopy
                }
                entries.append(Entry(name: name, storedName: storedName, sizeBytes: copied))
                totalBytes += copied
            } catch {
                try? input.close()
                try? output.close()
                try? FileManager.default.removeItem(at: itemDirectory)
                throw error
            }
        }

        public func publish() throws {
            guard !published, !entries.isEmpty else { throw QueueError.invalidFile }
            let manifest = Manifest(createdAt: Date(), entries: entries)
            let data = try JSONEncoder().encode(manifest)
            guard data.count <= AppGroupShareQueue.maxManifestBytes else { throw QueueError.batchTooLarge }
            try data.write(to: directory.appendingPathComponent(AppGroupShareQueue.manifestName), options: .atomic)
            published = true
        }

        public func discard() {
            if !published { try? FileManager.default.removeItem(at: directory) }
        }
    }

    public static func createBatch() throws -> PendingBatch {
        let root = try queueRoot()
        let directory = root.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false,
                                                attributes: [.posixPermissions: 0o700])
        return PendingBatch(directory: directory)
    }

    /// Returns only completed batches. Files stay staged until the user sends or removes them.
    public static func queuedFiles() throws -> [URL] {
        let root = try queueRoot()
        let directories = try FileManager.default.contentsOfDirectory(
            at: root, includingPropertiesForKeys: [.creationDateKey, .isSymbolicLinkKey],
            options: [.skipsHiddenFiles])
        var files: [URL] = []
        for directory in directories {
            guard directory.lastPathComponent.count == 36,
                  UUID(uuidString: directory.lastPathComponent) != nil,
                  (try? directory.resourceValues(forKeys: [.isSymbolicLinkKey]))?.isSymbolicLink != true else { continue }
            let manifestURL = directory.appendingPathComponent(manifestName)
            guard let manifest = try? readManifest(at: manifestURL) else {
                removeIfStale(directory)
                continue
            }
            if Date().timeIntervalSince(manifest.createdAt) > staleInterval {
                try? FileManager.default.removeItem(at: directory)
                continue
            }
            guard !manifest.entries.isEmpty,
                  manifest.entries.count <= ShareExtensionCoordinator.maxItemCount else { continue }
            var batchFiles: [URL] = []
            var batchBytes: Int64 = 0
            var valid = true
            for entry in manifest.entries {
                guard UUID(uuidString: entry.storedName) != nil,
                      DarwinPathSanitizer.sanitizeFilename(entry.name) == entry.name,
                      entry.sizeBytes >= 0,
                      entry.sizeBytes <= ShareExtensionCoordinator.maxItemSizeBytes else {
                    valid = false; break
                }
                let (nextBytes, overflow) = batchBytes.addingReportingOverflow(entry.sizeBytes)
                guard !overflow, nextBytes <= ShareExtensionCoordinator.maxBatchSizeBytes else {
                    valid = false; break
                }
                batchBytes = nextBytes
                let itemDirectory = directory.appendingPathComponent(entry.storedName, isDirectory: true)
                guard (try? itemDirectory.resourceValues(forKeys: [.isSymbolicLinkKey]))?.isSymbolicLink != true else {
                    valid = false; break
                }
                let url = itemDirectory.appendingPathComponent(entry.name)
                let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
                if values?.isRegularFile == true, values?.isSymbolicLink != true,
                   values?.fileSize.map(Int64.init) == entry.sizeBytes { batchFiles.append(url) }
                else { valid = false; break }
            }
            if valid { files.append(contentsOf: batchFiles) }
        }
        return files
    }

    /// Remove a queued source after the user removes it or after verified send completion.
    /// Ordinary document-picker URLs are never touched.
    public static func removeQueuedFile(_ url: URL) throws {
        guard let root = try? queueRoot().standardizedFileURL else { return }
        let itemDirectory = url.deletingLastPathComponent().standardizedFileURL
        let batchDirectory = itemDirectory.deletingLastPathComponent().standardizedFileURL
        guard batchDirectory.deletingLastPathComponent() == root,
              UUID(uuidString: batchDirectory.lastPathComponent) != nil,
              UUID(uuidString: itemDirectory.lastPathComponent) != nil else { return }
        let manifestURL = batchDirectory.appendingPathComponent(manifestName)
        let manifest = try readManifest(at: manifestURL)
        guard let matched = manifest.entries.first(where: {
            $0.storedName == itemDirectory.lastPathComponent && $0.name == url.lastPathComponent
        }) else { return }
        let remaining = manifest.entries.filter { $0.storedName != matched.storedName }
        if remaining.isEmpty {
            try FileManager.default.removeItem(at: batchDirectory)
        } else {
            let updated = Manifest(createdAt: manifest.createdAt, entries: remaining)
            try JSONEncoder().encode(updated).write(to: manifestURL, options: .atomic)
            try FileManager.default.removeItem(at: itemDirectory)
        }
    }

    private static func queueRoot() throws -> URL {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: groupIdentifier) else {
            throw QueueError.groupUnavailable
        }
        let root = container.appendingPathComponent(queueDirectoryName, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        return root
    }

    /// Bound the read before allocating JSON data, including for a malformed local queue file.
    private static func readManifest(at url: URL) throws -> Manifest {
        let fd = Darwin.open(url.path, O_RDONLY | O_NOFOLLOW)
        guard fd >= 0 else { throw QueueError.invalidFile }
        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        defer { try? handle.close() }
        var metadata = stat()
        guard Darwin.fstat(fd, &metadata) == 0,
              metadata.st_mode & mode_t(S_IFMT) == mode_t(S_IFREG),
              metadata.st_size >= 0, metadata.st_size <= maxManifestBytes else {
            throw QueueError.invalidFile
        }
        guard let data = try handle.read(upToCount: maxManifestBytes + 1),
              data.count <= maxManifestBytes,
              data.count == metadata.st_size else { throw QueueError.invalidFile }
        return try JSONDecoder().decode(Manifest.self, from: data)
    }

    private static func removeIfStale(_ directory: URL) {
        let created = (try? directory.resourceValues(forKeys: [.creationDateKey]))?.creationDate ?? .distantPast
        if Date().timeIntervalSince(created) > staleInterval {
            try? FileManager.default.removeItem(at: directory)
        }
    }
}
