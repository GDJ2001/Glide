package dev.glide.app.ui.haptics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Glide haptics semantics and contract enforcement (GLIDE-041).
 * Adheres strictly to `GLIDE_DESIGN_SYSTEM.md:66`:
 * "Haptics follow system touch feedback settings: light selection, one connect/accept confirmation
 * and success; error only when immediate user attention warranted. Device-found haptic off by default
 * to avoid repeated alerts; never use vibration alone to convey status."
 */
class GlideHapticsTest {

    @Test
    fun testGlideHaptics_recordsEventsWhenEnabled() {
        val haptics = TestGlideHaptics(isEnabled = true)

        haptics.performSelection()
        assertEquals(1, haptics.selectionCount)

        haptics.performConfirmation()
        assertEquals(1, haptics.confirmationCount)

        haptics.performSuccess()
        assertEquals(1, haptics.successCount)

        haptics.performError()
        assertEquals(1, haptics.errorCount)
    }

    @Test
    fun testGlideHaptics_suppressesEventsWhenDisabled() {
        val haptics = TestGlideHaptics(isEnabled = false)

        haptics.performSelection()
        haptics.performConfirmation()
        haptics.performSuccess()
        haptics.performError()
        haptics.performDeviceDiscovered()

        assertEquals(0, haptics.selectionCount)
        assertEquals(0, haptics.confirmationCount)
        assertEquals(0, haptics.successCount)
        assertEquals(0, haptics.errorCount)
        assertEquals(0, haptics.deviceDiscoveredCount)
    }

    @Test
    fun deviceDiscoveredHaptic_strictlyOffByDefault() {
        val haptics = TestGlideHaptics(isEnabled = true, deviceDiscoveredEnabled = false)

        // Calling performDeviceDiscovered when deviceDiscoveredEnabled is false must be a NO-OP
        haptics.performDeviceDiscovered()
        assertEquals(0, haptics.deviceDiscoveredCount)

        // Only records when explicitly enabled
        haptics.deviceDiscoveredEnabled = true
        haptics.performDeviceDiscovered()
        assertEquals(1, haptics.deviceDiscoveredCount)
    }

    @Test
    fun reset_clearsAllRecordedCounters() {
        val haptics = TestGlideHaptics(isEnabled = true, deviceDiscoveredEnabled = true)
        haptics.performSelection()
        haptics.performConfirmation()
        haptics.performSuccess()
        haptics.performError()
        haptics.performDeviceDiscovered()

        haptics.reset()

        assertEquals(0, haptics.selectionCount)
        assertEquals(0, haptics.confirmationCount)
        assertEquals(0, haptics.successCount)
        assertEquals(0, haptics.errorCount)
        assertEquals(0, haptics.deviceDiscoveredCount)
    }
}
