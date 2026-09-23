package dev.glide.core.discovery

/**
 * Origin source of a peer discovery observation (LAN-008, Section 7).
 */
enum class ObservationSource {
    NEARBY,
    LAN_NSD,
    QR_INVITATION
}

/**
 * Operating platform declared by a peer device (LAN-008, Section 7).
 * Informational only; unauthenticated until handshake completion.
 */
enum class DeclaredPlatform {
    ANDROID,
    WINDOWS,
    MACOS,
    IOS,
    LINUX,
    WEB,
    UNKNOWN;

    companion object {
        fun fromString(value: String?): DeclaredPlatform {
            if (value.isNullOrBlank()) return UNKNOWN
            return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: UNKNOWN
        }
    }
}

/**
 * Pure domain model representing an untrusted observation of a potential peer device (LAN-008, Section 7).
 *
 * All observations are considered untrusted hints until cryptographically authenticated.
 */
data class PeerObservation(
    val observationId: String,
    val source: ObservationSource,
    val label: String,
    val platform: DeclaredPlatform = DeclaredPlatform.UNKNOWN,
    val endpointHandle: String,
    val networkHandle: String? = null,
    val generation: Long = 0L,
    val observedAtMs: Long,
    val advertisedCapabilities: Set<String> = emptySet()
) {
    init {
        require(observationId.isNotBlank()) { "observationId must not be blank" }
        require(label.isNotBlank()) { "label must not be blank" }
        require(label.length <= MAX_LABEL_LENGTH) {
            "label length ${label.length} exceeds maximum limit of $MAX_LABEL_LENGTH characters"
        }
        require(endpointHandle.isNotBlank()) { "endpointHandle must not be blank" }
        require(generation >= 0L) { "generation must be non-negative, got $generation" }
        require(observedAtMs >= 0L) { "observedAtMs must be non-negative, got $observedAtMs" }
    }

    companion object {
        const val MAX_LABEL_LENGTH = 32
    }
}
