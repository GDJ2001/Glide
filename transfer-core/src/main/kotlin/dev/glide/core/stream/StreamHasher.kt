package dev.glide.core.stream

import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.Sha256Digest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Result of computing a streaming SHA-256 digest over an input stream.
 */
data class HashingResult(
    val sizeBytes: Long,
    val digest: Sha256Digest
)

/**
 * Streaming utility for reading, counting, and hashing binary source streams with bounded buffers.
 * Enforces strict byte counts, early EOF detection, extra byte detection, and descriptor closure on cancellation.
 */
object StreamHasher {

    const val BUFFER_SIZE_32_KIB: Int = 32 * 1024 // 32 KiB bounded buffer
    const val DEFAULT_BUFFER_SIZE: Int = 65_536 // 64 KiB bounded buffer (default)
    const val BUFFER_SIZE_256_KIB: Int = 256 * 1024 // 256 KiB bounded buffer (upper budget bound)

    /**
     * Reads the entire [inputStream] in chunks of [bufferSize] bytes, computing its SHA-256 digest.
     * Always closes [inputStream] upon completion, cancellation, or error.
     */
    suspend fun hashStream(
        inputStream: InputStream,
        expectedBytes: Long? = null,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
        onProgress: ((bytesRead: Long) -> Unit)? = null
    ): HashingResult {
        return copyAndHash(
            inputStream = inputStream,
            outputStream = null,
            expectedBytes = expectedBytes,
            bufferSize = bufferSize,
            onProgress = onProgress
        )
    }

    /**
     * Streams one item with the ordinary whole-file SHA-256 while also yielding exact fixed-size
     * chunks to a suspend callback. The callback must durably verify/store a chunk before returning.
     *
     * Memory is bounded to [bufferSize] plus one [chunkSize] accumulator (maximum 16 MiB).
     * The final chunk may be shorter only when it ends exactly at [expectedBytes].
     */
    suspend fun copyAndHashWithChunks(
        inputStream: InputStream,
        outputStream: OutputStream?,
        expectedBytes: Long,
        chunkSize: Int,
        bufferSize: Int =
            DEFAULT_BUFFER_SIZE,
        onChunk:
            suspend (
                chunkIndex: Long,
                chunkData: ByteArray
            ) -> Unit,
        onProgress:
            ((bytesRead: Long) -> Unit)? =
            null
    ): HashingResult {
        require(
            bufferSize > 0 &&
                bufferSize <=
                    1_048_576
        ) {
            "Buffer size must be between 1 byte and 1 MiB, got: $bufferSize"
        }
        require(
            expectedBytes in
                1..ProtocolLimits
                    .MAX_ITEM_BYTES
        ) {
            "Resumable expectedBytes must be in 1..${ProtocolLimits.MAX_ITEM_BYTES}, got: $expectedBytes"
        }
        require(
            chunkSize in
                1..16 * 1024 * 1024
        ) {
            "Resume chunk size must be in 1..16 MiB, got: $chunkSize"
        }

        val digest =
            MessageDigest.getInstance(
                "SHA-256"
            )
        val readBuffer =
            ByteArray(bufferSize)
        var chunkBuffer =
            ByteArray(chunkSize)
        var chunkUsed = 0
        var chunkIndex = 0L
        var totalBytes = 0L

        suspend fun commitChunk() {
            if (chunkUsed == 0) {
                return
            }
            val exactChunk =
                if (chunkUsed ==
                    chunkBuffer.size
                ) {
                    chunkBuffer
                } else {
                    chunkBuffer.copyOf(
                        chunkUsed
                    )
                }

            onChunk(
                chunkIndex,
                exactChunk
            )
            chunkIndex++
            chunkBuffer =
                ByteArray(chunkSize)
            chunkUsed = 0
        }

        try {
            while (totalBytes <
                expectedBytes
            ) {
                currentCoroutineContext()
                    .ensureActive()

                val remaining =
                    expectedBytes -
                        totalBytes
                val read =
                    inputStream.read(
                        readBuffer,
                        0,
                        minOf(
                            readBuffer.size
                                .toLong(),
                            remaining
                        ).toInt()
                    )
                if (read < 0) {
                    throw EOFException(
                        "Early EOF: expected $expectedBytes bytes, but stream ended after $totalBytes bytes"
                    )
                }
                if (read == 0) {
                    continue
                }

                digest.update(
                    readBuffer,
                    0,
                    read
                )
                outputStream?.write(
                    readBuffer,
                    0,
                    read
                )

                var sourceOffset = 0
                while (sourceOffset <
                    read
                ) {
                    val copyCount =
                        minOf(
                            read -
                                sourceOffset,
                            chunkSize -
                                chunkUsed
                        )
                    readBuffer.copyInto(
                        destination =
                            chunkBuffer,
                        destinationOffset =
                            chunkUsed,
                        startIndex =
                            sourceOffset,
                        endIndex =
                            sourceOffset +
                                copyCount
                    )
                    sourceOffset +=
                        copyCount
                    chunkUsed +=
                        copyCount

                    if (chunkUsed ==
                        chunkSize
                    ) {
                        commitChunk()
                    }
                }

                totalBytes +=
                    read.toLong()
                onProgress?.invoke(
                    totalBytes
                )
            }

            val extra =
                inputStream.read()
            if (extra != -1) {
                throw IllegalStateException(
                    "Stream exceeded expected size of $expectedBytes bytes (extra byte detected: $extra)"
                )
            }

            commitChunk()
            outputStream?.flush()

            return HashingResult(
                sizeBytes =
                    totalBytes,
                digest =
                    Sha256Digest
                        .fromByteArray(
                            digest.digest()
                        )
            )
        } finally {
            try {
                inputStream.close()
            } catch (_: Throwable) {
                // Suppress secondary exceptions on close.
            }
        }
    }

