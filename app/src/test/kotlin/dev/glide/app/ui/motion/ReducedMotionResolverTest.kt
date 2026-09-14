package dev.glide.app.ui.motion

import dev.glide.core.ports.ReducedMotionPreference
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ReducedMotionResolver].
 * Verifies system scale detection, preference overrides, and testing overrides (GLIDE-041).
 */
class ReducedMotionResolverTest {

    @After
    fun tearDown() {
        ReducedMotionResolver.testOverride = null
    }

    @Test
    fun preferenceOn_alwaysResolvesToTrue() {
        val result = ReducedMotionResolver.isReducedMotionActive(
            preference = ReducedMotionPreference.ON,
            context = null
        )
        assertTrue("ReducedMotionPreference.ON must resolve to true", result)
    }

    @Test
    fun preferenceOff_alwaysResolvesToFalse() {
        val result = ReducedMotionResolver.isReducedMotionActive(
            preference = ReducedMotionPreference.OFF,
            context = null
        )
        assertFalse("ReducedMotionPreference.OFF must resolve to false", result)
    }

    @Test
    fun preferenceSystem_nullContext_defaultsToFalse() {
        val result = ReducedMotionResolver.isReducedMotionActive(
            preference = ReducedMotionPreference.SYSTEM,
            context = null
        )
        assertFalse("ReducedMotionPreference.SYSTEM with null context should default to false", result)
    }

    @Test
    fun testOverride_takesPrecedenceOverPreferences() {
        ReducedMotionResolver.testOverride = true
        assertTrue(ReducedMotionResolver.isReducedMotionActive(ReducedMotionPreference.OFF, null))

        ReducedMotionResolver.testOverride = false
        assertFalse(ReducedMotionResolver.isReducedMotionActive(ReducedMotionPreference.ON, null))
    }
}
