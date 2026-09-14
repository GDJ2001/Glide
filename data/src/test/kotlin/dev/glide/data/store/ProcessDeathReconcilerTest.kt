package dev.glide.data.store

import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.data.db.ItemDao
import dev.glide.data.db.ItemEntity
import dev.glide.data.db.JournalPhases
import dev.glide.data.db.ReceiveJournalDao
import dev.glide.data.db.ReceiveJournalEntity
import dev.glide.data.db.ReceivedFileDao
import dev.glide.data.db.ReceivedFileEntity
import dev.glide.data.db.SessionDao
import dev.glide.data.db.SessionEntity
import dev.glide.data.db.SessionWithItems
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
import java.util.UUID

class ProcessDeathReconcilerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var journalDao: TestReceiveJournalDao
    private lateinit var fileCatalogDao: TestReceivedFileDao
    private lateinit var sessionDao: TestSessionDao
    private lateinit var itemDao: TestItemDao
    private lateinit var receiveStore: AndroidReceiveStore
    private lateinit var reconciler: ProcessDeathReconciler

    private val sessionId = SessionId(UUID.randomUUID().toString())
    private val itemId = ItemId(UUID.randomUUID().toString())

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("glide_storage")
        journalDao = TestReceiveJournalDao()
        fileCatalogDao = TestReceivedFileDao()
        itemDao = TestItemDao()
        sessionDao = TestSessionDao { itemDao }

        receiveStore = AndroidReceiveStore(
            rootDir = rootDir,
            journalDao = journalDao,
            fileCatalogDao = fileCatalogDao,
            freeSpaceProvider = { 10_000_000_000L }
        )

        reconciler = ProcessDeathReconciler(
            receiveStore = receiveStore,
            sessionDao = sessionDao,
            itemDao = itemDao,
            receivedFileDao = fileCatalogDao
        )
    }

    @Test
    fun killBeforeFlushOrRename_cleansPartialAndMarksInterrupted() = runTest {
        // Arrange: Transfer session and item in DB
        sessionDao.insert(
            SessionEntity(
                sessionId = sessionId.value,
                batchId = "batch-1",
                role = "RECEIVER",
                peerLabel = "Sender Device",
                startedAtMs = 1000L
            )
        )
        itemDao.insert(
            ItemEntity(
                itemId = itemId.value,
                sessionId = sessionId.value,
                ordinal = 0,
                displayName = "interrupted.txt",
                mime = "text/plain",
                offeredBytes = 500L
            )
        )

        // Stage file in receiving/
        val handle = receiveStore.prepareStaging(sessionId, itemId, 500L, "interrupted.txt")
        val out = receiveStore.openStagingOutput(handle)
        out.write("Incomplete stream...".toByteArray())
        out.close()

        val stagingFile = File(receiveStore.stagingDir, "${itemId.value}.part")
        assertTrue("Partial file must exist prior to crash", stagingFile.exists())

        // Act: App restarts, reconciliation executes without active session
        val summary = reconciler.reconcile()

        // Assert:
        assertEquals(1, summary.purgedPartials)
        assertEquals(1, summary.interruptedSessions)
        assertEquals(1, summary.interruptedItems)
        assertFalse("Unverified partial file must be deleted", stagingFile.exists())
        assertNull("Journal entry must be deleted", journalDao.getByItemId(itemId.value))

        val updatedItem = itemDao.getByItemId(itemId.value)
        assertNotNull(updatedItem)
        assertEquals("Interrupted", updatedItem!!.outcome)
        assertEquals("INTERRUPTED", updatedItem.failureCode)

        val updatedSession = sessionDao.getBySessionId(sessionId.value)
        assertNotNull(updatedSession)
        assertEquals("Interrupted", updatedSession!!.outcome)
        assertEquals("INTERRUPTED", updatedSession.failureCode)
    }

    @Test
    fun killAfterHashPreRename_completesCommitAndCatalogsFile() = runTest {
        // Arrange: Staged file with valid content and verified digest
        val payload = "Verified data that survived process death".toByteArray(Charsets.UTF_8)
        val digest = Sha256Digest.compute(payload)

        sessionDao.insert(
            SessionEntity(
                sessionId = sessionId.value,
                batchId = "batch-pre-rename",
                role = "RECEIVER",
                peerLabel = "Peer",
                startedAtMs = 1000L
            )
        )
        itemDao.insert(
            ItemEntity(
                itemId = itemId.value,
                sessionId = sessionId.value,
                ordinal = 0,
                displayName = "important_doc.pdf",
                mime = "application/pdf",
                offeredBytes = payload.size.toLong()
            )
        )

        val handle = receiveStore.prepareStaging(sessionId, itemId, payload.size.toLong(), "important_doc.pdf")
        val out = receiveStore.openStagingOutput(handle)
        out.write(payload)
        out.close()

        val entry = journalDao.getByItemId(itemId.value)!!
        journalDao.updateVerifiedDigest(
            itemId = itemId.value,
            phase = JournalPhases.COMMITTING,
            digest = digest.value,
            updatedAtMs = 1005L
        )

        val stagingFile = File(receiveStore.stagingDir, entry.stagingKey)
        val finalFile = File(receiveStore.receivedDir, entry.finalKey)
        assertTrue("Staging file must exist before rename", stagingFile.exists())
        assertFalse("Final file must NOT exist before rename", finalFile.exists())

        // Act: Process death recovery
        val summary = reconciler.reconcile()

        // Assert:
        assertEquals(1, summary.reconciledCommits)
        assertFalse("Staging file must be renamed away", stagingFile.exists())
        assertTrue("Final file must exist in received dir", finalFile.exists())
        assertEquals(payload.size.toLong(), finalFile.length())

        val catalogRecord = fileCatalogDao.getByFileKey(entry.finalKey)
        assertNotNull("File must be cataloged", catalogRecord)
        assertEquals("important_doc.pdf", catalogRecord!!.displayName)
        assertEquals(digest.value, catalogRecord.digest)

        val updatedItem = itemDao.getByItemId(itemId.value)
        assertNotNull(updatedItem)
        assertEquals("Completed", updatedItem!!.outcome)
        assertEquals(payload.size.toLong(), updatedItem.actualBytes)
        assertEquals(digest.value, updatedItem.digest)
    }

    @Test
    fun killAfterRenamePreCatalog_recoversAndCatalogsFile() = runTest {
        val payload = "Post-rename survivor".toByteArray(Charsets.UTF_8)
        val digest = Sha256Digest.compute(payload)

        sessionDao.insert(
            SessionEntity(
                sessionId = sessionId.value,
                batchId = "batch-post-rename",
                role = "RECEIVER",
                peerLabel = "Peer",
                startedAtMs = 1000L
            )
        )
        itemDao.insert(
            ItemEntity(
                itemId = itemId.value,
                sessionId = sessionId.value,
                ordinal = 0,
                displayName = "photo.jpg",
                mime = "image/jpeg",
                offeredBytes = payload.size.toLong()
            )
        )

        val handle = receiveStore.prepareStaging(sessionId, itemId, payload.size.toLong(), "photo.jpg")
        val out = receiveStore.openStagingOutput(handle)
        out.write(payload)
        out.close()

        val entry = journalDao.getByItemId(itemId.value)!!
        val stagingFile = File(receiveStore.stagingDir, entry.stagingKey)
        val finalFile = File(receiveStore.receivedDir, entry.finalKey)
        stagingFile.renameTo(finalFile)

        journalDao.updateVerifiedDigest(
            itemId = itemId.value,
            phase = JournalPhases.COMMITTING,
            digest = digest.value,
            updatedAtMs = 1005L
        )

        // Act: Reconcile discovers final file, verifies hash, and catalogs
        val summary = reconciler.reconcile()

        // Assert:
        assertEquals(1, summary.reconciledCommits)
        assertTrue(finalFile.exists())
        val catalogRecord = fileCatalogDao.getByFileKey(entry.finalKey)
        assertNotNull(catalogRecord)
        assertEquals(digest.value, catalogRecord!!.digest)
        assertNull(journalDao.getByItemId(itemId.value))

        val updatedItem = itemDao.getByItemId(itemId.value)
        assertNotNull(updatedItem)
        assertEquals("Completed", updatedItem!!.outcome)
    }

    @Test
    fun killAfterCatalogPreJournalDelete_cleansJournalWithoutDuplicatingFile() = runTest {
        val payload = "Already cataloged".toByteArray(Charsets.UTF_8)
        val digest = Sha256Digest.compute(payload)
        val finalKey = UUID.randomUUID().toString()

        val finalFile = File(receiveStore.receivedDir, finalKey)
        finalFile.writeBytes(payload)

        fileCatalogDao.insertOrUpdate(
            ReceivedFileEntity(
                fileKey = finalKey,
                originatingItemId = itemId.value,
                displayName = "notes.txt",
                mime = "text/plain",
                length = payload.size.toLong(),
                digest = digest.value,
                receivedAtMs = 1000L
            )
        )

        // Lingering journal entry
        journalDao.insertOrUpdate(
            ReceiveJournalEntity(
                itemId = itemId.value,
                sessionId = sessionId.value,
                stagingKey = "${itemId.value}.part",
                finalKey = finalKey,
                displayName = "notes.txt",
                mimeType = "text/plain",
                expectedBytes = payload.size.toLong(),
                verifiedDigest = digest.value,
                phase = JournalPhases.COMMITTING,
                updatedAtMs = 1000L
            )
        )

        sessionDao.insert(
            SessionEntity(
                sessionId = sessionId.value,
                batchId = "batch-already-cataloged",
                role = "RECEIVER",
                peerLabel = "Peer",
                startedAtMs = 1000L
            )
        )
        itemDao.insert(
            ItemEntity(
                itemId = itemId.value,
                sessionId = sessionId.value,
                ordinal = 0,
                displayName = "notes.txt",
                mime = "text/plain",
                offeredBytes = payload.size.toLong()
            )
        )

        // Act
        val summary = reconciler.reconcile()

        // Assert:
        assertEquals(1, summary.reconciledCommits)
        assertNull("Journal entry must be cleaned", journalDao.getByItemId(itemId.value))
        assertEquals(1, fileCatalogDao.files.size) // No duplicate!
        val item = itemDao.getByItemId(itemId.value)
        assertEquals("Completed", item!!.outcome)
    }

    @Test
    fun killAfterJournalDeletePreHistoryUpdate_reconcilesItemToCompleted() = runTest {
        val payload = "Durable file".toByteArray(Charsets.UTF_8)
        val digest = Sha256Digest.compute(payload)
        val finalKey = UUID.randomUUID().toString()

        val finalFile = File(receiveStore.receivedDir, finalKey)
        finalFile.writeBytes(payload)

        fileCatalogDao.insertOrUpdate(
            ReceivedFileEntity(
                fileKey = finalKey,
                originatingItemId = itemId.value,
                displayName = "archive.zip",
                mime = "application/zip",
                length = payload.size.toLong(),
                digest = digest.value,
                receivedAtMs = 1000L
            )
        )

        // Journal already deleted, but history item still had outcome = null
        sessionDao.insert(
            SessionEntity(
                sessionId = sessionId.value,
                batchId = "batch-unreconciled-history",
                role = "RECEIVER",
                peerLabel = "Peer",
                startedAtMs = 1000L
            )
        )
        itemDao.insert(
            ItemEntity(
                itemId = itemId.value,
                sessionId = sessionId.value,
                ordinal = 0,
                displayName = "archive.zip",
                mime = "application/zip",
                offeredBytes = payload.size.toLong(),
                outcome = null
            )
        )

        // Act
        val summary = reconciler.reconcile()

        // Assert:
        val item = itemDao.getByItemId(itemId.value)
        assertNotNull(item)
        assertEquals("Completed", item!!.outcome)
        assertEquals(payload.size.toLong(), item.actualBytes)
        assertEquals(digest.value, item.digest)
    }

    @Test
    fun dbMissingFile_marksCatalogDeletedAndDoesNotClaimMissingFile() = runTest {
        val fileKey = UUID.randomUUID().toString()
        // Record in catalog, but NO file exists on disk in receivedDir
        fileCatalogDao.insertOrUpdate(
            ReceivedFileEntity(
                fileKey = fileKey,
                originatingItemId = itemId.value,
                displayName = "ghost_file.mp4",
                mime = "video/mp4",
                length = 10_000_000L,
                digest = "0".repeat(64),
                receivedAtMs = 1000L
            )
        )

        sessionDao.insert(
            SessionEntity(
                sessionId = sessionId.value,
                batchId = "batch-ghost",
                role = "RECEIVER",
                peerLabel = "Peer",
                startedAtMs = 1000L
            )
        )
        itemDao.insert(
            ItemEntity(
                itemId = itemId.value,
                sessionId = sessionId.value,
                ordinal = 0,
                displayName = "ghost_file.mp4",
                mime = "video/mp4",
                offeredBytes = 10_000_000L
            )
        )

        // Act
        val summary = reconciler.reconcile()

        // Assert:
        assertEquals(1, summary.missingFilesMarkedDeleted)
        val catalogRecord = fileCatalogDao.getByFileKey(fileKey)
        assertNotNull(catalogRecord)
        assertNotNull("Durable record cannot claim missing file; marked deleted", catalogRecord!!.deletedAtMs)

        val item = itemDao.getByItemId(itemId.value)
        assertEquals("Interrupted", item!!.outcome)
    }

    @Test
    fun activeSession_isPreservedAndNotInterrupted() = runTest {
        val activeSessionId = UUID.randomUUID().toString()
        val activeItemId = UUID.randomUUID().toString()

        sessionDao.insert(
            SessionEntity(
                sessionId = activeSessionId,
                batchId = UUID.randomUUID().toString(),
                role = "RECEIVER",
                peerLabel = "Live Peer",
                startedAtMs = 5000L
            )
        )
        itemDao.insert(
            ItemEntity(
                itemId = activeItemId,
                sessionId = activeSessionId,
                ordinal = 0,
                displayName = "streaming_video.mp4",
                mime = "video/mp4",
                offeredBytes = 50_000_000L
            )
        )

        val handle = receiveStore.prepareStaging(
            SessionId(activeSessionId),
            ItemId(activeItemId),
            50_000_000L,
            "streaming_video.mp4"
        )
        val out = receiveStore.openStagingOutput(handle)
        out.write("Streaming bytes...".toByteArray())
        out.close()

        val stagingFile = File(receiveStore.stagingDir, "$activeItemId.part")
        assertTrue(stagingFile.exists())

        // Act: Reconcile called while activeSessionId is actively running
        val summary = reconciler.reconcile(activeSessionId = activeSessionId)

        // Assert:
        assertEquals(0, summary.interruptedSessions)
        assertEquals(0, summary.interruptedItems)
        assertEquals(0, summary.purgedPartials)

        assertTrue("Active session staging file must NOT be deleted", stagingFile.exists())
        assertNotNull("Active session journal must NOT be deleted", journalDao.getByItemId(activeItemId))

        val session = sessionDao.getBySessionId(activeSessionId)
        assertNull("Active session outcome must remain null (in-progress)", session!!.outcome)

        val item = itemDao.getByItemId(activeItemId)
        assertNull("Active item outcome must remain null (in-progress)", item!!.outcome)
    }

    @Test
    fun interruptedSession_preservesPreviouslyCompletedItemsInBatch() = runTest {
        val item1Id = UUID.randomUUID().toString()
        val item2Id = UUID.randomUUID().toString()

        val item1Payload = "First file completed".toByteArray()
        val item1Digest = Sha256Digest.compute(item1Payload)
        val item1FileKey = UUID.randomUUID().toString()

        // File 1 on disk and cataloged
        File(receiveStore.receivedDir, item1FileKey).writeBytes(item1Payload)
        fileCatalogDao.insertOrUpdate(
            ReceivedFileEntity(
                fileKey = item1FileKey,
                originatingItemId = item1Id,
                displayName = "item1.txt",
                mime = "text/plain",
                length = item1Payload.size.toLong(),
                digest = item1Digest.value,
                receivedAtMs = 1000L
            )
        )

        sessionDao.insert(
            SessionEntity(
                sessionId = sessionId.value,
                batchId = UUID.randomUUID().toString(),
                role = "RECEIVER",
                peerLabel = "Peer",
                startedAtMs = 1000L
            )
        )
        // Item 1: marked completed
        itemDao.insert(
            ItemEntity(
                itemId = item1Id,
                sessionId = sessionId.value,
                ordinal = 0,
                displayName = "item1.txt",
                mime = "text/plain",
                offeredBytes = item1Payload.size.toLong(),
                actualBytes = item1Payload.size.toLong(),
                digest = item1Digest.value,
                outcome = "Completed"
            )
        )
        // Item 2: in progress when process died
        itemDao.insert(
            ItemEntity(
                itemId = item2Id,
                sessionId = sessionId.value,
                ordinal = 1,
                displayName = "item2.txt",
                mime = "text/plain",
                offeredBytes = 2000L
            )
        )
        val item2Staging = File(receiveStore.stagingDir, "$item2Id.part")
        item2Staging.writeBytes("incomplete".toByteArray())
        journalDao.insertOrUpdate(
            ReceiveJournalEntity(
                itemId = item2Id,
                sessionId = sessionId.value,
                stagingKey = "$item2Id.part",
                finalKey = UUID.randomUUID().toString(),
                displayName = "item2.txt",
                mimeType = "text/plain",
                expectedBytes = 2000L,
                phase = JournalPhases.WRITING,
                updatedAtMs = 1050L
            )
        )

        // Act: Reconcile
        val summary = reconciler.reconcile()

        // Assert:
        assertEquals(1, summary.interruptedSessions)
        assertEquals(1, summary.interruptedItems) // Only item 2
        assertEquals(1, summary.purgedPartials)

        val item1 = itemDao.getByItemId(item1Id)
        assertEquals("Completed", item1!!.outcome) // Item 1 preserved!

        val item2 = itemDao.getByItemId(item2Id)
        assertEquals("Interrupted", item2!!.outcome)

        val session = sessionDao.getBySessionId(sessionId.value)
        assertEquals("Interrupted", session!!.outcome)
    }

    @Test
    fun repeatRecovery_isIdempotent() = runTest {
        // Run first recovery
        val firstSummary = reconciler.reconcile()

        // Run second recovery immediately
        val secondSummary = reconciler.reconcile()

        assertEquals(0, secondSummary.reconciledCommits)
        assertEquals(0, secondSummary.interruptedSessions)
        assertEquals(0, secondSummary.interruptedItems)
        assertEquals(0, secondSummary.purgedPartials)
        assertEquals(0, secondSummary.missingFilesMarkedDeleted)
        assertEquals(0, secondSummary.totalActions)
    }
}

