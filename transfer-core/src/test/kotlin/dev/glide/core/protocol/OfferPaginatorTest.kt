package dev.glide.core.protocol

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferPaginatorTest {

    private val validBatchId = BatchId("d0000000-0000-4000-8000-000000000001")

    private fun createItem(index: Int, size: Long = 1024L, name: String = "file_$index.txt"): TransferItem {
        val hexIndex = index.toString(16).padStart(12, '0')
        val id = ItemId("e0000000-0000-4000-8000-$hexIndex")
        return TransferItem(
            id = id,
            name = name,
            mimeType = "text/plain",
            sizeBytes = size
        )
    }

    @Test
    fun paginateSmallBatch_createsSinglePage() {
        val items = (1..5).map { createItem(it) }
        val batch = TransferBatch(validBatchId, items)

        val paged = OfferPaginator.paginate(batch)

        assertEquals(1, paged.pages.size)
        assertEquals(5, paged.begin.itemCount)
        assertEquals(1, paged.begin.pageCount)
        assertEquals(5 * 1024L, DecimalByteCount.parse(paged.begin.totalBytes))
        assertEquals(0, paged.pages[0].pageIndex)
        assertEquals(5, paged.pages[0].items.size)
    }

    @Test
    fun paginateLargeBatch_splitsIntoPagesOf32() {
        // 70 items should split into 3 pages: 32 + 32 + 6
        val items = (1..70).map { createItem(it) }
        val batch = TransferBatch(validBatchId, items)

        val paged = OfferPaginator.paginate(batch)

        assertEquals(3, paged.pages.size)
        assertEquals(70, paged.begin.itemCount)
        assertEquals(3, paged.begin.pageCount)
        assertEquals(32, paged.pages[0].items.size)
        assertEquals(32, paged.pages[1].items.size)
        assertEquals(6, paged.pages[2].items.size)
        assertEquals(0, paged.pages[0].pageIndex)
        assertEquals(1, paged.pages[1].pageIndex)
        assertEquals(2, paged.pages[2].pageIndex)
    }

    @Test
    fun paginateItemsWithLargeNames_splitsEarlyBeforeSizeLimit() {
        // Items with long display names within 255 code point ceiling
        val longName = "a".repeat(240)
        val items = (1..30).map { createItem(it, name = "${it}_$longName.txt") }
        val batch = TransferBatch(validBatchId, items)

        // With safe ceiling set to 2000 bytes, each ~350 byte item causes split after ~5 items
        val paged = OfferPaginator.paginate(batch, safePagePayloadCeilingBytes = 2000)

        assertTrue(paged.pages.size >= 2)
        assertEquals(30, paged.begin.itemCount)
        assertEquals(paged.pages.size, paged.begin.pageCount)
    }

    @Test
    fun paginatedOffer_roundTripsThroughOfferAssembler() {
        val items = (1..50).map { createItem(it, size = (it * 100).toLong()) }
        val batch = TransferBatch(validBatchId, items)

        val paged = OfferPaginator.paginate(batch)
        val assembler = OfferAssembler(paged.begin)

        for (page in paged.pages) {
            assembler.addPage(page)
        }

        val offer = assembler.finish(paged.end)
        assertEquals(batch.id, offer.batch.id)
        assertEquals(batch.items.size, offer.batch.items.size)
        assertEquals(batch.totalBytes, offer.batch.totalBytes)
        for (i in items.indices) {
            assertEquals(batch.items[i].id, offer.batch.items[i].id)
            assertEquals(batch.items[i].name, offer.batch.items[i].name)
            assertEquals(batch.items[i].sizeBytes, offer.batch.items[i].sizeBytes)
        }
    }
}
