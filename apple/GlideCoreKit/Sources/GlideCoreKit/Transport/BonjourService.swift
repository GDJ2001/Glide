import Foundation

/// Represents a peer discovered via Bonjour mDNS advertising on the local network.
public struct BonjourPeer: Equatable, Sendable {
    public let name: String
    public let endpoint: String
    public let spkiSha256: String
    public let presenceToken: String
    public let protocolVersion: Int

    public init(
        name: String,
        endpoint: String,
        spkiSha256: String,
        presenceToken: String,
        protocolVersion: Int = 1
    ) {
        self.name = name
        self.endpoint = endpoint
        self.spkiSha256 = spkiSha256.lowercased()
        self.presenceToken = presenceToken
        self.protocolVersion = protocolVersion
    }
}

/// Bonjour service and TXT record contract for Apple platform discovery.
/// Conforms to Section 4 of APPLE_PLATFORMS_SPECIFICATION.md.
public enum BonjourService {
    public static let serviceType = "_glide._tcp"
    public static let serviceDomain = "local."

    public enum BonjourError: Error, Equatable, Sendable {
        case missingKey(String)
        case unsupportedProtocolVersion(Int)
        case invalidSpkiLength(actual: Int)
        case emptyEndpoint
    }

    /// Constructs the standard TXT record dictionary for Bonjour service registration.
    public static func buildTxtRecord(
        name: String,
        spkiSha256Hex: String,
        presenceTokenHex: String,
        version: Int = 1
    ) -> [String: String] {
        return [
            "v": String(version),
            "name": name,
            "spki": spkiSha256Hex.lowercased(),
            "presence": presenceTokenHex
        ]
    }

    /// Parses and validates a TXT record dictionary received from a Bonjour browse result.
    public static func parseTxtRecord(_ dict: [String: String], endpoint: String) throws -> BonjourPeer {
        guard !endpoint.trimmingCharacters(in: .whitespaces).isEmpty else {
            throw BonjourError.emptyEndpoint
        }

        guard let vString = dict["v"], let v = Int(vString) else {
            throw BonjourError.missingKey("v")
        }
        guard v == 1 else {
            throw BonjourError.unsupportedProtocolVersion(v)
        }

        guard let name = dict["name"], !name.isEmpty else {
            throw BonjourError.missingKey("name")
        }

        guard let spki = dict["spki"] else {
            throw BonjourError.missingKey("spki")
        }
        guard spki.count == 64 else {
            throw BonjourError.invalidSpkiLength(actual: spki.count)
        }

        guard let presence = dict["presence"], !presence.isEmpty else {
            throw BonjourError.missingKey("presence")
        }

        return BonjourPeer(
            name: name,
            endpoint: endpoint,
            spkiSha256: spki,
            presenceToken: presence,
            protocolVersion: v
        )
    }
}
