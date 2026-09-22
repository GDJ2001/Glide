package dev.glide.app.ui.transfer

import dev.glide.app.ui.discovery.DeviceType
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferActiveResultAcceptanceTest {

    private val sessionUuid = "00000000-0000-4000-8000-000000000001"
    private val sessionId = SessionId(sessionUuid)
    private val itemId1 = ItemId("00000000-0000-4000-8000-000000000002")
    private val itemId2 = ItemId("00000000-0000-4000-8000-000000000003")

    private val item1 = TransferItem(
        id = itemId1,
        name = "design_system_tokens.pdf",
        mimeType = "application/pdf",
        sizeBytes = 10_000_000L
    )

    private val item2 = TransferItem(
        id = itemId2,
        name = "brand_assets_vector.zip",
        mimeType = "application/zip",
        sizeBytes = 20_000_000L
    )

    private val multiBatch = TransferBatch(
        id = BatchId("00000000-0000-4000-8000-000000000004"),
        items = listOf(item1, item2)
    )

    @Test
    fun transferringState_calculatesAndFormatsProgressAndThroughput() {
        val itemState = ItemTransferState(
            item = item1,
            phase = ItemPhase.Streaming,
            transferredBytes = 4_500_000L
        )
        val sessionState = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = SessionPhase.TransferringItem,
            batch = TransferBatch(BatchId("00000000-0000-4000-8000-000000000005"), listOf(item1)),
            itemStates = mapOf(itemId1 to itemState)
        )

        val uiState = sessionState.toUiState("Pixel 9 Pro")
        assertTrue(uiState is TransferUiState.Transferring)
        val transferring = (uiState as TransferUiState.Transferring).copy(
            speedBytesPerSec = 15_000_000L
        )

        assertEquals("design_system_tokens.pdf", transferring.itemName)
        assertEquals(4_500_000L, transferring.transferredBytes)
        assertEquals(10_000_000L, transferring.totalBytes)
        assertEquals(0.45f, transferring.progressFraction, 0.01f)
        assertEquals("45%", TransferFormatting.formatPercent(transferring.progressFraction))

        val speed = TransferFormatting.formatSpeed(transferring.speedBytesPerSec, transferring.isStalled)
        assertTrue(speed.contains("MB/s"))

        val eta = TransferFormatting.formatEta(
            transferring.totalBytes - transferring.transferredBytes,
            transferring.speedBytesPerSec,
            transferring.isStalled
        )
        assertTrue(eta.contains("remaining"))
    }

    @Test
    fun transferringState_multiFileBatch_displaysBatchProgressBadge() {
        val itemState = ItemTransferState(
            item = item2,
            phase = ItemPhase.Streaming,
            transferredBytes = 5_000_000L
        )
        val sessionState = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Receiver,
            phase = SessionPhase.TransferringItem,
            batch = multiBatch,
            currentItemIndex = 1,
            itemStates = mapOf(itemId2 to itemState)
        )

        val uiState = sessionState.toUiState("Galaxy Tab S9")
        assertTrue(uiState is TransferUiState.Transferring)
        val transferring = uiState as TransferUiState.Transferring

        assertEquals(2, transferring.itemCount)
        assertEquals(2, transferring.itemIndex)
        assertEquals("brand_assets_vector.zip", transferring.itemName)
        assertEquals(DeviceType.Tablet, transferring.peerDeviceType)
    }

    @Test
    fun verifyingState_displaysSha256Verification_neverClaims100PercentComplete() {
        val itemState = ItemTransferState(
            item = item1,
            phase = ItemPhase.Verifying,
            transferredBytes = 10_000_000L
        )
        val sessionState = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Receiver,
            phase = SessionPhase.VerifyingItem,
            batch = multiBatch,
            itemStates = mapOf(itemId1 to itemState)
        )

        val uiState = sessionState.toUiState("Pixel 9 Pro")
        assertTrue(uiState is TransferUiState.Verifying)
        val verifying = uiState as TransferUiState.Verifying

        assertEquals(1, verifying.itemIndex)
        assertEquals(2, verifying.itemCount)
        assertEquals("design_system_tokens.pdf", verifying.itemName)
    }

    @Test
    fun completedState_receiver_displaysStorageGuaranteeAndExportActions() {
        val sessionState = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Receiver,
            phase = SessionPhase.Completed,
            batch = multiBatch
        )

        val uiState = sessionState.toUiState("Pixel 9 Pro", committedFileKey = "committed-key-file-1")
        assertTrue(uiState is TransferUiState.Completed)
        val completed = uiState as TransferUiState.Completed

        assertEquals("committed-key-file-1", completed.committedFileKey)
        assertEquals(2, completed.itemCount)
        assertEquals(30_000_000L, completed.totalBytes)
        assertEquals(2, completed.items.size)
    }

    @Test
    fun completedState_sender_displaysDeliveryConfirmation() {
        val sessionState = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = SessionPhase.Completed,
            batch = multiBatch
        )

        val uiState = sessionState.toUiState("MacBook Air")
        assertTrue(uiState is TransferUiState.Completed)
        val completed = uiState as TransferUiState.Completed

        assertEquals(DeviceRole.Sender, completed.role)
        assertEquals("MacBook Air", completed.peerName)
        assertEquals(DeviceType.Computer, completed.peerDeviceType)
    }

    @Test
    fun failedState_interrupted_displaysPartialCompletedCountAndRetryAction() {
        val sessionState = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = SessionPhase.Interrupted,
            batch = multiBatch,
            failure = TransferFailure.TransferInterrupted("Connection timed out"),
            itemStates = mapOf(
                itemId1 to ItemTransferState(item = item1, phase = ItemPhase.Verified),
                itemId2 to ItemTransferState(item = item2, phase = ItemPhase.Interrupted)
            )
        )

        val uiState = sessionState.toUiState("Pixel 9 Pro")
        assertTrue(uiState is TransferUiState.Failed)
        val failed = uiState as TransferUiState.Failed

        assertTrue(failed.isInterrupted)
        assertTrue(failed.canRetry)
        assertEquals(2, failed.totalCount)
        assertEquals(1, failed.unfinishedCount)
        assertEquals(1, failed.completedCount)
    }

    @Test
    fun failedState_unconfirmedReceipt_displaysAmberWarningBanner() {
        val sessionState = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = SessionPhase.DeliveryUnconfirmed,
            batch = multiBatch,
            failure = TransferFailure.DeliveryUnconfirmed("Transport disconnected before receipt"),
            itemStates = mapOf(
                itemId1 to ItemTransferState(item = item1, phase = ItemPhase.Verified),
                itemId2 to ItemTransferState(item = item2, phase = ItemPhase.Interrupted)
            )
        )

        val uiState = sessionState.toUiState("Pixel 9 Pro")
        assertTrue(uiState is TransferUiState.Failed)
        val failed = uiState as TransferUiState.Failed

        assertTrue(failed.isReceiptUnconfirmed)
        assertTrue(failed.canRetry)
    }
}
