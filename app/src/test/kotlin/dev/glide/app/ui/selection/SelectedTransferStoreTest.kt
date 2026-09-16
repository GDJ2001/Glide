package dev.glide.app.ui.selection

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.UUID

class SelectedTransferStoreTest {

    @Before
    fun setUp() {
        SelectedTransferStore.resetForTesting()
    }

    @After
    fun tearDown() {
        SelectedTransferStore.resetForTesting()
    }

    @Test
    fun storeAndRetrieve_multiFileBatch_preservesFullBatchAndSourceMap() {
        val item1Id = ItemId(UUID.randomUUID().toString())
        val item2Id = ItemId(UUID.randomUUID().toString())
        val handle1 = SourceHandle("content://media/external/file/101")
        val handle2 = SourceHandle("content://media/external/file/102")

        val batchId = BatchId(UUID.randomUUID().toString())
        val batch = TransferBatch(
            id = batchId,
            items = listOf(
                TransferItem(item1Id, "doc1.pdf", "application/pdf", 1024L),
                TransferItem(item2Id, "photo.jpg", "image/jpeg", 2048L)
            )
        )
        val sourceMap = mapOf(
            item1Id to handle1,
            item2Id to handle2
        )

        SelectedTransferStore.store(batch, sourceMap)

        val retrieved = SelectedTransferStore.get(batchId)
        assertNotNull(retrieved)
        assertEquals(batchId, retrieved!!.batch.id)
        assertEquals(2, retrieved.batch.items.size)
        assertEquals(handle1, retrieved.sourceMap[item1Id])
        assertEquals(handle2, retrieved.sourceMap[item2Id])

        // Active batch
        val active = SelectedTransferStore.getActive()
        assertNotNull(active)
        assertEquals(batchId, active!!.batch.id)

        // getSourceMap convenience
        val map = SelectedTransferStore.getSourceMap(batchId)
        assertEquals(2, map.size)
        assertEquals(handle1, map[item1Id])
        assertEquals(handle2, map[item2Id])
    }

    @Test
    fun clear_removesBatchAndResetsActive() {
        val itemId = ItemId(UUID.randomUUID().toString())
        val batchId = BatchId(UUID.randomUUID().toString())
        val batch = TransferBatch(
            id = batchId,
            items = listOf(TransferItem(itemId, "sample.txt", "text/plain", 100L))
        )
        val sourceMap = mapOf(itemId to SourceHandle("content://test/1"))

        SelectedTransferStore.store(batch, sourceMap)
        assertNotNull(SelectedTransferStore.get(batchId))

        SelectedTransferStore.clear(batchId)
        assertNull(SelectedTransferStore.get(batchId))
        assertNull(SelectedTransferStore.getActive())
    }

    @Test
    fun get_withUnknownBatchId_returnsNull() {
        assertNull(SelectedTransferStore.get(BatchId(UUID.randomUUID().toString())))
    }
}
