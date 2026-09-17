package dev.glide.data.maintenance

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.ports.HistoryStore
import dev.glide.core.ports.ItemRecord
import dev.glide.core.ports.SessionRecord
import dev.glide.core.ports.SessionSummary
import dev.glide.data.db.ReceiveJournalDao
import dev.glide.data.db.ReceiveJournalEntity
import dev.glide.data.db.ReceivedFileDao
import dev.glide.data.db.ReceivedFileEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class OrphanMaintenanceCleanerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var receivingDir: File
    private lateinit var receivedDir: File
    private lateinit var sourceCacheDir: File
    private lateinit var journalDao: FakeReceiveJournalDao
    private lateinit var fileCatalogDao: FakeReceivedFileDao
    private lateinit var historyStore: FakeHistoryStore
    private var currentTimeMs: Long = 1_000_000_000_000L

    @Before
    fun setUp() {
        val root = tempFolder.newFolder("glide_orphan_test")
        receivingDir = File(root, "receiving").apply { mkdirs() }
        receivedDir = File(root, "received").apply { mkdirs() }
        sourceCacheDir = File(root, "staging_sources").apply { mkdirs() }

        journalDao = FakeReceiveJournalDao()
        fileCatalogDao = FakeReceivedFileDao()
        historyStore = FakeHistoryStore()
    }

    private fun createCleaner(
        recDir: File = receivingDir,
        recvdDir: File = receivedDir,
        srcDirs: List<File> = listOf(sourceCacheDir),
        jDao: ReceiveJournalDao? = journalDao,
        fDao: ReceivedFileDao? = fileCatalogDao,
        hStore: HistoryStore? = historyStore,
        clock: () -> Long = { currentTimeMs }
    ): OrphanMaintenanceCleaner {
        return OrphanMaintenanceCleaner(
            receivingDir = recDir,
            receivedDir = recvdDir,
            stagingSourcesDirs = srcDirs,
            receiveJournalDao = jDao,
            receivedFileDao = fDao,
            historyStore = hStore,
            clock = clock
        )
    }

    @Test
    fun activeVsOrphan_preservesActiveFilesAndDeletesOlderThan24h() = runTest {
        val cutoff25h = currentTimeMs - (25L * 60L * 60L * 1000L)
        val cutoff1h = currentTimeMs - (1L * 60L * 60L * 1000L)

        // 1. Orphan partial file older than 24h -> MUST BE DELETED
        val oldOrphan = File(receivingDir, "orphan_old.part").apply {
            writeText("orphan old content")
            setLastModified(cutoff25h)
        }

        // 2. Recent orphan partial file (< 24h) -> MUST BE PRESERVED
        val recentOrphan = File(receivingDir, "orphan_recent.part").apply {
            writeText("recent orphan content")
            setLastModified(cutoff1h)
        }

        // 3. Active session partial file (older than 24h, but in active session) -> MUST BE PRESERVED
        val activeSessionId = UUID.randomUUID().toString()
        val activeItemId = UUID.randomUUID().toString()
        val activePart = File(receivingDir, "$activeItemId.part").apply {
            writeText("active session content")
            setLastModified(cutoff25h)
        }
        journalDao.insertOrUpdate(
            ReceiveJournalEntity(
                itemId = activeItemId,
                sessionId = activeSessionId,
                stagingKey = "$activeItemId.part",
                finalKey = "final-$activeItemId",
                displayName = "active.mp4",
                mimeType = "video/mp4",
                expectedBytes = 100L,
                phase = "WRITING",
                updatedAtMs = cutoff25h
            )
        )

        // 4. Pending journal file (older than 24h, but in pending journal) -> MUST BE PRESERVED
        val pendingItemId = UUID.randomUUID().toString()
        val pendingPart = File(receivingDir, "$pendingItemId.part").apply {
            writeText("pending journal content")
            setLastModified(cutoff25h)
        }
        journalDao.insertOrUpdate(
            ReceiveJournalEntity(
                itemId = pendingItemId,
                sessionId = UUID.randomUUID().toString(),
                stagingKey = "$pendingItemId.part",
                finalKey = "final-$pendingItemId",
                displayName = "pending.pdf",
                mimeType = "application/pdf",
                expectedBytes = 200L,
                phase = "COMMITTING",
                updatedAtMs = cutoff25h
            )
        )

        // 5. Explicitly passed active staging key -> MUST BE PRESERVED
        val explicitPart = File(receivingDir, "explicit_active.part").apply {
            writeText("explicit active content")
            setLastModified(cutoff25h)
        }

        // 6. Regular non-.part file -> MUST BE PRESERVED
        val regularFile = File(receivingDir, "not_a_part.txt").apply {
            writeText("regular text file")
            setLastModified(cutoff25h)
        }

        val cleaner = createCleaner()
        val result = cleaner.runMaintenance(
            activeSessionSupplier = { activeSessionId },
            activeStagingKeys = setOf("explicit_active.part")
        )

        assertEquals("Should purge exactly 1 old unreferenced orphan", 1, result.purgedStagingPartials)
        assertFalse("Old orphan file must be deleted", oldOrphan.exists())
        assertTrue("Recent orphan file must be preserved", recentOrphan.exists())
        assertTrue("Active session file must be preserved", activePart.exists())
        assertTrue("Pending journal file must be preserved", pendingPart.exists())
        assertTrue("Explicit active file must be preserved", explicitPart.exists())
        assertTrue("Non-.part file must be preserved", regularFile.exists())
    }

    @Test
    fun recentOrphans_under24h_areNotDeleted() = runTest {
        val cutoff1h = currentTimeMs - (1L * 60L * 60L * 1000L)
        val cutoff12h = currentTimeMs - (12L * 60L * 60L * 1000L)
        val cutoff23h = currentTimeMs - (23L * 60L * 60L * 1000L)

        val file1 = File(receivingDir, "part_1h.part").apply { writeText("1h"); setLastModified(cutoff1h) }
        val file2 = File(receivingDir, "part_12h.part").apply { writeText("12h"); setLastModified(cutoff12h) }
        val file3 = File(receivingDir, "part_23h.part").apply { writeText("23h"); setLastModified(cutoff23h) }

        val cleaner = createCleaner()
        val result = cleaner.runMaintenance()

        assertEquals(0, result.purgedStagingPartials)
        assertTrue(file1.exists())
        assertTrue(file2.exists())
        assertTrue(file3.exists())
    }

    @Test
    fun sourceCacheOrphans_olderThan24h_arePurged() = runTest {
        val cutoff25h = currentTimeMs - (25L * 60L * 60L * 1000L)
        val cutoff2h = currentTimeMs - (2L * 60L * 60L * 1000L)

        val oldPart = File(sourceCacheDir, "src_stage_1.part").apply {
            writeText("old source part")
            setLastModified(cutoff25h)
        }
        val oldStaged = File(sourceCacheDir, "src_stage_2.staged").apply {
            writeText("old source staged")
            setLastModified(cutoff25h)
        }
        val recentStaged = File(sourceCacheDir, "src_stage_3.staged").apply {
            writeText("recent source staged")
            setLastModified(cutoff2h)
        }

        val cleaner = createCleaner()
        val result = cleaner.runMaintenance()

        assertEquals("Must purge 2 old staged source files", 2, result.purgedStagedSources)
        assertFalse("Old source part must be deleted", oldPart.exists())
        assertFalse("Old source staged must be deleted", oldStaged.exists())
        assertTrue("Recent source staged must be preserved", recentStaged.exists())
    }

    @Test
    fun databaseMissingFiles_markedDeletedInCatalog() = runTest {
        // Disk file that exists
        val existingKey = "existing_file_key"
        File(receivedDir, existingKey).writeText("Existing content")

        fileCatalogDao.insertOrUpdate(
            ReceivedFileEntity(
                fileKey = existingKey,
                originatingItemId = UUID.randomUUID().toString(),
                displayName = "existing.pdf",
                mime = "application/pdf",
                length = 16L,
                digest = "digest1",
                receivedAtMs = currentTimeMs - 10000L
            )
        )

        // Missing file on disk
        val missingKey = "missing_file_key"
        fileCatalogDao.insertOrUpdate(
            ReceivedFileEntity(
                fileKey = missingKey,
                originatingItemId = UUID.randomUUID().toString(),
                displayName = "ghost.pdf",
                mime = "application/pdf",
                length = 1000L,
                digest = "digest2",
                receivedAtMs = currentTimeMs - 20000L
            )
        )

        val cleaner = createCleaner()
        val result = cleaner.runMaintenance()

        assertEquals("Must mark 1 missing file deleted", 1, result.missingCatalogFilesMarkedDeleted)
        val existingRecord = fileCatalogDao.getByFileKey(existingKey)
        assertNotNull(existingRecord)
        assertEquals(null, existingRecord?.deletedAtMs)

        val missingRecord = fileCatalogDao.getByFileKey(missingKey)
        assertNotNull(missingRecord)
        assertEquals(currentTimeMs, missingRecord?.deletedAtMs)
    }

    @Test
    fun storageUnavailable_handlesGracefullyWithoutCrashing() = runTest {
        val nonExistentReceiving = File(tempFolder.root, "does_not_exist_receiving")
        val nonExistentReceived = File(tempFolder.root, "does_not_exist_received")

        // Should not crash even if directories do not exist
        val cleaner = createCleaner(
            recDir = nonExistentReceiving,
            recvdDir = nonExistentReceived
        )

        val result = cleaner.runMaintenance()
        assertEquals(0, result.purgedStagingPartials)
        assertEquals(0, result.purgedStagedSources)
        // Crucial: when storage is unmounted/inaccessible, missing files must NOT be marked deleted!
        assertEquals(0, result.missingCatalogFilesMarkedDeleted)
    }

    @Test
    fun cancelledJob_abortsCleanly() = runTest {
        val cutoff25h = currentTimeMs - (25L * 60L * 60L * 1000L)
        val orphan = File(receivingDir, "orphan.part").apply {
            writeText("orphan content")
            setLastModified(cutoff25h)
        }

        val cleaner = createCleaner()
        val result = cleaner.runMaintenance(
            isCancelled = { true }
        )

        assertEquals("Zero files should be purged when cancelled", 0, result.purgedStagingPartials)
        assertTrue("Orphan file must remain intact when cancelled", orphan.exists())
    }

    @Test
    fun historyRetention_trimmedPreservingCatalog() = runTest {
        historyStore.stubPrunedCount = 5

        val cleaner = createCleaner()
        val result = cleaner.runMaintenance()

        assertEquals(5, result.trimmedHistorySessions)
        assertEquals(OrphanMaintenanceCleaner.HISTORY_MAX_SESSIONS, historyStore.lastMaxSessions)
        assertEquals(OrphanMaintenanceCleaner.HISTORY_MAX_AGE_MS, historyStore.lastMaxAgeMs)
        assertEquals(currentTimeMs, historyStore.lastNowMs)
    }

    @Test
    fun repeatedWorker_isIdempotent() = runTest {
        val cutoff25h = currentTimeMs - (25L * 60L * 60L * 1000L)
        File(receivingDir, "old.part").apply { writeText("old"); setLastModified(cutoff25h) }
        File(sourceCacheDir, "src.staged").apply { writeText("src"); setLastModified(cutoff25h) }

        val missingKey = "missing_key"
        fileCatalogDao.insertOrUpdate(
            ReceivedFileEntity(
                fileKey = missingKey,
                originatingItemId = UUID.randomUUID().toString(),
                displayName = "ghost.png",
                mime = "image/png",
                length = 10L,
                digest = "d",
                receivedAtMs = cutoff25h
            )
        )

        val cleaner = createCleaner()

        // Pass 1: cleans up everything
        val result1 = cleaner.runMaintenance()
        assertEquals(1, result1.purgedStagingPartials)
        assertEquals(1, result1.purgedStagedSources)
        assertEquals(1, result1.missingCatalogFilesMarkedDeleted)

        // Pass 2: idempotent, nothing left to clean
        val result2 = cleaner.runMaintenance()
        assertEquals(0, result2.purgedStagingPartials)
        assertEquals(0, result2.purgedStagedSources)
        assertEquals(0, result2.missingCatalogFilesMarkedDeleted)
        assertEquals(0, result2.totalCleaned)
    }
}

