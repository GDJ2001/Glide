package dev.glide.core.resume

import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.SourceHandle
import dev.glide.core.ports.SourceAccess
import dev.glide.core.stream.HashingInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.EOFException
import java.io.InputStream

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
        skipBufferSize: Int =
            64 * 1024
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
            return RemainingResumeSource(
                stream =
                    HashingInputStream(
                        delegate = raw,
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
}

data class RemainingResumeSource(
    val stream: HashingInputStream,
    val remainingBytes: Long
)
