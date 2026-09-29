package dev.glide.data.store

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.ports.ItemRecord
import dev.glide.core.ports.SessionRecord
import dev.glide.data.db.ItemDao
import dev.glide.data.db.ItemEntity
import dev.glide.data.db.ReceivedFileDao
import dev.glide.data.db.ReceivedFileEntity
import dev.glide.data.db.SessionDao
import dev.glide.data.db.SessionEntity
import dev.glide.data.db.SessionWithItems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class FakeSessionDao(private val itemDaoSupplier: () -> FakeItemDao) : SessionDao {
    val sessions = mutableMapOf<String, SessionEntity>()

    override suspend fun insert(session: SessionEntity) {
        sessions[session.sessionId] = session
    }

    override suspend fun getBySessionId(sessionId: String): SessionEntity? {
        return sessions[sessionId]
    }

    override suspend fun getAll(): List<SessionEntity> {
        return sessions.values.sortedByDescending { it.startedAtMs }
    }

    override suspend fun getRecent(limit: Int): List<SessionEntity> {
        return sessions.values.sortedByDescending { it.startedAtMs }.take(limit)
    }

    override suspend fun updateOutcome(
        sessionId: String,
        outcome: String,
        endedAtMs: Long,
        failureCode: String?
    ) {
        val cur = sessions[sessionId] ?: return
        sessions[sessionId] = cur.copy(
            outcome = outcome,
            endedAtMs = endedAtMs,
            failureCode = failureCode
        )
    }

    override suspend fun delete(sessionId: String) {
        sessions.remove(sessionId)
        itemDaoSupplier().items.values.removeIf { it.sessionId == sessionId }
    }

    override suspend fun deleteAll() {
        sessions.clear()
    }

    override suspend fun getSessionsPaged(role: String?, limit: Int, offset: Int): List<SessionEntity> {
        return sessions.values
            .filter { role == null || it.role == role }
            .sortedByDescending { it.startedAtMs }
            .drop(offset)
            .take(limit)
    }

    override suspend fun getSessionCount(role: String?): Int {
        return sessions.values.count { role == null || it.role == role }
    }

    override suspend fun getTerminalSessionCount(): Int {
        return sessions.values.count { it.outcome != null }
    }

    override suspend fun getActiveSessions(): List<SessionEntity> {
        return sessions.values.filter { it.outcome == null }
    }

    override suspend fun deleteTerminalOlderThan(cutoffMs: Long): Int {
        val toDelete = sessions.values.filter { it.outcome != null && it.startedAtMs < cutoffMs }
        toDelete.forEach { session ->
            sessions.remove(session.sessionId)
            itemDaoSupplier().items.values.removeIf { it.sessionId == session.sessionId }
        }
        return toDelete.size
    }

    override suspend fun deleteTerminalExceedingMax(maxCount: Int): Int {
        val terminal = sessions.values
            .filter { it.outcome != null }
            .sortedByDescending { it.startedAtMs }
        if (terminal.size <= maxCount) return 0
        val toDelete = terminal.drop(maxCount)
        toDelete.forEach { session ->
            sessions.remove(session.sessionId)
            itemDaoSupplier().items.values.removeIf { it.sessionId == session.sessionId }
        }
        return toDelete.size
    }

    override suspend fun deleteTerminalSessions(): Int {
        val toDelete = sessions.values.filter { it.outcome != null }
        toDelete.forEach { session ->
            sessions.remove(session.sessionId)
            itemDaoSupplier().items.values.removeIf { it.sessionId == session.sessionId }
        }
        return toDelete.size
    }

    override suspend fun getSessionWithItems(sessionId: String): SessionWithItems? {
        val session = sessions[sessionId] ?: return null
        val items = itemDaoSupplier().getItemsForSession(sessionId)
        return SessionWithItems(session, items)
    }

    override suspend fun getSessionsWithItemsPaged(
        role: String?,
        limit: Int,
        offset: Int
    ): List<SessionWithItems> {
        val pagedSessions = getSessionsPaged(role, limit, offset)
        return pagedSessions.map { session ->
            SessionWithItems(session, itemDaoSupplier().getItemsForSession(session.sessionId))
        }
    }

    override fun observeSessionsWithItems(role: String?, limit: Int): Flow<List<SessionWithItems>> = flow {
        emit(getSessionsWithItemsPaged(role, limit, 0))
    }
}

