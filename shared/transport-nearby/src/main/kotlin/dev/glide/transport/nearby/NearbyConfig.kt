package dev.glide.transport.nearby

import com.google.android.gms.nearby.connection.Strategy

/**
 * Configuration constants for Google Nearby Connections V1 transport.
 */
object NearbyConfig {
    /** Production service ID routing label for Android V1 transfers. */
    const val SERVICE_ID_RELEASE: String = "glide.local.transfer.v1"

    /** Debug service ID routing label to ensure debug and release builds never interact. */
    const val SERVICE_ID_DEBUG: String = "glide.local.transfer.v1.debug"

    /** Default timeout window for peer discovery and advertising readiness (120 seconds). */
    const val DISCOVERY_TIMEOUT_MS: Long = 120_000L

    /** Point-to-point high-throughput strategy required for Android V1 transfers. */
    val STRATEGY: Strategy = Strategy.P2P_POINT_TO_POINT

    /**
     * Resolves the authoritative service ID for the current build environment.
     */
    fun serviceId(isDebug: Boolean): String =
        if (isDebug) SERVICE_ID_DEBUG else SERVICE_ID_RELEASE
}
