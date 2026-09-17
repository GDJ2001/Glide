package dev.glide.app.discovery

import dev.glide.app.ui.discovery.DeviceType
import dev.glide.core.discovery.DeclaredPlatform
import dev.glide.core.discovery.ObservationSource
import dev.glide.core.discovery.PeerObservation
import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import dev.glide.core.ports.DiscoveryEvent
import dev.glide.transport.lan.network.LanNetworkScope
import dev.glide.transport.lan.network.LanNetworkState
import dev.glide.transport.lan.network.SubnetPrefix
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedDiscoveryRepositoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun testSimultaneousNearbyAndLanDiscovery_aggregatesBothTransports() = testScope.runTest {
        val nearbyFlow = MutableSharedFlow<DiscoveryEvent>(replay = 1)
        val lanFlow = MutableSharedFlow<DiscoveryEvent>(replay = 1)

        val repository = UnifiedDiscoveryRepository(
            nearbyDiscoveryFlowProvider = { nearbyFlow },
            lanDiscoveryFlowProvider = { lanFlow },
            coroutineScope = this
        )

        repository.startDiscovery()

        // 1. Nearby peer appears
        val nearbyPeer = PeerDevice(PeerId("nearby-endpoint-1"), "Alice Pixel")
        nearbyFlow.emit(DiscoveryEvent.PeerAppeared(nearbyPeer))

        // 2. LAN peer appears
        val lanPeer = PeerDevice(PeerId("lan-Glide-bob123"), "Bob Galaxy")
        lanFlow.emit(DiscoveryEvent.PeerAppeared(lanPeer))

        val peers = repository.unifiedPeers.value
        assertEquals(2, peers.size)

        val alice = peers.first { it.displayName == "Alice Pixel" }
        assertEquals("Nearby", alice.routeSubtitle)
        assertTrue(alice.isAvailable)

        val bob = peers.first { it.displayName == "Bob Galaxy" }
        assertEquals("Local network", bob.routeSubtitle)
        assertTrue(bob.isAvailable)

        repository.close()
    }

    @Test
    fun testAdversarialDuplicateNames_remainDistinctCandidates() = testScope.runTest {
        val nearbyFlow = MutableSharedFlow<DiscoveryEvent>(replay = 1)
        val lanFlow = MutableSharedFlow<DiscoveryEvent>(replay = 1)

        val repository = UnifiedDiscoveryRepository(
            nearbyDiscoveryFlowProvider = { nearbyFlow },
            lanDiscoveryFlowProvider = { lanFlow },
            coroutineScope = this
        )

        repository.startDiscovery()

        // Two distinct peers advertise identical display names ("Living Room TV")
        val nearbyTv = PeerDevice(PeerId("tv-nearby-handle"), "Living Room TV")
        nearbyFlow.emit(DiscoveryEvent.PeerAppeared(nearbyTv))

        val lanTv = PeerDevice(PeerId("lan-Glide-tv-lan"), "Living Room TV")
        lanFlow.emit(DiscoveryEvent.PeerAppeared(lanTv))

        val peers = repository.unifiedPeers.value
        assertEquals("Adversarial same-name peers must remain 2 distinct cards", 2, peers.size)
        assertEquals("Living Room TV", peers[0].displayName)
        assertEquals("Living Room TV", peers[1].displayName)
        // One is Nearby, one is Local network
        assertTrue(peers.any { it.routeSubtitle == "Nearby" })
        assertTrue(peers.any { it.routeSubtitle == "Local network" })

        repository.close()
    }

    @Test
    fun testRepeatedUpdatesForSamePeer_updateInPlaceWithoutReordering() = testScope.runTest {
        val nearbyFlow = MutableSharedFlow<DiscoveryEvent>(replay = 1)

        val repository = UnifiedDiscoveryRepository(
            nearbyDiscoveryFlowProvider = { nearbyFlow },
            coroutineScope = this
        )

        repository.startDiscovery()

        nearbyFlow.emit(DiscoveryEvent.PeerAppeared(PeerDevice(PeerId("ep-1"), "Device 1")))
        nearbyFlow.emit(DiscoveryEvent.PeerAppeared(PeerDevice(PeerId("ep-2"), "Device 2")))
        nearbyFlow.emit(DiscoveryEvent.PeerAppeared(PeerDevice(PeerId("ep-3"), "Device 3")))

        var peers = repository.unifiedPeers.value
        assertEquals(3, peers.size)
        assertEquals("Device 1", peers[0].displayName)
        assertEquals("Device 2", peers[1].displayName)
        assertEquals("Device 3", peers[2].displayName)

        // Updated label for Device 2
        nearbyFlow.emit(DiscoveryEvent.PeerAppeared(PeerDevice(PeerId("ep-2"), "Device 2 Updated")))

        peers = repository.unifiedPeers.value
        assertEquals(3, peers.size)
        // Indices are strictly preserved without reordering/jumping!
        assertEquals("Device 1", peers[0].displayName)
        assertEquals("Device 2 Updated", peers[1].displayName)
        assertEquals("Device 3", peers[2].displayName)

        repository.close()
    }

    @Test
    fun testPeerLost_marksUnavailableWithoutRemovingFromList() = testScope.runTest {
        val nearbyFlow = MutableSharedFlow<DiscoveryEvent>(replay = 1)

        val repository = UnifiedDiscoveryRepository(
            nearbyDiscoveryFlowProvider = { nearbyFlow },
            coroutineScope = this
        )

        repository.startDiscovery()

        nearbyFlow.emit(DiscoveryEvent.PeerAppeared(PeerDevice(PeerId("peer-alpha"), "Alpha Phone")))

        var peers = repository.unifiedPeers.value
        assertEquals(1, peers.size)
        assertTrue(peers[0].isAvailable)

        // Peer is lost
        nearbyFlow.emit(DiscoveryEvent.PeerLost(PeerId("peer-alpha")))

        peers = repository.unifiedPeers.value
        assertEquals("Peer must not be abruptly dropped from list during active scan", 1, peers.size)
        assertFalse("Lost peer must be marked unavailable", peers[0].isAvailable)

        repository.close()
    }

    @Test
    fun testNetworkGenerationChange_invalidatesStaleLanCandidatesWhileRetainingNearby() = testScope.runTest {
        val nearbyFlow = MutableSharedFlow<DiscoveryEvent>(replay = 1)
        val lanFlow = MutableSharedFlow<DiscoveryEvent>(replay = 1)
        val networkScope = LanNetworkScope(null, null)

        val repository = UnifiedDiscoveryRepository(
            nearbyDiscoveryFlowProvider = { nearbyFlow },
            lanDiscoveryFlowProvider = { lanFlow },
            networkScopeStateProvider = { networkScope.networkState },
            coroutineScope = this
        )

        // Set network gen 1
        networkScope.updateNetworkStateForTesting(
            LanNetworkState.Available(
                network = null,
                localAddresses = listOf(InetAddress.getByName("192.168.1.10")),
                subnets = listOf(SubnetPrefix(InetAddress.getByName("192.168.1.0"), 24)),
                generation = 1L
            )
        )

        repository.startDiscovery()

        // Add Nearby and LAN peer
        nearbyFlow.emit(DiscoveryEvent.PeerAppeared(PeerDevice(PeerId("nb-1"), "Nearby Peer")))
        lanFlow.emit(DiscoveryEvent.PeerAppeared(PeerDevice(PeerId("lan-Glide-node1"), "LAN Peer Gen 1")))

        assertEquals(2, repository.unifiedPeers.value.size)

        // Network changes to Generation 2 (e.g. Wi-Fi IP reassigned or network switch)
        networkScope.updateNetworkStateForTesting(
            LanNetworkState.Available(
                network = null,
                localAddresses = listOf(InetAddress.getByName("10.0.0.15")),
                subnets = listOf(SubnetPrefix(InetAddress.getByName("10.0.0.0"), 24)),
                generation = 2L
            )
        )

        val peersAfterNetworkChange = repository.unifiedPeers.value
        // Stale LAN peer from Gen 1 was evicted, but Nearby peer remains!
        assertEquals(1, peersAfterNetworkChange.size)
        assertEquals("Nearby Peer", peersAfterNetworkChange[0].displayName)

        repository.close()
    }

    @Test
    fun testNetworkUnavailable_clearsLanCandidates() = testScope.runTest {
        val nearbyFlow = MutableSharedFlow<DiscoveryEvent>(replay = 1)
        val lanFlow = MutableSharedFlow<DiscoveryEvent>(replay = 1)
        val networkScope = LanNetworkScope(null, null)

        val repository = UnifiedDiscoveryRepository(
            nearbyDiscoveryFlowProvider = { nearbyFlow },
            lanDiscoveryFlowProvider = { lanFlow },
            networkScopeStateProvider = { networkScope.networkState },
            coroutineScope = this
        )

        networkScope.updateNetworkStateForTesting(
            LanNetworkState.Available(
                network = null,
                localAddresses = listOf(InetAddress.getByName("192.168.1.10")),
                subnets = listOf(SubnetPrefix(InetAddress.getByName("192.168.1.0"), 24)),
                generation = 1L
            )
        )

        repository.startDiscovery()

        nearbyFlow.emit(DiscoveryEvent.PeerAppeared(PeerDevice(PeerId("nb-1"), "Nearby Device")))
        lanFlow.emit(DiscoveryEvent.PeerAppeared(PeerDevice(PeerId("lan-Glide-node2"), "LAN Device")))

        assertEquals(2, repository.unifiedPeers.value.size)

        // Wi-Fi disconnects
        networkScope.updateNetworkStateForTesting(
            LanNetworkState.NoNetwork
        )

        val peers = repository.unifiedPeers.value
        assertEquals(1, peers.size)
        assertEquals("Nearby Device", peers[0].displayName)

        repository.close()
    }

    @Test
    fun testGetCandidateByUiKey_returnsValidUnderlyingPeerCandidate() = testScope.runTest {
        val repository = UnifiedDiscoveryRepository(coroutineScope = this)

        val observation = PeerObservation(
            observationId = "obs-1",
            source = ObservationSource.LAN_NSD,
            label = "Charlie Workstation",
            platform = DeclaredPlatform.WINDOWS,
            endpointHandle = "192.168.1.99:50000",
            observedAtMs = System.currentTimeMillis()
        )

        repository.ingestObservation(observation)

        val peers = repository.unifiedPeers.value
        assertEquals(1, peers.size)
        assertEquals(DeviceType.Computer, peers[0].deviceType)

        val candidate = repository.getCandidate(peers[0].uiKey)
        assertNotNull(candidate)
        assertEquals("Charlie Workstation", candidate?.displayName)
        assertEquals(DeclaredPlatform.WINDOWS, candidate?.platform)

        repository.close()
    }
}
