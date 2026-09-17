package dev.glide.transport.lan

import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.SessionId
import dev.glide.core.pairing.LanEndpoint
import dev.glide.core.ports.ConnectionEvent
import dev.glide.core.ports.StreamExpectation
import dev.glide.transport.lan.network.LanNetworkScope
import dev.glide.transport.lan.pairing.InvitationAuthority
import dev.glide.transport.lan.tls.LanLeaseIdentity
import dev.glide.transport.lan.tls.LanListener
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LanTransferTransportTest {

    private val testSessionId = SessionId("00000000-0000-4000-8000-000000000001")
    private val testItemId = ItemId("00000000-0000-4000-8000-000000000002")
    private val testBulkRef = BulkRef("00000000-0000-4000-8000-000000000003")
    private val testPeerId = PeerId("peer-lan-1")

    @Test
    fun testStreamExpectationAndReadinessRecorded() = runTest {
        val transport = LanTransferTransport(
            networkScope = LanNetworkScopeStub(),
            allowLoopbackForTesting = true
        )

        val expectation = StreamExpectation(
            sessionId = testSessionId,
            itemId = testItemId,
            bulkRef = testBulkRef,
            sizeBytes = 4096L
        )

        transport.registerExpectedStream(expectation)
        transport.markItemReady(testBulkRef)

        transport.close()
    }

    @Test
    fun testDisconnectEmitsConnectionEvent() = runTest(UnconfinedTestDispatcher()) {
        val transport = LanTransferTransport(
            networkScope = LanNetworkScopeStub(),
            allowLoopbackForTesting = true
        )

        val events = mutableListOf<ConnectionEvent>()
        val job = launch {
            transport.observeConnectionEvents().collect { events.add(it) }
        }

        transport.disconnect(testPeerId)
        assertEquals(1, events.size)
        assertTrue(events[0] is ConnectionEvent.Disconnected)
        assertEquals(testPeerId, (events[0] as ConnectionEvent.Disconnected).peerId)

        transport.rejectConnection(testPeerId)
        assertTrue(events.any { it is ConnectionEvent.Rejected && it.peerId == testPeerId })

        job.cancel()
        transport.close()
    }

    @Test
    fun testFullLanTransferFlowOverTlsLoopback(): Unit = runBlocking {
        val loopbackAddress = InetAddress.getByName("127.0.0.1")

        val receiverScope = LanNetworkScopeStub()
        val senderScope = LanNetworkScopeStub()

        val receiverTransport = LanTransferTransport(
            networkScope = receiverScope,
            allowLoopbackForTesting = true
        )
        val senderTransport = LanTransferTransport(
            networkScope = senderScope,
            allowLoopbackForTesting = true
        )

        val identity = LanLeaseIdentity.create(preferAndroidKeyStore = false)
        val listener = LanListener(
            identity = identity,
            networkScope = receiverScope,
            bindAddress = loopbackAddress,
            port = 0,
            allowLoopbackForTesting = true
        )
        val authority = InvitationAuthority()

        try {
            receiverTransport.attachServerListener(listener, authority)

            val invitation = authority.mintInvitation(
                endpoints = listOf(LanEndpoint(loopbackAddress.hostAddress ?: "127.0.0.1", listener.localPort)),
                spkiSha256 = identity.spkiSha256,
                deviceName = "Receiver Device"
            )

            val serverPeerId = PeerId("lan-server-peer")
            senderTransport.registerPendingInvitation(serverPeerId, invitation)

            val clientConnectionDeferred = kotlinx.coroutines.CompletableDeferred<ConnectionEvent>()
            val clientConnJob = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                clientConnectionDeferred.complete(
                    senderTransport.observeConnectionEvents().first { it is ConnectionEvent.Connected }
                )
            }

            val serverConnectionDeferred = kotlinx.coroutines.CompletableDeferred<ConnectionEvent>()
            val serverConnJob = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                serverConnectionDeferred.complete(
                    receiverTransport.observeConnectionEvents().first { it is ConnectionEvent.Connected }
                )
            }

            // Connect sender to receiver
            senderTransport.initiateConnection(serverPeerId, "Sender Device")

            // Verify connection events
            val clientConnection = withTimeout(5_000) { clientConnectionDeferred.await() }
            assertTrue(clientConnection is ConnectionEvent.Connected)

            val serverConnection = withTimeout(5_000) { serverConnectionDeferred.await() }
            assertTrue(serverConnection is ConnectionEvent.Connected)
            val receiverConnectedPeer = (serverConnection as ConnectionEvent.Connected).peer

            clientConnJob.cancel()
            serverConnJob.cancel()

            // 1. Control Message exchange
            val testControlPayload = "HELLO_V1_LAN_CONTROL".toByteArray(Charsets.UTF_8)
            val receivedControlDeferred = kotlinx.coroutines.CompletableDeferred<ByteArray>()
            val controlJob = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                val ctrl = receiverTransport.observeControl().first()
                receivedControlDeferred.complete(ctrl.payload)
            }

            senderTransport.sendControl(serverPeerId, testControlPayload)

            val receivedControl = withTimeout(5_000) { receivedControlDeferred.await() }
            assertArrayEquals(testControlPayload, receivedControl)
            controlJob.cancel()

            // 2. Stream transfer
            val testFileBytes = "Test file payload transferred over real TLS 1.3 LAN socket!".toByteArray(Charsets.UTF_8)
            val bulkRef = senderTransport.prepareStream { ByteArrayInputStream(testFileBytes) }!!

            // Register expectation on receiver before data arrives
            receiverTransport.registerExpectedStream(
                StreamExpectation(
                    sessionId = testSessionId,
                    itemId = testItemId,
                    bulkRef = bulkRef,
                    sizeBytes = testFileBytes.size.toLong()
                )
            )

            // Launch receiver incoming stream collector
            val incomingStreamDeferred = kotlinx.coroutines.CompletableDeferred<ByteArray>()
            val receiverJob = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                val incoming = receiverTransport.observeIncomingStream().first { it.bulkRef == bulkRef }
                val receivedData = incoming.stream.readBytes()
                incomingStreamDeferred.complete(receivedData)
            }

            // Sender sends stream
            val progressList = senderTransport.sendStream(serverPeerId, bulkRef).toList()
            assertTrue(progressList.isNotEmpty())
            assertTrue(progressList.last().isComplete)
            assertEquals(testFileBytes.size.toLong(), progressList.last().bytesTransferred)

            val receivedBytes = withTimeout(5_000) {
                incomingStreamDeferred.await()
            }
            assertArrayEquals(testFileBytes, receivedBytes)

            receiverJob.cancel()
        } finally {
            senderTransport.close()
            receiverTransport.close()
            listener.close()
            identity.close()
        }
    }

    private class LanNetworkScopeStub : LanNetworkScope(context = null, connectivityManager = null)
}
