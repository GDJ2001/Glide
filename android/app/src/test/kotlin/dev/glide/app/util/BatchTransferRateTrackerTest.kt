package dev.glide.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchTransferRateTrackerTest {

    private var virtualTimeMs: Long = 1000L

    private fun createTracker(
        minSampleIntervalMs: Long = 200L,
        stallTimeoutMs: Long = 2000L,
        emaAlpha: Double = 0.25
    ): BatchTransferRateTracker {
        return BatchTransferRateTracker(
            timeProvider = { virtualTimeMs },
            minSampleIntervalMs = minSampleIntervalMs,
            stallTimeoutMs = stallTimeoutMs,
            emaAlpha = emaAlpha
        )
    }

    @Test
    fun initialSample_reportsZeroSpeedAndEmitsUpdate() {
        val tracker = createTracker()
        val snapshot = tracker.updateProgress(cumulativeBatchBytes = 100L)

        assertEquals(0L, snapshot.speedBytesPerSec)
        assertFalse(snapshot.isStalled)
        assertTrue(snapshot.shouldEmitUiUpdate)
    }

    @Test
    fun consecutiveSamples_computesEmaSpeed() {
        val tracker = createTracker(emaAlpha = 0.5)

        // Baseline at t=1000
        tracker.updateProgress(cumulativeBatchBytes = 0L)

        // Advance 1 second at t=2000, 1,000,000 bytes transferred -> 1,000,000 B/s
        virtualTimeMs = 2000L
        val snap1 = tracker.updateProgress(cumulativeBatchBytes = 1_000_000L)
        assertEquals(1_000_000L, snap1.speedBytesPerSec)
        assertTrue(snap1.shouldEmitUiUpdate)

        // Advance 1 second at t=3000, 2,000,000 new bytes transferred -> instant rate 2,000,000 B/s
        // EMA: (1_000_000 * 0.5) + (2_000_000 * 0.5) = 1_500_000 B/s
        virtualTimeMs = 3000L
        val snap2 = tracker.updateProgress(cumulativeBatchBytes = 3_000_000L)
        assertEquals(1_500_000L, snap2.speedBytesPerSec)
        assertTrue(snap2.shouldEmitUiUpdate)
    }

    @Test
    fun subIntervalSamples_throttlesUiUpdates() {
        val tracker = createTracker(minSampleIntervalMs = 200L)

        // Baseline at t=1000
        tracker.updateProgress(cumulativeBatchBytes = 0L)

        // Only 50 ms elapsed (< 200 ms) -> throttled
        virtualTimeMs = 1050L
        val snap1 = tracker.updateProgress(cumulativeBatchBytes = 10_000L)
        assertFalse(snap1.shouldEmitUiUpdate)

        // 150 ms elapsed total (< 200 ms) -> throttled
        virtualTimeMs = 1150L
        val snap2 = tracker.updateProgress(cumulativeBatchBytes = 25_000L)
        assertFalse(snap2.shouldEmitUiUpdate)

        // 250 ms elapsed (> 200 ms) -> sample emitted
        virtualTimeMs = 1250L
        val snap3 = tracker.updateProgress(cumulativeBatchBytes = 50_000L)
        assertTrue(snap3.shouldEmitUiUpdate)
    }

    @Test
    fun itemBoundaryTransition_forcesImmediateUiUpdate() {
        val tracker = createTracker(minSampleIntervalMs = 200L)

        // Baseline item 1 at t=1000
        tracker.updateProgress(cumulativeBatchBytes = 0L, currentItemIndex = 1)

        // Only 50 ms elapsed, but item index transitions to 2 -> forces immediate update
        virtualTimeMs = 1050L
        val snapshot = tracker.updateProgress(cumulativeBatchBytes = 50_000L, currentItemIndex = 2)
        assertTrue(snapshot.shouldEmitUiUpdate)
    }

    @Test
    fun monotonicBatchRate_acrossItemBoundaries_preservesContinuousSpeed() {
        val tracker = createTracker(minSampleIntervalMs = 200L, emaAlpha = 0.5)

        // Item 1: 100 KB file
        virtualTimeMs = 1000L
        tracker.updateProgress(cumulativeBatchBytes = 0L, currentItemIndex = 1)

        virtualTimeMs = 2000L
        val snapItem1End = tracker.updateProgress(cumulativeBatchBytes = 100_000L, currentItemIndex = 1)
        assertEquals(100_000L, snapItem1End.speedBytesPerSec)

        // Item 2 starts: cumulative batch bytes increases from 100_000 to 200_000
        virtualTimeMs = 3000L
        val snapItem2 = tracker.updateProgress(cumulativeBatchBytes = 200_000L, currentItemIndex = 2)

        // Instant rate for 100_000 bytes over 1s is 100_000 B/s; EMA remains 100_000 B/s
        assertEquals(100_000L, snapItem2.speedBytesPerSec)
        assertFalse(snapItem2.isStalled)
    }

    @Test
    fun stallDetection_flagsStalledAfterTimeout_andRecoversOnNewBytes() {
        val tracker = createTracker(stallTimeoutMs = 2000L)

        // Baseline at t=1000
        virtualTimeMs = 1000L
        tracker.updateProgress(cumulativeBatchBytes = 500L)

        // Inactivity for 2100 ms (> 2000 ms stall timeout)
        virtualTimeMs = 3100L
        val stalledSnap = tracker.updateProgress(cumulativeBatchBytes = 500L)
        assertTrue("Tracker should flag isStalled when no bytes arrive for >= 2000ms", stalledSnap.isStalled)
        assertEquals(0L, stalledSnap.speedBytesPerSec)

        // Progress resumes with new bytes -> stall clears
        virtualTimeMs = 3300L
        val resumedSnap = tracker.updateProgress(cumulativeBatchBytes = 1000L)
        assertFalse("Stall should clear once new bytes arrive", resumedSnap.isStalled)
        assertTrue(resumedSnap.speedBytesPerSec > 0L)
    }

    @Test
    fun reset_clearsAllTrackingState() {
        val tracker = createTracker()

        virtualTimeMs = 1000L
        tracker.updateProgress(cumulativeBatchBytes = 1000L)
        virtualTimeMs = 2000L
        tracker.updateProgress(cumulativeBatchBytes = 2000L)

        tracker.reset()

        // After reset, first sample acts like initial sample
        virtualTimeMs = 5000L
        val snap = tracker.updateProgress(cumulativeBatchBytes = 2000L)
        assertEquals(0L, snap.speedBytesPerSec)
        assertFalse(snap.isStalled)
    }
}
