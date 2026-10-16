package dev.glide.transport.lan.framing

import dev.glide.core.domain.BulkRef
import dev.glide.core.transport.lan.LanRecord
import dev.glide.core.transport.lan.LanRecordKind
import dev.glide.core.transport.lan.LanUuidCodec
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Single serialized writer for outgoing LAN Profile 1 records (LAN-012, Section 6).
 *
 * Framing: kind:u8 | payloadLength:u32 big-endian | payload
 * Thread-safe: synchronizes write operations to prevent interleaving of frame headers and bodies.
 */
class LanRecordWriter(
    outputStream: OutputStream,
    private val coalesceDataWrites: Boolean = false
) {
    private val outputStream: OutputStream = if (coalesceDataWrites) {
        BufferedOutputStream(outputStream, WRITE_BUFFER_BYTES)
    } else {
        outputStream
    }
    private val header = ByteArray(5)
    private var pendingDataBytes = 0
    private var lastFlushNanos = System.nanoTime()
    private var cachedBulkRef: BulkRef? = null
    private var cachedBulkRefBytes: ByteArray? = null

    private fun bulkRefBytes(bulkRef: BulkRef): ByteArray {
        if (cachedBulkRef != bulkRef) {
            cachedBulkRef = bulkRef
            cachedBulkRefBytes = LanUuidCodec.bulkRefToNetworkBytes(bulkRef)
        }
        return checkNotNull(cachedBulkRefBytes)
    }

    private fun flushPending() {
        outputStream.flush()
        pendingDataBytes = 0
        lastFlushNanos = System.nanoTime()
    }

    @Throws(IOException::class)
    @Synchronized
    fun writeBootstrap(payload: ByteArray) {
        require(payload.size in LanRecordKind.BOOTSTRAP.minPayloadSize..LanRecordKind.BOOTSTRAP.maxPayloadSize) {
            "BOOTSTRAP payload size ${payload.size} out of bounds"
        }
        writeHeaderAndPayload(LanRecordKind.BOOTSTRAP.byteValue, payload)
    }

    @Throws(IOException::class)
    @Synchronized
    fun writeBootstrapResult(payload: ByteArray) {
        require(payload.size in LanRecordKind.BOOTSTRAP_RESULT.minPayloadSize..LanRecordKind.BOOTSTRAP_RESULT.maxPayloadSize) {
            "BOOTSTRAP_RESULT payload size ${payload.size} out of bounds"
        }
        writeHeaderAndPayload(LanRecordKind.BOOTSTRAP_RESULT.byteValue, payload)
    }

    @Throws(IOException::class)
    @Synchronized
    fun writeControl(payload: ByteArray) {
        require(payload.size in LanRecordKind.CONTROL.minPayloadSize..LanRecordKind.CONTROL.maxPayloadSize) {
            "CONTROL payload size ${payload.size} out of bounds"
        }
        writeHeaderAndPayload(LanRecordKind.CONTROL.byteValue, payload)
    }

    @Throws(IOException::class)
    @Synchronized
    fun writeData(bulkRef: BulkRef, chunk: ByteArray, offset: Int = 0, length: Int = chunk.size - offset) {
        require(length in 1..65520) { "DATA chunk size $length out of bounds [1..65520]" }
        require(offset >= 0 && offset <= chunk.size - length) { "DATA chunk range is invalid" }
        val totalLength = LanUuidCodec.UUID_BYTE_LENGTH + length
        header[0] = LanRecordKind.DATA.byteValue
        header[1] = ((totalLength shr 24) and 0xFF).toByte()
        header[2] = ((totalLength shr 16) and 0xFF).toByte()
        header[3] = ((totalLength shr 8) and 0xFF).toByte()
        header[4] = (totalLength and 0xFF).toByte()

        outputStream.write(header)
        outputStream.write(bulkRefBytes(bulkRef))
        outputStream.write(chunk, offset, length)
        if (coalesceDataWrites) {
            pendingDataBytes += 5 + totalLength
            if (pendingDataBytes >= DATA_FLUSH_THRESHOLD_BYTES ||
                System.nanoTime() - lastFlushNanos >= MAX_BATCH_DELAY_NANOS
            ) {
                flushPending()
            }
        } else {
            flushPending()
        }
    }

    @Throws(IOException::class)
    @Synchronized
    fun writeStreamEnd(bulkRef: BulkRef, actualBytes: Long) {
        require(actualBytes >= 0L) { "actualBytes must be non-negative, got: $actualBytes" }
        val totalLength = LanRecordKind.STREAM_END.maxPayloadSize
        header[0] = LanRecordKind.STREAM_END.byteValue
        header[1] = 0
        header[2] = 0
        header[3] = 0
        header[4] = totalLength.toByte()

        val payload = ByteArray(totalLength)
        val uuidBytes = bulkRefBytes(bulkRef)
        System.arraycopy(uuidBytes, 0, payload, 0, LanUuidCodec.UUID_BYTE_LENGTH)

        var v = actualBytes
        for (i in 7 downTo 0) {
            payload[LanUuidCodec.UUID_BYTE_LENGTH + i] = (v and 0xFF).toByte()
            v = v ushr 8
        }

        outputStream.write(header)
        outputStream.write(payload)
        flushPending()
    }

    @Throws(IOException::class)
    @Synchronized
    fun writeRecord(record: LanRecord) {
        when (record) {
            is LanRecord.Bootstrap -> writeBootstrap(record.jsonBytes)
            is LanRecord.BootstrapResult -> writeBootstrapResult(record.jsonBytes)
            is LanRecord.Control -> writeControl(record.payload)
            is LanRecord.Data -> writeData(record.bulkRef, record.chunk)
            is LanRecord.StreamEnd -> writeStreamEnd(record.bulkRef, record.actualBytes)
        }
    }

    @Throws(IOException::class)
    private fun writeHeaderAndPayload(kindByte: Byte, payload: ByteArray) {
        val length = payload.size
        header[0] = kindByte
        header[1] = ((length shr 24) and 0xFF).toByte()
        header[2] = ((length shr 16) and 0xFF).toByte()
        header[3] = ((length shr 8) and 0xFF).toByte()
        header[4] = (length and 0xFF).toByte()

        outputStream.write(header)
        outputStream.write(payload)
        flushPending()
    }

    private companion object {
        const val WRITE_BUFFER_BYTES = 256 * 1024
        const val DATA_FLUSH_THRESHOLD_BYTES = 3 * (5 + 16 + 65520)
        const val MAX_BATCH_DELAY_NANOS = 5_000_000L
    }
}
