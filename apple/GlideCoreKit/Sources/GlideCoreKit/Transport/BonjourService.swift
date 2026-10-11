import Foundation

/// Represents a peer discovered via Bonjour mDNS advertising on the local network.
/// Conforms to the canonical `_glide._tcp` TXT contract (`v=1, tv=1, q=1, p, dn, dt` + optional `pair=1, tr=1`).
public struct BonjourPeer: Equatable, Sendable, Identifiable {
    public var id: String { presenceToken }
    public let name: String
    public let endpoint: String
    public let presenceToken: String
    public let deviceType: String
    public let protocolVersion: Int
    public let recordProfileVersion: Int
    public let isPairReady: Bool
    public let supportsTrustedReconnect: Bool

    public init(
        name: String,
        endpoint: String,
        presenceToken: String,
        deviceType: String = BonjourService.deviceTypeDesktop,
        protocolVersion: Int = 1,
        recordProfileVersion: Int = 1,
        isPairReady: Bool = false,
        supportsTrustedReconnect: Bool = false
    ) {
        self.name = name
        self.endpoint = endpoint
        self.presenceToken = presenceToken.lowercased()
        self.deviceType = deviceType
        self.protocolVersion = protocolVersion
        self.recordProfileVersion = recordProfileVersion
        self.isPairReady = isPairReady
        self.supportsTrustedReconnect = supportsTrustedReconnect
    }
}

/// Bonjour service and canonical TXT record contract for Apple platform discovery.
/// Enforces zero secret/identity leakage (`spki`, `pin`, `token`, `trust`, `fp` are forbidden in DNS-SD).
public enum BonjourService {
    public static let serviceType = "_glide._tcp"
    public static let serviceDomain = "local."
    public static let maxTxtBudgetBytes = 512
    public static let maxDisplayNameCodePoints = 64
    public static let maxDisplayNameUtf8Bytes = 128

    public static let deviceTypePhone = "phone"
    public static let deviceTypeTablet = "tablet"
    public static let deviceTypeDesktop = "desktop"
    public static let deviceTypeUnknown = "unknown"

    public static let validDeviceTypes: Set<String> = [
        deviceTypePhone,
        deviceTypeTablet,
        deviceTypeDesktop,
        deviceTypeUnknown
    ]

    public static let forbiddenKeys: Set<String> = [
        "spki",
        "pin",
        "token",
        "trust",
        "fp"
    ]

    public enum BonjourError: Error, Equatable, Sendable {
        case missingKey(String)
        case forbiddenKey(String)
        case unsupportedProtocolVersion(Int)
        case unsupportedRecordProfile(Int)
        case invalidPairingFlag(String)
        case invalidPresenceToken(String)
        case invalidDisplayName
        case invalidDeviceType(String)
        case invalidAttribute(String)
        case budgetExceeded(Int)
        case emptyEndpoint
    }

    /// Constructs the canonical `_glide._tcp` TXT record dictionary for Bonjour service registration.
    public static func buildTxtRecord(
        name: String,
        presenceTokenHex: String,
        deviceType: String = deviceTypeDesktop,
        isPairReady: Bool = false,
        supportsTrustedReconnect: Bool = true,
        version: Int = 1,
        recordProfile: Int = 1
    ) -> [String: String] {
        var record: [String: String] = [
            "v": String(version),
            "tv": String(recordProfile),
            "q": "1",
            "p": presenceTokenHex.lowercased(),
            "dn": name,
            "dt": deviceType
        ]
        if isPairReady {
            record["pair"] = "1"
        }
        if supportsTrustedReconnect {
            record["tr"] = "1"
        }
        return record
    }

    /// Parses and validates a TXT record dictionary received from a Bonjour browse result.
    public static func parseTxtRecord(_ dict: [String: String], endpoint: String) throws -> BonjourPeer {
        guard !endpoint.trimmingCharacters(in: .whitespaces).isEmpty else {
            throw BonjourError.emptyEndpoint
        }

        for key in dict.keys {
            let lowered = key.lowercased()
            if forbiddenKeys.contains(lowered) {
                throw BonjourError.forbiddenKey(lowered)
            }
        }

        let totalWireBytes = calculateWireBytes(dict)
        if totalWireBytes > maxTxtBudgetBytes {
            throw BonjourError.budgetExceeded(totalWireBytes)
        }

        guard let vString = dict["v"], let v = Int(vString) else {
            throw BonjourError.missingKey("v")
        }
        guard v == 1 else {
            throw BonjourError.unsupportedProtocolVersion(v)
        }

        guard let tvString = dict["tv"], let tv = Int(tvString) else {
            throw BonjourError.missingKey("tv")
        }
        guard tv == 1 else {
            throw BonjourError.unsupportedRecordProfile(tv)
        }

        guard let qString = dict["q"] else {
            throw BonjourError.missingKey("q")
        }
        guard qString == "1" else {
            throw BonjourError.invalidPairingFlag(qString)
        }

        guard let presence = dict["p"], !presence.isEmpty else {
            throw BonjourError.missingKey("p")
        }
        guard isValidPresenceHex(presence) else {
            throw BonjourError.invalidPresenceToken(presence)
        }

        guard let dn = dict["dn"], !dn.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw BonjourError.missingKey("dn")
        }
        guard dn.unicodeScalars.count <= maxDisplayNameCodePoints,
              dn.utf8.count <= maxDisplayNameUtf8Bytes else {
            throw BonjourError.invalidDisplayName
        }

        let dt = dict["dt"] ?? deviceTypeUnknown
        guard validDeviceTypes.contains(dt) else {
            throw BonjourError.invalidDeviceType(dt)
        }

        var isPairReady = false
        if let pairVal = dict["pair"] {
            guard pairVal == "1" else {
                throw BonjourError.invalidAttribute("pair")
            }
            isPairReady = true
        }

        var supportsTrustedReconnect = false
        if let trVal = dict["tr"] {
            guard trVal == "1" else {
                throw BonjourError.invalidAttribute("tr")
            }
            supportsTrustedReconnect = true
        }

        return BonjourPeer(
            name: dn,
            endpoint: endpoint,
            presenceToken: presence,
            deviceType: dt,
            protocolVersion: v,
            recordProfileVersion: tv,
            isPairReady: isPairReady,
            supportsTrustedReconnect: supportsTrustedReconnect
        )
    }

    public static func calculateWireBytes(_ dict: [String: String]) -> Int {
        var total = 0
        for (key, value) in dict {
            total += 1 + key.utf8.count + 1 + value.utf8.count
        }
        return total
    }

    private static func isValidPresenceHex(_ value: String) -> Bool {
        guard value.utf8.count == 32 else { return false }
        for byte in value.utf8 {
            let isDigit = byte >= 0x30 && byte <= 0x39
            let isLowerHex = byte >= 0x61 && byte <= 0x66
            if !isDigit && !isLowerHex {
                return false
            }
        }
        return true
    }
}
