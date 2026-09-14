package dev.glide.core

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

class DomainValueTest {

    private val validUuid = "123e4567-e89b-42d3-a456-426614174000"
    private val validDigest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun sessionId_validCanonicalUuid_succeeds() {
        val session = SessionId(validUuid)
        assertEquals(validUuid, session.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sessionId_uppercaseUuid_fails() {
        SessionId("123E4567-E89B-42D3-A456-426614174000")
    }

    @Test(expected = IllegalArgumentException::class)
    fun sessionId_invalidFormat_fails() {
        SessionId("not-a-uuid")
    }

    @Test
    fun batchId_validUuid_succeeds() {
        val batchId = BatchId(validUuid)
        assertEquals(validUuid, batchId.value)
    }

    @Test
    fun itemId_validUuid_succeeds() {
        val itemId = ItemId(validUuid)
        assertEquals(validUuid, itemId.value)
    }

    @Test
    fun sha256Digest_valid64Hex_succeeds() {
        val digest = Sha256Digest(validDigest)
        assertEquals(validDigest, digest.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sha256Digest_uppercase_fails() {
        Sha256Digest("E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855")
    }

    @Test(expected = IllegalArgumentException::class)
    fun sha256Digest_shortLength_fails() {
        Sha256Digest("e3b0c442")
    }

    @Test(expected = IllegalArgumentException::class)
    fun sha256Digest_nonHex_fails() {
        Sha256Digest("g3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    @Test
    fun sha256Digest_fromByteArray_succeeds() {
        val rawBytes = ByteArray(32) { it.toByte() }
        val digest = Sha256Digest.fromByteArray(rawBytes)
        assertEquals(64, digest.value.length)
        assertEquals("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", digest.value)
    }

    @Test
    fun sha256Digest_compute_emptyBytes_matchesStandardSha256() {
        val digest = Sha256Digest.compute(ByteArray(0))
        assertEquals(validDigest, digest.value)
    }

    @Test
    fun transferItem_validProperties_succeeds() {
        val item = TransferItem(
            id = ItemId(validUuid),
            name = "document.pdf",
            mimeType = "application/pdf",
            sizeBytes = 1024L,
            digest = Sha256Digest(validDigest)
        )
        assertEquals("document.pdf", item.name)
        assertEquals(1024L, item.sizeBytes)
        assertNotNull(item.digest)
    }

    @Test(expected = IllegalArgumentException::class)
    fun transferItem_blankName_fails() {
        TransferItem(
            id = ItemId(validUuid),
            name = "   ",
            mimeType = "application/pdf",
            sizeBytes = 1024L
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun transferItem_negativeSize_fails() {
        TransferItem(
            id = ItemId(validUuid),
            name = "file.txt",
            mimeType = "text/plain",
            sizeBytes = -1L
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun transferItem_oversizedItem_fails() {
        TransferItem(
            id = ItemId(validUuid),
            name = "huge.bin",
            mimeType = "application/octet-stream",
            sizeBytes = ProtocolLimits.MAX_ITEM_BYTES + 1
        )
    }

    @Test
    fun transferBatch_calculatesTotalBytesCorrectly() {
        val item1 = TransferItem(
            id = ItemId("00000000-0000-4000-8000-000000000001"),
            name = "file1.txt",
            mimeType = "text/plain",
            sizeBytes = 100L
        )
        val item2 = TransferItem(
            id = ItemId("00000000-0000-4000-8000-000000000002"),
            name = "file2.txt",
            mimeType = "text/plain",
            sizeBytes = 250L
        )
        val batch = TransferBatch(
            id = BatchId(validUuid),
            items = listOf(item1, item2)
        )
        assertEquals(350L, batch.totalBytes)
        assertEquals(2, batch.items.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun transferBatch_emptyItems_fails() {
        TransferBatch(
            id = BatchId(validUuid),
            items = emptyList()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun transferBatch_duplicateItemIds_fails() {
        val item1 = TransferItem(
            id = ItemId("00000000-0000-4000-8000-000000000001"),
            name = "file1.txt",
            mimeType = "text/plain",
            sizeBytes = 100L
        )
        val item2 = TransferItem(
            id = ItemId("00000000-0000-4000-8000-000000000001"), // duplicate id
            name = "file2.txt",
            mimeType = "text/plain",
            sizeBytes = 200L
        )
        TransferBatch(
            id = BatchId(validUuid),
            items = listOf(item1, item2)
        )
    }

    @Test
    fun peerDevice_validDisplayName_succeeds() {
        val peer = PeerDevice(id = PeerId("peer-123"), displayName = "Pixel Phone")
        assertEquals("Pixel Phone", peer.displayName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun peerDevice_blankDisplayName_fails() {
        PeerDevice(id = PeerId("peer-123"), displayName = "")
    }
}
