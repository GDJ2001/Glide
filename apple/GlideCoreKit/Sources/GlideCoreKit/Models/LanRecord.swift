import Foundation

/// Record kinds defined for Glide LAN Transport Profile 1.
/// Wire framing: 5-byte header:
/// - byte 0: kind (UInt8)
/// - bytes 1..4: payloadLength (UInt32 big-endian)
public enum LanRecordKind: UInt8, Sendable, CaseIterable {
    case bootstrap = 0x01
    case bootstrapResult = 0x02
    case control = 0x10
    case data = 0x20
    case streamEnd = 0x21

    public var minPayloadSize: Int {
        switch self {
        case .bootstrap: return 1
        case .bootstrapResult: return 1
        case .control: return 1
        case .data: return 17 // 16-byte UUID + at least 1 byte data
        case .streamEnd: return 24 // 16-byte UUID + 8-byte actualBytes
        }
    }

    public var maxPayloadSize: Int {
        switch self {
        case .bootstrap: return 2048
        case .bootstrapResult: return 512
        case .control: return 16384 // 16 KiB maximum
        case .data: return 65536 // 64 KiB maximum
        case .streamEnd: return 24
        }
    }
}

/// Errors raised during LAN record framing, encoding, or decoding.
public enum LanRecordError: Error, Equatable, Sendable {
    case headerTooShort
    case unknownRecordKind(UInt8)
    case payloadOutOfBounds(kind: LanRecordKind, actual: Int, min: Int, max: Int)
    case payloadExceedsMaxCeiling(actual: Int, max: Int)
    case truncatedPayload(expected: Int, actual: Int)
    case trailingPayload(expected: Int, actual: Int)
    case invalidUuidLength
    case invalidStreamEndLength
}

/// Typed LAN Profile 1 on-wire records.
public enum LanRecord: Sendable, Equatable {
    case bootstrap(jsonBytes: Data)
    case bootstrapResult(jsonBytes: Data)
    case control(payload: Data)
    case data(bulkRef: UUID, chunk: Data)
    case streamEnd(bulkRef: UUID, actualBytes: UInt64)

    public var kind: LanRecordKind {
        switch self {
        case .bootstrap: return .bootstrap
        case .bootstrapResult: return .bootstrapResult
        case .control: return .control
        case .data: return .data
        case .streamEnd: return .streamEnd
        }
    }
}

/// Canonical codec for encoding and decoding LAN Profile 1 binary records.
public enum LanRecordCodec {
    public static let headerSize = 5
    public static let maxRecordPayloadSize = 65536

    /// Encodes a typed `LanRecord` into its on-wire 5-byte header + payload data frame.
    public static func encode(_ record: LanRecord) throws -> Data {
        let payload: Data
        switch record {
        case .bootstrap(let jsonBytes):
            payload = jsonBytes
        case .bootstrapResult(let jsonBytes):
            payload = jsonBytes
        case .control(let data):
            payload = data
        case .data(let bulkRef, let chunk):
            var data = Data(count: 16 + chunk.count)
            withUnsafeBytes(of: bulkRef.uuid) { uuidPtr in
                data.replaceSubrange(0..<16, with: uuidPtr)
            }
            data.replaceSubrange(16..<(16 + chunk.count), with: chunk)
            payload = data
        case .streamEnd(let bulkRef, let actualBytes):
            var data = Data(count: 24)
            withUnsafeBytes(of: bulkRef.uuid) { uuidPtr in
                data.replaceSubrange(0..<16, with: uuidPtr)
            }
            var beBytes = actualBytes.bigEndian
            withUnsafeBytes(of: &beBytes) { bytesPtr in
                data.replaceSubrange(16..<24, with: bytesPtr)
            }
            payload = data
        }

        let kind = record.kind
        if payload.count < kind.minPayloadSize || payload.count > kind.maxPayloadSize {
            throw LanRecordError.payloadOutOfBounds(
                kind: kind,
                actual: payload.count,
                min: kind.minPayloadSize,
                max: kind.maxPayloadSize
            )
        }

        var frame = Data(count: headerSize + payload.count)
        frame[0] = kind.rawValue
        var beLength = UInt32(payload.count).bigEndian
        withUnsafeBytes(of: &beLength) { lengthPtr in
            frame.replaceSubrange(1..<5, with: lengthPtr)
        }
        frame.replaceSubrange(headerSize..<(headerSize + payload.count), with: payload)
        return frame
    }

    /// Decodes an on-wire frame into a typed `LanRecord`.
    public static func decode(_ frame: Data) throws -> LanRecord {
        guard frame.count >= headerSize else {
            throw LanRecordError.headerTooShort
        }

        let kindByte = frame[0]
        guard let kind = LanRecordKind(rawValue: kindByte) else {
            throw LanRecordError.unknownRecordKind(kindByte)
        }

        let payloadLength = frame.dropFirst().prefix(4).reduce(UInt32(0)) {
            ($0 << 8) | UInt32($1)
        }

        let length = Int(payloadLength)
        guard length <= maxRecordPayloadSize else {
            throw LanRecordError.payloadExceedsMaxCeiling(actual: length, max: maxRecordPayloadSize)
        }

        guard length >= kind.minPayloadSize && length <= kind.maxPayloadSize else {
            throw LanRecordError.payloadOutOfBounds(
                kind: kind,
                actual: length,
                min: kind.minPayloadSize,
                max: kind.maxPayloadSize
            )
        }

        guard frame.count >= headerSize + length else {
            throw LanRecordError.truncatedPayload(expected: headerSize + length, actual: frame.count)
        }
        guard frame.count == headerSize + length else {
            throw LanRecordError.trailingPayload(expected: headerSize + length, actual: frame.count)
        }

        let payload = frame.subdata(in: headerSize..<(headerSize + length))

        switch kind {
        case .bootstrap:
            return .bootstrap(jsonBytes: payload)
        case .bootstrapResult:
            return .bootstrapResult(jsonBytes: payload)
        case .control:
            return .control(payload: payload)
        case .data:
            guard payload.count >= 17 else { throw LanRecordError.invalidUuidLength }
            let uuidBytes = Array(payload.prefix(16))
            let uuid = UUID(uuid: (
                uuidBytes[0], uuidBytes[1], uuidBytes[2], uuidBytes[3],
                uuidBytes[4], uuidBytes[5], uuidBytes[6], uuidBytes[7],
                uuidBytes[8], uuidBytes[9], uuidBytes[10], uuidBytes[11],
                uuidBytes[12], uuidBytes[13], uuidBytes[14], uuidBytes[15]
            ))
            let chunk = payload.subdata(in: 16..<payload.count)
            return .data(bulkRef: uuid, chunk: chunk)
        case .streamEnd:
            guard payload.count == 24 else { throw LanRecordError.invalidStreamEndLength }
            let uuidBytes = Array(payload.prefix(16))
            let uuid = UUID(uuid: (
                uuidBytes[0], uuidBytes[1], uuidBytes[2], uuidBytes[3],
                uuidBytes[4], uuidBytes[5], uuidBytes[6], uuidBytes[7],
                uuidBytes[8], uuidBytes[9], uuidBytes[10], uuidBytes[11],
                uuidBytes[12], uuidBytes[13], uuidBytes[14], uuidBytes[15]
            ))
            let actualBytes = payload.suffix(8).reduce(UInt64(0)) {
                ($0 << 8) | UInt64($1)
            }
            return .streamEnd(bulkRef: uuid, actualBytes: actualBytes)
        }
    }
}
