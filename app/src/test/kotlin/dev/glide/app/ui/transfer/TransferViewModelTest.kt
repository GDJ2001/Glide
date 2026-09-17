package dev.glide.app.ui.transfer

import dev.glide.app.service.TransferRuntime
import dev.glide.core.coordinator.TransferCoordinator
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.CommittedFileHandle
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.StagingHandle
import dev.glide.core.ports.AdvertisingEvent
import dev.glide.core.ports.BulkProgress
import dev.glide.core.ports.ConnectionEvent
import dev.glide.core.ports.ControlMessage
import dev.glide.core.ports.DiscoveryEvent
import dev.glide.core.ports.IncomingStream
import dev.glide.core.ports.InputStreamProvider
import dev.glide.core.ports.ReceiveStore
import dev.glide.core.ports.SourceAccess
import dev.glide.core.ports.SourceMetadata
import dev.glide.core.ports.TransferTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import dev.glide.app.util.FileOpenResult
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class TransferViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

    private class LocalFakeTransport : TransferTransport {
        override suspend fun startDiscovery(serviceId: String): Flow<DiscoveryEvent> = emptyFlow()
        override suspend fun stopDiscovery() {}
        override suspend fun startAdvertising(serviceId: String, localDeviceName: String): Flow<AdvertisingEvent> = emptyFlow()
        override suspend fun stopAdvertising() {}
        override suspend fun initiateConnection(peerId: PeerId, localDeviceName: String) {}
        override suspend fun acceptConnection(peerId: PeerId) {}
        override suspend fun rejectConnection(peerId: PeerId) {}
        override fun observeConnectionEvents(): Flow<ConnectionEvent> = emptyFlow()
        override suspend fun sendControl(peerId: PeerId, payload: ByteArray) {}
        override fun observeControl(): Flow<ControlMessage> = emptyFlow()
        override suspend fun sendStream(peerId: PeerId, bulkRef: BulkRef, streamSource: InputStreamProvider): Flow<BulkProgress> = emptyFlow()
        override fun observeIncomingStream(): Flow<IncomingStream> = emptyFlow()
        override suspend fun cancelPayload(bulkRef: BulkRef) {}
        override suspend fun disconnect(peerId: PeerId) {}
    }

    private class LocalFakeSourceAccess : SourceAccess {
        override suspend fun validateSource(handle: SourceHandle): SourceMetadata =
            SourceMetadata("test.txt", "text/plain", 100L)
        override suspend fun openSource(handle: SourceHandle): InputStream =
            ByteArrayInputStream(ByteArray(100))
    }

    private class LocalFakeReceiveStore : ReceiveStore {
        override suspend fun hasAvailableSpace(requiredBytes: Long): Boolean = true
        override suspend fun prepareStaging(
            sessionId: SessionId,
            itemId: ItemId,
            expectedBytes: Long,
            displayName: String,
            mimeType: String,
            relativePath: String?
        ): StagingHandle = StagingHandle("staging-${itemId.value}")
        override suspend fun openStagingOutput(handle: StagingHandle): OutputStream = ByteArrayOutputStream()
        override suspend fun commitVerified(handle: StagingHandle, digest: Sha256Digest): CommittedFileHandle =
            CommittedFileHandle("file-${handle.value}")
        override suspend fun discardStaging(handle: StagingHandle) {}
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun bindCoordinator_initializesUiStateAndStartsCoordinator() = runTest(testDispatcher) {
        val runtime = TransferRuntime.forTesting().also { it.attachServiceScope(this) }
        val viewModel = TransferViewModel(runtime)
        val transport = LocalFakeTransport()
        val sourceAccess = LocalFakeSourceAccess()
        val receiveStore = LocalFakeReceiveStore()
        val sessionUuid = java.util.UUID.randomUUID().toString()
        val sessionId = SessionId(sessionUuid)
        val peerId = PeerId("peer-1")

        val coordinator = TransferCoordinator(
            sessionId = sessionId,
            role = DeviceRole.Receiver,
            peerId = peerId,
            localDisplayName = "Local Receiver",
            transport = transport,
            sourceAccess = sourceAccess,
            receiveStore = receiveStore,
            scope = this
        )

        viewModel.bindCoordinator(coordinator, peerDisplayName = "Sender Device")
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is TransferUiState.Connecting)
        val connecting = state as TransferUiState.Connecting
        assertEquals(sessionUuid, connecting.sessionId)
        assertEquals("Sender Device", connecting.peerName)

        viewModel.cancelTransfer()
        testScheduler.advanceUntilIdle()

        val cancelledState = viewModel.uiState.value
        assertTrue(cancelledState is TransferUiState.Cancelled)
        runtime.detachServiceScope()
    }

    @Test
    fun openReceivedFile_whenNotCompleted_setsFeedback() {
        val viewModel = TransferViewModel()

        viewModel.openReceivedFile(null)
        assertEquals("No verified file available to open", viewModel.openFileFeedback.value)
    }

    @Test
    fun openReceivedFile_whenSenderCompleted_setsFeedback() {
        val viewModel = TransferViewModel()

        viewModel.setUiStateForTesting(
            TransferUiState.Completed(
                sessionId = "test-session",
                role = DeviceRole.Sender,
                peerName = "Receiver Device",
                totalBytes = 1000L,
                itemName = "test.txt",
                committedFileKey = "key-123"
            )
        )

        viewModel.openReceivedFile(null)
        assertEquals("No verified file available to open", viewModel.openFileFeedback.value)
    }

    @Test
    fun openReceivedFile_whenReceiverCompletedWithoutKey_setsFeedback() {
        val viewModel = TransferViewModel()

        viewModel.setUiStateForTesting(
            TransferUiState.Completed(
                sessionId = "test-session",
                role = DeviceRole.Receiver,
                peerName = "Sender Device",
                totalBytes = 1000L,
                itemName = "test.txt",
                committedFileKey = null
            )
        )

        viewModel.openReceivedFile(null)
        assertEquals("File not found", viewModel.openFileFeedback.value)
    }

    @Test
    fun openReceivedFile_whenReceiverCompletedAndFileNotFound_setsFeedback() {
        val viewModel = TransferViewModel()

        viewModel.setUiStateForTesting(
            TransferUiState.Completed(
                sessionId = "test-session",
                role = DeviceRole.Receiver,
                peerName = "Sender Device",
                totalBytes = 1000L,
                itemName = "test.txt",
                committedFileKey = "nonexistent-key"
            )
        )

        viewModel.openReceivedFile(
            context = null,
            fileResolver = { null }
        )
        assertEquals("File not found on device", viewModel.openFileFeedback.value)
    }

    @Test
    fun openReceivedFile_whenReceiverCompletedAndSuccess_clearsFeedback() {
        val viewModel = TransferViewModel()
        val validFile = tempFolder.newFile("test.txt")
        validFile.writeText("hello")

        viewModel.setUiStateForTesting(
            TransferUiState.Completed(
                sessionId = "test-session",
                role = DeviceRole.Receiver,
                peerName = "Sender Device",
                totalBytes = 1000L,
                itemName = "test.txt",
                committedFileKey = validFile.name
            )
        )

        viewModel.openReceivedFile(
            context = null,
            fileResolver = { validFile },
            fileOpener = { _, _, _ -> FileOpenResult.Success }
        )
        assertNull(viewModel.openFileFeedback.value)
    }

    @Test
    fun openReceivedFile_whenReceiverCompletedAndNoViewerApp_setsFeedback() {
        val viewModel = TransferViewModel()
        val validFile = tempFolder.newFile("test2.txt")
        validFile.writeText("hello 2")

        viewModel.setUiStateForTesting(
            TransferUiState.Completed(
                sessionId = "test-session",
                role = DeviceRole.Receiver,
                peerName = "Sender Device",
                totalBytes = 1000L,
                itemName = "test2.txt",
                committedFileKey = validFile.name
            )
        )

        viewModel.openReceivedFile(
            context = null,
            fileResolver = { validFile },
            fileOpener = { _, _, _ -> FileOpenResult.NoViewerApp }
        )
        assertEquals("No app available to open this file", viewModel.openFileFeedback.value)
    }

    @Test
    fun openReceivedFile_whenReceiverCompletedAndError_setsFeedback() {
        val viewModel = TransferViewModel()
        val validFile = tempFolder.newFile("test3.txt")
        validFile.writeText("hello 3")

        viewModel.setUiStateForTesting(
            TransferUiState.Completed(
                sessionId = "test-session",
                role = DeviceRole.Receiver,
                peerName = "Sender Device",
                totalBytes = 1000L,
                itemName = "test3.txt",
                committedFileKey = validFile.name
            )
        )

        viewModel.openReceivedFile(
            context = null,
            fileResolver = { validFile },
            fileOpener = { _, _, _ -> FileOpenResult.Error("Permission denied") }
        )
        assertEquals("Permission denied", viewModel.openFileFeedback.value)
    }

    @Test
    fun clearOpenFileFeedback_resetsToNull() {
        val viewModel = TransferViewModel()

        viewModel.openReceivedFile(null)
        assertEquals("No verified file available to open", viewModel.openFileFeedback.value)

        viewModel.clearOpenFileFeedback()
        assertNull(viewModel.openFileFeedback.value)
    }

    private class TestFileExporter(
        var result: dev.glide.data.export.ExportResult
    ) : dev.glide.data.export.FileExporter {
        var exportCalled = false
        override suspend fun exportToDownloads(
            sourceFile: File,
            displayName: String,
            mimeType: String,
            relativePath: String?
        ): dev.glide.data.export.ExportResult {
            exportCalled = true
            return result
        }

        override suspend fun exportToUri(
            sourceFile: File,
            destinationUriString: String,
            displayName: String,
            relativePath: String?
        ): dev.glide.data.export.ExportResult = result
    }

    @Test
    fun exportReceivedFile_whenNotReceiver_setsFailed() {
        val viewModel = TransferViewModel()
        viewModel.setUiStateForTesting(
            TransferUiState.Completed(
                sessionId = "test-session",
                role = DeviceRole.Sender,
                peerName = "Receiver Device",
                totalBytes = 1000L,
                itemName = "test.txt",
                committedFileKey = "key-1"
            )
        )

        viewModel.exportReceivedFile(context = null)
        val status = viewModel.exportStatus.value
        assertTrue(status is ExportUiStatus.Failed)
        assertEquals("No verified file available to export", (status as ExportUiStatus.Failed).message)
    }

    @Test
    fun exportReceivedFile_whenFileNotFound_setsFailed() {
        val viewModel = TransferViewModel()
        viewModel.setUiStateForTesting(
            TransferUiState.Completed(
                sessionId = "test-session",
                role = DeviceRole.Receiver,
                peerName = "Sender Device",
                totalBytes = 1000L,
                itemName = "test.txt",
                committedFileKey = "missing-key"
            )
        )

        viewModel.exportReceivedFile(
            context = null,
            fileResolver = { null }
        )
        val status = viewModel.exportStatus.value
        assertTrue(status is ExportUiStatus.Failed)
        assertEquals("File not found on device", (status as ExportUiStatus.Failed).message)
    }

    @Test
    fun exportReceivedFile_whenSuccess_updatesStatusToSuccess() = runTest {
        val viewModel = TransferViewModel()
        val validFile = tempFolder.newFile("export_target.txt")
        validFile.writeText("hello export")

        viewModel.setUiStateForTesting(
            TransferUiState.Completed(
                sessionId = "test-session",
                role = DeviceRole.Receiver,
                peerName = "Sender Device",
                totalBytes = 1000L,
                itemName = "export_target.txt",
                committedFileKey = validFile.name
            )
        )

        val fakeExporter = TestFileExporter(
            dev.glide.data.export.ExportResult.Success(
                destinationUriString = "content://downloads/1",
                destinationName = "export_target (1).txt",
                bytesExported = validFile.length()
            )
        )

        viewModel.exportReceivedFile(
            context = null,
            fileResolver = { validFile },
            fileExporter = fakeExporter
        )

        testScheduler.advanceUntilIdle()

        assertTrue(fakeExporter.exportCalled)
        val status = viewModel.exportStatus.value
        assertTrue(status is ExportUiStatus.Success)
        assertEquals("export_target (1).txt", (status as ExportUiStatus.Success).destinationName)

        viewModel.resetExportStatus()
        assertEquals(ExportUiStatus.Idle, viewModel.exportStatus.value)
    }

    @Test
    fun exportReceivedFile_whenExporterFails_updatesStatusToFailed() = runTest {
        val viewModel = TransferViewModel()
        val validFile = tempFolder.newFile("fail_export.txt")
        validFile.writeText("fail content")

        viewModel.setUiStateForTesting(
            TransferUiState.Completed(
                sessionId = "test-session",
                role = DeviceRole.Receiver,
                peerName = "Sender Device",
                totalBytes = 1000L,
                itemName = "fail_export.txt",
                committedFileKey = validFile.name
            )
        )

        val fakeExporter = TestFileExporter(
            dev.glide.data.export.ExportResult.Failure(
                message = "Disk full during export",
                partialDeleted = true
            )
        )

        viewModel.exportReceivedFile(
            context = null,
            fileResolver = { validFile },
            fileExporter = fakeExporter
        )

        testScheduler.advanceUntilIdle()

        assertTrue(fakeExporter.exportCalled)
        val status = viewModel.exportStatus.value
        assertTrue(status is ExportUiStatus.Failed)
        assertEquals("Disk full during export", (status as ExportUiStatus.Failed).message)
    }

    @Test
    fun failedUiState_withUnfinishedItems_computesCanRetryAndCounts() = runTest {
        val viewModel = TransferViewModel()
        val failedState = TransferUiState.Failed(
            sessionId = "fail-session",
            role = DeviceRole.Sender,
            message = "Connection lost",
            code = "TRANSFER_INTERRUPTED",
            isRecoverable = true,
            isInterrupted = true,
            isReceiptUnconfirmed = false,
            unfinishedCount = 3,
            totalCount = 5,
            canRetry = true,
            peerName = "Pixel 9"
        )
        viewModel.setUiStateForTesting(failedState)

        val uiState = viewModel.uiState.value
        assertTrue(uiState is TransferUiState.Failed)
        val f = uiState as TransferUiState.Failed
        assertTrue(f.canRetry)
        assertTrue(f.isInterrupted)
        assertFalse(f.isReceiptUnconfirmed)
        assertEquals(3, f.unfinishedCount)
        assertEquals(5, f.totalCount)
    }

    @Test
    fun failedUiState_withDeliveryUnconfirmed_setsReceiptUnconfirmedAndCanRetry() = runTest {
        val viewModel = TransferViewModel()
        val unconfirmedState = TransferUiState.Failed(
            sessionId = "unconfirmed-session",
            role = DeviceRole.Sender,
            message = "Delivery unconfirmed",
            code = "DELIVERY_UNCONFIRMED",
            isRecoverable = true,
            isInterrupted = true,
            isReceiptUnconfirmed = true,
            unfinishedCount = 1,
            totalCount = 1,
            canRetry = true,
            peerName = "Pixel 9"
        )
        viewModel.setUiStateForTesting(unconfirmedState)

        val uiState = viewModel.uiState.value
        assertTrue(uiState is TransferUiState.Failed)
        val f = uiState as TransferUiState.Failed
        assertTrue(f.canRetry)
        assertTrue(f.isReceiptUnconfirmed)
        assertEquals("DELIVERY_UNCONFIRMED", f.code)
    }

    @Test
    fun getUnfinishedSourceUris_returnsOnlyUrisOfUncompletedItems() = runTest {
        val runtime = TransferRuntime.forTesting().also { it.attachServiceScope(this) }
        val viewModel = TransferViewModel(runtime)
        val item1 = dev.glide.core.domain.TransferItem(
            id = ItemId(java.util.UUID.randomUUID().toString()),
            name = "file1.txt",
            mimeType = "text/plain",
            sizeBytes = 100L
        )
        val item2 = dev.glide.core.domain.TransferItem(
            id = ItemId(java.util.UUID.randomUUID().toString()),
            name = "file2.txt",
            mimeType = "text/plain",
            sizeBytes = 200L
        )
        val batch = dev.glide.core.domain.TransferBatch(
            id = dev.glide.core.domain.BatchId(java.util.UUID.randomUUID().toString()),
            items = listOf(item1, item2)
        )
        val sourceHandle1 = SourceHandle("content://media/1")
        val sourceHandle2 = SourceHandle("content://media/2")

        val coordinator = TransferCoordinator(
            sessionId = SessionId(java.util.UUID.randomUUID().toString()),
            role = DeviceRole.Sender,
            peerId = PeerId("peer-1"),
            localDisplayName = "Local Device",
            transport = LocalFakeTransport(),
            sourceAccess = LocalFakeSourceAccess(),
            receiveStore = LocalFakeReceiveStore(),
            scope = this,
            senderBatch = batch,
            sourceMap = mapOf(item1.id to sourceHandle1, item2.id to sourceHandle2),
            ioDispatcher = testDispatcher
        )

        viewModel.bindCoordinator(coordinator, peerDisplayName = "Receiver")
        testScheduler.advanceUntilIdle()

        // Before any transfer, both items are unfinished
        val unfinishedBefore = viewModel.getUnfinishedSourceUris()
        assertEquals(2, unfinishedBefore.size)
        assertTrue(unfinishedBefore.contains("content://media/1"))
        assertTrue(unfinishedBefore.contains("content://media/2"))
        runtime.detachServiceScope()
    }

    @Test
    fun getUnfinishedSourceUris_whenPartialBatchVerified_returnsOnlyUnfinished() = runTest {
        val runtime = TransferRuntime.forTesting().also { it.attachServiceScope(this) }
        val viewModel = TransferViewModel(runtime)
        val item1 = dev.glide.core.domain.TransferItem(
            id = ItemId(java.util.UUID.randomUUID().toString()),
            name = "file1.txt",
            mimeType = "text/plain",
            sizeBytes = 100L
        )
        val item2 = dev.glide.core.domain.TransferItem(
            id = ItemId(java.util.UUID.randomUUID().toString()),
            name = "file2.txt",
            mimeType = "text/plain",
            sizeBytes = 200L
        )
        val batch = dev.glide.core.domain.TransferBatch(
            id = dev.glide.core.domain.BatchId(java.util.UUID.randomUUID().toString()),
            items = listOf(item1, item2)
        )
        val sourceHandle1 = SourceHandle("content://media/1")
        val sourceHandle2 = SourceHandle("content://media/2")

        val coordinator = TransferCoordinator(
            sessionId = SessionId(java.util.UUID.randomUUID().toString()),
            role = DeviceRole.Sender,
            peerId = PeerId("peer-1"),
            localDisplayName = "Local Device",
            transport = LocalFakeTransport(),
            sourceAccess = LocalFakeSourceAccess(),
            receiveStore = LocalFakeReceiveStore(),
            scope = this,
            senderBatch = batch,
            sourceMap = mapOf(item1.id to sourceHandle1, item2.id to sourceHandle2),
            ioDispatcher = testDispatcher
        )

        viewModel.bindCoordinator(coordinator, peerDisplayName = "Receiver")
        testScheduler.advanceUntilIdle()

        // Simulate state where item1 is verified and item2 is interrupted
        val item1State = dev.glide.core.coordinator.ItemTransferState(
            item = item1,
            phase = dev.glide.core.coordinator.ItemPhase.Verified,
            transferredBytes = 100L,
            actualDigest = dev.glide.core.domain.Sha256Digest.compute("abc".toByteArray())
        )
        val item2State = dev.glide.core.coordinator.ItemTransferState(
            item = item2,
            phase = dev.glide.core.coordinator.ItemPhase.Interrupted,
            transferredBytes = 50L
        )
        val sessionState = dev.glide.core.coordinator.SessionState(
            sessionId = SessionId(java.util.UUID.randomUUID().toString()),
            role = DeviceRole.Sender,
            phase = dev.glide.core.coordinator.SessionPhase.Interrupted,
            batch = batch,
            itemStates = mapOf(item1.id to item1State, item2.id to item2State)
        )

        // Verify that unfinishedItems and createRetryBatch only contain item2
        assertEquals(1, sessionState.unfinishedItems.size)
        assertEquals(item2.id, sessionState.unfinishedItems.first().id)
        val retryBatch = sessionState.createRetryBatch()
        org.junit.Assert.assertNotNull(retryBatch)
        assertEquals(1, retryBatch!!.items.size)
        assertEquals(item2.id, retryBatch.items.first().id)
        runtime.detachServiceScope()
    }

    @Test
    fun openVerifiedItem_whenKeyNull_setsFeedback() {
        val viewModel = TransferViewModel()
        val item = VerifiedItemUiState(
            itemId = "item-1",
            displayName = "file.txt",
            mimeType = "text/plain",
            sizeBytes = 100L,
            committedFileKey = null
        )

        viewModel.openVerifiedItem(null, item)
        assertEquals("File not found", viewModel.openFileFeedback.value)
    }

    @Test
    fun openVerifiedItem_whenFileMissing_setsFeedback() {
        val viewModel = TransferViewModel()
        val item = VerifiedItemUiState(
            itemId = "item-1",
            displayName = "file.txt",
            mimeType = "text/plain",
            sizeBytes = 100L,
            committedFileKey = "missing-key"
        )

        viewModel.openVerifiedItem(null, item, fileResolver = { null })
        assertEquals("File not found on device", viewModel.openFileFeedback.value)
    }

    @Test
    fun openVerifiedItem_whenFileExists_callsOpenerAndClearsFeedback() {
        val viewModel = TransferViewModel()
        val validFile = tempFolder.newFile("open_test.txt")
        validFile.writeText("content")

        val item = VerifiedItemUiState(
            itemId = "item-1",
            displayName = "open_test.txt",
            mimeType = "text/plain",
            sizeBytes = 100L,
            committedFileKey = "open-key"
        )

        viewModel.openVerifiedItem(
            context = null,
            item = item,
            fileResolver = { validFile },
            fileOpener = { _, _, _ -> FileOpenResult.Success }
        )
        assertNull(viewModel.openFileFeedback.value)
    }

    @Test
    fun shareVerifiedItem_whenFileMissing_setsFeedback() {
        val viewModel = TransferViewModel()
        val item = VerifiedItemUiState(
            itemId = "item-1",
            displayName = "share_test.txt",
            mimeType = "text/plain",
            sizeBytes = 100L,
            committedFileKey = "missing-key"
        )

        viewModel.shareVerifiedItem(null, item, fileResolver = { null })
        assertEquals("File not found on device", viewModel.openFileFeedback.value)
    }

    @Test
    fun shareVerifiedItem_whenSuccess_clearsFeedback() {
        val viewModel = TransferViewModel()
        val validFile = tempFolder.newFile("share_test.txt")
        validFile.writeText("content")

        val item = VerifiedItemUiState(
            itemId = "item-1",
            displayName = "share_test.txt",
            mimeType = "text/plain",
            sizeBytes = 100L,
            committedFileKey = "key"
        )

        viewModel.shareVerifiedItem(
            context = null,
            item = item,
            fileResolver = { validFile },
            fileSharer = { _, _, _ -> FileOpenResult.Success }
        )
        assertNull(viewModel.openFileFeedback.value)
    }

    @Test
    fun exportVerifiedItem_whenSuccess_setsExportStatusSuccess() = runTest {
        val viewModel = TransferViewModel()
        val validFile = tempFolder.newFile("export_test.txt")
        validFile.writeText("content")

        val item = VerifiedItemUiState(
            itemId = "item-1",
            displayName = "export_test.txt",
            mimeType = "text/plain",
            sizeBytes = 100L,
            committedFileKey = "export-key"
        )

        val fakeExporter = object : dev.glide.data.export.FileExporter {
            override suspend fun exportToDownloads(sourceFile: File, displayName: String, mimeType: String, relativePath: String?): dev.glide.data.export.ExportResult {
                return dev.glide.data.export.ExportResult.Success(
                    destinationUriString = "content://downloads/1",
                    destinationName = "export_test (1).txt",
                    bytesExported = 100L
                )
            }
            override suspend fun exportToUri(sourceFile: File, destinationUriString: String, displayName: String, relativePath: String?): dev.glide.data.export.ExportResult {
                return dev.glide.data.export.ExportResult.Success(
                    destinationUriString = destinationUriString,
                    destinationName = "export_test.txt",
                    bytesExported = 100L
                )
            }
        }

        viewModel.exportVerifiedItem(
            context = null,
            item = item,
            fileResolver = { validFile },
            fileExporter = fakeExporter
        )
        testScheduler.advanceUntilIdle()

        val status = viewModel.exportStatus.value
        assertTrue(status is ExportUiStatus.Success)
        assertEquals("export_test (1).txt", (status as ExportUiStatus.Success).destinationName)
    }

    @Test
    fun shareAllVerifiedItems_whenNoFilesAvailable_setsFeedback() {
        val viewModel = TransferViewModel()
        val item = VerifiedItemUiState(
            itemId = "item-1",
            displayName = "file1.txt",
            mimeType = "text/plain",
            sizeBytes = 100L,
            committedFileKey = "key1"
        )

        viewModel.setUiStateForTesting(
            TransferUiState.Completed(
                sessionId = "s1",
                role = DeviceRole.Receiver,
                peerName = "Sender",
                totalBytes = 100L,
                itemName = "file1.txt",
                verifiedItems = listOf(item)
            )
        )

        viewModel.shareAllVerifiedItems(null, fileResolver = { null })
        assertEquals("No files available on device to share", viewModel.openFileFeedback.value)
    }

    @Test
    fun shareAllVerifiedItems_whenSuccess_invokesSharerAndClearsFeedback() {
        val viewModel = TransferViewModel()
        val file1 = tempFolder.newFile("batch1.txt").apply { writeText("1") }
        val file2 = tempFolder.newFile("batch2.txt").apply { writeText("2") }

        val item1 = VerifiedItemUiState("i1", "batch1.txt", "text/plain", 10L, "k1")
        val item2 = VerifiedItemUiState("i2", "batch2.txt", "text/plain", 20L, "k2")

        viewModel.setUiStateForTesting(
            TransferUiState.Completed(
                sessionId = "s1",
                role = DeviceRole.Receiver,
                peerName = "Sender",
                totalBytes = 30L,
                itemName = "batch1.txt",
                verifiedItems = listOf(item1, item2)
            )
        )

        var sharedFilesCount = 0
        viewModel.shareAllVerifiedItems(
            context = null,
            fileResolver = { key -> if (key == "k1") file1 else file2 },
            fileSharer = { files, _ ->
                sharedFilesCount = files.size
                FileOpenResult.Success
            }
        )

        assertEquals(2, sharedFilesCount)
        assertNull(viewModel.openFileFeedback.value)
    }

    @Test
    fun exportAllVerifiedItems_multipleFiles_updatesStatusWithTotalCount() = runTest {
        val viewModel = TransferViewModel()
        val file1 = tempFolder.newFile("export1.txt").apply { writeText("1") }
        val file2 = tempFolder.newFile("export2.txt").apply { writeText("2") }

        val item1 = VerifiedItemUiState("i1", "export1.txt", "text/plain", 10L, "k1")
        val item2 = VerifiedItemUiState("i2", "export2.txt", "text/plain", 20L, "k2")

        viewModel.setUiStateForTesting(
            TransferUiState.Completed(
                sessionId = "s1",
                role = DeviceRole.Receiver,
                peerName = "Sender",
                totalBytes = 30L,
                itemName = "export1.txt",
                verifiedItems = listOf(item1, item2)
            )
        )

        val fakeExporter = object : dev.glide.data.export.FileExporter {
            override suspend fun exportToDownloads(sourceFile: File, displayName: String, mimeType: String, relativePath: String?): dev.glide.data.export.ExportResult {
                return dev.glide.data.export.ExportResult.Success(
                    destinationUriString = "content://downloads/1",
                    destinationName = displayName,
                    bytesExported = 10L
                )
            }
            override suspend fun exportToUri(sourceFile: File, destinationUriString: String, displayName: String, relativePath: String?): dev.glide.data.export.ExportResult {
                return dev.glide.data.export.ExportResult.Success(
                    destinationUriString = destinationUriString,
                    destinationName = displayName,
                    bytesExported = 10L
                )
            }
        }

        viewModel.exportAllVerifiedItems(
            context = null,
            fileResolver = { key -> if (key == "k1") file1 else file2 },
            fileExporter = fakeExporter
        )
        testScheduler.advanceUntilIdle()

        val status = viewModel.exportStatus.value
        assertTrue(status is ExportUiStatus.Success)
        assertEquals("2 files", (status as ExportUiStatus.Success).destinationName)
    }
}
