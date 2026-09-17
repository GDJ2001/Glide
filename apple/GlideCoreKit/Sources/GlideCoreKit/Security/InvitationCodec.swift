import Foundation

/// Errors encountered during parsing or generation of Glide pairing invitations.
public enum InvitationError: Error, Equatable, Sendable {
    case invalidUriPrefix
    case uriExceedsMaxCharacters(actual: Int, max: Int)
    case invalidBase64Url
    case jsonExceedsMaxBytes(actual: Int, max: Int)
    case missingRequiredField(String)
    case invalidSpkiHashLength(actual: Int)
    case emptyEndpoints
    case invitationExpired
}

/// Strict canonical codec for Glide QR Pairing invitations (LAN-006 Section 8, ADR-0020).
/// Encoding format: `glide://pair/v1#<unpadded-base64url(UTF-8 JSON)>`
public enum InvitationCodec {
    public static let uriPrefix = "glide://pair/v1#"
    public static let maxUriChars = 2768
    public static let maxJsonBytes = 2048

    /// Encodes a `LanInvitation` into the canonical QR pairing URI.
    public static func encode(_ invitation: LanInvitation) throws -> String {
        guard !invitation.endpoints.isEmpty else {
            throw InvitationError.emptyEndpoints
        }
        guard invitation.spkiSha256.count == 64 else {
            throw InvitationError.invalidSpkiHashLength(actual: invitation.spkiSha256.count)
        }

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

        let decoder = JSONDecoder()
        let invitation: LanInvitation
        do {
            invitation = try decoder.decode(LanInvitation.self, from: jsonData)
        } catch {
            throw InvitationError.missingRequiredField(error.localizedDescription)
        }

        guard !invitation.endpoints.isEmpty else {
            throw InvitationError.emptyEndpoints
        }

        guard invitation.spkiSha256.count == 64 else {
            throw InvitationError.invalidSpkiHashLength(actual: invitation.spkiSha256.count)
        }

        if invitation.isExpired(currentEpochSeconds: currentEpochSeconds) {
            throw InvitationError.invitationExpired
        }

        return invitation
    }

    private static func toUnpaddedBase64Url(_ data: Data) -> String {
        return data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .trimmingCharacters(in: CharacterSet(charactersIn: "="))
    }

    private static func fromUnpaddedBase64Url(_ string: String) -> Data? {
        var base64 = string
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")

        let remainder = base64.count % 4
        if remainder > 0 {
            base64.append(String(repeating: "=", count: 4 - remainder))
        }

        return Data(base64Encoded: base64)
    }
}
