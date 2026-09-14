package dev.glide.core.coordinator

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferFailure
import dev.glide.core.domain.TransferItem
import dev.glide.core.protocol.HelloBody
import dev.glide.core.protocol.NegotiatedSession
import dev.glide.core.protocol.OfferAssembler

enum class SessionPhase {
    Idle,
    Negotiating,
    Offering,
    AwaitingConsent,
    PreparingItem,
    TransferringItem,
    VerifyingItem,
    AwaitingReceipt,
    Completed,
    DeliveryUnconfirmed,
    Cancelled,
    Failed,
    Interrupted;

    val isTerminal: Boolean
        get() = this in setOf(Completed, DeliveryUnconfirmed, Cancelled, Failed, Interrupted)
}

enum class ItemPhase {
    Queued,
    Preparing,
    Streaming,
    Verifying,
    Committing,
    Verified,
    Failed,
    Cancelled,
    Interrupted,
    NotAttempted;

    val isTerminal: Boolean
        get() = this in setOf(Verified, Failed, Cancelled, Interrupted, NotAttempted)
}

data class ItemTransferState(
    val item: TransferItem,
    val phase: ItemPhase = ItemPhase.Queued,
    val bulkRef: BulkRef? = null,
    val transferredBytes: Long = 0L,
    val eofReceived: Boolean = false,
    val actualBytesAtEof: Long? = null,
    val transportSuccessReceived: Boolean = false,
    val actualDigest: Sha256Digest? = null,
    val expectedDigest: Sha256Digest? = null,
    val failure: TransferFailure? = null
) {
    val isVerified: Boolean get() = phase == ItemPhase.Verified
}

data class SessionState(
    val sessionId: SessionId,
    val role: DeviceRole,
    val phase: SessionPhase = SessionPhase.Idle,
    val outboundHello: HelloBody? = null,
    val negotiated: NegotiatedSession? = null,
    val batch: TransferBatch? = null,
    val offerAssembler: OfferAssembler? = null,
    val currentItemIndex: Int = 0,
    val itemStates: Map<ItemId, ItemTransferState> = emptyMap(),
    val failure: TransferFailure? = null,
    val lastActivityMonotonicMs: Long = 0L,
    val nextOutboundSeq: Long = 1L,
    val expectedInboundSeq: Long = 1L
) {
    val isTerminal: Boolean get() = phase.isTerminal

    val currentItemState: ItemTransferState?
        get() {
            val b = batch ?: return null
            if (currentItemIndex !in b.items.indices) return null
            val itemId = b.items[currentItemIndex].id
            return itemStates[itemId]
        }

    val verifiedCount: Int
        get() = itemStates.values.count { it.phase == ItemPhase.Verified }

    val totalVerifiedBytes: Long
        get() = itemStates.values.filter { it.phase == ItemPhase.Verified }.sumOf { it.item.sizeBytes }

    val unfinishedItems: List<TransferItem>
        get() {
            val b = batch ?: return emptyList()
            return b.items.filter { item ->
                val itState = itemStates[item.id]
                itState == null || itState.phase != ItemPhase.Verified
            }
        }

    val unfinishedCount: Int
        get() = unfinishedItems.size

    fun createRetryBatch(): TransferBatch? {
        val remaining = unfinishedItems
        if (remaining.isEmpty()) return null
        return TransferBatch(
            id = BatchId(java.util.UUID.randomUUID().toString()),
            items = remaining
        )
    }
}
