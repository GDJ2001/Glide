package dev.glide.app.ui.transfer

import java.util.Locale

/**
 * Pure formatting utilities for transfer progress, byte counts, throughput, and ETA.
 * Conforms to GLIDE_DESIGN_SYSTEM.md typography and tabular figure rules.
 */
object TransferFormatting {

    /**
     * Formats byte count to human-readable string with IEC/decimal decimal precision.
     * e.g., "0 B", "850 B", "1.4 KB", "42.5 MB", "1.25 GB".
     */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        if (bytes < 1024L) return "$bytes B"

        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt().coerceAtMost(4)
        val pre = "KMGT"[exp - 1]
        val value = bytes / Math.pow(1024.0, exp.toDouble())

        return if (value >= 100.0) {
            String.format(Locale.US, "%.0f %sB", value, pre)
        } else if (value >= 10.0) {
            String.format(Locale.US, "%.1f %sB", value, pre)
        } else {
            String.format(Locale.US, "%.2f %sB", value, pre)
        }
    }

    /**
     * Formats transfer throughput (bytes per second).
     * Returns "Stalled" if [isStalled] is true.
     * Returns "Calculating…" if throughput is <= 0L.
     * e.g., "14.2 MB/s", "820 KB/s".
     */
    fun formatSpeed(speedBytesPerSec: Long, isStalled: Boolean = false): String {
        if (isStalled) return "Stalled"
        if (speedBytesPerSec <= 0L) return "Calculating…"

        val formattedBytes = formatBytes(speedBytesPerSec)
        return "$formattedBytes/s"
    }

    /**
     * Formats estimated time remaining based on remaining bytes and current throughput.
     * Returns "—" if [isStalled] is true.
     * Returns "Estimating…" if speed is 0 or remaining bytes <= 0L while transfer is ongoing.
     * Formats as:
     * - "~Xs remaining" for < 60s
     * - "~Xm remaining" for < 60m
     * - "~Xh Ym remaining" for >= 60m
     */
    fun formatEta(remainingBytes: Long, speedBytesPerSec: Long, isStalled: Boolean = false): String {
        if (isStalled) return "—"
        if (remainingBytes <= 0L) return "—"
        if (speedBytesPerSec <= 0L) return "Estimating…"

        val totalSeconds = (remainingBytes / speedBytesPerSec).coerceAtLeast(1L)

        return when {
            totalSeconds < 60L -> "~${totalSeconds}s remaining"
            totalSeconds < 3600L -> {
                val minutes = (totalSeconds + 59L) / 60L
                "~${minutes}m remaining"
            }
            else -> {
                val hours = totalSeconds / 3600L
                val minutes = (totalSeconds % 3600L + 59L) / 60L
                "~${hours}h ${minutes}m remaining"
            }
        }
    }

    /**
     * Formats progress fraction (0.0f .. 1.0f) as an integer percentage string ("0%" .. "100%").
     * Uses tabular figures format for layout stability.
     */
    fun formatPercent(fraction: Float): String {
        val clamped = fraction.coerceIn(0f, 1f)
        val percent = (clamped * 100).toInt()
        return "$percent%"
    }
}
