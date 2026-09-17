package dev.glide.core.folder

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferItem
import dev.glide.core.protocol.Envelope
import dev.glide.core.protocol.MessageTypes
import dev.glide.core.protocol.NegotiationConstants
import dev.glide.core.protocol.NegotiationValidator
import dev.glide.core.protocol.OfferAssembler
import dev.glide.core.protocol.OfferItemDto
import dev.glide.core.protocol.OfferPageBody
import dev.glide.core.protocol.OfferPaginator
import dev.glide.core.protocol.ProtocolCodec
import dev.glide.core.protocol.ProtocolViolationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SafeFolderTransferProtocolTest {

    private val sessionId = SessionId("00000000-0000-4000-8000-000000000001")
    private val senderPeerId = "00000000-0000-4000-8000-000000000002"
    private val receiverPeerId = "00000000-0000-4000-8000-000000000003"

    @Test
    fun `negotiation includes folder-v1 when both peers support it`() {
        val hello = NegotiationValidator.createHello(
            role = DeviceRole.Sender,
            ephemeralPeerId = senderPeerId,
            displayName = "Sender Device",
            optionalCapabilities = listOf(NegotiationConstants.CAPABILITY_FOLDER_V1)
        )

        val helloAck = NegotiationValidator.createHelloAck(
            hello = hello,
            ephemeralPeerId = receiverPeerId,
            displayName = "Receiver Device",
            supportedOptionalCapabilities = setOf(NegotiationConstants.CAPABILITY_FOLDER_V1)
        )

        val negotiated = NegotiationValidator.validateHelloAck(hello, helloAck)
        assertTrue(negotiated.capabilities.contains(NegotiationConstants.CAPABILITY_FOLDER_V1))
        assertTrue(negotiated.capabilities.contains(NegotiationConstants.CAPABILITY_STREAM_V1))
        assertTrue(negotiated.capabilities.contains(NegotiationConstants.CAPABILITY_SHA256))
    }

    @Test
    fun `negotiation cleanly falls back to flat stream when receiver does not support folder-v1`() {
        val hello = NegotiationValidator.createHello(
            role = DeviceRole.Sender,
            ephemeralPeerId = senderPeerId,
            displayName = "Sender Device",
            optionalCapabilities = listOf(NegotiationConstants.CAPABILITY_FOLDER_V1)
        )

        val helloAck = NegotiationValidator.createHelloAck(
            hello = hello,
            ephemeralPeerId = receiverPeerId,
            displayName = "Receiver Device",
            supportedOptionalCapabilities = emptySet() // Receiver does NOT support folder-v1
        )

        val negotiated = NegotiationValidator.validateHelloAck(hello, helloAck)
        assertFalse(negotiated.capabilities.contains(NegotiationConstants.CAPABILITY_FOLDER_V1))
        assertTrue(negotiated.capabilities.contains(NegotiationConstants.CAPABILITY_STREAM_V1))
        assertTrue(negotiated.capabilities.contains(NegotiationConstants.CAPABILITY_SHA256))
    }

    @Test
    fun `valid relativePath is encoded and decoded in OfferPage`() {
        val batchId = BatchId(UUID.randomUUID().toString())
        val itemId = ItemId(UUID.randomUUID().toString())
        val page = OfferPageBody(
            batchId = batchId,
            pageIndex = 0,
            items = listOf(
                OfferItemDto(
                    itemId = itemId,
                    name = "vacation.jpg",
                    mime = "image/jpeg",
                    sizeBytes = "1048576",
                    relativePath = "Summer2026/Photos/vacation.jpg"
                )
            )
        )

        val envelope = Envelope(
            v = 1,
            type = MessageTypes.OFFER_PAGE,
            sessionId = sessionId,
            messageId = dev.glide.core.domain.MessageId(UUID.randomUUID().toString()),
            seq = 1,
            body = ProtocolCodec.encodeBody(page)
        )

        val bytes = ProtocolCodec.encode(envelope)
        val decodedEnvelope = ProtocolCodec.decode(bytes)
        val decodedPage = ProtocolCodec.decodeBody<OfferPageBody>(decodedEnvelope)

        assertEquals(1, decodedPage.items.size)
        val item = decodedPage.items[0]
        assertEquals("vacation.jpg", item.name)
        assertEquals("Summer2026/Photos/vacation.jpg", item.relativePath)
    }

    @Test
    fun `malicious relativePath with path traversal is rejected during decode`() {
        val batchId = BatchId(UUID.randomUUID().toString())
        val itemId = ItemId(UUID.randomUUID().toString())
        val maliciousPage = OfferPageBody(
            batchId = batchId,
            pageIndex = 0,
            items = listOf(
                OfferItemDto(
                    itemId = itemId,
                    name = "passwd",
                    mime = "text/plain",
                    sizeBytes = "100",
                    relativePath = "../../etc/passwd"
                )
            )
        )

        val envelope = Envelope(
            v = 1,
            type = MessageTypes.OFFER_PAGE,
            sessionId = sessionId,
            messageId = dev.glide.core.domain.MessageId(UUID.randomUUID().toString()),
            seq = 1,
            body = ProtocolCodec.encodeBody(maliciousPage)
        )

        val bytes = ProtocolCodec.encode(envelope)
        assertThrows(ProtocolViolationException::class.java) {
            ProtocolCodec.decode(bytes)
        }
    }

    @Test
    fun `malicious relativePath with Windows drive letter is rejected during decode`() {
        val batchId = BatchId(UUID.randomUUID().toString())
        val itemId = ItemId(UUID.randomUUID().toString())
        val maliciousPage = OfferPageBody(
            batchId = batchId,
            pageIndex = 0,
            items = listOf(
                OfferItemDto(
                    itemId = itemId,
                    name = "boot.ini",
                    mime = "text/plain",
                    sizeBytes = "100",
                    relativePath = "C:/boot.ini"
                )
            )
        )

        val envelope = Envelope(
            v = 1,
            type = MessageTypes.OFFER_PAGE,
            sessionId = sessionId,
            messageId = dev.glide.core.domain.MessageId(UUID.randomUUID().toString()),
            seq = 1,
            body = ProtocolCodec.encodeBody(maliciousPage)
        )

        val bytes = ProtocolCodec.encode(envelope)
        assertThrows(ProtocolViolationException::class.java) {
            ProtocolCodec.decode(bytes)
        }
    }

    @Test
    fun `malicious relativePath with DOS reserved name is rejected during decode`() {
        val batchId = BatchId(UUID.randomUUID().toString())
        val itemId = ItemId(UUID.randomUUID().toString())
        val maliciousPage = OfferPageBody(
            batchId = batchId,
            pageIndex = 0,
            items = listOf(
                OfferItemDto(
                    itemId = itemId,
                    name = "NUL.txt",
                    mime = "text/plain",
                    sizeBytes = "100",
                    relativePath = "nested/NUL.txt"
                )
            )
        )

        val envelope = Envelope(
            v = 1,
            type = MessageTypes.OFFER_PAGE,
            sessionId = sessionId,
            messageId = dev.glide.core.domain.MessageId(UUID.randomUUID().toString()),
            seq = 1,
            body = ProtocolCodec.encodeBody(maliciousPage)
        )

        val bytes = ProtocolCodec.encode(envelope)
        assertThrows(ProtocolViolationException::class.java) {
            ProtocolCodec.decode(bytes)
        }
    }

    @Test
    fun `roundtrip pagination and assembly preserves relativePath on all items`() {
        val batchId = BatchId(UUID.randomUUID().toString())
        val items = listOf(
            TransferItem(
                id = ItemId(UUID.randomUUID().toString()),
                name = "report.pdf",
                mimeType = "application/pdf",
                sizeBytes = 2048,
                relativePath = "Work/Q3/report.pdf"
            ),
            TransferItem(
                id = ItemId(UUID.randomUUID().toString()),
                name = "standalone.txt",
                mimeType = "text/plain",
                sizeBytes = 512,
                relativePath = null // Flat item mixed in batch
            ),
            TransferItem(
                id = ItemId(UUID.randomUUID().toString()),
                name = "diagram.png",
                mimeType = "image/png",
                sizeBytes = 4096,
                relativePath = "Work/Q3/Assets/diagram.png"
            )
        )

        val batch = TransferBatch(id = batchId, items = items)
        val pagedOffer = OfferPaginator.paginate(batch)

        assertEquals(1, pagedOffer.pages.size)
        val assembler = OfferAssembler(pagedOffer.begin)
        for (page in pagedOffer.pages) {
            assembler.addPage(page)
        }
        val assembledOffer = assembler.finish(pagedOffer.end)

        assertEquals(3, assembledOffer.batch.items.size)
        val item1 = assembledOffer.batch.items[0]
        assertEquals("report.pdf", item1.name)
        assertEquals("Work/Q3/report.pdf", item1.relativePath)

        val item2 = assembledOffer.batch.items[1]
        assertEquals("standalone.txt", item2.name)
        assertNull(item2.relativePath)

        val item3 = assembledOffer.batch.items[2]
        assertEquals("diagram.png", item3.name)
        assertEquals("Work/Q3/Assets/diagram.png", item3.relativePath)
    }
}
