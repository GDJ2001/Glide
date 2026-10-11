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
/// Conforms to LAN-006 Section 8, ADR-0020, and GLIDE_PROTOCOL_V1.
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
    public let expiresAt: String
    public let ttlSeconds: Int
    public let invitationHash: String

    public static let allowedPlatforms: Set<String> = [
        "android", "windows", "macos", "ios", "linux", "web", "unknown"
    ]

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

    // invitationHash is local state computed from the exact JSON payload. It is never
    // present on the wire: Kotlin and C# reject unknown invitation fields.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.v = try c.decode(Int.self, forKey: .v)
        self.transport = try c.decode(String.self, forKey: .transport)
        self.profile = try c.decode(Int.self, forKey: .profile)
        self.protocolVersion = try c.decode(Int.self, forKey: .protocolVersion)
        self.invitationId = try c.decode(String.self, forKey: .invitationId)
        self.presence = try c.decode(String.self, forKey: .presence)
        self.name = try c.decode(String.self, forKey: .name)
        self.platform = try c.decode(String.self, forKey: .platform)
        self.endpoints = try c.decode([LanEndpoint].self, forKey: .endpoints)
        self.spkiSha256 = try c.decode(String.self, forKey: .spkiSha256)
        self.token = try c.decode(String.self, forKey: .token)
        self.expiresAt = try c.decode(String.self, forKey: .expiresAt)
        self.ttlSeconds = try c.decode(Int.self, forKey: .ttlSeconds)
        self.invitationHash = ""
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(v, forKey: .v)
        try c.encode(transport, forKey: .transport)
        try c.encode(profile, forKey: .profile)
        try c.encode(protocolVersion, forKey: .protocolVersion)
        try c.encode(invitationId, forKey: .invitationId)
        try c.encode(presence, forKey: .presence)
        try c.encode(name, forKey: .name)
        try c.encode(platform, forKey: .platform)
        try c.encode(endpoints, forKey: .endpoints)
        try c.encode(spkiSha256, forKey: .spkiSha256)
        try c.encode(token, forKey: .token)
        try c.encode(expiresAt, forKey: .expiresAt)
        try c.encode(ttlSeconds, forKey: .ttlSeconds)
    }

    public init(
        v: Int = 1,
        transport: String = "lan-tls",
        profile: Int = 1,
        protocolVersion: Int = 1,
        invitationId: String,
        presence: String,
        name: String,
        platform: String,
        endpoints: [LanEndpoint],
        spkiSha256: String,
        token: String,
        expiresAt: String,
        ttlSeconds: Int = 120,
        invitationHash: String = ""
    ) {
        self.v = v
        self.transport = transport
        self.profile = profile
        self.protocolVersion = protocolVersion
        self.invitationId = invitationId.lowercased()
        self.presence = presence.lowercased()
        self.name = name
        self.platform = platform.lowercased()
        self.endpoints = endpoints
        self.spkiSha256 = spkiSha256.lowercased()
        self.token = token
        self.expiresAt = expiresAt
        self.ttlSeconds = ttlSeconds
        self.invitationHash = invitationHash
    }

    public func isExpired(currentEpochSeconds: Int64? = nil) -> Bool {
        guard let exp = Int64(expiresAt) else { return true }
        let now = currentEpochSeconds ?? Int64(Date().timeIntervalSince1970)
        return now > exp
    }

    public func withHash(_ hash: String) -> LanInvitation {
        LanInvitation(
            v: v,
            transport: transport,
            profile: profile,
            protocolVersion: protocolVersion,
            invitationId: invitationId,
            presence: presence,
            name: name,
            platform: platform,
            endpoints: endpoints,
            spkiSha256: spkiSha256,
            token: token,
            expiresAt: expiresAt,
            ttlSeconds: ttlSeconds,
            invitationHash: hash
        )
    }
}

/// Domain model for a Profile 2 pair-only QR/link invitation (`glide://pair/v2#...`).
public struct LanPairV2Invitation: Codable, Equatable, Sendable {
    public static let requiredPurpose = "pair-only"
    public static let maxTtlSeconds = 120
    public static let allowedPlatforms: Set<String> = [
        "android", "windows", "macos", "ios", "linux", "unknown"
    ]

