package dev.glide.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreUnitTest {
    @Test
    fun coreMarker_isAvailable() {
        val marker = object : CoreMarker {
            override val version: String = "1.0.0"
        }
        assertEquals("1.0.0", marker.version)
    }
}
