package dev.glide.app.ui.pairing

import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingModelTest {

    @Test
    fun `idle state is distinct singleton`() {
        val state: PairingUiState = PairingUiState.Idle
        assertEquals(PairingUiState.Idle, state)
    }

    @Test
    fun `verifying state holds peer info, 4 digits and countdown`() {
        val peerId = PeerId("endpoint-test")
        val state = PairingUiState.Verifying(
            peerId = peerId,
            peerName = "Pixel 9 Pro",
            authDigits = "4812",
            secondsRemaining = 45,
            localConfirmed = false
        )

        assertEquals(peerId, state.peerId)
        assertEquals("Pixel 9 Pro", state.peerName)
        assertEquals("4812", state.authDigits)
        assertEquals(45, state.secondsRemaining)
        assertFalse(state.localConfirmed)

        val confirmed = state.copy(localConfirmed = true)
        assertTrue(confirmed.localConfirmed)
    }

    @Test
    fun `connected state preserves peer device identity`() {
        val peer = PeerDevice(PeerId("ep-success"), "Galaxy S24")
        val state = PairingUiState.Connected(peer)
        assertEquals(peer, state.peer)
    }

    @Test
    fun `failed state provides structured reason and guidance`() {
        val state = PairingUiState.Failed(
            title = "Verification Timed Out",
            message = "The 60-second verification window elapsed. Please try again."
        )
        assertEquals("Verification Timed Out", state.title)
        assertTrue(state.message.contains("60-second"))
    }
}
