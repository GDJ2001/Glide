package dev.glide.designsystem

/**
 * Unicode bidirectional text isolation utilities complying with GLIDE_DESIGN_SYSTEM.md:
 * "Support RTL; isolate filenames and codes safely in bidirectional text, keep numerical code order."
 *
 * Unicode Directional Formatting Characters:
 * - U+2068 FIRST STRONG ISOLATE (FSI): Isolates text directionally based on its first strong character.
 * - U+2066 LEFT-TO-RIGHT ISOLATE (LRI): Forces the enclosed text to be treated as LTR regardless of context.
 * - U+2067 RIGHT-TO-LEFT ISOLATE (RLI): Forces the enclosed text to be treated as RTL regardless of context.
 * - U+2069 POP DIRECTIONAL ISOLATE (PDI): Terminates the scope of the last isolate character.
 */
object BidiIsolation {
    const val FSI: Char = '\u2068'
    const val LRI: Char = '\u2066'
    const val RLI: Char = '\u2067'
    const val PDI: Char = '\u2069'

    /**
     * Isolates a string (such as a filename, peer device name, or path) so that mixed LTR/RTL characters,
     * file extensions (e.g. .jpg, .pdf), or punctuation (e.g. parentheses, hyphens) do not reorder
     * or scramble the surrounding sentence grammar in bidirectional layouts.
     */
    fun isolate(text: String): String {
        if (text.isEmpty()) return text
        return "$FSI$text$PDI"
    }

    /**
     * Isolates a numeric or alphanumeric code (such as a 4-digit or 6-digit verification code)
     * enforcing strict left-to-right numerical order even within right-to-left layout contexts.
     */
    fun isolateCode(code: String): String {
        if (code.isEmpty()) return code
        return "$LRI$code$PDI"
    }

    /**
     * Strips all Unicode directional formatting characters from a string.
     */
    fun stripIsolates(text: String): String {
        if (text.isEmpty()) return text
        return text.filterNot { it == FSI || it == LRI || it == RLI || it == PDI }
    }
}

/**
 * Convenience extension to wrap a string in Unicode First Strong Isolate (FSI) and PDI.
 */
fun String.isolateBidi(): String = BidiIsolation.isolate(this)

/**
 * Convenience extension to wrap a code in Unicode Left-to-Right Isolate (LRI) and PDI.
 */
fun String.isolateBidiCode(): String = BidiIsolation.isolateCode(this)
