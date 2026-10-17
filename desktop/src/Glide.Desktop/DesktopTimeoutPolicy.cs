using System.Diagnostics;
using Glide.Core.Codec;

namespace Glide.Desktop;

/// <summary>
/// Injectable desktop timeout and admission policy using monotonic time.
/// </summary>
public sealed record DesktopTimeoutPolicy
{
    public static DesktopTimeoutPolicy Default { get; } = new();

    public TimeSpan TcpConnectTimeout { get; init; } = TimeSpan.FromSeconds(5);
    public TimeSpan TlsHandshakeTimeout { get; init; } = TimeSpan.FromSeconds(15);
    public TimeSpan ProtocolPreambleTimeout { get; init; } = TimeSpan.FromSeconds(10);
    public TimeSpan MachineAuthenticationTimeout { get; init; } = TimeSpan.FromSeconds(15);
    public TimeSpan HumanApprovalTimeout { get; init; } = TimeSpan.FromSeconds(120);
    public TimeSpan NoPayloadProgressTimeout { get; init; } = TimeSpan.FromSeconds(60);
    public TimeSpan LocalStorageProgressTimeout { get; init; } = TimeSpan.FromSeconds(60);
    public TimeSpan TotalCandidateBudget { get; init; } = TimeSpan.FromSeconds(45);

    public TimeSpan PreambleAndSessionOpenTimeout => ProtocolPreambleTimeout;
    public TimeSpan MachineAuthTimeout => MachineAuthenticationTimeout;
    public TimeSpan TrustedCandidateBudget => TotalCandidateBudget;

    public int MaxEndpointCandidates { get; init; } = 8;
    public int MaxConcurrentAdmissions { get; init; } = 4;

    public TimeSpan MinFinalizationTimeout { get; init; } = TimeSpan.FromSeconds(120);
    public TimeSpan MaxFinalizationTimeout { get; init; } = TimeSpan.FromHours(6);
    public TimeSpan FinalizationBaseAllowance { get; init; } = TimeSpan.FromSeconds(60);
    public long FinalizationReferenceBytesPerSecond { get; init; } = 10L * 1024L * 1024L;
    public int FinalizationPassCount { get; init; } = 3;

    public TimeProvider TimeProvider { get; init; } = TimeProvider.System;

    /// <summary>
    /// Calculates the compatibility finalization deadline for a single item or batch:
    /// minimum 120 seconds, otherwise 60 seconds plus time for three full-file passes at 10 MiB/s,
    /// capped at an absolute maximum of six hours, using checked arithmetic.
    /// </summary>
    public TimeSpan CalculateFinalizationDeadline(long fileSizeBytes)
    {
        if (fileSizeBytes < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(fileSizeBytes), "File size cannot be negative.");
        }

        checked
        {
            long passCount = Math.Max(1, FinalizationPassCount);
            long referenceRate = Math.Max(1L, FinalizationReferenceBytesPerSecond);
            long totalPassBytes = fileSizeBytes * passCount;
            long passMilliseconds = (totalPassBytes * 1000L + referenceRate - 1L) / referenceRate;
            long baseMilliseconds = (long)FinalizationBaseAllowance.TotalMilliseconds;
            long calculatedMilliseconds = baseMilliseconds + passMilliseconds;

            long minMilliseconds = (long)MinFinalizationTimeout.TotalMilliseconds;
            long maxMilliseconds = (long)MaxFinalizationTimeout.TotalMilliseconds;

            if (calculatedMilliseconds < minMilliseconds)
            {
                calculatedMilliseconds = minMilliseconds;
            }

            if (calculatedMilliseconds > maxMilliseconds)
            {
                calculatedMilliseconds = maxMilliseconds;
            }

            return TimeSpan.FromMilliseconds(calculatedMilliseconds);
        }
    }

    public long GetMonotonicTimestamp() => TimeProvider.GetTimestamp();

    public TimeSpan GetElapsedTime(long startTimestamp) =>
        TimeProvider.GetElapsedTime(startTimestamp);

    public CancellationTokenSource CreateTimeoutTokenSource(TimeSpan timeout, CancellationToken linkedToken = default)
    {
        var cts = linkedToken.CanBeCanceled
            ? CancellationTokenSource.CreateLinkedTokenSource(linkedToken)
            : new CancellationTokenSource();

        if (timeout > TimeSpan.Zero && timeout != Timeout.InfiniteTimeSpan)
        {
            if (ReferenceEquals(TimeProvider, TimeProvider.System))
            {
                cts.CancelAfter(timeout);
            }
            else
            {
                ITimer? timer = null;
                timer = TimeProvider.CreateTimer(static state =>
                {
                    var target = (CancellationTokenSource)state!;
                    try { target.Cancel(); } catch (ObjectDisposedException) { }
                }, cts, timeout, Timeout.InfiniteTimeSpan);
                cts.Token.Register(() => timer?.Dispose());
            }
        }

        return cts;
    }
}
