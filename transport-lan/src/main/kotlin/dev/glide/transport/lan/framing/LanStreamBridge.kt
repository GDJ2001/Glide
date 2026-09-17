package dev.glide.transport.lan.framing

import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.PeerId
import dev.glide.core.ports.BulkProgress
import dev.glide.core.ports.IncomingStream
import dev.glide.core.ports.InputStreamProvider
import dev.glide.core.ports.StreamExpectation
import dev.glide.core.transport.lan.LanRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections
import java.util.UUID

/**
 * Bridges framed LAN Profile 1 DATA and STREAM_END records with [TransferTransport] stream contracts (LAN-012, Section 6).
 *
 * Enforces:
 * - Registered typed [StreamExpectation] before any incoming DATA or STREAM_END is accepted.
 * - Single counted/hashed source preparation and transmission.
 * - Actual EOF verification after sending offered byte count.
 * - Strict length matching between actual transmitted bytes and STREAM_END record.
 * - Bounded pipes and clean cancellation.
 */
class LanStreamBridge : Closeable {

    private val preparedSources = Collections.synchronizedMap(mutableMapOf<BulkRef, InputStreamProvider>())
    private val expectedStreams = Collections.synchronizedMap(mutableMapOf<BulkRef, StreamExpectation>())
    private val activePipes = Collections.synchronizedMap(mutableMapOf<BulkRef, InboundPipeState>())

    private data class InboundPipeState(
        val outputStream: PipedOutputStream,
        val inputStream: PipedInputStream,
        val expectation: StreamExpectation,
        var receivedBytes: Long = 0L
    )

    fun prepareStream(streamSource: InputStreamProvider): BulkRef {
        val bulkRef = BulkRef(UUID.randomUUID().toString())
        preparedSources[bulkRef] = streamSource
        return bulkRef
    }

    fun registerExpectedStream(expectation: StreamExpectation) {
        expectedStreams[expectation.bulkRef] = expectation
    }

    fun sendStream(
        peerId: PeerId,
        bulkRef: BulkRef,
        writer: LanRecordWriter
    ): Flow<BulkProgress> = flow {
        val provider = preparedSources[bulkRef]
            ?: throw IllegalStateException("Stream not prepared for bulkRef: ${bulkRef.value}")

        val inputStream = provider.openStream()
        var transferredBytes = 0L

        try {
            val buffer = ByteArray(MAX_CHUNK_PAYLOAD_SIZE)
            emit(BulkProgress(bulkRef = bulkRef, bytesTransferred = 0L, isComplete = false))

            while (true) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead < 0) break

                if (bytesRead > 0) {
                    writer.writeData(bulkRef = bulkRef, chunk = buffer, offset = 0, length = bytesRead)
                    transferredBytes += bytesRead
                    emit(BulkProgress(bulkRef = bulkRef, bytesTransferred = transferredBytes, isComplete = false))
                }
            }

            // Prove actual source EOF (Section 6, Section 18)
            val extraCheck = inputStream.read()
            if (extraCheck != -1) {
                throw IllegalStateException("Source yielded extra byte after expected EOF for $bulkRef")
            }

            // Write STREAM_END with actual byte count
            writer.writeStreamEnd(bulkRef = bulkRef, actualBytes = transferredBytes)
            emit(BulkProgress(bulkRef = bulkRef, bytesTransferred = transferredBytes, isComplete = true))
        } finally {
            try { inputStream.close() } catch (_: Exception) {}
            preparedSources.remove(bulkRef)
        }
    }

    @Throws(IOException::class)
    fun handleIncomingData(
        peerId: PeerId,
        record: LanRecord.Data,
        onIncomingStream: (IncomingStream) -> Unit,
        onBulkProgress: (BulkProgress) -> Unit
    ) {
        val expectation = expectedStreams[record.bulkRef]
            ?: throw LanFramingException("Received DATA for unregistered bulkRef: ${record.bulkRef.value}")

        var pipeState = activePipes[record.bulkRef]
        if (pipeState == null) {
            // First chunk: open bounded piped stream (64 KiB buffer)
            val output = PipedOutputStream()
            val input = PipedInputStream(output, PIPE_BUFFER_SIZE)
            pipeState = InboundPipeState(output, input, expectation)
            activePipes[record.bulkRef] = pipeState
            onIncomingStream(IncomingStream(peerId = peerId, bulkRef = record.bulkRef, stream = input))
        }

        pipeState.outputStream.write(record.chunk)
        pipeState.outputStream.flush()
        pipeState.receivedBytes += record.chunk.size

        if (pipeState.receivedBytes > expectation.sizeBytes) {
            try { pipeState.outputStream.close() } catch (_: Exception) {}
            throw LanFramingException("Received bytes ${pipeState.receivedBytes} exceeds expected ${expectation.sizeBytes}")
        }

        onBulkProgress(
            BulkProgress(
                bulkRef = record.bulkRef,
                bytesTransferred = pipeState.receivedBytes,
                isComplete = false
            )
        )
    }

    @Throws(IOException::class)
    fun handleIncomingStreamEnd(
        peerId: PeerId,
        record: LanRecord.StreamEnd,
        onIncomingStream: (IncomingStream) -> Unit,
        onBulkProgress: (BulkProgress) -> Unit
    ) {
        val expectation = expectedStreams.remove(record.bulkRef)
            ?: throw LanFramingException("Received STREAM_END for unregistered bulkRef: ${record.bulkRef.value}")

        if (record.actualBytes != expectation.sizeBytes) {
            throw LanFramingException("STREAM_END byte count mismatch: expected ${expectation.sizeBytes}, got ${record.actualBytes}")
        }

        val pipeState = activePipes.remove(record.bulkRef)
        if (pipeState == null) {
            // Zero-byte file: emit empty InputStream
            onIncomingStream(IncomingStream(peerId = peerId, bulkRef = record.bulkRef, stream = ByteArrayInputStream(ByteArray(0))))
        } else {
            if (pipeState.receivedBytes != record.actualBytes) {
                try { pipeState.outputStream.close() } catch (_: Exception) {}
                throw LanFramingException("STREAM_END bytes mismatch: received ${pipeState.receivedBytes}, declared ${record.actualBytes}")
            }
            // Signal clean EOF to reader
            pipeState.outputStream.close()
        }

        onBulkProgress(
            BulkProgress(
                bulkRef = record.bulkRef,
                bytesTransferred = record.actualBytes,
                isComplete = true
            )
        )
    }

    fun cancelPayload(bulkRef: BulkRef) {
        preparedSources.remove(bulkRef)
        expectedStreams.remove(bulkRef)
        val pipe = activePipes.remove(bulkRef)
        try { pipe?.outputStream?.close() } catch (_: Exception) {}
        try { pipe?.inputStream?.close() } catch (_: Exception) {}
    }

    override fun close() {
        preparedSources.clear()
        expectedStreams.clear()
        val pipes = activePipes.values.toList()
        activePipes.clear()
        for (pipe in pipes) {
            try { pipe.outputStream.close() } catch (_: Exception) {}
            try { pipe.inputStream.close() } catch (_: Exception) {}
        }
    }

    companion object {
        const val MAX_CHUNK_PAYLOAD_SIZE = 65520 // 64 KiB minus 16-byte UUID
        const val PIPE_BUFFER_SIZE = 64 * 1024 // 64 KiB bounded pipe buffer
    }
}
