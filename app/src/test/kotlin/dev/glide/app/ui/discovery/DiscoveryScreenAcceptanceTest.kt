package dev.glide.app.ui.discovery

import dev.glide.core.domain.PeerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryScreenAcceptanceTest {

    @Test
    fun discoveryViewModel_initialSearchingState_isSearching() = runTest {
        val viewModel = DiscoveryViewModel()
        val state = viewModel.uiState.value
        assertTrue("Initial state should be Searching", state is DiscoveryUiState.Searching)
        val searching = state as DiscoveryUiState.Searching
        assertTrue("Initially peer list is empty", searching.peers.isEmpty())
        assertNull("No peer should be selected initially", searching.selectedPeerId)
    }

    @Test
    fun rapidPeerAppearAndLoss_maintainsStableIndicesWithoutListJumps() = runTest {
        val viewModel = DiscoveryViewModel()

        val peerA = DiscoveredPeer(
            id = PeerId("peer-a"),
            displayName = "Pixel 9 Pro",
            deviceType = DeviceType.Phone
        )
        val peerB = DiscoveredPeer(
            id = PeerId("peer-b"),
            displayName = "Galaxy Tab S9",
            deviceType = DeviceType.Tablet
        )
        val peerC = DiscoveredPeer(
            id = PeerId("peer-c"),
            displayName = "MacBook Pro",
            deviceType = DeviceType.Computer
        )

        viewModel.onPeerDiscovered(peerA)
        viewModel.onPeerDiscovered(peerB)
        viewModel.onPeerDiscovered(peerC)

        var searching = viewModel.uiState.value as DiscoveryUiState.Searching
        assertEquals(3, searching.peers.size)
        assertEquals("peer-a", searching.peers[0].id.value)
        assertEquals("peer-b", searching.peers[1].id.value)
        assertEquals("peer-c", searching.peers[2].id.value)

        // Peer B is lost suddenly (e.g. out of range or radio dropped)
        viewModel.onPeerLost(PeerId("peer-b"))

        searching = viewModel.uiState.value as DiscoveryUiState.Searching
        // Invariant: List size remains 3 and indices remain unchanged to prevent list jumps!
        assertEquals("List size must remain unchanged to preserve focus and avoid jumps", 3, searching.peers.size)
        assertEquals("peer-a", searching.peers[0].id.value)
        assertEquals("peer-b", searching.peers[1].id.value)
        assertEquals("peer-c", searching.peers[2].id.value)

        // Peer B is marked unavailable
        assertFalse("Lost peer must be marked unavailable", searching.peers[1].isAvailable)
        assertTrue("Peer A remains available", searching.peers[0].isAvailable)
        assertTrue("Peer C remains available", searching.peers[2].isAvailable)

        // Peer B reappears
        val peerBRecovered = peerB.copy(isAvailable = true)
        viewModel.onPeerDiscovered(peerBRecovered)

        searching = viewModel.uiState.value as DiscoveryUiState.Searching
        assertEquals(3, searching.peers.size)
        assertTrue("Re-discovered peer must be restored to available", searching.peers[1].isAvailable)
    }

    @Test
    fun peerSelection_locksSingleConnection_andDisablesDuplicateConnectAttempts() = runTest {
        val viewModel = DiscoveryViewModel()

        val peerA = DiscoveredPeer(id = PeerId("peer-a"), displayName = "Pixel 9")
        val peerB = DiscoveredPeer(id = PeerId("peer-b"), displayName = "Pixel 10")
        viewModel.onPeerDiscovered(peerA)
        viewModel.onPeerDiscovered(peerB)

        var initiatedCount = 0
        var initiatedPeerId: PeerId? = null

        // First click selects Peer A
        viewModel.selectPeer(PeerId("peer-a")) { id ->
            initiatedCount++
            initiatedPeerId = id
        }

        assertEquals(1, initiatedCount)
        assertEquals("peer-a", initiatedPeerId?.value)

        var searching = viewModel.uiState.value as DiscoveryUiState.Searching
        assertEquals("peer-a", searching.selectedPeerId?.value)
        assertTrue(searching.peers[0].isSelected)
        assertTrue(searching.peers[0].isConnecting)

        // Duplicate click on Peer A while connecting must be ignored
        viewModel.selectPeer(PeerId("peer-a")) {
            initiatedCount++
        }
        assertEquals("Duplicate click on same peer must be ignored", 1, initiatedCount)

        // Click on Peer B while Peer A is connecting must also be ignored (single-connection lock)
        viewModel.selectPeer(PeerId("peer-b")) {
            initiatedCount++
        }
        assertEquals("Click on another peer while connecting must be ignored", 1, initiatedCount)
    }

    @Test
    fun blockedStates_correctlyReflectReasons() = runTest {
        val viewModel = DiscoveryViewModel()

        viewModel.setBlocked(BlockedReason.BluetoothDisabled)
        assertEquals(
            DiscoveryUiState.Blocked(BlockedReason.BluetoothDisabled),
            viewModel.uiState.value
        )

        viewModel.setBlocked(BlockedReason.WifiDisabled)
        assertEquals(
            DiscoveryUiState.Blocked(BlockedReason.WifiDisabled),
            viewModel.uiState.value
        )

        viewModel.setBlocked(BlockedReason.PermissionsRequired)
        assertEquals(
            DiscoveryUiState.Blocked(BlockedReason.PermissionsRequired),
            viewModel.uiState.value
        )

        viewModel.setBlocked(BlockedReason.TransportError("Nearby Service unavailable"))
        val errorState = viewModel.uiState.value as DiscoveryUiState.Blocked
        assertTrue(errorState.reason is BlockedReason.TransportError)
        assertEquals("Nearby Service unavailable", (errorState.reason as BlockedReason.TransportError).message)

        // Retrying returns to Searching state
        viewModel.retryDiscovery()
        assertTrue(viewModel.uiState.value is DiscoveryUiState.Searching)
    }

    @Test
    fun receiveReadinessViewModel_managesAdvertisingAndTimeout() = runTest {
        val viewModel = ReceiveReadinessViewModel()

        viewModel.startAdvertising("Test Pixel")
        val state = viewModel.uiState.value
        assertTrue(state is ReceiveReadinessUiState.Advertising)
        val adv = state as ReceiveReadinessUiState.Advertising
        assertEquals("Test Pixel", adv.localDeviceName)
        assertEquals(120, adv.secondsRemaining)

        // Incoming connection transition
        val peer = DiscoveredPeer(id = PeerId("sender-1"), displayName = "Sender Tablet")
        viewModel.onConnectionInitiated(peer, "482910")

        val incomingState = viewModel.uiState.value
        assertTrue(incomingState is ReceiveReadinessUiState.IncomingConnection)
        val incoming = incomingState as ReceiveReadinessUiState.IncomingConnection
        assertEquals("sender-1", incoming.peer.id.value)
        assertEquals("482910", incoming.authDigits)
    }

    @Test
    fun deviceTypeHeuristics_accuratelyCategorizesCommonDevices() {
        assertEquals(DeviceType.Phone, DeviceType.fromDeviceName("Pixel 9 Pro"))
        assertEquals(DeviceType.Phone, DeviceType.fromDeviceName("Galaxy S24"))
        assertEquals(DeviceType.Phone, DeviceType.fromDeviceName("iPhone 16"))
        assertEquals(DeviceType.Tablet, DeviceType.fromDeviceName("Galaxy Tab S9"))
        assertEquals(DeviceType.Tablet, DeviceType.fromDeviceName("iPad Air"))
        assertEquals(DeviceType.Computer, DeviceType.fromDeviceName("MacBook Pro"))
        assertEquals(DeviceType.Computer, DeviceType.fromDeviceName("Windows PC Laptop"))
        assertEquals(DeviceType.Computer, DeviceType.fromDeviceName("Desktop Workstation"))
    }
}
