package dev.glide.app.ui.motion

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.glide.designsystem.GlideMotion
import dev.glide.designsystem.LocalReducedMotion

/**
 * Lifecycle-aware and reduced-motion-aware tonal breathing alpha provider.
 *
 * Adheres strictly to `GLIDE_DESIGN_SYSTEM.md` and `UI_QUALITY_CHECKLIST.md`:
 * - "Slow 2.4s low-amplitude tonal breathing only while active; no radar implying measured distance."
 * - "Disable decorative discovery animation while backgrounded, under reduced-motion preference, or when no discovery is running. Performance wins over effects."
 * - Halts infinite animation when the lifecycle is not at least [Lifecycle.State.RESUMED].
 * - Halts infinite animation when [LocalReducedMotion] is true.
 * - Halts infinite animation when [isActive] is false.
 * - In all halted states, immediately returns static [targetValue] (1.0f) without continuous recomposition.
 */
@Composable
fun rememberTonalBreathingAlpha(
    initialValue: Float = 0.55f,
    targetValue: Float = 1.0f,
    periodMs: Int = GlideMotion.DiscoveryBreathingPeriodMs,
    isActive: Boolean = true
): State<Float> {
    val reducedMotion = LocalReducedMotion.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val isResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    val shouldAnimate = isActive && isResumed && !reducedMotion

    return if (shouldAnimate) {
        val infiniteTransition = rememberInfiniteTransition(label = "TonalBreathing")
        infiniteTransition.animateFloat(
            initialValue = initialValue,
            targetValue = targetValue,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = periodMs / 2,
                    easing = GlideMotion.StandardEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "TonalBreathingAlpha"
        )
    } else {
        remember(targetValue) { mutableFloatStateOf(targetValue) }
    }
}