// ============================================================================
// Test Fakes
// ============================================================================

class FakeReceiveJournalDao : ReceiveJournalDao {
    private val entries = mutableMapOf<String, ReceiveJournalEntity>()

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

    override suspend fun getPendingEntries(): List<ReceiveJournalEntity> {
        return entries.values.filter { it.phase != "COMPLETED" }
    }

    override suspend fun getEntriesBySessionId(sessionId: String): List<ReceiveJournalEntity> {
        return entries.values.filter { it.sessionId == sessionId }
    }

    override suspend fun deleteBySessionId(sessionId: String) {
        val toRemove = entries.values.filter { it.sessionId == sessionId }.map { it.itemId }
        toRemove.forEach { entries.remove(it) }
    }
}

class FakeReceivedFileDao : ReceivedFileDao {
    private val files = mutableMapOf<String, ReceivedFileEntity>()

    override suspend fun insertOrUpdate(file: ReceivedFileEntity) {
        files[file.fileKey] = file
    }

    override suspend fun getByFileKey(fileKey: String): ReceivedFileEntity? = files[fileKey]

    override suspend fun getByOriginatingItemId(itemId: String): ReceivedFileEntity? {
        return files.values.firstOrNull { it.originatingItemId == itemId }
    }

    override suspend fun getActiveFiles(): List<ReceivedFileEntity> {
        return files.values.filter { it.deletedAtMs == null }
    }

