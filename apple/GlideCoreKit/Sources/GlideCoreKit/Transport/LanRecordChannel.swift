import Foundation
import Network

public enum LanChannelError: Error, Equatable, Sendable {
    case connectionClosed
    case headerTooShort
    case payloadTooLarge(Int)
    case timedOut
    case cancelled
    case invalidRecord(String)
}

public enum LanSessionDispatchKind: Sendable, Equatable {
    case profile1LanRecord
    case profile2Gld2
    case invalid
}

/// Asynchronous length-prefixed frame channel operating over Apple Network.framework NWConnection.
/// Enforces the 5-byte record header, 16 KiB control ceiling, and 64 KiB bulk payload bounding.
public actor LanRecordChannel {
    private let connection: NWConnection
    public nonisolated let remoteSpkiSha256Hex: String?
    private var prefixBuffer = Data()

    public init(connection: NWConnection, remoteSpkiSha256Hex: String? = nil) {
        self.connection = connection
        self.remoteSpkiSha256Hex = remoteSpkiSha256Hex
    }

    /// Inspects the first 4 bytes of an inbound session to route Profile 2 (`"GLD2"`) vs Profile 1 (`LanRecordKind`).
    public func inspectSessionPreamble() async throws -> LanSessionDispatchKind {
        let firstFour = try await readExact(count: 4)
        if firstFour == Profile2Constants.gld2Magic {
            return .profile2Gld2
        }
        guard let firstByte = firstFour.first,
              LanRecordKind(rawValue: firstByte) != nil else {
            return .invalid
        }
        var combined = firstFour
        combined.append(prefixBuffer)
        prefixBuffer = combined
        return .profile1LanRecord
    }

    public func writeGld2Preamble() async throws {
        try await writeRaw(Profile2Constants.gld2Magic)
    }

    public func writeProfile2Message<T: Encodable>(_ message: T) async throws {
        let payload = try JSONEncoder().encode(message)
        guard !payload.isEmpty, payload.count <= Profile2Constants.maxControlPayloadBytes else {
            throw LanChannelError.payloadTooLarge(payload.count)
        }
        let len = UInt32(payload.count)
        var frame = Data([
            UInt8((len >> 24) & 0xFF),
            UInt8((len >> 16) & 0xFF),
            UInt8((len >> 8) & 0xFF),
            UInt8(len & 0xFF)
        ])
        frame.append(payload)
        try await writeRaw(frame)
    }

    public func readProfile2MessageData() async throws -> Data {
        let header = try await readExact(count: 4)
        let length = header.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        let count = Int(length)
        guard count > 0, count <= Profile2Constants.maxControlPayloadBytes else {
            throw LanChannelError.payloadTooLarge(count)
        }
        let payload = try await readExact(count: count)
        try Profile2WireValidator.validate(payload)
        return payload
    }

    /// Reads exactly `count` bytes from the underlying TLS connection (consuming any prefix buffer first).
    public func readExact(count: Int) async throws -> Data {
        guard count >= 0, count <= LanRecordCodec.maxRecordPayloadSize else {
            throw LanChannelError.payloadTooLarge(count)
        }
        var accumulated = Data()
        if !prefixBuffer.isEmpty {
            let take = min(count, prefixBuffer.count)
            accumulated.append(prefixBuffer.prefix(take))
            prefixBuffer.removeFirst(take)
        }
        while accumulated.count < count {
            let needed = count - accumulated.count
            let chunk: Data = try await withCheckedThrowingContinuation { continuation in
                connection.receive(minimumIncompleteLength: 1, maximumLength: needed) { content, _, isComplete, error in
                    if let error = error {
                        continuation.resume(throwing: error)
                        return
                    }
                    if let data = content, !data.isEmpty {
                        continuation.resume(returning: data)
                    } else if isComplete {
                        continuation.resume(throwing: LanChannelError.connectionClosed)
                    } else {
                        continuation.resume(returning: Data())
                    }
                }
            }
            if chunk.isEmpty && accumulated.count < count {
                throw LanChannelError.connectionClosed
            }
            accumulated.append(chunk)
        }
        return accumulated
    }

    /// Reads and decodes a typed `LanRecord` with strict framing checks.
    public func readRecord() async throws -> LanRecord {
        let headerData = try await readExact(count: LanRecordCodec.headerSize)
        guard let kindByte = headerData.first,
              let kind = LanRecordKind(rawValue: kindByte) else {
            connection.cancel()
            throw LanRecordError.unknownRecordKind(headerData.first ?? 0)
        }

        // Data's storage need not be 32-bit aligned (including on Apple Silicon).
        // Decode the network-order prefix byte by byte before allocating payload data.
        let length = headerData.dropFirst().prefix(4).reduce(UInt32(0)) {
            ($0 << 8) | UInt32($1)
        }

        let payloadSize = Int(length)
        guard payloadSize <= LanRecordCodec.maxRecordPayloadSize else {
            connection.cancel()
            throw LanRecordError.payloadExceedsMaxCeiling(actual: payloadSize, max: LanRecordCodec.maxRecordPayloadSize)
        }
        guard payloadSize >= kind.minPayloadSize, payloadSize <= kind.maxPayloadSize else {
            connection.cancel()
            throw LanRecordError.payloadOutOfBounds(kind: kind, actual: payloadSize,
                                                  min: kind.minPayloadSize, max: kind.maxPayloadSize)
        }

        let payloadData = try await readExact(count: payloadSize)
        var fullFrame = Data()
        fullFrame.append(headerData)
        fullFrame.append(payloadData)

        return try LanRecordCodec.decode(fullFrame)
    }

    /// Encodes and sends a `LanRecord` frame atomically.
    public func writeRecord(_ record: LanRecord) async throws {
        let frameData = try LanRecordCodec.encode(record)
        try await writeRaw(frameData)
    }

    public func writeRaw(_ frameData: Data) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: frameData, isComplete: false, completion: .contentProcessed { error in
                if let error = error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            })
        }
    }

    /// Closes the connection.
    nonisolated public func close() {
        connection.cancel()
    }
}
