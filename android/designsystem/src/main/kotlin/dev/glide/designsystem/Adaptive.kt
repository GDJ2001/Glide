package dev.glide.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Window width size classes conforming to Material 3 and GLIDE_DESIGN_SYSTEM.md.
 * - Compact: < 600dp (standard portrait phones)
 * - Medium: 600dp .. 839dp (foldables unfolded, landscape phones, small tablets)
 * - Expanded: >= 840dp (tablets, large foldables, landscape tablets, desktop/freeform)
 */
enum class WindowWidthSizeClass {
    Compact,
    Medium,
    Expanded;

    val isCompact: Boolean get() = this == Compact
    val isMedium: Boolean get() = this == Medium
    val isExpanded: Boolean get() = this == Expanded
}

/**
 * Window height size classes conforming to Material 3.
 * - Compact: < 480dp (landscape phones)
 * - Medium: 480dp .. 899dp (standard portrait phones)
 * - Expanded: >= 900dp (tall tablets, desktop/freeform)
 */
enum class WindowHeightSizeClass {
    Compact,
    Medium,
    Expanded;

    val isCompact: Boolean get() = this == Compact
    val isMedium: Boolean get() = this == Medium
    val isExpanded: Boolean get() = this == Expanded
}

/**
 * Comprehensive window size class state.
 */
data class WindowSizeClass(
    val widthSizeClass: WindowWidthSizeClass,
    val heightSizeClass: WindowHeightSizeClass,
    val widthDp: Dp,
    val heightDp: Dp
) {
    val isCompactWidth: Boolean get() = widthSizeClass.isCompact
    val isMediumWidth: Boolean get() = widthSizeClass.isMedium
    val isExpandedWidth: Boolean get() = widthSizeClass.isExpanded

    val isCompactHeight: Boolean get() = heightSizeClass.isCompact
    val isMediumHeight: Boolean get() = heightSizeClass.isMedium
    val isExpandedHeight: Boolean get() = heightSizeClass.isExpanded

    /**
     * True when the layout should present a multi-pane layout (e.g. list-detail for History).
     */
    val supportsTwoPaneLayout: Boolean get() = isExpandedWidth

    /**
     * Returns the recommended page horizontal padding based on window size class:
     * - Compact: 20dp
     * - Medium: 24dp
     * - Expanded: 32dp
     */
    val pageHorizontalPadding: Dp
        get() = when (widthSizeClass) {
            WindowWidthSizeClass.Compact -> AdaptiveLayoutTokens.CompactPagePadding
            WindowWidthSizeClass.Medium -> AdaptiveLayoutTokens.MediumPagePadding
            WindowWidthSizeClass.Expanded -> AdaptiveLayoutTokens.ExpandedPagePadding
        }
}

/**
 * Design system layout tokens for adaptive dimensions and boundaries.
 * Mandated by GLIDE_DESIGN_SYSTEM.md.
 */
object AdaptiveLayoutTokens {
    val CompactWidthCeiling: Dp = 600.dp
    val MediumWidthCeiling: Dp = 840.dp

    val CompactHeightCeiling: Dp = 480.dp
    val MediumHeightCeiling: Dp = 900.dp

    val CompactPagePadding: Dp = 20.dp
    val MediumPagePadding: Dp = 24.dp
    val ExpandedPagePadding: Dp = 32.dp

    val MaxContentWidth: Dp = 1200.dp
    val MaxDetailPaneWidth: Dp = 640.dp
    val MinListPaneWidth: Dp = 360.dp
    val MaxListPaneWidth: Dp = 420.dp
}

/**
 * Pure calculation function for [WindowSizeClass].
 */
fun calculateWindowSizeClass(width: Dp, height: Dp): WindowSizeClass {
    val widthClass = when {
        width < AdaptiveLayoutTokens.CompactWidthCeiling -> WindowWidthSizeClass.Compact
        width < AdaptiveLayoutTokens.MediumWidthCeiling -> WindowWidthSizeClass.Medium
        else -> WindowWidthSizeClass.Expanded
    }

    val heightClass = when {
        height < AdaptiveLayoutTokens.CompactHeightCeiling -> WindowHeightSizeClass.Compact
        height < AdaptiveLayoutTokens.MediumHeightCeiling -> WindowHeightSizeClass.Medium
        else -> WindowHeightSizeClass.Expanded
    }

    return WindowSizeClass(
        widthSizeClass = widthClass,
        heightSizeClass = heightClass,
        widthDp = width,
        heightDp = height
    )
}

/**
 * CompositionLocal providing current [WindowSizeClass].
 */
val LocalWindowSizeClass = compositionLocalOf {
    calculateWindowSizeClass(360.dp, 640.dp)
}

/**
 * Composable utility to resolve the current [WindowSizeClass] live from [LocalConfiguration].
 */
@Composable
fun rememberWindowSizeClass(): WindowSizeClass {
    val configuration = LocalConfiguration.current
    val width = configuration.screenWidthDp.dp
    val height = configuration.screenHeightDp.dp
    return calculateWindowSizeClass(width, height)
}
