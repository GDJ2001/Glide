package dev.glide.app.routing

import dev.glide.core.discovery.CandidateVerificationStatus
import dev.glide.core.discovery.PeerCandidate
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import dev.glide.core.ports.AdvertisingEvent
import dev.glide.core.ports.BulkProgress
import dev.glide.core.ports.ConnectionEvent
import dev.glide.core.ports.ControlMessage
import dev.glide.core.ports.DiscoveryEvent
import dev.glide.core.ports.IncomingStream
import dev.glide.core.ports.InputStreamProvider
import dev.glide.core.ports.TransferTransport
import dev.glide.core.ports.VerificationEvidence
import dev.glide.core.routing.RouteCandidate
import dev.glide.core.routing.RouteSelectionResult
import dev.glide.core.routing.SelectedRoute
import dev.glide.core.transport.TransportType
import dev.glide.transport.lan.LanTransferTransport
import dev.glide.transport.lan.LanTransportFactory
import dev.glide.transport.lan.network.LanNetworkScope
import dev.glide.transport.lan.network.LanNetworkState
import dev.glide.transport.lan.network.SubnetPrefix
import dev.glide.transport.nearby.NearbyHardwareStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.util.UUID

class TransportRouterTest {

    private class FakeTransferTransport : TransferTransport {
        override suspend fun startDiscovery(serviceId: String): Flow<DiscoveryEvent> = emptyFlow()
        override suspend fun stopDiscovery() {}
        override suspend fun startAdvertising(serviceId: String, localDeviceName: String): Flow<AdvertisingEvent> = emptyFlow()
        override suspend fun stopAdvertising() {}
        override suspend fun initiateConnection(peerId: PeerId, localDeviceName: String) {}
        override suspend fun acceptConnection(peerId: PeerId) {}
        override suspend fun rejectConnection(peerId: PeerId) {}
        override fun observeConnectionEvents(): Flow<ConnectionEvent> = emptyFlow()
        override suspend fun sendControl(peerId: PeerId, payload: ByteArray) {}
        override fun observeControl(): Flow<ControlMessage> = emptyFlow()
        override suspend fun sendStream(peerId: PeerId, bulkRef: BulkRef, streamSource: InputStreamProvider): Flow<BulkProgress> = emptyFlow()
        override fun observeIncomingStream(): Flow<IncomingStream> = emptyFlow()
        override suspend fun cancelPayload(bulkRef: BulkRef) {}
        override suspend fun disconnect(peerId: PeerId) {}
    }

    private val fakeNearbyTransport = FakeTransferTransport()

    private fun createNetworkScopeStub(state: LanNetworkState): LanNetworkScope {
        val scope = LanNetworkScope(null, null)
        scope.updateNetworkStateForTesting(state)
        return scope
    }

    private fun createLanTransportFactoryStub(): LanTransportFactory {
        return object : LanTransportFactory(null) {
            override fun createTransport(
                allowLoopbackForTesting: Boolean,
                coroutineScope: CoroutineScope
            ): LanTransferTransport {
                throw UnsupportedOperationException("Stub")
            }
        }
    }

    @Test
    fun testScannedQrInvitation_selectsOnlyPinnedLanRoute_prohibitsNearbyFallback() {
        val networkScope = createNetworkScopeStub(
            LanNetworkState.Available(
                network = null,
                localAddresses = listOf(InetAddress.getByName("192.168.1.10")),
                subnets = listOf(SubnetPrefix(InetAddress.getByName("192.168.1.0"), 24)),
                generation = 1L
            )
        )

        val router = TransportRouter(
            context = null,
            lanTransportFactory = createLanTransportFactoryStub(),
            lanNetworkScope = networkScope,
            nearbyAvailabilityChecker = { NearbyHardwareStatus.Available },
            permissionChecker = { true },
            apiLevelProvider = { 34 },
            nearbyTransportFactory = { fakeNearbyTransport }
        )

        val pinnedLanRoute = RouteCandidate(
            routeId = "route-pinned-lan",
            transportType = TransportType.LAN_NSD_TLS,
            endpointHandle = "192.168.1.50:50000",
            trustEvidence = VerificationEvidence.InvitationVerified(
                invitationId = "invitation-123",
                serverPin = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            )
        )

        // Candidate with pinned QR invitation
        val candidate = PeerCandidate(
            uiKey = "qr:invitation-123",
            displayName = "Target Device",
            routes = listOf(pinnedLanRoute),
            verificationStatus = CandidateVerificationStatus.PINNED_INVITATION
        )

        val result = router.evaluateRoute(candidate)
        assertTrue("Pinned invitation must evaluate to Selected", result is RouteSelectionResult.Selected)
        val selected = (result as RouteSelectionResult.Selected).route
        assertEquals("Pinned invitation MUST select LAN_NSD_TLS", TransportType.LAN_NSD_TLS, selected.transportType)
        assertEquals("192.168.1.50:50000", selected.endpointHandle)
    }

