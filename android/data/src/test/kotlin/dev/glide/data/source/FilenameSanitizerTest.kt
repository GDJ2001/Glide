package dev.glide.data.source

import dev.glide.core.domain.ProtocolLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

class FilenameSanitizerTest {

    @Test
    fun `sanitize normalizes unicode to NFC`() {
        // Decomposed 'e' + combining acute accent -> precomposed 'é'
        val decomposed = "caf\u0065\u0301.pdf"
        val sanitized = FilenameSanitizer.sanitize(decomposed)
        assertEquals("café.pdf", sanitized)
        assertTrue(Normalizer.isNormalized(sanitized, Normalizer.Form.NFC))
    }

    @Test
    fun `sanitize strips bidirectional control characters`() {
        // Right-to-left override attempt: e.g. text\u202Efdp.exe -> textfdp.exe
        val bidiName = "important_\u202Eexe.doc\u202C.pdf"
        val sanitized = FilenameSanitizer.sanitize(bidiName)
        assertFalse(sanitized.contains("\u202E"))
        assertFalse(sanitized.contains("\u202C"))
        assertEquals("important_exe.doc.pdf", sanitized)
    }

    @Test
    fun `sanitize replaces path traversal separators and illegal characters`() {
        val traversalName = "../../secret/passwords.txt"
        val sanitized = FilenameSanitizer.sanitize(traversalName)
        assertFalse(sanitized.contains("/"))
        assertFalse(sanitized.contains("\\"))
        assertEquals("_.._secret_passwords.txt", sanitized)

        val illegalChars = "file:name?*<bar>|\"test.png"
        val sanitizedChars = FilenameSanitizer.sanitize(illegalChars)
        assertEquals("file_name___bar___test.png", sanitizedChars)
    }

    @Test
    fun `sanitize prefixes Windows reserved device names`() {
        val reservedNames = listOf(
            "CON" to "_CON",
            "con.txt" to "_con.txt",
            "PRN.pdf" to "_PRN.pdf",
            "aux.png" to "_aux.png",
            "NUL" to "_NUL",
            "com1.zip" to "_com1.zip",
            "COM9.dat" to "_COM9.dat",
            "lpt1.log" to "_lpt1.log",
            "LPT8.tar.gz" to "_LPT8.tar.gz"
        )

        for ((input, expected) in reservedNames) {
            val sanitized = FilenameSanitizer.sanitize(input)
            assertEquals("Expected prefix for reserved name $input", expected, sanitized)
        }

        // Non-reserved words with reserved prefixes should not be modified
        assertEquals("contact.txt", FilenameSanitizer.sanitize("contact.txt"))
        assertEquals("prn_report.pdf", FilenameSanitizer.sanitize("prn_report.pdf"))
        assertEquals("auxiliary.png", FilenameSanitizer.sanitize("auxiliary.png"))
        assertEquals("com10.zip", FilenameSanitizer.sanitize("com10.zip"))
    }

    @Test
    fun `sanitize falls back on empty blank or navigation-only names`() {
        assertEquals("Received file", FilenameSanitizer.sanitize(null))
        assertEquals("Received file", FilenameSanitizer.sanitize(""))
        assertEquals("Received file", FilenameSanitizer.sanitize("   "))
        assertEquals("Received file", FilenameSanitizer.sanitize("."))
        assertEquals("Received file", FilenameSanitizer.sanitize(".."))
        assertEquals("Received file", FilenameSanitizer.sanitize("..."))
        assertEquals("Received file", FilenameSanitizer.sanitize("___"))
        assertEquals("Custom fallback", FilenameSanitizer.sanitize(null, fallbackName = "Custom fallback"))
    }

    @Test
    fun `sanitize enforces maximum code points and UTF-8 byte limits`() {
        val longName = "a".repeat(300) + ".txt"
        val sanitized = FilenameSanitizer.sanitize(longName)
        assertTrue(sanitized.codePointCount(0, sanitized.length) <= ProtocolLimits.MAX_FILENAME_CODE_POINTS)
        assertTrue(sanitized.toByteArray(Charsets.UTF_8).size <= ProtocolLimits.MAX_FILENAME_UTF8_BYTES)

        // 4-byte emojis
        val emojiName = "\uD83D\uDE00".repeat(300)
        val sanitizedEmoji = FilenameSanitizer.sanitize(emojiName)
        assertTrue(sanitizedEmoji.codePointCount(0, sanitizedEmoji.length) <= ProtocolLimits.MAX_FILENAME_CODE_POINTS)
        assertTrue(sanitizedEmoji.toByteArray(Charsets.UTF_8).size <= ProtocolLimits.MAX_FILENAME_UTF8_BYTES)
    }

    @Test
    fun `resolveUniqueName returns original name if no collision`() {
        val unique = FilenameSanitizer.resolveUniqueName(
            desiredName = "photo.jpg",
            existsPredicate = { false }
        )
        assertEquals("photo.jpg", unique)
    }

    @Test
    fun `resolveUniqueName increments index on collision`() {
        val existing = setOf("photo.jpg", "photo (1).jpg")
        val unique = FilenameSanitizer.resolveUniqueName(
            desiredName = "photo.jpg",
            existsPredicate = { it in existing }
        )
        assertEquals("photo (2).jpg", unique)
    }

    @Test
    fun `resolveUniqueName handles files without extension`() {
        val existing = setOf("README", "README (1)")
        val unique = FilenameSanitizer.resolveUniqueName(
            desiredName = "README",
            existsPredicate = { it in existing }
        )
        assertEquals("README (2)", unique)
    }

    @Test
    fun `resolveUniqueName handles Windows reserved names with duplicates`() {
        val existing = setOf("_CON.txt")
        val unique = FilenameSanitizer.resolveUniqueName(
            desiredName = "CON.txt",
            existsPredicate = { it in existing }
        )
        assertEquals("_CON (1).txt", unique)
    }
}
