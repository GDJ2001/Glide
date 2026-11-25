package dev.glide.core.protocol

import dev.glide.core.domain.AppTransferMetadata
import dev.glide.core.domain.BatchId
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AppOfferMetadataTest {
    @Test
    fun paginatesEncodedHintsWithoutChangingItemKeys() {
        val metadata = AppTransferMetadata(
            "dev.glide.example", 20, "2.0".repeat(40), true, "ab".repeat(32),
            listOf("arm64-v8a", "armeabi-v7a")
        )
        val batch = TransferBatch(
            BatchId(UUID.randomUUID().toString()),
            List(10) {
                TransferItem(
                    ItemId(UUID.randomUUID().toString()), "Glide.apks",
                    "application/vnd.android.apks", 10, appMetadata = metadata
                )
            }
        )
        val paged = OfferPaginator.paginate(batch)
        assertTrue(paged.pages.size > 1)
        val assembler = OfferAssembler(paged.begin)
        for (page in paged.pages) {
            val hints = page.items.associate { it.itemId to metadata }
            val extensions = AppOfferMetadata.encode(hints)
            assertNotNull(extensions)
            assertEquals(hints, AppOfferMetadata.decode(extensions, page))
            assertTrue(ProtocolCodec.encodeBody(page).toString().contains("appMeta").not())
            assembler.addPage(page, AppOfferMetadata.decode(extensions, page))
        }
        assertEquals(batch.items, assembler.finish(paged.end).batch.items)
    }

    @Test
    fun preservesResumeDigestsAndAppHintsAcrossBoundedPages() {
        val metadata = AppTransferMetadata(
            "dev.glide.example", 20, "2.0".repeat(40), true, "ab".repeat(32),
            listOf("arm64-v8a", "armeabi-v7a")
        )
        val batch = TransferBatch(
            BatchId(UUID.randomUUID().toString()),
            List(40) { index ->
                TransferItem(
                    ItemId(UUID.randomUUID().toString()), "Glide-$index.apks",
                    "application/vnd.android.apks", 10,
                    digest = Sha256Digest(index.toString(16).padStart(64, '0')),
                    resumeChunkSize = 1024, resumeChunkRootHashHex = "ab".repeat(32),
                    appMetadata = metadata.takeIf { index % 3 == 0 }
                )
            }
        )
        val paged = OfferPaginator.paginate(batch, allowResumeMetadata = true)
        val assembler = OfferAssembler(paged.begin, allowResumeMetadata = true)
        val itemsById = batch.items.associateBy { it.id }
        for (page in paged.pages) {
            val items = page.items.map { itemsById.getValue(it.itemId) }
            val extensions = checkNotNull(ResumeOfferMetadata.encodePage(items, true))
            assertTrue(extensions.toString().encodeToByteArray().size <= ProtocolCodec.MAX_EXTENSIONS_BYTES)
            assembler.addPage(page, AppOfferMetadata.decode(extensions, page), ResumeOfferMetadata.decode(extensions, page))
        }
        assertEquals(batch.items, assembler.finish(paged.end).batch.items)
    }
}
