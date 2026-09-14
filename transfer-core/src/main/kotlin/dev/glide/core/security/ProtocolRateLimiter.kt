package dev.glide.core.security

import dev.glide.core.domain.PeerId
import java.util.Collections

/**
 * Result of rate limit evaluation.
 */
sealed interface RateLimitResult {
    data object Allowed : RateLimitResult
    data class Throttled(val waitMs: Long, val reason: String) : RateLimitResult
}

/**
 * Token-bucket rate limiter enforcing control rate limits conforming to GLIDE_PROTOCOL_V1.md:
 * "Control rate: <=20 messages/second sustained, burst 40; legitimate local progress never sent at frame rate."
 *
 * @param sustainedRatePerSec Number of message tokens added per second (default 20.0).
 * @param burstCapacity Maximum burst tokens accumulated (default 40.0).
 * @param clock Time provider returning monotonic or epoch milliseconds.
 */
class ProtocolRateLimiter(
    private val sustainedRatePerSec: Double = 20.0,
    private val burstCapacity: Double = 40.0,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var availableTokens: Double = burstCapacity
    private var lastRefillMs: Long = clock()
    private val lock = Any()

    /**
     * Attempts to acquire [cost] tokens for an incoming or outgoing control message.
     *
     * @return [RateLimitResult.Allowed] if sufficient tokens were available,
     *         or [RateLimitResult.Throttled] with the duration in ms before tokens replenish.
     */
    fun tryAcquire(cost: Double = 1.0, nowMs: Long = clock()): RateLimitResult = synchronized(lock) {
        refill(nowMs)
        return if (availableTokens >= cost) {
            availableTokens -= cost
            RateLimitResult.Allowed
        } else {
            val deficit = cost - availableTokens
            val waitMs = ((deficit / sustainedRatePerSec) * 1000.0).toLong().coerceAtLeast(1L)
            RateLimitResult.Throttled(
                waitMs = waitMs,
                reason = "Sustained control rate limit exceeded (burst: $burstCapacity, sustained: $sustainedRatePerSec/s)"
            )
        }
    }

    /**
     * Resets the rate limiter to full capacity (e.g. for a new connection).
     */
    fun reset(nowMs: Long = clock()) = synchronized(lock) {
        availableTokens = burstCapacity
        lastRefillMs = nowMs
    }

    /**
     * Returns current available token balance.
     */
    val tokens: Double
        get() = synchronized(lock) {
            refill(clock())
            availableTokens
        }

    private fun refill(nowMs: Long) {
        val elapsedMs = (nowMs - lastRefillMs).coerceAtLeast(0L)
        if (elapsedMs > 0) {
            val refillTokens = (elapsedMs / 1000.0) * sustainedRatePerSec
            availableTokens = (availableTokens + refillTokens).coerceAtMost(burstCapacity)
            lastRefillMs = nowMs
        }
    }
}

/**
 * Defends against nearby connection and request spam conforming to GLIDE_THREAT_MODEL.md (Threat T08):
 * "After 3 rejected/failed attempts in 60s pause incoming 30s; user can stop. Flood connects/offers/streams; no unbounded jobs/maps."
 */
class ConnectionSpamGuard(
    private val maxFailuresBeforeCooldown: Int = 3,
    private val failureWindowMs: Long = 60_000L, // 60 seconds
    private val cooldownDurationMs: Long = 30_000L, // 30 seconds
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private val failureHistory = mutableListOf<Long>()
    private var cooldownUntilMs: Long = 0L

    /**
     * Records a failed connection attempt (e.g. rejected auth code, handshake mismatch, or timeout).
     */
    fun recordFailure(peerId: PeerId? = null, nowMs: Long = clock()): Boolean = synchronized(lock) {
        pruneExpired(nowMs)
        failureHistory.add(nowMs)

        if (failureHistory.size >= maxFailuresBeforeCooldown) {
            cooldownUntilMs = nowMs + cooldownDurationMs
            failureHistory.clear()
            return true // Cooldown triggered
        }
        return false
    }

    /**
     * Records a successfully verified connection, clearing recent failure accumulation.
     */
    fun recordSuccess(peerId: PeerId? = null) = synchronized(lock) {
        failureHistory.clear()
        cooldownUntilMs = 0L
    }

    /**
     * Checks whether incoming connections are currently paused due to spam defense cooldown.
     *
     * @return 0L if allowed, or positive milliseconds remaining in cooldown if blocked.
     */
    fun checkCooldownRemaining(nowMs: Long = clock()): Long = synchronized(lock) {
        val remaining = cooldownUntilMs - nowMs
        if (remaining > 0L) remaining else 0L
    }

    /**
     * Returns true if incoming connection requests should be accepted.
     */
    fun isIncomingAllowed(nowMs: Long = clock()): Boolean = checkCooldownRemaining(nowMs) == 0L

    /**
     * Returns true if incoming connection requests are currently blocked by cooldown.
     */
    fun isBlocked(nowMs: Long = clock()): Boolean = !isIncomingAllowed(nowMs)

    /**
     * Returns milliseconds remaining in cooldown pause.
     */
    fun cooldownRemainingMs(nowMs: Long = clock()): Long = checkCooldownRemaining(nowMs)

    /**
     * Resets all failure history and clears active cooldowns.
     */
    fun reset() = synchronized(lock) {
        failureHistory.clear()
        cooldownUntilMs = 0L
    }

    private fun pruneExpired(nowMs: Long) {
        val cutoff = nowMs - failureWindowMs
        failureHistory.removeAll { it < cutoff }
    }
}
