import Foundation

public actor LanControlSequence {
    private var outboundSeq: Int64 = 1
    private var expectedInboundSeq: Int64 = 1

    public init() {}

    public func createRecord(
        type: String,
        sessionId: String,
        body: [String: AnySendable],
        extensions: [String: AnySendable]? = nil
    ) throws -> LanRecord {
        let seq = outboundSeq
        outboundSeq += 1
        let envelope = try LanEnvelope(
            v: 1,
            type: type,
            sessionId: sessionId,
            messageId: UUID().uuidString.lowercased(),
            seq: seq,
            body: body,
            extensions: extensions
        )
        let data = try LanProtocolCodec.encode(envelope)
        return LanRecord.control(payload: data)
    }

    public func readEnvelope(
        from channel: LanRecordChannel,
        expectedType: String? = nil,
        sessionId: String
    ) async throws -> LanEnvelope {
        let record = try await channel.readRecord()
        guard case .control(let payload) = record else {
            throw LanProtocolError.protocolOrder("Expected control record, got: \(record.kind)")
        }

        let envelope = try LanProtocolCodec.decode(payload)
        guard envelope.sessionId == sessionId.lowercased() else {
            throw LanProtocolError.malformedMessage("SessionId mismatch: expected \(sessionId), got \(envelope.sessionId)")
        }

        if let expected = expectedType, envelope.type != expected {
            throw LanProtocolError.protocolOrder("Expected \(expected), got \(envelope.type)")
        }

        guard envelope.seq >= expectedInboundSeq else {
            throw LanProtocolError.protocolOrder("Inbound sequence \(envelope.seq) less than expected \(expectedInboundSeq)")
        }
        expectedInboundSeq = envelope.seq + 1
        return envelope
    }

    public func readEnvelopeOneOf(
        from channel: LanRecordChannel,
        expectedTypes: Set<String>,
        sessionId: String
    ) async throws -> LanEnvelope {
        let record = try await channel.readRecord()
        guard case .control(let payload) = record else {
            throw LanProtocolError.protocolOrder("Expected control record, got: \(record.kind)")
        }

        let envelope = try LanProtocolCodec.decode(payload)
        guard envelope.sessionId == sessionId.lowercased() else {
            throw LanProtocolError.malformedMessage("SessionId mismatch: expected \(sessionId), got \(envelope.sessionId)")
        }

        guard expectedTypes.contains(envelope.type) else {
            throw LanProtocolError.protocolOrder("Unexpected message type: \(envelope.type), expected one of \(expectedTypes)")
        }

        guard envelope.seq >= expectedInboundSeq else {
            throw LanProtocolError.protocolOrder("Inbound sequence \(envelope.seq) less than expected \(expectedInboundSeq)")
        }
        expectedInboundSeq = envelope.seq + 1
        return envelope
    }
}
