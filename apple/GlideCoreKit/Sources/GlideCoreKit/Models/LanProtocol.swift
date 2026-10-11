import Foundation

/// Wire protocol constants for Glide LAN Profile 1.
public enum LanMessageTypes {
    public static let hello = "HELLO"
    public static let helloAck = "HELLO_ACK"
    public static let offerBegin = "OFFER_BEGIN"
    public static let offerPage = "OFFER_PAGE"
    public static let offerEnd = "OFFER_END"
    public static let offerDecision = "OFFER_DECISION"
    public static let itemStart = "ITEM_START"
    public static let itemReady = "ITEM_READY"
    public static let itemEnd = "ITEM_END"
    public static let itemResult = "ITEM_RESULT"
    public static let batchEnd = "BATCH_END"
    public static let batchResult = "BATCH_RESULT"
    public static let cancel = "CANCEL"
    public static let cancelAck = "CANCEL_ACK"
    public static let error = "ERROR"
    public static let ping = "PING"
    public static let pong = "PONG"
    public static let identityChallenge = "IDENTITY_CHALLENGE"
    public static let identityResponse = "IDENTITY_RESPONSE"

    public static let all: Set<String> = [
        hello, helloAck,
        offerBegin, offerPage, offerEnd, offerDecision,
        itemStart, itemReady, itemEnd, itemResult,
        batchEnd, batchResult,
        cancel, cancelAck,
        error, ping, pong, identityChallenge, identityResponse
    ]
}

public enum LanWireErrorCodes {
    public static let unsupportedProtocol = "UNSUPPORTED_PROTOCOL"
    public static let malformedMessage = "MALFORMED_MESSAGE"
    public static let protocolOrder = "PROTOCOL_ORDER"
    public static let authenticationFailed = "AUTHENTICATION_FAILED"
    public static let userRejected = "USER_REJECTED"
    public static let userCancelled = "USER_CANCELLED"
    public static let systemCancelled = "SYSTEM_CANCELLED"
    public static let sourceUnavailable = "SOURCE_UNAVAILABLE"
    public static let readFailure = "READ_FAILURE"
    public static let destinationUnavailable = "DESTINATION_UNAVAILABLE"
    public static let writeFailure = "WRITE_FAILURE"
    public static let insufficientStorage = "INSUFFICIENT_STORAGE"
    public static let transferInterrupted = "TRANSFER_INTERRUPTED"
    public static let timeout = "TIMEOUT"
    public static let peerLost = "PEER_LOST"
    public static let integrityMismatch = "INTEGRITY_MISMATCH"
    public static let limitExceeded = "LIMIT_EXCEEDED"
    public static let busy = "BUSY"
}

public enum LanProtocolError: Error, Equatable, Sendable {
    case unsupportedProtocol(String)
    case malformedMessage(String)
    case protocolOrder(String)
    case limitExceeded(String)
    case invalidSessionId(String)
    case invalidMessageId(String)
    case invalidSeq(Int64)
}

/// Profile 1 control envelope structure.
public struct LanEnvelope: Equatable, Sendable {
    public let v: Int
    public let type: String
    public let sessionId: String
    public let messageId: String
    public let seq: Int64
    public let body: [String: AnySendable]
    public let extensions: [String: AnySendable]?

    public init(
        v: Int = 1,
        type: String,
        sessionId: String,
        messageId: String = UUID().uuidString.lowercased(),
        seq: Int64,
        body: [String: AnySendable],
        extensions: [String: AnySendable]? = nil
    ) throws {
        guard v == 1 else {
            throw LanProtocolError.unsupportedProtocol("Unsupported protocol version \(v), expected 1")
        }
        guard LanMessageTypes.all.contains(type) else {
            throw LanProtocolError.unsupportedProtocol("Unknown message type: \(type)")
        }
        guard Self.isValidUuidV4(sessionId) else {
            throw LanProtocolError.invalidSessionId("Invalid sessionId: \(sessionId)")
        }
        guard Self.isValidUuidV4(messageId) else {
            throw LanProtocolError.invalidMessageId("Invalid messageId: \(messageId)")
        }
        guard seq >= 1 else {
            throw LanProtocolError.invalidSeq(seq)
        }

        self.v = v
        self.type = type
        self.sessionId = sessionId.lowercased()
        self.messageId = messageId.lowercased()
        self.seq = seq
        self.body = body
        self.extensions = extensions
    }

    public static func isValidUuidV4(_ value: String) -> Bool {
        guard value.count == 36,
              UUID(uuidString: value)?.uuidString.lowercased() == value.lowercased() else { return false }
        let chars = Array(value.lowercased())
        return chars[14] == "4" && "89ab".contains(chars[19])
    }
}

#if canImport(CoreFoundation)
import CoreFoundation
#endif

/// Type-safe wrapper for arbitrary JSON values in Swift.
public enum AnySendable: Equatable, Sendable {
    case string(String)
    case int(Int64)
    case double(Double)
    case bool(Bool)
    case array([AnySendable])
    case object([String: AnySendable])
    case null

    public var stringValue: String? {
        switch self {
        case .string(let s): return s
        case .int(let i): return String(i)
        default: return nil
        }
    }

    public var intValue: Int64? {
        switch self {
        case .int(let i): return i
        case .string(let s): return Int64(s)
        case .double(let d): return Int64(d)
        default: return nil
        }
    }

    public var boolValue: Bool? {
        switch self {
        case .bool(let b): return b
        case .int(let i): return i != 0
        case .string(let s):
            if s.lowercased() == "true" { return true }
            if s.lowercased() == "false" { return false }
            return nil
        default: return nil
        }
    }

    public var arrayValue: [AnySendable]? {
        if case .array(let a) = self { return a }
        return nil
    }

