package dev.glide.core.transport.lan

import dev.glide.core.domain.ProtocolLimits
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Encodes, decodes, and frames binary records for Glide LAN Transport Profile 1 (LAN-007, Section 6, Section 19).
 *
 * Framing format: 5-byte header:
 * - byte 0: kind (UByte)
 * - bytes 1..4: payloadLength (UInt32 big-endian)
 *
 * Enforces strict bounds checking and signed-overflow rejection before allocating payload buffers.
 */
object LanRecordCodec {
    const val HEADER_SIZE = 5
    const val MAX_RECORD_PAYLOAD_SIZE = 65536 // 64 KiB ceiling across all record kinds

    /**
     * Encodes a [LanRecord] into its on-wire 5-byte header + payload format.
     */
    fun encode(record: LanRecord): ByteArray {
        val payloadBytes = when (record) {
            is LanRecord.Bootstrap -> record.jsonBytes
            is LanRecord.BootstrapResult -> record.jsonBytes
            is LanRecord.Control -> record.payload
            is LanRecord.Data -> {
                val uuidBytes = LanUuidCodec.bulkRefToNetworkBytes(record.bulkRef)
                val out = ByteArray(LanUuidCodec.UUID_BYTE_LENGTH + record.chunk.size)
                System.arraycopy(uuidBytes, 0, out, 0, LanUuidCodec.UUID_BYTE_LENGTH)
                System.arraycopy(record.chunk, 0, out, LanUuidCodec.UUID_BYTE_LENGTH, record.chunk.size)
                out
            }
            is LanRecord.StreamEnd -> {
                val uuidBytes = LanUuidCodec.bulkRefToNetworkBytes(record.bulkRef)
                val out = ByteArray(LanRecordKind.STREAM_END.maxPayloadSize)
                System.arraycopy(uuidBytes, 0, out, 0, LanUuidCodec.UUID_BYTE_LENGTH)
                val count = record.actualBytes
                for (i in 0..7) {
                    out[LanUuidCodec.UUID_BYTE_LENGTH + i] = ((count ushr ((7 - i) * 8)) and 0xFF).toByte()
                }
                out
            }
        }

        val kind = record.kind
        require(payloadBytes.size in kind.minPayloadSize..kind.maxPayloadSize) {
            "Payload size ${payloadBytes.size} out of bounds for ${kind.name} (${kind.minPayloadSize}..${kind.maxPayloadSize})"
        }

        val frame = ByteArray(HEADER_SIZE + payloadBytes.size)
        frame[0] = kind.byteValue
        val length = payloadBytes.size
        frame[1] = ((length ushr 24) and 0xFF).toByte()
        frame[2] = ((length ushr 16) and 0xFF).toByte()
        frame[3] = ((length ushr 8) and 0xFF).toByte()
        frame[4] = (length and 0xFF).toByte()
        System.arraycopy(payloadBytes, 0, frame, HEADER_SIZE, payloadBytes.size)
        return frame
    }

    /**
     * Writes an encoded [LanRecord] directly to an [OutputStream].
     */
    fun writeRecord(record: LanRecord, out: OutputStream) {
        val bytes = encode(record)
        out.write(bytes)
        out.flush()
    }

    /**
     * Decodes a complete on-wire frame from a byte array.
     */
    fun decode(frame: ByteArray, offset: Int = 0, length: Int = frame.size - offset): LanRecord {
        require(length >= HEADER_SIZE) {
            "Frame length $length is less than minimum header size $HEADER_SIZE"
        }

        val (kind, payloadLength) = parseHeader(frame, offset)
        require(length == HEADER_SIZE + payloadLength) {
            "Frame size $length does not match declared size ${HEADER_SIZE + payloadLength}"
        }

        val payload = ByteArray(payloadLength)
        System.arraycopy(frame, offset + HEADER_SIZE, payload, 0, payloadLength)
        return decodePayload(kind, payload)
    }

