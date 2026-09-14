package dev.glide.core.protocol

object DecimalByteCount {
    private val DECIMAL_REGEX = Regex("^(0|[1-9][0-9]*)$")

    fun parse(text: String): Long {
        require(DECIMAL_REGEX.matches(text)) {
            "Byte count must be a non-negative decimal string without leading zeros, got: '$text'"
        }
        try {
            val value = text.toLong()
            require(value >= 0) { "Byte count must be non-negative" }
            return value
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Byte count decimal value overflows 64-bit signed integer: '$text'", e)
        }
    }

    fun serialize(bytes: Long): String {
        require(bytes >= 0) { "Byte count must be non-negative, got: $bytes" }
        return bytes.toString()
    }
}
