package dev.glide.designsystem

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test suite verifying device posture calculation, hinge boundaries,
 * and occlusion logic conforming to GLIDE_DESIGN_SYSTEM.md (GLIDE-045).
 */
class PostureTest {

    @Test
    fun calculateDevicePosture_withoutHinge_returnsNormalPosture() {
        val result = calculateDevicePosture(
            windowWidth = 411.dp,
            windowHeight = 923.dp,
            hinge = null
        )

        assertEquals(DevicePosture.Normal, result.posture)
        assertFalse(result.isBook)
        assertFalse(result.isTabletop)
        assertFalse(result.hasActiveHinge)
    }

    @Test
    fun calculateDevicePosture_withFlatNonSeparatingHinge_returnsNormalPosture() {
        val hinge = HingeBounds(
            leftDp = 400.dp,
            topDp = 0.dp,
            rightDp = 400.dp,
            bottomDp = 800.dp,
            isOccluding = false,
            isSeparating = false,
            orientation = HingeOrientation.Vertical
        )

        val result = calculateDevicePosture(
            windowWidth = 800.dp,
            windowHeight = 800.dp,
            hinge = hinge
        )

        assertEquals(DevicePosture.Normal, result.posture)
        assertFalse(result.hasActiveHinge)
    }

    @Test
    fun calculateDevicePosture_withVerticalSeparatingHinge_returnsBookPosture() {
        val hinge = HingeBounds(
            leftDp = 410.dp,
            topDp = 0.dp,
            rightDp = 430.dp,
            bottomDp = 900.dp,
            isOccluding = true,
            isSeparating = true,
            orientation = HingeOrientation.Vertical
        )

        val result = calculateDevicePosture(
            windowWidth = 840.dp,
            windowHeight = 900.dp,
            hinge = hinge
        )

        assertEquals(DevicePosture.Book, result.posture)
        assertTrue(result.isBook)
        assertTrue(result.isSeparating)
        assertTrue(result.isOccluding)
        assertTrue(result.hasActiveHinge)
        assertEquals(20.dp, hinge.widthDp)
    }

    @Test
    fun calculateDevicePosture_withHorizontalSeparatingHinge_returnsTabletopPosture() {
        val hinge = HingeBounds(
            leftDp = 0.dp,
            topDp = 440.dp,
            rightDp = 800.dp,
            bottomDp = 460.dp,
            isOccluding = false,
            isSeparating = true,
            orientation = HingeOrientation.Horizontal
        )

        val result = calculateDevicePosture(
            windowWidth = 800.dp,
            windowHeight = 900.dp,
            hinge = hinge
        )

        assertEquals(DevicePosture.Tabletop, result.posture)
        assertTrue(result.isTabletop)
        assertTrue(result.isSeparating)
        assertFalse(result.isOccluding)
        assertTrue(result.hasActiveHinge)
        assertEquals(20.dp, hinge.heightDp)
    }
}
