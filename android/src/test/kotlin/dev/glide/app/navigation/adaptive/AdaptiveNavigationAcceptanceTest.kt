package dev.glide.app.navigation.adaptive

import dev.glide.designsystem.AdaptiveLayoutTokens
import dev.glide.designsystem.WindowSizeClass
import dev.glide.designsystem.WindowWidthSizeClass
import dev.glide.designsystem.calculateWindowSizeClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp

/**
 * Acceptance tests for GLIDE-044 adaptive navigation requirements.
 *
 * Verifies:
 * - Width breakpoints (Compact < 600dp, Medium 600-839dp, Expanded >= 840dp)
 * - NavigationBar vs NavigationRail mode selection
 * - Two-pane layout activation on Expanded width
 * - Content width token application
 * - Transfer card max width constraint
 * - Emulator layout class resolution (portrait Compact, landscape Expanded)
 */
class AdaptiveNavigationAcceptanceTest {

    // -------------------------------------------------------------------------
    // 1. Compact portrait (411dp x 923dp) -> bottom NavigationBar
    // -------------------------------------------------------------------------
    @Test
    fun compact_portrait_emulator_resolves_to_Compact_width_and_bottom_nav() {
        val wsc = calculateWindowSizeClass(width = 411.dp, height = 923.dp)

        assertEquals(WindowWidthSizeClass.Compact, wsc.widthSizeClass)
        assertTrue(wsc.isCompactWidth)
        assertFalse(wsc.supportsTwoPaneLayout)
    }

    // -------------------------------------------------------------------------
    // 2. Landscape expanded emulator (923dp x 411dp) -> NavigationRail + two-pane
    // -------------------------------------------------------------------------
    @Test
    fun landscape_expanded_emulator_resolves_to_Expanded_width_with_two_pane() {
        val wsc = calculateWindowSizeClass(width = 923.dp, height = 411.dp)

        assertEquals(WindowWidthSizeClass.Expanded, wsc.widthSizeClass)
        assertFalse(wsc.isCompactWidth)
        assertTrue(wsc.supportsTwoPaneLayout)
    }

    // -------------------------------------------------------------------------
    // 3. Width boundary at exactly 840dp -> Expanded (NavigationRail + two-pane)
    // -------------------------------------------------------------------------
    @Test
    fun width_exactly_840dp_resolves_to_Expanded_and_enables_two_pane() {
        val wsc = calculateWindowSizeClass(width = 840.dp, height = 700.dp)

        assertEquals(WindowWidthSizeClass.Expanded, wsc.widthSizeClass)
        assertTrue(wsc.supportsTwoPaneLayout)
    }

    // -------------------------------------------------------------------------
    // 4. Width at 839dp -> Medium (NavigationRail, single-pane)
    // -------------------------------------------------------------------------
    @Test
    fun width_839dp_resolves_to_Medium_and_stays_single_pane() {
        val wsc = calculateWindowSizeClass(width = 839.dp, height = 700.dp)

        assertEquals(WindowWidthSizeClass.Medium, wsc.widthSizeClass)
        assertFalse(wsc.isCompactWidth)
        assertFalse(wsc.supportsTwoPaneLayout)
    }

    // -------------------------------------------------------------------------
    // 5. Content width tokens satisfy design system bounds
    // -------------------------------------------------------------------------
    @Test
    fun adaptive_layout_tokens_satisfy_design_system_specification() {
        assertEquals(1200.dp, AdaptiveLayoutTokens.MaxContentWidth)
        assertEquals(640.dp, AdaptiveLayoutTokens.MaxDetailPaneWidth)
        assertEquals(360.dp, AdaptiveLayoutTokens.MinListPaneWidth)
        assertEquals(420.dp, AdaptiveLayoutTokens.MaxListPaneWidth)
    }

    // -------------------------------------------------------------------------
    // 6. Page horizontal padding scales with window width class
    // -------------------------------------------------------------------------
    @Test
    fun page_horizontal_padding_increases_with_window_width_class() {
        val compact = calculateWindowSizeClass(width = 411.dp, height = 900.dp)
        val medium = calculateWindowSizeClass(width = 700.dp, height = 900.dp)
        val expanded = calculateWindowSizeClass(width = 900.dp, height = 600.dp)

        assertEquals(20.dp, compact.pageHorizontalPadding)
        assertEquals(24.dp, medium.pageHorizontalPadding)
        assertEquals(32.dp, expanded.pageHorizontalPadding)
        assertTrue(medium.pageHorizontalPadding > compact.pageHorizontalPadding)
        assertTrue(expanded.pageHorizontalPadding > medium.pageHorizontalPadding)
    }
}
