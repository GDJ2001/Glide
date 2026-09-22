using System;
using System.Collections.Generic;

namespace Glide.Core.Remote;

/// <summary>
/// Thrown when a remote transfer exceeds the 100 MB blind relay bandwidth ceiling.
/// </summary>
public sealed class RelayQuotaExceededException : Exception
{
    public long RequestedBytes { get; }
    public long MaxAllowedBytes { get; }

    public RelayQuotaExceededException(long requestedBytes, long maxAllowedBytes)
        : base($"Relayed transfer of {requestedBytes} bytes exceeds the 100 MB quota ceiling ({maxAllowedBytes} bytes). Direct peer-to-peer or local Wi-Fi required.")
    {
        RequestedBytes = requestedBytes;
        MaxAllowedBytes = maxAllowedBytes;
    }
}

/// <summary>
/// Thrown when connection attempts to the blind relay exceed the rate limit (10 attempts / minute).
/// </summary>
public sealed class RelayRateLimitExceededException : Exception
{
    public int AttemptCount { get; }
    public int MaxAllowedAttempts { get; }

    public RelayRateLimitExceededException(int attemptCount, int maxAllowedAttempts)
        : base($"Relay connection rate limit exceeded: {attemptCount} attempts in the last minute (max {maxAllowedAttempts}).")
    {
        AttemptCount = attemptCount;
        MaxAllowedAttempts = maxAllowedAttempts;
    }
}

/// <summary>
/// Thrown when concurrent remote relay sessions exceed the maximum allowed limit (3 concurrent sessions).
/// </summary>
public sealed class RelayConcurrencyLimitExceededException : Exception
{
    public int ActiveSessions { get; }
    public int MaxAllowedSessions { get; }

    public RelayConcurrencyLimitExceededException(int activeSessions, int maxAllowedSessions)
        : base($"Relay concurrency limit exceeded: {activeSessions} active sessions (max {maxAllowedSessions}).")
    {
        ActiveSessions = activeSessions;
        MaxAllowedSessions = maxAllowedSessions;
    }
}

/// <summary>
/// Enforces anti-abuse policies, bandwidth ceilings, and session concurrency controls for remote TURN relays.
/// Conforms strictly to ADR-0022 and REMOTE_INTERNET_TRANSFER_SPECIFICATION.md Section 5.2.
/// </summary>
public sealed class RelayQuotaTracker
{
    /// <summary>
    /// Free community relay ceiling: 100 MB per transfer (104,857,600 bytes).
    /// </summary>
    public const long MaxRelayBytesPerTransfer = 100L * 1024L * 1024L;

    /// <summary>
    /// Maximum concurrent remote relay sessions per client IP.
    /// </summary>
    public const int MaxConcurrentSessions = 3;

    /// <summary>
    /// Maximum connection attempts per minute.
    /// </summary>
    public const int MaxAttemptsPerMinute = 10;

    private static readonly TimeSpan SlidingRateWindow = TimeSpan.FromMinutes(1);

    private readonly object _lock = new();
    private readonly HashSet<string> _activeSessions = new(StringComparer.Ordinal);
    private readonly List<DateTimeOffset> _connectionAttempts = new();
    private long _cumulativeBytesTransferred;

    /// <summary>
    /// Cumulative bytes transferred in the current session.
    /// </summary>
    public long CumulativeBytesTransferred
    {
        get { lock (_lock) return _cumulativeBytesTransferred; }
    }

    /// <summary>
    /// Number of currently active relay sessions.
    /// </summary>
    public int ActiveSessionCount
    {
        get { lock (_lock) return _activeSessions.Count; }
    }

    /// <summary>
    /// Validates whether a proposed transfer total byte count is within the 100 MB relay limit.
    /// </summary>
    public void CheckTransferOffer(long totalSizeBytes)
    {
        if (totalSizeBytes < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(totalSizeBytes), "Transfer size cannot be negative.");
        }

        if (totalSizeBytes > MaxRelayBytesPerTransfer)
        {
            throw new RelayQuotaExceededException(totalSizeBytes, MaxRelayBytesPerTransfer);
        }
    }

    /// <summary>
    /// Records transferred bytes and asserts that the cumulative volume has not exceeded the 100 MB quota ceiling.
    /// </summary>
    public void RecordBytesTransferred(long byteCount)
    {
        if (byteCount < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(byteCount), "Transferred byte count cannot be negative.");
        }

        lock (_lock)
        {
            long newTotal = _cumulativeBytesTransferred + byteCount;
            if (newTotal > MaxRelayBytesPerTransfer)
            {
                throw new RelayQuotaExceededException(newTotal, MaxRelayBytesPerTransfer);
            }
            _cumulativeBytesTransferred = newTotal;
        }
    }

    /// <summary>
    /// Attempts to register an active relay session, ensuring concurrency limits are upheld.
    /// </summary>
    public bool TryAcquireSession(string sessionId)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(sessionId);

        lock (_lock)
        {
            if (_activeSessions.Contains(sessionId))
            {
                return true;
            }

            if (_activeSessions.Count >= MaxConcurrentSessions)
            {
                throw new RelayConcurrencyLimitExceededException(_activeSessions.Count, MaxConcurrentSessions);
            }

            _activeSessions.Add(sessionId);
            return true;
        }
    }

    /// <summary>
    /// Releases an active relay session.
    /// </summary>
    public void ReleaseSession(string sessionId)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(sessionId);

        lock (_lock)
        {
            _activeSessions.Remove(sessionId);
        }
    }

    /// <summary>
    /// Records a new relay connection attempt and validates rate limits within the sliding 1-minute window.
    /// </summary>
    public void RecordConnectionAttempt(DateTimeOffset? timestamp = null)
    {
        DateTimeOffset now = timestamp ?? DateTimeOffset.UtcNow;

        lock (_lock)
        {
            PurgeStaleAttempts(now);

            if (_connectionAttempts.Count >= MaxAttemptsPerMinute)
            {
                throw new RelayRateLimitExceededException(_connectionAttempts.Count, MaxAttemptsPerMinute);
            }

            _connectionAttempts.Add(now);
        }
    }

    /// <summary>
    /// Retrieves the count of connection attempts within the current 1-minute sliding window.
    /// </summary>
    public int GetRecentAttemptCount(DateTimeOffset? timestamp = null)
    {
        DateTimeOffset now = timestamp ?? DateTimeOffset.UtcNow;
        lock (_lock)
        {
            PurgeStaleAttempts(now);
            return _connectionAttempts.Count;
        }
    }

    /// <summary>
    /// Resets all counters and session states.
    /// </summary>
    public void Reset()
    {
        lock (_lock)
        {
            _activeSessions.Clear();
            _connectionAttempts.Clear();
            _cumulativeBytesTransferred = 0;
        }
    }

    private void PurgeStaleAttempts(DateTimeOffset now)
    {
        DateTimeOffset cutoff = now - SlidingRateWindow;
        _connectionAttempts.RemoveAll(t => t < cutoff);
    }
}
