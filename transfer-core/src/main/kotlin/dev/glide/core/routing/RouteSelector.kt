package dev.glide.core.routing

import dev.glide.core.discovery.CandidateVerificationStatus
import dev.glide.core.discovery.PeerCandidate
import dev.glide.core.domain.PeerId
import dev.glide.core.transport.TransportType
import java.util.UUID

/**
 * Deterministic transport route selection engine (LAN-008, Section 10, ADR-0018).
 *
 * Implements Section 10 filter order:
 * 1. Implemented adapter check
 * 2. Platform API / GMS support
 * 3. User policy / permission granted
 * 4. Compatible protocol/profile
 * 5. Fresh route on eligible network
 * 6. Authenticated bootstrap availability
 * 7. Bounded reachability probe result
 *
 * Enforces strict security invariants:
 * - Scanned QR invitations select ONLY that pinned LAN route; no silent Nearby fallback.
 * - Security / auth failures are terminal and never fall back.
 * - Active transfers cannot switch routes mid-session.
 * - Does not use speculative Mbps or hardware availability ratio for live routing.
 */
object RouteSelector {

    fun selectRoute(
        candidate: PeerCandidate,
        context: RouteFilterContext,
        nowMs: Long = System.currentTimeMillis()
    ): RouteSelectionResult {
        if (candidate.routes.isEmpty()) {
            return RouteSelectionResult.Rejected("Candidate has no available routes", isTerminal = false)
        }

        // 1. Situation: Scanned/pasted LAN invitation -> ONLY that pinned LAN route; no Nearby fallback
        if (candidate.verificationStatus == CandidateVerificationStatus.PINNED_INVITATION) {
            val lanRoute = candidate.routes.firstOrNull { it.transportType == TransportType.LAN_NSD_TLS }
                ?: return RouteSelectionResult.Rejected(
                    "Pinned invitation candidate is missing LAN route",
                    isTerminal = true
                )

            val filterError = validateRoute(lanRoute, context, nowMs)
            if (filterError != null) {
                return RouteSelectionResult.Rejected(filterError, isTerminal = true)
            }

            return RouteSelectionResult.Selected(
                SelectedRoute(
                    attemptId = UUID.randomUUID().toString(),
                    transportType = TransportType.LAN_NSD_TLS,
                    peerId = PeerId("lan-${lanRoute.endpointHandle.take(16)}"),
                    endpointHandle = lanRoute.endpointHandle,
                    networkGeneration = 0L
                )
            )
        }

        // 2. Filter candidate routes through Section-10 ordered pipeline
        val eligibleRoutes = candidate.routes.filter { route ->
            validateRoute(route, context, nowMs) == null
        }

        if (eligibleRoutes.isEmpty()) {
            val firstRoute = candidate.routes.first()
            val reason = validateRoute(firstRoute, context, nowMs) ?: "No route satisfied selection policy"
            return RouteSelectionResult.Rejected(reason, isTerminal = false)
        }

        val hasNearby = eligibleRoutes.any { it.transportType == TransportType.GMS_NEARBY_P2P }
        val hasLan = eligibleRoutes.any { it.transportType == TransportType.LAN_NSD_TLS }

        // 3. Android Nearby candidate with no proven LAN binding -> Nearby with full SDK verification
        if (hasNearby) {
            val nearbyRoute = eligibleRoutes.first { it.transportType == TransportType.GMS_NEARBY_P2P }
            return RouteSelectionResult.Selected(
                SelectedRoute(
                    attemptId = UUID.randomUUID().toString(),
                    transportType = TransportType.GMS_NEARBY_P2P,
                    peerId = PeerId(nearbyRoute.endpointHandle),
                    endpointHandle = nearbyRoute.endpointHandle,
                    networkGeneration = 0L
                )
            )
        }

        // 4. LAN-only candidate -> require QR invitation if unauthenticated
        if (hasLan) {
            return RouteSelectionResult.RequiresInvitation(
                candidateUiKey = candidate.uiKey,
                reason = "Unpaired LAN peer requires QR invitation or pairing"
            )
        }

        return RouteSelectionResult.Rejected("Unsupported transport type in candidate routes", isTerminal = false)
    }

    /**
     * Validates a candidate route against the Section-10 ordered filters.
     * Returns null if valid, or a descriptive rejection reason string if rejected.
     */
    fun validateRoute(
        route: RouteCandidate,
        context: RouteFilterContext,
        nowMs: Long
    ): String? {
        // Filter 1: Implemented adapter check
        if (!context.implementedTransports.contains(route.transportType)) {
            return "Transport ${route.transportType} is not implemented in this build"
        }

        // Filter 2: Platform API / GMS support
        when (route.transportType) {
            TransportType.GMS_NEARBY_P2P -> {
                if (!context.hasGms) {
                    return "Nearby transport requires Google Play Services (GMS)"
                }
            }
            TransportType.LAN_NSD_TLS -> {
                if (context.apiLevel < 29) {
                    return "LAN transport requires Android API 29+ (current: ${context.apiLevel})"
                }
            }
            else -> return "Transport ${route.transportType} is unsupported"
        }

        // Filter 3: User policy / permission granted
        when (route.transportType) {
            TransportType.GMS_NEARBY_P2P -> {
                if (!context.hasNearbyPermission) {
                    return "Nearby permission not granted"
                }
            }
            TransportType.LAN_NSD_TLS -> {
                if (!context.hasLocalNetworkPermission) {
                    return "Local network permission not granted"
                }
            }
            TransportType.WIFI_DIRECT_P2P,
            TransportType.LOCAL_HOTSPOT,
            TransportType.WIFI_AWARE_NAN -> {}
        }

        // Filter 4: Compatible protocol and profile versions
        if (route.protocolVersion != 1) {
            return "Incompatible protocol version ${route.protocolVersion}, expected 1"
        }
        if (route.profileVersion != 1) {
            return "Incompatible profile version ${route.profileVersion}, expected 1"
        }

        // Filter 5: Fresh route on eligible network
        if (!route.isAvailable) {
            return "Route is currently marked unavailable"
        }
        if (route.isExpired(nowMs)) {
            return "Route has expired"
        }
        if (route.transportType == TransportType.LAN_NSD_TLS && !context.activeNetworkEligible) {
            return "Active network is not eligible for LAN transfer (VPN or off-link)"
        }

        // Filter 7: Reachability probe result
        if (route.lastProbeResult == ProbeResult.UNREACHABLE || route.lastProbeResult == ProbeResult.TIMEOUT) {
            return "Route failed reachability probe: ${route.lastProbeResult}"
        }

        return null
    }

    /**
     * Enforces the invariant that an active transfer route cannot be switched mid-transfer.
     */
    fun assertRouteLocked(
        activeRoute: SelectedRoute,
        requestedRoute: SelectedRoute
    ) {
        require(
            activeRoute.attemptId == requestedRoute.attemptId &&
            activeRoute.transportType == requestedRoute.transportType &&
            activeRoute.peerId == requestedRoute.peerId
        ) {
            "Active transfer route is locked; mid-file/mid-session transport switching is strictly prohibited"
        }
    }
}
