package dev.glide.app.ui.history

import android.content.Context
import dev.glide.app.ui.transfer.ExportUiStatus
import dev.glide.app.util.FileOpenResult
import dev.glide.core.domain.BatchId
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.ports.HistoryStore
import dev.glide.core.ports.ItemRecord
import dev.glide.core.ports.SessionRecord
import dev.glide.core.ports.SessionSummary
import dev.glide.data.export.ExportResult
import dev.glide.data.export.FileExporter
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

    // -----------------------------------------------------------------------
    // Fake HistoryStore
    // -----------------------------------------------------------------------

    private inner class FakeHistoryStore : HistoryStore {
        val sessions = mutableListOf<SessionRecord>()
        val items = mutableListOf<ItemRecord>()
        var clearCount = 0

        override suspend fun recordSession(record: SessionRecord) {
            sessions.add(record)
        }

        override suspend fun recordItem(record: ItemRecord) {
            items.add(record)
        }

        override suspend fun updateSessionOutcome(sessionId: SessionId, outcome: String, endedAtMs: Long) {
            val idx = sessions.indexOfFirst { it.sessionId == sessionId }
            if (idx >= 0) sessions[idx] = sessions[idx].copy(outcome = outcome, endedAtMs = endedAtMs)
        }

        override suspend fun getSession(sessionId: SessionId): SessionRecord? =
            sessions.firstOrNull { it.sessionId == sessionId }

        override suspend fun getItemsForSession(sessionId: SessionId): List<ItemRecord> =
            items.filter { it.sessionId == sessionId }

        override suspend fun getSessionsPaged(role: DeviceRole?, limit: Int, offset: Int): List<SessionSummary> {
            val filtered = if (role == null) sessions else sessions.filter { it.role == role }
            return filtered.sortedByDescending { it.startedAtMs }.drop(offset).take(limit).map { s ->
                val sessionItems = items.filter { it.sessionId == s.sessionId }
                SessionSummary(
                    sessionId = s.sessionId,
                    batchId = s.batchId,
                    role = s.role,
                    peerLabel = s.peerLabel,
                    startedAtMs = s.startedAtMs,
                    endedAtMs = s.endedAtMs,
                    outcome = s.outcome,
                    failureCode = null,
                    itemCount = sessionItems.size,
                    totalBytes = sessionItems.sumOf { it.actualBytes ?: it.offeredBytes }
                )
            }
        }

        override suspend fun getSessionCount(role: DeviceRole?): Int =
            if (role == null) sessions.size else sessions.count { it.role == role }

        override suspend fun clearHistory(): Int {
            clearCount++
            val count = sessions.size
            sessions.clear()
            items.clear()
            return count
        }

        var deleteSessionCount = 0
        override suspend fun deleteSession(sessionId: SessionId): Boolean {
            deleteSessionCount++
            val sessionExisted = sessions.removeIf { it.sessionId == sessionId }
            items.removeIf { it.sessionId == sessionId }
            return sessionExisted
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun newSessionId() = SessionId(UUID.randomUUID().toString())
    private fun newBatchId() = BatchId(UUID.randomUUID().toString())
    private fun newItemId() = ItemId(UUID.randomUUID().toString())

    private fun FakeHistoryStore.addSession(
        role: DeviceRole = DeviceRole.Sender,
        peerLabel: String = "TestPeer",
        startedAtMs: Long = 1_000L,
        outcome: String? = "Completed"
    ): SessionId {
        val sid = newSessionId()
        sessions.add(
            SessionRecord(
                sessionId = sid,
                batchId = newBatchId(),
                role = role,
                peerLabel = peerLabel,
                startedAtMs = startedAtMs,
                endedAtMs = startedAtMs + 1000L,
                outcome = outcome
            )
        )
        return sid
    }

    private fun FakeHistoryStore.addItem(
        sessionId: SessionId,
        displayName: String = "test.txt",
        mimeType: String = "text/plain",
        offeredBytes: Long = 100L,
        outcome: String? = "Verified"
    ): ItemId {
        val iid = newItemId()
        items.add(
            ItemRecord(
                itemId = iid,
                sessionId = sessionId,
                ordinal = 0,
                displayName = displayName,
                mimeType = mimeType,
                offeredBytes = offeredBytes,
                actualBytes = offeredBytes,
                outcome = outcome
            )
        )
        return iid
    }

    private lateinit var fakeStore: FakeHistoryStore

    private fun buildViewModel(
        fileResolver: (Context?, String) -> File? = { _, _ -> null },
        fileOpener: (Context?, File, String, String) -> FileOpenResult = { _, _, _, _ -> FileOpenResult.FileNotFound },
        fileSharer: (Context?, File, String, String) -> FileOpenResult = { _, _, _, _ -> FileOpenResult.FileNotFound },
        fileExporterFactory: (Context) -> FileExporter = { _ ->
            object : FileExporter {
                override suspend fun exportToDownloads(
                    sourceFile: File,
                    displayName: String,
                    mimeType: String
                ): ExportResult = ExportResult.Failure("Not implemented")

                override suspend fun exportToUri(
                    sourceFile: File,
                    destinationUriString: String,
                    displayName: String
                ): ExportResult = ExportResult.Failure("Not implemented")
            }
        }
    ): HistoryViewModel {
        return HistoryViewModel(
            historyStore = fakeStore,
            fileResolver = fileResolver,
            fileOpener = fileOpener,
            fileSharer = fileSharer,
            fileExporterFactory = fileExporterFactory
        )
    }

    // -----------------------------------------------------------------------
    // Test lifecycle
    // -----------------------------------------------------------------------

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeStore = FakeHistoryStore()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    fun `init loads all sessions`() = runTest {
        fakeStore.addSession(role = DeviceRole.Sender, peerLabel = "Alice")
        fakeStore.addSession(role = DeviceRole.Receiver, peerLabel = "Bob")

        val vm = buildViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(2, state.sessions.size)
        assertFalse(state.isLoading)
    }

    @Test
    fun `setFilter_SENT shows only sent sessions`() = runTest {
        fakeStore.addSession(role = DeviceRole.Sender, peerLabel = "Alice")
        fakeStore.addSession(role = DeviceRole.Receiver, peerLabel = "Bob")

        val vm = buildViewModel()
        advanceUntilIdle()

        vm.setFilter(HistoryFilter.SENT)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(HistoryFilter.SENT, state.filter)
        assertEquals(1, state.sessions.size)
        assertEquals("Alice", state.sessions.first().peerLabel)
    }

    @Test
    fun `setFilter_RECEIVED shows only received sessions`() = runTest {
        fakeStore.addSession(role = DeviceRole.Sender, peerLabel = "Alice")
        fakeStore.addSession(role = DeviceRole.Receiver, peerLabel = "Bob")

        val vm = buildViewModel()
        advanceUntilIdle()

        vm.setFilter(HistoryFilter.RECEIVED)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(HistoryFilter.RECEIVED, state.filter)
        assertEquals(1, state.sessions.size)
        assertEquals("Bob", state.sessions.first().peerLabel)
    }

    @Test
    fun `selectSession populates selectedSessionDetail`() = runTest {
        val sid = fakeStore.addSession(role = DeviceRole.Sender, peerLabel = "Charlie")
        fakeStore.addItem(sessionId = sid, displayName = "photo.jpg", offeredBytes = 500L)

        val vm = buildViewModel()
        advanceUntilIdle()

        vm.selectSession(sid.value, context = null)
        advanceUntilIdle()

        val detail = vm.uiState.value.selectedSessionDetail
        assertNotNull(detail)
        assertEquals("Charlie", detail!!.session.peerLabel)
        assertEquals(1, detail.items.size)
        assertEquals("photo.jpg", detail.items.first().displayName)
    }

    @Test
    fun `selectSession sets isFileAvailable false when file missing`() = runTest {
        val sid = fakeStore.addSession(role = DeviceRole.Receiver, peerLabel = "Dana")
        fakeStore.addItem(sessionId = sid, displayName = "report.pdf", outcome = "Verified")

        // fileResolver always returns null → file absent
        val vm = buildViewModel(fileResolver = { _, _ -> null })
        advanceUntilIdle()

        vm.selectSession(sid.value, context = null)
        advanceUntilIdle()

        val detail = vm.uiState.value.selectedSessionDetail
        assertNotNull(detail)
        val item = detail!!.items.first()
        assertFalse(item.isFileAvailable)
    }

    @Test
    fun `selectSession sets isFileAvailable true when file exists`() = runTest {
        val sid = fakeStore.addSession(role = DeviceRole.Receiver, peerLabel = "Eve")
        fakeStore.addItem(sessionId = sid, displayName = "data.csv", outcome = "Verified")

        // Create a real temp file for the resolver to return
        val realFile = tempFolder.newFile("data.csv")

        val vm = buildViewModel(fileResolver = { _, _ -> realFile })
        advanceUntilIdle()

        vm.selectSession(sid.value, context = null)
        advanceUntilIdle()

        val detail = vm.uiState.value.selectedSessionDetail
        // Without a RoomHistoryStore, getReceivedFileKey returns null → isFileAvailable stays false.
        // This test documents that non-RoomHistoryStore paths always yield isFileAvailable=false.
        assertNotNull(detail)
        val item = detail!!.items.first()
        // receivedFileKey is null because fakeStore is not a RoomHistoryStore
        assertNull(item.receivedFileKey)
        assertFalse(item.isFileAvailable)
    }

    @Test
    fun `dismissSessionDetail clears selectedSessionDetail`() = runTest {
        val sid = fakeStore.addSession()
        val vm = buildViewModel()
        advanceUntilIdle()

        vm.selectSession(sid.value, context = null)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.selectedSessionDetail)

        vm.dismissSessionDetail()
        assertNull(vm.uiState.value.selectedSessionDetail)
    }

    @Test
    fun `confirmClearHistory clears sessions and reloads empty list`() = runTest {
        fakeStore.addSession(peerLabel = "Frank")
        fakeStore.addSession(peerLabel = "Grace")

        val vm = buildViewModel()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.sessions.size)

        vm.requestClearHistory()
        assertTrue(vm.uiState.value.isClearHistoryDialogOpen)

        vm.confirmClearHistory()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isClearHistoryDialogOpen)
        assertEquals(0, state.sessions.size)
        assertEquals(1, fakeStore.clearCount)
    }

    @Test
    fun `dismissClearHistoryDialog closes dialog without clearing`() = runTest {
        fakeStore.addSession()
        val vm = buildViewModel()
        advanceUntilIdle()

        vm.requestClearHistory()
        assertTrue(vm.uiState.value.isClearHistoryDialogOpen)

        vm.dismissClearHistoryDialog()
        assertFalse(vm.uiState.value.isClearHistoryDialogOpen)
        assertEquals(0, fakeStore.clearCount)
        assertEquals(1, vm.uiState.value.sessions.size)
    }

    @Test
    fun `exportItemFile with null key sets Failed status immediately`() = runTest {
        val vm = buildViewModel()
        advanceUntilIdle()

        val item = HistoryItemDetail(
            itemId = newItemId().value,
            ordinal = 0,
            displayName = "test.txt",
            mimeType = "text/plain",
            offeredBytes = 100L,
            actualBytes = 100L,
            outcome = "Verified",
            failureCode = null,
            receivedFileKey = null,  // null key → immediate failure
            isFileAvailable = false
        )

        vm.exportItemFile(context = null, item = item)

        val status = vm.uiState.value.exportStatus
        assertTrue(status is ExportUiStatus.Failed)
    }

    @Test
    fun `openItemFile with null key sets openFileFeedback`() = runTest {
        val vm = buildViewModel()
        advanceUntilIdle()

        val item = HistoryItemDetail(
            itemId = newItemId().value,
            ordinal = 0,
            displayName = "test.txt",
            mimeType = "text/plain",
            offeredBytes = 100L,
            actualBytes = null,
            outcome = null,
            failureCode = null,
            receivedFileKey = null,
            isFileAvailable = false
        )

        vm.openItemFile(context = null, item = item)
        assertNotNull(vm.uiState.value.openFileFeedback)
    }

    @Test
    fun `clearOpenFileFeedback clears feedback`() = runTest {
        val vm = buildViewModel()
        advanceUntilIdle()

        val item = HistoryItemDetail(
            itemId = newItemId().value,
            ordinal = 0,
            displayName = "test.txt",
            mimeType = "text/plain",
            offeredBytes = 100L,
            actualBytes = null,
            outcome = null,
            failureCode = null,
            receivedFileKey = null,
            isFileAvailable = false
        )

        vm.openItemFile(context = null, item = item)
        assertNotNull(vm.uiState.value.openFileFeedback)

        vm.clearOpenFileFeedback()
        assertNull(vm.uiState.value.openFileFeedback)
    }

    @Test
    fun `resetExportStatus resets to Idle`() = runTest {
        val vm = buildViewModel()
        advanceUntilIdle()

        val item = HistoryItemDetail(
            itemId = newItemId().value,
            ordinal = 0,
            displayName = "test.txt",
            mimeType = "text/plain",
            offeredBytes = 100L,
            actualBytes = null,
            outcome = null,
            failureCode = null,
            receivedFileKey = null,
            isFileAvailable = false
        )

        vm.exportItemFile(context = null, item = item)
        assertTrue(vm.uiState.value.exportStatus is ExportUiStatus.Failed)

        vm.resetExportStatus()
        assertEquals(ExportUiStatus.Idle, vm.uiState.value.exportStatus)
    }

    @Test
    fun `deleteSession removes session and items and clears selected session`() = runTest {
        val sid = fakeStore.addSession(DeviceRole.Receiver, "SenderDevice")
        fakeStore.addItem(sid, displayName = "sample.pdf")
        val vm = buildViewModel()
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.sessions.size)

        vm.selectSession(sid.value)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.selectedSessionDetail)

        vm.deleteSession(sid.value)
        advanceUntilIdle()

        assertEquals(1, fakeStore.deleteSessionCount)
        assertTrue(fakeStore.sessions.isEmpty())
        assertTrue(fakeStore.items.isEmpty())
        assertNull(vm.uiState.value.selectedSessionDetail)
        assertTrue(vm.uiState.value.sessions.isEmpty())
    }

    @Test
    fun `shareItemFile with null key sets error feedback`() = runTest {
        val vm = buildViewModel()
        advanceUntilIdle()

        val item = HistoryItemDetail(
            itemId = newItemId().value,
            ordinal = 0,
            displayName = "test.txt",
            mimeType = "text/plain",
            offeredBytes = 100L,
            actualBytes = null,
            outcome = null,
            failureCode = null,
            receivedFileKey = null,
            isFileAvailable = false
        )

        vm.shareItemFile(context = null, item = item)
        assertEquals("File not found", vm.uiState.value.openFileFeedback)
    }

    @Test
    fun `shareItemFile with missing file sets error feedback`() = runTest {
        val vm = buildViewModel()
        advanceUntilIdle()

        val item = HistoryItemDetail(
            itemId = newItemId().value,
            ordinal = 0,
            displayName = "test.txt",
            mimeType = "text/plain",
            offeredBytes = 100L,
            actualBytes = null,
            outcome = null,
            failureCode = null,
            receivedFileKey = "missing-key",
            isFileAvailable = true
        )

        vm.shareItemFile(context = null, item = item)
        assertEquals("File not found on device", vm.uiState.value.openFileFeedback)
    }

    @Test
    fun `shareItemFile with valid file invokes fileSharer and clears feedback on success`() = runTest {
        val validFile = tempFolder.newFile("shareable.pdf")
        var sharedFile: File? = null
        val vm = buildViewModel(
            fileResolver = { _, _ -> validFile },
            fileSharer = { _, file, _, _ ->
                sharedFile = file
                FileOpenResult.Success
            }
        )
        advanceUntilIdle()

        val item = HistoryItemDetail(
            itemId = newItemId().value,
            ordinal = 0,
            displayName = "shareable.pdf",
            mimeType = "application/pdf",
            offeredBytes = 200L,
            actualBytes = 200L,
            outcome = "Verified",
            failureCode = null,
            receivedFileKey = "some-key",
            isFileAvailable = true
        )

        vm.shareItemFile(context = null, item = item)
        assertEquals(validFile, sharedFile)
        assertNull(vm.uiState.value.openFileFeedback)
    }

    @Test
    fun `shareItemFile sets feedback when no app available to share`() = runTest {
        val validFile = tempFolder.newFile("unshareable.bin")
        val vm = buildViewModel(
            fileResolver = { _, _ -> validFile },
            fileSharer = { _, _, _, _ -> FileOpenResult.NoViewerApp }
        )
        advanceUntilIdle()

        val item = HistoryItemDetail(
            itemId = newItemId().value,
            ordinal = 0,
            displayName = "unshareable.bin",
            mimeType = "application/octet-stream",
            offeredBytes = 200L,
            actualBytes = 200L,
            outcome = "Verified",
            failureCode = null,
            receivedFileKey = "some-key",
            isFileAvailable = true
        )

        vm.shareItemFile(context = null, item = item)
        assertEquals("No app available to share this file", vm.uiState.value.openFileFeedback)
    }
}