    public let v: Int
    public let purpose: String
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
    public let expiresAt: String
    public let ttlSeconds: Int
    public let invitationHash: String

    enum CodingKeys: String, CodingKey {
        case v
        case purpose
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

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.v = try c.decode(Int.self, forKey: .v)
        self.purpose = try c.decode(String.self, forKey: .purpose)
        self.transport = try c.decode(String.self, forKey: .transport)
        self.profile = try c.decode(Int.self, forKey: .profile)
        self.protocolVersion = try c.decode(Int.self, forKey: .protocolVersion)
        self.invitationId = try c.decode(String.self, forKey: .invitationId)
        self.presence = try c.decode(String.self, forKey: .presence)
        self.name = try c.decode(String.self, forKey: .name)
        self.platform = try c.decode(String.self, forKey: .platform)
        self.endpoints = try c.decode([LanEndpoint].self, forKey: .endpoints)
        self.spkiSha256 = try c.decode(String.self, forKey: .spkiSha256)
        self.token = try c.decode(String.self, forKey: .token)
        self.expiresAt = try c.decode(String.self, forKey: .expiresAt)
        self.ttlSeconds = try c.decode(Int.self, forKey: .ttlSeconds)
        self.invitationHash = ""
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(v, forKey: .v)
        try c.encode(purpose, forKey: .purpose)
        try c.encode(transport, forKey: .transport)
        try c.encode(profile, forKey: .profile)
        try c.encode(protocolVersion, forKey: .protocolVersion)
        try c.encode(invitationId, forKey: .invitationId)
        try c.encode(presence, forKey: .presence)
        try c.encode(name, forKey: .name)
        try c.encode(platform, forKey: .platform)
        try c.encode(endpoints, forKey: .endpoints)
        try c.encode(spkiSha256, forKey: .spkiSha256)
        try c.encode(token, forKey: .token)
        try c.encode(expiresAt, forKey: .expiresAt)
        try c.encode(ttlSeconds, forKey: .ttlSeconds)
    }

    public init(
        v: Int = 2,
        purpose: String = LanPairV2Invitation.requiredPurpose,
        transport: String = "lan-tls",
        profile: Int = 2,
        protocolVersion: Int = 1,
        invitationId: String,
        presence: String,
        name: String,
        platform: String,
        endpoints: [LanEndpoint],
        spkiSha256: String,
        token: String,
        expiresAt: String,
        ttlSeconds: Int = LanPairV2Invitation.maxTtlSeconds,
        invitationHash: String = ""
    ) {
        self.v = v
        self.purpose = purpose
        self.transport = transport
        self.profile = profile
        self.protocolVersion = protocolVersion
        self.invitationId = invitationId.lowercased()
        self.presence = presence.lowercased()
        self.name = name
        self.platform = platform.lowercased()
        self.endpoints = endpoints
        self.spkiSha256 = spkiSha256.lowercased()
        self.token = token
        self.expiresAt = expiresAt
        self.ttlSeconds = ttlSeconds
        self.invitationHash = invitationHash
    }

    public func isExpired(currentEpochSeconds: Int64? = nil) -> Bool {
        guard let exp = Int64(expiresAt) else { return true }
        let now = currentEpochSeconds ?? Int64(Date().timeIntervalSince1970)
        return now > exp
    }

    public func withHash(_ hash: String) -> LanPairV2Invitation {
        LanPairV2Invitation(
            v: v,
            purpose: purpose,
            transport: transport,
            profile: profile,
            protocolVersion: protocolVersion,
            invitationId: invitationId,
            presence: presence,
            name: name,
            platform: platform,
            endpoints: endpoints,
            spkiSha256: spkiSha256,
            token: token,
            expiresAt: expiresAt,
            ttlSeconds: ttlSeconds,
            invitationHash: hash
        )
    }
}

public enum ParsedGlideInvitation: Equatable, Sendable {
    case transferV1(LanInvitation)
    case pairOnlyV2(LanPairV2Invitation)
}