    override suspend fun getActiveFileCount(): Int = files.values.count { it.deletedAtMs == null }

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

    override suspend fun detachItemIdsForSession(sessionId: String): Int = 0
}

class FakeHistoryStore : HistoryStore {
    var stubPrunedCount: Int = 0
    var lastMaxSessions: Int? = null
    var lastMaxAgeMs: Long? = null
    var lastNowMs: Long? = null

    override suspend fun recordSession(record: SessionRecord) {}
    override suspend fun recordItem(record: ItemRecord) {}
    override suspend fun updateSessionOutcome(sessionId: SessionId, outcome: String, endedAtMs: Long) {}
    override suspend fun getSession(sessionId: SessionId): SessionRecord? = null
    override suspend fun updateItemOutcome(itemId: ItemId, outcome: String, actualBytes: Long?, digest: Sha256Digest?) {}
    override suspend fun getItemsForSession(sessionId: SessionId): List<ItemRecord> = emptyList()
    override suspend fun getSessionsPaged(role: DeviceRole?, limit: Int, offset: Int): List<SessionSummary> = emptyList()
    override suspend fun getSessionCount(role: DeviceRole?): Int = 0

    override suspend fun trimRetention(maxSessions: Int, maxAgeMs: Long, nowMs: Long): Int {
        lastMaxSessions = maxSessions
        lastMaxAgeMs = maxAgeMs
        lastNowMs = nowMs
        return stubPrunedCount
    }

    override suspend fun clearHistory(): Int = 0
}
