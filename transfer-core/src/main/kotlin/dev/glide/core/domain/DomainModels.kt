package dev.glide.core.domain

import kotlinx.serialization.Serializable

object ProtocolLimits {
    const val MAX_ITEMS_PER_BATCH: Int = 1_000
    const val MAX_ITEM_BYTES: Long = 68_719_476_736L // 64 GiB
    const val MAX_BATCH_BYTES: Long = 274_877_906_944L // 256 GiB
    const val MAX_FILENAME_CODE_POINTS: Int = 255
    const val MAX_FILENAME_UTF8_BYTES: Int = 1_024
    const val MAX_MIME_LENGTH: Int = 127
    const val MAX_CONTROL_PAYLOAD_BYTES: Int = 16_384 // 16 KiB
    const val MAX_OFFER_PAGE_ITEMS: Int = 32
    const val MAX_OFFER_METADATA_BYTES: Long = 1_048_576L // 1 MiB
}

@Serializable
data class EffectiveLimits(
    val maxItems: Int = ProtocolLimits.MAX_ITEMS_PER_BATCH,
    val maxItemBytes: Long = ProtocolLimits.MAX_ITEM_BYTES,
    val maxBatchBytes: Long = ProtocolLimits.MAX_BATCH_BYTES,
    val maxControlBytes: Int = ProtocolLimits.MAX_CONTROL_PAYLOAD_BYTES
) {
    init {
        require(maxItems in 1..ProtocolLimits.MAX_ITEMS_PER_BATCH) {
            "maxItems must be in 1..${ProtocolLimits.MAX_ITEMS_PER_BATCH}, got: $maxItems"
        }
        require(maxItemBytes in 1..ProtocolLimits.MAX_ITEM_BYTES) {
            "maxItemBytes must be in 1..${ProtocolLimits.MAX_ITEM_BYTES}, got: $maxItemBytes"
        }
        require(maxBatchBytes in 1..ProtocolLimits.MAX_BATCH_BYTES) {
            "maxBatchBytes must be in 1..${ProtocolLimits.MAX_BATCH_BYTES}, got: $maxBatchBytes"
        }
        require(maxControlBytes in 1024..ProtocolLimits.MAX_CONTROL_PAYLOAD_BYTES) {
            "maxControlBytes must be in 1024..${ProtocolLimits.MAX_CONTROL_PAYLOAD_BYTES}, got: $maxControlBytes"
        }
    }
}

@Serializable
enum class DeviceRole {
    Sender,
    Receiver
}

@Serializable
data class PeerDevice(
    val id: PeerId,
    val displayName: String
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 64) {
            "Peer displayName must be between 1 and 64 characters, got: '$displayName'"
        }
    }
}

data class SessionVerification(
    val authDigits: String,
    val promptTimeMs: Long
) {
    init {
        require(authDigits.isNotBlank() && authDigits.length in 4..16) {
            "authDigits must be between 4 and 16 characters"
        }
        require(promptTimeMs > 0) { "promptTimeMs must be positive" }
    }
}

@Serializable
data class TransferItem(
    val id: ItemId,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val digest: Sha256Digest? = null
) {
    init {
        require(name.isNotBlank() && name.codePointCount(0, name.length) <= ProtocolLimits.MAX_FILENAME_CODE_POINTS) {
            "Item name must be between 1 and ${ProtocolLimits.MAX_FILENAME_CODE_POINTS} code points"
        }
        require(name.toByteArray(Charsets.UTF_8).size <= ProtocolLimits.MAX_FILENAME_UTF8_BYTES) {
            "Item name must be at most ${ProtocolLimits.MAX_FILENAME_UTF8_BYTES} UTF-8 bytes"
        }
        require(mimeType.isNotBlank() && mimeType.length <= ProtocolLimits.MAX_MIME_LENGTH) {
            "MIME type must be non-blank and at most ${ProtocolLimits.MAX_MIME_LENGTH} characters"
        }
        require(sizeBytes in 0..ProtocolLimits.MAX_ITEM_BYTES) {
            "Item sizeBytes must be between 0 and ${ProtocolLimits.MAX_ITEM_BYTES}, got $sizeBytes"
        }
    }
}

@Serializable
data class TransferBatch(
    val id: BatchId,
    val items: List<TransferItem>
) {
    val totalBytes: Long = items.sumOf { it.sizeBytes }

    init {
        require(items.isNotEmpty()) { "TransferBatch must not be empty" }
        require(items.size <= ProtocolLimits.MAX_ITEMS_PER_BATCH) {
            "TransferBatch item count cannot exceed ${ProtocolLimits.MAX_ITEMS_PER_BATCH}, got ${items.size}"
        }
        require(totalBytes in 0..ProtocolLimits.MAX_BATCH_BYTES) {
            "TransferBatch totalBytes cannot exceed ${ProtocolLimits.MAX_BATCH_BYTES}, got $totalBytes"
        }
        val uniqueIds = items.map { it.id }.toSet()
        require(uniqueIds.size == items.size) { "TransferBatch items must have unique IDs" }
    }
}

data class TransferOffer(
    val batch: TransferBatch
)

data class TransferProgress(
    val transferredBytes: Long,
    val totalBytes: Long,
    val currentItemIndex: Int,
    val totalItems: Int
) {
    init {
        require(transferredBytes >= 0) { "transferredBytes must be non-negative" }
        require(totalBytes >= 0) { "totalBytes must be non-negative" }
        require(currentItemIndex >= 0) { "currentItemIndex must be non-negative" }
        require(totalItems >= 0) { "totalItems must be non-negative" }
    }
}
