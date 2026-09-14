package dev.glide.core.coordinator

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.MessageId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferFailure
import dev.glide.core.protocol.BatchEndBody
import dev.glide.core.protocol.BatchResultBody
import dev.glide.core.protocol.CancelBody
import dev.glide.core.protocol.HelloAckBody
import dev.glide.core.protocol.HelloBody
import dev.glide.core.protocol.ItemEndBody
import dev.glide.core.protocol.ItemReadyBody
import dev.glide.core.protocol.ItemResultBody
import dev.glide.core.protocol.ItemStartBody
import dev.glide.core.protocol.OfferBeginBody
import dev.glide.core.protocol.OfferDecisionBody
import dev.glide.core.protocol.OfferEndBody
import dev.glide.core.protocol.OfferPageBody

sealed class ReducerEvent {
    abstract val timestampMs: Long

    data class StartNegotiation(
        val ephemeralPeerId: String,
        val displayName: String,
        override val timestampMs: Long
    ) : ReducerEvent()

    data class SubmitOffer(
        val batch: TransferBatch,
        override val timestampMs: Long
    ) : ReducerEvent()

    data class SubmitOfferDecision(
        val accepted: Boolean,
        val rejectionCode: String? = null,
        override val timestampMs: Long
    ) : ReducerEvent()

    data class RequestCancel(
        val code: String = "USER_CANCELLED",
        override val timestampMs: Long
    ) : ReducerEvent()

    data class HelloReceived(
        val hello: HelloBody,
        val messageId: MessageId,
        val seq: Long,
        override val timestampMs: Long
    ) : ReducerEvent()

    data class HelloAckReceived(
        val ack: HelloAckBody,
        val messageId: MessageId,
        val seq: Long,
        override val timestampMs: Long
    ) : ReducerEvent()

    data class OfferBeginReceived(
        val begin: OfferBeginBody,
        val messageId: MessageId,
        val seq: Long,
        override val timestampMs: Long
    ) : ReducerEvent()

    data class OfferPageReceived(
        val page: OfferPageBody,
        val messageId: MessageId,
        val seq: Long,
        override val timestampMs: Long
    ) : ReducerEvent()

    data class OfferEndReceived(
        val end: OfferEndBody,
        val messageId: MessageId,
        val seq: Long,
        override val timestampMs: Long
    ) : ReducerEvent()

    data class OfferDecisionReceived(
        val decision: OfferDecisionBody,
        val messageId: MessageId,
        val seq: Long,
        override val timestampMs: Long,
        val bulkRef: BulkRef? = null
    ) : ReducerEvent()

    data class ItemStartReceived(
        val start: ItemStartBody,
        val messageId: MessageId,
        val seq: Long,
        override val timestampMs: Long
    ) : ReducerEvent()

    data class ItemReadyReceived(
        val ready: ItemReadyBody,
        val messageId: MessageId,
        val seq: Long,
        override val timestampMs: Long
    ) : ReducerEvent()

    data class ItemEndReceived(
        val end: ItemEndBody,
        val messageId: MessageId,
        val seq: Long,
        override val timestampMs: Long
    ) : ReducerEvent()

    data class ItemResultReceived(
        val result: ItemResultBody,
        val messageId: MessageId,
        val seq: Long,
        override val timestampMs: Long,
        val bulkRef: BulkRef? = null
    ) : ReducerEvent()

    data class BatchEndReceived(
        val batchEnd: BatchEndBody,
        val messageId: MessageId,
        val seq: Long,
        override val timestampMs: Long
    ) : ReducerEvent()

    data class BatchResultReceived(
        val batchResult: BatchResultBody,
        val messageId: MessageId,
        val seq: Long,
        override val timestampMs: Long
    ) : ReducerEvent()

    data class CancelReceived(
        val cancel: CancelBody,
        val messageId: MessageId,
        val seq: Long,
        override val timestampMs: Long
    ) : ReducerEvent()

    data class StreamProgress(
        val itemId: ItemId,
        val bytesTransferred: Long,
        override val timestampMs: Long
    ) : ReducerEvent()

    data class StreamEof(
        val itemId: ItemId,
        val actualBytes: Long,
        val digest: Sha256Digest,
        override val timestampMs: Long
    ) : ReducerEvent()

    data class TransportPayloadSuccess(
        val itemId: ItemId,
        val bulkRef: BulkRef,
        override val timestampMs: Long
    ) : ReducerEvent()

    data class LocalCommitSuccess(
        val itemId: ItemId,
        override val timestampMs: Long
    ) : ReducerEvent()

    data class TransportFailure(
        val failure: dev.glide.core.domain.TransferFailure,
        override val timestampMs: Long
    ) : ReducerEvent()

    data class Timeout(
        val phase: String,
        override val timestampMs: Long
    ) : ReducerEvent()
}
