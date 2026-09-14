package dev.glide.core.security

import dev.glide.core.domain.PeerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolRateLimiterTest {

    @Test
    fun sustainedRateAndBurstAllowance() {
        var currentTimeMs = 1_000L
        val rateLimiter = ProtocolRateLimiter(
            sustainedRatePerSec = 20.0,
            burstCapacity = 40.0,
            clock = { currentTimeMs }
        )

        // Burst: exactly 40 messages should be allowed without delay
        for (i in 1..40) {
            val result = rateLimiter.tryAcquire(cost = 1.0, nowMs = currentTimeMs)
            assertTrue("Message #$i within burst should be allowed", result is RateLimitResult.Allowed)
        }

        // 41st message should be throttled
        val throttled = rateLimiter.tryAcquire(cost = 1.0, nowMs = currentTimeMs)
        assertTrue("Message #41 exceeding burst should be throttled", throttled is RateLimitResult.Throttled)
        val throttledResult = throttled as RateLimitResult.Throttled
        assertTrue("Wait ms should be positive", throttledResult.waitMs > 0)
    }

    @Test
    fun replenishmentOverTime() {
        var currentTimeMs = 1_000L
        val rateLimiter = ProtocolRateLimiter(
            sustainedRatePerSec = 20.0,
            burstCapacity = 40.0,
            clock = { currentTimeMs }
        )

        // Drain burst capacity
        for (i in 1..40) {
            rateLimiter.tryAcquire(cost = 1.0, nowMs = currentTimeMs)
        }
        assertTrue(rateLimiter.tryAcquire(cost = 1.0, nowMs = currentTimeMs) is RateLimitResult.Throttled)

        // Advance 1 second (1000ms) -> refills 20 tokens
        currentTimeMs += 1_000L

        for (i in 1..20) {
            val res = rateLimiter.tryAcquire(cost = 1.0, nowMs = currentTimeMs)
            assertTrue("Token #$i after 1s replenishment should be allowed", res is RateLimitResult.Allowed)
        }

        // 21st should now be throttled again
        val next = rateLimiter.tryAcquire(cost = 1.0, nowMs = currentTimeMs)
        assertTrue("Token #21 should be throttled", next is RateLimitResult.Throttled)
    }

    @Test
    fun resetReplenishesBurstCapacity() {
        var currentTimeMs = 1_000L
        val rateLimiter = ProtocolRateLimiter(
            sustainedRatePerSec = 20.0,
            burstCapacity = 40.0,
            clock = { currentTimeMs }
        )

        for (i in 1..40) {
            rateLimiter.tryAcquire(cost = 1.0, nowMs = currentTimeMs)
        }
        assertTrue(rateLimiter.tryAcquire(cost = 1.0, nowMs = currentTimeMs) is RateLimitResult.Throttled)

        rateLimiter.reset(nowMs = currentTimeMs)
        assertEquals(40.0, rateLimiter.tokens, 0.001)

        val allowed = rateLimiter.tryAcquire(cost = 1.0, nowMs = currentTimeMs)
        assertTrue("Should be allowed after reset", allowed is RateLimitResult.Allowed)
    }

    @Test
    fun connectionSpamGuardAllowsNormalFailuresUnderThreshold() {
        var currentTimeMs = 10_000L
        val guard = ConnectionSpamGuard(
            maxFailuresBeforeCooldown = 3,
            failureWindowMs = 60_000L,
            cooldownDurationMs = 30_000L,
            clock = { currentTimeMs }
        )

        val peer = PeerId("test-peer-1")
        assertFalse(guard.isBlocked(nowMs = currentTimeMs))

        // First failure: no cooldown
        assertFalse(guard.recordFailure(peer, nowMs = currentTimeMs))
        assertFalse(guard.isBlocked(nowMs = currentTimeMs))

        // Second failure: no cooldown
        currentTimeMs += 10_000L
        assertFalse(guard.recordFailure(peer, nowMs = currentTimeMs))
        assertFalse(guard.isBlocked(nowMs = currentTimeMs))
    }

    @Test
    fun connectionSpamGuardTriggersCooldownOnThirdFailure() {
        var currentTimeMs = 10_000L
        val guard = ConnectionSpamGuard(
            maxFailuresBeforeCooldown = 3,
            failureWindowMs = 60_000L,
            cooldownDurationMs = 30_000L,
            clock = { currentTimeMs }
        )

        val peer = PeerId("spam-peer")
        assertFalse(guard.recordFailure(peer, nowMs = currentTimeMs))
        assertFalse(guard.recordFailure(peer, nowMs = currentTimeMs + 5_000L))

        // Third failure triggers 30s cooldown
        val triggered = guard.recordFailure(peer, nowMs = currentTimeMs + 10_000L)
        assertTrue("Third failure within 60s must trigger cooldown", triggered)
        assertTrue("Spam guard must be blocked during cooldown", guard.isBlocked(nowMs = currentTimeMs + 10_000L))
        assertEquals(30_000L, guard.cooldownRemainingMs(nowMs = currentTimeMs + 10_000L))

        // Advance 20 seconds: still blocked
        assertTrue(guard.isBlocked(nowMs = currentTimeMs + 30_000L))
        assertEquals(10_000L, guard.cooldownRemainingMs(nowMs = currentTimeMs + 30_000L))

        // Advance 31 seconds: cooldown expired
        assertFalse(guard.isBlocked(nowMs = currentTimeMs + 41_000L))
        assertEquals(0L, guard.cooldownRemainingMs(nowMs = currentTimeMs + 41_000L))
    }

    @Test
    fun connectionSpamGuardManualResetClearsBlock() {
        var currentTimeMs = 10_000L
        val guard = ConnectionSpamGuard(
            maxFailuresBeforeCooldown = 3,
            failureWindowMs = 60_000L,
            cooldownDurationMs = 30_000L,
            clock = { currentTimeMs }
        )

        guard.recordFailure(nowMs = currentTimeMs)
        guard.recordFailure(nowMs = currentTimeMs)
        guard.recordFailure(nowMs = currentTimeMs)
        assertTrue(guard.isBlocked(nowMs = currentTimeMs))

        guard.reset()
        assertFalse("Guard must unblock immediately on manual user stop/reset", guard.isBlocked(nowMs = currentTimeMs))
    }
}
