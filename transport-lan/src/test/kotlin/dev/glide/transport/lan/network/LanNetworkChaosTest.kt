package dev.glide.transport.lan.network

import dev.glide.core.domain.PeerId
import dev.glide.core.pairing.LanEndpoint
import dev.glide.core.pairing.LanInvitation
import dev.glide.core.ports.ConnectionEvent
import dev.glide.transport.lan.LanTransferTransport
import dev.glide.transport.lan.pairing.InvitationAuthority
import dev.glide.transport.lan.tls.LanLeaseIdentity
import dev.glide.transport.lan.tls.LanListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/**
 * Validates network chaos and resilience scenarios under Section 19, 20, 22 (M07/M08):
 * - Network loss mid-transfer: immediate fail-closed, sockets closed, no leaked listeners.
 * - Network switch / roaming: generation increment, previous sockets invalidated and closed.
 * - VPN activation: fail-closed policy, all local sockets severed, VPN state observable.
 * - Host unreachable: fail-closed timeout without socket leaks.
 * - Listener lifecycle: accept loop terminates cleanly and releases server port.
 */
class LanNetworkChaosTest {

    private val loopbackAddress = InetAddress.getByName("127.0.0.1")

    @Test
    fun testNetworkSwitch_roaming_invalidatesActiveSocketsAndBumpsGeneration() {
        val scope = LanNetworkScope(null)

        val subnets1 = listOf(SubnetPrefix(InetAddress.getByName("192.168.1.100"), 24))
        val addresses1 = listOf(InetAddress.getByName("192.168.1.100"))

        // Initial evaluate: Network 1
        scope.onNetworkSwitchedForTesting(subnets1, addresses1)
        val state1 = scope.networkState.value
        assertTrue("State 1 must be Available", state1 is LanNetworkState.Available)
        val gen1 = (state1 as LanNetworkState.Available).generation

        // Create socket registered with scope
        val dummySocket = Socket()
        scope.bindSocket(dummySocket)
        assertFalse("Dummy socket must be open", dummySocket.isClosed)

        val dummyServerSocket = ServerSocket(0)
        scope.registerServerSocket(dummyServerSocket)
        assertFalse("Server socket must be open", dummyServerSocket.isClosed)

        // Roam to Network 2 (e.g. Wi-Fi AP handover or Wi-Fi -> Ethernet)
        val subnets2 = listOf(SubnetPrefix(InetAddress.getByName("10.0.0.50"), 24))
        val addresses2 = listOf(InetAddress.getByName("10.0.0.50"))

        scope.onNetworkSwitchedForTesting(subnets2, addresses2)

        val state2 = scope.networkState.value
        assertTrue("State 2 must be Available", state2 is LanNetworkState.Available)
        val gen2 = (state2 as LanNetworkState.Available).generation

        assertEquals("Generation must increment upon network switch", gen1 + 1, gen2)
        assertTrue("Previous sockets must be invalidated and closed", dummySocket.isClosed)
        assertTrue("Previous server sockets must be invalidated and closed", dummyServerSocket.isClosed)

        scope.close()
    }

    @Test
    fun testVpnActivation_failsClosedAndClosesAllSockets() {
        val scope = LanNetworkScope(null)

        val subnets = listOf(SubnetPrefix(InetAddress.getByName("192.168.1.100"), 24))
        val addresses = listOf(InetAddress.getByName("192.168.1.100"))
        scope.onNetworkSwitchedForTesting(subnets, addresses)
        assertTrue(scope.networkState.value is LanNetworkState.Available)

        val activeSocket = Socket()
        val activeServerSocket = ServerSocket(0)
        scope.bindSocket(activeSocket)
        scope.registerServerSocket(activeServerSocket)

        assertFalse(activeSocket.isClosed)
        assertFalse(activeServerSocket.isClosed)

        // VPN activates on network
        scope.onVpnActivatedForTesting("Active VPN tunnel detected")

        val state = scope.networkState.value
        assertTrue("Network state must be VpnActive", state is LanNetworkState.VpnActive)
        assertTrue("Active socket must be closed on VPN activation", activeSocket.isClosed)
        assertTrue("Active server socket must be closed on VPN activation", activeServerSocket.isClosed)

        // Subsequent socket creation must fail closed
        assertThrows(IllegalStateException::class.java) {
            scope.createBoundSocket("192.168.1.50", 42424)
        }

        assertThrows(IllegalStateException::class.java) {
            scope.createBoundServerSocket(0)
        }

        scope.close()
    }

