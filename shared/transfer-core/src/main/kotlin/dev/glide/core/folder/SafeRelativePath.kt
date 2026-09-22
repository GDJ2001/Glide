package dev.glide.core.folder

/**
 * Validated, canonical relative path conforming to the Safe Folder Transfer Specification (GLIDE-069).
 *
 * Invariants:
 * 1. Normalized forward slash '/' delimiters.
 * 2. Strict rejection of '..' (parent directory traversal) and '.' (current directory).
 * 3. Strict rejection of leading slashes ('/', '\'), drive letters ('C:'), and UNC prefixes ('\\').
 * 4. Strict rejection of null bytes (0x00) and ASCII control characters (< 0x20).
 * 5. Strict rejection of illegal filename characters: ':', '*', '?', '"', '<', '>', '|'.
 * 6. Strict rejection of Windows reserved names: CON, PRN, AUX, NUL, COM1-9, LPT1-9.
 * 7. Enforces bounds: max depth <= 16, max total length <= 1024 bytes, max segment length <= 255 bytes.
 */
class SafeRelativePath private constructor(
    val normalizedPath: String,
    val segments: List<String>
) {
    val depth: Int get() = segments.size - 1 // e.g. "photos/beach.jpg" -> depth 1
    val fileName: String get() = segments.last()
    val parentPath: String? get() = if (segments.size > 1) segments.dropLast(1).joinToString("/") else null

    companion object {
        const val MAX_DEPTH = 16
        const val MAX_PATH_LENGTH = 1024
        const val MAX_SEGMENT_LENGTH = 255

        private val FORBIDDEN_CHARS = setOf(':', '*', '?', '"', '<', '>', '|')
        private val RESERVED_NAMES = setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
        )

        /**
         * Validates and creates a [SafeRelativePath].
         * Returns [Result.success] with the canonical path, or [Result.failure] if any invariant is violated.
         */
        fun parse(rawPath: String?): Result<SafeRelativePath> {
            if (rawPath.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("Relative path cannot be null or blank"))
            }

            if (rawPath.length > MAX_PATH_LENGTH) {
                return Result.failure(IllegalArgumentException("Relative path exceeds max length of $MAX_PATH_LENGTH characters"))
            }

            // Reject null bytes or control characters
            for (ch in rawPath) {
                if (ch.code < 0x20 || ch.code == 0x7F) {
                    return Result.failure(IllegalArgumentException("Path contains illegal control character or null byte (code: ${ch.code})"))
                }
                if (ch in FORBIDDEN_CHARS) {
                    return Result.failure(IllegalArgumentException("Path contains forbidden character '$ch'"))
                }
            }

            // Reject Windows drive letters (e.g. C:, D:)
            if (rawPath.length >= 2 && rawPath[1] == ':' && rawPath[0].isLetter()) {
                return Result.failure(IllegalArgumentException("Absolute drive letter path rejected: $rawPath"))
            }

            // Reject leading slash or backslash
            if (rawPath.startsWith('/') || rawPath.startsWith('\\')) {
                return Result.failure(IllegalArgumentException("Path cannot start with a root delimiter: $rawPath"))
            }

            // Reject Windows backslash in relative path (require POSIX forward slash)
            if (rawPath.contains('\\')) {
                return Result.failure(IllegalArgumentException("Windows backslash '\\' not permitted; use POSIX forward slash '/'"))
            }

            // Split into segments
            val rawSegments = rawPath.split('/')
            val cleanSegments = mutableListOf<String>()

            for (seg in rawSegments) {
                if (seg.isEmpty()) {
                    return Result.failure(IllegalArgumentException("Empty path segment detected in $rawPath"))
                }
                if (seg == ".." || seg == ".") {
                    return Result.failure(IllegalArgumentException("Path traversal segment '$seg' strictly forbidden in $rawPath"))
                }
                if (seg.length > MAX_SEGMENT_LENGTH) {
                    return Result.failure(IllegalArgumentException("Path segment exceeds max length of $MAX_SEGMENT_LENGTH: $seg"))
                }
                val baseName = seg.substringBefore('.')
                if (baseName.uppercase() in RESERVED_NAMES) {
                    return Result.failure(IllegalArgumentException("Reserved device name '$seg' strictly forbidden"))
                }
                cleanSegments.add(seg)
            }

            // Check depth bound (directory levels = segments - 1)
            val dirDepth = cleanSegments.size - 1
            if (dirDepth > MAX_DEPTH) {
                return Result.failure(IllegalArgumentException("Directory depth $dirDepth exceeds max permitted depth of $MAX_DEPTH"))
            }

            val normalized = cleanSegments.joinToString("/")
            return Result.success(SafeRelativePath(normalized, cleanSegments))
        }
    }

    override fun toString(): String = normalizedPath
    override fun equals(other: Any?): Boolean = other is SafeRelativePath && other.normalizedPath == normalizedPath
    override fun hashCode(): Int = normalizedPath.hashCode()
}
