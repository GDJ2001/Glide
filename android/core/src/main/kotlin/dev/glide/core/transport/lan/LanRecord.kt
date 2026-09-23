package dev.glide.core.transport.lan

import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.isValidUuidV4
import java.util.UUID

/**
 * Record types defined for Glide LAN Transport Profile 1 (LAN-007, Section 6, ADR-0018).
 *
 * Framing format: 5-byte header:
 * - 1 byte kind (UByte)
 * - 4 bytes payloadLength (UInt32 big-endian)
 *
 * All bounds are strictly enforced before allocating payload buffers.
 */
enum class LanRecordKind(
    val byteValue: Byte,
    val minPayloadSize: Int,
    val maxPayloadSize: Int,
    val description: String
) {
    BOOTSTRAP(0x01.toByte(), 1, 2048, "Strict JSON bootstrap handshake"),
    BOOTSTRAP_RESULT(0x02.toByte(), 1, 512, "Strict JSON bootstrap result"),
    CONTROL(0x10.toByte(), 1, 16384, "V1 JSON control envelope"),
    DATA(0x20.toByte(), 17, 65536, "Binary file chunk (16-byte UUID bulkRef + 1..65520 bytes file data)"),
    STREAM_END(0x21.toByte(), 24, 24, "Terminal stream signal (16-byte UUID bulkRef + 8-byte actualBytes)");

    companion object {
        private val BYTES_TO_KIND = entries.associateBy { it.byteValue }

        fun fromByte(byte: Byte): LanRecordKind? = BYTES_TO_KIND[byte]
    }
}

/**
 * Sealed hierarchy of typed records transmitted over the LAN TLS connection.
 */
sealed interface LanRecord {
    val kind: LanRecordKind

    /**
     * Client's first record after TLS handshake validation.
     * Contains UTF-8 strict JSON of [LanBootstrapMessage].
     */
    data class Bootstrap(val jsonBytes: ByteArray) : LanRecord {
        override val kind: LanRecordKind get() = LanRecordKind.BOOTSTRAP

        init {
            require(jsonBytes.isNotEmpty() && jsonBytes.size <= LanRecordKind.BOOTSTRAP.maxPayloadSize) {
                "BOOTSTRAP payload size ${jsonBytes.size} out of bounds (1..${LanRecordKind.BOOTSTRAP.maxPayloadSize})"
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bootstrap) return false
            return jsonBytes.contentEquals(other.jsonBytes)
        }

        override fun hashCode(): Int = jsonBytes.contentHashCode()
    }

    /**
     * Server's one bootstrap response.
     * Contains UTF-8 strict JSON of [LanBootstrapResult].
     */
    data class BootstrapResult(val jsonBytes: ByteArray) : LanRecord {
        override val kind: LanRecordKind get() = LanRecordKind.BOOTSTRAP_RESULT

        init {
            require(jsonBytes.isNotEmpty() && jsonBytes.size <= LanRecordKind.BOOTSTRAP_RESULT.maxPayloadSize) {
                "BOOTSTRAP_RESULT payload size ${jsonBytes.size} out of bounds (1..${LanRecordKind.BOOTSTRAP_RESULT.maxPayloadSize})"
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BootstrapResult) return false
            return jsonBytes.contentEquals(other.jsonBytes)
        }

        override fun hashCode(): Int = jsonBytes.contentHashCode()
    }

    /**
     * Existing ProtocolCodec UTF-8 envelope for session control messages.
     */
    data class Control(val payload: ByteArray) : LanRecord {
        override val kind: LanRecordKind get() = LanRecordKind.CONTROL

        init {
            require(payload.isNotEmpty() && payload.size <= LanRecordKind.CONTROL.maxPayloadSize) {
                "CONTROL payload size ${payload.size} out of bounds (1..${LanRecordKind.CONTROL.maxPayloadSize})"
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Control) return false
            return payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = payload.contentHashCode()
    }

