package dev.glide.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BaselineUnitTest {
    @Test
    fun baselineEnvironment_isWorking() {
        val expected = "Glide"
        val actual = "Glide"
        assertEquals(expected, actual)
    }
}
