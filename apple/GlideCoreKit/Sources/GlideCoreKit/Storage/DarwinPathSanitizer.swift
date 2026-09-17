import Foundation

/// Darwin / POSIX filesystem path sanitizer for macOS, iOS, and iPadOS.
/// Conforms to Section 7.3 of APPLE_PLATFORMS_SPECIFICATION.md and ADR-0020.
public enum DarwinPathSanitizer {
    public static let maxFilenameBytes = 255
    public static let defaultFallbackName = "unnamed_file"

    private static let forbiddenCharacters = CharacterSet(charactersIn: "/:\\\0\r\n\t")
        .union(.controlCharacters)

    /// Sanitizes an untrusted filename received from an external peer.
    public static func sanitizeFilename(_ rawFilename: String) -> String {
        var name = rawFilename.trimmingCharacters(in: .whitespacesAndNewlines)

        // 1. Unicode Normalization to Form C (Canonical Decomposition followed by Canonical Composition)
        name = name.precomposedStringWithCanonicalMapping

        // 2. Strip directory traversal tokens
        while name.contains("..") {
            name = name.replacingOccurrences(of: "..", with: "")
        }

        // 3. Remove forbidden characters (/, :, \, null, control characters)
        name = name.unicodeScalars.filter { scalar in
            !forbiddenCharacters.contains(scalar)
        }.reduce("") { $0 + String($1) }

        // 4. Strip leading dots (hidden files) and trim whitespace
        name = name.trimmingCharacters(in: CharacterSet(charactersIn: ".").union(.whitespaces))

        if name.isEmpty {
            name = defaultFallbackName
        }

        // 5. Enforce 255-byte UTF-8 limit while preserving extension
        return truncateToMaxBytes(name, maxBytes: maxFilenameBytes)
    }

    /// Truncates a filename to at most `maxBytes` UTF-8 bytes while preserving its extension.
    public static func truncateToMaxBytes(_ filename: String, maxBytes: Int) -> String {
        let utf8Count = filename.utf8.count
        if utf8Count <= maxBytes {
            return filename
        }

        let ext = (filename as NSString).pathExtension
        let hasExt = !ext.isEmpty && ext.count < 32
        let dotExt = hasExt ? ".\(ext)" : ""
        let dotExtBytes = dotExt.utf8.count

        let maxBaseBytes = max(1, maxBytes - dotExtBytes)
        let baseName = hasExt ? (filename as NSString).deletingPathExtension : filename

        var truncatedBase = ""
        var currentBytes = 0

        for char in baseName {
            let charBytes = String(char).utf8.count
            if currentBytes + charBytes > maxBaseBytes {
                break
            }
            truncatedBase.append(char)
            currentBytes += charBytes
        }

        if truncatedBase.isEmpty {
            truncatedBase = "file"
        }

        return truncatedBase + dotExt
    }

    /// Resolves filename collisions by appending ` (1)`, ` (2)`, etc. before the extension.
    public static func resolveCollision(filename: String, existingNames: Set<String>) -> String {
        if !existingNames.contains(filename) {
            return filename
        }

        let ext = (filename as NSString).pathExtension
        let hasExt = !ext.isEmpty
        let baseName = hasExt ? (filename as NSString).deletingPathExtension : filename
        let dotExt = hasExt ? ".\(ext)" : ""

        var counter = 1
        while true {
            let candidate = "\(baseName) (\(counter))\(dotExt)"
            if !existingNames.contains(candidate) {
                return candidate
            }
            counter += 1
        }
    }
}
