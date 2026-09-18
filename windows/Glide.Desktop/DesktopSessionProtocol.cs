using System.Text.Json.Nodes;
using Glide.Core.Models;
using Glide.Transport.Framing;

namespace Glide.Desktop;

public sealed record DesktopProtocolDeadlines(
    TimeSpan Bootstrap,
    TimeSpan Hello,
    TimeSpan OfferPageGap,
    TimeSpan OfferDecision,
    TimeSpan ItemReady,
    TimeSpan FirstBulkBytes,
    TimeSpan BulkProgress,
    TimeSpan ItemReceipt,
    TimeSpan BatchResult)
{
    public static DesktopProtocolDeadlines Default { get; } = new(
        Bootstrap: TimeSpan.FromSeconds(30),
        Hello: TimeSpan.FromSeconds(10),
        OfferPageGap: TimeSpan.FromSeconds(10),
        OfferDecision: TimeSpan.FromSeconds(120),
        ItemReady: TimeSpan.FromSeconds(30),
        FirstBulkBytes: TimeSpan.FromSeconds(30),
        BulkProgress: TimeSpan.FromSeconds(60),
        ItemReceipt: TimeSpan.FromSeconds(120),
        BatchResult: TimeSpan.FromSeconds(10));
}

internal sealed class DesktopSessionProtocol
{
    private readonly SessionId _sessionId;
    private readonly HashSet<string> _incomingMessageIds = new(StringComparer.Ordinal);
    private long _nextOutgoingSeq = 1;
    private long _nextIncomingSeq = 1;

    public DesktopSessionProtocol(SessionId sessionId)
    {
        _sessionId = sessionId;
    }

    public SessionId SessionId => _sessionId;

    public Envelope Create(string type, JsonObject body) =>
        new(
            1,
            type,
            _sessionId,
            NewMessageId(),
            _nextOutgoingSeq++,
            body);

    public async Task<Envelope> ReadExpectedAsync(
        LanRecordChannel channel,
        string expectedType,
        TimeSpan timeout,
        CancellationToken cancellationToken,
        string? expectedBatchId = null,
        string? expectedItemId = null)
    {
        Envelope envelope = await WithDeadlineAsync(
            ct => channel.ReadEnvelopeAsync(ct),
            timeout,
            expectedType,
            cancellationToken);

        ValidateInbound(
            envelope,
            expectedType,
            expectedBatchId,
            expectedItemId);

        return envelope;
    }

    public void ValidateInbound(
        Envelope envelope,
        string expectedType,
        string? expectedBatchId = null,
        string? expectedItemId = null)
    {
        if (envelope.SessionId != _sessionId)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                $"Expected sessionId '{_sessionId.Value}' but received '{envelope.SessionId.Value}'.");
        }

        if (envelope.Seq != _nextIncomingSeq)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                $"Expected inbound seq {_nextIncomingSeq} but received {envelope.Seq}.");
        }

        if (!_incomingMessageIds.Add(envelope.MessageId.Value))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                $"Duplicate inbound messageId '{envelope.MessageId.Value}' is not valid in this live state.");
        }

        if (!string.Equals(envelope.Type, expectedType, StringComparison.Ordinal))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                $"Expected {expectedType} at seq {_nextIncomingSeq} but received {envelope.Type}.");
        }

        RequireCorrelation(envelope, "batchId", expectedBatchId);
        RequireCorrelation(envelope, "itemId", expectedItemId);
        _nextIncomingSeq++;
    }

    public static async Task<T> WithDeadlineAsync<T>(
        Func<CancellationToken, Task<T>> operation,
        TimeSpan timeout,
        string operationName,
        CancellationToken cancellationToken)
    {
        try
        {
            return await operation(cancellationToken)
                .WaitAsync(timeout, cancellationToken);
        }
        catch (TimeoutException ex)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.Timeout,
                $"{operationName} exceeded protocol deadline of {timeout.TotalSeconds:0.###} seconds.",
                ex);
        }
    }

    private static void RequireCorrelation(
        Envelope envelope,
        string field,
        string? expected)
    {
        if (expected == null)
        {
            return;
        }

        string actual = envelope.Body[field]?.ToString() ?? string.Empty;
        if (!string.Equals(actual, expected, StringComparison.Ordinal))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                $"{envelope.Type} {field} '{actual}' does not match expected '{expected}'.");
        }
    }

    private static MessageId NewMessageId() =>
        new(Guid.NewGuid().ToString("D").ToLowerInvariant());
}
