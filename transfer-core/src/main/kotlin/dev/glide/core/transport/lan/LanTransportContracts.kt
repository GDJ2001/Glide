package dev.glide.core.transport.lan

/**
 * Encapsulates an mDNS service discovery announcement for Glide LAN transport.
 */
data class LanMdnsRecord(
    val serviceName: String,
    val serviceType: String = DEFAULT_SERVICE_TYPE,
    val host: String,
    val port: Int,
    val attributes: Map<String, String> = emptyMap()
) {
    init {
        require(serviceName.isNotBlank()) { "serviceName must not be blank" }
        require(port in 1..65535) { "port must be between 1 and 65535" }
    }

    val protocolVersion: Int
        get() = attributes["v"]?.toIntOrNull() ?: 1

    val deviceName: String
        get() = attributes["dn"] ?: serviceName

    val deviceType: String
        get() = attributes["dt"] ?: "unknown"

    val certFingerprintPrefix: String?
        get() = attributes["fp"]

    val capabilityMask: Int
        get() = attributes["cap"]?.removePrefix("0x")?.toIntOrNull(16) ?: 0

    companion object {
        const val DEFAULT_SERVICE_TYPE = "_glide._tcp.local."
        const val ALPN_TOKEN = "glide-v1"

        fun createTxtAttributes(
            version: Int = 1,
            deviceName: String,
            deviceType: String = "desktop",
            certFingerprintPrefix: String? = null,
            capabilityMask: Int = 0x07
        ): Map<String, String> {
            val map = mutableMapOf(
                "v" to version.toString(),
                "dn" to deviceName.take(32),
                "dt" to deviceType,
                "alpn" to ALPN_TOKEN,
                "cap" to "0x%02X".format(capabilityMask)
            )
            if (certFingerprintPrefix != null) {
                map["fp"] = certFingerprintPrefix.take(8)
            }
            return map
        }
    }
}

/**
 * Legacy 4-byte big-endian length-prefixed control framing helper (ADR-0017).
 *
 * NOTE: For LAN Profile 1 framed streaming, use [LanRecordCodec] and [LanRecord], which implement
 * the 5-byte header (`kind: UByte` + `payloadLength: UInt32`) specified in ADR-0018 and master plan Section 6.
 * Retained for backward compatibility with early control tests.
 */
object LanFrameHeader {
    const val HEADER_SIZE = 4
    const val MAX_FRAME_SIZE = 16384 // 16 KiB strict limit for control frames

    fun encodeHeader(payloadLength: Int): ByteArray {
        require(payloadLength in 1..MAX_FRAME_SIZE) {
            "Payload length $payloadLength must be between 1 and $MAX_FRAME_SIZE bytes"
        }
        return byteArrayOf(
            ((payloadLength ushr 24) and 0xFF).toByte(),
            ((payloadLength ushr 16) and 0xFF).toByte(),
            ((payloadLength ushr 8) and 0xFF).toByte(),
            (payloadLength and 0xFF).toByte()
        )
    }

    fun decodeHeader(bytes: ByteArray, offset: Int = 0): Int {
        require(bytes.size >= offset + HEADER_SIZE) {
            "Insufficient bytes for length header: required $HEADER_SIZE, available ${bytes.size - offset}"
        }
        val length = ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

        require(length in 1..MAX_FRAME_SIZE) {
            "Decoded frame length $length exceeds maximum allowed bound of $MAX_FRAME_SIZE bytes"
        }
        return length
    }

    fun wrapFrame(payload: ByteArray): ByteArray {
        val header = encodeHeader(payload.size)
        val result = ByteArray(HEADER_SIZE + payload.size)
        System.arraycopy(header, 0, result, 0, HEADER_SIZE)
        System.arraycopy(payload, 0, result, HEADER_SIZE, payload.size)
        return result
    }

    fun unwrapFrame(frame: ByteArray): ByteArray {
        val payloadLength = decodeHeader(frame, 0)
        require(frame.size == HEADER_SIZE + payloadLength) {
            "Frame size ${frame.size} does not match header length ${HEADER_SIZE + payloadLength}"
        }
        val payload = ByteArray(payloadLength)
        System.arraycopy(frame, HEADER_SIZE, payload, 0, payloadLength)
        return payload
    }
}

/**
 * Validates and sanitizes relative file paths for safe storage on Windows filesystems.
 */
object WindowsPathSanitizer {

    private val RESERVED_DOS_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    private val ILLEGAL_CHARS_REGEX = Regex("""[<>:"/\\|?*\x00-\x1F]""")
    private val DRIVE_LETTER_REGEX = Regex("""^[a-zA-Z]:""")

    fun sanitize(relativePath: String): String {
        require(relativePath.isNotBlank()) { "Path must not be blank" }

        // Reject drive letter injections: C:, D: etc.
        require(!DRIVE_LETTER_REGEX.containsMatchIn(relativePath)) {
            "Drive letters are strictly forbidden: $relativePath"
        }

        // Reject UNC paths
        require(!relativePath.startsWith("//") && !relativePath.startsWith("\\\\")) {
            "UNC paths are strictly forbidden: $relativePath"
        }

        // Normalize backslashes to forward slashes for segment parsing
        val normalized = relativePath.replace('\\', '/')

        // Reject root slashes
        require(!normalized.startsWith("/")) {
            "Absolute root paths are forbidden: $relativePath"
        }

        val segments = normalized.split('/')
        require(segments.isNotEmpty()) { "Path contains no valid segments" }
        require(segments.size <= 16) { "Path depth exceeds maximum limit of 16" }

        val cleanSegments = mutableListOf<String>()

        for (segment in segments) {
            val trimmed = segment.trim()
            require(trimmed.isNotEmpty()) { "Empty segment in path: $relativePath" }
            require(trimmed != "." && trimmed != "..") {
                "Directory traversal sequence '$trimmed' forbidden: $relativePath"
            }
            require(trimmed.length <= 255) {
                "Path segment exceeds 255 characters: $trimmed"
            }

            // Check for illegal characters
            require(!ILLEGAL_CHARS_REGEX.containsMatchIn(trimmed)) {
                "Segment '$trimmed' contains illegal Windows characters"
            }

            // Check for reserved DOS device names (e.g. CON, NUL, AUX, PRN)
            val baseName = trimmed.substringBefore('.').uppercase()
            require(!RESERVED_DOS_NAMES.contains(baseName)) {
                "Segment '$trimmed' uses reserved DOS device name '$baseName'"
            }

            cleanSegments.add(trimmed)
        }

        val result = cleanSegments.joinToString("/")
        require(result.length <= 260) {
            "Total path length ${result.length} exceeds standard Windows MAX_PATH (260)"
        }
        return result
    }
}
