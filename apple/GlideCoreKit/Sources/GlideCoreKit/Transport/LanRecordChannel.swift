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

/// Asynchronous length-prefixed frame channel operating over Apple Network.framework NWConnection.
/// Enforces the 5-byte record header, 16 KiB control ceiling, and 64 KiB bulk payload bounding.
public actor LanRecordChannel {
    private let connection: NWConnection

    public init(connection: NWConnection) {
        self.connection = connection
    }

    /// Reads exactly `count` bytes from the underlying TLS connection.
    public func readExact(count: Int) async throws -> Data {
        var accumulated = Data()
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
            throw LanRecordError.unknownRecordKind(headerData.first ?? 0)
        }

        // Data's storage need not be 32-bit aligned (including on Apple Silicon).
        // Decode the network-order prefix byte by byte before allocating payload data.
        let length = headerData.dropFirst().prefix(4).reduce(UInt32(0)) {
            ($0 << 8) | UInt32($1)
        }

        let payloadSize = Int(length)
        guard payloadSize <= LanRecordCodec.maxRecordPayloadSize else {
            throw LanRecordError.payloadExceedsMaxCeiling(actual: payloadSize, max: LanRecordCodec.maxRecordPayloadSize)
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
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: frameData, completion: .contentProcessed { error in
                if let error = error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            })
        }
    }

    /// Closes the connection.
    public func close() {
        connection.cancel()
    }
}
