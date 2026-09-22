package dev.glide.core.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class StrictJsonParser(
    private val text: String,
    private val maxDepth: Int = 8
) {
    private var index = 0
    private var currentDepth = 0

    fun parse(): JsonObject {
        skipWhitespace()
        if (index >= text.length) {
            throw IllegalArgumentException("Unexpected end of JSON input: payload is empty")
        }
        val element = parseElement()
        skipWhitespace()
        if (index < text.length) {
            throw IllegalArgumentException("Trailing characters after root JSON element at character index $index")
        }
        if (element !is JsonObject) {
            throw IllegalArgumentException("Root JSON payload must be an object, but got ${element::class.simpleName}")
        }
        return element
    }

    private fun parseElement(): JsonElement {
        skipWhitespace()
        if (index >= text.length) {
            throw IllegalArgumentException("Unexpected end of JSON input at index $index")
        }
        return when (val c = text[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            '-', in '0'..'9' -> parseNumber()
            else -> throw IllegalArgumentException("Unexpected character '$c' at index $index")
        }
    }

    private fun parseObject(): JsonObject {
        expect('{')
        currentDepth++
        if (currentDepth > maxDepth) {
            throw IllegalArgumentException("JSON nesting depth limit of $maxDepth exceeded (depth: $currentDepth)")
        }
        val map = LinkedHashMap<String, JsonElement>()
        skipWhitespace()
        if (peek() == '}') {
            expect('}')
            currentDepth--
            return JsonObject(map)
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') {
                throw IllegalArgumentException("Expected string object key at index $index")
            }
            val key = parseStringLiteral()
            if (map.containsKey(key)) {
                throw IllegalArgumentException("Duplicate JSON key '$key' detected in object")
            }
            skipWhitespace()
            expect(':')
            val value = parseElement()
            map[key] = value
            skipWhitespace()
            val next = peek()
            if (next == '}') {
                expect('}')
                break
            } else if (next == ',') {
                expect(',')
            } else {
                throw IllegalArgumentException("Expected ',' or '}' in object at index $index, found '$next'")
            }
        }
        currentDepth--
        return JsonObject(map)
    }

    private fun parseArray(): JsonArray {
        expect('[')
        currentDepth++
        if (currentDepth > maxDepth) {
            throw IllegalArgumentException("JSON nesting depth limit of $maxDepth exceeded (depth: $currentDepth)")
        }
        val list = mutableListOf<JsonElement>()
        skipWhitespace()
        if (peek() == ']') {
            expect(']')
            currentDepth--
            return JsonArray(list)
        }
        while (true) {
            list.add(parseElement())
            skipWhitespace()
            val next = peek()
            if (next == ']') {
                expect(']')
                break
            } else if (next == ',') {
                expect(',')
            } else {
                throw IllegalArgumentException("Expected ',' or ']' in array at index $index, found '$next'")
            }
        }
        currentDepth--
        return JsonArray(list)
    }

    private fun parseString(): JsonPrimitive {
        return JsonPrimitive(parseStringLiteral())
    }

    private fun parseStringLiteral(): String {
        expect('"')
        val sb = StringBuilder()
        while (index < text.length) {
            val c = text[index++]
            if (c == '"') {
                return sb.toString()
            } else if (c == '\\') {
                if (index >= text.length) {
                    throw IllegalArgumentException("Unterminated escape sequence in string at index $index")
                }
                when (val esc = text[index++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (index + 4 > text.length) {
                            throw IllegalArgumentException("Invalid unicode escape sequence at index $index")
                        }
                        val hex = text.substring(index, index + 4)
                        index += 4
                        try {
                            val codePoint = hex.toInt(16)
                            sb.append(codePoint.toChar())
                        } catch (e: NumberFormatException) {
                            throw IllegalArgumentException("Invalid unicode escape '\\u$hex' at index $index", e)
                        }
                    }
                    else -> throw IllegalArgumentException("Invalid escape character '\\$esc' at index $index")
                }
            } else if (c.code < 0x20) {
                throw IllegalArgumentException("Unescaped control character 0x${c.code.toString(16)} in string at index $index")
            } else {
                sb.append(c)
            }
        }
        throw IllegalArgumentException("Unterminated string literal at index $index")
    }

    private fun parseBoolean(): JsonPrimitive {
        if (text.startsWith("true", index)) {
            index += 4
            return JsonPrimitive(true)
        }
        if (text.startsWith("false", index)) {
            index += 5
            return JsonPrimitive(false)
        }
        throw IllegalArgumentException("Expected boolean literal at index $index")
    }

    private fun parseNull(): JsonNull {
        if (text.startsWith("null", index)) {
            index += 4
            return JsonNull
        }
        throw IllegalArgumentException("Expected null literal at index $index")
    }

    private fun parseNumber(): JsonPrimitive {
        val start = index
        if (text[index] == '-') {
            index++
        }
        if (index >= text.length) {
            throw IllegalArgumentException("Invalid number at index $index")
        }
        if (text[index] == '0') {
            index++
            if (index < text.length && text[index] in '0'..'9') {
                throw IllegalArgumentException("Leading zeros are not permitted in JSON numbers at index $start")
            }
        } else if (text[index] in '1'..'9') {
            while (index < text.length && text[index] in '0'..'9') {
                index++
            }
        } else {
            throw IllegalArgumentException("Invalid number at index $index")
        }
        // Fraction
        if (index < text.length && text[index] == '.') {
            index++
            val fracStart = index
            while (index < text.length && text[index] in '0'..'9') {
                index++
            }
            if (index == fracStart) {
                throw IllegalArgumentException("Number fraction must contain at least one digit at index $index")
            }
        }
        // Exponent
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            index++
            if (index < text.length && (text[index] == '+' || text[index] == '-')) {
                index++
            }
            val expStart = index
            while (index < text.length && text[index] in '0'..'9') {
                index++
            }
            if (index == expStart) {
                throw IllegalArgumentException("Number exponent must contain at least one digit at index $index")
            }
        }
        val numStr = text.substring(start, index)
        return try {
            if (numStr.contains('.') || numStr.contains('e') || numStr.contains('E')) {
                val d = numStr.toDouble()
                if (d.isNaN() || d.isInfinite()) {
                    throw IllegalArgumentException("Non-finite number at index $start: $numStr")
                }
                JsonPrimitive(d)
            } else {
                JsonPrimitive(numStr.toLong())
            }
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Invalid number format at index $start: $numStr", e)
        }
    }

    private fun skipWhitespace() {
        while (index < text.length) {
            when (text[index]) {
                ' ', '\t', '\r', '\n' -> index++
                else -> return
            }
        }
    }

    private fun peek(): Char {
        if (index >= text.length) {
            throw IllegalArgumentException("Unexpected end of JSON input at index $index")
        }
        return text[index]
    }

    private fun expect(expected: Char) {
        if (index >= text.length) {
            throw IllegalArgumentException("Expected '$expected' but reached end of input at index $index")
        }
        val actual = text[index++]
        if (actual != expected) {
            throw IllegalArgumentException("Expected '$expected' but found '$actual' at index ${index - 1}")
        }
    }
}
