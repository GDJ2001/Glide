import Foundation
import CryptoKit
import Network

/// Errors encountered during parsing or generation of Glide pairing invitations.
public enum InvitationError: Error, Equatable, Sendable {
    case invalidUriPrefix
    case uriExceedsMaxCharacters(actual: Int, max: Int)
    case invalidBase64Url
    case jsonExceedsMaxBytes(actual: Int, max: Int)
    case missingRequiredField(String)
    case invalidSpkiHashLength(actual: Int)
    case invalidEndpointsCount(actual: Int)
    case unsupportedTransport(String)
    case invalidExpiresAt(String)
    case invitationExpired
    case invalidSchema
    case invalidToken
}

/// A bounded structural pass before JSONDecoder. JSONDecoder silently accepts unknown and
/// duplicate keys; both must be rejected before using an invitation for authentication.
private enum StrictInvitationKeys {
    static func validate(_ data: Data, rootKeys: Set<String>, endpointKeys: Set<String>) throws {
        var parser = Parser(bytes: Array(data))
        try parser.object(expected: rootKeys, endpointKeys: endpointKeys)
        parser.whitespace()
        guard parser.position == parser.bytes.count else { throw InvitationError.invalidSchema }
    }

    private struct Parser {
        let bytes: [UInt8]
        var position = 0

        mutating func whitespace() {
            while position < bytes.count && [UInt8(9), 10, 13, 32].contains(bytes[position]) {
                position += 1
            }
        }

        mutating func expect(_ byte: UInt8) throws {
            whitespace()
            guard position < bytes.count && bytes[position] == byte else {
                throw InvitationError.invalidSchema
            }
            position += 1
        }

        mutating func string() throws -> String {
            whitespace()
            let start = position
            try expect(34)
            var escaped = false
            while position < bytes.count {
                let byte = bytes[position]
                position += 1
                if escaped {
                    escaped = false
                } else if byte == 92 {
                    escaped = true
                } else if byte == 34 {
                    let fragment = Data(bytes[start..<position])
                    guard let value = try? JSONDecoder().decode(String.self, from: fragment) else {
                        throw InvitationError.invalidSchema
                    }
                    return value
                }
            }
            throw InvitationError.invalidSchema
        }

        mutating func scalar() throws {
            whitespace()
            guard position < bytes.count else { throw InvitationError.invalidSchema }
            if bytes[position] == 34 {
                _ = try string()
                return
            }
            guard bytes[position] != 123 && bytes[position] != 91 else {
                throw InvitationError.invalidSchema
            }
            let start = position
            while position < bytes.count &&
                    bytes[position] != 44 && bytes[position] != 125 && bytes[position] != 93 {
                position += 1
            }
            guard position > start else { throw InvitationError.invalidSchema }
        }

        mutating func endpointArray(expected: Set<String>) throws {
            try expect(91)
            whitespace()
            if position < bytes.count && bytes[position] == 93 {
                position += 1
                return
            }
            while true {
                try object(expected: expected, endpointKeys: nil)
                whitespace()
                guard position < bytes.count else { throw InvitationError.invalidSchema }
                let delimiter = bytes[position]
                position += 1
                if delimiter == 93 { return }
                guard delimiter == 44 else { throw InvitationError.invalidSchema }
            }
        }

        mutating func object(expected: Set<String>, endpointKeys: Set<String>?) throws {
            try expect(123)
            var seen = Set<String>()
            while true {
                whitespace()
                guard position < bytes.count else { throw InvitationError.invalidSchema }
                if bytes[position] == 125 {
                    position += 1
                    break
                }
                let key = try string()
                guard expected.contains(key) && seen.insert(key).inserted else {
                    throw InvitationError.invalidSchema
                }
                try expect(58)
                if key == "endpoints", let endpointKeys {
                    try endpointArray(expected: endpointKeys)
                } else {
                    try scalar()
                }
                whitespace()
                guard position < bytes.count else { throw InvitationError.invalidSchema }
                let delimiter = bytes[position]
                position += 1
                if delimiter == 125 { break }
                guard delimiter == 44 else { throw InvitationError.invalidSchema }
            }
            guard seen == expected else { throw InvitationError.invalidSchema }
        }
    }
}

/// Strict canonical codec for Glide QR Pairing invitations (LAN-006 Section 8, ADR-0020).
/// Encoding format: `glide://pair/v1#<unpadded-base64url(UTF-8 JSON)>`
public enum InvitationCodec {
    public static let uriPrefix = "glide://pair/v1#"
    public static let maxUriChars = 2768
    public static let maxJsonBytes = 2048
    private static let rootKeys: Set<String> = [
        "v", "transport", "profile", "protocol", "invitationId", "presence",
        "name", "platform", "endpoints", "spkiSha256", "token", "expiresAt", "ttlSeconds"
    ]
    private static let endpointKeys: Set<String> = ["address", "port"]

