package dev.glide.data.source

import dev.glide.core.domain.Sha256Digest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.security.MessageDigest

class StreamHasherTest {

    private val emptySha256 = Sha256Digest("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

    @Test
    fun emptyFile_producesExactEmptyHash() = runTest {
        val stream = ByteArrayInputStream(ByteArray(0))
        val result = StreamHasher.hashStream(stream, expectedBytes = 0L)

        assertEquals(0L, result.sizeBytes)
        assertEquals(emptySha256, result.digest)
    }

    @Test
    fun tenMegabyteFile_producesExactHash() = runTest {
        val totalSize = 10 * 1024 * 1024 // 10 MiB = 10,485,760 bytes
        // Deterministic synthetic stream generating known repeating pattern
        val patternStream = object : InputStream() {
            private var bytesEmitted = 0L
            override fun read(): Int {
                if (bytesEmitted >= totalSize) return -1
                val b = (bytesEmitted % 256).toInt()
                bytesEmitted++
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (bytesEmitted >= totalSize) return -1
                val toRead = minOf(len.toLong(), totalSize - bytesEmitted).toInt()
                for (i in 0 until toRead) {
                    b[off + i] = ((bytesEmitted + i) % 256).toByte()
                }
                bytesEmitted += toRead
                return toRead
            }
        }

        // Expected digest precomputed over the exact same deterministic stream
        val md = MessageDigest.getInstance("SHA-256")
        val referenceBuffer = ByteArray(65536)
        var referenceCount = 0L
        while (referenceCount < totalSize) {
            val chunk = minOf(referenceBuffer.size.toLong(), totalSize - referenceCount).toInt()
            for (i in 0 until chunk) {
                referenceBuffer[i] = ((referenceCount + i) % 256).toByte()
            }
            md.update(referenceBuffer, 0, chunk)
            referenceCount += chunk
        }
        val expectedDigest = Sha256Digest.fromByteArray(md.digest())

        var progressUpdates = 0
        var lastReportedBytes = 0L
        val result = StreamHasher.hashStream(
            inputStream = patternStream,
            expectedBytes = totalSize.toLong(),
            bufferSize = 65536,
            onProgress = { bytesRead ->
                progressUpdates++
                lastReportedBytes = bytesRead
            }
        )

        assertEquals(totalSize.toLong(), result.sizeBytes)
        assertEquals(expectedDigest, result.digest)
        assertEquals(totalSize.toLong(), lastReportedBytes)
        assertTrue("Expected multiple 64KiB buffer progress updates", progressUpdates > 100)
    }

    @Test
    fun earlyEof_failsIntegrityCheck() = runTest {
        val actualBytes = "Short content".toByteArray(Charsets.UTF_8)
        val stream = ByteArrayInputStream(actualBytes)
        val expectedBytes = actualBytes.size.toLong() + 50L // Expects more bytes than provided

        try {
            StreamHasher.hashStream(stream, expectedBytes = expectedBytes)
            fail("Expected EOFException on early EOF")
        } catch (e: EOFException) {
            assertTrue(e.message!!.contains("Early EOF"))
        }
    }

    @Test
    fun extraByte_failsImmediately() = runTest {
        val actualBytes = "Unexpected extra data".toByteArray(Charsets.UTF_8)
        val stream = ByteArrayInputStream(actualBytes)
        val expectedBytes = actualBytes.size.toLong() - 5L // Expects fewer bytes than provided

        try {
            StreamHasher.hashStream(stream, expectedBytes = expectedBytes)
            fail("Expected IllegalStateException when stream exceeds expected size")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Stream exceeded expected size"))
        }
    }

    @Test
    fun cancel_closesDescriptorAndStopsStream() = runTest {
        var isClosed = false
        var hasRead = false
        val slowStream = object : InputStream() {
            override fun read(): Int {
                hasRead = true
                return 42
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                hasRead = true
                b.fill(42, off, off + len)
                return len
            }
            override fun close() {
                isClosed = true
                super.close()
            }
        }

        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            StreamHasher.hashStream(slowStream, expectedBytes = 100_000_000L)
        }

        // Wait until stream has started reading
        while (!hasRead) {
            kotlinx.coroutines.delay(10)
        }

        // Cancel job mid-stream
        job.cancelAndJoin()

        assertTrue("Stream must be closed on coroutine cancellation", isClosed)
    }

    @Test
    fun copyAndHash_streamsBytesToDestinationOutput() = runTest {
        val payload = "Hello, Glide streaming hash!".toByteArray(Charsets.UTF_8)
        val inputStream = ByteArrayInputStream(payload)
        val outputStream = ByteArrayOutputStream()

        val expectedDigest = Sha256Digest.compute(payload)
        val result = StreamHasher.copyAndHash(
            inputStream = inputStream,
            outputStream = outputStream,
            expectedBytes = payload.size.toLong()
        )

        assertEquals(payload.size.toLong(), result.sizeBytes)
        assertEquals(expectedDigest, result.digest)
        assertArrayEquals(payload, outputStream.toByteArray())
    }
}
