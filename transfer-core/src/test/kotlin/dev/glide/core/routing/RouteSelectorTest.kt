package dev.glide.core.routing

import dev.glide.core.discovery.CandidateVerificationStatus
import dev.glide.core.discovery.PeerCandidate
import dev.glide.core.domain.PeerId
import dev.glide.core.ports.VerificationEvidence
import dev.glide.core.transport.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class RouteSelectorTest {

    private val defaultContext = RouteFilterContext(
        hasGms = true,
        apiLevel = 34,
        hasNearbyPermission = true,
        hasLocalNetworkPermission = true,
        activeNetworkEligible = true,
        implementedTransports = setOf(TransportType.GMS_NEARBY_P2P, TransportType.LAN_NSD_TLS)
    )

    @Test
    fun testPinnedQrCandidate_selectsOnlyLanRoute() {
        val qrRoute = RouteCandidate(
            routeId = "route-lan-1",
            transportType = TransportType.LAN_NSD_TLS,
            endpointHandle = "192.168.1.100:49152",
            trustEvidence = VerificationEvidence.InvitationVerified("inv-1", "pin-1")
        )
        val nearbyRoute = RouteCandidate(
            routeId = "route-nearby-1",
            transportType = TransportType.GMS_NEARBY_P2P,
            endpointHandle = "nearby-endpoint-1"
        )
        val candidate = PeerCandidate(
            uiKey = "qr:inv-1",
            displayName = "Target Device",
            routes = listOf(qrRoute, nearbyRoute),
            verificationStatus = CandidateVerificationStatus.PINNED_INVITATION
        )

        val result = RouteSelector.selectRoute(candidate, defaultContext)
        assertTrue("Pinned QR candidate must select route", result is RouteSelectionResult.Selected)
        val selected = (result as RouteSelectionResult.Selected).route
        assertEquals(TransportType.LAN_NSD_TLS, selected.transportType)
        assertEquals("192.168.1.100:49152", selected.endpointHandle)
    }

    @Test
    fun testNearbyCandidate_selectsNearbyWithSdkVerification() {
        val nearbyRoute = RouteCandidate(
            routeId = "route-nearby-1",
            transportType = TransportType.GMS_NEARBY_P2P,
            endpointHandle = "nearby-endpoint-1"
        )
        val candidate = PeerCandidate(
            uiKey = "nearby:nearby-endpoint-1",
            displayName = "Nearby Peer",
            routes = listOf(nearbyRoute),
            verificationStatus = CandidateVerificationStatus.UNVERIFIED
        )

        val result = RouteSelector.selectRoute(candidate, defaultContext)
        assertTrue(result is RouteSelectionResult.Selected)
        val selected = (result as RouteSelectionResult.Selected).route
        assertEquals(TransportType.GMS_NEARBY_P2P, selected.transportType)
        assertEquals("nearby-endpoint-1", selected.endpointHandle)
    }

    @Test
    fun testLanOnlyCandidate_requiresInvitation() {
        val lanRoute = RouteCandidate(
            routeId = "route-lan-1",
            transportType = TransportType.LAN_NSD_TLS,
            endpointHandle = "192.168.1.50:42424"
        )
        val candidate = PeerCandidate(
            uiKey = "lan-nsd:local:192.168.1.50:42424",
            displayName = "LAN Device",
            routes = listOf(lanRoute),
            verificationStatus = CandidateVerificationStatus.UNVERIFIED
        )

        val result = RouteSelector.selectRoute(candidate, defaultContext)
        assertTrue("Unpaired LAN candidate must require invitation", result is RouteSelectionResult.RequiresInvitation)
        val req = result as RouteSelectionResult.RequiresInvitation
        assertEquals(candidate.uiKey, req.candidateUiKey)
    }

    @Test
    fun testMissingGms_rejectsNearbyRoute() {
        val nearbyRoute = RouteCandidate(
            routeId = "route-nearby-1",
            transportType = TransportType.GMS_NEARBY_P2P,
            endpointHandle = "nearby-endpoint-1"
        )
        val candidate = PeerCandidate(
            uiKey = "nearby:nearby-endpoint-1",
            displayName = "Nearby Peer",
            routes = listOf(nearbyRoute)
        )

        val noGmsContext = defaultContext.copy(hasGms = false)
        val result = RouteSelector.selectRoute(candidate, noGmsContext)
        assertTrue(result is RouteSelectionResult.Rejected)
        val rejected = result as RouteSelectionResult.Rejected
        assertTrue(rejected.reason.contains("GMS"))
    }

    @Test
    fun testApiLevelBelow29_rejectsLanRoute() {
        val lanRoute = RouteCandidate(
            routeId = "route-lan-1",
            transportType = TransportType.LAN_NSD_TLS,
            endpointHandle = "192.168.1.50:42424"
        )
        val candidate = PeerCandidate(
            uiKey = "lan-nsd:local:192.168.1.50:42424",
            displayName = "LAN Device",
            routes = listOf(lanRoute)
        )

        val oldApiContext = defaultContext.copy(apiLevel = 28)
        val result = RouteSelector.selectRoute(candidate, oldApiContext)
        assertTrue(result is RouteSelectionResult.Rejected)
        val rejected = result as RouteSelectionResult.Rejected
        assertTrue(rejected.reason.contains("API 29+"))
    }

    @Test
    fun testMissingLocalNetworkPermission_rejectsLanRoute() {
        val lanRoute = RouteCandidate(
            routeId = "route-lan-1",
            transportType = TransportType.LAN_NSD_TLS,
            endpointHandle = "192.168.1.50:42424"
        )
        val candidate = PeerCandidate(
            uiKey = "lan-nsd:local:192.168.1.50:42424",
            displayName = "LAN Device",
            routes = listOf(lanRoute)
        )

        val noPermContext = defaultContext.copy(hasLocalNetworkPermission = false)
        val result = RouteSelector.selectRoute(candidate, noPermContext)
        assertTrue(result is RouteSelectionResult.Rejected)
        val rejected = result as RouteSelectionResult.Rejected
        assertTrue(rejected.reason.contains("permission not granted"))
    }

    @Test
    fun testIncompatibleProtocolVersion_rejected() {
        val badRoute = RouteCandidate(
            routeId = "route-bad-1",
            transportType = TransportType.GMS_NEARBY_P2P,
            protocolVersion = 2, // Incompatible!
            endpointHandle = "endpoint-1"
        )
        val candidate = PeerCandidate(
            uiKey = "nearby:endpoint-1",
            displayName = "Device",
            routes = listOf(badRoute)
        )

        val result = RouteSelector.selectRoute(candidate, defaultContext)
        assertTrue(result is RouteSelectionResult.Rejected)
        assertTrue((result as RouteSelectionResult.Rejected).reason.contains("Incompatible protocol version"))
    }

    @Test
    fun testExpiredRoute_rejected() {
        val expiredRoute = RouteCandidate(
            routeId = "route-exp-1",
            transportType = TransportType.GMS_NEARBY_P2P,
            endpointHandle = "endpoint-1",
            expiresAtMs = 1000L
        )
        val candidate = PeerCandidate(
            uiKey = "nearby:endpoint-1",
            displayName = "Device",
            routes = listOf(expiredRoute)
        )

        val result = RouteSelector.selectRoute(candidate, defaultContext, nowMs = 2000L)
        assertTrue(result is RouteSelectionResult.Rejected)
        assertTrue((result as RouteSelectionResult.Rejected).reason.contains("expired"))
    }

    @Test
    fun testIneligibleNetwork_rejectsLan() {
        val lanRoute = RouteCandidate(
            routeId = "route-lan-1",
            transportType = TransportType.LAN_NSD_TLS,
            endpointHandle = "192.168.1.50:42424"
        )
        val candidate = PeerCandidate(
            uiKey = "lan-nsd:local:192.168.1.50:42424",
            displayName = "LAN Device",
            routes = listOf(lanRoute)
        )

        val vpnContext = defaultContext.copy(activeNetworkEligible = false)
        val result = RouteSelector.selectRoute(candidate, vpnContext)
        assertTrue(result is RouteSelectionResult.Rejected)
        assertTrue((result as RouteSelectionResult.Rejected).reason.contains("not eligible for LAN transfer"))
    }

    @Test
    fun testFailedProbe_rejected() {
        val failedRoute = RouteCandidate(
            routeId = "route-probe-1",
            transportType = TransportType.GMS_NEARBY_P2P,
            endpointHandle = "endpoint-1",
            lastProbeResult = ProbeResult.UNREACHABLE
        )
        val candidate = PeerCandidate(
            uiKey = "nearby:endpoint-1",
            displayName = "Device",
            routes = listOf(failedRoute)
        )

        val result = RouteSelector.selectRoute(candidate, defaultContext)
        assertTrue(result is RouteSelectionResult.Rejected)
        assertTrue((result as RouteSelectionResult.Rejected).reason.contains("reachability probe"))
    }

    @Test
    fun testAssertRouteLocked_forbidsMidTransferSwitching() {
        val attemptId = UUID.randomUUID().toString()
        val originalRoute = SelectedRoute(
            attemptId = attemptId,
            transportType = TransportType.LAN_NSD_TLS,
            peerId = PeerId("peer-lan-1"),
            endpointHandle = "192.168.1.50:42424"
        )

        // Matching route succeeds
        RouteSelector.assertRouteLocked(originalRoute, originalRoute.copy())

        // Different attemptId fails
        try {
            RouteSelector.assertRouteLocked(originalRoute, originalRoute.copy(attemptId = UUID.randomUUID().toString()))
            fail("Expected rejection for different attemptId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("locked") == true)
        }

        // Different transportType fails
        try {
            RouteSelector.assertRouteLocked(originalRoute, originalRoute.copy(transportType = TransportType.GMS_NEARBY_P2P))
            fail("Expected rejection for transport switch")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("locked") == true)
        }
    }
}