class FakeItemDao : ItemDao {
    val items = mutableMapOf<String, ItemEntity>()

    override suspend fun insert(item: ItemEntity) {
        items[item.itemId] = item
    }

    override suspend fun insertAll(items: List<ItemEntity>) {
        items.forEach { this.items[it.itemId] = it }
    }

    override suspend fun getByItemId(itemId: String): ItemEntity? {
        return items[itemId]
    }

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
            failureCode = failureCode
        )
    }

    override suspend fun delete(itemId: String) {
        items.remove(itemId)
    }
}

class RoomHistoryStoreTest {

    private lateinit var sessionDao: FakeSessionDao
    private lateinit var itemDao: FakeItemDao
    private lateinit var receivedFileDao: FakeReceivedFileDao
    private lateinit var historyStore: RoomHistoryStore

    private val sessionId = SessionId(UUID.randomUUID().toString())
    private val batchId = BatchId(UUID.randomUUID().toString())
    private val itemId = ItemId(UUID.randomUUID().toString())

    @Before
    fun setUp() {
        itemDao = FakeItemDao()
        sessionDao = FakeSessionDao { itemDao }
        receivedFileDao = FakeReceivedFileDao()
        historyStore = RoomHistoryStore(sessionDao, itemDao, receivedFileDao)
    }

    @Test
    fun recordSessionAndGet_persistsAndRetrievesSessionRecord() = runTest {
        val record = SessionRecord(
            sessionId = sessionId,
            batchId = batchId,
            role = DeviceRole.Receiver,
            peerLabel = "Sender Device",
            startedAtMs = 1000L
        )

        historyStore.recordSession(record)

        val retrieved = historyStore.getSession(sessionId)
        assertNotNull(retrieved)
        assertEquals(sessionId, retrieved?.sessionId)
        assertEquals(batchId, retrieved?.batchId)
        assertEquals(DeviceRole.Receiver, retrieved?.role)
        assertEquals("Sender Device", retrieved?.peerLabel)
        assertEquals(1000L, retrieved?.startedAtMs)
        assertNull(retrieved?.endedAtMs)
        assertNull(retrieved?.outcome)
    }

    @Test
    fun updateSessionOutcome_updatesOutcomeAndEndTime() = runTest {
        val record = SessionRecord(
            sessionId = sessionId,
            batchId = batchId,
            role = DeviceRole.Sender,
            peerLabel = "Receiver Device",
            startedAtMs = 2000L
        )
        historyStore.recordSession(record)

        historyStore.updateSessionOutcome(sessionId, "Completed", 3500L)

        val updated = historyStore.getSession(sessionId)
        assertNotNull(updated)
        assertEquals("Completed", updated?.outcome)
        assertEquals(3500L, updated?.endedAtMs)
    }

    @Test
    fun recordItemAndGet_persistsAndRetrievesItemRecords() = runTest {
        val sessionRecord = SessionRecord(
            sessionId = sessionId,
            batchId = batchId,
            role = DeviceRole.Receiver,
            peerLabel = "Sender Device",
            startedAtMs = 1000L
        )
        historyStore.recordSession(sessionRecord)

        val itemRecord = ItemRecord(
            itemId = itemId,
            sessionId = sessionId,
            ordinal = 0,
            displayName = "test_document.pdf",
            mimeType = "application/pdf",
            offeredBytes = 50_000L
        )
        historyStore.recordItem(itemRecord)

        val sessionItems = historyStore.getItemsForSession(sessionId)
        assertEquals(1, sessionItems.size)
        val retrievedItem = sessionItems[0]
        assertEquals(itemId, retrievedItem.itemId)
        assertEquals("test_document.pdf", retrievedItem.displayName)
        assertEquals("application/pdf", retrievedItem.mimeType)
        assertEquals(50_000L, retrievedItem.offeredBytes)
        assertNull(retrievedItem.actualBytes)
        assertNull(retrievedItem.digest)
    }

