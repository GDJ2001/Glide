package dev.glide.app.util

/**
 * Snapshot of smoothed transfer throughput and stall status (LAN-024).
 */
data class RateSnapshot(
    val speedBytesPerSec: Long,
    val isStalled: Boolean,
    val shouldEmitUiUpdate: Boolean
)

/**
 * Tracks batch-level transfer speed using an Exponential Moving Average (EMA) and
 * bounds UI update frequency to <= 5 Hz (200 ms interval) in compliance with GLIDE_PERFORMANCE_PLAN.
 *
 * Key guarantees:
 * - Monotonic batch rate: Cumulative bytes transferred across all items in a batch are tracked,
 *   preventing throughput and progress drops when transitioning across item boundaries.
 * - Exponential Moving Average (EMA): Smooths bursty network/socket reads with alpha = 0.25.
 * - Stall detection: Flags [isStalled] = true if elapsed time exceeds [stallTimeoutMs] (default 2,000 ms)
 *   without any bytes transferred during an active transfer.
 * - Rate-limited updates (<= 5 Hz): Suppresses redundant UI state updates within [minSampleIntervalMs] (200 ms)
 *   during continuous streaming, while supporting immediate emissions on phase or item boundaries.
 * - Virtual clock support: Accepts an injectable [timeProvider] for 100% deterministic testability.
 */
class BatchTransferRateTracker(
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    val minSampleIntervalMs: Long = DEFAULT_MIN_SAMPLE_INTERVAL_MS,
    val stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
    val emaAlpha: Double = DEFAULT_EMA_ALPHA
) {
    companion object {
        const val DEFAULT_MIN_SAMPLE_INTERVAL_MS = 200L // 5 Hz maximum UI update rate
        const val DEFAULT_STALL_TIMEOUT_MS = 2000L      // 2 seconds inactivity -> stalled
        const val DEFAULT_EMA_ALPHA = 0.25              // EMA weight on new sample
    }

    private var lastSampleTimeMs: Long = 0L
    private var lastBytes: Long = 0L
    private var lastActivityTimeMs: Long = 0L
    private var smoothedSpeed: Long = 0L
    private var isStalled: Boolean = false
    private var lastObservedItemIndex: Int = -1

    /**
     * Updates progress with current [cumulativeBatchBytes] and returns a [RateSnapshot].
     *
     * @param cumulativeBatchBytes Total bytes transferred across the entire batch so far.
     * @param currentItemIndex Current ordinal or index of the item within the batch (to detect item boundaries).
     * @param forceImmediate If true (e.g. on phase/item transition or completion), forces [RateSnapshot.shouldEmitUiUpdate] = true.
     */
    fun updateProgress(
        cumulativeBatchBytes: Long,
        currentItemIndex: Int = 1,
        forceImmediate: Boolean = false
    ): RateSnapshot {
        val now = timeProvider()

        // Detect item boundary transition
        val isItemBoundary = lastObservedItemIndex != -1 && currentItemIndex != lastObservedItemIndex
        lastObservedItemIndex = currentItemIndex

        if (lastSampleTimeMs == 0L) {
            lastSampleTimeMs = now
            lastActivityTimeMs = now
            lastBytes = cumulativeBatchBytes
            return RateSnapshot(
                speedBytesPerSec = 0L,
                isStalled = false,
                shouldEmitUiUpdate = true
            )
        }

        val elapsedMs = now - lastSampleTimeMs
        val bytesDelta = (cumulativeBatchBytes - lastBytes).coerceAtLeast(0L)

        if (bytesDelta > 0L) {
            lastActivityTimeMs = now
            isStalled = false
        } else if (now - lastActivityTimeMs >= stallTimeoutMs) {
            isStalled = true
            smoothedSpeed = 0L
        }

        val shouldSample = elapsedMs >= minSampleIntervalMs || forceImmediate || isItemBoundary

        if (shouldSample && elapsedMs > 0L) {
            if (bytesDelta > 0L) {
                val dtSec = elapsedMs / 1000.0
                val instantRate = (bytesDelta / dtSec).toLong()
                smoothedSpeed = if (smoothedSpeed == 0L) {
                    instantRate
                } else {
                    ((smoothedSpeed * (1.0 - emaAlpha)) + (instantRate * emaAlpha)).toLong()
                }
                lastBytes = cumulativeBatchBytes
                lastSampleTimeMs = now
            }
            return RateSnapshot(
                speedBytesPerSec = smoothedSpeed,
                isStalled = isStalled,
                shouldEmitUiUpdate = true
            )
        }

        return RateSnapshot(
            speedBytesPerSec = smoothedSpeed,
            isStalled = isStalled,
            shouldEmitUiUpdate = forceImmediate || isItemBoundary
        )
    }

    /**
     * Resets tracker state on session start, retry, or teardown.
     */
    fun reset() {
        lastSampleTimeMs = 0L
        lastBytes = 0L
        lastActivityTimeMs = 0L
        smoothedSpeed = 0L
        isStalled = false
        lastObservedItemIndex = -1
    }
}
