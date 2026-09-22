package dev.glide.app.ui.selection

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.SourceHandle
import dev.glide.core.protocol.OfferPaginator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.system.measureTimeMillis

class SelectionUiStateTest {

    private fun validUuid(seed: Int): String {
        return String.format(java.util.Locale.US, "00000000-0000-4000-8000-%012x", seed)
    }

    private fun createItem(
        index: Int,
        uri: String = "content://media/external/file/$index",
        name: String = "file_$index.dat",
        sizeBytes: Long = 1024L,
        mimeType: String = "application/octet-stream",
        isAvailable: Boolean = true,
        errorMessage: String? = null
    ): SelectedFileItem = SelectedFileItem(
        id = ItemId(validUuid(index)),
        handle = SourceHandle(uri),
        displayName = name,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        isAvailable = isAvailable,
        errorMessage = errorMessage
    )

    @Test
    fun batchReady_aggregatesCountAndTotalBytesAccurately() {
        val items = listOf(
            createItem(1, sizeBytes = 1000L),
            createItem(2, sizeBytes = 2500L),
            createItem(3, sizeBytes = 500L)
        )
        val state = SelectionUiState.BatchReady(items = items)

        assertEquals(3, state.itemCount)
        assertEquals(3, state.validItemCount)
        assertEquals(4000L, state.totalBytes)
        assertFalse(state.hasUnavailableItems)
        assertFalse(state.isLimitExceeded)
        assertTrue(state.canProceed)
    }

    @Test
    fun batchReady_deduplicationPreservesDistinctUris() {
        val uri1 = "content://media/external/file/101"
        val uri2 = "content://media/external/file/102"
        val rawSelectedUris = listOf(uri1, uri2, uri1, uri2, "content://media/external/file/103")

        val distinctUris = rawSelectedUris.distinct()
        assertEquals(3, distinctUris.size)

        val items = distinctUris.mapIndexed { index, uri ->
            createItem(index = index + 1, uri = uri)
        }
        val state = SelectionUiState.BatchReady(items = items)
        assertEquals(3, state.itemCount)
    }

    @Test
    fun batchReady_enforces1000ItemCeiling() {
        // 1000 items is the maximum allowed
        val atLimitItems = (1..1000).map { createItem(it, sizeBytes = 10L) }
        val atLimitState = SelectionUiState.BatchReady(items = atLimitItems)
        assertEquals(1000, atLimitState.itemCount)
        assertFalse(atLimitState.isLimitExceeded)
        assertTrue(atLimitState.canProceed)

        // 1001 items exceeds protocol ceiling
        val overLimitItems = (1..1001).map { createItem(it, sizeBytes = 10L) }
        val overLimitState = SelectionUiState.BatchReady(items = overLimitItems)
        assertEquals(1001, overLimitState.itemCount)
        assertTrue(overLimitState.isLimitExceeded)
        assertFalse(overLimitState.canProceed)
    }

    @Test
    fun batchReady_itemRemovalUpdatesStateImmediately() {
        val item1 = createItem(1, sizeBytes = 1000L)
        val item2 = createItem(2, sizeBytes = 2000L)
        val item3 = createItem(3, sizeBytes = 3000L)

        val initialState = SelectionUiState.BatchReady(items = listOf(item1, item2, item3))
        assertEquals(3, initialState.itemCount)
        assertEquals(6000L, initialState.totalBytes)

        // Remove item2
        val updatedItems = initialState.items.filterNot { it.id == item2.id }
        val updatedState = SelectionUiState.BatchReady(items = updatedItems)

        assertEquals(2, updatedState.itemCount)
        assertEquals(4000L, updatedState.totalBytes)
        assertEquals(listOf(item1, item3), updatedState.items)
    }

    @Test
    fun batchReady_unavailableItemDisablesProceedUntilRemoved() {
        val validItem = createItem(1, sizeBytes = 5000L, isAvailable = true)
        val brokenItem = createItem(
            2,
            sizeBytes = 0L,
            isAvailable = false,
            errorMessage = "File not found or deleted"
        )

        val stateWithBroken = SelectionUiState.BatchReady(items = listOf(validItem, brokenItem))
        assertEquals(2, stateWithBroken.itemCount)
        assertEquals(1, stateWithBroken.validItemCount)
        assertEquals(5000L, stateWithBroken.totalBytes)
        assertTrue(stateWithBroken.hasUnavailableItems)
        assertFalse(stateWithBroken.canProceed)

        // Calling toTransferBatch throws IllegalStateException
        assertThrows(IllegalStateException::class.java) {
            stateWithBroken.toTransferBatch()
        }

        // Removing broken item restores proceed capability
        val healedItems = stateWithBroken.items.filterNot { it.id == brokenItem.id }
        val healedState = SelectionUiState.BatchReady(items = healedItems)
        assertFalse(healedState.hasUnavailableItems)
        assertTrue(healedState.canProceed)
        assertEquals(1, healedState.validItemCount)
    }

    @Test
    fun batchReady_emptyBatchCannotProceed() {
        val emptyState = SelectionUiState.BatchReady(items = emptyList())
        assertEquals(0, emptyState.itemCount)
        assertFalse(emptyState.canProceed)
        assertThrows(IllegalStateException::class.java) {
            emptyState.toTransferBatch()
        }
    }

    @Test
    fun batchReady_convertsToTransferBatchAndPaginatesCleanly() {
        val itemCount = 100
        val items = (1..itemCount).map {
            createItem(
                index = it,
                name = "document_$it.pdf",
                sizeBytes = (it * 1024).toLong(),
                mimeType = "application/pdf"
            )
        }
        val state = SelectionUiState.BatchReady(items = items)
        assertTrue(state.canProceed)

        val batchId = BatchId(UUID.randomUUID().toString())
        val transferBatch = state.toTransferBatch(batchId = batchId)

        assertEquals(batchId, transferBatch.id)
        assertEquals(100, transferBatch.items.size)
        assertEquals(items.sumOf { it.sizeBytes }, transferBatch.totalBytes)

        // Verify compatibility with OfferPaginator
        val pagedOffer = OfferPaginator.paginate(
            batch = transferBatch,
            maxItemsPerPage = ProtocolLimits.MAX_OFFER_PAGE_ITEMS // 32
        )

        assertEquals(batchId, pagedOffer.begin.batchId)
        assertEquals(100, pagedOffer.begin.itemCount)
        // 100 items / 32 per page = 4 pages (32, 32, 32, 4)
        assertEquals(4, pagedOffer.pages.size)
        assertEquals(32, pagedOffer.pages[0].items.size)
        assertEquals(32, pagedOffer.pages[1].items.size)
        assertEquals(32, pagedOffer.pages[2].items.size)
        assertEquals(4, pagedOffer.pages[3].items.size)
        assertEquals(batchId, pagedOffer.end.batchId)
    }

    @Test
    fun batchReady_performance1000ItemsInstantiationAndAggregation() {
        val items = (1..1000).map {
            createItem(
                index = it,
                name = "video_recording_$it.mp4",
                sizeBytes = 10_000_000L,
                mimeType = "video/mp4"
            )
        }

        val elapsedMs = measureTimeMillis {
            val state = SelectionUiState.BatchReady(items = items)
            assertEquals(1000, state.itemCount)
            assertEquals(10_000_000_000L, state.totalBytes)
            assertTrue(state.canProceed)
            val batch = state.toTransferBatch()
            assertEquals(1000, batch.items.size)
        }

        // Must complete well within 50ms budget for UI responsiveness
        assertTrue("Batch calculation took ${elapsedMs}ms, exceeding 50ms budget", elapsedMs < 50)
    }
}
