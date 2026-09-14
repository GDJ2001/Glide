package dev.glide.transport.nearby

import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.PeerId
import dev.glide.core.ports.ConnectionEvent
import dev.glide.core.ports.DiscoveryEvent
import dev.glide.core.ports.TransferTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyTransferTransportTest {

    private lateinit var fakeClient: FakeNearbyClientWrapper
    private lateinit var transport: TransferTransport

    @Before
    fun setUp() {
        fakeClient = FakeNearbyClientWrapper()
        transport = NearbyTransferTransport(fakeClient)
    }

    @Test
    fun `startDiscovery and stopDiscovery control scanning lifecycle via transport port`() = runTest {
        val events = mutableListOf<DiscoveryEvent>()
        val job = launch {
            transport.startDiscovery("test.service.id").collect {
                events.add(it)
            }
        }
        runCurrent()

        assertTrue(fakeClient.isDiscovering)
        assertEquals("test.service.id", fakeClient.discoveryServiceId)

        fakeClient.simulateEndpointFound("ep-transport", "Sender Phone")
        runCurrent()

        assertEquals(1, events.size)

        transport.stopDiscovery()
        runCurrent()

        assertFalse(fakeClient.isDiscovering)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `startAdvertising and stopAdvertising control advertising lifecycle via transport port`() = runTest {
        val job = launch {
            transport.startAdvertising("test.service.id", "Receiver Phone").collect {}
        }
        runCurrent()

        assertTrue(fakeClient.isAdvertising)
        assertEquals("Receiver Phone", fakeClient.advertisingName)
        assertEquals("test.service.id", fakeClient.advertisingServiceId)

        transport.stopAdvertising()
        runCurrent()

        assertFalse(fakeClient.isAdvertising)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `initiateConnection delegates to Nearby client`() = runTest {
        transport.initiateConnection(PeerId("ep-sender"), "My Phone")
        runCurrent()

        assertEquals(1, fakeClient.requestedConnections.size)
        assertEquals("My Phone" to "ep-sender", fakeClient.requestedConnections[0])
    }

    @Test
    fun `acceptConnection delegates to Nearby client`() = runTest {
        val job = launch { transport.startAdvertising("test.service.id", "Receiver").collect {} }
        runCurrent()

        fakeClient.simulateConnectionInitiated("ep-accept", "Peer", "1234")
        runCurrent()

        transport.acceptConnection(PeerId("ep-accept"))
        runCurrent()

        assertEquals(listOf("ep-accept"), fakeClient.acceptedConnections)
        job.cancel()
    }

    @Test
    fun `rejectConnection delegates to Nearby client`() = runTest {
        val job = launch { transport.startAdvertising("test.service.id", "Receiver").collect {} }
        runCurrent()

        fakeClient.simulateConnectionInitiated("ep-reject", "Peer", "1234")
        runCurrent()

        transport.rejectConnection(PeerId("ep-reject"))
        runCurrent()

        assertEquals(listOf("ep-reject"), fakeClient.rejectedConnections)
        job.cancel()
    }

    @Test
    fun `disconnect delegates to Nearby client and emits Disconnected`() = runTest {
        val advJob = launch { transport.startAdvertising("test.service.id", "Receiver").collect {} }
        runCurrent()

        fakeClient.simulateConnectionInitiated("ep-disc", "Peer", "1234")
        transport.acceptConnection(PeerId("ep-disc"))
        fakeClient.simulateConnectionResult("ep-disc", ConnectionsStatusCodes.STATUS_OK)
        runCurrent()

        val events = mutableListOf<ConnectionEvent>()
        val job = launch {
            transport.observeConnectionEvents().collect { events.add(it) }
        }
        runCurrent()

        transport.disconnect(PeerId("ep-disc"))
        runCurrent()

        assertEquals(listOf("ep-disc"), fakeClient.disconnectedEndpoints)

        job.cancel()
        advJob.cancel()
    }

    @Test
    fun `sendControl and observeControl delegate to NearbyPayloadBridge`() = runTest {
        transport = NearbyTransferTransport(fakeClient, this)
        val advJob = launch { transport.startAdvertising("test.service.id", "Receiver").collect {} }
        runCurrent()

        fakeClient.simulateConnectionInitiated("ep-ctrl", "Peer", "1234")
        transport.acceptConnection(PeerId("ep-ctrl"))
        fakeClient.simulateConnectionResult("ep-ctrl", ConnectionsStatusCodes.STATUS_OK)
        runCurrent()

        val received = mutableListOf<ByteArray>()
        val observeJob = launch {
            transport.observeControl().collect {
                received.add(it.payload)
            }
        }
        runCurrent()

        val sampleMsg = "{\"type\":\"PING\"}".toByteArray(Charsets.UTF_8)
        transport.sendControl(PeerId("ep-ctrl"), sampleMsg)
        runCurrent()

        assertEquals(1, fakeClient.sentPayloads.size)
        assertEquals("ep-ctrl", fakeClient.sentPayloads[0].first)

        fakeClient.simulatePayloadReceived("ep-ctrl", com.google.android.gms.nearby.connection.Payload.fromBytes(sampleMsg))
        runCurrent()

        assertEquals(1, received.size)
        org.junit.Assert.assertArrayEquals(sampleMsg, received[0])

        observeJob.cancel()
        advJob.cancel()
    }

    @Test
    fun `cancelPayload delegates to NearbyPayloadBridge and cancels in Nearby`() = runTest {
        val nearbyTransport = transport as NearbyTransferTransport
        val bulkRef = nearbyTransport.payloadBridge.prepareStream {
            java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }

        transport.cancelPayload(bulkRef)
        runCurrent()

        assertTrue(fakeClient.cancelledPayloads.contains(bulkRef.value.toLong()))
    }

    @Test
    fun `observeIncomingStream and observeBulkProgress return active flows`() {
        val incomingFlow = transport.observeIncomingStream()
        val progressFlow = transport.observeBulkProgress()
        org.junit.Assert.assertNotNull(incomingFlow)
        org.junit.Assert.assertNotNull(progressFlow)
    }
}
