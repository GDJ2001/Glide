import Foundation

public struct PersistentTrustedPeer: Codable, Equatable, Sendable {
    public let fingerprintHex: String
    public let publicKeyDerHex: String
    public let displayName: String
    public let enrolledAtUnixMs: Int64
    public var lastSeenAtUnixMs: Int64

    public init(
        fingerprintHex: String,
        publicKeyDerHex: String,
        displayName: String,
        enrolledAtUnixMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        lastSeenAtUnixMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) throws {
        let cleanFp = fingerprintHex.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard cleanFp.count == 64, cleanFp.utf8.allSatisfy({ ($0 >= 48 && $0 <= 57) || ($0 >= 97 && $0 <= 102) }) else {
            throw LanProtocolError.malformedMessage("Fingerprint must be 64 lowercase hex characters.")
        }
        guard !publicKeyDerHex.isEmpty, publicKeyDerHex.count % 2 == 0 else {
            throw LanProtocolError.malformedMessage("Public key must be even-length hex string.")
        }
        guard !displayName.isEmpty && displayName.count <= 64 else {
            throw LanProtocolError.malformedMessage("Display name must be 1..64 characters.")
        }

        self.fingerprintHex = cleanFp
        self.publicKeyDerHex = publicKeyDerHex.lowercased()
        self.displayName = displayName
        self.enrolledAtUnixMs = enrolledAtUnixMs
        self.lastSeenAtUnixMs = lastSeenAtUnixMs
    }
}

public final class PersistentTrustStore: @unchecked Sendable {
    private let lock = NSLock()
    private let fileUrl: URL
    private var peers: [String: PersistentTrustedPeer] = [:]

    public init(storageDirectory: URL? = nil) {
        let dir = storageDirectory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Glide", isDirectory: true)
        self.fileUrl = dir.appendingPathComponent("trusted_peers.json")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        load()
    }

    public func load() {
        lock.lock()
        defer { lock.unlock() }
        guard FileManager.default.fileExists(atPath: fileUrl.path) else { return }
        do {
            let data = try Data(contentsOf: fileUrl)
            let decoded = try JSONDecoder().decode([PersistentTrustedPeer].self, from: data)
            peers = Dictionary(uniqueKeysWithValues: decoded.map { ($0.fingerprintHex, $0) })
        } catch {
            peers = [:]
        }
    }

    public func save() throws {
        lock.lock()
        defer { lock.unlock() }
        let peerList = Array(peers.values)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .prettyPrinted]
        let data = try encoder.encode(peerList)

        let dir = fileUrl.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try data.write(to: fileUrl, options: [.atomic])
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: fileUrl.path)
    }

    public func enrollPeer(
        fingerprintHex: String,
        publicKeyDerHex: String,
        displayName: String
    ) throws {
        let peer = try PersistentTrustedPeer(
            fingerprintHex: fingerprintHex,
            publicKeyDerHex: publicKeyDerHex,
            displayName: displayName
        )
        lock.lock()
        peers[peer.fingerprintHex] = peer
        lock.unlock()
        try save()
    }

    public func revokePeer(fingerprintHex: String) throws {
        let key = fingerprintHex.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        lock.lock()
        let removed = peers.removeValue(forKey: key) != nil
        lock.unlock()
        if removed {
            try save()
        }
    }

    public func isTrusted(fingerprintHex: String) -> Bool {
        let key = fingerprintHex.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        lock.lock()
        defer { lock.unlock() }
        return peers[key] != nil
    }

    public func getPeer(fingerprintHex: String) -> PersistentTrustedPeer? {
        let key = fingerprintHex.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        lock.lock()
        defer { lock.unlock() }
        return peers[key]
    }

    public func allPeers() -> [PersistentTrustedPeer] {
        lock.lock()
        defer { lock.unlock() }
        return Array(peers.values)
    }

    public func clearAll() throws {
        lock.lock()
        peers.removeAll()
        lock.unlock()
        try save()
    }
}
