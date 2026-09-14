package dev.glide.data.store

import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.TransferFailure
import dev.glide.data.db.JournalPhases
import dev.glide.data.db.ReceiveJournalDao
import dev.glide.data.db.ReceiveJournalEntity
import dev.glide.data.db.ReceivedFileDao
import dev.glide.data.db.ReceivedFileEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FakeReceiveJournalDao : ReceiveJournalDao {
    val entries = mutableMapOf<String, ReceiveJournalEntity>()

    override suspend fun insertOrUpdate(entry: ReceiveJournalEntity) {
        entries[entry.itemId] = entry
    }

    override suspend fun getByItemId(itemId: String): ReceiveJournalEntity? {
        return entries[itemId]
    }

    override suspend fun updatePhase(itemId: String, phase: String, updatedAtMs: Long) {
        val cur = entries[itemId] ?: return
        entries[itemId] = cur.copy(phase = phase, updatedAtMs = updatedAtMs)
    }

    override suspend fun updateVerifiedDigest(
        itemId: String,
        phase: String,
        digest: String,
        updatedAtMs: Long
    ) {
        val cur = entries[itemId] ?: return
        entries[itemId] = cur.copy(phase = phase, verifiedDigest = digest, updatedAtMs = updatedAtMs)
    }

    override suspend fun delete(itemId: String) {
        entries.remove(itemId)
    }

    override suspend fun getPendingEntries(): List<ReceiveJournalEntity> {
        return entries.values.filter { it.phase != JournalPhases.COMPLETED }
    }

    override suspend fun getEntriesBySessionId(sessionId: String): List<ReceiveJournalEntity> {
        return entries.values.filter { it.sessionId == sessionId }
    }

    override suspend fun deleteBySessionId(sessionId: String) {
        entries.entries.removeIf { it.value.sessionId == sessionId }
    }
}

class FakeReceivedFileDao : ReceivedFileDao {
    val files = mutableMapOf<String, ReceivedFileEntity>()

    override suspend fun insertOrUpdate(file: ReceivedFileEntity) {
        files[file.fileKey] = file
    }

    override suspend fun getByFileKey(fileKey: String): ReceivedFileEntity? {
        return files[fileKey]
    }

    override suspend fun getByOriginatingItemId(itemId: String): ReceivedFileEntity? {
        return files.values.firstOrNull { it.originatingItemId == itemId }
    }

    override suspend fun getActiveFiles(): List<ReceivedFileEntity> {
        return files.values.filter { it.deletedAtMs == null }
    }

    override suspend fun getActiveFileCount(): Int {
        return files.values.count { it.deletedAtMs == null }
    }

    override suspend fun getActiveFileTotalBytes(): Long {
        return files.values.filter { it.deletedAtMs == null }.sumOf { it.length }
    }

    override suspend fun markDeleted(fileKey: String, deletedAtMs: Long) {
        val cur = files[fileKey] ?: return
        files[fileKey] = cur.copy(deletedAtMs = deletedAtMs)
    }

    override suspend fun deletePermanently(fileKey: String) {
        files.remove(fileKey)
    }

    override suspend fun detachItemIdsOlderThan(cutoffMs: Long): Int {
        var count = 0
        files.values.forEach {
            if (it.originatingItemId != null) {
                files[it.fileKey] = it.copy(originatingItemId = null)
                count++
            }
        }
        return count
    }

    override suspend fun detachItemIdsExceedingMax(maxCount: Int): Int {
        var count = 0
        files.values.forEach {
            if (it.originatingItemId != null) {
                files[it.fileKey] = it.copy(originatingItemId = null)
                count++
            }
        }
        return count
    }

    override suspend fun detachAllItemIds(): Int {
        var count = 0
        files.values.forEach {
            if (it.originatingItemId != null) {
                files[it.fileKey] = it.copy(originatingItemId = null)
                count++
            }
        }
        return count
    }
}

class AndroidReceiveStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var journalDao: FakeReceiveJournalDao
    private lateinit var fileCatalogDao: FakeReceivedFileDao

    private val sessionId = SessionId("11111111-1111-4111-8111-111111111111")
    private val itemId = ItemId("22222222-2222-4222-8222-222222222222")

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("glide_storage")
        journalDao = FakeReceiveJournalDao()
        fileCatalogDao = FakeReceivedFileDao()
    }

    @Test
    fun diskFull_spaceCheckRejectsOffer() = runTest {
        // Mock free space to 100 MB
        val freeSpace = 100L * 1024L * 1024L
        val receiveStore = AndroidReceiveStore(
            rootDir = rootDir,
            journalDao = journalDao,
            fileCatalogDao = fileCatalogDao,
            freeSpaceProvider = { freeSpace }
        )

        // Required bytes: 50 MB. Margin is max(256MB, 5% of 50MB) = 256MB. Total budget: 306MB > 100MB
        val requiredBytes = 50L * 1024L * 1024L
        assertFalse(receiveStore.hasAvailableSpace(requiredBytes))

        try {
            receiveStore.prepareStaging(sessionId, itemId, requiredBytes)
            fail("Expected InsufficientStorage failure")
        } catch (e: dev.glide.core.ports.ReceiveStoreException) {
            val failure = e.failure as TransferFailure.InsufficientStorage
            assertEquals(requiredBytes, failure.requiredBytes)
            assertEquals(freeSpace, failure.availableBytes)
        }
    }

    @Test
    fun wrongHash_rejectsCommitAndDeletesPartial() = runTest {
        val receiveStore = AndroidReceiveStore(
            rootDir = rootDir,
            journalDao = journalDao,
            fileCatalogDao = fileCatalogDao,
            freeSpaceProvider = { 10_000_000_000L } // 10 GB
        )

        val payload = "Real payload bytes".toByteArray(Charsets.UTF_8)
        val handle = receiveStore.prepareStaging(sessionId, itemId, payload.size.toLong())

        val output = receiveStore.openStagingOutput(handle)
        output.write(payload)
        output.flush()
        output.close()

        val stagingFile = File(receiveStore.stagingDir, "${itemId.value}.part")
        assertTrue("Staging file must exist before commit", stagingFile.exists())

        val wrongDigest = Sha256Digest("f".repeat(64))
        try {
            receiveStore.commitVerified(handle, wrongDigest)
            fail("Expected IntegrityMismatch when digest does not match on-disk bytes")
        } catch (e: dev.glide.core.ports.ReceiveStoreException) {
            val failure = e.failure as TransferFailure.IntegrityMismatch
            assertEquals(wrongDigest, failure.expected)
        }

        // Acceptance criterion: Unverified file is NEVER visible/openable; deleted immediately
        assertFalse("Staging file must be deleted on integrity mismatch", stagingFile.exists())
        assertEquals(0, fileCatalogDao.files.size)
        assertNull(journalDao.getByItemId(itemId.value))
    }

    @Test
    fun zeroByteFile_stagesAndCommitsSuccessfully() = runTest {
        val receiveStore = AndroidReceiveStore(
            rootDir = rootDir,
            journalDao = journalDao,
            fileCatalogDao = fileCatalogDao,
            freeSpaceProvider = { 10_000_000_000L }
        )

        val handle = receiveStore.prepareStaging(
            sessionId = sessionId,
            itemId = itemId,
            expectedBytes = 0L,
            displayName = "empty.txt",
            mimeType = "text/plain"
        )

        val output = receiveStore.openStagingOutput(handle)
        output.close()

        val emptyDigest = Sha256Digest("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        val committedHandle = receiveStore.commitVerified(handle, emptyDigest)

        assertNotNull(committedHandle)

        // Verified file exists in received/
        val finalFile = File(receiveStore.receivedDir, committedHandle.value)
        assertTrue(finalFile.exists())
        assertEquals(0L, finalFile.length())

        // File catalog record is created
        val catalogRecord = fileCatalogDao.getByFileKey(committedHandle.value)
        assertNotNull(catalogRecord)
        assertEquals("empty.txt", catalogRecord!!.displayName)
        assertEquals(0L, catalogRecord.length)
        assertEquals(emptyDigest.value, catalogRecord.digest)
    }

    @Test
    fun crashRecovery_preCommitCleansPartial() = runTest {
        val receiveStore = AndroidReceiveStore(
            rootDir = rootDir,
            journalDao = journalDao,
            fileCatalogDao = fileCatalogDao,
            freeSpaceProvider = { 10_000_000_000L }
        )

        val payload = "Interrupted upload".toByteArray(Charsets.UTF_8)
        val handle = receiveStore.prepareStaging(sessionId, itemId, payload.size.toLong())
        val output = receiveStore.openStagingOutput(handle)
        output.write(payload)
        output.close()

        // Simulate crash: phase is WRITING and .part file is left in staging
        val stagingFile = File(receiveStore.stagingDir, "${itemId.value}.part")
        assertTrue(stagingFile.exists())

        // Recovery / startup reconciliation
        val cleanedCount = receiveStore.reconcile()
        assertEquals(1, cleanedCount)

        assertFalse("Interrupted unverified file must be cleaned", stagingFile.exists())
        assertNull(journalDao.getByItemId(itemId.value))
        assertEquals(0, fileCatalogDao.files.size)
    }

    @Test
    fun crashRecovery_postRenameCompletesIdempotently() = runTest {
        val receiveStore = AndroidReceiveStore(
            rootDir = rootDir,
            journalDao = journalDao,
            fileCatalogDao = fileCatalogDao,
            freeSpaceProvider = { 10_000_000_000L }
        )

        val payload = "Verified data".toByteArray(Charsets.UTF_8)
        val handle = receiveStore.prepareStaging(sessionId, itemId, payload.size.toLong(), displayName = "doc.pdf")
        val output = receiveStore.openStagingOutput(handle)
        output.write(payload)
        output.close()

        val digest = Sha256Digest.compute(payload)

        // Manually simulate crash during COMMITTING right after file rename:
        val entry = journalDao.getByItemId(itemId.value)!!
        val stagingFile = File(receiveStore.stagingDir, entry.stagingKey)
        val finalFile = File(receiveStore.receivedDir, entry.finalKey)
        stagingFile.renameTo(finalFile)

        journalDao.updateVerifiedDigest(
            itemId = itemId.value,
            phase = JournalPhases.COMMITTING,
            digest = digest.value,
            updatedAtMs = System.currentTimeMillis()
        )

        // Reconciliation discovers final file and completes catalog entry
        val reconciled = receiveStore.reconcile()
        assertEquals(1, reconciled)

        assertTrue(finalFile.exists())
        val catalogRecord = fileCatalogDao.getByFileKey(entry.finalKey)
        assertNotNull(catalogRecord)
        assertEquals("doc.pdf", catalogRecord!!.displayName)
        assertEquals(digest.value, catalogRecord.digest)
        assertNull("Journal entry should be cleared after reconciliation", journalDao.getByItemId(itemId.value))
    }

    @Test
    fun discardStaging_cleansPartialFileAndJournal() = runTest {
        val receiveStore = AndroidReceiveStore(
            rootDir = rootDir,
            journalDao = journalDao,
            fileCatalogDao = fileCatalogDao,
            freeSpaceProvider = { 10_000_000_000L }
        )

        val handle = receiveStore.prepareStaging(sessionId, itemId, 100L)
        val stagingFile = File(receiveStore.stagingDir, "${itemId.value}.part")
        assertTrue(stagingFile.exists())

        receiveStore.discardStaging(handle)

        assertFalse(stagingFile.exists())
        assertNull(journalDao.getByItemId(itemId.value))
    }
}
