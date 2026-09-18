package dev.glide.core.protocol

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.MessageId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferItem
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumeOfferMetadataTest {

    private val sessionId =
        SessionId(
            "11111111-1111-4111-8111-111111111111"
        )
    private val batchId =
        BatchId(
            "22222222-2222-4222-8222-222222222222"
        )
    private val itemId =
        ItemId(
            "33333333-3333-4333-8333-333333333333"
        )
    private val messageId =
        MessageId(
            "44444444-4444-4444-8444-444444444444"
        )
    private val root =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun resumeMetadata_roundTripsAndSurvivesPaginationAssembly() {
        val batch =
            TransferBatch(
                id = batchId,
                items =
                    listOf(
                        TransferItem(
                            id = itemId,
                            name = "resume.bin",
                            mimeType =
                                "application/octet-stream",
                            sizeBytes =
                                2_097_152L,
                            resumeChunkSize =
                                1_048_576,
                            resumeChunkRootHashHex =
                                root
                        )
                    )
            )

        val paged =
            OfferPaginator.paginate(
                batch
            )
        val dto =
            paged.pages
                .single()
                .items
                .single()
        assertEquals(
            root,
            dto.rootHash
        )
        assertEquals(
            1_048_576,
            dto.chunkSize
        )

        val encoded =
            ProtocolCodec.encode(
                Envelope(
                    v = 1,
                    type =
                        MessageTypes
                            .OFFER_PAGE,
                    sessionId =
                        sessionId,
                    messageId =
                        messageId,
                    seq = 1L,
                    body =
                        ProtocolCodec
                            .encodeBody(
                                paged.pages
                                    .single()
                            )
                )
            )
        val decoded =
            ProtocolCodec.decode(
                encoded
            )
        val decodedPage:
            OfferPageBody =
            ProtocolCodec.decodeBody(
                decoded
            )

        val assembler =
            OfferAssembler(
                paged.begin
            )
        assembler.addPage(
            decodedPage
        )
        val assembled =
            assembler.finish(
                paged.end
            )
        val item =
            assembled.batch
                .items
                .single()

        assertEquals(
            root,
            item.resumeChunkRootHashHex
        )
        assertEquals(
            1_048_576,
            item.resumeChunkSize
        )
    }

    @Test
    fun ordinaryOffer_omitsResumeMetadata() {
        val item =
            OfferPaginator.paginate(
                TransferBatch(
                    batchId,
                    listOf(
                        TransferItem(
                            itemId,
                            "plain.bin",
                            "application/octet-stream",
                            4L
                        )
                    )
                )
            ).pages
                .single()
                .items
                .single()

        assertNull(item.rootHash)
        assertNull(item.chunkSize)
    }

    @Test(
        expected =
            ProtocolViolationException::class
    )
    fun rootWithoutChunkSize_isRejected() {
        decodeInvalid(
            rootHash = root,
            chunkSize = null
        )
    }

    @Test(
        expected =
            ProtocolViolationException::class
    )
    fun uppercaseRoot_isRejected() {
        decodeInvalid(
            rootHash =
                root.uppercase(),
            chunkSize = 1024
        )
    }

    @Test(
        expected =
            ProtocolViolationException::class
    )
    fun oversizedChunkSize_isRejected() {
        decodeInvalid(
            rootHash = root,
            chunkSize =
                16 * 1024 * 1024 +
                    1
        )
    }

    private fun decodeInvalid(
        rootHash: String?,
        chunkSize: Int?
    ) {
        val item =
            buildJsonObject {
                put(
                    "itemId",
                    itemId.value
                )
                put(
                    "name",
                    "resume.bin"
                )
                put(
                    "mime",
                    "application/octet-stream"
                )
                put(
                    "sizeBytes",
                    "1048576"
                )
                if (rootHash != null) {
                    put(
                        "rootHash",
                        rootHash
                    )
                }
                if (chunkSize != null) {
                    put(
                        "chunkSize",
                        chunkSize
                    )
                }
            }
        val body =
            buildJsonObject {
                put(
                    "batchId",
                    batchId.value
                )
                put(
                    "pageIndex",
                    0
                )
                put(
                    "items",
                    buildJsonArray {
                        add(item)
                    }
                )
            }

        ProtocolCodec.decode(
            ProtocolCodec.encode(
                Envelope(
                    1,
                    MessageTypes.OFFER_PAGE,
                    sessionId,
                    messageId,
                    1L,
                    body
                )
            )
        )
    }
}
