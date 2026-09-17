package dev.glide.transport.lan.framing

import dev.glide.core.transport.lan.LanRecord
import dev.glide.core.transport.lan.LanRecordKind
import dev.glide.core.transport.lan.LanUuidCodec
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Thrown when incoming record framing or payload violates LAN Profile 1 constraints (LAN-012, Section 6).
 */
class LanFramingException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Reads and validates framed records from an [InputStream] according to LAN Profile 1 (LAN-012, Section 6).
 *
 * Framing: kind:u8 | payloadLength:u32 big-endian | payload
 * Validates kind and length bounds before allocating/reading body.
 */
object LanRecordReader {

    const val HEADER_SIZE = 5

    @Throws(IOException::class)
    fun readRecord(inputStream: InputStream): LanRecord {
        val header = ByteArray(HEADER_SIZE)
        readFully(inputStream, header)

        val kindByte = header[0]
        val kind = LanRecordKind.fromByte(kindByte)
            ?: throw LanFramingException("Unknown record kind: 0x%02x".format(kindByte.toInt() and 0xFF))

        val payloadLength = ((header[1].toInt() and 0xFF) shl 24) or
            ((header[2].toInt() and 0xFF) shl 16) or
            ((header[3].toInt() and 0xFF) shl 8) or
            (header[4].toInt() and 0xFF)

        if (payloadLength < 0) {
            throw LanFramingException("Invalid signed payload length: $payloadLength")
        }

        validateLength(kind, payloadLength)

        val payload = ByteArray(payloadLength)
        readFully(inputStream, payload)

        return when (kind) {
            LanRecordKind.BOOTSTRAP -> LanRecord.Bootstrap(payload)
            LanRecordKind.BOOTSTRAP_RESULT -> LanRecord.BootstrapResult(payload)
            LanRecordKind.CONTROL -> LanRecord.Control(payload)
            LanRecordKind.DATA -> {
                val bulkRef = LanUuidCodec.networkBytesToBulkRef(payload, 0)
                val chunk = payload.copyOfRange(LanUuidCodec.UUID_BYTE_LENGTH, payload.size)
                LanRecord.Data(bulkRef, chunk)
            }
            LanRecordKind.STREAM_END -> {
                val bulkRef = LanUuidCodec.networkBytesToBulkRef(payload, 0)
                var actualBytes: Long = 0L
                for (i in 0 until 8) {
                    actualBytes = (actualBytes shl 8) or (payload[LanUuidCodec.UUID_BYTE_LENGTH + i].toLong() and 0xFFL)
                }
                LanRecord.StreamEnd(bulkRef, actualBytes)
            }
        }
    }

    private fun validateLength(kind: LanRecordKind, length: Int) {
        if (length < kind.minPayloadSize || length > kind.maxPayloadSize) {
            throw LanFramingException("$kind length $length out of bounds [${kind.minPayloadSize}..${kind.maxPayloadSize}]")
        }
    }

    @Throws(IOException::class)
    fun readFully(inputStream: InputStream, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) {
        var totalRead = 0
        while (totalRead < length) {
            val count = inputStream.read(buffer, offset + totalRead, length - totalRead)
            if (count < 0) {
                throw EOFException("Unexpected EOF after $totalRead bytes, expected $length")
            }
            totalRead += count
        }
    }
}
