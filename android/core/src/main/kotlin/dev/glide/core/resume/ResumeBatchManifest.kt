package dev.glide.core.resume

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.TransferBatch
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.security.MessageDigest

/** The ordered subset accepted by the receiver, independent of local paths and progress. */
@Serializable
data class ResumeBatchManifest(
    val sessionId: SessionId,
    val batchId: BatchId,
    val senderFingerprint: Sha256Digest,
    val receiverFingerprint: Sha256Digest,
    val items: List<Item>
) {
    @Serializable
    data class Item(
        val id: ItemId,
        val name: String,
        val relativePath: String?,
        val sizeBytes: Long,
        val chunkSize: Int,
        val rootHash: Sha256Digest?,
        val digest: Sha256Digest
    ) {
        init {
            require(name.isNotBlank() && name.encodeToByteArray().size <= ProtocolLimits.MAX_FILENAME_UTF8_BYTES)
            require(relativePath == null ||
                (relativePath.isNotBlank() && relativePath.length <= ProtocolLimits.MAX_RELATIVE_PATH_LENGTH))
            require(sizeBytes in 0..ProtocolLimits.MAX_ITEM_BYTES)
            require(if (sizeBytes == 0L) chunkSize == 0 && rootHash == null
                else chunkSize in 1..16_777_216 && rootHash != null)
        }
    }

    init {
        require(senderFingerprint != receiverFingerprint)
        require(items.size in 1..ProtocolLimits.MAX_ITEMS_PER_BATCH)
        require(items.map { it.id }.toSet().size == items.size)
        require(items.sumOf { it.sizeBytes } <= ProtocolLimits.MAX_BATCH_BYTES)
    }

    /** A commit receipt may advance exactly one item, or repeat an earlier verified receipt. */
    fun recordVerifiedItem(verifiedCount: Int, id: ItemId, sizeBytes: Long, digest: Sha256Digest): Int {
        require(verifiedCount in 0..items.size)
        val index = items.indexOfFirst { it.id == id }
        require(index in 0..verifiedCount) { "Resume receipt is not in accepted batch order" }
        val item = items[index]
        require(item.sizeBytes == sizeBytes && item.digest == digest) {
            "Resume receipt does not match the accepted item"
        }
        return maxOf(verifiedCount, index + 1)
    }

    companion object {
        fun fromAcceptedOffer(
            sessionId: SessionId,
            sender: Sha256Digest,
            receiver: Sha256Digest,
            batch: TransferBatch
        ): ResumeBatchManifest? {
            if (batch.items.any { it.digest == null }) return null
            return ResumeBatchManifest(sessionId, batch.id, sender, receiver, batch.items.map { item ->
                Item(
                    item.id, item.name, item.relativePath, item.sizeBytes,
                    item.resumeChunkSize ?: 0,
                    item.resumeChunkRootHashHex?.let(::Sha256Digest),
                    requireNotNull(item.digest)
                )
            })
        }
    }

    /** SHA-256 of length-prefixed UTF-8 fields and signed big-endian integers. */
    fun digest(): Sha256Digest {
        val hash = MessageDigest.getInstance("SHA-256")
        fun number(value: Int) = hash.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
        fun field(value: String?) {
            val bytes = value?.encodeToByteArray(throwOnInvalidSequence = true)
            number(bytes?.size ?: -1)
            if (bytes != null) hash.update(bytes)
        }
        field("glide/resume-batch-v1")
        field(sessionId.value)
        field(batchId.value)
        field(senderFingerprint.value)
        field(receiverFingerprint.value)
        number(items.size)
        for (item in items) {
            field(item.id.value)
            field(item.name)
            field(item.relativePath)
            hash.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(item.sizeBytes).array())
            number(item.chunkSize)
            field(item.rootHash?.value)
            field(item.digest.value)
        }
        return Sha256Digest(hash.digest().joinToString("") { "%02x".format(it) })
    }
}
