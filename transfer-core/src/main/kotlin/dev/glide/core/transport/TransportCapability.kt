package dev.glide.core.transport

/**
 * Identifies the physical and logical transport channels evaluated for Glide.
 */
enum class TransportType(val displayName: String) {
    GMS_NEARBY_P2P("GMS Nearby Connections P2P"),
    LAN_NSD_TLS("Local Area Network (NSD + TLS 1.3)"),
    WIFI_DIRECT_P2P("Autonomous Wi-Fi Direct P2P"),
    LOCAL_HOTSPOT("Local-Only SoftAP Hotspot"),
    WIFI_AWARE_NAN("Wi-Fi Aware (NAN)");
}

/**
 * Transport hierarchy tier defined in ADR-0016.
 */
enum class TransportTier(val priority: Int) {
    TIER_1_DEFAULT_P2P(1),
    TIER_2_CROSS_PLATFORM_LAN(2),
    TIER_3_GMS_FREE_OFFLINE_P2P(3),
    OPPORTUNISTIC_PROBE(4),
    EMERGENCY_FALLBACK(5);
}

/**
 * Architectural and operational profile for a transport candidate.
 */
data class TransportProfile(
    val type: TransportType,
    val tier: TransportTier,
    val minApiLevel: Int,
    val requiresGms: Boolean,
    val requiresAccessPoint: Boolean,
    val requiresTls13: Boolean,
    val maxThroughputMbps: Int,
    val hardwareAvailabilityRatio: Double,
    val supportsCrossPlatform: Boolean,
    val canPreserveStationWifi: Boolean
) {
    init {
        require(minApiLevel >= 14) { "minApiLevel must be >= 14" }
        require(maxThroughputMbps > 0) { "maxThroughputMbps must be positive" }
        require(hardwareAvailabilityRatio in 0.0..1.0) {
            "hardwareAvailabilityRatio must be between 0.0 and 1.0"
        }
        // Invariant: all non-GMS socket-based transports mandate TLS 1.3 application security
        if (type != TransportType.GMS_NEARBY_P2P) {
            require(requiresTls13) {
                "Non-GMS transport $type must mandate TLS 1.3 application security"
            }
        }
    }
}

/**
 * Standard registry of evaluated transport profiles per ADR-0016.
 */
object TransportProfiles {
    val GMS_NEARBY = TransportProfile(
        type = TransportType.GMS_NEARBY_P2P,
        tier = TransportTier.TIER_1_DEFAULT_P2P,
        minApiLevel = 26,
        requiresGms = true,
        requiresAccessPoint = false,
        requiresTls13 = false, // Handled internally by GMS Nearby UKEY2/DTLS
        maxThroughputMbps = 380,
        hardwareAvailabilityRatio = 0.85,
        supportsCrossPlatform = false,
        canPreserveStationWifi = true
    )

    val LAN_NSD_TLS = TransportProfile(
        type = TransportType.LAN_NSD_TLS,
        tier = TransportTier.TIER_2_CROSS_PLATFORM_LAN,
        minApiLevel = 16,
        requiresGms = false,
        requiresAccessPoint = true,
        requiresTls13 = true,
        maxThroughputMbps = 600,
        hardwareAvailabilityRatio = 1.00,
        supportsCrossPlatform = true,
        canPreserveStationWifi = true
    )

    val WIFI_DIRECT_P2P = TransportProfile(
        type = TransportType.WIFI_DIRECT_P2P,
        tier = TransportTier.TIER_3_GMS_FREE_OFFLINE_P2P,
        minApiLevel = 14,
        requiresGms = false,
        requiresAccessPoint = false,
        requiresTls13 = true,
        maxThroughputMbps = 450,
        hardwareAvailabilityRatio = 0.95,
        supportsCrossPlatform = false,
        canPreserveStationWifi = false // Dependent on single vs dual PHY
    )

    val WIFI_AWARE_NAN = TransportProfile(
        type = TransportType.WIFI_AWARE_NAN,
        tier = TransportTier.OPPORTUNISTIC_PROBE,
        minApiLevel = 26,
        requiresGms = false,
        requiresAccessPoint = false,
        requiresTls13 = true,
        maxThroughputMbps = 280,
        hardwareAvailabilityRatio = 0.38, // Severe fragmentation (<40%)
        supportsCrossPlatform = false,
        canPreserveStationWifi = false
    )

    val LOCAL_HOTSPOT = TransportProfile(
        type = TransportType.LOCAL_HOTSPOT,
        tier = TransportTier.EMERGENCY_FALLBACK,
        minApiLevel = 26,
        requiresGms = false,
        requiresAccessPoint = false,
        requiresTls13 = true,
        maxThroughputMbps = 350,
        hardwareAvailabilityRatio = 0.90,
        supportsCrossPlatform = true,
        canPreserveStationWifi = false
    )

    val ALL = listOf(
        GMS_NEARBY,
        LAN_NSD_TLS,
        WIFI_DIRECT_P2P,
        WIFI_AWARE_NAN,
        LOCAL_HOTSPOT
    )
}

/**
 * Snapshot of the device's hardware, radio, and network operating environment.
 */
data class DeviceEnvironment(
    val apiLevel: Int,
    val hasGms: Boolean,
    val hasWifiAwareHardware: Boolean,
    val hasWifiDirectHardware: Boolean,
    val isConnectedToLocalNetwork: Boolean,
    val peerSupportsCrossPlatform: Boolean
)

/**
 * Evaluates transport candidates and selects the optimal transport profile
 * based on device capabilities and environment.
 */
object TransportSelectionEngine {

    /**
     * Determines whether a specific transport candidate is feasible in the given environment.
     */
    fun isFeasible(profile: TransportProfile, env: DeviceEnvironment): Boolean {
        if (env.apiLevel < profile.minApiLevel) return false
        if (profile.requiresGms && !env.hasGms) return false
        if (profile.requiresAccessPoint && !env.isConnectedToLocalNetwork) return false
        if (profile.type == TransportType.WIFI_AWARE_NAN && !env.hasWifiAwareHardware) return false
        if (profile.type == TransportType.WIFI_DIRECT_P2P && !env.hasWifiDirectHardware) return false
        if (env.peerSupportsCrossPlatform && !profile.supportsCrossPlatform) return false
        return true
    }

    /**
     * Returns all feasible transport profiles sorted by priority tier.
     */
    fun getEligibleTransports(env: DeviceEnvironment): List<TransportProfile> {
        return TransportProfiles.ALL
            .filter { isFeasible(it, env) }
            .sortedBy { it.tier.priority }
    }

    /**
     * Selects the highest priority feasible transport profile, or null if none is available.
     */
    fun selectBestTransport(env: DeviceEnvironment): TransportProfile? {
        return getEligibleTransports(env).firstOrNull()
    }
}
