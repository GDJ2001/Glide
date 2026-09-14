package dev.glide.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext

/**
 * Glide Design System theme composable.
 *
 * Implements specifications in docs/design/GLIDE_DESIGN_SYSTEM.md:
 * - Light and Dark canonical theme tokens with contrast-tested tonal pairs.
 * - Dynamic color support on API 31+ (Android 12+), off by default.
 * - Extended semantic color roles (success, warning, error, info containers and text pairs).
 * - Canonical layout metrics and spacing scale (4, 8, 12, 16, 24, 32, 48, 64 dp).
 * - Accessible typography and responsive shapes.
 */
@Composable
fun GlideTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    reducedMotion: Boolean = false,
    haptics: GlideHaptics = NoOpGlideHaptics,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> GlideDarkColorScheme
        else -> GlideLightColorScheme
    }

    val semanticColors = if (darkTheme) GlideDarkSemanticColors else GlideLightSemanticColors

    CompositionLocalProvider(
        LocalGlideSemanticColors provides semanticColors,
        LocalGlideSpacing provides GlideSpacing(),
        LocalReducedMotion provides reducedMotion,
        LocalGlideHaptics provides haptics
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = GlideTypography,
            shapes = GlideShapes,
            content = content
        )
    }
}

/**
 * Direct static accessors for Glide design system tokens.
 */
object GlideTheme {
    val semanticColors: GlideSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalGlideSemanticColors.current

    val spacing: GlideSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalGlideSpacing.current

    val motion: GlideMotion
        get() = GlideMotion

    val haptics: GlideHaptics
        @Composable
        @ReadOnlyComposable
        get() = LocalGlideHaptics.current
}