    @Test
    fun testNearbyCandidate_withNoProvenLanBinding_selectsNearbyTransport() {
        val router = TransportRouter(
            context = null,
            nearbyAvailabilityChecker = { NearbyHardwareStatus.Available },
            permissionChecker = { true },
            apiLevelProvider = { 34 },
            nearbyTransportFactory = { fakeNearbyTransport }
        )

        val nearbyRoute = RouteCandidate(
            routeId = "route-nearby-1",
            transportType = TransportType.GMS_NEARBY_P2P,
            endpointHandle = "nearby-endpoint-alpha"
        )

        val candidate = PeerCandidate(
            uiKey = "nearby:endpoint-alpha",
            displayName = "Alice's Phone",
            routes = listOf(nearbyRoute),
            verificationStatus = CandidateVerificationStatus.UNVERIFIED
        )

        val result = router.evaluateRoute(candidate)
        assertTrue(result is RouteSelectionResult.Selected)
        val selected = (result as RouteSelectionResult.Selected).route
        assertEquals(TransportType.GMS_NEARBY_P2P, selected.transportType)
        assertEquals("nearby-endpoint-alpha", selected.endpointHandle)
    }

    @Test
    fun testUnauthenticatedLanCandidate_yieldsRequiresInvitation() {
        val networkScope = createNetworkScopeStub(
            LanNetworkState.Available(
                network = null,
                localAddresses = listOf(InetAddress.getByName("192.168.1.10")),
                subnets = listOf(SubnetPrefix(InetAddress.getByName("192.168.1.0"), 24)),
                generation = 1L
            )
        )

        val router = TransportRouter(
            context = null,
            lanTransportFactory = createLanTransportFactoryStub(),
            lanNetworkScope = networkScope,
            nearbyAvailabilityChecker = { NearbyHardwareStatus.Available },
            permissionChecker = { true },
            apiLevelProvider = { 34 }
        )

        val lanRoute = RouteCandidate(
            routeId = "route-lan-1",
            transportType = TransportType.LAN_NSD_TLS,
            endpointHandle = "192.168.1.50:50001"
        )

        val candidate = PeerCandidate(
            uiKey = "lan-nsd:local:192.168.1.50:50001",
            displayName = "Bob's Workstation",
            routes = listOf(lanRoute),
            verificationStatus = CandidateVerificationStatus.UNVERIFIED
        )

        val result = router.evaluateRoute(candidate)
        assertTrue(
            "Unpaired LAN peer must require invitation per Section 10",
            result is RouteSelectionResult.RequiresInvitation
        )
    }

    @Test
    fun testMissingGms_rejectsNearbyRoute() {
        val router = TransportRouter(
            context = null,
            nearbyAvailabilityChecker = { NearbyHardwareStatus.PlayServicesUnavailable(2, false) },
            permissionChecker = { true },
            apiLevelProvider = { 34 }
        )

        val nearbyRoute = RouteCandidate(
            routeId = "route-nearby-2",
            transportType = TransportType.GMS_NEARBY_P2P,
            endpointHandle = "nearby-endpoint-beta"
        )

        val candidate = PeerCandidate(
            uiKey = "nearby:endpoint-beta",
            displayName = "GMS-less Phone",
            routes = listOf(nearbyRoute)
        )

        val result = router.evaluateRoute(candidate)
        assertTrue(result is RouteSelectionResult.Rejected)
        val rejected = result as RouteSelectionResult.Rejected
        assertTrue(rejected.reason.contains("Google Play Services"))
    }

    @Test
    fun testDeniedNearbyPermission_rejectsNearbyRoute() {
        val router = TransportRouter(
            context = null,
            nearbyAvailabilityChecker = { NearbyHardwareStatus.Available },
            permissionChecker = { false }, // Permissions denied
            apiLevelProvider = { 34 }
        )

        val nearbyRoute = RouteCandidate(
            routeId = "route-nearby-3",
            transportType = TransportType.GMS_NEARBY_P2P,
            endpointHandle = "nearby-endpoint-gamma"
        )

        val candidate = PeerCandidate(
            uiKey = "nearby:endpoint-gamma",
            displayName = "Permission Denied Device",
            routes = listOf(nearbyRoute)
        )

        val result = router.evaluateRoute(candidate)
        assertTrue(result is RouteSelectionResult.Rejected)
        val rejected = result as RouteSelectionResult.Rejected
        assertTrue(rejected.reason.contains("permission not granted"))
    }

