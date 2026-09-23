package dev.glide.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// =============================================================================
// Canonical Color Tokens (docs/design/GLIDE_DESIGN_SYSTEM.md)
// =============================================================================

// Primary & Containers
val PrimaryLight = Color(0xFF375BEE)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFE0E5FF)
val OnPrimaryContainerLight = Color(0xFF10256E)

val PrimaryDark = Color(0xFFBBC6FF)
val OnPrimaryDark = Color(0xFF10256E)
val PrimaryContainerDark = Color(0xFF263D91)
val OnPrimaryContainerDark = Color(0xFFE0E5FF)

// Secondary & Containers
val SecondaryLight = Color(0xFF465475)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFDEE5FA)
val OnSecondaryContainerLight = Color(0xFF151B38)

val SecondaryDark = Color(0xFFBDC7E8)
val OnSecondaryDark = Color(0xFF24314E)
val SecondaryContainerDark = Color(0xFF364362)
val OnSecondaryContainerDark = Color(0xFFDEE5FA)

// Tertiary Accent & Containers
val TertiaryLight = Color(0xFF006879)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFB4F0FA)
val OnTertiaryContainerLight = Color(0xFF003640)

val TertiaryDark = Color(0xFF75D7EA)
val OnTertiaryDark = Color(0xFF003640)
val TertiaryContainerDark = Color(0xFF004F5D)
val OnTertiaryContainerDark = Color(0xFFB4F0FA)

// Background & Surface
val BackgroundLight = Color(0xFFF8F9FF)
val OnBackgroundLight = Color(0xFF151B38)
val SurfaceLight = Color(0xFFF8F9FF)
val OnSurfaceLight = Color(0xFF151B38)
val SurfaceLowLight = Color(0xFFF0F2FA)
val SurfaceHighLight = Color(0xFFE6EAF5)

val BackgroundDark = Color(0xFF0D1121)
val OnBackgroundDark = Color(0xFFEDF0FF)
val SurfaceDark = Color(0xFF111629)
val OnSurfaceDark = Color(0xFFEDF0FF)
val SurfaceLowDark = Color(0xFF171E34)
val SurfaceHighDark = Color(0xFF252D45)

// Outline
val OutlineLight = Color(0xFF69738C)
val OutlineVariantLight = Color(0xFFC5CDDF)

val OutlineDark = Color(0xFF929DB8)
val OutlineVariantDark = Color(0xFF3B4660)

// Semantics: Success, Warning, Error, Info (Bases & Contrast-Tested Containers)
val SuccessLight = Color(0xFF126C45)
val OnSuccessLight = Color(0xFFFFFFFF)
val SuccessContainerLight = Color(0xFFD4F7E2)
val OnSuccessContainerLight = Color(0xFF003921)

val SuccessDark = Color(0xFF80DBAD)
val OnSuccessDark = Color(0xFF003921)
val SuccessContainerDark = Color(0xFF005232)
val OnSuccessContainerDark = Color(0xFFD4F7E2)

val WarningLight = Color(0xFF795400)
val OnWarningLight = Color(0xFFFFFFFF)
val WarningContainerLight = Color(0xFFFFE8A3)
val OnWarningContainerLight = Color(0xFF402D00)

val WarningDark = Color(0xFFEBC46A)
val OnWarningDark = Color(0xFF402D00)
val WarningContainerDark = Color(0xFF593E00)
val OnWarningContainerDark = Color(0xFFFFE8A3)

val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)

val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)

val InfoLight = Color(0xFF2755A4)
val OnInfoLight = Color(0xFFFFFFFF)
val InfoContainerLight = Color(0xFFD8E2FF)
val OnInfoContainerLight = Color(0xFF001A41)

val InfoDark = Color(0xFFAAC7FF)
val OnInfoDark = Color(0xFF003063)
val InfoContainerDark = Color(0xFF00418C)
val OnInfoContainerDark = Color(0xFFD8E2FF)

// Brand Decorative Accent (non-text; home ambient wash / active gradient stroke)
val BrandCyanAccent = Color(0xFF22CBE5)

// =============================================================================
// Material 3 Color Schemes
// =============================================================================

val GlideLightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceLowLight,
    onSurfaceVariant = SecondaryLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight
)

val GlideDarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceLowDark,
    onSurfaceVariant = SecondaryDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark
)

// =============================================================================
// Extended Semantic Colors
// =============================================================================

@Immutable
data class GlideSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val brandAccent: Color
)

val GlideLightSemanticColors = GlideSemanticColors(
    success = SuccessLight,
    onSuccess = OnSuccessLight,
    successContainer = SuccessContainerLight,
    onSuccessContainer = OnSuccessContainerLight,
    warning = WarningLight,
    onWarning = OnWarningLight,
    warningContainer = WarningContainerLight,
    onWarningContainer = OnWarningContainerLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    info = InfoLight,
    onInfo = OnInfoLight,
    infoContainer = InfoContainerLight,
    onInfoContainer = OnInfoContainerLight,
    brandAccent = BrandCyanAccent
)

val GlideDarkSemanticColors = GlideSemanticColors(
    success = SuccessDark,
    onSuccess = OnSuccessDark,
    successContainer = SuccessContainerDark,
    onSuccessContainer = OnSuccessContainerDark,
    warning = WarningDark,
    onWarning = OnWarningDark,
    warningContainer = WarningContainerDark,
    onWarningContainer = OnWarningContainerDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    info = InfoDark,
    onInfo = OnInfoDark,
    infoContainer = InfoContainerDark,
    onInfoContainer = OnInfoContainerDark,
    brandAccent = BrandCyanAccent
)

val LocalGlideSemanticColors = staticCompositionLocalOf { GlideLightSemanticColors }

// =============================================================================
// Extension Accessors on Material3 ColorScheme
// =============================================================================

val ColorScheme.success: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalGlideSemanticColors.current.success

val ColorScheme.onSuccess: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalGlideSemanticColors.current.onSuccess

val ColorScheme.successContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalGlideSemanticColors.current.successContainer

val ColorScheme.onSuccessContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalGlideSemanticColors.current.onSuccessContainer

val ColorScheme.warning: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalGlideSemanticColors.current.warning

val ColorScheme.onWarning: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalGlideSemanticColors.current.onWarning

val ColorScheme.warningContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalGlideSemanticColors.current.warningContainer

val ColorScheme.onWarningContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalGlideSemanticColors.current.onWarningContainer

val ColorScheme.info: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalGlideSemanticColors.current.info

val ColorScheme.onInfo: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalGlideSemanticColors.current.onInfo

val ColorScheme.infoContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalGlideSemanticColors.current.infoContainer

val ColorScheme.onInfoContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalGlideSemanticColors.current.onInfoContainer

val ColorScheme.brandCyanAccent: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalGlideSemanticColors.current.brandAccent
