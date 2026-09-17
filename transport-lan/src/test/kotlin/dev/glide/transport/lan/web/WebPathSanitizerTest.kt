package dev.glide.transport.lan.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPathSanitizerTest {

    @Test
    fun `sanitize preserves normal filenames`() {
        assertEquals("vacation.jpg", WebPathSanitizer.sanitize("vacation.jpg"))
        assertEquals("report_2026.pdf", WebPathSanitizer.sanitize("report_2026.pdf"))
    }

    @Test
    fun `sanitize strips directory traversal sequences`() {
        val result = WebPathSanitizer.sanitize("../../etc/passwd")
        assertFalse(result.contains(".."))
        assertFalse(result.contains("/"))
        assertEquals("etc_passwd", result)
    }

    @Test
    fun `sanitize strips drive letter prefixes`() {
        val result = WebPathSanitizer.sanitize("C:\\Windows\\System32\\cmd.exe")
        assertFalse(result.startsWith("C:"))
        assertFalse(result.contains("\\"))
        assertEquals("Windows_System32_cmd.exe", result)
    }

    @Test
    fun `sanitize replaces illegal characters`() {
        val result = WebPathSanitizer.sanitize("test<file>:*\"|?name.txt")
        assertEquals("test_file______name.txt", result)
    }

    @Test
    fun `sanitize escapes Windows reserved DOS device names`() {
        assertEquals("_CON.txt", WebPathSanitizer.sanitize("CON.txt"))
        assertEquals("_prn.pdf", WebPathSanitizer.sanitize("prn.pdf"))
        assertEquals("_AUX.dat", WebPathSanitizer.sanitize("AUX.dat"))
        assertEquals("_nul", WebPathSanitizer.sanitize("nul"))
        assertEquals("_COM1.txt", WebPathSanitizer.sanitize("COM1.txt"))
        assertEquals("_lpt9.bin", WebPathSanitizer.sanitize("lpt9.bin"))
    }

    @Test
    fun `sanitize strips bidi control characters`() {
        val bidiPayload = "safe\u202Ecod.exe"
        val result = WebPathSanitizer.sanitize(bidiPayload)
        assertFalse(result.contains("\u202E"))
        assertEquals("safecod.exe", result)
    }

    @Test
    fun `sanitize handles empty null or blank inputs`() {
        assertEquals(WebPathSanitizer.FALLBACK_NAME, WebPathSanitizer.sanitize(null))
        assertEquals(WebPathSanitizer.FALLBACK_NAME, WebPathSanitizer.sanitize(""))
        assertEquals(WebPathSanitizer.FALLBACK_NAME, WebPathSanitizer.sanitize("   "))
        assertEquals(WebPathSanitizer.FALLBACK_NAME, WebPathSanitizer.sanitize("."))
        assertEquals(WebPathSanitizer.FALLBACK_NAME, WebPathSanitizer.sanitize(".."))
        assertEquals(WebPathSanitizer.FALLBACK_NAME, WebPathSanitizer.sanitize("..."))
        assertEquals(WebPathSanitizer.FALLBACK_NAME, WebPathSanitizer.sanitize("___"))
    }

    @Test
    fun `sanitize truncates overly long filenames`() {
        val longBase = "a".repeat(300)
        val sanitized = WebPathSanitizer.sanitize("$longBase.txt")
        assertTrue(sanitized.length <= 255)
    }

    @Test
    fun `resolveUniqueName avoids collisions`() {
        val existing = setOf("test.txt", "test (1).txt")
        val unique = WebPathSanitizer.resolveUniqueName("test.txt", { it in existing })
        assertEquals("test (2).txt", unique)
    }
}
