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
        guard count >= 0, count <= LanRecordCodec.maxRecordPayloadSize else {
            throw LanChannelError.payloadTooLarge(count)
        }
        var accumulated = Data()
        while accumulated.count < count {
            let needed = count - accumulated.count
            print("DEBUG: [readExact] calling connection.receive for needed=\(needed)...")
            let chunk: Data = try await withCheckedThrowingContinuation { continuation in
                connection.receive(minimumIncompleteLength: 1, maximumLength: needed) { content, _, isComplete, error in
                    if let error = error {
                        print("DEBUG: [readExact] connection.receive error: \(error)")
                        continuation.resume(throwing: error)
                        return
                    }
                    if let data = content, !data.isEmpty {
                        print("DEBUG: [readExact] connection.receive got \(data.count) bytes (isComplete=\(isComplete))")
                        continuation.resume(returning: data)
                    } else if isComplete {
                        print("DEBUG: [readExact] connection.receive EOF isComplete=true")
                        continuation.resume(throwing: LanChannelError.connectionClosed)
                    } else {
                        print("DEBUG: [readExact] connection.receive empty data, returning Data()")
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
        print("DEBUG: [writeRecord] sending record kind=\(record.kind), len=\(frameData.count)...")
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: frameData, isComplete: false, completion: .contentProcessed { error in
                if let error = error {
                    print("DEBUG: [writeRecord] send error: \(error)")
                    continuation.resume(throwing: error)
                } else {
                    print("DEBUG: [writeRecord] send completed for kind=\(record.kind)")
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
