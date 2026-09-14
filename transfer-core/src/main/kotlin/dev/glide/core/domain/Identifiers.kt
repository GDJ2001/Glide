package dev.glide.core.domain

import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable

internal val UUID_V4_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
internal val SHA256_REGEX = Regex("^[0-9a-f]{64}$")

internal fun isValidUuidV4(value: String): Boolean = UUID_V4_REGEX.matches(value)
internal fun isValidSha256(value: String): Boolean = SHA256_REGEX.matches(value)

@Serializable
@JvmInline
value class MessageId(val value: String) {
    init {
        require(UUID_V4_REGEX.matches(value)) {
            "MessageId must be a canonical lowercase UUIDv4, got: '$value'"
        }
    }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class SessionId(val value: String) {
    init {
        require(UUID_V4_REGEX.matches(value)) {
            "SessionId must be a canonical lowercase UUIDv4, got: '$value'"
        }
    }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class BatchId(val value: String) {
    init {
        require(UUID_V4_REGEX.matches(value)) {
            "BatchId must be a canonical lowercase UUIDv4, got: '$value'"
        }
    }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class ItemId(val value: String) {
    init {
        require(UUID_V4_REGEX.matches(value)) {
            "ItemId must be a canonical lowercase UUIDv4, got: '$value'"
        }
    }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class PeerId(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 64) {
            "PeerId must be non-blank and at most 64 characters, got length ${value.length}"
        }
    }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class BulkRef(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 64) {
            "BulkRef must be non-blank and at most 64 characters, got length ${value.length}"
        }
    }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class Sha256Digest(val value: String) {
    init {
        require(SHA256_REGEX.matches(value)) {
            "Sha256Digest must be exactly 64 lowercase hexadecimal characters, got: '$value'"
        }
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): Sha256Digest {
            require(bytes.size == 32) { "SHA-256 raw bytes must be exactly 32 bytes, got ${bytes.size}" }
            val hex = buildString(64) {
                for (b in bytes) {
                    append(String.format(Locale.US, "%02x", b))
                }
            }
            return Sha256Digest(hex)
        }

        fun compute(input: ByteArray): Sha256Digest {
            val md = MessageDigest.getInstance("SHA-256")
            return fromByteArray(md.digest(input))
        }
    }

    override fun toString(): String = value
}

@Serializable
@JvmInline
value class SourceHandle(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 512) {
            "SourceHandle must be non-blank and at most 512 characters"
        }
    }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class StagingHandle(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 512) {
            "StagingHandle must be non-blank and at most 512 characters"
        }
    }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class CommittedFileHandle(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 512) {
            "CommittedFileHandle must be non-blank and at most 512 characters"
        }
    }
    override fun toString(): String = value
}