    @Test
    fun testApiBelow29_rejectsLanRoute() {
        val networkScope = createNetworkScopeStub(
            LanNetworkState.Available(
                network = null,
                localAddresses = listOf(InetAddress.getByName("192.168.1.10")),
                subnets = listOf(SubnetPrefix(InetAddress.getByName("192.168.1.0"), 24)),
                generation = 1L
            )
        )

        val router = TransportRouter(
            context = null,
            lanTransportFactory = createLanTransportFactoryStub(),
            lanNetworkScope = networkScope,
            nearbyAvailabilityChecker = { NearbyHardwareStatus.Available },
            permissionChecker = { true },
            apiLevelProvider = { 28 } // Android 9 (API 28) - below TLS 1.3 gate
        )

        val lanRoute = RouteCandidate(
            routeId = "route-lan-api28",
            transportType = TransportType.LAN_NSD_TLS,
            endpointHandle = "192.168.1.50:50000",
            trustEvidence = VerificationEvidence.InvitationVerified(
                invitationId = "invite-api28",
                serverPin = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            )
        )

        val candidate = PeerCandidate(
            uiKey = "qr:invite-api28",
            displayName = "Legacy Device",
            routes = listOf(lanRoute),
            verificationStatus = CandidateVerificationStatus.PINNED_INVITATION
        )

        val result = router.evaluateRoute(candidate)
        assertTrue(result is RouteSelectionResult.Rejected)
        val rejected = result as RouteSelectionResult.Rejected
        assertTrue(rejected.reason.contains("API 29+"))
    }

    @Test
    fun testIneligibleNetwork_rejectsLanRoute() {
        val networkScope = createNetworkScopeStub(
            LanNetworkState.VpnActive("Active corporate VPN")
        )

        val router = TransportRouter(
            context = null,
            lanTransportFactory = createLanTransportFactoryStub(),
            lanNetworkScope = networkScope,
            nearbyAvailabilityChecker = { NearbyHardwareStatus.Available },
            permissionChecker = { true },
            apiLevelProvider = { 34 }
        )

        val lanRoute = RouteCandidate(
            routeId = "route-lan-vpn",
            transportType = TransportType.LAN_NSD_TLS,
            endpointHandle = "192.168.1.50:50000",
            trustEvidence = VerificationEvidence.InvitationVerified(
                invitationId = "invite-vpn",
                serverPin = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            )
        )

        val candidate = PeerCandidate(
            uiKey = "qr:invite-vpn",
            displayName = "VPN Device",
            routes = listOf(lanRoute),
            verificationStatus = CandidateVerificationStatus.PINNED_INVITATION
        )

        val result = router.evaluateRoute(candidate)
        assertTrue(result is RouteSelectionResult.Rejected)
        val rejected = result as RouteSelectionResult.Rejected
        assertTrue(rejected.reason.contains("not eligible for LAN transfer"))
    }

    @Test
    fun testMidTransferRouteSwitch_strictlyProhibited() {
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val router = TransportRouter(
            context = null,
            nearbyAvailabilityChecker = { NearbyHardwareStatus.Available },
            permissionChecker = { true },
            apiLevelProvider = { 34 },
            nearbyTransportFactory = { fakeNearbyTransport }
        )

        val attemptId = UUID.randomUUID().toString()
        val route1 = SelectedRoute(
            attemptId = attemptId,
            transportType = TransportType.GMS_NEARBY_P2P,
            peerId = dev.glide.core.domain.PeerId("peer-1"),
            endpointHandle = "endpoint-1"
        )

        // Lock route 1
        val transport1 = router.resolveTransport(route1, testScope)
        assertNotNull(transport1)
        assertEquals(route1, router.getActiveRoute())

        // Attempting to resolve a different route mid-transfer throws IllegalArgumentException from assertRouteLocked
        val route2 = SelectedRoute(
            attemptId = UUID.randomUUID().toString(), // Different attempt!
            transportType = TransportType.GMS_NEARBY_P2P,
            peerId = dev.glide.core.domain.PeerId("peer-2"),
            endpointHandle = "endpoint-2"
        )

        try {
            router.resolveTransport(route2, testScope)
            fail("Mid-transfer route switch must throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Active transfer route is locked") == true)
        }

        // Release lock
        router.releaseActiveRoute()
        assertNull(router.getActiveRoute())

        // Now route 2 can be locked
        val transport2 = router.resolveTransport(route2, testScope)
        assertNotNull(transport2)
        assertEquals(route2, router.getActiveRoute())

        router.releaseActiveRoute()
    }
}