// In-test test fakes for isolated DAO testing
class TestReceiveJournalDao : ReceiveJournalDao {
    val entries = mutableMapOf<String, ReceiveJournalEntity>()

    override suspend fun insertOrUpdate(entry: ReceiveJournalEntity) {
        entries[entry.itemId] = entry
    }

    override suspend fun getByItemId(itemId: String): ReceiveJournalEntity? = entries[itemId]

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

class TestReceivedFileDao : ReceivedFileDao {
    val files = mutableMapOf<String, ReceivedFileEntity>()

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

    override suspend fun detachItemIdsOlderThan(cutoffMs: Long): Int = 0
    override suspend fun detachItemIdsExceedingMax(maxCount: Int): Int = 0
    override suspend fun detachAllItemIds(): Int = 0
}

class TestSessionDao(private val itemDaoSupplier: () -> TestItemDao) : SessionDao {
    val sessions = mutableMapOf<String, SessionEntity>()

    override suspend fun insert(session: SessionEntity) {
        sessions[session.sessionId] = session
    }

    override suspend fun getBySessionId(sessionId: String): SessionEntity? = sessions[sessionId]

    override suspend fun getAll(): List<SessionEntity> = sessions.values.toList()

    override suspend fun getRecent(limit: Int): List<SessionEntity> = sessions.values.take(limit)

