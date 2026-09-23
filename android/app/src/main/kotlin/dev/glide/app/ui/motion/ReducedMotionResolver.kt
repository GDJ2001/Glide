package dev.glide.app.ui.motion

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import dev.glide.core.ports.ReducedMotionPreference

/**
 * Utility to resolve whether reduced motion is active, taking into account:
 * 1. User app preference ([ReducedMotionPreference.ON], [ReducedMotionPreference.OFF], [ReducedMotionPreference.SYSTEM]).
 * 2. System animator duration scale (`animator_duration_scale == 0`).
 * 3. System transition animation scale (`transition_animation_scale == 0`).
 * 4. Android 14+ (API 34+) system accessibility setting ([AccessibilityManager.isReducedMotionEnabled]).
 */
object ReducedMotionResolver {

    /**
     * Optional testing override for deterministic unit/acceptance tests and emulator verification.
     */
    var testOverride: Boolean? = null

    /**
     * Resolves effective reduced motion state given the user preference and Android context.
     */
    fun isReducedMotionActive(
        preference: ReducedMotionPreference,
        context: Context?
    ): Boolean {
        testOverride?.let { return it }

        return when (preference) {
            ReducedMotionPreference.ON -> true
            ReducedMotionPreference.OFF -> false
            ReducedMotionPreference.SYSTEM -> {
                if (context == null) false else isSystemReducedMotionEnabled(context)
            }
        }
    }

    /**
     * Checks if Android system settings indicate reduced motion / animation disabled.
     */
    fun isSystemReducedMotionEnabled(context: Context): Boolean {
        // 1. Check API 34+ AccessibilityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
                val method = accessibilityManager?.javaClass?.getMethod("isReducedMotionEnabled")
                if (method?.invoke(accessibilityManager) == true) {
                    return true
                }
            } catch (_: Throwable) {
                // Fallback to settings query
            }
        }

        // 2. Check Animator Duration Scale (0.0f means animations are disabled)
        try {
            val animatorScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
            if (animatorScale <= 0.05f) {
                return true
            }
        } catch (_: Throwable) {
            // Ignored in test environments without global settings access
        }

        // 3. Check Transition Animation Scale
        try {
            val transitionScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1.0f
            )
            if (transitionScale <= 0.05f) {
                return true
            }
        } catch (_: Throwable) {
            // Ignored in test environments
        }

        return false
    }
}
