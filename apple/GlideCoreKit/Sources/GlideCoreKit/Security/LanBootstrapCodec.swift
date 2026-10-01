import Foundation

public enum LanBootstrapError: Error, Equatable, Sendable {
    case invalidSchema
    case payloadTooLarge
    case invalidToken
}

/// The first application record after pinned TLS. No file metadata is allowed
/// before the receiving authority atomically claims this one-use invitation.
public struct LanBootstrapMessage: Codable, Equatable, Sendable, CustomStringConvertible {
    public let v: Int
    public let invitationId: String
    public let token: String
    public let invitationHash: String
    public let sessionId: String
    public let role: String

    public var description: String {
        "LanBootstrapMessage(invitationId=[REDACTED], token=[REDACTED], invitationHash=[REDACTED], role=\(role))"
    }

    public init(invitationId: String, token: String, invitationHash: String,
                sessionId: String, role: String = "sender") throws {
        self.v = 1
        self.invitationId = invitationId
        self.token = token
        self.invitationHash = invitationHash
        self.sessionId = sessionId
        self.role = role
        try validate()
    }

    fileprivate func validate() throws {
        guard v == 1, Self.isUuidV4(invitationId), Self.isUuidV4(sessionId),
              role == "sender" || role == "receiver",
              invitationHash.count == 64,
              invitationHash.utf8.allSatisfy({ Self.isLowerHex($0) }) else {
            throw LanBootstrapError.invalidSchema
        }
        guard token.count == 43,
              InvitationCodec.fromUnpaddedBase64Url(token)?.count == 32 else {
            throw LanBootstrapError.invalidToken
        }
    }

    private static func isUuidV4(_ value: String) -> Bool {
        guard value.count == 36,
              UUID(uuidString: value)?.uuidString.lowercased() == value else { return false }
        let characters = Array(value)
        return characters[14] == "4" && "89ab".contains(characters[19])
    }

    private static func isLowerHex(_ byte: UInt8) -> Bool {
        (byte >= 48 && byte <= 57) || (byte >= 97 && byte <= 102)
    }
}

public struct LanBootstrapResult: Codable, Equatable, Sendable {
    public let v: Int
    public let status: String
    public let sessionId: String?
    public let code: String?

    private static let failureCodes: Set<String> = [
        "AUTHENTICATION_FAILED", "INVITATION_EXPIRED", "INVITATION_USED",
        "BUSY", "UNSUPPORTED_PROFILE"
    ]

    public static func accepted(sessionId: String) throws -> LanBootstrapResult {
        let result = LanBootstrapResult(v: 1, status: "accepted", sessionId: sessionId, code: nil)
        try result.validate()
        return result
    }

    public static func rejected(code: String) throws -> LanBootstrapResult {
        let result = LanBootstrapResult(v: 1, status: "rejected", sessionId: nil, code: code)
        try result.validate()
        return result
    }

    fileprivate func validate() throws {
        guard v == 1 else { throw LanBootstrapError.invalidSchema }
        if status == "accepted" {
            guard let sessionId, code == nil,
                  UUID(uuidString: sessionId)?.uuidString.lowercased() == sessionId,
                  Array(sessionId)[14] == "4",
                  "89ab".contains(Array(sessionId)[19]) else {
                throw LanBootstrapError.invalidSchema
            }
        } else if status == "rejected" {
            guard sessionId == nil, let code, Self.failureCodes.contains(code) else {
                throw LanBootstrapError.invalidSchema
            }
        } else {
            throw LanBootstrapError.invalidSchema
        }
    }
}

public enum LanBootstrapCodec {
    public static let maxBootstrapBytes = 2_048
    public static let maxResultBytes = 512
    private static let bootstrapKeys: Set<String> = [
        "v", "invitationId", "token", "invitationHash", "sessionId", "role"
    ]
    private static let resultKeys: Set<String> = ["v", "status", "sessionId", "code"]

    public static func encode(_ message: LanBootstrapMessage) throws -> Data {
        try message.validate()
        return try encodeBounded(message, maxBytes: maxBootstrapBytes)
    }

    public static func decodeBootstrap(_ data: Data) throws -> LanBootstrapMessage {
        guard !data.isEmpty, data.count <= maxBootstrapBytes else {
            throw LanBootstrapError.payloadTooLarge
        }
        try StrictJsonObjectKeys.validate(data, allowedKeys: bootstrapKeys,
                                          requiredKeys: bootstrapKeys)
        let message = try JSONDecoder().decode(LanBootstrapMessage.self, from: data)
        try message.validate()
        return message
    }

    public static func encode(_ result: LanBootstrapResult) throws -> Data {
        try result.validate()
        return try encodeBounded(result, maxBytes: maxResultBytes)
    }

    public static func decodeResult(_ data: Data) throws -> LanBootstrapResult {
        guard !data.isEmpty, data.count <= maxResultBytes else {
            throw LanBootstrapError.payloadTooLarge
        }
        try StrictJsonObjectKeys.validate(data, allowedKeys: resultKeys,
                                          requiredKeys: ["v", "status"])
        let result = try JSONDecoder().decode(LanBootstrapResult.self, from: data)
        try result.validate()
        return result
    }

    private static func encodeBounded<T: Encodable>(_ value: T, maxBytes: Int) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(value)
        guard data.count <= maxBytes else { throw LanBootstrapError.payloadTooLarge }
        return data
    }
}
