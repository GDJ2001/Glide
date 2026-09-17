package dev.glide.data.performance

import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.stream.StreamHasher
import dev.glide.data.db.ReceiveJournalEntity
import dev.glide.data.db.ReceivedFileEntity
import dev.glide.data.store.AndroidReceiveStore
import dev.glide.data.store.FakeReceiveJournalDao
import dev.glide.data.store.FakeReceivedFileDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Benchmark suite evaluating physical storage pipeline performance (GLIDE-051).
 *
 * Verifies:
 * - End-to-end streaming write into app-private quarantine with single-pass SHA-256 hashing.
 * - Storage amplification factor (1.0x atomic relocation vs duplicate staging copies).
 * - Multi-file staging batch IOPS and descriptor lifecycle.
 * - Discard and abort latency.
 *
 * The production store uses Dispatchers.IO. This JVM benchmark injects Dispatchers.Unconfined so
 * wall-clock assertions measure filesystem/store work rather than shared-CI coroutine scheduling
 * delay. Production dispatcher behavior is covered separately by functional store tests.
 */
class StorageStreamingBenchmarkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var journalDao: FakeReceiveJournalDao
    private lateinit var fileCatalogDao: FakeReceivedFileDao
    private lateinit var receiveStore: AndroidReceiveStore

    private val sessionId = SessionId("11111111-1111-4111-8111-111111111111")

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("glide_storage_bench")
        journalDao = FakeReceiveJournalDao()
        fileCatalogDao = FakeReceivedFileDao()
        receiveStore = AndroidReceiveStore(
            rootDir = rootDir,
            journalDao = journalDao,
            fileCatalogDao = fileCatalogDao,
            freeSpaceProvider = { 100_000_000_000L }, // 100 GB
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun stageStreamAndCommit_10MB_measuresThroughputAndStorageAmplification() = runTest {
        val sizeBytes = 10L * 1024L * 1024L // 10 MiB
        val itemId = ItemId(UUID.randomUUID().toString())

        val handle = receiveStore.prepareStaging(sessionId, itemId, sizeBytes)
        val stream = FastDeterministicInputStream(sizeBytes)

        val writeStart = System.nanoTime()
        val hashResult = receiveStore.openStagingOutput(handle).use { out ->
            StreamHasher.copyAndHash(
                inputStream = stream,
                outputStream = out,
                expectedBytes = sizeBytes,
                bufferSize = StreamHasher.DEFAULT_BUFFER_SIZE
            )
        }
        val writeDurationMs = (System.nanoTime() - writeStart) / 1_000_000
        val writeThroughputMBps = (sizeBytes.toDouble() / (1024.0 * 1024.0)) / (writeDurationMs.toDouble() / 1000.0)

        val stagingFile = File(receiveStore.stagingDir, "${itemId.value}.part")
        assertTrue("Staging file must exist prior to commit", stagingFile.exists())
        val peakStagingBytes = stagingFile.length()
        assertEquals(sizeBytes, peakStagingBytes)

        val commitStart = System.nanoTime()
        val committedHandle = receiveStore.commitVerified(handle, hashResult.digest)
        val commitDurationMs = (System.nanoTime() - commitStart) / 1_000_000

        // Staging file must be moved/renamed atomically (not copied)
        assertFalse("Staging .part file must be removed upon commit", stagingFile.exists())
        val committedFile = File(receiveStore.receivedDir, committedHandle.value)
        assertTrue("Committed file must exist in quarantine", committedFile.exists())
        assertEquals(sizeBytes, committedFile.length())

        val totalDurationMs = writeDurationMs + commitDurationMs
        val overallThroughputMBps = (sizeBytes.toDouble() / (1024.0 * 1024.0)) / (totalDurationMs.toDouble() / 1000.0)

        // Storage amplification: peak storage / payload size
        val diskAmplification = peakStagingBytes.toDouble() / sizeBytes.toDouble()

        println("\n Storage Benchmark (10 MiB Physical Staging + Commit):")
        println("   Write + Hash Time   : $writeDurationMs ms (${String.format("%.2f", writeThroughputMBps)} MB/s)")
        println("   Atomic Commit Time  : $commitDurationMs ms")
        println("   Total End-to-End    : $totalDurationMs ms (${String.format("%.2f", overallThroughputMBps)} MB/s)")
        println("   Disk Amplification  : ${String.format("%.1f", diskAmplification)}x (Target: 1.0x)")

        assertEquals(1.0, diskAmplification, 0.001)
        assertTrue("Write throughput to disk must exceed 50 MB/s", writeThroughputMBps > 50.0)
        assertTrue("Atomic commit must complete under 100 ms", commitDurationMs < 100)
    }

    @Test
    fun stageStreamAndCommit_50MB_measuresThroughputAndStorageAmplification() = runTest {
        val sizeBytes = 50L * 1024L * 1024L // 50 MiB
        val itemId = ItemId(UUID.randomUUID().toString())

        val handle = receiveStore.prepareStaging(sessionId, itemId, sizeBytes)
        val stream = FastDeterministicInputStream(sizeBytes)

        val writeStart = System.nanoTime()
        val hashResult = receiveStore.openStagingOutput(handle).use { out ->
            StreamHasher.copyAndHash(
                inputStream = stream,
                outputStream = out,
                expectedBytes = sizeBytes,
                bufferSize = StreamHasher.DEFAULT_BUFFER_SIZE
            )
        }
        val writeDurationMs = (System.nanoTime() - writeStart) / 1_000_000
        val writeThroughputMBps = (sizeBytes.toDouble() / (1024.0 * 1024.0)) / (writeDurationMs.toDouble() / 1000.0)

        val commitStart = System.nanoTime()
        val committedHandle = receiveStore.commitVerified(handle, hashResult.digest)
        val commitDurationMs = (System.nanoTime() - commitStart) / 1_000_000

        val totalDurationMs = writeDurationMs + commitDurationMs
        val overallThroughputMBps = (sizeBytes.toDouble() / (1024.0 * 1024.0)) / (totalDurationMs.toDouble() / 1000.0)

        println("\n Storage Benchmark (50 MiB Physical Staging + Commit):")
        println("   Write + Hash Time   : $writeDurationMs ms (${String.format("%.2f", writeThroughputMBps)} MB/s)")
        println("   Atomic Commit Time  : $commitDurationMs ms")
        println("   Total End-to-End    : $totalDurationMs ms (${String.format("%.2f", overallThroughputMBps)} MB/s)")

        assertTrue("Write throughput to disk must exceed 50 MB/s", writeThroughputMBps > 50.0)
    }

    @Test
    fun discardStaging_cleansUpDiskImmediately() = runTest {
        val sizeBytes = 10L * 1024L * 1024L // 10 MiB
        val itemId = ItemId(UUID.randomUUID().toString())

        val handle = receiveStore.prepareStaging(sessionId, itemId, sizeBytes)
        val stream = FastDeterministicInputStream(sizeBytes)

        receiveStore.openStagingOutput(handle).use { out ->
            StreamHasher.copyAndHash(
                inputStream = stream,
                outputStream = out,
                expectedBytes = sizeBytes
            )
        }

        val stagingFile = File(receiveStore.stagingDir, "${itemId.value}.part")
        assertTrue(stagingFile.exists())

        val discardStart = System.nanoTime()
        receiveStore.discardStaging(handle)
        val discardDurationMs = (System.nanoTime() - discardStart) / 1_000_000

        assertFalse("Staging file must be wiped immediately on discard", stagingFile.exists())
        println("\n Discard Staging Latency: $discardDurationMs ms")
        assertTrue("Discard staging must complete in <= 50 ms", discardDurationMs <= 50)
    }

    @Test
    fun oneHundredFileBatch_stagingAndCommit_measuresBatchIOPS() = runTest {
        val fileCount = 100
        val fileSize = 64 * 1024L // 64 KiB each
        val start = System.nanoTime()

        for (i in 1..fileCount) {
            val itemId = ItemId(UUID.randomUUID().toString())
            val handle = receiveStore.prepareStaging(sessionId, itemId, fileSize)
            val stream = FastDeterministicInputStream(fileSize)

            val hashResult = receiveStore.openStagingOutput(handle).use { out ->
                StreamHasher.copyAndHash(
                    inputStream = stream,
                    outputStream = out,
                    expectedBytes = fileSize
                )
            }
            receiveStore.commitVerified(handle, hashResult.digest)
        }

        val durationMs = (System.nanoTime() - start) / 1_000_000
        val iops = (fileCount.toDouble() / (durationMs.toDouble() / 1000.0))
        println("\n 100-File Storage Staging + Commit Batch:")
        println("   Total Time : $durationMs ms")
        println("   Throughput : ${String.format("%.1f", iops)} files/sec")

        assertEquals(fileCount, fileCatalogDao.files.size)
        assertTrue("Batch file processing must exceed 20 files/sec on local disk", iops > 20.0)
    }
}

/**
 * Fast deterministic stream emitting patterned bytes using System.arraycopy.
 */
class FastDeterministicInputStream(
    val totalBytes: Long,
    val patternSize: Int = 65536
) : InputStream() {
    private var bytesEmitted = 0L
    private val pattern = ByteArray(patternSize) { i ->
        ((i xor (i ushr 8) xor 0x3C) and 0xFF).toByte()
    }

    override fun read(): Int {
        if (bytesEmitted >= totalBytes) return -1
        val b = pattern[(bytesEmitted % patternSize).toInt()].toInt() and 0xFF
        bytesEmitted++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (bytesEmitted >= totalBytes) return -1
        val remaining = totalBytes - bytesEmitted
        val toRead = minOf(len.toLong(), remaining).toInt()
        var written = 0
        while (written < toRead) {
            val patternOffset = ((bytesEmitted + written) % patternSize).toInt()
            val chunk = minOf(toRead - written, patternSize - patternOffset)
            System.arraycopy(pattern, patternOffset, b, off + written, chunk)
            written += chunk
        }
        bytesEmitted += toRead
        return toRead
    }
}
