import Foundation

public struct PersistentHistoryEntry: Codable, Equatable, Sendable, Identifiable {
    public let id: String
    public let timestamp: Date
    public let direction: String
    public let peerName: String
    public let fileCount: Int
    public let totalBytes: Int64
    public let status: String
    public let destinationPath: String?

    public init(
        id: String = UUID().uuidString,
        timestamp: Date = Date(),
        direction: String,
        peerName: String,
        fileCount: Int,
        totalBytes: Int64,
        status: String,
        destinationPath: String? = nil
    ) {
        self.id = id
        self.timestamp = timestamp
        self.direction = direction
        self.peerName = peerName
        self.fileCount = fileCount
        self.totalBytes = totalBytes
        self.status = status
        self.destinationPath = destinationPath
    }
}

public final class PersistentHistoryStore: @unchecked Sendable {
    private let lock = NSLock()
    private let fileUrl: URL
    private var entries: [PersistentHistoryEntry] = []
    public static let maxEntries = 500

    public init(storageDirectory: URL? = nil) {
        let dir = storageDirectory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Glide", isDirectory: true)
        self.fileUrl = dir.appendingPathComponent("history.json")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        load()
    }

    public func load() {
        lock.lock()
        defer { lock.unlock() }
        guard FileManager.default.fileExists(atPath: fileUrl.path) else { return }
        do {
            let data = try Data(contentsOf: fileUrl)
            entries = try JSONDecoder().decode([PersistentHistoryEntry].self, from: data)
        } catch {
            entries = []
        }
    }

    public func save() throws {
        lock.lock()
        defer { lock.unlock() }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .prettyPrinted]
        encoder.dateEncodingStrategy = .iso8601
        let data = try encoder.encode(entries)

        let dir = fileUrl.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try data.write(to: fileUrl, options: [.atomic])
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: fileUrl.path)
    }

    public func addEntry(_ entry: PersistentHistoryEntry) throws {
        lock.lock()
        entries.insert(entry, at: 0)
        if entries.count > Self.maxEntries {
            entries = Array(entries.prefix(Self.maxEntries))
        }
        lock.unlock()
        try save()
    }

    public func allEntries() -> [PersistentHistoryEntry] {
        lock.lock()
        defer { lock.unlock() }
        return entries
    }

    public func clearAll() throws {
        lock.lock()
        entries.removeAll()
        lock.unlock()
        try save()
    }
}
