package dev.glide.app.ui.transfer

import dev.glide.core.coordinator.ItemPhase
import dev.glide.core.coordinator.ItemTransferState
import dev.glide.core.coordinator.SessionPhase
import dev.glide.core.coordinator.SessionState
import dev.glide.core.domain.BatchId
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferFailure
import dev.glide.core.domain.TransferItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferUiStateTest {

    private val sessionUuid = java.util.UUID.randomUUID().toString()
    private val sessionId = SessionId(sessionUuid)
    private val itemId = ItemId(java.util.UUID.randomUUID().toString())
    private val item = TransferItem(
        id = itemId,
        name = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2_000_000L
    )
    private val batch = TransferBatch(BatchId(java.util.UUID.randomUUID().toString()), listOf(item))

    @Test
    fun idleAndNegotiating_mapToConnecting() {
        val state = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = SessionPhase.Negotiating
        )
        val uiState = state.toUiState("Pixel 9")
        assertTrue(uiState is TransferUiState.Connecting)
        val connecting = uiState as TransferUiState.Connecting
        assertEquals(sessionUuid, connecting.sessionId)
        assertEquals("Pixel 9", connecting.peerName)
    }

    @Test
    fun awaitingConsent_receiver_mapsToAwaitingConsentWithBatchInfo() {
        val state = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Receiver,
            phase = SessionPhase.AwaitingConsent,
            batch = batch
        )
        val uiState = state.toUiState("Pixel 9")
        assertTrue(uiState is TransferUiState.AwaitingConsent)
        val consent = uiState as TransferUiState.AwaitingConsent
        assertEquals("photo.jpg", consent.firstItemName)
        assertEquals(2_000_000L, consent.totalBytes)
        assertEquals(1, consent.itemCount)
    }

    @Test
    fun transferringItem_mapsToTransferringWithProgressFraction() {
        val itemState = ItemTransferState(
            item = item,
            phase = ItemPhase.Streaming,
            transferredBytes = 1_000_000L
        )
        val state = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = SessionPhase.TransferringItem,
            batch = batch,
            itemStates = mapOf(itemId to itemState)
        )
        val uiState = state.toUiState("Pixel 9")
        assertTrue(uiState is TransferUiState.Transferring)
        val transferring = uiState as TransferUiState.Transferring
        assertEquals("photo.jpg", transferring.itemName)
        assertEquals(1_000_000L, transferring.transferredBytes)
        assertEquals(2_000_000L, transferring.totalBytes)
        assertEquals(0.5f, transferring.progressFraction, 0.01f)
    }

    @Test
    fun completed_mapsToCompleted() {
        val state = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Receiver,
            phase = SessionPhase.Completed,
            batch = batch
        )
        val uiState = state.toUiState("Pixel 9", committedFileKey = "committed-key-123")
        assertTrue(uiState is TransferUiState.Completed)
        val completed = uiState as TransferUiState.Completed
        assertEquals("photo.jpg", completed.itemName)
        assertEquals("committed-key-123", completed.committedFileKey)
    }

    @Test
    fun cancelled_mapsToCancelled() {
        val state = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = SessionPhase.Cancelled,
            failure = TransferFailure.UserCancelled()
        )
        val uiState = state.toUiState("Pixel 9")
        assertTrue(uiState is TransferUiState.Cancelled)
    }

    @Test
    fun failed_mapsToFailed() {
        val state = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Receiver,
            phase = SessionPhase.Failed,
            failure = TransferFailure.TransferInterrupted("Connection dropped")
        )
        val uiState = state.toUiState("Pixel 9")
        assertTrue(uiState is TransferUiState.Failed)
        val failed = uiState as TransferUiState.Failed
        assertEquals("Connection dropped", failed.message)
        assertEquals("TRANSFER_INTERRUPTED", failed.code)
    }
}
