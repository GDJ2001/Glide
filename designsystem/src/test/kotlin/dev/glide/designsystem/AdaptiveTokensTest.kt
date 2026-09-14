package dev.glide.designsystem

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test suite verifying adaptive window size class calculations and layout token boundaries (GLIDE-044).
 */
class AdaptiveTokensTest {

    @Test
    fun widthThresholds_calculateCorrectSizeClasses() {
        // Compact width (< 600dp)
        val compactPhone = calculateWindowSizeClass(360.dp, 800.dp)
        assertEquals(WindowWidthSizeClass.Compact, compactPhone.widthSizeClass)
        assertTrue(compactPhone.isCompactWidth)
        assertFalse(compactPhone.isMediumWidth)
        assertFalse(compactPhone.isExpandedWidth)
        assertFalse(compactPhone.supportsTwoPaneLayout)

        val boundaryCompact = calculateWindowSizeClass(599.dp, 800.dp)
        assertEquals(WindowWidthSizeClass.Compact, boundaryCompact.widthSizeClass)

        // Medium width (600dp .. 839dp)
        val boundaryMediumLower = calculateWindowSizeClass(600.dp, 800.dp)
        assertEquals(WindowWidthSizeClass.Medium, boundaryMediumLower.widthSizeClass)
        assertTrue(boundaryMediumLower.isMediumWidth)
        assertFalse(boundaryMediumLower.isCompactWidth)
        assertFalse(boundaryMediumLower.isExpandedWidth)
        assertFalse(boundaryMediumLower.supportsTwoPaneLayout)

        val boundaryMediumUpper = calculateWindowSizeClass(839.dp, 800.dp)
        assertEquals(WindowWidthSizeClass.Medium, boundaryMediumUpper.widthSizeClass)

        // Expanded width (>= 840dp)
        val boundaryExpanded = calculateWindowSizeClass(840.dp, 800.dp)
        assertEquals(WindowWidthSizeClass.Expanded, boundaryExpanded.widthSizeClass)
        assertTrue(boundaryExpanded.isExpandedWidth)
        assertFalse(boundaryExpanded.isMediumWidth)
        assertFalse(boundaryExpanded.isCompactWidth)
        assertTrue(boundaryExpanded.supportsTwoPaneLayout)

        val largeTablet = calculateWindowSizeClass(1280.dp, 900.dp)
        assertEquals(WindowWidthSizeClass.Expanded, largeTablet.widthSizeClass)
        assertTrue(largeTablet.supportsTwoPaneLayout)
    }

    @Test
    fun heightThresholds_calculateCorrectSizeClasses() {
        // Compact height (< 480dp) - typical landscape phone
        val landscapePhone = calculateWindowSizeClass(840.dp, 411.dp)
        assertEquals(WindowHeightSizeClass.Compact, landscapePhone.heightSizeClass)
        assertTrue(landscapePhone.isCompactHeight)

        val boundaryCompactHeight = calculateWindowSizeClass(600.dp, 479.dp)
        assertEquals(WindowHeightSizeClass.Compact, boundaryCompactHeight.heightSizeClass)

        // Medium height (480dp .. 899dp) - typical portrait phone
        val portraitPhone = calculateWindowSizeClass(411.dp, 840.dp)
        assertEquals(WindowHeightSizeClass.Medium, portraitPhone.heightSizeClass)
        assertTrue(portraitPhone.isMediumHeight)

        // Expanded height (>= 900dp) - tall tablet / desktop
        val tallTablet = calculateWindowSizeClass(800.dp, 1200.dp)
        assertEquals(WindowHeightSizeClass.Expanded, tallTablet.heightSizeClass)
        assertTrue(tallTablet.isExpandedHeight)
    }

    @Test
    fun paddingTokens_mapToDesignSystemSpecifications() {
        val compact = calculateWindowSizeClass(400.dp, 800.dp)
        assertEquals(20.dp, compact.pageHorizontalPadding)
        assertEquals(AdaptiveLayoutTokens.CompactPagePadding, compact.pageHorizontalPadding)

        val medium = calculateWindowSizeClass(700.dp, 800.dp)
        assertEquals(24.dp, medium.pageHorizontalPadding)
        assertEquals(AdaptiveLayoutTokens.MediumPagePadding, medium.pageHorizontalPadding)

        val expanded = calculateWindowSizeClass(900.dp, 800.dp)
        assertEquals(32.dp, expanded.pageHorizontalPadding)
        assertEquals(AdaptiveLayoutTokens.ExpandedPagePadding, expanded.pageHorizontalPadding)
    }

    @Test
    fun layoutBoundsTokens_matchDesignSystemMaxLimits() {
        assertEquals(1200.dp, AdaptiveLayoutTokens.MaxContentWidth)
        assertEquals(640.dp, AdaptiveLayoutTokens.MaxDetailPaneWidth)
        assertEquals(360.dp, AdaptiveLayoutTokens.MinListPaneWidth)
        assertEquals(420.dp, AdaptiveLayoutTokens.MaxListPaneWidth)
    }
}