    override suspend fun updateOutcome(
        sessionId: String,
        outcome: String,
        endedAtMs: Long,
        failureCode: String?
    ) {
        val cur = sessions[sessionId] ?: return
        sessions[sessionId] = cur.copy(outcome = outcome, endedAtMs = endedAtMs, failureCode = failureCode)
    }

    override suspend fun delete(sessionId: String) {
        sessions.remove(sessionId)
    }

    override suspend fun deleteAll() {
        sessions.clear()
    }

    override suspend fun getSessionsPaged(role: String?, limit: Int, offset: Int): List<SessionEntity> =
        sessions.values.drop(offset).take(limit)

    override suspend fun getSessionCount(role: String?): Int = sessions.size

    override suspend fun getTerminalSessionCount(): Int = sessions.values.count { it.outcome != null }

    override suspend fun getActiveSessions(): List<SessionEntity> =
        sessions.values.filter { it.outcome == null }

    override suspend fun deleteTerminalOlderThan(cutoffMs: Long): Int = 0
    override suspend fun deleteTerminalExceedingMax(maxCount: Int): Int = 0
    override suspend fun deleteTerminalSessions(): Int = 0

    override suspend fun getSessionWithItems(sessionId: String): SessionWithItems? {
        val s = sessions[sessionId] ?: return null
        return SessionWithItems(s, itemDaoSupplier().getItemsForSession(sessionId))
    }

    override suspend fun getSessionsWithItemsPaged(
        role: String?,
        limit: Int,
        offset: Int
    ): List<SessionWithItems> = emptyList()
}

class TestItemDao : ItemDao {
    val items = mutableMapOf<String, ItemEntity>()

    override suspend fun insert(item: ItemEntity) {
        items[item.itemId] = item
    }

    override suspend fun insertAll(items: List<ItemEntity>) {
        items.forEach { insert(it) }
    }

    override suspend fun getByItemId(itemId: String): ItemEntity? = items[itemId]

    override suspend fun getItemsForSession(sessionId: String): List<ItemEntity> {
        return items.values.filter { it.sessionId == sessionId }.sortedBy { it.ordinal }
    }

    override suspend fun updateOutcome(
        itemId: String,
        outcome: String,
        actualBytes: Long?,
        digest: String?,
        failureCode: String?
    ) {
        val cur = items[itemId] ?: return
        items[itemId] = cur.copy(
            outcome = outcome,
            actualBytes = actualBytes ?: cur.actualBytes,
            digest = digest ?: cur.digest,
            failureCode = failureCode ?: cur.failureCode
        )
    }

    override suspend fun delete(itemId: String) {
        items.remove(itemId)
    }
}
