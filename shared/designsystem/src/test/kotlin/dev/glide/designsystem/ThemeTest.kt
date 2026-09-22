package dev.glide.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive tests for the Glide Design System theme, tokens, spacing, and WCAG contrast (GLIDE-035).
 */
class ThemeTest {

    @Test
    fun lightColorScheme_definesRequiredTokens() {
        assertEquals(Color(0xFF375BEE), PrimaryLight)
        assertEquals(Color(0xFFFFFFFF), OnPrimaryLight)
        assertEquals(Color(0xFFE0E5FF), PrimaryContainerLight)
        assertEquals(Color(0xFF10256E), OnPrimaryContainerLight)

        assertEquals(Color(0xFF465475), SecondaryLight)
        assertEquals(Color(0xFFFFFFFF), OnSecondaryLight)
        assertEquals(Color(0xFFDEE5FA), SecondaryContainerLight)
        assertEquals(Color(0xFF151B38), OnSecondaryContainerLight)

        assertEquals(Color(0xFF006879), TertiaryLight)
        assertEquals(Color(0xFFFFFFFF), OnTertiaryLight)
        assertEquals(Color(0xFFB4F0FA), TertiaryContainerLight)
        assertEquals(Color(0xFF003640), OnTertiaryContainerLight)

        assertEquals(Color(0xFFF8F9FF), BackgroundLight)
        assertEquals(Color(0xFF151B38), OnBackgroundLight)
        assertEquals(Color(0xFFF8F9FF), SurfaceLight)
        assertEquals(Color(0xFF151B38), OnSurfaceLight)
        assertEquals(Color(0xFFF0F2FA), SurfaceLowLight)
        assertEquals(Color(0xFFE6EAF5), SurfaceHighLight)

        assertEquals(Color(0xFF69738C), OutlineLight)
        assertEquals(Color(0xFFC5CDDF), OutlineVariantLight)

        assertEquals(Color(0xFF126C45), SuccessLight)
        assertEquals(Color(0xFFFFFFFF), OnSuccessLight)
        assertEquals(Color(0xFFD4F7E2), SuccessContainerLight)
        assertEquals(Color(0xFF003921), OnSuccessContainerLight)

        assertEquals(Color(0xFF795400), WarningLight)
        assertEquals(Color(0xFFFFFFFF), OnWarningLight)
        assertEquals(Color(0xFFFFE8A3), WarningContainerLight)
        assertEquals(Color(0xFF402D00), OnWarningContainerLight)

        assertEquals(Color(0xFFBA1A1A), ErrorLight)
        assertEquals(Color(0xFFFFFFFF), OnErrorLight)
        assertEquals(Color(0xFFFFDAD6), ErrorContainerLight)
        assertEquals(Color(0xFF410002), OnErrorContainerLight)

        assertEquals(Color(0xFF2755A4), InfoLight)
        assertEquals(Color(0xFFFFFFFF), OnInfoLight)
        assertEquals(Color(0xFFD8E2FF), InfoContainerLight)
        assertEquals(Color(0xFF001A41), OnInfoContainerLight)

        assertEquals(Color(0xFF22CBE5), BrandCyanAccent)

        assertNotNull(GlideLightColorScheme)
        assertEquals(ErrorContainerLight, GlideLightColorScheme.errorContainer)
        assertEquals(OnErrorContainerLight, GlideLightColorScheme.onErrorContainer)
    }

    @Test
    fun darkColorScheme_definesRequiredTokens() {
        assertEquals(Color(0xFFBBC6FF), PrimaryDark)
        assertEquals(Color(0xFF10256E), OnPrimaryDark)
        assertEquals(Color(0xFF263D91), PrimaryContainerDark)
        assertEquals(Color(0xFFE0E5FF), OnPrimaryContainerDark)

        assertEquals(Color(0xFFBDC7E8), SecondaryDark)
        assertEquals(Color(0xFF24314E), OnSecondaryDark)
        assertEquals(Color(0xFF364362), SecondaryContainerDark)
        assertEquals(Color(0xFFDEE5FA), OnSecondaryContainerDark)

        assertEquals(Color(0xFF75D7EA), TertiaryDark)
        assertEquals(Color(0xFF003640), OnTertiaryDark)
        assertEquals(Color(0xFF004F5D), TertiaryContainerDark)
        assertEquals(Color(0xFFB4F0FA), OnTertiaryContainerDark)

        assertEquals(Color(0xFF0D1121), BackgroundDark)
        assertEquals(Color(0xFFEDF0FF), OnBackgroundDark)
        assertEquals(Color(0xFF111629), SurfaceDark)
        assertEquals(Color(0xFFEDF0FF), OnSurfaceDark)
        assertEquals(Color(0xFF171E34), SurfaceLowDark)
        assertEquals(Color(0xFF252D45), SurfaceHighDark)

        assertEquals(Color(0xFF929DB8), OutlineDark)
        assertEquals(Color(0xFF3B4660), OutlineVariantDark)

        assertEquals(Color(0xFF80DBAD), SuccessDark)
        assertEquals(Color(0xFF003921), OnSuccessDark)
        assertEquals(Color(0xFF005232), SuccessContainerDark)
        assertEquals(Color(0xFFD4F7E2), OnSuccessContainerDark)

        assertEquals(Color(0xFFEBC46A), WarningDark)
        assertEquals(Color(0xFF402D00), OnWarningDark)
        assertEquals(Color(0xFF593E00), WarningContainerDark)
        assertEquals(Color(0xFFFFE8A3), OnWarningContainerDark)

        assertEquals(Color(0xFFFFB4AB), ErrorDark)
        assertEquals(Color(0xFF690005), OnErrorDark)
        assertEquals(Color(0xFF93000A), ErrorContainerDark)
        assertEquals(Color(0xFFFFDAD6), OnErrorContainerDark)

        assertEquals(Color(0xFFAAC7FF), InfoDark)
        assertEquals(Color(0xFF003063), OnInfoDark)
        assertEquals(Color(0xFF00418C), InfoContainerDark)
        assertEquals(Color(0xFFD8E2FF), OnInfoContainerDark)

        assertNotNull(GlideDarkColorScheme)
        assertEquals(ErrorContainerDark, GlideDarkColorScheme.errorContainer)
        assertEquals(OnErrorContainerDark, GlideDarkColorScheme.onErrorContainer)
    }

