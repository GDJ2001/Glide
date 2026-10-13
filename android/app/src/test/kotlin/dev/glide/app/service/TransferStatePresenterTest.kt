package dev.glide.app.service

import dev.glide.app.ui.transfer.TransferUiState
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class TransferStatePresenterTest {
    private val item = TransferItem(
        ItemId(UUID.randomUUID().toString()), "file.bin", "application/octet-stream", 1_000L
    )
    private val batch = TransferBatch(BatchId(UUID.randomUUID().toString()), listOf(item))
    private val sessionId = SessionId(UUID.randomUUID().toString())

    private fun state(bytes: Long, phase: SessionPhase = SessionPhase.TransferringItem) =
        SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = phase,
            batch = batch,
            itemStates = mapOf(
                item.id to ItemTransferState(
                    item = item,
                    phase = ItemPhase.Streaming,
                    transferredBytes = bytes
                )
            )
        )

    @Test
    fun progressIsThrottledButTerminalStateIsAlwaysShown() {
        var now = 1_000L
        val presenter = TransferStatePresenter { now }
        val first = presenter.present(state(100), "Peer", null, emptyMap(), TransferUiState.Idle)
        assertTrue(first is TransferUiState.Transferring)

        now += 50L
        assertNull(presenter.present(state(300), "Peer", null, emptyMap(), first!!))

        now += 200L
        val updated = presenter.present(state(600), "Peer", null, emptyMap(), first)
        assertEquals(600L, (updated as TransferUiState.Transferring).transferredBytes)
        assertTrue(updated.speedBytesPerSec > 0L)

        now += 1L
        val completed = presenter.present(
            state(1_000, SessionPhase.Completed), "Peer", null, emptyMap(), updated
        )
        assertTrue(completed is TransferUiState.Completed)

        presenter.reset()
        val restarted = presenter.present(state(0), "Peer", null, emptyMap(), completed!!)
        assertTrue(restarted is TransferUiState.Transferring)
        assertEquals(0L, (restarted as TransferUiState.Transferring).speedBytesPerSec)
    }
}