    /**
     * Synchronously reads a single [LanRecord] from a blocking [InputStream].
     * Throws [EOFException] if stream has ended cleanly before any header bytes.
     */
    fun readRecord(stream: InputStream): LanRecord {
        val header = ByteArray(HEADER_SIZE)
        var totalRead = 0
        while (totalRead < HEADER_SIZE) {
            val count = stream.read(header, totalRead, HEADER_SIZE - totalRead)
            if (count == -1) {
                if (totalRead == 0) {
                    throw EOFException("End of stream reached cleanly before record header")
                } else {
                    throw EOFException("Truncated record header: read $totalRead of $HEADER_SIZE bytes")
                }
            }
            totalRead += count
        }

        val (kind, payloadLength) = parseHeader(header, 0)
        val payload = ByteArray(payloadLength)
        var payloadRead = 0
        while (payloadRead < payloadLength) {
            val count = stream.read(payload, payloadRead, payloadLength - payloadRead)
            if (count == -1) {
                throw EOFException("Truncated record payload: read $payloadRead of $payloadLength bytes for ${kind.name}")
            }
            payloadRead += count
        }

        return decodePayload(kind, payload)
    }

    /**
     * Parses and validates a 5-byte header, returning the record kind and payload length.
     * Enforces signed overflow rejection, bounds checking, and kind validation before allocation.
     */
    fun parseHeader(bytes: ByteArray, offset: Int = 0): Pair<LanRecordKind, Int> {
        require(bytes.size >= offset + HEADER_SIZE) {
            "Insufficient bytes for record header: required $HEADER_SIZE, available ${bytes.size - offset}"
        }

        val kindByte = bytes[offset]
        val kind = LanRecordKind.fromByte(kindByte)
            ?: throw IllegalArgumentException("Unsupported record kind 0x%02X".format(kindByte))

        val b1 = bytes[offset + 1].toLong() and 0xFF
        val b2 = bytes[offset + 2].toLong() and 0xFF
        val b3 = bytes[offset + 3].toLong() and 0xFF
        val b4 = bytes[offset + 4].toLong() and 0xFF

        // Check for signed-length overflow (highest bit set in 32-bit big-endian)
        if ((b1 and 0x80L) != 0L) {
            throw IllegalArgumentException("Signed length overflow in record header")
        }

        val declaredLength = (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4

        if (declaredLength > MAX_RECORD_PAYLOAD_SIZE) {
            throw IllegalArgumentException(
                "Declared payload length $declaredLength exceeds maximum ceiling of $MAX_RECORD_PAYLOAD_SIZE bytes"
            )
        }

        val intLength = declaredLength.toInt()
        require(intLength in kind.minPayloadSize..kind.maxPayloadSize) {
            "Payload length $intLength out of bounds for kind ${kind.name} (${kind.minPayloadSize}..${kind.maxPayloadSize})"
        }

        return kind to intLength
    }

    private fun decodePayload(kind: LanRecordKind, payload: ByteArray): LanRecord {
        return when (kind) {
            LanRecordKind.BOOTSTRAP -> LanRecord.Bootstrap(payload)
            LanRecordKind.BOOTSTRAP_RESULT -> LanRecord.BootstrapResult(payload)
            LanRecordKind.CONTROL -> LanRecord.Control(payload)
            LanRecordKind.DATA -> {
                val bulkRef = LanUuidCodec.networkBytesToBulkRef(payload, 0)
                val chunkLength = payload.size - LanUuidCodec.UUID_BYTE_LENGTH
                val chunk = ByteArray(chunkLength)
                System.arraycopy(payload, LanUuidCodec.UUID_BYTE_LENGTH, chunk, 0, chunkLength)
                LanRecord.Data(bulkRef, chunk)
            }
            LanRecordKind.STREAM_END -> {
                val bulkRef = LanUuidCodec.networkBytesToBulkRef(payload, 0)
                var actualBytes = 0L
                for (i in 0..7) {
                    actualBytes = (actualBytes shl 8) or (payload[LanUuidCodec.UUID_BYTE_LENGTH + i].toLong() and 0xFFL)
                }
                if (actualBytes < 0L) {
                    throw IllegalArgumentException("Signed overflow in actualBytes count: $actualBytes")
                }
                if (actualBytes > ProtocolLimits.MAX_ITEM_BYTES) {
                    throw IllegalArgumentException("actualBytes count $actualBytes exceeds MAX_ITEM_BYTES (${ProtocolLimits.MAX_ITEM_BYTES})")
                }
                LanRecord.StreamEnd(bulkRef, actualBytes)
            }
        }
    }
}

/**
 * State machine accumulator that parses an incoming byte stream into discrete [LanRecord] instances.
 *
 * Gracefully handles:
 * - 1-byte incremental feeds
 * - Arbitrarily split chunks across header or payload boundaries
 * - Coalesced multiple records in a single chunk
 * - Immediate rejection of invalid kinds or oversized lengths before payload buffering
 */
class LanRecordAccumulator {
    private enum class State {
        AWAITING_HEADER,
        AWAITING_PAYLOAD
    }

