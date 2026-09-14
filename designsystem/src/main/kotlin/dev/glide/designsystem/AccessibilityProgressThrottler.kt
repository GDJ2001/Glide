package dev.glide.designsystem

/**
 * Throttles screen reader announcements for transfer progress complying with GLIDE_DESIGN_SYSTEM.md:
 * "transfer progress announcements at phase change or 10% increments at most, never 5Hz."
 */
class AccessibilityProgressThrottler(
    private var lastAnnouncedBucket: Int? = null,
    private var lastAnnouncedPhase: String? = null
) {
    /**
     * Evaluates whether a new announcement should be emitted.
     *
     * @param percent Current completion percentage [0..100].
     * @param phase Current transfer phase (e.g. "Sending", "Receiving", "Verifying", "Completed").
     * @param itemName Name of the active item being transferred (isolated for bidi safety).
     * @return Formatted announcement string if a 10% milestone or phase change occurred, or null if throttled.
     */
    fun checkAnnouncement(
        percent: Int,
        phase: String,
        itemName: String = ""
    ): String? {
        val clampedPercent = percent.coerceIn(0, 100)
        val bucket = (clampedPercent / 10) * 10

        val phaseChanged = phase != lastAnnouncedPhase
        val bucketChanged = bucket != lastAnnouncedBucket

        if (phaseChanged || bucketChanged) {
            lastAnnouncedBucket = bucket
            lastAnnouncedPhase = phase

            val itemPart = if (itemName.isNotBlank()) " for $itemName" else ""
            return if (phaseChanged && !bucketChanged) {
                "$phase$itemPart."
            } else {
                "$phase$itemPart: $bucket% complete."
            }
        }

        return null
    }

    /**
     * Resets the throttler state (e.g., when starting a new session or file).
     */
    fun reset() {
        lastAnnouncedBucket = null
        lastAnnouncedPhase = null
    }

    /**
     * Returns the currently active milestone bucket, or null if none announced yet.
     */
    val currentBucket: Int? get() = lastAnnouncedBucket

    /**
     * Returns the currently active phase, or null if none announced yet.
     */
    val currentPhase: String? get() = lastAnnouncedPhase
}
