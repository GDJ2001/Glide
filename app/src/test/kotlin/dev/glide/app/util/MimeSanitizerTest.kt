package dev.glide.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MimeSanitizerTest {

    @Test
    fun sanitize_validStandardMimes_returnsNormalizedLowercase() {
        assertEquals("image/jpeg", MimeSanitizer.sanitize("image/jpeg"))
        assertEquals("image/png", MimeSanitizer.sanitize("IMAGE/PNG"))
        assertEquals("application/pdf", MimeSanitizer.sanitize("Application/Pdf"))
        assertEquals("text/plain", MimeSanitizer.sanitize("text/plain"))
        assertEquals("video/mp4", MimeSanitizer.sanitize("video/mp4"))
        assertEquals("application/vnd.android.package-archive", MimeSanitizer.sanitize("application/vnd.android.package-archive"))
    }

    @Test
    fun sanitize_stripsParametersAfterSemicolon() {
        assertEquals("text/plain", MimeSanitizer.sanitize("text/plain; charset=utf-8"))
        assertEquals("application/json", MimeSanitizer.sanitize("application/json;odata=verbose"))
    }

    @Test
    fun sanitize_wildcard_preserved() {
        assertEquals("*/*", MimeSanitizer.sanitize("*/*"))
    }

    @Test
    fun sanitize_nullOrBlank_returnsFallback() {
        assertEquals("application/octet-stream", MimeSanitizer.sanitize(null))
        assertEquals("application/octet-stream", MimeSanitizer.sanitize(""))
        assertEquals("application/octet-stream", MimeSanitizer.sanitize("   "))
    }

    @Test
    fun sanitize_invalidOrDangerousCharacters_returnsFallback() {
        // Path traversal
        assertEquals("application/octet-stream", MimeSanitizer.sanitize("../../../evil"))
        // Quotes and spaces
        assertEquals("application/octet-stream", MimeSanitizer.sanitize("image/\"png\""))
        assertEquals("application/octet-stream", MimeSanitizer.sanitize("image/ png"))
        // No slash
        assertEquals("application/octet-stream", MimeSanitizer.sanitize("notamime"))
        // Control characters
        assertEquals("application/octet-stream", MimeSanitizer.sanitize("image/\u0000png"))
        // Excessive length
        val longMime = "application/" + "a".repeat(200)
        assertEquals("application/octet-stream", MimeSanitizer.sanitize(longMime))
    }
}
