package dev.glide.app.ui.discovery

import dev.glide.app.ui.pairing.QrPairingEvent
import dev.glide.app.ui.pairing.QrPairingViewModel
import dev.glide.app.ui.pairing.QrScannerUiState
import dev.glide.core.domain.PeerId
import dev.glide.core.pairing.InvitationCodec
import dev.glide.core.pairing.LanEndpoint
import dev.glide.core.pairing.LanInvitation
import dev.glide.transport.lan.pairing.InvitationAuthority
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verification test suite for Unified Quick Share UX (LAN-019).
 *
 * Covers:
 * 1. Unified peer surface displaying both Nearby and LAN candidates with accurate route subtitles.
 * 2. Busy state handling: peers marked isBusy = true cannot be selected and display busy status.
 * 3. Single-selection lock: initiating connection locks selection and rejects duplicate taps.
 * 4. Non-jumping UI list: peer loss preserves candidate at stable index as unavailable.
 * 5. QR scanner confirmed connection event generation for transfer session launch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedQuickShareTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun unifiedDiscovery_presentsNearbyAndLanPeers_withAccurateRouteSubtitles() = runTest(testDispatcher) {
        val viewModel = DiscoveryViewModel.forTesting()

        val nearbyPeer = DiscoveredPeer(
            id = PeerId("nearby-pixel-9"),
            displayName = "Pixel 9 Pro",
            deviceType = DeviceType.Phone,
            routeSubtitle = "Nearby"
        )
        val lanPeer = DiscoveredPeer(
            id = PeerId("lan-desktop-win"),
            displayName = "Workstation PC",
            deviceType = DeviceType.Computer,
            routeSubtitle = "Local network"
        )
        val qrPeer = DiscoveredPeer(
            id = PeerId("qr-tab-s9"),
            displayName = "Galaxy Tab S9",
            deviceType = DeviceType.Tablet,
            routeSubtitle = "Scanned invite"
        )

        viewModel.onPeerDiscovered(nearbyPeer)
        viewModel.onPeerDiscovered(lanPeer)
        viewModel.onPeerDiscovered(qrPeer)

        val state = viewModel.uiState.value as DiscoveryUiState.Searching
        assertEquals("All three candidates should appear on the unified surface", 3, state.peers.size)

        assertEquals("Nearby", state.peers[0].routeSubtitle)
        assertEquals(DeviceType.Phone, state.peers[0].deviceType)

        assertEquals("Local network", state.peers[1].routeSubtitle)
        assertEquals(DeviceType.Computer, state.peers[1].deviceType)

        assertEquals("Scanned invite", state.peers[2].routeSubtitle)
        assertEquals(DeviceType.Tablet, state.peers[2].deviceType)
    }

    @Test
    fun busyPeer_cannotBeSelected_andDisplaysBusyState() = runTest(testDispatcher) {
        val viewModel = DiscoveryViewModel.forTesting()

        val idlePeer = DiscoveredPeer(
            id = PeerId("peer-idle"),
            displayName = "Idle Phone",
            isBusy = false
        )
        val busyPeer = DiscoveredPeer(
            id = PeerId("peer-busy"),
            displayName = "Busy Tablet",
            isBusy = true
        )

        viewModel.onPeerDiscovered(idlePeer)
        viewModel.onPeerDiscovered(busyPeer)

        var connectionAttempts = 0

        // Attempt to select the busy peer
        viewModel.selectPeer(PeerId("peer-busy")) {
            connectionAttempts++
        }

        assertEquals("Busy peer must not trigger connection callback", 0, connectionAttempts)
        val state = viewModel.uiState.value as DiscoveryUiState.Searching
        assertNull("Selected peer ID must remain null when tapping busy peer", state.selectedPeerId)
        assertFalse("Busy peer must not be marked connecting", state.peers[1].isConnecting)
        assertFalse("Busy peer must not be marked selected", state.peers[1].isSelected)

        // Dynamically toggle peer busy status
        viewModel.setPeerBusy(PeerId("peer-idle"), isBusy = true)
        val updatedState = viewModel.uiState.value as DiscoveryUiState.Searching
        assertTrue("Idle peer should now be marked busy", updatedState.peers[0].isBusy)

        // Now idle peer cannot be selected either
        viewModel.selectPeer(PeerId("peer-idle")) {
            connectionAttempts++
        }
        assertEquals("Newly busy peer must also be rejected", 0, connectionAttempts)
    }

    @Test
    fun singleSelectionLock_freezesSelection_andIgnoresDuplicateOrOtherPeerTaps() = runTest(testDispatcher) {
        val viewModel = DiscoveryViewModel.forTesting()

        val peerA = DiscoveredPeer(id = PeerId("peer-a"), displayName = "Device A")
        val peerB = DiscoveredPeer(id = PeerId("peer-b"), displayName = "Device B")
        viewModel.onPeerDiscovered(peerA)
        viewModel.onPeerDiscovered(peerB)

        var connections = 0
        var connectedId: PeerId? = null

        // Tap Device A
        viewModel.selectPeer(PeerId("peer-a")) { id ->
            connections++
            connectedId = id
        }

        assertEquals(1, connections)
        assertEquals("peer-a", connectedId?.value)

        val stateAfterFirstTap = viewModel.uiState.value as DiscoveryUiState.Searching
        assertEquals(PeerId("peer-a"), stateAfterFirstTap.selectedPeerId)
        assertTrue(stateAfterFirstTap.peers[0].isSelected)
        assertTrue(stateAfterFirstTap.peers[0].isConnecting)

        // Duplicate tap on Device A must be ignored
        viewModel.selectPeer(PeerId("peer-a")) { connections++ }
        assertEquals(1, connections)

        // Concurrent tap on Device B while Device A is connecting must be ignored
        viewModel.selectPeer(PeerId("peer-b")) { connections++ }
        assertEquals(1, connections)
        assertEquals(PeerId("peer-a"), (viewModel.uiState.value as DiscoveryUiState.Searching).selectedPeerId)
    }

    @Test
    fun peerLost_marksPeerUnavailable_withoutRemovingFromListOrChangingIndices() = runTest(testDispatcher) {
        val viewModel = DiscoveryViewModel.forTesting()

        val peer1 = DiscoveredPeer(id = PeerId("peer-1"), displayName = "Device 1")
        val peer2 = DiscoveredPeer(id = PeerId("peer-2"), displayName = "Device 2")
        viewModel.onPeerDiscovered(peer1)
        viewModel.onPeerDiscovered(peer2)

        var state = viewModel.uiState.value as DiscoveryUiState.Searching
        assertEquals(2, state.peers.size)
        assertTrue(state.peers[0].isAvailable)
        assertTrue(state.peers[1].isAvailable)

        // Peer 1 is lost
        viewModel.onPeerLost(PeerId("peer-1"))

        state = viewModel.uiState.value as DiscoveryUiState.Searching
        assertEquals("List size must remain stable to prevent UI jumping", 2, state.peers.size)
        assertEquals("peer-1", state.peers[0].id.value)
        assertFalse("Lost peer must be marked unavailable", state.peers[0].isAvailable)
        assertTrue("Remaining peer remains available", state.peers[1].isAvailable)

        // Unavailable peer cannot be selected
        var connectionAttempted = false
        viewModel.selectPeer(PeerId("peer-1")) { connectionAttempted = true }
        assertFalse("Unavailable peer must not initiate connection", connectionAttempted)
    }

    @Test
    fun qrPairingConfirmation_emitsConnectConfirmedEvent_withExactInvitationId() = runTest(testDispatcher) {
        val authority = InvitationAuthority()
        val viewModel = QrPairingViewModel(invitationAuthority = authority)

        val invitation = authority.mintInvitation(
            endpoints = listOf(LanEndpoint("192.168.1.120", 4242)),
            spkiSha256 = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
            deviceName = "Test Receiver",
            ttlSeconds = 120
        )

        // Process valid invitation text
        viewModel.onQrCodeScanned(InvitationCodec.encode(invitation))

        val confirmationState = viewModel.scannerState.value
        assertTrue("State must require explicit confirmation", confirmationState is QrScannerUiState.ConfirmationRequired)

        // Confirm connection
        val eventDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.events.first()
        }
        viewModel.confirmConnection()

        val event = eventDeferred.await()
        assertTrue("Event must be ConnectConfirmed", event is QrPairingEvent.ConnectConfirmed)
        val confirmedEvent = event as QrPairingEvent.ConnectConfirmed
        assertEquals(invitation.invitationId, confirmedEvent.invitation.invitationId)
        assertEquals("Test Receiver", confirmedEvent.invitation.name)
    }
}