    public var objectValue: [String: AnySendable]? {
        if case .object(let o) = self { return o }
        return nil
    }

    public static func fromAny(_ any: Any) -> AnySendable? {
        if any is NSNull { return .null }
        if let already = any as? AnySendable { return already }
        if let s = any as? String { return .string(s) }
        #if canImport(Foundation)
        if let num = any as? NSNumber {
            if CFGetTypeID(num as CFTypeRef) == CFBooleanGetTypeID() {
                return .bool(num.boolValue)
            } else {
                let doubleVal = num.doubleValue
                if doubleVal.rounded() == doubleVal && !doubleVal.isInfinite && !doubleVal.isNaN && doubleVal >= Double(Int64.min) && doubleVal <= Double(Int64.max) {
                    return .int(num.int64Value)
                }
                return .double(doubleVal)
            }
        }
        #endif
        if let b = any as? Bool { return .bool(b) }
        if let i = any as? Int64 { return .int(i) }
        if let i = any as? Int { return .int(Int64(i)) }
        if let d = any as? Double { return .double(d) }
        if let arr = any as? [Any] {
            return .array(arr.compactMap { fromAny($0) })
        }
        if let dict = any as? [String: Any] {
            var res = [String: AnySendable]()
            for (k, v) in dict {
                if let val = fromAny(v) { res[k] = val }
            }
            return .object(res)
        }
        return nil
    }

    public func toNative() -> Any {
        switch self {
        case .string(let s): return s
        case .int(let i): return i
        case .double(let d): return d
        case .bool(let b): return b
        case .array(let arr): return arr.map { $0.toNative() }
        case .object(let obj): return obj.mapValues { $0.toNative() }
        case .null: return NSNull()
        }
    }
}

/// Canonical codec for Profile 1 control envelopes.
public enum LanProtocolCodec {
    public static let maxControlBytes = 16_384
    public static let maxExtensionsBytes = 1_024
    private static let allowedEnvelopeKeys: Set<String> = [
        "v", "type", "sessionId", "messageId", "seq", "body", "extensions"
    ]

    public static func encode(_ envelope: LanEnvelope) throws -> Data {
        var dict: [String: Any] = [
            "v": envelope.v,
            "type": envelope.type,
            "sessionId": envelope.sessionId,
            "messageId": envelope.messageId,
            "seq": envelope.seq,
            "body": envelope.body.mapValues { $0.toNative() }
        ]
        if let ext = envelope.extensions {
            let extDict = ext.mapValues { $0.toNative() }
            let extData = try JSONSerialization.data(withJSONObject: extDict, options: [.sortedKeys])
            guard extData.count <= maxExtensionsBytes else {
                throw LanProtocolError.limitExceeded("Extensions exceed \(maxExtensionsBytes) bytes")
            }
            dict["extensions"] = extDict
        }

        let jsonData = try JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys])
        guard jsonData.count <= maxControlBytes else {
            throw LanProtocolError.limitExceeded("Control payload exceeds \(maxControlBytes) bytes")
        }
        return jsonData
    }

    public static func decode(_ data: Data) throws -> LanEnvelope {
        guard !data.isEmpty, data.count <= maxControlBytes else {
            throw LanProtocolError.limitExceeded("Payload empty or exceeds \(maxControlBytes) bytes")
        }

        // Prohibit UTF-8 BOM
        if data.count >= 3 && data[0] == 0xEF && data[1] == 0xBB && data[2] == 0xBF {
            throw LanProtocolError.malformedMessage("UTF-8 BOM is prohibited")
        }

        let json = try JSONSerialization.jsonObject(with: data, options: [])
        guard let dict = json as? [String: Any] else {
            throw LanProtocolError.malformedMessage("Root must be a JSON object")
        }

        for key in dict.keys {
            guard allowedEnvelopeKeys.contains(key) else {
                throw LanProtocolError.malformedMessage("Unknown envelope key: \(key)")
            }
        }

        guard let vNum = dict["v"] as? NSNumber else {
            throw LanProtocolError.malformedMessage("Missing or invalid field 'v'")
        }
        let v = vNum.intValue

        guard let type = dict["type"] as? String else {
            throw LanProtocolError.malformedMessage("Missing or invalid field 'type'")
        }

        guard let sessionId = dict["sessionId"] as? String else {
            throw LanProtocolError.malformedMessage("Missing or invalid field 'sessionId'")
        }

        guard let messageId = dict["messageId"] as? String else {
            throw LanProtocolError.malformedMessage("Missing or invalid field 'messageId'")
        }

        guard let seqNum = dict["seq"] as? NSNumber else {
            throw LanProtocolError.malformedMessage("Missing or invalid field 'seq'")
        }
        let seq = seqNum.int64Value

        guard let bodyDict = dict["body"] as? [String: Any] else {
            throw LanProtocolError.malformedMessage("Missing or invalid field 'body'")
        }

        var sendableBody = [String: AnySendable]()
        for (k, v) in bodyDict {
            guard let sendable = AnySendable.fromAny(v) else {
                throw LanProtocolError.malformedMessage("Invalid body value for key \(k)")
            }
            sendableBody[k] = sendable
        }

        var sendableExt: [String: AnySendable]? = nil
        if let extDict = dict["extensions"] as? [String: Any] {
            var parsedExt = [String: AnySendable]()
            for (k, v) in extDict {
                guard let sendable = AnySendable.fromAny(v) else {
                    throw LanProtocolError.malformedMessage("Invalid extension value for key \(k)")
                }
                parsedExt[k] = sendable
            }
            sendableExt = parsedExt
        }

        return try LanEnvelope(
            v: v,
            type: type,
            sessionId: sessionId,
            messageId: messageId,
            seq: seq,
            body: sendableBody,
            extensions: sendableExt
        )
    }
}
