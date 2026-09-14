package dev.glide.data.source

import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.TransferFailure
import dev.glide.core.ports.SourceAccessException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BoundedSourceStagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val handle = SourceHandle("content://com.android.providers.media/documents/document/456")

    @Test
    fun stageSource_unknownSize_measuresExactBytesAndProducesValidMetadata() = runTest {
        val stagingDir = tempFolder.newFolder("staging_unknown")
        val payload = ByteArray(150_000) { (it % 256).toByte() }
        val fakeResolver = FakeContentResolverWrapper().apply {
            metadataResult = SourceQueryMetadata(displayName = "cloud_data.bin", sizeBytes = null)
            mimeType = "application/octet-stream"
            streamProvider = { ByteArrayInputStream(payload) }
        }

        val stager = BoundedSourceStager(
            stagingDir = stagingDir,
            resolverWrapper = fakeResolver,
            spaceProvider = { 1024 * 1024 * 1024L } // 1 GiB free
        )

        var reportedProgress = 0L
        val staged = stager.stageSource(
            handle = handle,
            displayName = "cloud_data.bin",
            mimeType = "application/octet-stream",
            onProgress = { reportedProgress = it }
        )

        assertEquals("cloud_data.bin", staged.metadata.name)
        assertEquals("application/octet-stream", staged.metadata.mimeType)
        assertEquals(150_000L, staged.metadata.sizeBytes)
        assertEquals(150_000L, reportedProgress)
        assertTrue(staged.stagedFile.exists())
        assertEquals(150_000L, staged.stagedFile.length())
        assertTrue(stager.isStaged(handle))

        // Ensure .part file was renamed and no orphan partials exist
        val partFiles = stagingDir.listFiles { _, name -> name.endsWith(".part") } ?: emptyArray()
        assertEquals(0, partFiles.size)

        // Verify content integrity
        val readBytes = stager.openStagedSource(handle)!!.use { it.readBytes() }
        assertArrayEquals(payload, readBytes)

        stager.cleanup(handle)
        assertFalse(staged.stagedFile.exists())
        assertFalse(stager.isStaged(handle))
    }

    @Test
    fun stageSource_providerHang_purgesPartialFileAndThrowsTimeout() = runBlocking(Dispatchers.Default) {
        val stagingDir = tempFolder.newFolder("staging_hang")

        class StalledInputStream : InputStream() {
            override fun read(): Int {
                Thread.sleep(5000)
                return -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                Thread.sleep(5000)
                return -1
            }
        }

        val fakeResolver = FakeContentResolverWrapper().apply {
            metadataResult = SourceQueryMetadata(displayName = "hung_source.bin", sizeBytes = null)
            mimeType = "application/octet-stream"
            streamProvider = { StalledInputStream() }
        }

        val stager = BoundedSourceStager(
            stagingDir = stagingDir,
            resolverWrapper = fakeResolver,
            readTimeoutMs = 150L, // 150ms timeout
            spaceProvider = { 1024 * 1024 * 1024L }
        )

        try {
            stager.stageSource(handle, "hung_source.bin")
            fail("Expected timeout SourceAccessException")
        } catch (e: SourceAccessException) {
            assertTrue(e.failure is TransferFailure.Timeout)
            val failure = e.failure as TransferFailure.Timeout
            assertEquals("staging", failure.phase)
        }

        // Verify partial and staged files are completely purged
        val files = stagingDir.listFiles() ?: emptyArray()
        assertEquals(0, files.size)
        assertFalse(stager.isStaged(handle))
    }

    @Test
    fun stageSource_cancellation_immediatelyRemovesPartialFileWithZeroLeakedBytes() = runBlocking(Dispatchers.Default) {
        val stagingDir = tempFolder.newFolder("staging_cancel")
        val firstChunkWritten = CountDownLatch(1)

        class SlowStream : InputStream() {
            override fun read(): Int = 0

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                firstChunkWritten.countDown()
                Thread.sleep(20)
                return 1024
            }
        }

        val fakeResolver = FakeContentResolverWrapper().apply {
            metadataResult = SourceQueryMetadata(displayName = "slow.bin", sizeBytes = null)
            mimeType = "application/octet-stream"
            streamProvider = { SlowStream() }
        }

        val stager = BoundedSourceStager(
            stagingDir = stagingDir,
            resolverWrapper = fakeResolver,
            readTimeoutMs = 10_000L,
            spaceProvider = { 1024 * 1024 * 1024L }
        )

        val job = launch {
            stager.stageSource(handle, "slow.bin")
        }

        // Wait until stager opens and writes something
        firstChunkWritten.await()
        delay(30)

        // Cancel the staging job
        job.cancelAndJoin()

        // Verify zero leaked bytes on disk
        val files = stagingDir.listFiles() ?: emptyArray()
        assertEquals("Expected 0 files remaining after cancellation", 0, files.size)
        assertFalse(stager.isStaged(handle))
    }

    @Test
    fun stageSource_diskExhaustionInitial_purgesAndThrowsInsufficientStorage() = runTest {
        val stagingDir = tempFolder.newFolder("staging_disk_init")
        val fakeResolver = FakeContentResolverWrapper().apply {
            metadataResult = SourceQueryMetadata(displayName = "large.dat", sizeBytes = null)
            mimeType = "application/octet-stream"
            streamProvider = { ByteArrayInputStream(ByteArray(1024)) }
        }

        val stager = BoundedSourceStager(
            stagingDir = stagingDir,
            resolverWrapper = fakeResolver,
            diskReserveBytes = 256 * 1024 * 1024L,
            spaceProvider = { 100 * 1024 * 1024L } // Only 100 MiB free, below 256 MiB reserve
        )

        try {
            stager.stageSource(handle, "large.dat")
            fail("Expected InsufficientStorage SourceAccessException")
        } catch (e: SourceAccessException) {
            assertTrue(e.failure is TransferFailure.InsufficientStorage)
            val failure = e.failure as TransferFailure.InsufficientStorage
            assertEquals(256 * 1024 * 1024L, failure.requiredBytes)
            assertEquals(100 * 1024 * 1024L, failure.availableBytes)
        }

        val files = stagingDir.listFiles() ?: emptyArray()
        assertEquals(0, files.size)
    }

    @Test
    fun stageSource_diskExhaustionInFlight_purgesPartialAndThrowsInsufficientStorage() = runTest {
        val stagingDir = tempFolder.newFolder("staging_disk_inflight")
        val callCount = AtomicInteger(0)
        val payload = ByteArray(200_000)

        val fakeResolver = FakeContentResolverWrapper().apply {
            metadataResult = SourceQueryMetadata(displayName = "inflight.bin", sizeBytes = null)
            mimeType = "application/octet-stream"
            streamProvider = { ByteArrayInputStream(payload) }
        }

        val stager = BoundedSourceStager(
            stagingDir = stagingDir,
            resolverWrapper = fakeResolver,
            diskReserveBytes = 256 * 1024 * 1024L,
            spaceProvider = {
                val count = callCount.incrementAndGet()
                if (count <= 1) {
                    500 * 1024 * 1024L // Initial check passes (500 MiB)
                } else {
                    100 * 1024 * 1024L // In-flight drops below reserve (100 MiB)
                }
            }
        )

        try {
            stager.stageSource(handle, "inflight.bin")
            fail("Expected in-flight InsufficientStorage SourceAccessException")
        } catch (e: SourceAccessException) {
            assertTrue(e.failure is TransferFailure.InsufficientStorage)
        }

        val files = stagingDir.listFiles() ?: emptyArray()
        assertEquals(0, files.size)
        assertFalse(stager.isStaged(handle))
    }

    @Test
    fun transientGrantLifetime_stagedContentRemainsReadableAfterProviderRevocation() = runTest {
        val stagingDir = tempFolder.newFolder("staging_transient")
        val payload = "Transient grant test payload across expiration".toByteArray(Charsets.UTF_8)
        val grantRevoked = AtomicBoolean(false)

        val fakeResolver = FakeContentResolverWrapper().apply {
            metadataResult = SourceQueryMetadata(displayName = "transient_file.txt", sizeBytes = null)
            mimeType = "text/plain"
            streamProvider = {
                if (grantRevoked.get()) {
                    throw SecurityException("Permission Denial: transient grant expired")
                } else {
                    ByteArrayInputStream(payload)
                }
            }
        }

        val stager = BoundedSourceStager(
            stagingDir = stagingDir,
            resolverWrapper = fakeResolver,
            spaceProvider = { 1024 * 1024 * 1024L }
        )

        val sourceAccess = AndroidSourceAccess(
            resolverWrapper = fakeResolver,
            stager = stager
        )

        // 1. Validate and stage source while grant is valid
        val metadata = sourceAccess.validateSource(handle)
        assertEquals("transient_file.txt", metadata.name)
        assertEquals(payload.size.toLong(), metadata.sizeBytes)

        // 2. Simulate grant expiration/revocation
        grantRevoked.set(true)

        // Direct ContentResolver access now throws SecurityException
        try {
            fakeResolver.openInputStream(handle.value)
            fail("Expected SecurityException on revoked stream")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("Permission Denial"))
        }

        // 3. Opening through SourceAccess still succeeds via staged app-private cache!
        val openStream = sourceAccess.openSource(handle)
        val readBytes = openStream.readBytes()
        openStream.close()
        assertArrayEquals(payload, readBytes)

        // 4. Cleanup removes the file
        sourceAccess.cleanupStaged(handle)
        assertNull(stager.openStagedSource(handle))

        // 5. Subsequent open after cleanup fails because grant is revoked
        try {
            sourceAccess.openSource(handle)
            fail("Expected SourceAccessException after cleanup")
        } catch (e: SourceAccessException) {
            assertTrue(e.failure is TransferFailure.SourceUnavailable)
        }
    }

    @Test
    fun cleanupAll_removesAllStagedFilesAndResetsState() = runTest {
        val stagingDir = tempFolder.newFolder("staging_cleanup_all")
        val fakeResolver = FakeContentResolverWrapper().apply {
            metadataResult = SourceQueryMetadata(displayName = "file.bin", sizeBytes = null)
            mimeType = "application/octet-stream"
            streamProvider = { ByteArrayInputStream(ByteArray(1024)) }
        }

        val stager = BoundedSourceStager(
            stagingDir = stagingDir,
            resolverWrapper = fakeResolver,
            spaceProvider = { 1024 * 1024 * 1024L }
        )

        val h1 = SourceHandle("content://media/1")
        val h2 = SourceHandle("content://media/2")

        stager.stageSource(h1, "file1.bin")
        stager.stageSource(h2, "file2.bin")

        assertTrue(stager.isStaged(h1))
        assertTrue(stager.isStaged(h2))

        stager.cleanupAll()

        assertFalse(stager.isStaged(h1))
        assertFalse(stager.isStaged(h2))
        assertEquals(0, stagingDir.listFiles()?.size ?: 0)
    }

    @Test
    fun cleanupOrphans_deletesOldFilesWhileRetainingRecent() {
        val stagingDir = tempFolder.newFolder("staging_orphans")
        val oldFile = File(stagingDir, "src_stage_old.staged").apply {
            writeBytes(ByteArray(100))
            setLastModified(System.currentTimeMillis() - 48 * 3600 * 1000L) // 48h old
        }
        val recentFile = File(stagingDir, "src_stage_recent.staged").apply {
            writeBytes(ByteArray(100))
            setLastModified(System.currentTimeMillis())
        }

        val fakeResolver = FakeContentResolverWrapper()
        val stager = BoundedSourceStager(
            stagingDir = stagingDir,
            resolverWrapper = fakeResolver
        )

        stager.cleanupOrphans(maxAgeMs = 24 * 3600 * 1000L)

        assertFalse(oldFile.exists())
        assertTrue(recentFile.exists())
    }
}
