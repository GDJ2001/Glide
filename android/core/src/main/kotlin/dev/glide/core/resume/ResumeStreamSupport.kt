package dev.glide.core.resume

import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.ports.SourceAccess
import dev.glide.core.stream.HashingInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.EOFException
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Bounded source positioning for a previously accepted resume prefix.
 *
 * Source continuity is still established by platform preflight/revalidation. This helper performs
 * a second size check, opens the current source once, consumes exactly [startByteOffset] bytes with
 * a bounded buffer, and exposes only the remaining byte range through [HashingInputStream].
 */
object ResumeStreamSupport {

    suspend fun openRemainingSource(
        sourceAccess: SourceAccess,
        handle: SourceHandle,
        expectedTotalBytes: Long,
        startByteOffset: Long,
        skipBufferSize: Int = 64 * 1024,
        verifiedChunkSize: Int = 0,
        verifiedChunkDigests: List<String> = emptyList()
    ): RemainingResumeSource {
        require(
            expectedTotalBytes in
                1..ProtocolLimits
                    .MAX_ITEM_BYTES
        ) {
            "expectedTotalBytes is outside protocol limits"
        }
        require(
            startByteOffset in
                0 until expectedTotalBytes
        ) {
            "Resume stream requires an offset before EOF"
        }
        require(
            skipBufferSize in
                1..1024 * 1024
        ) {
            "skipBufferSize must be in 1..1 MiB"
        }

        val metadata =
            sourceAccess
                .validateSource(handle)
        require(
            metadata.sizeBytes ==
                expectedTotalBytes
        ) {
            "Source size changed before resumed stream opened"
        }

        val raw =
            sourceAccess
                .openSource(handle)
        try {
            skipExactly(
                raw,
                startByteOffset,
                skipBufferSize
            )
            val remaining =
                expectedTotalBytes -
                    startByteOffset
            val verifiedInput = if (verifiedChunkDigests.isEmpty()) raw else {
                require(verifiedChunkSize in 1..16 * 1024 * 1024 &&
                    startByteOffset % verifiedChunkSize == 0L &&
                    verifiedChunkDigests.size.toLong() == (expectedTotalBytes + verifiedChunkSize - 1) / verifiedChunkSize)
                VerifiedChunkInput(raw, remaining, verifiedChunkSize,
                    (startByteOffset / verifiedChunkSize).toInt(), verifiedChunkDigests)
            }
            return RemainingResumeSource(
                stream =
                    HashingInputStream(
                        delegate = verifiedInput,
                        expectedBytes =
                            remaining
                    ),
                remainingBytes =
                    remaining
            )
        } catch (error: Throwable) {
            try {
                raw.close()
            } catch (_: Throwable) {
                // Preserve primary failure.
            }
            throw error
        }
    }

    suspend fun skipExactly(
        input: InputStream,
        bytes: Long,
        bufferSize: Int =
            64 * 1024
    ) {
        require(bytes >= 0L) {
            "bytes cannot be negative"
        }
        require(
            bufferSize in
                1..1024 * 1024
        ) {
            "bufferSize must be in 1..1 MiB"
        }

        if (bytes == 0L) {
            return
        }

        val buffer =
            ByteArray(bufferSize)
        var consumed = 0L
        while (consumed < bytes) {
            currentCoroutineContext()
                .ensureActive()
            val skipped = input.skip(bytes - consumed)
            if (skipped > 0) {
                consumed += skipped
                continue
            }
            val wanted =
                minOf(
                    buffer.size.toLong(),
                    bytes - consumed
                ).toInt()
            val read =
                input.read(
                    buffer,
                    0,
                    wanted
                )
            if (read < 0) {
                throw EOFException(
                    "Source ended while positioning resume stream: expected to skip $bytes bytes, reached EOF at $consumed"
                )
            }
            if (read == 0) {
                continue
            }
            consumed +=
                read.toLong()
        }
    }

    private class VerifiedChunkInput(
        private val source: InputStream,
        private var remaining: Long,
        chunkSize: Int,
        private var chunkIndex: Int,
        private val digests: List<String>
    ) : InputStream() {
        private val chunk = ByteArray(chunkSize)
        private val hasher = MessageDigest.getInstance("SHA-256")
        private var position = 0
        private var end = 0

        override fun read(destination: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position == end && !loadChunk()) return -1
            val count = minOf(length, end - position)
            chunk.copyInto(destination, offset, position, position + count)
            position += count
            return count
        }

        override fun read(): Int {
            if (position == end && !loadChunk()) return -1
            return chunk[position++].toInt() and 0xff
        }

        private fun loadChunk(): Boolean {
            if (remaining == 0L) return false
            end = minOf(remaining, chunk.size.toLong()).toInt()
            position = 0
            var filled = 0
            while (filled < end) {
                val read = source.read(chunk, filled, end - filled)
                if (read <= 0) throw EOFException("The approved source ended during resume")
                filled += read
            }
            hasher.update(chunk, 0, end)
            if (Sha256Digest.fromByteArray(hasher.digest()).value != digests[chunkIndex++])
                throw IOException("The approved source changed during resume")
            remaining -= end
            return true
        }

        override fun close() {
            chunk.fill(0)
            source.close()
        }
    }
}

data class RemainingResumeSource(
    val stream: HashingInputStream,
    val remainingBytes: Long
)
