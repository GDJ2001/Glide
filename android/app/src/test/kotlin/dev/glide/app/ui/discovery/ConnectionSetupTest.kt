package dev.glide.app.ui.discovery

import dev.glide.app.connectivity.ConnectionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ConnectionSetupTest {
    @Test
    fun anOutstandingPromptKeepsItsOriginalSharingAction() {
        val setup = ConnectionSetup()
        var advances = 0
        setup.advance = { request, _ ->
            advances++
            setup.pending = request
        }
        val sendFiles = {}

        setup.start(ConnectionKind.Lan, onReady = sendFiles)
        setup.start(ConnectionKind.Nearby) { error("A second request must wait") }
        setup.openSettings(ConnectionKind.Nearby, BlockedReason.BluetoothDisabled, {}) {
            error("Settings must wait for the current prompt")
        }

        assertEquals(1, advances)
        assertSame(sendFiles, setup.pending?.ready)
        assertEquals(ConnectionKind.Lan, setup.pending?.kind)
    }

    @Test
    fun manualRecoveryRetriesTheOriginalActionAndCancelReleasesIt() {
        val setup = ConnectionSetup()
        val sendFiles = {}
        val request = ConnectionRequest(ConnectionKind.Lan, ready = sendFiles, failed = {})
        setup.blockedRequest = request
        setup.blocker = BlockedReason.NoLocalNetwork
        var manualReason: BlockedReason? = null
        setup.advance = { pending, reason ->
            setup.pending = pending
            manualReason = reason
        }

        setup.openSettings()

        assertEquals(BlockedReason.NoLocalNetwork, manualReason)
        assertSame(sendFiles, setup.pending?.ready)
        setup.cancel()
        assertNull(setup.pending)
        assertNull(setup.blockedRequest)
        assertNull(setup.blocker)
    }
}
