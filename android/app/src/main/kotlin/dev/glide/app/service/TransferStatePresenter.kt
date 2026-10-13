package dev.glide.app.service

import dev.glide.app.ui.transfer.TransferUiState
import dev.glide.app.ui.transfer.toUiState
import dev.glide.app.util.BatchTransferRateTracker
import dev.glide.core.coordinator.SessionPhase
import dev.glide.core.coordinator.SessionState

/** Maps coordinator state to a bounded-rate UI stream without owning the session. */
internal class TransferStatePresenter(timeProvider: () -> Long) {
    private val rateTracker = BatchTransferRateTracker(timeProvider = timeProvider)
    private var lastPhase: SessionPhase? = null

    fun present(
        sessionState: SessionState,
        peerDisplayName: String,
        committedFileKey: String?,
        committedItemKeys: Map<String, String>,
        current: TransferUiState
    ): TransferUiState? {
        val base = sessionState.toUiState(
            peerDisplayName = peerDisplayName,
            committedFileKey = committedFileKey,
            committedItemKeys = committedItemKeys
        )
        val phaseChanged = sessionState.phase != lastPhase
        lastPhase = sessionState.phase
        if (base !is TransferUiState.Transferring) {
            rateTracker.reset()
            return base
        }

        val snapshot = rateTracker.updateProgress(
            cumulativeBatchBytes = base.transferredBytes,
            currentItemIndex = base.itemIndex,
            forceImmediate = phaseChanged
        )
        return if (snapshot.shouldEmitUiUpdate || current !is TransferUiState.Transferring) {
            base.copy(
                speedBytesPerSec = snapshot.speedBytesPerSec,
                isStalled = snapshot.isStalled
            )
        } else null
    }

    fun reset() {
        lastPhase = null
        rateTracker.reset()
    }
}
