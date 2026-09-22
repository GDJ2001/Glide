using System;
using Glide.Core.Remote;
using Xunit;

namespace Glide.Tests;

public class RelayQuotaTrackerTests
{
    [Fact]
    public void CheckTransferOffer_Within100MB_DoesNotThrow()
    {
        var tracker = new RelayQuotaTracker();

        // 50 MB is permitted
        tracker.CheckTransferOffer(50L * 1024L * 1024L);

        // Exactly 100 MB is permitted
        tracker.CheckTransferOffer(RelayQuotaTracker.MaxRelayBytesPerTransfer);
    }

    [Fact]
    public void CheckTransferOffer_Exceeding100MB_ThrowsRelayQuotaExceededException()
    {
        var tracker = new RelayQuotaTracker();
        long oversized = RelayQuotaTracker.MaxRelayBytesPerTransfer + 1L;

        var ex = Assert.Throws<RelayQuotaExceededException>(() => tracker.CheckTransferOffer(oversized));
        Assert.Equal(oversized, ex.RequestedBytes);
        Assert.Equal(RelayQuotaTracker.MaxRelayBytesPerTransfer, ex.MaxAllowedBytes);
    }

    [Fact]
    public void CheckTransferOffer_NegativeBytes_ThrowsArgumentOutOfRangeException()
    {
        var tracker = new RelayQuotaTracker();
        Assert.Throws<ArgumentOutOfRangeException>(() => tracker.CheckTransferOffer(-1));
    }

    [Fact]
    public void RecordBytesTransferred_AccumulatesBytes_AndEnforcesCeiling()
    {
        var tracker = new RelayQuotaTracker();

        tracker.RecordBytesTransferred(40L * 1024L * 1024L);
        Assert.Equal(40L * 1024L * 1024L, tracker.CumulativeBytesTransferred);

        tracker.RecordBytesTransferred(50L * 1024L * 1024L);
        Assert.Equal(90L * 1024L * 1024L, tracker.CumulativeBytesTransferred);

        // Adding 11 MB pushes total to 101 MB, which must throw
        Assert.Throws<RelayQuotaExceededException>(() => tracker.RecordBytesTransferred(11L * 1024L * 1024L));
    }

    [Fact]
    public void SessionConcurrency_AllowsUpTo3Sessions_RejectsFourth()
    {
        var tracker = new RelayQuotaTracker();

        Assert.True(tracker.TryAcquireSession("session-1"));
        Assert.True(tracker.TryAcquireSession("session-2"));
        Assert.True(tracker.TryAcquireSession("session-3"));
        Assert.Equal(3, tracker.ActiveSessionCount);

        // Same session re-acquire is idempotent
        Assert.True(tracker.TryAcquireSession("session-3"));

        // 4th distinct session violates concurrency limit
        var ex = Assert.Throws<RelayConcurrencyLimitExceededException>(() => tracker.TryAcquireSession("session-4"));
        Assert.Equal(3, ex.ActiveSessions);
        Assert.Equal(3, ex.MaxAllowedSessions);

        // Release one session, then 4th succeeds
        tracker.ReleaseSession("session-1");
        Assert.Equal(2, tracker.ActiveSessionCount);
        Assert.True(tracker.TryAcquireSession("session-4"));
        Assert.Equal(3, tracker.ActiveSessionCount);
    }

    [Fact]
    public void RateLimiting_AllowsUpTo10AttemptsPerMinute_RejectsEleventh()
    {
        var tracker = new RelayQuotaTracker();
        var baseTime = DateTimeOffset.UtcNow;

        for (int i = 1; i <= 10; i++)
        {
            tracker.RecordConnectionAttempt(baseTime.AddSeconds(i));
        }

        Assert.Equal(10, tracker.GetRecentAttemptCount(baseTime.AddSeconds(10)));

        // 11th attempt within same minute window throws
        var ex = Assert.Throws<RelayRateLimitExceededException>(() =>
            tracker.RecordConnectionAttempt(baseTime.AddSeconds(11)));
        Assert.Equal(10, ex.AttemptCount);
        Assert.Equal(10, ex.MaxAllowedAttempts);

        // Advancing time past 60s purges old attempts
        DateTimeOffset futureTime = baseTime.AddSeconds(65);
        tracker.RecordConnectionAttempt(futureTime);
        Assert.True(tracker.GetRecentAttemptCount(futureTime) <= 10);
    }

    [Fact]
    public void Reset_ClearsAllState()
    {
        var tracker = new RelayQuotaTracker();
        tracker.TryAcquireSession("session-1");
        tracker.RecordBytesTransferred(1024);
        tracker.RecordConnectionAttempt();

        tracker.Reset();

        Assert.Equal(0, tracker.ActiveSessionCount);
        Assert.Equal(0, tracker.CumulativeBytesTransferred);
        Assert.Equal(0, tracker.GetRecentAttemptCount());
    }
}
