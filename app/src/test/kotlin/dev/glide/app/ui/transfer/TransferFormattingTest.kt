package dev.glide.app.ui.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferFormattingTest {

    @Test
    fun formatBytes_zeroAndNegative_returnsZeroBytes() {
        assertEquals("0 B", TransferFormatting.formatBytes(0L))
        assertEquals("0 B", TransferFormatting.formatBytes(-500L))
    }

    @Test
    fun formatBytes_subKilobyte_returnsExactBytes() {
        assertEquals("512 B", TransferFormatting.formatBytes(512L))
        assertEquals("1023 B", TransferFormatting.formatBytes(1023L))
    }

    @Test
    fun formatBytes_kilobytesMegabytesGigabytes() {
        assertEquals("1.00 KB", TransferFormatting.formatBytes(1024L))
        assertEquals("50.0 KB", TransferFormatting.formatBytes(50 * 1024L))
        assertEquals("500 KB", TransferFormatting.formatBytes(500 * 1024L))
        assertEquals("4.25 MB", TransferFormatting.formatBytes((4.25 * 1024 * 1024).toLong()))
        assertEquals("42.5 MB", TransferFormatting.formatBytes((42.5 * 1024 * 1024).toLong()))
        assertEquals("1.50 GB", TransferFormatting.formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun formatSpeed_normalStalledAndZero() {
        assertEquals("Calculating…", TransferFormatting.formatSpeed(0L))
        assertEquals("Calculating…", TransferFormatting.formatSpeed(-100L))
        assertEquals("Stalled", TransferFormatting.formatSpeed(10_000_000L, isStalled = true))
        assertEquals("Stalled", TransferFormatting.formatSpeed(0L, isStalled = true))

        val speedMb = TransferFormatting.formatSpeed(14_000_000L)
        assertTrue(speedMb.endsWith("/s"))
        assertTrue(speedMb.contains("MB/s"))
    }

    @Test
    fun formatEta_stalledAndZero_returnsDashesOrEstimating() {
        assertEquals("—", TransferFormatting.formatEta(10_000_000L, 1_000_000L, isStalled = true))
        assertEquals("—", TransferFormatting.formatEta(0L, 1_000_000L))
        assertEquals("Estimating…", TransferFormatting.formatEta(10_000_000L, 0L))
        assertEquals("Estimating…", TransferFormatting.formatEta(10_000_000L, -50L))
    }

    @Test
    fun formatEta_secondsMinutesHours() {
        // 30 seconds
        val etaSec = TransferFormatting.formatEta(30_000_000L, 1_000_000L)
        assertEquals("~30s remaining", etaSec)

        // 5 minutes (300s)
        val etaMin = TransferFormatting.formatEta(300_000_000L, 1_000_000L)
        assertEquals("~5m remaining", etaMin)

        // 1 hour 30 min (5400s)
        val etaHours = TransferFormatting.formatEta(5_400_000_000L, 1_000_000L)
        assertEquals("~1h 30m remaining", etaHours)
    }

    @Test
    fun formatPercent_clampingAndTabular() {
        assertEquals("0%", TransferFormatting.formatPercent(0f))
        assertEquals("0%", TransferFormatting.formatPercent(-0.2f))
        assertEquals("50%", TransferFormatting.formatPercent(0.50f))
        assertEquals("99%", TransferFormatting.formatPercent(0.999f))
        assertEquals("100%", TransferFormatting.formatPercent(1.0f))
        assertEquals("100%", TransferFormatting.formatPercent(1.5f))
    }
}
