using System.Text.Json.Nodes;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Transport.Lan;
using Glide.Transport.Framing;

namespace Glide.Desktop;

/// <summary>
/// Connection-local control-envelope discipline for Glide protocol v1.
///
/// Sequence numbers are independent per direction, start at 1, and increase exactly by one.
/// Because LAN profile 1 runs over an ordered TLS/TCP stream, any gap, replay, wrong session, or
/// unexpected message type is a protocol violation rather than something to reorder locally.
/// </summary>
internal sealed class DesktopControlSequence
{
    private static readonly TimeSpan DefaultControlTimeout =
        TimeSpan.FromSeconds(30);
    private static readonly TimeSpan DefaultRecordTimeout =
        TimeSpan.FromSeconds(60);

    private long _nextOutbound = 1;
    private long _nextInbound = 1;

    public Envelope Create(
        string type,
        SessionId sessionId,
        JsonObject body)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(type);
        ArgumentNullException.ThrowIfNull(body);

        return new Envelope(
            1,
            type,
            sessionId,
            new MessageId(
                Guid.NewGuid()
                    .ToString("D")
                    .ToLowerInvariant()),
            _nextOutbound++,
            body);
    }

    public Task<Envelope> ReadExpectedAsync(
        LanRecordChannel channel,
        string expectedType,
        SessionId sessionId,
        CancellationToken cancellationToken,
        TimeSpan? timeout = null) =>
        ReadExpectedOneOfAsync(
            channel,
            [expectedType],
            sessionId,
            cancellationToken,
            timeout);

    public async Task<Envelope> ReadExpectedOneOfAsync(
        LanRecordChannel channel,
        IReadOnlyCollection<string> expectedTypes,
        SessionId sessionId,
        CancellationToken cancellationToken,
        TimeSpan? timeout = null)
    {
        if (expectedTypes.Count == 0)
        {
            throw new ArgumentException(
                "At least one expected control type is required.",
                nameof(expectedTypes));
        }

        string expectedLabel = string.Join(" or ", expectedTypes);
        Envelope envelope = await ReadWithTimeoutAsync(
            token => channel.ReadEnvelopeAsync(token),
            timeout ?? DefaultControlTimeout,
            cancellationToken,
            $"Timed out waiting for {expectedLabel}.");

        if (!string.Equals(
                envelope.SessionId.Value,
                sessionId.Value,
                StringComparison.Ordinal))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                $"Control message used sessionId '{envelope.SessionId.Value}', expected '{sessionId.Value}'.");
        }

        if (envelope.Seq != _nextInbound)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                $"Inbound control sequence {envelope.Seq} received while expecting {_nextInbound}.");
        }

        if (!expectedTypes.Contains(
                envelope.Type,
                StringComparer.Ordinal))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                $"Expected {expectedLabel} at sequence {_nextInbound}, received {envelope.Type}.");
        }

        _nextInbound++;
        return envelope;
    }

    internal static readonly TimeSpan RecordTimeout = DefaultRecordTimeout;

    public static Task<LanRecord?> ReadRecordAsync(
        LanRecordChannel channel,
        CancellationToken cancellationToken,
        TimeSpan? timeout = null) =>
        ReadWithTimeoutAsync(
            token => channel.ReadRecordAsync(token),
            timeout ?? DefaultRecordTimeout,
            cancellationToken,
            "Timed out waiting for transfer data.");

    public static ValueTask<LanRecord?> ReadOwnedRecordWithDeadlineAsync(
        LanRecordChannel channel,
        BulkRef? expectedBulkRef,
        CancellationTokenSource slidingTimeoutCts,
        CancellationToken callerCancellationToken,
        TimeSpan? timeout = null)
    {
        slidingTimeoutCts.CancelAfter(timeout ?? DefaultRecordTimeout);
        return ReadOwnedCoreAsync(channel, expectedBulkRef, slidingTimeoutCts.Token, callerCancellationToken);
    }

    private static async ValueTask<LanRecord?> ReadOwnedCoreAsync(
        LanRecordChannel channel,
        BulkRef? expectedBulkRef,
        CancellationToken combinedToken,
        CancellationToken callerCancellationToken)
    {
        try
        {
            return await channel.ReadOwnedRecordAsync(expectedBulkRef, combinedToken)
                .ConfigureAwait(false);
        }
        catch (OperationCanceledException)
            when (!callerCancellationToken.IsCancellationRequested)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.Timeout,
                "Timed out waiting for transfer data.");
        }
    }

    public static Task<LanBootstrapResult> ReadBootstrapResultAsync(
        LanRecordChannel channel,
        CancellationToken cancellationToken) =>
        ReadWithTimeoutAsync(
            token => channel.ReadBootstrapResultAsync(token),
            DefaultControlTimeout,
            cancellationToken,
            "Timed out waiting for pairing bootstrap result.");

    private static async Task<T> ReadWithTimeoutAsync<T>(
        Func<CancellationToken, Task<T>> operation,
        TimeSpan timeout,
        CancellationToken cancellationToken,
        string timeoutMessage)
    {
        using var timeoutCts =
            CancellationTokenSource.CreateLinkedTokenSource(
                cancellationToken);
        timeoutCts.CancelAfter(timeout);

        try
        {
            return await operation(timeoutCts.Token)
                .ConfigureAwait(false);
        }
        catch (OperationCanceledException)
            when (!cancellationToken.IsCancellationRequested)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.Timeout,
                timeoutMessage);
        }
    }
}