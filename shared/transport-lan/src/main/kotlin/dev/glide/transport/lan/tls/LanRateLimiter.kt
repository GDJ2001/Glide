package dev.glide.transport.lan.tls

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Rate limiter for inbound LAN connections and handshake failures (LAN-010, Section 6).
 *
 * Limits:
 * - 5 authentication/handshake failures per minute per remote address -> 30-second cooldown.
 * - 20 failures per minute overall -> 30-second global cooldown.
 */
class LanRateLimiter(
    private val maxFailuresPerAddress: Int = 5,
    private val maxTotalFailures: Int = 20,
    private val windowMillis: Long = 60_000L,
    private val cooldownMillis: Long = 30_000L
) {
    private val addressFailures = ConcurrentHashMap<InetAddress, MutableList<Long>>()
    private val addressCooldownUntil = ConcurrentHashMap<InetAddress, Long>()

    private val totalFailures = mutableListOf<Long>()
    private var globalCooldownUntil: Long = 0L

    @Synchronized
    fun isBlocked(address: InetAddress, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        if (nowEpochMs < globalCooldownUntil) return true

        val cooldown = addressCooldownUntil[address]
        if (cooldown != null && nowEpochMs < cooldown) return true

        return false
    }

    @Synchronized
    fun recordFailure(address: InetAddress, nowEpochMs: Long = System.currentTimeMillis()) {
        // Prune old timestamps
        val cutoff = nowEpochMs - windowMillis

        val list = addressFailures.computeIfAbsent(address) { mutableListOf() }
        list.removeAll { it < cutoff }
        list.add(nowEpochMs)

        if (list.size >= maxFailuresPerAddress) {
            addressCooldownUntil[address] = nowEpochMs + cooldownMillis
            list.clear()
        }

        totalFailures.removeAll { it < cutoff }
        totalFailures.add(nowEpochMs)

        if (totalFailures.size >= maxTotalFailures) {
            globalCooldownUntil = nowEpochMs + cooldownMillis
            totalFailures.clear()
        }
    }

    @Synchronized
    fun reset() {
        addressFailures.clear()
        addressCooldownUntil.clear()
        totalFailures.clear()
        globalCooldownUntil = 0L
    }
}
