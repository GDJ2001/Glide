package dev.glide.designsystem

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Interface for semantic haptic feedback in Glide.
 *
 * Strictly adheres to `GLIDE_DESIGN_SYSTEM.md`:
 * - Haptics follow system touch feedback settings.
 * - Light selection: for chips, toggles, row selections.
 * - Confirmation: one connect/accept confirmation.
 * - Success: transfer completion check.
 * - Error: only when immediate user attention is warranted.
 * - Device-found haptic: strictly off by default to avoid repeated alerts in dense RF environments.
 * - Never use vibration alone to convey status (must always accompany visual UI indicators).
 */
interface GlideHaptics {
    fun performSelection()
    fun performConfirmation()
    fun performSuccess()
    fun performError()
    fun performDeviceDiscovered()
}

/**
 * No-op implementation of [GlideHaptics] when haptics are disabled or in preview/test environments.
 */
object NoOpGlideHaptics : GlideHaptics {
    override fun performSelection() {}
    override fun performConfirmation() {}
    override fun performSuccess() {}
    override fun performError() {}
    override fun performDeviceDiscovered() {}
}

/**
 * CompositionLocal providing access to the current [GlideHaptics] instance.
 */
val LocalGlideHaptics = staticCompositionLocalOf<GlideHaptics> { NoOpGlideHaptics }