    private var state = State.AWAITING_HEADER
    private val buffer = ByteArrayOutputStream()
    private var pendingKind: LanRecordKind? = null
    private var pendingPayloadLength: Int = 0

    val bufferedBytesCount: Int get() = buffer.size()

    fun reset() {
        state = State.AWAITING_HEADER
        buffer.reset()
        pendingKind = null
        pendingPayloadLength = 0
    }

    /**
     * Feeds incoming bytes into the accumulator and returns all newly completed records.
     */
    fun feed(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size - offset): List<LanRecord> {
        require(offset >= 0 && length >= 0 && offset + length <= chunk.size) {
            "Invalid offset $offset or length $length for chunk size ${chunk.size}"
        }

        val completedRecords = mutableListOf<LanRecord>()
        var cursor = offset
        val end = offset + length

        while (cursor < end) {
            when (state) {
                State.AWAITING_HEADER -> {
                    val needed = LanRecordCodec.HEADER_SIZE - buffer.size()
                    val available = end - cursor
                    val toTake = minOf(needed, available)
                    buffer.write(chunk, cursor, toTake)
                    cursor += toTake

                    if (buffer.size() == LanRecordCodec.HEADER_SIZE) {
                        val headerBytes = buffer.toByteArray()
                        val (kind, payloadLength) = LanRecordCodec.parseHeader(headerBytes, 0)
                        pendingKind = kind
                        pendingPayloadLength = payloadLength
                        buffer.reset()
                        state = State.AWAITING_PAYLOAD
                    }
                }
                State.AWAITING_PAYLOAD -> {
                    val needed = pendingPayloadLength - buffer.size()
                    val available = end - cursor
                    val toTake = minOf(needed, available)
                    buffer.write(chunk, cursor, toTake)
                    cursor += toTake

                    if (buffer.size() == pendingPayloadLength) {
                        val payloadBytes = buffer.toByteArray()
                        val kind = pendingKind ?: error("Missing pending record kind")
                        val record = LanRecordCodec.decode(
                            buildCompleteFrame(kind, payloadBytes)
                        )
                        completedRecords.add(record)
                        reset()
                    }
                }
            }
        }

        return completedRecords
    }

    private fun buildCompleteFrame(kind: LanRecordKind, payload: ByteArray): ByteArray {
        val frame = ByteArray(LanRecordCodec.HEADER_SIZE + payload.size)
        frame[0] = kind.byteValue
        val length = payload.size
        frame[1] = ((length ushr 24) and 0xFF).toByte()
        frame[2] = ((length ushr 16) and 0xFF).toByte()
        frame[3] = ((length ushr 8) and 0xFF).toByte()
        frame[4] = (length and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, LanRecordCodec.HEADER_SIZE, payload.size)
        return frame
    }
}