    /// Encodes a `LanInvitation` into the canonical QR pairing URI.
    public static func encode(_ invitation: LanInvitation) throws -> String {
        try validate(invitation)

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let jsonData = try encoder.encode(invitation)

        guard jsonData.count <= maxJsonBytes else {
            throw InvitationError.jsonExceedsMaxBytes(actual: jsonData.count, max: maxJsonBytes)
        }

        let base64Url = toUnpaddedBase64Url(jsonData)
        let uri = "\(uriPrefix)\(base64Url)"

        guard uri.count <= maxUriChars else {
            throw InvitationError.uriExceedsMaxCharacters(actual: uri.count, max: maxUriChars)
        }

        return uri
    }

    /// Strictly decodes a canonical QR pairing URI into a `LanInvitation`.
    public static func decode(_ uriString: String, currentEpochSeconds: Int64? = nil) throws -> LanInvitation {
        guard uriString.count <= maxUriChars else {
            throw InvitationError.uriExceedsMaxCharacters(actual: uriString.count, max: maxUriChars)
        }

        guard uriString.hasPrefix(uriPrefix) else {
            throw InvitationError.invalidUriPrefix
        }

        let base64Part = String(uriString.dropFirst(uriPrefix.count))
        guard let jsonData = fromUnpaddedBase64Url(base64Part) else {
            throw InvitationError.invalidBase64Url
        }

        guard jsonData.count <= maxJsonBytes else {
            throw InvitationError.jsonExceedsMaxBytes(actual: jsonData.count, max: maxJsonBytes)
        }

        try StrictInvitationKeys.validate(jsonData, rootKeys: rootKeys, endpointKeys: endpointKeys)

        // Calculate payload hash directly from decoded JSON bytes
        let digest = SHA256.hash(data: jsonData)
        let payloadHash = digest.map { String(format: "%02x", $0) }.joined()

        let decoder = JSONDecoder()
        let decoded: LanInvitation
        do {
            decoded = try decoder.decode(LanInvitation.self, from: jsonData)
        } catch {
            throw InvitationError.missingRequiredField(error.localizedDescription)
        }

        try validate(decoded)

        if decoded.isExpired(currentEpochSeconds: currentEpochSeconds) {
            throw InvitationError.invitationExpired
        }

        return decoded.withHash(payloadHash)
    }

    public static func toUnpaddedBase64Url(_ data: Data) -> String {
        return data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .trimmingCharacters(in: CharacterSet(charactersIn: "="))
    }

    public static func fromUnpaddedBase64Url(_ string: String) -> Data? {
        guard !string.isEmpty,
              string.utf8.allSatisfy({ byte in
                  (byte >= 65 && byte <= 90) || (byte >= 97 && byte <= 122) ||
                  (byte >= 48 && byte <= 57) || byte == 45 || byte == 95
              }) else { return nil }
        var base64 = string
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")

        let remainder = base64.count % 4
        if remainder > 0 {
            base64.append(String(repeating: "=", count: 4 - remainder))
        }

        guard let decoded = Data(base64Encoded: base64),
              toUnpaddedBase64Url(decoded) == string else { return nil }
        return decoded
    }

    private static func validate(_ invitation: LanInvitation) throws {
        guard invitation.endpoints.count >= 1 && invitation.endpoints.count <= 4 else {
            throw InvitationError.invalidEndpointsCount(actual: invitation.endpoints.count)
        }
        guard invitation.spkiSha256.count == 64 else {
            throw InvitationError.invalidSpkiHashLength(actual: invitation.spkiSha256.count)
        }
        guard invitation.transport == "lan-tls" else {
            throw InvitationError.unsupportedTransport(invitation.transport)
        }
        guard invitation.v == 1 && invitation.profile == 1 && invitation.protocolVersion == 1 &&
              invitation.ttlSeconds == 120 &&
              invitation.spkiSha256.utf8.allSatisfy({ ($0 >= 48 && $0 <= 57) || ($0 >= 97 && $0 <= 102) }) &&
              invitation.presence.count == 32 &&
              invitation.presence.utf8.allSatisfy({ ($0 >= 48 && $0 <= 57) || ($0 >= 97 && $0 <= 102) }) &&
              LanInvitation.allowedPlatforms.contains(invitation.platform) &&
              !invitation.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
              invitation.name.count <= 64 && invitation.name.utf8.count <= 128 &&
              invitation.invitationId.count == 36 &&
              invitation.invitationId == UUID(uuidString: invitation.invitationId)?.uuidString.lowercased() &&
              invitation.invitationId[invitation.invitationId.index(invitation.invitationId.startIndex, offsetBy: 14)] == "4" &&
              "89ab".contains(invitation.invitationId[invitation.invitationId.index(invitation.invitationId.startIndex, offsetBy: 19)]) &&
              Set(invitation.endpoints.map { "\($0.address):\($0.port)" }).count == invitation.endpoints.count &&
              invitation.endpoints.allSatisfy({
                  (IPv4Address($0.address) != nil || IPv6Address($0.address) != nil) &&
                  $0.port > 0 && $0.port <= 65535
              }) else {
            throw InvitationError.invalidSchema
        }
        guard invitation.token.count == 43,
              let tokenBytes = fromUnpaddedBase64Url(invitation.token),
              tokenBytes.count == 32 else { throw InvitationError.invalidToken }
        guard let expiry = Int64(invitation.expiresAt),
              expiry > 0,
              String(expiry) == invitation.expiresAt else {
            throw InvitationError.invalidExpiresAt(invitation.expiresAt)
        }
    }
}
