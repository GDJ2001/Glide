package dev.glide.designsystem

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Device posture classifications conforming to Android foldable guidance and GLIDE_DESIGN_SYSTEM.md.
 */
enum class DevicePosture {
    /** Standard single-screen or continuous display without an active separating hinge. */
    Normal,

    /** Half-folded with vertical hinge separating left and right displays (reading / dual-pane posture). */
    Book,

    /** Half-folded with horizontal hinge separating top display and bottom controls (laptop / tabletop posture). */
    Tabletop,

    /** Foldable device opened completely flat (180 degrees). */
    Flat;

    val isNormal: Boolean get() = this == Normal
    val isBook: Boolean get() = this == Book
    val isTabletop: Boolean get() = this == Tabletop
    val isFlat: Boolean get() = this == Flat
}

/**
 * Orientation of a physical display fold or hinge feature.
 */
enum class HingeOrientation {
    None,
    Horizontal,
    Vertical;

    val isHorizontal: Boolean get() = this == Horizontal
    val isVertical: Boolean get() = this == Vertical
}

/**
 * Geometric and physical properties of a display fold, hinge, or occlusion.
 *
 * @property leftDp Left boundary of the hinge in window coordinates.
 * @property topDp Top boundary of the hinge in window coordinates.
 * @property rightDp Right boundary of the hinge in window coordinates.
 * @property bottomDp Bottom boundary of the hinge in window coordinates.
 * @property isOccluding True if the hinge physically covers pixels (e.g. physical bezel between dual screens).
 * @property isSeparating True if the hinge logically/physically separates the window into two distinct display areas.
 * @property orientation Whether the hinge runs horizontally or vertically.
 */
data class HingeBounds(
    val leftDp: Dp = 0.dp,
    val topDp: Dp = 0.dp,
    val rightDp: Dp = 0.dp,
    val bottomDp: Dp = 0.dp,
    val isOccluding: Boolean = false,
    val isSeparating: Boolean = false,
    val orientation: HingeOrientation = HingeOrientation.None
) {
    val widthDp: Dp get() = (rightDp - leftDp).coerceAtLeast(0.dp)
    val heightDp: Dp get() = (bottomDp - topDp).coerceAtLeast(0.dp)
}

/**
 * Comprehensive device posture information.
 */
data class DevicePostureInfo(
    val posture: DevicePosture = DevicePosture.Normal,
    val hinge: HingeBounds? = null
) {
    val isSeparating: Boolean get() = hinge?.isSeparating == true
    val isOccluding: Boolean get() = hinge?.isOccluding == true
    val isBook: Boolean get() = posture.isBook
    val isTabletop: Boolean get() = posture.isTabletop
    val isFlat: Boolean get() = posture.isFlat

    /**
     * True if the device has an active hinge that separates panes or occludes content.
     */
    val hasActiveHinge: Boolean get() = hinge != null && (isSeparating || isOccluding)
}

/**
 * Pure calculation function for [DevicePostureInfo].
 */
fun calculateDevicePosture(
    windowWidth: Dp,
    windowHeight: Dp,
    hinge: HingeBounds?
): DevicePostureInfo {
    if (hinge == null || (!hinge.isSeparating && !hinge.isOccluding)) {
        return DevicePostureInfo(
            posture = DevicePosture.Normal,
            hinge = hinge
        )
    }

    val posture = when (hinge.orientation) {
        HingeOrientation.Vertical -> DevicePosture.Book
        HingeOrientation.Horizontal -> DevicePosture.Tabletop
        HingeOrientation.None -> DevicePosture.Flat
    }

    return DevicePostureInfo(
        posture = posture,
        hinge = hinge
    )
}

/**
 * CompositionLocal providing [DevicePostureInfo] down the Compose hierarchy.
 * Default is [DevicePosture.Normal].
 */
val LocalDevicePosture = compositionLocalOf {
    DevicePostureInfo(posture = DevicePosture.Normal, hinge = null)
}

/**
 * Modifier that adds safety padding to ensure interactive controls and critical text
 * are never placed directly under an occluding or separating hinge.
 */
fun Modifier.avoidHinge(
    hinge: HingeBounds?,
    additionalPadding: Dp = 0.dp
): Modifier {
    if (hinge == null || (!hinge.isOccluding && !hinge.isSeparating)) {
        return if (additionalPadding > 0.dp) padding(additionalPadding) else this
    }

    return when (hinge.orientation) {
        HingeOrientation.Vertical -> {
            // Apply horizontal safety buffer around the hinge width
            val buffer = (hinge.widthDp / 2) + additionalPadding
            padding(start = buffer, end = buffer)
        }
        HingeOrientation.Horizontal -> {
            // Apply vertical safety buffer around the hinge height
            val buffer = (hinge.heightDp / 2) + additionalPadding
            padding(top = buffer, bottom = buffer)
        }
        HingeOrientation.None -> {
            if (additionalPadding > 0.dp) padding(additionalPadding) else this
        }
    }
}
