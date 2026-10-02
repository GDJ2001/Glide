import Foundation

/// Represents a validated transfer dispatch request to a target macOS companion or nearby peer.
public struct MacTransferRequest: Equatable, Sendable {
    public let peer: BonjourPeer
    public let batch: ShareBatch
    public let sessionId: String
    public let initiatedAt: Date

    public init(peer: BonjourPeer, batch: ShareBatch, sessionId: String = UUID().uuidString, initiatedAt: Date = Date()) {
        self.peer = peer
        self.batch = batch
        self.sessionId = sessionId
        self.initiatedAt = initiatedAt
    }
}

/// Coordinates macOS Finder drag-and-drop operations, folder traversal, metadata filtering,
/// protocol bounded limits, and peer drawer target dispatch.
/// Conforms to Section 6.2 and Section 7 of APPLE_PLATFORMS_SPECIFICATION.md.
public final class MacDropTargetCoordinator: @unchecked Sendable {
    // Protocol V1 Bounded Limits
    public static let maxItemSizeBytes: Int64 = 68_719_476_736   // 64 GiB
    public static let maxBatchSizeBytes: Int64 = 274_877_906_944 // 256 GiB
    public static let maxItemCount: Int = 10_000

    public enum DropError: Error, Equatable, Sendable {
        case emptyDrop
        case tooManyItems(actual: Int, max: Int)
        case itemTooLarge(name: String, size: Int64, max: Int64)
        case batchTooLarge(totalSize: Int64, max: Int64)
        case fileNotFound(String)
        case unreadableFile(String)
        case noPeerSelected
    }

    private let lock = NSLock()
    private var _selectedPeer: BonjourPeer?

    public var selectedPeer: BonjourPeer? {
        lock.lock()
        defer { lock.unlock() }
        return _selectedPeer
    }

    public init(initialPeer: BonjourPeer? = nil) {
        self._selectedPeer = initialPeer
    }

    /// Selects or deselects the active target peer for drag-and-drop transmissions.
    public func selectPeer(_ peer: BonjourPeer?) {
        lock.lock()
        defer { lock.unlock() }
        _selectedPeer = peer
    }

    /// Filters and expands dropped URLs (recursively scanning folders and stripping macOS metadata files).
    public static func expandAndFilterUrls(_ urls: [URL]) throws -> [URL] {
        var expanded: [URL] = []
        var visitedPaths = Set<String>()

        for url in urls {
            try collectFiles(at: url, result: &expanded, visitedPaths: &visitedPaths)
        }

        return expanded
    }

    /// Recursively collects files while skipping macOS metadata, system artifacts, and circular symlinks.
    private static func collectFiles(at url: URL, result: inout [URL], visitedPaths: inout Set<String>) throws {
        let path = url.standardizedFileURL.path
        guard FileManager.default.fileExists(atPath: path) else {
            throw DropError.fileNotFound(path)
        }

        let canonicalPath = URL(fileURLWithPath: path).resolvingSymlinksInPath().path

        // Prevent infinite loops on recursive symlinks
        if visitedPaths.contains(canonicalPath) {
            return
        }
        visitedPaths.insert(canonicalPath)

        let filename = url.lastPathComponent
        if shouldIgnoreFilename(filename) {
            return
        }

        var isDir: ObjCBool = false
        if FileManager.default.fileExists(atPath: path, isDirectory: &isDir) {
            if isDir.boolValue {
                let contents = try? FileManager.default.contentsOfDirectory(
                    at: url,
                    includingPropertiesForKeys: [.isRegularFileKey, .isDirectoryKey],
                    options: [.skipsHiddenFiles]
                )
                for child in contents ?? [] {
                    try collectFiles(at: child, result: &result, visitedPaths: &visitedPaths)
                }
            } else {
                result.append(url)
            }
        }
    }

    /// Determines if a file should be ignored as macOS junk / metadata.
    public static func shouldIgnoreFilename(_ name: String) -> Bool {
        if name == ".DS_Store" || name == "Thumbs.db" || name == "__MACOSX" || name == ".Spotlight-V100" || name == ".Trashes" {
            return true
        }
        if name.hasPrefix("._") {
            return true
        }
        return false
    }

    /// Validates dropped URLs against size and count limits.
    public static func validateDrop(fileUrls: [URL]) throws -> [URL] {
        let expanded = try expandAndFilterUrls(fileUrls)
        guard !expanded.isEmpty else {
            throw DropError.emptyDrop
        }
        guard expanded.count <= maxItemCount else {
            throw DropError.tooManyItems(actual: expanded.count, max: maxItemCount)
        }
        return expanded
    }

    /// Prepares a validated `ShareBatch` from dropped URLs.
    public static func prepareDropBatch(from fileUrls: [URL]) throws -> ShareBatch {
        let validatedUrls = try validateDrop(fileUrls: fileUrls)

        var items: [ShareItem] = []
        var totalSize: Int64 = 0

        for url in validatedUrls {
            let filename = url.lastPathComponent
            let attrs = try? FileManager.default.attributesOfItem(atPath: url.path)
            let fileSize = (attrs?[.size] as? NSNumber)?.int64Value ?? 0

            guard fileSize <= maxItemSizeBytes else {
                throw DropError.itemTooLarge(name: filename, size: fileSize, max: maxItemSizeBytes)
            }

            totalSize += fileSize
            guard totalSize <= maxBatchSizeBytes else {
                throw DropError.batchTooLarge(totalSize: totalSize, max: maxBatchSizeBytes)
            }

            let mime = ShareExtensionCoordinator.mimeTypeForExtension((filename as NSString).pathExtension)
            items.append(ShareItem(name: filename, sizeBytes: fileSize, mimeType: mime, sourceUrl: url))
        }

        return ShareBatch(items: items)
    }

    /// Initiates a transfer session for the prepared batch against the currently selected peer.
    public func initiateTransfer(batch: ShareBatch) throws -> MacTransferRequest {
        lock.lock()
        guard let peer = _selectedPeer else {
            lock.unlock()
            throw DropError.noPeerSelected
        }
        lock.unlock()

        guard !batch.items.isEmpty else {
            throw DropError.emptyDrop
        }

        return MacTransferRequest(peer: peer, batch: batch)
    }
}
