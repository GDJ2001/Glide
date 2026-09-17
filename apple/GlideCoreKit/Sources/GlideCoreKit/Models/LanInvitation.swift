import Foundation

/// Represents an authorized network endpoint for local peer connection.
public struct LanEndpoint: Codable, Equatable, Sendable {
    public let address: String
    public let port: Int

    public init(address: String, port: Int) {
        self.address = address
        self.port = port
    }
}

/// Domain model for an out-of-band QR/link pairing invitation.
/// Conforms to LAN-006 Section 8 and ADR-0020.
public struct LanInvitation: Codable, Equatable, Sendable {
    public let v: Int
    public let transport: String
    public let profile: Int
    public let protocolVersion: Int
    public let invitationId: String
    public let presence: String
    public let name: String
    public let platform: String
    public let endpoints: [LanEndpoint]
    public let spkiSha256: String
    public let token: String
    public let expiresAt: Int64
    public let ttlSeconds: Int

    enum CodingKeys: String, CodingKey {
        case v
        case transport
        case profile
        case protocolVersion = "protocol"
        case invitationId
        case presence
        case name
        case platform
        case endpoints
        case spkiSha256
        case token
        case expiresAt
        case ttlSeconds
    }

    public init(
        v: Int = 1,
        transport: String = "lan",
        profile: Int = 1,
        protocolVersion: Int = 1,
        invitationId: String,
        presence: String,
        name: String,
        platform: String,
        endpoints: [LanEndpoint],
        spkiSha256: String,
        token: String,
        expiresAt: Int64,
        ttlSeconds: Int = 120
    ) {
        self.v = v
        self.transport = transport
        self.profile = profile
        self.protocolVersion = protocolVersion
        self.invitationId = invitationId.lowercased()
        self.presence = presence
        self.name = name
        self.platform = platform.lowercased()
        self.endpoints = endpoints
        self.spkiSha256 = spkiSha256.lowercased()
        self.token = token
        self.expiresAt = expiresAt
        self.ttlSeconds = ttlSeconds
    }

    public func isExpired(currentEpochSeconds: Int64? = nil) -> Bool {
        let now = currentEpochSeconds ?? Int64(Date().timeIntervalSince1970)
        return now > expiresAt
    }
}
