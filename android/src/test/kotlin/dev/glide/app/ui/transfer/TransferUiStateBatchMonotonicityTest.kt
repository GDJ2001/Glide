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
import dev.glide.core.domain.TransferItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class TransferUiStateBatchMonotonicityTest {

    private val sessionId = SessionId(UUID.randomUUID().toString())

    private val item1 = TransferItem(
        id = ItemId(UUID.randomUUID().toString()),
        name = "photo1.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 100_000L
    )

    private val item2 = TransferItem(
        id = ItemId(UUID.randomUUID().toString()),
        name = "document.pdf",
        mimeType = "application/pdf",
        sizeBytes = 200_000L
    )

    private val item3 = TransferItem(
        id = ItemId(UUID.randomUUID().toString()),
        name = "video.mp4",
        mimeType = "video/mp4",
        sizeBytes = 300_000L
    )

    private val batch = TransferBatch(
        id = BatchId(UUID.randomUUID().toString()),
        items = listOf(item1, item2, item3)
    )

    @Test
    fun multiItemBatch_maintainsMonotonicCumulativeBytesAcrossItemBoundaries() {
        val itemStates = mutableMapOf<ItemId, ItemTransferState>()

        // 1. Item 1 halfway transferred: 50,000 of 100,000 bytes
        itemStates[item1.id] = ItemTransferState(
            item = item1,
            phase = ItemPhase.Streaming,
            transferredBytes = 50_000L
        )
        val state1 = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = SessionPhase.TransferringItem,
            batch = batch,
            currentItemIndex = 0,
            itemStates = itemStates.toMap()
        )
        val ui1 = state1.toUiState("Pixel 9 Pro") as TransferUiState.Transferring
        assertEquals("photo1.jpg", ui1.itemName)
        assertEquals(1, ui1.itemIndex)
        assertEquals(3, ui1.itemCount)
        assertEquals(50_000L, ui1.transferredBytes)
        assertEquals(600_000L, ui1.totalBytes)
        assertEquals(50_000f / 600_000f, ui1.progressFraction, 0.001f)

        // 2. Item 1 verified. Item 2 starts streaming with 0 bytes transferred.
        // Regression check: transferredBytes must NOT drop to 0!
        itemStates[item1.id] = ItemTransferState(
            item = item1,
            phase = ItemPhase.Verified,
            transferredBytes = 100_000L
        )
        itemStates[item2.id] = ItemTransferState(
            item = item2,
            phase = ItemPhase.Streaming,
            transferredBytes = 0L
        )
        val state2 = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = SessionPhase.TransferringItem,
            batch = batch,
            currentItemIndex = 1,
            itemStates = itemStates.toMap()
        )
        val ui2 = state2.toUiState("Pixel 9 Pro") as TransferUiState.Transferring
        assertEquals("document.pdf", ui2.itemName)
        assertEquals(2, ui2.itemIndex)
        assertEquals(3, ui2.itemCount)
        // Crucial invariant: 100,000 cumulative bytes, NOT 0!
        assertEquals(100_000L, ui2.transferredBytes)
        assertEquals(600_000L, ui2.totalBytes)
        assertEquals(100_000f / 600_000f, ui2.progressFraction, 0.001f)
        assertTrue(
            "Progress fraction must not regress across item boundary: ${ui2.progressFraction} >= ${ui1.progressFraction}",
            ui2.progressFraction >= ui1.progressFraction
        )

        // 3. Item 2 halfway: 100,000 of 200,000 bytes
        itemStates[item2.id] = ItemTransferState(
            item = item2,
            phase = ItemPhase.Streaming,
            transferredBytes = 100_000L
        )
        val state3 = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = SessionPhase.TransferringItem,
            batch = batch,
            currentItemIndex = 1,
            itemStates = itemStates.toMap()
        )
        val ui3 = state3.toUiState("Pixel 9 Pro") as TransferUiState.Transferring
        assertEquals("document.pdf", ui3.itemName)
        assertEquals(2, ui3.itemIndex)
        assertEquals(3, ui3.itemCount)
        assertEquals(200_000L, ui3.transferredBytes)
        assertEquals(200_000f / 600_000f, ui3.progressFraction, 0.001f)
        assertTrue(ui3.progressFraction >= ui2.progressFraction)

        // 4. Item 2 verified. Item 3 streaming at 150,000 of 300,000 bytes
        itemStates[item2.id] = ItemTransferState(
            item = item2,
            phase = ItemPhase.Verified,
            transferredBytes = 200_000L
        )
        itemStates[item3.id] = ItemTransferState(
            item = item3,
            phase = ItemPhase.Streaming,
            transferredBytes = 150_000L
        )
        val state4 = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = SessionPhase.TransferringItem,
            batch = batch,
            currentItemIndex = 2,
            itemStates = itemStates.toMap()
        )
        val ui4 = state4.toUiState("Pixel 9 Pro") as TransferUiState.Transferring
        assertEquals("video.mp4", ui4.itemName)
        assertEquals(3, ui4.itemIndex)
        assertEquals(3, ui4.itemCount)
        // 100k (item1) + 200k (item2) + 150k (item3) = 450k
        assertEquals(450_000L, ui4.transferredBytes)
        assertEquals(450_000f / 600_000f, ui4.progressFraction, 0.001f)
        assertTrue(ui4.progressFraction >= ui3.progressFraction)

        // 5. Item 3 Verifying state
        itemStates[item3.id] = ItemTransferState(
            item = item3,
            phase = ItemPhase.Verifying,
            transferredBytes = 300_000L
        )
        val state5 = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = SessionPhase.VerifyingItem,
            batch = batch,
            currentItemIndex = 2,
            itemStates = itemStates.toMap()
        )
        val ui5 = state5.toUiState("Pixel 9 Pro") as TransferUiState.Verifying
        assertEquals("video.mp4", ui5.itemName)
        assertEquals(3, ui5.itemIndex)
        assertEquals(3, ui5.itemCount)
        assertEquals(600_000L, ui5.totalBytes)
    }

    @Test
    fun singleItemBatch_calculatesProgressCorrectly() {
        val singleItem = TransferItem(
            id = ItemId(UUID.randomUUID().toString()),
            name = "single.zip",
            mimeType = "application/zip",
            sizeBytes = 500_000L
        )
        val singleBatch = TransferBatch(
            id = BatchId(UUID.randomUUID().toString()),
            items = listOf(singleItem)
        )
        val itemState = ItemTransferState(
            item = singleItem,
            phase = ItemPhase.Streaming,
            transferredBytes = 250_000L
        )
        val state = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Receiver,
            phase = SessionPhase.TransferringItem,
            batch = singleBatch,
            currentItemIndex = 0,
            itemStates = mapOf(singleItem.id to itemState)
        )
        val ui = state.toUiState("Pixel 9 Pro") as TransferUiState.Transferring
        assertEquals(1, ui.itemIndex)
        assertEquals(1, ui.itemCount)
        assertEquals(250_000L, ui.transferredBytes)
        assertEquals(500_000L, ui.totalBytes)
        assertEquals(0.5f, ui.progressFraction, 0.001f)
    }

    @Test
    fun zeroByteBatch_doesNotDivideByZero() {
        val zeroItem = TransferItem(
            id = ItemId(UUID.randomUUID().toString()),
            name = "empty.txt",
            mimeType = "text/plain",
            sizeBytes = 0L
        )
        val zeroBatch = TransferBatch(
            id = BatchId(UUID.randomUUID().toString()),
            items = listOf(zeroItem)
        )
        val itemState = ItemTransferState(
            item = zeroItem,
            phase = ItemPhase.Streaming,
            transferredBytes = 0L
        )
        val state = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = SessionPhase.TransferringItem,
            batch = zeroBatch,
            currentItemIndex = 0,
            itemStates = mapOf(zeroItem.id to itemState)
        )
        val ui = state.toUiState("Pixel 9 Pro") as TransferUiState.Transferring
        assertEquals(0L, ui.transferredBytes)
        assertEquals(0L, ui.totalBytes)
        assertEquals(0f, ui.progressFraction, 0.001f)
    }
}
