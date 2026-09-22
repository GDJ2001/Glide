package dev.glide.app.permission

/**
 * High-level device capability state for initiating nearby transfer.
 */
sealed class CapabilityState {
    /** Device is fully capable and authorized to begin discovery/advertising. */
    object Ready : CapabilityState()

    /** Blocked because one or more permissions are not granted. */
    data class BlockedByPermission(
        val evaluation: PermissionEvaluation
    ) : CapabilityState()

    /** Blocked because Bluetooth hardware radio is turned off or unsupported. */
    object BlockedByBluetooth : CapabilityState()

    /** Blocked because Wi-Fi radio is turned off or unsupported. */
    object BlockedByWifi : CapabilityState()

    /** Blocked because system Location Services are disabled (required on Android 12 and below). */
    object BlockedByLocationServices : CapabilityState()

    /** Blocked because Google Play Services Nearby is unavailable or outdated. */
    data class BlockedByPlayServices(
        val errorCode: Int,
        val isResolvable: Boolean
    ) : CapabilityState()
}
