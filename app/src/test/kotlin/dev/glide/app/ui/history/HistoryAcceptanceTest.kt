package dev.glide.app.ui.history

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.ports.HistoryStore
import dev.glide.core.ports.ItemRecord
import dev.glide.core.ports.SessionRecord
import dev.glide.core.ports.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryAcceptanceTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeHistoryStore : HistoryStore {
        val sessions = mutableListOf<SessionRecord>()
        val items = mutableListOf<ItemRecord>()
        var clearHistoryCallCount = 0

        override suspend fun recordSession(record: SessionRecord) {
            sessions.add(record)
        }

        override suspend fun recordItem(record: ItemRecord) {
            items.add(record)
        }

        override suspend fun updateSessionOutcome(sessionId: SessionId, outcome: String, endedAtMs: Long) {
            val idx = sessions.indexOfFirst { it.sessionId == sessionId }
            if (idx >= 0) {
                sessions[idx] = sessions[idx].copy(outcome = outcome, endedAtMs = endedAtMs)
            }
        }

        override suspend fun getSession(sessionId: SessionId): SessionRecord? {
            return sessions.firstOrNull { it.sessionId == sessionId }
        }

        override suspend fun getItemsForSession(sessionId: SessionId): List<ItemRecord> {
            return items.filter { it.sessionId == sessionId }
        }

        override suspend fun getSessionsPaged(
            role: DeviceRole?,
            limit: Int,
            offset: Int
        ): List<SessionSummary> {
            return sessions
                .filter { s -> role == null || s.role == role }
                .sortedByDescending { it.startedAtMs }
                .drop(offset)
                .take(limit)
                .map { s ->
                    val sItems = items.filter { it.sessionId == s.sessionId }
                    SessionSummary(
                        sessionId = s.sessionId,
                        batchId = s.batchId,
                        role = s.role,
                        peerLabel = s.peerLabel,
                        startedAtMs = s.startedAtMs,
                        endedAtMs = s.endedAtMs,
                        outcome = s.outcome,
                        itemCount = sItems.size,
                        totalBytes = sItems.sumOf { it.actualBytes ?: it.offeredBytes }
                    )
                }
        }

        override suspend fun getSessionCount(role: DeviceRole?): Int {
            return sessions.count { role == null || it.role == role }
        }

        override suspend fun clearHistory(): Int {
            val count = sessions.size
            sessions.clear()
            items.clear()
            clearHistoryCallCount++
            return count
        }
    }

    private lateinit var store: FakeHistoryStore
    private lateinit var viewModel: HistoryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        store = FakeHistoryStore()
        viewModel = HistoryViewModel(store)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun emptyState_rendersAcrossAllSentAndReceivedFilters() = runTest(testDispatcher) {
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertEquals(HistoryFilter.ALL, state.filter)
        assertTrue(state.sessions.isEmpty())

        // Switch to Sent filter
        viewModel.setFilter(HistoryFilter.SENT)
        advanceUntilIdle()
        state = viewModel.uiState.value
        assertEquals(HistoryFilter.SENT, state.filter)
        assertTrue(state.sessions.isEmpty())

        // Switch to Received filter
        viewModel.setFilter(HistoryFilter.RECEIVED)
        advanceUntilIdle()
        state = viewModel.uiState.value
        assertEquals(HistoryFilter.RECEIVED, state.filter)
        assertTrue(state.sessions.isEmpty())
    }

    @Test
    fun populatedHistory_filtersCorrectlyByDirectionAndRole() = runTest(testDispatcher) {
        val s1Id = SessionId(UUID.randomUUID().toString())
        val s2Id = SessionId(UUID.randomUUID().toString())

        store.recordSession(
            SessionRecord(
                sessionId = s1Id,
                batchId = BatchId(UUID.randomUUID().toString()),
                role = DeviceRole.Sender,
                peerLabel = "MacBook Pro M3",
                startedAtMs = 1000L,
                endedAtMs = 1050L,
                outcome = "Completed"
            )
        )
        store.recordItem(
            ItemRecord(
                itemId = ItemId(UUID.randomUUID().toString()),
                sessionId = s1Id,
                ordinal = 0,
                displayName = "system_architecture_diagram.png",
                mimeType = "image/png",
                offeredBytes = 4_000_000L,
                actualBytes = 4_000_000L,
                digest = Sha256Digest("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                outcome = "Verified"
            )
        )

        store.recordSession(
            SessionRecord(
                sessionId = s2Id,
                batchId = BatchId(UUID.randomUUID().toString()),
                role = DeviceRole.Receiver,
                peerLabel = "Pixel 10 Pro XL",
                startedAtMs = 2000L,
                endedAtMs = 2080L,
                outcome = "Completed"
            )
        )
        store.recordItem(
            ItemRecord(
                itemId = ItemId(UUID.randomUUID().toString()),
                sessionId = s2Id,
                ordinal = 0,
                displayName = "dataset_raw_archive.tar.gz",
                mimeType = "application/gzip",
                offeredBytes = 50_000_000L,
                actualBytes = 50_000_000L,
                digest = Sha256Digest("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
                outcome = "Verified"
            )
        )

        viewModel.loadHistory()
        advanceUntilIdle()

        val allState = viewModel.uiState.value
        assertEquals(2, allState.sessions.size)

        // Filter SENT only
        viewModel.setFilter(HistoryFilter.SENT)
        advanceUntilIdle()
        val sentState = viewModel.uiState.value
        assertEquals(1, sentState.sessions.size)
        assertEquals("MacBook Pro M3", sentState.sessions[0].peerLabel)
        assertEquals(DeviceRole.Sender, sentState.sessions[0].role)

        // Filter RECEIVED only
        viewModel.setFilter(HistoryFilter.RECEIVED)
        advanceUntilIdle()
        val receivedState = viewModel.uiState.value
        assertEquals(1, receivedState.sessions.size)
        assertEquals("Pixel 10 Pro XL", receivedState.sessions[0].peerLabel)
        assertEquals(DeviceRole.Receiver, receivedState.sessions[0].role)
    }

    @Test
    fun sessionDetail_formatsSessionMetadataAndHandlesMissingFile() = runTest(testDispatcher) {
        val sId = SessionId(UUID.randomUUID().toString())
        val missingItemId = ItemId(UUID.randomUUID().toString())

        val sessionItem = HistorySessionItem(
            sessionId = sId.value,
            role = DeviceRole.Receiver,
            peerLabel = "Galaxy Tab S10+",
            startedAtMs = 10_000L,
            endedAtMs = 15_000L,
            outcome = "Completed",
            itemCount = 1,
            totalBytes = 120_000_000L
        )

        val detail = HistorySessionDetail(
            session = sessionItem,
            items = listOf(
                HistoryItemDetail(
                    itemId = missingItemId.value,
                    ordinal = 0,
                    displayName = "deleted_after_export.mp4",
                    mimeType = "video/mp4",
                    offeredBytes = 120_000_000L,
                    actualBytes = 120_000_000L,
                    outcome = "Verified",
                    isFileAvailable = false
                )
            )
        )

        assertEquals("Galaxy Tab S10+", detail.session.peerLabel)
        assertEquals(DeviceRole.Receiver, detail.session.role)
        assertEquals(1, detail.items.size)
        val item = detail.items[0]
        assertFalse("Item file must be marked unavailable when not found on disk", item.isFileAvailable)
        assertEquals("deleted_after_export.mp4", item.displayName)
    }

    @Test
    fun longPeerAndItemNames_doNotCrashAndPreserveSafeModelValues() = runTest(testDispatcher) {
        val longPeer = "A".repeat(80)
        val longFilename = "VeryLongDocumentNameWithManyCharactersForTestingLayoutSafety_2026_Final_v12_Approved.presentation.pdf"
        val sId = SessionId(UUID.randomUUID().toString())

        store.recordSession(
            SessionRecord(
                sessionId = sId,
                batchId = BatchId(UUID.randomUUID().toString()),
                role = DeviceRole.Sender,
                peerLabel = longPeer,
                startedAtMs = 5000L,
                outcome = "Failed"
            )
        )
        store.recordItem(
            ItemRecord(
                itemId = ItemId(UUID.randomUUID().toString()),
                sessionId = sId,
                ordinal = 0,
                displayName = longFilename,
                mimeType = "application/pdf",
                offeredBytes = 8_500_000L,
                actualBytes = null,
                digest = null,
                outcome = "Failed"
            )
        )

        viewModel.loadHistory()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.sessions.size)
        assertEquals(longPeer, state.sessions[0].peerLabel)
    }

    @Test
    fun clearHistoryDialog_operatesOnAuditRecordsOnly() = runTest(testDispatcher) {
        val sId = SessionId(UUID.randomUUID().toString())
        store.recordSession(
            SessionRecord(
                sessionId = sId,
                batchId = BatchId(UUID.randomUUID().toString()),
                role = DeviceRole.Receiver,
                peerLabel = "Workstation Linux",
                startedAtMs = 1234L,
                outcome = "Completed"
            )
        )
        viewModel.loadHistory()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.sessions.size)

        // Request clear history dialog
        viewModel.requestClearHistory()
        assertTrue(viewModel.uiState.value.isClearHistoryDialogOpen)

        // Dismiss without clearing
        viewModel.dismissClearHistoryDialog()
        assertFalse(viewModel.uiState.value.isClearHistoryDialogOpen)
        assertEquals(0, store.clearHistoryCallCount)

        // Request and confirm clear history
        viewModel.requestClearHistory()
        viewModel.confirmClearHistory()
        advanceUntilIdle()

        assertEquals(1, store.clearHistoryCallCount)
        assertTrue(viewModel.uiState.value.sessions.isEmpty())
        assertFalse(viewModel.uiState.value.isClearHistoryDialogOpen)
    }
}
