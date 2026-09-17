package dev.glide.app.ui.selection

import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.TransferFailure
import dev.glide.core.ports.SourceAccess
import dev.glide.core.ports.SourceAccessException
import dev.glide.core.ports.SourceMetadata
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
import org.junit.Test
import dev.glide.data.source.SafDocumentNode
import dev.glide.data.source.SafTreeCrawler
import dev.glide.data.source.SafTreeQueryAdapter
import java.io.InputStream

/**
 * Unit tests for [SelectionViewModel] (LAN-021, L5).
 *
 * Verifies:
 * 1. Multi-file validation via SourceAccess port.
 * 2. Complete batch retention without single-file truncation.
 * 3. Unavailable / revoked sources marked unavailable with safe errors.
 * 4. Deduplication of incoming URIs.
 * 5. Hand-off to [SelectedTransferStore] preserving all SourceHandles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectionViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        SelectedTransferStore.resetForTesting()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        SelectedTransferStore.resetForTesting()
    }

    @Test
    fun onUriStringsSelected_multipleValidFiles_populatesBatchReadyWithAllItems() = runTest(testDispatcher) {
        val fakeSourceAccess = FakeSourceAccess(
            files = mapOf(
                "content://media/photos/1" to SourceMetadata("photo1.jpg", "image/jpeg", 1_500_000L),
                "content://media/photos/2" to SourceMetadata("photo2.jpg", "image/jpeg", 2_200_000L),
                "content://media/docs/3" to SourceMetadata("report.pdf", "application/pdf", 500_000L)
            )
        )

        val viewModel = SelectionViewModel(customSourceAccess = fakeSourceAccess)
        viewModel.onUriStringsSelected(
            listOf(
                "content://media/photos/1",
                "content://media/photos/2",
                "content://media/docs/3"
            ),
            fakeSourceAccess
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Expected BatchReady, got: $state", state is SelectionUiState.BatchReady)
        val batch = state as SelectionUiState.BatchReady
        assertEquals(3, batch.itemCount)
        assertEquals(3, batch.validItemCount)
        assertEquals(4_200_000L, batch.totalBytes)
        assertFalse(batch.hasUnavailableItems)
        assertTrue(batch.canProceed)
    }

    @Test
    fun onUriStringsSelected_inaccessibleFile_markedUnavailableAndBlocksProceed() = runTest(testDispatcher) {
        val fakeSourceAccess = FakeSourceAccess(
            files = mapOf(
                "content://media/photos/1" to SourceMetadata("photo1.jpg", "image/jpeg", 1_000_000L)
            ),
            failingUris = mapOf(
                "content://revoked/file.png" to SourceAccessException(TransferFailure.SourceUnavailable("Permission revoked"))
            )
        )

        val viewModel = SelectionViewModel(customSourceAccess = fakeSourceAccess)
        viewModel.onUriStringsSelected(
            listOf("content://media/photos/1", "content://revoked/file.png"),
            fakeSourceAccess
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is SelectionUiState.BatchReady)
        val batch = state as SelectionUiState.BatchReady
        assertEquals(2, batch.itemCount)
        assertEquals(1, batch.validItemCount)
        assertTrue(batch.hasUnavailableItems)
        assertFalse("Cannot proceed while unavailable items are present", batch.canProceed)

        val unavailable = batch.items.first { !it.isAvailable }
        assertEquals("content://revoked/file.png", unavailable.handle.value)
        assertNotNull(unavailable.errorMessage)

        // Remove unavailable file to unblock proceeding
        viewModel.removeFile(unavailable.id)
        val updatedState = viewModel.uiState.value as SelectionUiState.BatchReady
        assertEquals(1, updatedState.itemCount)
        assertFalse(updatedState.hasUnavailableItems)
        assertTrue(updatedState.canProceed)
    }

    @Test
    fun onUriStringsSelected_duplicateUris_deduplicatedPreservingOrder() = runTest(testDispatcher) {
        val fakeSourceAccess = FakeSourceAccess(
            files = mapOf(
                "content://media/1" to SourceMetadata("file1.dat", "application/octet-stream", 100L),
                "content://media/2" to SourceMetadata("file2.dat", "application/octet-stream", 200L)
            )
        )

        val viewModel = SelectionViewModel(customSourceAccess = fakeSourceAccess)
        viewModel.onUriStringsSelected(
            listOf(
                "content://media/1",
                "content://media/2",
                "content://media/1",
                "content://media/2"
            ),
            fakeSourceAccess
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value as SelectionUiState.BatchReady
        assertEquals(2, state.itemCount)
        assertEquals("file1.dat", state.items[0].displayName)
        assertEquals("file2.dat", state.items[1].displayName)
    }

    @Test
    fun onUriStringsSelected_batchLimitExceeded_setsErrorState() = runTest(testDispatcher) {
        val fakeSourceAccess = FakeSourceAccess(emptyMap())
        val hugeList = (1..ProtocolLimits.MAX_ITEMS_PER_BATCH + 5).map { "content://photos/$it" }

        val viewModel = SelectionViewModel(customSourceAccess = fakeSourceAccess)
        viewModel.onUriStringsSelected(hugeList, fakeSourceAccess)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is SelectionUiState.Error)
        val error = state as SelectionUiState.Error
        assertTrue(error.message.contains("Batch limit exceeded"))
    }

    @Test
    fun prepareBatchForTransfer_storesAllHandlesInSelectedTransferStore() = runTest(testDispatcher) {
        val fakeSourceAccess = FakeSourceAccess(
            files = mapOf(
                "content://files/alpha.txt" to SourceMetadata("alpha.txt", "text/plain", 1000L),
                "content://files/beta.txt" to SourceMetadata("beta.txt", "text/plain", 2000L)
            )
        )

        val viewModel = SelectionViewModel(customSourceAccess = fakeSourceAccess)
        viewModel.onUriStringsSelected(listOf("content://files/alpha.txt", "content://files/beta.txt"), fakeSourceAccess)
        advanceUntilIdle()

        val prepared = viewModel.prepareBatchForTransfer()
        assertNotNull(prepared)
        val (batch, sourceMap) = prepared!!
        assertEquals(2, batch.items.size)
        assertEquals(2, sourceMap.size)

        // Verify retrieval from SelectedTransferStore
        val stored = SelectedTransferStore.get(batch.id)
        assertNotNull(stored)
        assertEquals(batch.id, stored!!.batch.id)
        assertEquals(2, stored.sourceMap.size)
        assertEquals("content://files/alpha.txt", stored.sourceMap[batch.items[0].id]?.value)
        assertEquals("content://files/beta.txt", stored.sourceMap[batch.items[1].id]?.value)
    }

    @Test
    fun prepareBatchForTransfer_cannotProceed_returnsNull() = runTest(testDispatcher) {
        val viewModel = SelectionViewModel()
        assertNull(viewModel.prepareBatchForTransfer())
    }

    @Test
    fun clear_resetsStateToIdle() = runTest(testDispatcher) {
        val fakeSourceAccess = FakeSourceAccess(
            files = mapOf("content://f/1" to SourceMetadata("f1", "text/plain", 50L))
        )
        val viewModel = SelectionViewModel(customSourceAccess = fakeSourceAccess)
        viewModel.onUriStringsSelected(listOf("content://f/1"), fakeSourceAccess)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is SelectionUiState.BatchReady)
        viewModel.clear()
        assertEquals(SelectionUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun cancelStaging_transitionsToCancelledState() = runTest(testDispatcher) {
        val viewModel = SelectionViewModel()
        viewModel.cancelStaging()
        assertEquals(SelectionUiState.Cancelled, viewModel.uiState.value)
    }

    @Test
    fun onTreeUriStringSelected_traversesFolderAndPopulatesBatchWithRelativePaths() = runTest(testDispatcher) {
        val rootId = "primary:Documents/MyFolder"
        val adapter = FakeSafTreeQueryAdapter(
            treeDocId = rootId,
            rootNode = SafDocumentNode(rootId, "MyFolder", "vnd.android.document/directory", 0L, true),
            childrenMap = mapOf(
                rootId to listOf(
                    SafDocumentNode("doc_1", "file1.txt", "text/plain", 100L, false),
                    SafDocumentNode("doc_dir", "sub", "vnd.android.document/directory", 0L, true)
                ),
                "doc_dir" to listOf(
                    SafDocumentNode("doc_2", "file2.png", "image/png", 200L, false)
                )
            )
        )
        val crawler = SafTreeCrawler(adapter, testDispatcher)

        val viewModel = SelectionViewModel()
        viewModel.onTreeUriStringSelected("content://tree/primary%3AMyFolder", crawler)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Expected BatchReady, got: $state", state is SelectionUiState.BatchReady)
        val batch = state as SelectionUiState.BatchReady
        assertEquals(2, batch.itemCount)
        assertEquals(300L, batch.totalBytes)

        val item1 = batch.items.find { it.displayName == "file1.txt" }
        assertNotNull(item1)
        assertEquals("MyFolder/file1.txt", item1!!.relativePath)
        assertEquals(100L, item1.sizeBytes)

        val item2 = batch.items.find { it.displayName == "file2.png" }
        assertNotNull(item2)
        assertEquals("MyFolder/sub/file2.png", item2!!.relativePath)
        assertEquals(200L, item2.sizeBytes)

        // Verify batch transfer preparation retains relative paths
        val prepared = viewModel.prepareBatchForTransfer()
        assertNotNull(prepared)
        val (transferBatch, _) = prepared!!
        assertEquals(2, transferBatch.items.size)
        val transferItem1 = transferBatch.items.find { it.name == "file1.txt" }
        assertEquals("MyFolder/file1.txt", transferItem1?.relativePath)
        val transferItem2 = transferBatch.items.find { it.name == "file2.png" }
        assertEquals("MyFolder/sub/file2.png", transferItem2?.relativePath)
    }

    @Test
    fun onTreeUriStringSelected_emptyFolder_leavesBatchIdle() = runTest(testDispatcher) {
        val rootId = "primary:Documents/EmptyFolder"
        val adapter = FakeSafTreeQueryAdapter(
            treeDocId = rootId,
            rootNode = SafDocumentNode(rootId, "EmptyFolder", "vnd.android.document/directory", 0L, true),
            childrenMap = emptyMap()
        )
        val crawler = SafTreeCrawler(adapter, testDispatcher)

        val viewModel = SelectionViewModel()
        viewModel.onTreeUriStringSelected("content://tree/primary%3AEmptyFolder", crawler)
        advanceUntilIdle()

        assertEquals(SelectionUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun onTreeUriStringSelected_appendsToExistingBatch_retainsFlatAndFolderFiles() = runTest(testDispatcher) {
        val fakeSourceAccess = FakeSourceAccess(
            files = mapOf(
                "content://media/photos/1" to SourceMetadata("flat.jpg", "image/jpeg", 500L)
            )
        )
        val viewModel = SelectionViewModel(customSourceAccess = fakeSourceAccess)
        viewModel.onUriStringsSelected(listOf("content://media/photos/1"), fakeSourceAccess)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is SelectionUiState.BatchReady)

        val rootId = "primary:Documents/Folder"
        val adapter = FakeSafTreeQueryAdapter(
            treeDocId = rootId,
            rootNode = SafDocumentNode(rootId, "Folder", "vnd.android.document/directory", 0L, true),
            childrenMap = mapOf(
                rootId to listOf(
                    SafDocumentNode("doc_f1", "nested.txt", "text/plain", 300L, false)
                )
            )
        )
        val crawler = SafTreeCrawler(adapter, testDispatcher)
        viewModel.onTreeUriStringSelected("content://tree/primary%3AFolder", crawler)
        advanceUntilIdle()

        val state = viewModel.uiState.value as SelectionUiState.BatchReady
        assertEquals(2, state.itemCount)
        assertEquals(800L, state.totalBytes)

        val flatItem = state.items.find { it.displayName == "flat.jpg" }
        assertNotNull(flatItem)
        assertNull(flatItem!!.relativePath)

        val folderItem = state.items.find { it.displayName == "nested.txt" }
        assertNotNull(folderItem)
        assertEquals("Folder/nested.txt", folderItem!!.relativePath)
    }

    private class FakeSafTreeQueryAdapter(
        private val treeDocId: String = "primary:Documents/MyFolder",
        private val rootNode: SafDocumentNode = SafDocumentNode("primary:Documents/MyFolder", "MyFolder", "vnd.android.document/directory", 0L, true),
        private val childrenMap: Map<String, List<SafDocumentNode>> = emptyMap()
    ) : SafTreeQueryAdapter {
        override fun getTreeDocumentId(treeUriString: String): String = treeDocId

        override fun queryDocument(treeUriString: String, documentId: String): SafDocumentNode? =
            if (documentId == treeDocId) rootNode else childrenMap.values.flatten().find { it.documentId == documentId }

        override fun queryChildren(treeUriString: String, parentDocumentId: String): List<SafDocumentNode> =
            childrenMap[parentDocumentId] ?: emptyList()

        override fun buildDocumentUri(treeUriString: String, documentId: String): String =
            "content://com.android.externalstorage.documents/tree/primary%3AMyFolder/document/$documentId"
    }

    private class FakeSourceAccess(
        private val files: Map<String, SourceMetadata>,
        private val failingUris: Map<String, Exception> = emptyMap()
    ) : SourceAccess {
        override suspend fun validateSource(handle: SourceHandle): SourceMetadata {
            failingUris[handle.value]?.let { throw it }
            return files[handle.value]
                ?: throw SourceAccessException(TransferFailure.SourceUnavailable("File not found: ${handle.value}"))
        }

        override suspend fun openSource(handle: SourceHandle): InputStream {
            return "".byteInputStream()
        }
    }
}
