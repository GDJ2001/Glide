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
 * Used by sender to hash exactly what is sent to the transport.
 */
class HashingInputStream(
    private val delegate: InputStream,
    private val onProgress: ((bytesRead: Long) -> Unit)? = null
) : InputStream() {
    private val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    private var totalRead: Long = 0L

    val bytesRead: Long get() = totalRead
    val sha256Digest: Sha256Digest
        get() = Sha256Digest.fromByteArray(digest.digest())

    override fun read(): Int {
        val b = delegate.read()
        if (b != -1) {
            digest.update(b.toByte())
            totalRead++
            onProgress?.invoke(totalRead)
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val count = delegate.read(b, off, len)
        if (count > 0) {
            digest.update(b, off, count)
            totalRead += count
            onProgress?.invoke(totalRead)
        }
        return count
    }

    override fun available(): Int = delegate.available()

    override fun close() {
        delegate.close()
    }
}
