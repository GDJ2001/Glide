package dev.glide.core.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StreamProgressSamplerTest {

    @Test
    fun rapidProgressUpdates_areThrottledToSpecifiedInterval() {
        var simulatedTime = 1000L
        val emittedSamples = mutableListOf<Long>()

        val sampler = StreamProgressSampler(
            minIntervalMs = 100L,
            minBytesDelta = 256 * 1024L, // 256 KiB
            clock = { simulatedTime },
            onSample = { emittedSamples.add(it) }
        )

        // Initial sample at T=1000ms
        sampler.onBytesRead(256 * 1024L)
        assertEquals(1, emittedSamples.size)
        assertEquals(256 * 1024L, emittedSamples[0])

        // Rapid updates within 100ms window (T=1010..1090) should be suppressed
        for (ms in 10..90 step 10) {
            simulatedTime = 1000L + ms
            sampler.onBytesRead(256 * 1024L + (ms * 10000L))
        }
        assertEquals(1, emittedSamples.size) // No new samples emitted

        // T=1100ms (100ms elapsed) and >= 256 KiB delta: should emit sample
        simulatedTime = 1100L
        sampler.onBytesRead(1024 * 1024L) // 1 MiB
        assertEquals(2, emittedSamples.size)
        assertEquals(1024 * 1024L, emittedSamples[1])

        // Rapid updates again (T=1110..1190)
        for (ms in 110..190 step 10) {
            simulatedTime = 1000L + ms
            sampler.onBytesRead(1024 * 1024L + (ms * 10000L))
        }
        assertEquals(2, emittedSamples.size)

        // T=1201ms: next sample
        simulatedTime = 1201L
        sampler.onBytesRead(2 * 1024 * 1024L) // 2 MiB
        assertEquals(3, emittedSamples.size)
        assertEquals(2 * 1024 * 1024L, emittedSamples[2])
    }

    @Test
    fun guaranteedTrailingEdge_emitsFinalBytesEvenIfIntervalNotElapsed() {
        var simulatedTime = 1000L
        val emittedSamples = mutableListOf<Long>()

        val sampler = StreamProgressSampler(
            minIntervalMs = 100L,
            minBytesDelta = 256 * 1024L,
            clock = { simulatedTime },
            onSample = { emittedSamples.add(it) }
        )

        // Initial sample at T=1000ms
        sampler.onBytesRead(500_000L)
        assertEquals(listOf(500_000L), emittedSamples)

        // Stream finishes just 15ms later at T=1015ms with 1,234,567 bytes
        simulatedTime = 1015L
        sampler.onBytesRead(1_234_567L) // Within throttle window, suppressed by onBytesRead
        assertEquals(listOf(500_000L), emittedSamples)

        // onComplete guarantees trailing edge regardless of elapsed time
        sampler.onComplete(1_234_567L)
        assertEquals(listOf(500_000L, 1_234_567L), emittedSamples)
    }

    @Test
    fun onComplete_isNoOpIfFinalBytesAlreadySampled() {
        var simulatedTime = 1000L
        val emittedSamples = mutableListOf<Long>()

        val sampler = StreamProgressSampler(
            minIntervalMs = 100L,
            minBytesDelta = 256 * 1024L,
            clock = { simulatedTime },
            onSample = { emittedSamples.add(it) }
        )

        sampler.onBytesRead(1_000_000L)
        assertEquals(1, emittedSamples.size)

        // onComplete called with the exact same bytes
        sampler.onComplete(1_000_000L)
        assertEquals(1, emittedSamples.size)
    }

    @Test
    fun reset_clearsHistoryAndAllowsFreshSampling() {
        var simulatedTime = 1000L
        val emittedSamples = mutableListOf<Long>()

        val sampler = StreamProgressSampler(
            minIntervalMs = 100L,
            minBytesDelta = 256 * 1024L,
            clock = { simulatedTime },
            onSample = { emittedSamples.add(it) }
        )

        sampler.onBytesRead(500_000L)
        sampler.onComplete(1_000_000L)
        assertEquals(2, sampler.totalSamplesEmitted)

        sampler.reset()
        assertEquals(0, sampler.totalSamplesEmitted)

        // First sample after reset is accepted immediately
        sampler.onBytesRead(100L)
        assertEquals(3, emittedSamples.size)
        assertEquals(100L, emittedSamples[2])
    }

    @Test
    fun concurrentProgressCalls_areThreadSafe() {
        val emittedSamples = ConcurrentLinkedQueue<Long>()
        val sampler = StreamProgressSampler(
            minIntervalMs = 10L,
            minBytesDelta = 1024L,
            onSample = { emittedSamples.add(it) }
        )

        val executor = Executors.newFixedThreadPool(4)
        val iterations = 1000
        for (i in 1..iterations) {
            executor.submit {
                sampler.onBytesRead(i * 1024L)
            }
        }
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))

        sampler.onComplete(iterations * 1024L)
        assertTrue("Expected throttled samples", emittedSamples.size in 1..iterations)
        assertEquals(iterations * 1024L, emittedSamples.last())
    }
}