    @Test
    fun wcagContrastRatio_allLightTokenPairsMeetWcagAaRequirements() {
        val pairs = listOf(
            "Primary" to (PrimaryLight to OnPrimaryLight),
            "PrimaryContainer" to (PrimaryContainerLight to OnPrimaryContainerLight),
            "Secondary" to (SecondaryLight to OnSecondaryLight),
            "SecondaryContainer" to (SecondaryContainerLight to OnSecondaryContainerLight),
            "Tertiary" to (TertiaryLight to OnTertiaryLight),
            "TertiaryContainer" to (TertiaryContainerLight to OnTertiaryContainerLight),
            "Background" to (BackgroundLight to OnBackgroundLight),
            "Surface" to (SurfaceLight to OnSurfaceLight),
            "Success" to (SuccessLight to OnSuccessLight),
            "SuccessContainer" to (SuccessContainerLight to OnSuccessContainerLight),
            "Warning" to (WarningLight to OnWarningLight),
            "WarningContainer" to (WarningContainerLight to OnWarningContainerLight),
            "Error" to (ErrorLight to OnErrorLight),
            "ErrorContainer" to (ErrorContainerLight to OnErrorContainerLight),
            "Info" to (InfoLight to OnInfoLight),
            "InfoContainer" to (InfoContainerLight to OnInfoContainerLight)
        )

        for ((name, pair) in pairs) {
            val (c1, c2) = pair
            val ratio = ColorContrast.contrastRatio(c1, c2)
            assertTrue(
                "Light mode pair '$name' contrast ratio $ratio must be >= 4.5:1 (WCAG AA)",
                ratio >= 4.5
            )
        }
    }

    @Test
    fun wcagContrastRatio_allDarkTokenPairsMeetWcagAaRequirements() {
        val pairs = listOf(
            "Primary" to (PrimaryDark to OnPrimaryDark),
            "PrimaryContainer" to (PrimaryContainerDark to OnPrimaryContainerDark),
            "Secondary" to (SecondaryDark to OnSecondaryDark),
            "SecondaryContainer" to (SecondaryContainerDark to OnSecondaryContainerDark),
            "Tertiary" to (TertiaryDark to OnTertiaryDark),
            "TertiaryContainer" to (TertiaryContainerDark to OnTertiaryContainerDark),
            "Background" to (BackgroundDark to OnBackgroundDark),
            "Surface" to (SurfaceDark to OnSurfaceDark),
            "Success" to (SuccessDark to OnSuccessDark),
            "SuccessContainer" to (SuccessContainerDark to OnSuccessContainerDark),
            "Warning" to (WarningDark to OnWarningDark),
            "WarningContainer" to (WarningContainerDark to OnWarningContainerDark),
            "Error" to (ErrorDark to OnErrorDark),
            "ErrorContainer" to (ErrorContainerDark to OnErrorContainerDark),
            "Info" to (InfoDark to OnInfoDark),
            "InfoContainer" to (InfoContainerDark to OnInfoContainerDark)
        )

        for ((name, pair) in pairs) {
            val (c1, c2) = pair
            val ratio = ColorContrast.contrastRatio(c1, c2)
            assertTrue(
                "Dark mode pair '$name' contrast ratio $ratio must be >= 4.5:1 (WCAG AA)",
                ratio >= 4.5
            )
        }
    }

    @Test
    fun spacing_adheresToCanonicalMetrics() {
        val spacing = GlideSpacing()
        assertEquals(4.dp, spacing.xs)
        assertEquals(8.dp, spacing.sm)
        assertEquals(12.dp, spacing.md)
        assertEquals(16.dp, spacing.lg)
        assertEquals(24.dp, spacing.xl)
        assertEquals(32.dp, spacing.xxl)
        assertEquals(48.dp, spacing.huge)
        assertEquals(64.dp, spacing.giant)

        assertEquals(20.dp, spacing.pageCompact)
        assertEquals(24.dp, spacing.pageMedium)
        assertEquals(32.dp, spacing.pageExpanded)

        assertEquals(16.dp, spacing.cardContent)
        assertEquals(24.dp, spacing.cardContentLarge)

        assertEquals(8.dp, spacing.iconLabelGap)
        assertEquals(16.dp, spacing.sectionItemGap)
        assertEquals(48.dp, spacing.minTouchTarget)

        assertEquals(1200.dp, spacing.maxContentWidth)
        assertEquals(640.dp, spacing.maxDetailColumnWidth)
    }

    @Test
    fun typography_usesSpecifiedSpScales() {
        assertNotNull(GlideTypography.displayLarge)
        assertNotNull(GlideTypography.headlineLarge)
        assertNotNull(GlideTypography.titleLarge)
        assertNotNull(GlideTypography.bodyLarge)
        assertNotNull(GlideTypography.labelLarge)
    }

    @Test
    fun shapes_definesExpectedRadius() {
        assertNotNull(GlideShapes.small)
        assertNotNull(GlideShapes.medium)
        assertNotNull(GlideShapes.large)
        assertNotNull(PrimaryButtonShape)
    }
}
