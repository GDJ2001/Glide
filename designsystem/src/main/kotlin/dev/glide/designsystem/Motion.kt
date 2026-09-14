package dev.glide.designsystem

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf

/**
 * CompositionLocal indicating whether reduced motion is active (system scale 0 or user preference).
 * When true, animated transitions collapse to instantaneous state changes and decorative animations halt.
 */
val LocalReducedMotion = compositionLocalOf { false }

/**
 * Canonical motion tokens and timing curves for Glide.
 * Strictly adheres to `GLIDE_DESIGN_SYSTEM.md`:
 * - Control press/selection: 80–120ms state change, small standard spring with no bounce overshoot.
 * - Card appearance / device discovered: 160–220ms fade + <=8dp travel.
 * - Sheet/dialog: 220–300ms Material easing.
 * - Navigation: 200–300ms relationship-preserving transition.
 * - Pairing / connection: Phase crossfade 180ms.
 * - Transfer progress: Interpolate samples 150–250ms, linear within known byte progress.
 * - Completion: One 240ms check transition, then static state.
 * - Failure: Calm 160ms change, no shaking.
 * - Discovery: Slow 2.4s low-amplitude tonal breathing only while active; no radar.
 */
object GlideMotion {
    // Duration Constants (in milliseconds)
    const val ControlPressDurationMs: Int = 100
    const val CardAppearanceDurationMs: Int = 200
    const val DialogDurationMs: Int = 260
    const val NavigationDurationMs: Int = 250
    const val PairingPhaseCrossfadeMs: Int = 180
    const val TransferProgressSampleMs: Int = 200
    const val CompletionCheckDurationMs: Int = 240
    const val FailureChangeDurationMs: Int = 160
    const val DiscoveryBreathingPeriodMs: Int = 2400

    // Standard Material Easing Curves
    val EnterEasing: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f) // Emphasized decelerate
    val ExitEasing: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f) // Emphasized accelerate
    val StandardEasing: Easing = FastOutSlowInEasing // Standard / shared standard

    /**
     * Standard spring for control presses with zero overshoot bounce.
     */
    fun <T> controlPressSpring(): AnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    /**
     * Helper to resolve animation spec respecting the current reduced motion setting.
     * If reduced motion is active, returns `snap()` for instant transitions.
     */
    @Composable
    @ReadOnlyComposable
    fun <T> resolveSpec(spec: AnimationSpec<T>): AnimationSpec<T> {
        return if (LocalReducedMotion.current) {
            snap()
        } else {
            spec
        }
    }

    /**
     * Standard tween animation spec respecting reduced motion.
     */
    @Composable
    @ReadOnlyComposable
    fun <T> tweenOrSnap(
        durationMillis: Int,
        delayMillis: Int = 0,
        easing: Easing = StandardEasing
    ): AnimationSpec<T> {
        return if (LocalReducedMotion.current) {
            snap(delayMillis = 0)
        } else {
            tween(
                durationMillis = durationMillis,
                delayMillis = delayMillis,
                easing = easing
            )
        }
    }
}
