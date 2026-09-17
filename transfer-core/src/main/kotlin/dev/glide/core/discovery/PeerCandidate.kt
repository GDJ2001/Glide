package dev.glide.core.discovery

import dev.glide.core.routing.RouteCandidate
import dev.glide.core.transport.TransportType

/**
 * Verification status of a discovered candidate in the UI (LAN-008, Section 7).
 */
enum class CandidateVerificationStatus {
    UNVERIFIED,
    PINNED_INVITATION,
    AUTHENTICATED
}

/**
 * UI representation of a peer device available for transfer (LAN-008, Section 7).
 *
 * Invariant: Never keyed by name or IP alone. Name collisions produce distinct candidates.
 */
data class PeerCandidate(
    val uiKey: String,
    val displayName: String,
    val platform: DeclaredPlatform = DeclaredPlatform.UNKNOWN,
    val observations: List<PeerObservation> = emptyList(),
    val routes: List<RouteCandidate> = emptyList(),
    val verificationStatus: CandidateVerificationStatus = CandidateVerificationStatus.UNVERIFIED,
    val isAvailable: Boolean = true
) {
    init {
        require(uiKey.isNotBlank()) { "uiKey must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(displayName.length <= PeerObservation.MAX_LABEL_LENGTH) {
            "displayName length ${displayName.length} exceeds maximum ${PeerObservation.MAX_LABEL_LENGTH}"
        }
    }

    /**
     * Informational route subtitle for UI display (e.g., "Nearby", "Local network", "Scan to connect").
     */
    val routeSubtitle: String
        get() = when {
            verificationStatus == CandidateVerificationStatus.PINNED_INVITATION -> "Scanned invite"
            routes.any { it.transportType == TransportType.GMS_NEARBY_P2P } -> "Nearby"
            routes.any { it.transportType == TransportType.LAN_NSD_TLS } -> "Local network"
            else -> "Available"
        }
}

/**
 * Pure deduplication engine enforcing Section 7 identity and discovery rules:
 * - Merges repeated NSD updates for the same service instance + network.
 * - Same-name peers remain distinct candidates (no merging by label alone).
 * - Nearby and LAN observations remain separate candidates at this baseline (no unverified auto-merge).
 * - Losing an observation preserves the candidate as long as another valid observation/route exists.
 */
object CandidateDeduplicator {

    /**
     * Generates a stable UI key for an observation based on source and endpoint.
     * Guaranteed never to merge by display name or unverified IP alone.
     */
    fun createUiKey(observation: PeerObservation): String {
        return when (observation.source) {
            ObservationSource.NEARBY -> "nearby:${observation.endpointHandle}"
            ObservationSource.LAN_NSD -> {
                val net = observation.networkHandle ?: "local"
                "lan-nsd:$net:${observation.endpointHandle}"
            }
            ObservationSource.QR_INVITATION -> "qr:${observation.observationId}"
        }
    }

    /**
     * Ingests a new observation into an existing candidate map.
     * Merges updates for the same UI key, or creates a new candidate.
     */
    fun ingestObservation(
        currentCandidates: Map<String, PeerCandidate>,
        observation: PeerObservation,
        associatedRoute: RouteCandidate? = null
    ): Map<String, PeerCandidate> {
        val uiKey = createUiKey(observation)
        val existing = currentCandidates[uiKey]

        val updatedObservations = if (existing != null) {
            // Replace old observation with same observationId, or append
            val filtered = existing.observations.filterNot { it.observationId == observation.observationId }
            filtered + observation
        } else {
            listOf(observation)
        }

        val updatedRoutes = if (existing != null) {
            if (associatedRoute != null) {
                val filtered = existing.routes.filterNot { it.routeId == associatedRoute.routeId }
                filtered + associatedRoute
            } else {
                existing.routes
            }
        } else {
            if (associatedRoute != null) listOf(associatedRoute) else emptyList()
        }

        val verificationStatus = when {
            observation.source == ObservationSource.QR_INVITATION -> CandidateVerificationStatus.PINNED_INVITATION
            existing?.verificationStatus == CandidateVerificationStatus.AUTHENTICATED -> CandidateVerificationStatus.AUTHENTICATED
            else -> CandidateVerificationStatus.UNVERIFIED
        }

        val updatedCandidate = PeerCandidate(
            uiKey = uiKey,
            displayName = observation.label.take(PeerObservation.MAX_LABEL_LENGTH),
            platform = observation.platform,
            observations = updatedObservations,
            routes = updatedRoutes,
            verificationStatus = verificationStatus,
            isAvailable = true
        )

        return currentCandidates + (uiKey to updatedCandidate)
    }

    /**
     * Removes an observation by its ID.
     * If all observations for a candidate are removed, the candidate is marked unavailable or removed.
     */
    fun removeObservation(
        currentCandidates: Map<String, PeerCandidate>,
        observationId: String
    ): Map<String, PeerCandidate> {
        val resultMap = mutableMapOf<String, PeerCandidate>()

        for ((uiKey, candidate) in currentCandidates) {
            val remainingObservations = candidate.observations.filterNot { it.observationId == observationId }
            if (remainingObservations.isNotEmpty()) {
                resultMap[uiKey] = candidate.copy(observations = remainingObservations)
            }
            // If no observations remain, the candidate is omitted
        }

        return resultMap
    }

    /**
     * Merges a QR invitation candidate with a discovered LAN route ONLY when the endpoint
     * and SPKI pin are cryptographically matched (LAN-008, Section 7).
     */
    fun mergeProvenLanCandidate(
        qrCandidate: PeerCandidate,
        lanCandidate: PeerCandidate,
        spkiSha256Match: Boolean
    ): PeerCandidate? {
        if (!spkiSha256Match) return null
        if (qrCandidate.verificationStatus != CandidateVerificationStatus.PINNED_INVITATION) return null

        val mergedObservations = qrCandidate.observations + lanCandidate.observations
        val mergedRoutes = qrCandidate.routes + lanCandidate.routes

        return PeerCandidate(
            uiKey = qrCandidate.uiKey, // Retain the pinned QR key
            displayName = qrCandidate.displayName,
            platform = if (qrCandidate.platform != DeclaredPlatform.UNKNOWN) qrCandidate.platform else lanCandidate.platform,
            observations = mergedObservations,
            routes = mergedRoutes,
            verificationStatus = CandidateVerificationStatus.PINNED_INVITATION,
            isAvailable = true
        )
    }
}