    /**
     * Binary data chunk for an expected item.
     * Raw payload on wire: 16 bytes network-order UUID bulkRef + 1..65,520 bytes file data.
     */
    data class Data(
        val bulkRef: BulkRef,
        val chunk: ByteArray
    ) : LanRecord {
        override val kind: LanRecordKind get() = LanRecordKind.DATA

        init {
            require(isValidUuidV4(bulkRef.value)) {
                "DATA bulkRef must be a canonical UUIDv4, got: '${bulkRef.value}'"
            }
            require(chunk.isNotEmpty() && chunk.size <= (LanRecordKind.DATA.maxPayloadSize - LanUuidCodec.UUID_BYTE_LENGTH)) {
                "DATA chunk size ${chunk.size} out of bounds (1..${LanRecordKind.DATA.maxPayloadSize - LanUuidCodec.UUID_BYTE_LENGTH})"
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Data) return false
            if (bulkRef != other.bulkRef) return false
            return chunk.contentEquals(other.chunk)
        }

        override fun hashCode(): Int {
            var result = bulkRef.hashCode()
            result = 31 * result + chunk.contentHashCode()
            return result
        }
    }

    /**
     * Sent once after all DATA records for an item, including zero-byte files.
     * Raw payload on wire: exactly 24 bytes (16-byte UUID bulkRef + 8-byte actualBytes unsigned big-endian).
     */
    data class StreamEnd(
        val bulkRef: BulkRef,
        val actualBytes: Long
    ) : LanRecord {
        override val kind: LanRecordKind get() = LanRecordKind.STREAM_END

        init {
            require(isValidUuidV4(bulkRef.value)) {
                "STREAM_END bulkRef must be a canonical UUIDv4, got: '${bulkRef.value}'"
            }
            require(actualBytes >= 0L) {
                "STREAM_END actualBytes must be non-negative, got: $actualBytes"
            }
            require(actualBytes <= ProtocolLimits.MAX_ITEM_BYTES) {
                "STREAM_END actualBytes $actualBytes exceeds MAX_ITEM_BYTES (${ProtocolLimits.MAX_ITEM_BYTES})"
            }
        }
    }
}

/**
 * RFC 4122 standard network-byte-order UUID codec for LAN binary records.
 *
 * Encodes MSB (8 bytes big-endian) then LSB (8 bytes big-endian).
 * Does not use Windows GUID mixed-endian layout.
 */
object LanUuidCodec {
    const val UUID_BYTE_LENGTH = 16

    fun toNetworkBytes(uuid: UUID): ByteArray {
        val bytes = ByteArray(UUID_BYTE_LENGTH)
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        for (i in 0..7) {
            bytes[i] = ((msb ushr ((7 - i) * 8)) and 0xFF).toByte()
        }
        for (i in 0..7) {
            bytes[8 + i] = ((lsb ushr ((7 - i) * 8)) and 0xFF).toByte()
        }
        return bytes
    }

    fun fromNetworkBytes(bytes: ByteArray, offset: Int = 0): UUID {
        require(bytes.size >= offset + UUID_BYTE_LENGTH) {
            "Insufficient bytes for UUID: required $UUID_BYTE_LENGTH, available ${bytes.size - offset}"
        }
        var msb = 0L
        var lsb = 0L
        for (i in 0..7) {
            msb = (msb shl 8) or (bytes[offset + i].toLong() and 0xFFL)
        }
        for (i in 0..7) {
            lsb = (lsb shl 8) or (bytes[offset + 8 + i].toLong() and 0xFFL)
        }
        return UUID(msb, lsb)
    }

    fun bulkRefToNetworkBytes(bulkRef: BulkRef): ByteArray {
        require(isValidUuidV4(bulkRef.value)) {
            "LAN bulkRef must be a canonical UUIDv4, got: '${bulkRef.value}'"
        }
        val uuid = UUID.fromString(bulkRef.value)
        return toNetworkBytes(uuid)
    }

    fun networkBytesToBulkRef(bytes: ByteArray, offset: Int = 0): BulkRef {
        val uuid = fromNetworkBytes(bytes, offset)
        val canonical = uuid.toString().lowercase()
        require(isValidUuidV4(canonical)) {
            "Decoded UUID from network bytes is not a canonical UUIDv4: '$canonical'"
        }
        return BulkRef(canonical)
    }
}
