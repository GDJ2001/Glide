package dev.glide.app.ui.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import dev.glide.designsystem.GlideHaptics

/**
 * Concrete Android implementation of [GlideHaptics].
 *
 * Adheres strictly to `GLIDE_DESIGN_SYSTEM.md`:
 * - Respects system touch feedback settings (`Settings.System.HAPTIC_FEEDBACK_ENABLED`).
 * - Light selection: for chips, toggles, row selections.
 * - Confirmation: one connect/accept confirmation.
 * - Success: transfer completion check.
 * - Error: only when immediate user attention is warranted.
 * - Device-found haptic: strictly OFF by default.
 * - Never uses vibration alone to convey status.
 */
class AndroidGlideHaptics(
    private val context: Context,
    private val composeHaptic: HapticFeedback? = null,
    private val deviceDiscoveredHapticsEnabled: Boolean = false
) : GlideHaptics {

    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(VibratorManager::class.java)
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Checks if system touch feedback / haptics are enabled on device.
     */
    @Suppress("DEPRECATION")
    fun isSystemHapticFeedbackEnabled(): Boolean {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                1
            ) != 0
        } catch (_: Throwable) {
            true
        }
    }

    override fun performSelection() {
        if (!isSystemHapticFeedbackEnabled()) return

        composeHaptic?.performHapticFeedback(HapticFeedbackType.TextHandleMove) ?: run {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            }
        }
    }

    override fun performConfirmation() {
        if (!isSystemHapticFeedbackEnabled()) return

        composeHaptic?.performHapticFeedback(HapticFeedbackType.LongPress) ?: run {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            }
        }
    }

    override fun performSuccess() {
        if (!isSystemHapticFeedbackEnabled()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
            composeHaptic?.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    override fun performError() {
        if (!isSystemHapticFeedbackEnabled()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        } else {
            composeHaptic?.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    override fun performDeviceDiscovered() {
        // Strictly off by default to avoid repeated alerts in dense RF environments!
        if (!deviceDiscoveredHapticsEnabled) return
        if (!isSystemHapticFeedbackEnabled()) return

        composeHaptic?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
}

/**
 * Recording test fake for [GlideHaptics] to verify haptic invocations in unit/acceptance tests.
 */
class TestGlideHaptics(
    var isEnabled: Boolean = true,
    var deviceDiscoveredEnabled: Boolean = false
) : GlideHaptics {
    var selectionCount: Int = 0
        private set
    var confirmationCount: Int = 0
        private set
    var successCount: Int = 0
        private set
    var errorCount: Int = 0
        private set
    var deviceDiscoveredCount: Int = 0
        private set

    fun reset() {
        selectionCount = 0
        confirmationCount = 0
        successCount = 0
        errorCount = 0
        deviceDiscoveredCount = 0
    }

    override fun performSelection() {
        if (isEnabled) selectionCount++
    }

    override fun performConfirmation() {
        if (isEnabled) confirmationCount++
    }

    override fun performSuccess() {
        if (isEnabled) successCount++
    }

    override fun performError() {
        if (isEnabled) errorCount++
    }

    override fun performDeviceDiscovered() {
        if (isEnabled && deviceDiscoveredEnabled) deviceDiscoveredCount++
    }
}