    @Test
    fun updateItemOutcome_updatesItemStatusAndDigest() = runTest {
        val itemRecord = ItemRecord(
            itemId = itemId,
            sessionId = sessionId,
            ordinal = 0,
            displayName = "photo.jpg",
            mimeType = "image/jpeg",
            offeredBytes = 100_000L
        )
        historyStore.recordItem(itemRecord)

        val digest = Sha256Digest("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        historyStore.updateItemOutcome(itemId, "Verified", 100_000L, digest)

        val sessionItems = historyStore.getItemsForSession(sessionId)
        assertEquals(1, sessionItems.size)
        val updated = sessionItems[0]
        assertEquals("Verified", updated.outcome)
        assertEquals(100_000L, updated.actualBytes)
        assertEquals(digest, updated.digest)
    }

    @Test
    fun simulatedProcessRestart_historySurvivesRelaunch() = runTest {
        // Record initial history
        historyStore.recordSession(
            SessionRecord(
                sessionId = sessionId,
                batchId = batchId,
                role = DeviceRole.Receiver,
                peerLabel = "Pixel 9",
                startedAtMs = 5000L
            )
        )
        historyStore.recordItem(
            ItemRecord(
                itemId = itemId,
                sessionId = sessionId,
                ordinal = 0,
                displayName = "video.mp4",
                mimeType = "video/mp4",
                offeredBytes = 2_000_000L
            )
        )
        historyStore.updateSessionOutcome(sessionId, "Completed", 8000L)

        // Simulate app recreation by constructing a new RoomHistoryStore over the same durable DAOs
        val reloadedHistoryStore = RoomHistoryStore(sessionDao, itemDao, receivedFileDao)

        val reloadedSession = reloadedHistoryStore.getSession(sessionId)
        assertNotNull(reloadedSession)
        assertEquals("Completed", reloadedSession?.outcome)
        assertEquals(8000L, reloadedSession?.endedAtMs)

        val reloadedItems = reloadedHistoryStore.getItemsForSession(sessionId)
        assertEquals(1, reloadedItems.size)
        assertEquals("video.mp4", reloadedItems[0].displayName)
    }

    private fun randomSessionId() = SessionId(UUID.randomUUID().toString())
    private fun randomBatchId() = BatchId(UUID.randomUUID().toString())
    private fun randomItemId() = ItemId(UUID.randomUUID().toString())

    @Test
    fun pagination_ordersByStartedAtDescendingAndFiltersByRole() = runTest {
        val senderIds = mutableListOf<SessionId>()
        for (i in 1..15) {
            val sId = randomSessionId()
            senderIds.add(sId)
            val bId = randomBatchId()
            historyStore.recordSession(
                SessionRecord(
                    sessionId = sId,
                    batchId = bId,
                    role = DeviceRole.Sender,
                    peerLabel = "Peer S$i",
                    startedAtMs = 1000L * i
                )
            )
            historyStore.recordItem(
                ItemRecord(
                    itemId = randomItemId(),
                    sessionId = sId,
                    ordinal = 0,
                    displayName = "sender_file_$i.txt",
                    mimeType = "text/plain",
                    offeredBytes = 1024L * i
                )
            )
        }

        val receiverIds = mutableListOf<SessionId>()
        for (i in 1..10) {
            val rId = randomSessionId()
            receiverIds.add(rId)
            val bId = randomBatchId()
            historyStore.recordSession(
                SessionRecord(
                    sessionId = rId,
                    batchId = bId,
                    role = DeviceRole.Receiver,
                    peerLabel = "Peer R$i",
                    startedAtMs = 1000L * (i + 20)
                )
            )
            historyStore.recordItem(
                ItemRecord(
                    itemId = randomItemId(),
                    sessionId = rId,
                    ordinal = 0,
                    displayName = "receiver_file_$i.png",
                    mimeType = "image/png",
                    offeredBytes = 2048L * i
                )
            )
        }

        assertEquals(25, historyStore.getSessionCount())
        assertEquals(15, historyStore.getSessionCount(DeviceRole.Sender))
        assertEquals(10, historyStore.getSessionCount(DeviceRole.Receiver))

        // Page 0 (limit 10, offset 0) - across all roles
        val page0 = historyStore.getSessionsPaged(role = null, limit = 10, offset = 0)
        assertEquals(10, page0.size)
        // Highest timestamp is receiver-10 (startedAtMs = 30000L)
        assertEquals(receiverIds.last(), page0[0].sessionId)
        assertEquals(DeviceRole.Receiver, page0[0].role)
        assertEquals(1, page0[0].itemCount)
        assertEquals(20480L, page0[0].totalBytes)

        // Page 1 (limit 10, offset 10)
        val page1 = historyStore.getSessionsPaged(role = null, limit = 10, offset = 10)
        assertEquals(10, page1.size)

        // Page 2 (limit 10, offset 20)
        val page2 = historyStore.getSessionsPaged(role = null, limit = 10, offset = 20)
        assertEquals(5, page2.size)

        // Filter: Sender only
        val senderPage = historyStore.getSessionsPaged(role = DeviceRole.Sender, limit = 10, offset = 0)
        assertEquals(10, senderPage.size)
        senderPage.forEach { assertEquals(DeviceRole.Sender, it.role) }
        assertEquals(senderIds.last(), senderPage[0].sessionId)

        // Filter: Receiver only
        val receiverPage = historyStore.getSessionsPaged(role = DeviceRole.Receiver, limit = 20, offset = 0)
        assertEquals(10, receiverPage.size)
        receiverPage.forEach { assertEquals(DeviceRole.Receiver, it.role) }
    }

    @Test
    fun retentionTrimming_olderThan90DaysPruned_inProgressNeverPruned() = runTest {
        val now = 10_000_000_000L
        val ninetyDaysMs = 90L * 24 * 60 * 60 * 1000L

        // Old terminal session (> 90 days ago) -> MUST be pruned
        val oldTerminalId = randomSessionId()
        historyStore.recordSession(
            SessionRecord(
                sessionId = oldTerminalId,
                batchId = randomBatchId(),
                role = DeviceRole.Sender,
                peerLabel = "Old Peer",
                startedAtMs = now - ninetyDaysMs - 100_000L
            )
        )
        historyStore.updateSessionOutcome(oldTerminalId, "Completed", now - ninetyDaysMs - 90_000L)

        // Old in-progress session (> 90 days ago, outcome == null) -> MUST NOT be pruned!
        val oldInProgressId = randomSessionId()
        historyStore.recordSession(
            SessionRecord(
                sessionId = oldInProgressId,
                batchId = randomBatchId(),
                role = DeviceRole.Receiver,
                peerLabel = "Active Peer",
                startedAtMs = now - ninetyDaysMs - 100_000L
            )
        )

        // Recent terminal session (< 90 days ago) -> MUST be kept
        val recentTerminalId = randomSessionId()
        historyStore.recordSession(
            SessionRecord(
                sessionId = recentTerminalId,
                batchId = randomBatchId(),
                role = DeviceRole.Receiver,
                peerLabel = "Recent Peer",
                startedAtMs = now - 100_000L
            )
        )
        historyStore.updateSessionOutcome(recentTerminalId, "Completed", now - 50_000L)

        val pruned = historyStore.trimRetention(maxSessions = 10_000, maxAgeMs = ninetyDaysMs, nowMs = now)
        assertEquals(1, pruned)

        assertNull("Old terminal session should be pruned", historyStore.getSession(oldTerminalId))
        assertNotNull("Old in-progress session should be preserved", historyStore.getSession(oldInProgressId))
        assertNotNull("Recent terminal session should be preserved", historyStore.getSession(recentTerminalId))
    }

    @Test
    fun retentionTrimming_exceeding10000Rows_prunesOldestTerminalRows() = runTest {
        val baseTime = 1_000_000L
        val createdSessionIds = ArrayList<SessionId>(10_050)
        // Insert 10,050 terminal sessions
        for (i in 1..10_050) {
            val sId = randomSessionId()
            createdSessionIds.add(sId)
            val bId = randomBatchId()
            historyStore.recordSession(
                SessionRecord(
                    sessionId = sId,
                    batchId = bId,
                    role = DeviceRole.Sender,
                    peerLabel = "Peer $i",
                    startedAtMs = baseTime + i
                )
            )
            historyStore.updateSessionOutcome(sId, "Completed", baseTime + i + 10)
        }

        assertEquals(10_050, historyStore.getSessionCount())

        // Trim with maxSessions = 10,000, large maxAgeMs so age trimming doesn't trigger
        val pruned = historyStore.trimRetention(
            maxSessions = 10_000,
            maxAgeMs = Long.MAX_VALUE / 2,
            nowMs = baseTime + 20_000
        )
        assertEquals(50, pruned)
        assertEquals(10_000, historyStore.getSessionCount())

        // The 50 oldest (0..49) must be pruned
        for (i in 0 until 50) {
            assertNull("Session $i should be pruned", historyStore.getSession(createdSessionIds[i]))
        }
        // The newest (50..10049) must remain
        assertNotNull(historyStore.getSession(createdSessionIds[50]))
        assertNotNull(historyStore.getSession(createdSessionIds[10049]))
    }

    @Test
    fun clearHistory_deletesSessionsAndItems_detachesReceivedFilesWithoutDeletingCatalogOrFiles() = runTest {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "glide_test_quarantine_${UUID.randomUUID()}")
        tempDir.mkdirs()
        try {
            val fileKey = "rec_file_key_123"
            val quarantineFile = File(tempDir, fileKey)
            quarantineFile.writeText("Verified payload content")

            val sId = randomSessionId()
            val bId = randomBatchId()
            val iId = randomItemId()

            historyStore.recordSession(
                SessionRecord(
                    sessionId = sId,
                    batchId = bId,
                    role = DeviceRole.Receiver,
                    peerLabel = "Sender Device",
                    startedAtMs = 5000L
                )
            )
            historyStore.recordItem(
                ItemRecord(
                    itemId = iId,
                    sessionId = sId,
                    ordinal = 0,
                    displayName = "document.pdf",
                    mimeType = "application/pdf",
                    offeredBytes = quarantineFile.length()
                )
            )
            historyStore.updateSessionOutcome(sId, "Completed", 6000L)

            // Register in received_files catalog linked to iId
            receivedFileDao.insertOrUpdate(
                ReceivedFileEntity(
                    fileKey = fileKey,
                    originatingItemId = iId.value,
                    displayName = "document.pdf",
                    mime = "application/pdf",
                    length = quarantineFile.length(),
                    digest = "abc123",
                    receivedAtMs = 6000L
                )
            )

            // Verify before clear
            assertNotNull(historyStore.getSession(sId))
            assertEquals(1, historyStore.getItemsForSession(sId).size)
            val catalogFileBefore = receivedFileDao.getByFileKey(fileKey)
            assertNotNull(catalogFileBefore)
            assertEquals(iId.value, catalogFileBefore?.originatingItemId)
            assertTrue(quarantineFile.exists())

            // Execute clear history
            val clearedCount = historyStore.clearHistory()
            assertEquals(1, clearedCount)

            // 1. Session and items are removed from history
            assertNull("Session should be deleted", historyStore.getSession(sId))
            assertTrue("Items should be empty", historyStore.getItemsForSession(sId).isEmpty())

            // 2. Catalog entry STILL exists, but originatingItemId is detached (null)
            val catalogFileAfter = receivedFileDao.getByFileKey(fileKey)
            assertNotNull("Received file catalog record MUST survive history clearing", catalogFileAfter)
            assertNull("Catalog link to deleted item should be detached", catalogFileAfter?.originatingItemId)
            assertEquals("document.pdf", catalogFileAfter?.displayName)

            // 3. Physical file on disk MUST NOT be deleted
            assertTrue("Quarantine file on disk MUST NOT be deleted by history clearing", quarantineFile.exists())
            assertEquals("Verified payload content", quarantineFile.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun getReceivedFileKey_retrievesKeyLinkedToOriginatingItem() = runTest {
        val iId = randomItemId()
        receivedFileDao.insertOrUpdate(
            ReceivedFileEntity(
                fileKey = "file-abc-999",
                originatingItemId = iId.value,
                displayName = "photo.jpg",
                mime = "image/jpeg",
                length = 5000L,
                digest = "hash999",
                receivedAtMs = 12000L
            )
        )

        val retrievedKey = historyStore.getReceivedFileKey(iId)
        assertEquals("file-abc-999", retrievedKey)

        val unknownKey = historyStore.getReceivedFileKey(randomItemId())
        assertNull(unknownKey)
    }

    @Test
    fun deleteSession_removesSessionAndItems_retainsReceivedFileAndDiskFile() = runTest {
        val tempDir = File.createTempFile("glide_history_del_test", "").apply {
            delete()
            mkdirs()
        }
        try {
            val sId = randomSessionId()
            val bId = randomBatchId()
            val iId = randomItemId()
            val fileKey = "test-del-key-${iId.value}"

            historyStore.recordSession(
                SessionRecord(
                    sessionId = sId,
                    batchId = bId,
                    role = DeviceRole.Receiver,
                    peerLabel = "Peer Sender",
                    startedAtMs = 5000L,
                    endedAtMs = 6000L,
                    outcome = "Completed"
                )
            )
            historyStore.recordItem(
                ItemRecord(
                    itemId = iId,
                    sessionId = sId,
                    ordinal = 0,
                    displayName = "safe_doc.pdf",
                    mimeType = "application/pdf",
                    offeredBytes = 2048L,
                    actualBytes = 2048L,
                    outcome = "Verified"
                )
            )

            // Disk file
            val diskFile = File(tempDir, "$fileKey.bin").apply {
                writeText("Valuable user data")
            }

            // Catalog record linked to item
            receivedFileDao.insertOrUpdate(
                ReceivedFileEntity(
                    fileKey = fileKey,
                    originatingItemId = iId.value,
                    displayName = "safe_doc.pdf",
                    mime = "application/pdf",
                    length = 2048L,
                    digest = "hash-safe",
                    receivedAtMs = 6000L
                )
            )

            // Verify exists prior to deletion
            assertNotNull(historyStore.getSession(sId))
            assertEquals(1, historyStore.getItemsForSession(sId).size)
            val initialCatalog = receivedFileDao.getByFileKey(fileKey)
            assertNotNull(initialCatalog)
            assertEquals(iId.value, initialCatalog?.originatingItemId)

            // Execute deleteSession
            val deleted = historyStore.deleteSession(sId)
            assertTrue("deleteSession should return true when session existed", deleted)

            // Verify session and items are gone
            assertNull("Session must be removed from history", historyStore.getSession(sId))
            assertTrue("Items must be removed from history", historyStore.getItemsForSession(sId).isEmpty())

            // Verify catalog file record STILL EXISTS and was detached
            val catalogFileAfter = receivedFileDao.getByFileKey(fileKey)
            assertNotNull("Received file record MUST survive session deletion", catalogFileAfter)
            assertNull("originatingItemId MUST be detached (null) after session deletion", catalogFileAfter?.originatingItemId)
            assertEquals("safe_doc.pdf", catalogFileAfter?.displayName)

            // Verify physical file on disk MUST NOT be deleted
            assertTrue("File on disk MUST NOT be deleted when deleting history", diskFile.exists())
            assertEquals("Valuable user data", diskFile.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
