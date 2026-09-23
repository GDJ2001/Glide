package dev.glide.core.coordinator

import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.TransferFailure
import dev.glide.core.domain.TransferProgress
import dev.glide.core.protocol.Envelope

sealed class ReducerEffect {
    data class SendControl(val envelope: Envelope) : ReducerEffect()
    data class StartBulkStream(val itemId: ItemId, val bulkRef: BulkRef) : ReducerEffect()
    data class CancelBulkStream(val bulkRef: BulkRef) : ReducerEffect()
    data class PrepareDestinationStorage(val itemId: ItemId, val sizeBytes: Long) : ReducerEffect()
    data class CommitDestinationFile(val itemId: ItemId) : ReducerEffect()
    data class DeletePartialDestination(val itemId: ItemId) : ReducerEffect()
    data class UpdateTransferProgress(val progress: TransferProgress) : ReducerEffect()
    data class NotifyTerminalOutcome(val phase: SessionPhase, val failure: TransferFailure?) : ReducerEffect()
    object CloseTransportChannel : ReducerEffect()
}
