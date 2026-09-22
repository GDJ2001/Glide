package dev.glide.core.routing

import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.isValidUuidV4
import dev.glide.core.ports.VerificationEvidence
import dev.glide.core.transport.TransportType

/**
 * Reachability probe status for a route candidate (LAN-008, Section 7).
 */
enum class ProbeResult {
    SUCCESS,
    UNREACHABLE,
    TIMEOUT,
    NOT_PROBED
}

/**
 * Authentication / link verification method for a peer binding (LAN-008, Section 7).
 */
enum class VerificationMethod {
    UKEY2_SAS,
    PINNED_INVITATION_TLS
}

/**
 * Concrete route candidate evaluated for transfer (LAN-008, Section 7).
 * Pure domain model; does NOT hold sockets or framework handles.
 */
data class RouteCandidate(
    val routeId: String,
    val transportType: TransportType,
    val protocolVersion: Int = 1,
    val profileVersion: Int = 1,
    val endpointHandle: String,
    val networkHandle: String? = null,
    val isAvailable: Boolean = true,
    val trustEvidence: VerificationEvidence? = null,
    val expiresAtMs: Long? = null,
    val lastProbeResult: ProbeResult = ProbeResult.NOT_PROBED
) {
    init {
        require(routeId.isNotBlank()) { "routeId must not be blank" }
        require(protocolVersion >= 1) { "protocolVersion must be >= 1" }
        require(profileVersion >= 1) { "profileVersion must be >= 1" }
        require(endpointHandle.isNotBlank()) { "endpointHandle must not be blank" }
    }

    val isPinnedInvitation: Boolean
        get() = trustEvidence is VerificationEvidence.InvitationVerified

    fun isExpired(nowMs: Long): Boolean =
        expiresAtMs != null && nowMs >= expiresAtMs
}

/**
 * Ephemeral verification proof binding a transport connection to an authenticated peer (LAN-008, Section 7).
 * Valid only for the duration of the current lease or connection.
 * Does NOT claim permanent device identity.
 */
data class VerifiedPeerBinding(
    val peerId: PeerId,
    val spkiSha256: String? = null,
    val nearbyConnectionId: String? = null,
    val verificationMethod: VerificationMethod,
    val leaseGeneration: Long = 0L,
    val proofTimestampMs: Long
) {
    init {
        require(proofTimestampMs >= 0L) { "proofTimestampMs must be non-negative" }
        require(leaseGeneration >= 0L) { "leaseGeneration must be non-negative" }
        when (verificationMethod) {
            VerificationMethod.UKEY2_SAS -> {
                require(!nearbyConnectionId.isNullOrBlank()) {
                    "UKEY2_SAS verification requires a valid nearbyConnectionId"
                }
            }
            VerificationMethod.PINNED_INVITATION_TLS -> {
                require(!spkiSha256.isNullOrBlank() && spkiSha256.length == 64) {
                    "PINNED_INVITATION_TLS requires a valid 64-hex spkiSha256"
                }
            }
        }
    }
}

/**
 * Immutable route selection decision locked to a single transfer attempt (LAN-008, Section 7, Section 10).
 * Selected exactly once; no mid-session switching.
 */
data class SelectedRoute(
    val attemptId: String,
    val transportType: TransportType,
    val peerId: PeerId,
    val endpointHandle: String,
    val networkGeneration: Long = 0L
) {
    init {
        require(isValidUuidV4(attemptId)) { "attemptId must be a canonical UUIDv4, got: '$attemptId'" }
        require(endpointHandle.isNotBlank()) { "endpointHandle must not be blank" }
        require(networkGeneration >= 0L) { "networkGeneration must be non-negative" }
    }
}

/**
 * Negotiated transfer limits for a session (LAN-008, Section 7).
 */
data class SessionLimits(
    val maxItemBytes: Long = ProtocolLimits.MAX_ITEM_BYTES,
    val maxBatchBytes: Long = ProtocolLimits.MAX_BATCH_BYTES,
    val maxItemsPerBatch: Int = ProtocolLimits.MAX_ITEMS_PER_BATCH
) {
    init {
        require(maxItemBytes > 0L) { "maxItemBytes must be positive" }
        require(maxBatchBytes >= maxItemBytes) { "maxBatchBytes must be >= maxItemBytes" }
        require(maxItemsPerBatch > 0) { "maxItemsPerBatch must be positive" }
    }
}

/**
 * Agreed session context for an active transfer session (LAN-008, Section 7).
 */
data class SessionContext(
    val sessionId: SessionId,
    val attemptId: String,
    val selectedRoute: SelectedRoute,
    val localRole: DeviceRole,
    val negotiatedLimits: SessionLimits = SessionLimits(),
    val negotiatedCapabilities: Set<String> = setOf("stream-v1", "sha256")
) {
    init {
        require(isValidUuidV4(attemptId)) { "attemptId must be a canonical UUIDv4, got: '$attemptId'" }
        require(negotiatedCapabilities.contains("stream-v1")) { "stream-v1 capability is mandatory" }
        require(negotiatedCapabilities.contains("sha256")) { "sha256 capability is mandatory" }
    }
}

/**
 * Runtime environment and permissions context supplied to [RouteSelector] (LAN-008, Section 10).
 */
data class RouteFilterContext(
    val hasGms: Boolean = true,
    val apiLevel: Int = 34,
    val hasNearbyPermission: Boolean = true,
    val hasLocalNetworkPermission: Boolean = true,
    val activeNetworkEligible: Boolean = true,
    val implementedTransports: Set<TransportType> = setOf(
        TransportType.GMS_NEARBY_P2P,
        TransportType.LAN_NSD_TLS
    )
)

/**
 * Outcome of a route evaluation attempt (LAN-008, Section 10).
 */
sealed interface RouteSelectionResult {
    data class Selected(val route: SelectedRoute) : RouteSelectionResult
    data class RequiresInvitation(val candidateUiKey: String, val reason: String) : RouteSelectionResult
    data class Rejected(val reason: String, val isTerminal: Boolean) : RouteSelectionResult
}