    /**
     * Reads [inputStream] and writes all bytes to [outputStream] (if non-null) while computing SHA-256.
     * Always closes [inputStream] upon completion, cancellation, or error.
     */
    suspend fun copyAndHash(
        inputStream: InputStream,
        outputStream: OutputStream?,
        expectedBytes: Long? = null,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
        onProgress: ((bytesRead: Long) -> Unit)? = null
    ): HashingResult {
        require(bufferSize > 0 && bufferSize <= 1_048_576) {
            "Buffer size must be between 1 byte and 1 MiB, got: $bufferSize"
        }
        if (expectedBytes != null) {
            require(expectedBytes in 0..ProtocolLimits.MAX_ITEM_BYTES) {
                "expectedBytes must be in 0..${ProtocolLimits.MAX_ITEM_BYTES}, got: $expectedBytes"
            }
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(bufferSize)
        var totalBytes = 0L

        try {
            while (true) {
                // Cooperative cancellation check
                currentCoroutineContext().ensureActive()

                val bytesToRead = if (expectedBytes != null) {
                    val remaining = expectedBytes - totalBytes
                    if (remaining == 0L) {
                        // Already read the expected count. Verify there is no extra byte!
                        val extraCheck = inputStream.read()
                        if (extraCheck != -1) {
                            throw IllegalStateException(
                                "Stream exceeded expected size of $expectedBytes bytes (extra byte detected: $extraCheck)"
                            )
                        }
                        break
                    }
                    minOf(buffer.size.toLong(), remaining).toInt()
                } else {
                    buffer.size
                }

                val read = inputStream.read(buffer, 0, bytesToRead)
                if (read == -1) {
                    // Reached EOF
                    if (expectedBytes != null && totalBytes != expectedBytes) {
                        throw EOFException(
                            "Early EOF: expected $expectedBytes bytes, but stream ended after $totalBytes bytes"
                        )
                    }
                    break
                }

                digest.update(buffer, 0, read)
                outputStream?.write(buffer, 0, read)
                totalBytes += read

                if (totalBytes > ProtocolLimits.MAX_ITEM_BYTES) {
                    throw IllegalStateException(
                        "Stream exceeded maximum allowed item size of ${ProtocolLimits.MAX_ITEM_BYTES} bytes"
                    )
                }

                onProgress?.invoke(totalBytes)
            }

            outputStream?.flush()
            val finalDigest = Sha256Digest.fromByteArray(digest.digest())
            return HashingResult(sizeBytes = totalBytes, digest = finalDigest)
        } finally {
            try {
                inputStream.close()
            } catch (_: Throwable) {
                // Suppress secondary exceptions on close
            }
        }
    }
}

/**
 * An [InputStream] wrapper that calculates SHA-256 and counts bytes on-the-fly as bytes are consumed.
 * Enforces expected byte counts, early EOF detection, extra-byte detection, idempotent single close,
 * and caches the final SHA-256 digest once actual EOF is verified.
 */
class HashingInputStream(
    private val delegate: InputStream,
    val expectedBytes: Long? = null,
    private var onProgress: ((bytesRead: Long) -> Unit)? = null
) : InputStream() {
    private val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    private var totalRead: Long = 0L
    private val isClosed = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var isEofReached: Boolean = false
    @Volatile private var cachedDigest: Sha256Digest? = null

    val bytesRead: Long get() = totalRead
    val isEof: Boolean get() = isEofReached

    fun setProgressCallback(callback: ((bytesRead: Long) -> Unit)?) {
        this.onProgress = callback
    }

    val sha256Digest: Sha256Digest
        get() {
            cachedDigest?.let { return it }
            proveEof()
            check(isEofReached) {
                "Digest only available after actual EOF reached (read $totalRead of expected $expectedBytes bytes)"
            }
            val d = Sha256Digest.fromByteArray(digest.digest())
            cachedDigest = d
            return d
        }

    fun proveEof(): Boolean {
        if (isEofReached) return true
        if (expectedBytes != null && totalRead == expectedBytes) {
            val extra = delegate.read()
            if (extra == -1) {
                isEofReached = true
                return true
            } else {
                throw IllegalStateException("Stream exceeded expected size of $expectedBytes bytes (extra byte detected: $extra)")
            }
        }
        return isEofReached
    }

    override fun read(): Int {
        checkNotClosed()
        if (expectedBytes != null && totalRead >= expectedBytes) {
            val extra = delegate.read()
            if (extra != -1) {
                throw IllegalStateException("Stream exceeded expected size of $expectedBytes bytes (extra byte detected: $extra)")
            }
            isEofReached = true
            return -1
        }

        val b = delegate.read()
        if (b != -1) {
            digest.update(b.toByte())
            totalRead++
            onProgress?.invoke(totalRead)
            if (expectedBytes != null && totalRead == expectedBytes) {
                proveEof()
            }
        } else {
            if (expectedBytes != null && totalRead < expectedBytes) {
                throw EOFException("Early EOF: expected $expectedBytes bytes, but stream ended after $totalRead bytes")
            }
            isEofReached = true
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        checkNotClosed()
        if (len == 0) return 0
        if (expectedBytes != null && totalRead >= expectedBytes) {
            val extra = delegate.read()
            if (extra != -1) {
                throw IllegalStateException("Stream exceeded expected size of $expectedBytes bytes (extra byte detected: $extra)")
            }
            isEofReached = true
            return -1
        }

        val maxToRead = if (expectedBytes != null) {
            val remaining = expectedBytes - totalRead
            minOf(len.toLong(), remaining).toInt()
        } else {
            len
        }

        val count = delegate.read(b, off, maxToRead)
        if (count > 0) {
            digest.update(b, off, count)
            totalRead += count
            onProgress?.invoke(totalRead)
            if (expectedBytes != null && totalRead == expectedBytes) {
                proveEof()
            }
        } else if (count == -1) {
            if (expectedBytes != null && totalRead < expectedBytes) {
                throw EOFException("Early EOF: expected $expectedBytes bytes, but stream ended after $totalRead bytes")
            }
            isEofReached = true
        }
        return count
    }

    override fun available(): Int {
        checkNotClosed()
        return delegate.available()
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            delegate.close()
        }
    }

    private fun checkNotClosed() {
        check(!isClosed.get()) { "Stream is closed" }
    }
}