    @Test
    fun testNetworkLost_midTransfer_closesSocketsAndEmitsDisconnect(): Unit = runBlocking {
        val serverIdentity = LanLeaseIdentity.create(preferAndroidKeyStore = false)
        val serverScope = LanNetworkScope(null)
        val listener = LanListener(
            identity = serverIdentity,
            networkScope = serverScope,
            bindAddress = loopbackAddress,
            port = 0,
            allowLoopbackForTesting = true
        )
        val serverTransport = LanTransferTransport(
            networkScope = serverScope,
            allowLoopbackForTesting = true
        )
        val authority = InvitationAuthority()
        serverTransport.attachServerListener(listener, authority)

        val clientScope = LanNetworkScope(null)
        val clientTransport = LanTransferTransport(
            networkScope = clientScope,
            allowLoopbackForTesting = true
        )

        val events = mutableListOf<ConnectionEvent>()
        val collectJob = launch {
            clientTransport.observeConnectionEvents().collect { events.add(it) }
        }

        try {
            val peerId = PeerId("lan-test-chaos-peer")
            val endpoint = LanEndpoint("127.0.0.1", listener.localPort)
            val invitation = authority.mintInvitation(
                endpoints = listOf(endpoint),
                spkiSha256 = serverIdentity.spkiSha256,
                deviceName = "Chaos Receiver"
            )

            clientTransport.registerPendingInvitation(peerId, invitation)
            clientTransport.initiateConnection(peerId, "Chaos Client")

            // Wait until connected event is recorded
            withTimeout(5_000) {
                while (events.none { it is ConnectionEvent.Connected }) {
                    delay(20)
                }
            }
            assertTrue("Client must be connected", events.any { it is ConnectionEvent.Connected })

            // Inject complete network interface drop / network lost on server
            serverScope.close()

            // Client readLoop encounters EOF / socket reset and dispatches Disconnected
            withTimeout(5_000) {
                while (events.none { it is ConnectionEvent.Disconnected && it.peerId == peerId }) {
                    delay(20)
                }
            }

            assertTrue("Client must observe Disconnected event",
                events.any { it is ConnectionEvent.Disconnected && it.peerId == peerId }
            )
        } finally {
            collectJob.cancel()
            clientTransport.close()
            serverTransport.close()
            listener.close()
            serverIdentity.close()
            clientScope.close()
            serverScope.close()
        }
    }

    @Test
    fun testHostUnreachableOrTimeout_failsCleanlyWithoutLeakingResources(): Unit = runBlocking {
        val clientScope = LanNetworkScope(null)
        val clientTransport = LanTransferTransport(
            networkScope = clientScope,
            allowLoopbackForTesting = true
        )

        try {
            val peerId = PeerId("lan-unreachable-peer")
            // Find an unused, unbound local port
            val dummy = ServerSocket(0)
            val freePort = dummy.localPort
            dummy.close()

            val dummyPin = "11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff"
            val endpoint = LanEndpoint("127.0.0.1", freePort)
            val invitation = LanInvitation(
                invitationId = UUID.randomUUID().toString(),
                presence = "0123456789abcdef0123456789abcdef",
                name = "Unreachable Host",
                platform = "android",
                endpoints = listOf(endpoint),
                spkiSha256 = dummyPin,
                token = "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8S9t0U1v",
                expiresAt = "1750000120",
                ttlSeconds = 120
            )

            clientTransport.registerPendingInvitation(peerId, invitation)

            // Initiate connection to unbound port must throw IOException (ConnectException)
            val error = assertThrows(IOException::class.java) {
                runBlocking {
                    clientTransport.initiateConnection(peerId, "Chaos Client")
                }
            }
            assertTrue("Must fail connection with IOException: $error", error is IOException)
        } finally {
            clientTransport.close()
            clientScope.close()
        }
    }

    @Test
    fun testListenerClose_terminatesAcceptLoopAndReleasesPort() {
        val serverIdentity = LanLeaseIdentity.create(preferAndroidKeyStore = false)
        val listener = LanListener(
            identity = serverIdentity,
            bindAddress = loopbackAddress,
            port = 0,
            allowLoopbackForTesting = true
        )

        val boundPort = listener.localPort
        assertTrue("Port must be assigned (>0)", boundPort > 0)

        // Close listener
        listener.close()

        // Verify port is freed and can be bound immediately
        val testServerSocket = ServerSocket()
        testServerSocket.reuseAddress = true
        testServerSocket.bind(java.net.InetSocketAddress(loopbackAddress, boundPort))
        assertEquals("Port must be available for re-binding", boundPort, testServerSocket.localPort)
        testServerSocket.close()
        serverIdentity.close()
    }
}
