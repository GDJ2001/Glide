package dev.glide.transport.nearby

import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.Payload
import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import dev.glide.core.ports.ConnectionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyConnectionManagerTest {

    private lateinit var fakeClient: FakeNearbyClientWrapper
    private lateinit var manager: NearbyConnectionManager

    @Before
    fun setUp() {
        fakeClient = FakeNearbyClientWrapper()
    }

    private fun createManager(scope: CoroutineScope): NearbyConnectionManager {
        val mgr = NearbyConnectionManager(fakeClient, scope)
        fakeClient.connectionCallback = mgr.connectionLifecycleCallback
        return mgr
    }

    @Test
    fun `initiateConnection requests connection and transitions to Connecting`() = runTest {
        manager = createManager(backgroundScope)
        val peerId = PeerId("endpoint-alpha")

        manager.initiateConnection(peerId, "Alpha Phone", "Local Phone")
        runCurrent()

        assertEquals(1, fakeClient.requestedConnections.size)
        assertEquals("Local Phone" to "endpoint-alpha", fakeClient.requestedConnections[0])
        val state = manager.linkState.value as LinkState.Connecting
        assertEquals(peerId, state.peerId)
        assertEquals("Alpha Phone", state.peerName)
    }

    @Test
    fun `handleConnectionInitiated transitions to Verifying and emits ConnectionInitiated`() = runTest {
        manager = createManager(backgroundScope)
        val events = mutableListOf<ConnectionEvent>()
        val job = launch {
            manager.connectionEvents.collect { events.add(it) }
        }
        runCurrent()

        fakeClient.simulateConnectionInitiated("ep-1", "Remote Device", "1234")
        runCurrent()

        assertEquals(1, events.size)
        val initiated = events[0] as ConnectionEvent.Initiated
        assertEquals(PeerId("ep-1"), initiated.peer.id)
        assertEquals("Remote Device", initiated.peer.displayName)
        assertTrue(initiated.authDigits.all { it.isDigit() })

        val state = manager.linkState.value as LinkState.Verifying
        assertEquals(PeerId("ep-1"), state.peerId)
        assertFalse(state.localAccepted)

        job.cancel()
    }

    @Test
    fun `mutual acceptance transitions to Connected only after local accept and SDK OK`() = runTest {
        manager = createManager(backgroundScope)
        val events = mutableListOf<ConnectionEvent>()
        val job = launch {
            manager.connectionEvents.collect { events.add(it) }
        }
        runCurrent()

        fakeClient.simulateConnectionInitiated("ep-1", "Remote Device", "1234")
        runCurrent()

        // 1. Local user confirms Match
        manager.acceptConnection(PeerId("ep-1"))
        runCurrent()

        assertEquals(listOf("ep-1"), fakeClient.acceptedConnections)
        val verifyingState = manager.linkState.value as LinkState.Verifying
        assertTrue(verifyingState.localAccepted)
        // Remains in Verifying until peer confirms / SDK fires OK
        assertEquals(1, events.size) // only initiated so far

        // 2. Remote peer accepts and SDK fires STATUS_OK
        fakeClient.simulateConnectionResult("ep-1", ConnectionsStatusCodes.STATUS_OK)
        runCurrent()

        val connectedState = manager.linkState.value as LinkState.Connected
        assertEquals(PeerId("ep-1"), connectedState.peer.id)
        assertEquals("Remote Device", connectedState.peer.displayName)

        assertEquals(2, events.size)
        val connectedEvent = events[1] as ConnectionEvent.Connected
        assertEquals(PeerId("ep-1"), connectedEvent.peer.id)

        job.cancel()
    }

    @Test
    fun `SDK OK before local accept does not connect until user explicitly confirms Match`() = runTest {
        manager = createManager(backgroundScope)
        fakeClient.simulateConnectionInitiated("ep-1", "Remote Device", "1234")
        runCurrent()

        // SDK fires OK before user tapped button
        fakeClient.simulateConnectionResult("ep-1", ConnectionsStatusCodes.STATUS_OK)
        runCurrent()

        // Still Verifying!
        assertTrue(manager.linkState.value is LinkState.Verifying)

        // Local user finally confirms Match
        manager.acceptConnection(PeerId("ep-1"))
        runCurrent()

        // Now Connected!
        assertTrue(manager.linkState.value is LinkState.Connected)
    }

    @Test
    fun `local rejection calls rejectConnection and transitions to Failed Mismatch`() = runTest {
        manager = createManager(backgroundScope)
        val events = mutableListOf<ConnectionEvent>()
        val job = launch {
            manager.connectionEvents.collect { events.add(it) }
        }
        runCurrent()

        fakeClient.simulateConnectionInitiated("ep-1", "Remote Device", "1234")
        runCurrent()

        manager.rejectConnection(PeerId("ep-1"))
        runCurrent()

        assertEquals(listOf("ep-1"), fakeClient.rejectedConnections)
        val failed = manager.linkState.value as LinkState.Failed
        assertEquals(LinkFailure.Mismatch, failed.reason)

        assertEquals(2, events.size)
        val rejectedEvent = events[1] as ConnectionEvent.Rejected
        assertEquals(PeerId("ep-1"), rejectedEvent.peerId)

        job.cancel()
    }

    @Test
    fun `peer rejection transitions to Failed PeerRejected`() = runTest {
        manager = createManager(backgroundScope)
        fakeClient.simulateConnectionInitiated("ep-1", "Remote Device", "1234")
        runCurrent()

        manager.acceptConnection(PeerId("ep-1"))
        runCurrent()

        fakeClient.simulateConnectionResult("ep-1", ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED)
        runCurrent()

        val failed = manager.linkState.value as LinkState.Failed
        assertEquals(LinkFailure.PeerRejected, failed.reason)
    }

    @Test
    fun `peer disappears transitions to Failed PeerDisappeared`() = runTest {
        manager = createManager(backgroundScope)
        fakeClient.simulateConnectionInitiated("ep-1", "Remote Device", "1234")
        runCurrent()

        fakeClient.simulateConnectionResult("ep-1", ConnectionsStatusCodes.STATUS_ERROR)
        runCurrent()

        val failed = manager.linkState.value as LinkState.Failed
        assertEquals(LinkFailure.PeerDisappeared, failed.reason)
    }

    @Test
    fun `60s timeout expires triggers automatic rejection and transitions to Failed Timeout`() = runTest {
        manager = createManager(backgroundScope)
        fakeClient.simulateConnectionInitiated("ep-1", "Remote Device", "1234")
        runCurrent()

        advanceTimeBy(59_999L)
        runCurrent()
        assertTrue(manager.linkState.value is LinkState.Verifying)

        advanceTimeBy(1L)
        runCurrent()

        assertEquals(listOf("ep-1"), fakeClient.rejectedConnections)
        val failed = manager.linkState.value as LinkState.Failed
        assertEquals(LinkFailure.Timeout, failed.reason)
    }

    @Test
    fun `stale confirm for wrong peerId is ignored`() = runTest {
        manager = createManager(backgroundScope)
        fakeClient.simulateConnectionInitiated("ep-1", "Device 1", "1234")
        runCurrent()

        // Stale confirmation for ep-old
        manager.acceptConnection(PeerId("ep-old"))
        runCurrent()

        assertEquals(0, fakeClient.acceptedConnections.size)
        val verifying = manager.linkState.value as LinkState.Verifying
        assertFalse(verifying.localAccepted)
    }

    @Test
    fun `same-name peers cannot bypass verification`() = runTest {
        manager = createManager(backgroundScope)
        // First peer arrives
        fakeClient.simulateConnectionInitiated("ep-legit", "Pixel 9", "1234")
        runCurrent()

        // Attacker arrives with same name but different endpoint
        fakeClient.simulateConnectionInitiated("ep-imposter", "Pixel 9", "9999")
        runCurrent()

        // Active verifying target is ep-imposter; confirming ep-legit is rejected as stale
        manager.acceptConnection(PeerId("ep-legit"))
        runCurrent()

        assertEquals(0, fakeClient.acceptedConnections.size)

        // Only confirming the active peer works
        manager.acceptConnection(PeerId("ep-imposter"))
        runCurrent()
        assertEquals(listOf("ep-imposter"), fakeClient.acceptedConnections)
    }

    @Test
    fun `unauthenticated payload received prior to Connected is safely dropped`() = runTest {
        manager = createManager(backgroundScope)
        fakeClient.simulateConnectionInitiated("ep-1", "Device", "1234")
        runCurrent()

        // Unsolicited premature payload arriving in Verifying state
        val dummyPayload = Payload.fromBytes(byteArrayOf(1, 2, 3))
        // Calling payloadCallback while in Verifying does nothing and does not crash
        manager.payloadCallback.onPayloadReceived("ep-1", dummyPayload)
        assertTrue(manager.linkState.value is LinkState.Verifying)
    }

    @Test
    fun `disconnect terminates active connection and emits Disconnected`() = runTest {
        manager = createManager(backgroundScope)
        val events = mutableListOf<ConnectionEvent>()
        val job = launch {
            manager.connectionEvents.collect { events.add(it) }
        }
        runCurrent()

        fakeClient.simulateConnectionInitiated("ep-1", "Device", "1234")
        manager.acceptConnection(PeerId("ep-1"))
        fakeClient.simulateConnectionResult("ep-1", ConnectionsStatusCodes.STATUS_OK)
        runCurrent()

        assertTrue(manager.linkState.value is LinkState.Connected)

        manager.disconnect(PeerId("ep-1"))
        runCurrent()

        assertEquals(listOf("ep-1"), fakeClient.disconnectedEndpoints)
        assertEquals(LinkState.Disconnected, manager.linkState.value)

        val disconnectedEvent = events.last() as ConnectionEvent.Disconnected
        assertEquals(PeerId("ep-1"), disconnectedEvent.peerId)

        job.cancel()
    }
}
