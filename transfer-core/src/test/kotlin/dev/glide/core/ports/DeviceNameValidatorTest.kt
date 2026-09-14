package dev.glide.core.ports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceNameValidatorTest {

    @Test
    fun `valid name preserves characters and passes`() {
        val result = DeviceNameValidator.validate("Pixel 9 Pro")
        assertTrue(result.isSuccess)
        assertEquals("Pixel 9 Pro", result.getOrNull())
    }

    @Test
    fun `whitespace is trimmed from both sides`() {
        val result = DeviceNameValidator.validate("   My Phone   ")
        assertTrue(result.isSuccess)
        assertEquals("My Phone", result.getOrNull())
    }

    @Test
    fun `null name fails`() {
        val result = DeviceNameValidator.validate(null)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `blank or whitespace only name fails`() {
        val result = DeviceNameValidator.validate("   \t \n  ")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("blank") == true)
    }

    @Test
    fun `name exceeding 32 characters fails`() {
        val longName = "A".repeat(33)
        val result = DeviceNameValidator.validate(longName)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("cannot exceed 32") == true)
    }

    @Test
    fun `name at exact 32 characters passes`() {
        val exactName = "A".repeat(32)
        val result = DeviceNameValidator.validate(exactName)
        assertTrue(result.isSuccess)
        assertEquals(32, result.getOrNull()?.length)
    }

    @Test
    fun `strips bidi control and ISO control characters`() {
        // \u200E is LTR mark, \u0007 is bell
        val dirty = "\u200EMy\u0007 Device\u202E"
        val result = DeviceNameValidator.validate(dirty)
        assertTrue(result.isSuccess)
        assertEquals("My Device", result.getOrNull())
    }

    @Test
    fun `nfc normalizes unicode characters`() {
        // e + combining acute accent -> single é in NFC
        val decomposed = "Cafe\u0301"
        val result = DeviceNameValidator.validate(decomposed)
        assertTrue(result.isSuccess)
        assertEquals("Café", result.getOrNull())
    }
}
