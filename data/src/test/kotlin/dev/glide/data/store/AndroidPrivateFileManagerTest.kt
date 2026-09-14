package dev.glide.data.store

import dev.glide.data.db.ReceiveJournalDao
import dev.glide.data.db.ReceiveJournalEntity
import dev.glide.data.db.ReceivedFileDao
import dev.glide.data.db.ReceivedFileEntity
import dev.glide.data.db.SessionDao
import dev.glide.data.db.SessionEntity
import dev.glide.data.db.SessionWithItems
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FakeTestReceivedFileDao : ReceivedFileDao {
    val files = mutableMapOf<String, ReceivedFileEntity>()

    override suspend fun insertOrUpdate(file: ReceivedFileEntity) {
        files[file.fileKey] = file
    }

    override suspend fun getByFileKey(fileKey: String): ReceivedFileEntity? = files[fileKey]

    override suspend fun getByOriginatingItemId(itemId: String): ReceivedFileEntity? =
        files.values.firstOrNull { it.originatingItemId == itemId }

    override suspend fun getActiveFiles(): List<ReceivedFileEntity> =
        files.values.filter { it.deletedAtMs == null }

    override suspend fun getActiveFileCount(): Int =
        files.values.count { it.deletedAtMs == null }

    override suspend fun getActiveFileTotalBytes(): Long =
        files.values.filter { it.deletedAtMs == null }.sumOf { it.length }

    override suspend fun markDeleted(fileKey: String, deletedAtMs: Long) {
        val cur = files[fileKey] ?: return
        files[fileKey] = cur.copy(deletedAtMs = deletedAtMs)
    }

    override suspend fun deletePermanently(fileKey: String) {
        files.remove(fileKey)
    }

    override suspend fun detachItemIdsOlderThan(cutoffMs: Long): Int = 0
    override suspend fun detachItemIdsExceedingMax(maxCount: Int): Int = 0
    override suspend fun detachAllItemIds(): Int = 0
}

class FakeTestReceiveJournalDao : ReceiveJournalDao {
    val entries = mutableMapOf<String, ReceiveJournalEntity>()

    override suspend fun insertOrUpdate(entry: ReceiveJournalEntity) {
        entries[entry.itemId] = entry
    }

    override suspend fun getByItemId(itemId: String): ReceiveJournalEntity? = entries[itemId]

    override suspend fun updatePhase(itemId: String, phase: String, updatedAtMs: Long) {
        val cur = entries[itemId] ?: return
        entries[itemId] = cur.copy(phase = phase, updatedAtMs = updatedAtMs)
    }

    override suspend fun updateVerifiedDigest(itemId: String, phase: String, digest: String, updatedAtMs: Long) {
        val cur = entries[itemId] ?: return
        entries[itemId] = cur.copy(phase = phase, verifiedDigest = digest, updatedAtMs = updatedAtMs)
    }

    override suspend fun delete(itemId: String) {
        entries.remove(itemId)
    }

    override suspend fun getPendingEntries(): List<ReceiveJournalEntity> =
        entries.values.filter { it.phase != "COMPLETED" }

    override suspend fun getEntriesBySessionId(sessionId: String): List<ReceiveJournalEntity> =
        entries.values.filter { it.sessionId == sessionId }

    override suspend fun deleteBySessionId(sessionId: String) {
        entries.values.removeIf { it.sessionId == sessionId }
    }
}

class FakeTestSessionDao : SessionDao {
    val sessions = mutableMapOf<String, SessionEntity>()

    override suspend fun insert(session: SessionEntity) {
        sessions[session.sessionId] = session
    }

    override suspend fun getBySessionId(sessionId: String): SessionEntity? = sessions[sessionId]
    override suspend fun getAll(): List<SessionEntity> = sessions.values.toList()
    override suspend fun getRecent(limit: Int): List<SessionEntity> = sessions.values.take(limit)
    override suspend fun updateOutcome(sessionId: String, outcome: String, endedAtMs: Long, failureCode: String?) {}
    override suspend fun delete(sessionId: String) { sessions.remove(sessionId) }
    override suspend fun deleteAll() { sessions.clear() }
    override suspend fun getSessionsPaged(role: String?, limit: Int, offset: Int): List<SessionEntity> = emptyList()
    override suspend fun getSessionCount(role: String?): Int = sessions.size
    override suspend fun getTerminalSessionCount(): Int = sessions.values.count { it.outcome != null }
    override suspend fun getActiveSessions(): List<SessionEntity> = sessions.values.filter { it.outcome == null }
    override suspend fun deleteTerminalOlderThan(cutoffMs: Long): Int = 0
    override suspend fun deleteTerminalExceedingMax(maxCount: Int): Int = 0
    override suspend fun deleteTerminalSessions(): Int = 0
    override suspend fun getSessionWithItems(sessionId: String): SessionWithItems? = null
    override suspend fun getSessionsWithItemsPaged(role: String?, limit: Int, offset: Int): List<SessionWithItems> = emptyList()
}

class AndroidPrivateFileManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var receivedDir: File
    private lateinit var fileCatalogDao: FakeTestReceivedFileDao
    private lateinit var receiveJournalDao: FakeTestReceiveJournalDao
    private lateinit var sessionDao: FakeTestSessionDao
    private lateinit var manager: AndroidPrivateFileManager

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        receivedDir = tempFolder.newFolder("received")
        fileCatalogDao = FakeTestReceivedFileDao()
        receiveJournalDao = FakeTestReceiveJournalDao()
        sessionDao = FakeTestSessionDao()

        manager = AndroidPrivateFileManager(
            receivedFilesDir = receivedDir,
            fileCatalogDao = fileCatalogDao,
            receiveJournalDao = receiveJournalDao,
            sessionDao = sessionDao,
            clock = { 100_000L },
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun `getStorageSummary returns accurate count and total size`() = runTest(testDispatcher) {
        // Create 2 files on disk
        val f1 = File(receivedDir, "file_1.dat").apply { writeBytes(ByteArray(1024)) }
        val f2 = File(receivedDir, "file_2.dat").apply { writeBytes(ByteArray(2048)) }

        fileCatalogDao.insertOrUpdate(
            ReceivedFileEntity("file_1.dat", "item_1", "doc1.txt", "text/plain", 1024, "hash1", 1000L)
        )
        fileCatalogDao.insertOrUpdate(
            ReceivedFileEntity("file_2.dat", "item_2", "photo.png", "image/png", 2048, "hash2", 2000L)
        )

        val summary = manager.getStorageSummary()
        assertEquals(2, summary.totalFiles)
        assertEquals(3072L, summary.totalBytes)
        assertEquals(0, summary.activeFiles)
    }

    @Test
    fun `active file in pending journal is flagged and cannot be deleted`() = runTest(testDispatcher) {
        val f1 = File(receivedDir, "active_file.dat").apply { writeBytes(ByteArray(512)) }
        fileCatalogDao.insertOrUpdate(
            ReceivedFileEntity("active_file.dat", "item_active", "transfer.bin", "application/octet-stream", 512, "hash", 1000L)
        )

        // Mark active in pending receive journal
        receiveJournalDao.insertOrUpdate(
            ReceiveJournalEntity(
                itemId = "item_active",
                sessionId = "session_1",
                stagingKey = "staging_key",
                finalKey = "active_file.dat",
                displayName = "transfer.bin",
                mimeType = "application/octet-stream",
                expectedBytes = 512,
                phase = "WRITING",
                updatedAtMs = 1500L
            )
        )

        // Verify summary flags 1 active file
        val summary = manager.getStorageSummary()
        assertEquals(1, summary.activeFiles)

        // Verify getReceivedFiles marks isActive = true
        val files = manager.getReceivedFiles()
        assertEquals(1, files.size)
        assertTrue(files[0].isActive)

        // Attempt deletion -> must fail!
        val deleteResult = manager.deleteReceivedFile("active_file.dat")
        assertTrue(deleteResult.isFailure)
        assertTrue(deleteResult.exceptionOrNull() is IllegalStateException)
        assertTrue(deleteResult.exceptionOrNull()?.message?.contains("active file") == true)

        // File remains on disk and in catalog
        assertTrue(f1.exists())
        assertNull(fileCatalogDao.getByFileKey("active_file.dat")?.deletedAtMs)
    }

    @Test
    fun `inactive file deletion removes from disk and updates catalog`() = runTest(testDispatcher) {
        val f1 = File(receivedDir, "completed_file.dat").apply { writeBytes(ByteArray(256)) }
        fileCatalogDao.insertOrUpdate(
            ReceivedFileEntity("completed_file.dat", "item_done", "done.txt", "text/plain", 256, "hash", 1000L)
        )

        val deleteResult = manager.deleteReceivedFile("completed_file.dat")
        assertTrue(deleteResult.isSuccess)

        // Physical file deleted from disk
        assertFalse(f1.exists())

        // Catalog marked deleted
        val catalogEntry = fileCatalogDao.getByFileKey("completed_file.dat")
        assertNotNull(catalogEntry)
        assertEquals(100_000L, catalogEntry?.deletedAtMs)

        // Storage summary now shows 0 active files
        val summary = manager.getStorageSummary()
        assertEquals(0, summary.totalFiles)
    }

    @Test
    fun `deleteAllReceivedFiles deletes only inactive files and preserves active files`() = runTest(testDispatcher) {
        val fInactive1 = File(receivedDir, "inactive_1.dat").apply { writeBytes(ByteArray(100)) }
        val fInactive2 = File(receivedDir, "inactive_2.dat").apply { writeBytes(ByteArray(200)) }
        val fActive = File(receivedDir, "active.dat").apply { writeBytes(ByteArray(300)) }

        fileCatalogDao.insertOrUpdate(ReceivedFileEntity("inactive_1.dat", "i1", "f1", "mime", 100, "h1", 1000L))
        fileCatalogDao.insertOrUpdate(ReceivedFileEntity("inactive_2.dat", "i2", "f2", "mime", 200, "h2", 2000L))
        fileCatalogDao.insertOrUpdate(ReceivedFileEntity("active.dat", "i3", "f3", "mime", 300, "h3", 3000L))

        // Set fActive as active in journal
        receiveJournalDao.insertOrUpdate(
            ReceiveJournalEntity("i3", "s1", "stg", "active.dat", "f3", "mime", 300, phase = "COMMITTING", updatedAtMs = 3500L)
        )

        val result = manager.deleteAllReceivedFiles()
        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull())

        // Inactive files deleted from disk
        assertFalse(fInactive1.exists())
        assertFalse(fInactive2.exists())

        // Active file preserved on disk
        assertTrue(fActive.exists())

        // Active file still active in catalog
        assertNull(fileCatalogDao.getByFileKey("active.dat")?.deletedAtMs)
        assertNotNull(fileCatalogDao.getByFileKey("inactive_1.dat")?.deletedAtMs)
        assertNotNull(fileCatalogDao.getByFileKey("inactive_2.dat")?.deletedAtMs)
    }
}
