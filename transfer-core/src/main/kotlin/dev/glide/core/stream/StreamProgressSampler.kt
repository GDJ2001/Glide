package dev.glide.core.stream

/**
 * Throttles stream progress updates during high-throughput transfers to bound coroutine
 * dispatch and UI rendering overhead in compliance with GLIDE_PERFORMANCE_PLAN (<= 5-10 Hz).
 *
 * Guarantees trailing-edge delivery so that the final exact byte count (EOF) is never dropped,
 * regardless of the time elapsed since the preceding sample.
 *
 * @param minIntervalMs Minimum elapsed wall-clock milliseconds between emitted progress samples (default: 100 ms = 10 Hz).
 * @param minBytesDelta Minimum bytes read between emitted progress samples (default: 256 KiB).
 * @param clock Millisecond timestamp provider.
 * @param onSample Callback invoked when a progress update is sampled.
 */
class StreamProgressSampler(
    val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    val minBytesDelta: Long = DEFAULT_MIN_BYTES_DELTA,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onSample: (bytesRead: Long) -> Unit
) {
    companion object {
        const val DEFAULT_MIN_INTERVAL_MS: Long = 100L // 10 Hz maximum sampling frequency
        const val DEFAULT_MIN_BYTES_DELTA: Long = 256 * 1024L // 256 KiB minimum step
    }

    private val lock = Any()
    private var lastSampleTime: Long = 0L
    private var lastSampleBytes: Long = 0L
    private var sampleCount: Int = 0

    val totalSamplesEmitted: Int
        get() = synchronized(lock) { sampleCount }

    /**
     * Records [bytesRead] and invokes [onSample] if the minimum interval and byte delta have elapsed.
     */
    fun onBytesRead(bytesRead: Long) {
        synchronized(lock) {
            val now = clock()
            if (lastSampleTime == 0L || (now - lastSampleTime >= minIntervalMs && bytesRead - lastSampleBytes >= minBytesDelta)) {
                lastSampleTime = now
                lastSampleBytes = bytesRead
                sampleCount++
                onSample(bytesRead)
            }
        }
    }

    /**
     * Guarantees delivery of the final byte count on stream completion / EOF.
     * If the final byte count was already sampled, this is a no-op.
     */
    fun onComplete(finalBytes: Long) {
        synchronized(lock) {
            if (lastSampleBytes != finalBytes || sampleCount == 0) {
                lastSampleBytes = finalBytes
                lastSampleTime = clock()
                sampleCount++
                onSample(finalBytes)
            }
        }
    }

    /**
     * Resets sampling history for stream retries or reuse.
     */
    fun reset() {
        synchronized(lock) {
            lastSampleTime = 0L
            lastSampleBytes = 0L
            sampleCount = 0
        }
    }
}
