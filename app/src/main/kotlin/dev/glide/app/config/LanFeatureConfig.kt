package dev.glide.app.config

/**
 * Global feature configuration and rollback control for Local Area Network (LAN) transport (LAN-032).
 *
 * Provides a programmatic toggle to cleanly disable LAN transport in emergencies
 * or forward-fix rollback builds without rolling back binary versions or affecting
 * Google Nearby Connections (P2P STREAM).
 *
 * Invariants:
 * - Default: enabled (`isLanTransportEnabled = true`).
 * - When disabled:
 *   - [dev.glide.app.routing.TransportRouter] strictly excludes [dev.glide.core.transport.TransportType.LAN_NSD_TLS].
 *   - [dev.glide.app.di.TransferModule] supplies null for [dev.glide.transport.lan.LanTransportFactory].
 *   - [dev.glide.app.discovery.UnifiedDiscoveryRepository] omits LAN discovery listeners.
 *   - Nearby Connections operations, session management, and storage remain 100% functional.
 */
object LanFeatureConfig {

    @Volatile
    var isLanTransportEnabled: Boolean = true

    /**
     * Resets the configuration back to default state (enabled).
     */
    fun reset() {
        isLanTransportEnabled = true
    }
}
