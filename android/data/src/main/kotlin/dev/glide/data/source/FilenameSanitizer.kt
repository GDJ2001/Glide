package dev.glide.data.source

import dev.glide.core.domain.ProtocolLimits
import java.text.Normalizer
import java.util.Locale

/**
 * Sanitizes untrusted display names according to security requirements (Threat T06):
 * - Normalizes to Unicode NFC.
 * - Strips directory separators, traversal segments, and control characters.
 * - Replaces filesystem-illegal characters across platforms (: * ? " < > |).
 * - Removes bidirectional override markers.
 * - Avoids Windows reserved device names (CON, PRN, AUX, NUL, COM1-9, LPT1-9).
 * - Enforces length caps: at most 255 code points and 1024 UTF-8 bytes.
 * - Resolves safe duplicate names using "name (n).ext" pattern with bounded retry.
 * - Falls back to a safe default name if empty or invalid.
 */
object FilenameSanitizer {

    const val FALLBACK_NAME = "Received file"

    private val BIDI_CONTROL_REGEX = Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069]")
    private val INVALID_CHARS_REGEX = Regex("[\\x00-\\x1F\\x7F/\\\\:*?\"<>|]")

    private val WINDOWS_RESERVED_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM0", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT0", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    fun sanitize(rawName: String?, fallbackName: String = FALLBACK_NAME): String {
        if (rawName.isNullOrBlank()) {
            return fallbackName
        }

        // 1. Unicode NFC normalization
        val nfc = Normalizer.normalize(rawName, Normalizer.Form.NFC)

        // 2. Remove bidi controls and invalid chars (separators, controls, illegal filesystem chars)
        var cleaned = nfc.replace(BIDI_CONTROL_REGEX, "")
            .replace(INVALID_CHARS_REGEX, "_")

        // 3. Remove leading/trailing dots and whitespace
        cleaned = cleaned.trim().trim { it == '.' || it.isWhitespace() }

        // 4. Reject relative navigation indicators or empty/underscore-only strings
        if (cleaned.isEmpty() || cleaned == "." || cleaned == ".." || cleaned.all { it == '_' || it == '.' }) {
            return fallbackName
        }

        // 5. Avoid Windows reserved device names by prefixing '_'
        val baseName = cleaned.substringBefore('.').uppercase(Locale.ROOT)
        if (WINDOWS_RESERVED_NAMES.contains(baseName)) {
            cleaned = "_$cleaned"
        }

        // 6. Truncate to maximum code points
        if (cleaned.codePointCount(0, cleaned.length) > ProtocolLimits.MAX_FILENAME_CODE_POINTS) {
            val endOffset = cleaned.offsetByCodePoints(0, ProtocolLimits.MAX_FILENAME_CODE_POINTS)
            cleaned = cleaned.substring(0, endOffset)
        }

        // 7. Truncate to maximum UTF-8 bytes without splitting code points
        var utf8Bytes = cleaned.toByteArray(Charsets.UTF_8)
        if (utf8Bytes.size > ProtocolLimits.MAX_FILENAME_UTF8_BYTES) {
            var validSubstr = cleaned
            while (validSubstr.isNotEmpty() && validSubstr.toByteArray(Charsets.UTF_8).size > ProtocolLimits.MAX_FILENAME_UTF8_BYTES) {
                val codePoints = validSubstr.codePointCount(0, validSubstr.length)
                validSubstr = validSubstr.substring(0, validSubstr.offsetByCodePoints(0, codePoints - 1))
            }
            cleaned = if (validSubstr.isBlank()) fallbackName else validSubstr
        }

        return cleaned.ifBlank { fallbackName }
    }

    /**
     * Resolves a safe duplicate name using "name (n).ext" pattern with bounded retry.
     * Guaranteed to never check-then-write; callers provide an existence / exclusive creation predicate.
     */
    fun resolveUniqueName(
        desiredName: String,
        existsPredicate: (String) -> Boolean,
        maxAttempts: Int = 1000,
        fallbackName: String = FALLBACK_NAME
    ): String {
        val sanitized = sanitize(desiredName, fallbackName)
        if (!existsPredicate(sanitized)) {
            return sanitized
        }

        val dotIndex = sanitized.lastIndexOf('.')
        val (rawBase, ext) = if (dotIndex > 0) {
            sanitized.substring(0, dotIndex) to sanitized.substring(dotIndex)
        } else {
            sanitized to ""
        }

        for (i in 1..maxAttempts) {
            val suffix = " ($i)$ext"
            val maxBaseCodePoints = (ProtocolLimits.MAX_FILENAME_CODE_POINTS - suffix.codePointCount(0, suffix.length)).coerceAtLeast(1)
            val candidateBase = if (rawBase.codePointCount(0, rawBase.length) > maxBaseCodePoints) {
                val endOffset = rawBase.offsetByCodePoints(0, maxBaseCodePoints)
                rawBase.substring(0, endOffset)
            } else {
                rawBase
            }
            val candidate = "$candidateBase$suffix"
            if (!existsPredicate(candidate)) {
                return candidate
            }
        }

        val timestampSuffix = " (${System.currentTimeMillis()})$ext"
        return "$rawBase$timestampSuffix"
    }
}
