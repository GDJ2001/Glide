import Foundation

/// Serializes document leases, metadata reads and App Group queue mutations away
/// from the main actor. A selected document remains accessible until it is removed.
public actor StagedFilesStore {
    public struct Snapshot: Sendable {
        public let urls: [URL]
        public let sizes: [URL: Int64]
        public let totalBytes: Int64
        public let removalFailed: Bool
    }

    private var urls: [URL] = []
    private var sizes: [URL: Int64] = [:]
    private var leases: Set<URL> = []

    public init() {}

    public func importSharedFiles() throws -> Snapshot {
        try add(AppGroupShareQueue.queuedFiles())
    }

    public func add(_ incoming: [URL]) throws -> Snapshot {
        var candidates = urls
        var acquired: Set<URL> = []
        for url in incoming where !candidates.contains(url) {
            if url.startAccessingSecurityScopedResource() { acquired.insert(url) }
            candidates.append(url)
        }
        do {
            if !candidates.isEmpty {
                let prepared = try ShareExtensionCoordinator.prepareBatch(from: candidates)
                sizes = Dictionary(uniqueKeysWithValues: prepared.items.map { ($0.sourceUrl, $0.sizeBytes) })
            }
            urls = candidates
            leases.formUnion(acquired)
            return snapshot()
        } catch {
            for url in acquired { url.stopAccessingSecurityScopedResource() }
            throw error
        }
    }

    public func remove(_ selected: [URL]) -> Snapshot {
        var removalFailed = false
        for url in Set(selected) where urls.contains(url) {
            do { try AppGroupShareQueue.removeQueuedFile(url) }
            catch { removalFailed = true; continue }
            urls.removeAll { $0 == url }
            sizes.removeValue(forKey: url)
            if leases.remove(url) != nil { url.stopAccessingSecurityScopedResource() }
        }
        return snapshot(removalFailed: removalFailed)
    }

    private func snapshot(removalFailed: Bool = false) -> Snapshot {
        Snapshot(urls: urls, sizes: sizes, totalBytes: sizes.values.reduce(0, +),
                 removalFailed: removalFailed)
    }

    deinit {
        for url in leases { url.stopAccessingSecurityScopedResource() }
    }
}
