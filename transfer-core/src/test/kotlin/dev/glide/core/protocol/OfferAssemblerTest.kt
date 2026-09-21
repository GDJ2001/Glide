package dev.glide.core.protocol

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.EffectiveLimits
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.ProtocolLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class OfferAssemblerTest {

    private val validBatchId = BatchId("d0000000-0000-4000-8000-000000000001")
    private val item1 = ItemId("e0000000-0000-4000-8000-000000000001")
    private val item2 = ItemId("e0000000-0000-4000-8000-000000000002")
    private val item3 = ItemId("e0000000-0000-4000-8000-000000000003")

    @Test
    fun singlePageOffer_assemblesSuccessfully() {
        val begin = OfferBeginBody(
            batchId = validBatchId,
            itemCount = 2,
            totalBytes = "15360",
            pageCount = 1
        )
        val assembler = OfferAssembler(begin)

        val page = OfferPageBody(
            batchId = validBatchId,
            pageIndex = 0,
            items = listOf(
                OfferItemDto(item1, "file1.txt", "text/plain", "10240"),
                OfferItemDto(item2, "file2.png", "image/png", "5120")
            )
        )
        assembler.addPage(page)

        val end = OfferEndBody(batchId = validBatchId)
        val offer = assembler.finish(end)

        assertEquals(validBatchId, offer.batch.id)
        assertEquals(2, offer.batch.items.size)
        assertEquals(15360L, offer.batch.totalBytes)
        assertEquals("file1.txt", offer.batch.items[0].name)
        assertEquals("file2.png", offer.batch.items[1].name)
    }

    @Test
    fun multiPageOffer_assemblesSuccessfully() {
        val begin = OfferBeginBody(
            batchId = validBatchId,
            itemCount = 3,
            totalBytes = "3000",
            pageCount = 2
        )
        val assembler = OfferAssembler(begin)

        val page0 = OfferPageBody(
            batchId = validBatchId,
            pageIndex = 0,
            items = listOf(
                OfferItemDto(item1, "file1.txt", "text/plain", "1000"),
                OfferItemDto(item2, "file2.txt", "text/plain", "1000")
            )
        )
        assembler.addPage(page0)

        val page1 = OfferPageBody(
            batchId = validBatchId,
            pageIndex = 1,
            items = listOf(
                OfferItemDto(item3, "file3.txt", "text/plain", "1000")
            )
        )
        assembler.addPage(page1)

        val end = OfferEndBody(batchId = validBatchId)
        val offer = assembler.finish(end)

        assertEquals(3, offer.batch.items.size)
        assertEquals(3000L, offer.batch.totalBytes)
    }

    @Test
    fun resumeMetadata_withoutNegotiatedCapability_failsUnsupportedProtocol() {
        val begin =
            OfferBeginBody(
                batchId = validBatchId,
                itemCount = 1,
                totalBytes = "1048576",
                pageCount = 1
            )
        val assembler =
            OfferAssembler(begin)
        val page =
            OfferPageBody(
                batchId = validBatchId,
                pageIndex = 0,
                items =
                    listOf(
                        OfferItemDto(
                            itemId = item1,
                            name = "resume.bin",
                            mime =
                                "application/octet-stream",
                            sizeBytes = "1048576",
                            rootHash =
                                "c".repeat(64),
                            chunkSize =
                                1024 * 1024
                        )
                    )
            )

        try {
            assembler.addPage(page)
            fail(
                "Expected unnegotiated resume metadata rejection"
            )
        } catch (
            error: ProtocolViolationException
        ) {
            assertEquals(
                "UNSUPPORTED_PROTOCOL",
                error.failure.code
            )
        }
    }

    @Test
    fun outOfOrderPage_failsProtocolOrder() {
        val begin = OfferBeginBody(
            batchId = validBatchId,
            itemCount = 2,
            totalBytes = "2000",
            pageCount = 2
        )
        val assembler = OfferAssembler(begin)

        // Arrives page 1 before page 0
        val page1 = OfferPageBody(
            batchId = validBatchId,
            pageIndex = 1,
            items = listOf(OfferItemDto(item1, "file1.txt", "text/plain", "1000"))
        )

        try {
            assembler.addPage(page1)
            fail("Expected ProtocolOrder failure for out-of-order page")
        } catch (e: ProtocolViolationException) {
            assertEquals("PROTOCOL_ORDER", e.failure.code)
        }
    }

    @Test
    fun duplicatePage_failsProtocolOrder() {
        val begin = OfferBeginBody(
            batchId = validBatchId,
            itemCount = 2,
            totalBytes = "2000",
            pageCount = 2
        )
        val assembler = OfferAssembler(begin)

        val page0 = OfferPageBody(
            batchId = validBatchId,
            pageIndex = 0,
            items = listOf(OfferItemDto(item1, "file1.txt", "text/plain", "1000"))
        )
        assembler.addPage(page0)

        // Repeat page 0
        try {
            assembler.addPage(page0)
            fail("Expected ProtocolOrder failure for duplicate page")
        } catch (e: ProtocolViolationException) {
            assertEquals("PROTOCOL_ORDER", e.failure.code)
        }
    }

    @Test
    fun duplicateItemId_failsMalformedMessage() {
        val begin = OfferBeginBody(
            batchId = validBatchId,
            itemCount = 2,
            totalBytes = "2000",
            pageCount = 1
        )
        val assembler = OfferAssembler(begin)

        val page0 = OfferPageBody(
            batchId = validBatchId,
            pageIndex = 0,
            items = listOf(
                OfferItemDto(item1, "file1.txt", "text/plain", "1000"),
                OfferItemDto(item1, "file2.txt", "text/plain", "1000") // Duplicate itemId
            )
        )

        try {
            assembler.addPage(page0)
            fail("Expected MalformedMessage failure for duplicate itemId")
        } catch (e: ProtocolViolationException) {
            assertEquals("MALFORMED_MESSAGE", e.failure.code)
        }
    }

    @Test
    fun prematureOfferEnd_failsProtocolOrder() {
        val begin = OfferBeginBody(
            batchId = validBatchId,
            itemCount = 2,
            totalBytes = "2000",
            pageCount = 2
        )
        val assembler = OfferAssembler(begin)

        val page0 = OfferPageBody(
            batchId = validBatchId,
            pageIndex = 0,
            items = listOf(OfferItemDto(item1, "file1.txt", "text/plain", "1000"))
        )
        assembler.addPage(page0)

        // Send OFFER_END before page 1
        val end = OfferEndBody(batchId = validBatchId)
        try {
            assembler.finish(end)
            fail("Expected ProtocolOrder failure for premature OFFER_END")
        } catch (e: ProtocolViolationException) {
            assertEquals("PROTOCOL_ORDER", e.failure.code)
        }
    }

    @Test
    fun totalBytesMismatch_failsMalformedMessage() {
        val begin = OfferBeginBody(
            batchId = validBatchId,
            itemCount = 1,
            totalBytes = "5000", // Declared 5000
            pageCount = 1
        )
        val assembler = OfferAssembler(begin)

        val page0 = OfferPageBody(
            batchId = validBatchId,
            pageIndex = 0,
            items = listOf(OfferItemDto(item1, "file1.txt", "text/plain", "4000")) // Actual 4000
        )
        assembler.addPage(page0)

        val end = OfferEndBody(batchId = validBatchId)
        try {
            assembler.finish(end)
            fail("Expected MalformedMessage failure for byte sum mismatch")
        } catch (e: ProtocolViolationException) {
            assertEquals("MALFORMED_MESSAGE", e.failure.code)
        }
    }

    @Test
    fun itemCountMismatch_failsMalformedMessage() {
        val begin = OfferBeginBody(
            batchId = validBatchId,
            itemCount = 2, // Declared 2 items
            totalBytes = "2000",
            pageCount = 1
        )
        val assembler = OfferAssembler(begin)

        val page0 = OfferPageBody(
            batchId = validBatchId,
            pageIndex = 0,
            items = listOf(OfferItemDto(item1, "file1.txt", "text/plain", "2000")) // Actual 1 item
        )
        assembler.addPage(page0)

        val end = OfferEndBody(batchId = validBatchId)
        try {
            assembler.finish(end)
            fail("Expected MalformedMessage failure for item count mismatch")
        } catch (e: ProtocolViolationException) {
            assertEquals("MALFORMED_MESSAGE", e.failure.code)
        }
    }

    @Test
    fun batchIdMismatch_failsMalformedMessage() {
        val begin = OfferBeginBody(
            batchId = validBatchId,
            itemCount = 1,
            totalBytes = "1000",
            pageCount = 1
        )
        val assembler = OfferAssembler(begin)

        val wrongBatchId = BatchId("d0000000-0000-4000-8000-000000000002")
        val page0 = OfferPageBody(
            batchId = wrongBatchId,
            pageIndex = 0,
            items = listOf(OfferItemDto(item1, "file1.txt", "text/plain", "1000"))
        )

        try {
            assembler.addPage(page0)
            fail("Expected MalformedMessage failure for batchId mismatch")
        } catch (e: ProtocolViolationException) {
            assertEquals("MALFORMED_MESSAGE", e.failure.code)
        }
    }

    @Test
    fun itemExceedsMaxItemBytes_failsLimitExceeded() {
        val begin = OfferBeginBody(
            batchId = validBatchId,
            itemCount = 1,
            totalBytes = "2000",
            pageCount = 1
        )
        val limits = EffectiveLimits(maxItemBytes = 1000L)
        val assembler = OfferAssembler(begin, limits)

        val page0 = OfferPageBody(
            batchId = validBatchId,
            pageIndex = 0,
            items = listOf(OfferItemDto(item1, "file1.txt", "text/plain", "1500"))
        )

        try {
            assembler.addPage(page0)
            fail("Expected LimitExceeded for item exceeding maxItemBytes")
        } catch (e: ProtocolViolationException) {
            assertEquals("LIMIT_EXCEEDED", e.failure.code)
        }
    }
}
