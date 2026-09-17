package dev.glide.app.util

import dev.glide.core.domain.ProtocolLimits
import java.util.Locale

/**
 * Strict MIME type sanitization utility conforming to GLIDE_PROTOCOL_V1 and Android intent safety.
 *
 * Ensures MIME types passed to Android intents or stored in catalog are safe from injection,
 * malicious schemes, or malformed characters. Always falls back to "application/octet-stream"
 * when given an invalid, blank, or suspicious MIME type.
 */
object MimeSanitizer {

    private const val FALLBACK_MIME_TYPE = "application/octet-stream"
    private const val WILDCARD_MIME_TYPE = "*/*"

    // RFC 2045/2046 compliant pattern for type/subtype with allowed token characters
    private val MIME_REGEX = Regex("^[a-zA-Z0-9!#\\$&.+_^-]+/[a-zA-Z0-9!#\\$&.+_^-]+\$")

    /**
     * Sanitizes [mimeType] to ensure it is safe for Android Intent resolution and FileProvider.
     *
     * Rules:
     * - Returns WILDCARD_MIME_TYPE if input is explicitly wildcard
     * - Strips leading and trailing whitespace
     * - Discards parameters (e.g. "text/plain; charset=utf-8" -> "text/plain")
     * - Rejects any string with path traversal, quotes, spaces, control chars, or length > [ProtocolLimits.MAX_MIME_LENGTH]
     * - Normalizes to lowercase ASCII
     * - If invalid, returns "application/octet-stream"
     */
    fun sanitize(mimeType: String?): String {
        if (mimeType.isNullOrBlank()) {
            return FALLBACK_MIME_TYPE
        }

        val trimmed = mimeType.trim()
        if (trimmed == WILDCARD_MIME_TYPE) {
            return WILDCARD_MIME_TYPE
        }

        // Strip parameters after semicolon if present
        val baseMime = trimmed.substringBefore(';').trim()
        if (baseMime.isEmpty() || baseMime.length > ProtocolLimits.MAX_MIME_LENGTH) {
            return FALLBACK_MIME_TYPE
        }

        if (!MIME_REGEX.matches(baseMime)) {
            return FALLBACK_MIME_TYPE
        }

        return baseMime.lowercase(Locale.ROOT)
    }
}
