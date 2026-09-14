package dev.glide.designsystem

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * Utility for verifying WCAG 2.1 contrast ratios across Glide semantic color tokens.
 *
 * Implements W3C WCAG 2.1 relative luminance and contrast formula:
 * - Relative luminance L = 0.2126 * R + 0.7152 * G + 0.0722 * B
 * - Contrast ratio = (L1 + 0.05) / (L2 + 0.05) where L1 >= L2
 * - Standard text requires >= 4.5:1 (Level AA)
 * - Large text (>= 18pt or >= 14pt bold) and UI components require >= 3.0:1 (Level AA)
 */
object ColorContrast {

    /**
     * Calculates the relative luminance of a sRGB [Color] normalized to [0.0..1.0].
     */
    fun relativeLuminance(color: Color): Double {
        fun channelLuminance(channel: Float): Double {
            val c = channel.toDouble().coerceIn(0.0, 1.0)
            return if (c <= 0.04045) {
                c / 12.92
            } else {
                ((c + 0.055) / 1.055).pow(2.4)
            }
        }

        val r = channelLuminance(color.red)
        val g = channelLuminance(color.green)
        val b = channelLuminance(color.blue)

        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /**
     * Computes the WCAG contrast ratio between two colors.
     * The result is always >= 1.0 and <= 21.0.
     */
    fun contrastRatio(color1: Color, color2: Color): Double {
        val l1 = relativeLuminance(color1)
        val l2 = relativeLuminance(color2)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Verifies if the contrast ratio meets WCAG Level AA for normal text (>= 4.5:1).
     */
    fun meetsWcagAaNormal(foreground: Color, background: Color): Boolean {
        return contrastRatio(foreground, background) >= 4.5
    }

    /**
     * Verifies if the contrast ratio meets WCAG Level AA for large text or graphical controls (>= 3.0:1).
     */
    fun meetsWcagAaLarge(foreground: Color, background: Color): Boolean {
        return contrastRatio(foreground, background) >= 3.0
    }
}
