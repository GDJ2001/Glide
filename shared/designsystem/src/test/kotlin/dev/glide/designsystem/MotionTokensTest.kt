package dev.glide.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Glide Design System motion tokens and timing specifications (GLIDE-041).
 * Verifies exact compliance with `GLIDE_DESIGN_SYSTEM.md:53-67`.
 */
class MotionTokensTest {

    @Test
    fun durations_conformToDocumentedRanges() {
        // Control press/selection: 80–120ms state change
        assertTrue("Control press duration must be between 80ms and 120ms",
            GlideMotion.ControlPressDurationMs in 80..120)

        // Card appearance / device discovered: 160–220ms fade
        assertTrue("Card appearance duration must be between 160ms and 220ms",
            GlideMotion.CardAppearanceDurationMs in 160..220)

        // Sheet/dialog: 220–300ms Material easing
        assertTrue("Dialog duration must be between 220ms and 300ms",
            GlideMotion.DialogDurationMs in 220..300)

        // Navigation: 200–300ms relationship-preserving transition
        assertTrue("Navigation duration must be between 200ms and 300ms",
            GlideMotion.NavigationDurationMs in 200..300)

        // Pairing / connection: Phase crossfade 180ms
        assertEquals(180, GlideMotion.PairingPhaseCrossfadeMs)

        // Transfer progress: Interpolate samples 150–250ms
        assertTrue("Transfer progress sample duration must be between 150ms and 250ms",
            GlideMotion.TransferProgressSampleMs in 150..250)

        // Completion: One 240ms check transition
        assertEquals(240, GlideMotion.CompletionCheckDurationMs)

        // Failure: Calm 160ms change
        assertEquals(160, GlideMotion.FailureChangeDurationMs)

        // Discovery: Slow 2.4s low-amplitude tonal breathing
        assertEquals(2400, GlideMotion.DiscoveryBreathingPeriodMs)
    }

    @Test
    fun standardEasings_areMaterialCompliant() {
        assertNotNull(GlideMotion.EnterEasing)
        assertNotNull(GlideMotion.ExitEasing)
        assertNotNull(GlideMotion.StandardEasing)

        // Standard easing is FastOutSlowInEasing
        assertEquals(FastOutSlowInEasing, GlideMotion.StandardEasing)

        // Enter easing starts at 0,0 and finishes decelerating into 1,1
        assertTrue(GlideMotion.EnterEasing is CubicBezierEasing)
        assertTrue(GlideMotion.ExitEasing is CubicBezierEasing)
    }

    @Test
    fun controlPressSpring_createsValidSpec() {
        val springSpec = GlideMotion.controlPressSpring<Float>()
        assertNotNull(springSpec)
    }

    @Test
    fun hapticsDefault_isNoOp() {
        // Calling NoOpGlideHaptics methods should execute safely without exceptions
        NoOpGlideHaptics.performSelection()
        NoOpGlideHaptics.performConfirmation()
        NoOpGlideHaptics.performSuccess()
        NoOpGlideHaptics.performError()
        NoOpGlideHaptics.performDeviceDiscovered()
    }
}
