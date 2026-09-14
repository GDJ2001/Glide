package dev.glide.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Glide canonical spacing scale and layout metrics defined in docs/design/GLIDE_DESIGN_SYSTEM.md:
 * - Scale: 4, 8, 12, 16, 24, 32, 48, 64 dp.
 * - Compact page padding: 20 dp, medium: 24 dp, expanded: 32 dp.
 * - Card content padding: 16 / 24 dp.
 * - Icon-label gap: 8 dp, section-item gap: 16 dp.
 * - Min touch target: 48 dp (never shrink to match glyph).
 * - Max readable detail column: 640 dp, max content width: 1200 dp.
 */
@Immutable
data class GlideSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val huge: Dp = 48.dp,
    val giant: Dp = 64.dp,
    val pageCompact: Dp = 20.dp,
    val pageMedium: Dp = 24.dp,
    val pageExpanded: Dp = 32.dp,
    val cardContent: Dp = 16.dp,
    val cardContentLarge: Dp = 24.dp,
    val iconLabelGap: Dp = 8.dp,
    val sectionItemGap: Dp = 16.dp,
    val minTouchTarget: Dp = 48.dp,
    val maxContentWidth: Dp = 1200.dp,
    val maxDetailColumnWidth: Dp = 640.dp
)

val LocalGlideSpacing = staticCompositionLocalOf { GlideSpacing() }
