package dev.glide.transport.nearby

import com.google.android.gms.nearby.connection.Strategy
import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import dev.glide.core.ports.AdvertisingEvent
import dev.glide.core.ports.DiscoveryEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyDiscoveryAdapterTest {

    private lateinit var fakeClient: FakeNearbyClientWrapper
    private lateinit var adapter: NearbyDiscoveryAdapter

    @Before
    fun setUp() {
        fakeClient = FakeNearbyClientWrapper()
        adapter = NearbyDiscoveryAdapter(fakeClient)
    }

    @Test
    fun `serviceId isolates debug and release builds`() {
        assertEquals("glide.local.transfer.v1", NearbyConfig.serviceId(isDebug = false))
        assertEquals("glide.local.transfer.v1.debug", NearbyConfig.serviceId(isDebug = true))
        assertEquals(Strategy.P2P_POINT_TO_POINT, NearbyConfig.STRATEGY)
        assertEquals(120_000L, NearbyConfig.DISCOVERY_TIMEOUT_MS)
    }

    @Test
    fun `discoverPeers emits PeerAppeared when endpoint is found`() = runTest {
        val events = mutableListOf<DiscoveryEvent>()
        val job = launch {
            adapter.discoverPeers(serviceId = NearbyConfig.SERVICE_ID_RELEASE).collect {
                events.add(it)
            }
        }
        runCurrent()

        assertTrue(fakeClient.isDiscovering)
        assertEquals(NearbyConfig.SERVICE_ID_RELEASE, fakeClient.discoveryServiceId)
        assertEquals(Strategy.P2P_POINT_TO_POINT, fakeClient.discoveryOptions?.strategy)

        fakeClient.simulateEndpointFound("endpoint-1", "Pixel 9 Pro")
        runCurrent()

        assertEquals(1, events.size)
        val appeared = events[0] as DiscoveryEvent.PeerAppeared
        assertEquals(PeerId("endpoint-1"), appeared.peer.id)
        assertEquals("Pixel 9 Pro", appeared.peer.displayName)

        job.cancel()
        runCurrent()

        assertFalse(fakeClient.isDiscovering)
        assertEquals(1, fakeClient.stopDiscoveryCallCount)
    }

    @Test
    fun `discoverPeers emits PeerLost when endpoint is lost`() = runTest {
        val events = mutableListOf<DiscoveryEvent>()
        val job = launch {
            adapter.discoverPeers().collect {
                events.add(it)
            }
        }
        runCurrent()

        fakeClient.simulateEndpointFound("ep-42", "Galaxy S24")
        fakeClient.simulateEndpointLost("ep-42")
        runCurrent()

        assertEquals(2, events.size)
        val lost = events[1] as DiscoveryEvent.PeerLost
        assertEquals(PeerId("ep-42"), lost.peerId)

        job.cancel()
    }

    @Test
    fun `discoverPeers sanitizes blank and long device names safely`() = runTest {
        val events = mutableListOf<DiscoveryEvent>()
        val job = launch {
            adapter.discoverPeers().collect {
                events.add(it)
            }
        }
        runCurrent()

        fakeClient.simulateEndpointFound("ep-blank", "   ")
        fakeClient.simulateEndpointFound("ep-long", "A".repeat(100))
        runCurrent()

        assertEquals(2, events.size)
        val blankPeer = (events[0] as DiscoveryEvent.PeerAppeared).peer
        assertEquals("Glide Device", blankPeer.displayName)

        val longPeer = (events[1] as DiscoveryEvent.PeerAppeared).peer
        assertEquals(64, longPeer.displayName.length)
        assertEquals("A".repeat(64), longPeer.displayName)

        job.cancel()
    }

    @Test
    fun `discoverPeers stops radio on explicit stopDiscovery`() = runTest {
        val events = mutableListOf<DiscoveryEvent>()
        val job = launch {
            adapter.discoverPeers().collect {
                events.add(it)
            }
        }
        runCurrent()
        assertTrue(fakeClient.isDiscovering)

        adapter.stopDiscovery()
        runCurrent()

        assertFalse(fakeClient.isDiscovering)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `discoverPeers auto-stops radio when 120s timeout elapses`() = runTest {
        val events = mutableListOf<DiscoveryEvent>()
        val job = launch {
            adapter.discoverPeers(timeoutMs = 120_000L).collect {
                events.add(it)
            }
        }
        runCurrent()
        assertTrue(fakeClient.isDiscovering)

        advanceTimeBy(119_999L)
        runCurrent()
        assertTrue(fakeClient.isDiscovering)
        assertFalse(job.isCompleted)

        advanceTimeBy(1L)
        runCurrent()
        assertFalse(fakeClient.isDiscovering)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `discoverPeers propagates failure if radio start fails`() = runTest {
        fakeClient.startDiscoveryFailure = IllegalStateException("Bluetooth radio disabled")

        try {
            adapter.discoverPeers().toList()
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Bluetooth radio disabled", e.message)
        }

        assertFalse(fakeClient.isDiscovering)
    }

    @Test
    fun `advertiseReadiness emits ConnectionInitiated when peer connects`() = runTest {
        val events = mutableListOf<AdvertisingEvent>()
        val job = launch {
            adapter.advertiseReadiness(
                serviceId = NearbyConfig.SERVICE_ID_RELEASE,
                localDeviceName = "Receiver Phone"
            ).collect {
                events.add(it)
            }
        }
        runCurrent()

        assertTrue(fakeClient.isAdvertising)
        assertEquals("Receiver Phone", fakeClient.advertisingName)
        assertEquals(NearbyConfig.SERVICE_ID_RELEASE, fakeClient.advertisingServiceId)
        assertEquals(Strategy.P2P_POINT_TO_POINT, fakeClient.advertisingOptions?.strategy)

        fakeClient.simulateConnectionInitiated("sender-ep-1", "Sender Phone", "4812")
        runCurrent()

        assertEquals(1, events.size)
        val initiated = events[0] as AdvertisingEvent.ConnectionInitiated
        assertEquals(PeerId("sender-ep-1"), initiated.peer.id)
        assertEquals("Sender Phone", initiated.peer.displayName)
        assertEquals("2585", initiated.authDigits)
        assertTrue(initiated.authDigits.all { it.isDigit() })
        assertEquals(4, initiated.authDigits.length)

        job.cancel()
        runCurrent()

        assertFalse(fakeClient.isAdvertising)
        assertEquals(1, fakeClient.stopAdvertisingCallCount)
    }

    @Test
    fun `advertiseReadiness sanitizes local device name`() = runTest {
        val job = launch {
            adapter.advertiseReadiness(localDeviceName = "   ").collect {}
        }
        runCurrent()

        assertEquals("Glide Receiver", fakeClient.advertisingName)
        job.cancel()
    }

    @Test
    fun `advertiseReadiness stops radio on explicit stopAdvertising`() = runTest {
        val job = launch {
            adapter.advertiseReadiness(localDeviceName = "My Phone").collect {}
        }
        runCurrent()
        assertTrue(fakeClient.isAdvertising)

        adapter.stopAdvertising()
        runCurrent()

        assertFalse(fakeClient.isAdvertising)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `advertiseReadiness auto-stops radio when timeout expires`() = runTest {
        val job = launch {
            adapter.advertiseReadiness(localDeviceName = "My Phone", timeoutMs = 120_000L).collect {}
        }
        runCurrent()
        assertTrue(fakeClient.isAdvertising)

        advanceTimeBy(120_000L)
        runCurrent()

        assertFalse(fakeClient.isAdvertising)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `advertiseReadiness propagates failure if radio start fails`() = runTest {
        fakeClient.startAdvertisingFailure = SecurityException("Missing BLUETOOTH_ADVERTISE permission")

        try {
            adapter.advertiseReadiness(localDeviceName = "Phone").toList()
            fail("Expected SecurityException")
        } catch (e: SecurityException) {
            assertEquals("Missing BLUETOOTH_ADVERTISE permission", e.message)
        }

        assertFalse(fakeClient.isAdvertising)
    }
}
