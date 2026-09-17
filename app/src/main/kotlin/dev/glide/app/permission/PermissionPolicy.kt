package dev.glide.app.permission

import android.os.Build

/**
 * Result of evaluating permission status for an action.
 */
sealed class PermissionEvaluation {
    /** All required permissions have been granted by the user. */
    object Granted : PermissionEvaluation()

    /** One or more permissions need to be requested with an educational rationale. */
    data class NeedsRationale(
        val permissions: List<String>,
        val rationale: String
    ) : PermissionEvaluation()

    /** Permissions can be requested directly via system dialog. */
    data class Requestable(
        val permissions: List<String>
    ) : PermissionEvaluation()

    /** One or more required permissions were permanently denied (requires Settings). */
    data class PermanentlyDenied(
        val deniedPermissions: List<String>,
        val settingsGuidance: String
    ) : PermissionEvaluation()
}

sealed interface PermissionAction {
    /** Nearby Connections discovery, advertising, and transfer. */
    data object NearbyTransfer : PermissionAction

    /** Local Area Network (mDNS/NSD + TLS) discovery and transfer. */
    data object LanTransfer : PermissionAction

    /** Scanning QR pairing invitations via camera. */
    data object ScanQr : PermissionAction

    /** Foreground transfer progress and cancellation notifications. */
    data object Notification : PermissionAction
}

/**
 * Pure policy engine mapping Android API levels (Build.VERSION.SDK_INT) to required
 * permissions, point-of-use rationales, and grant evaluation.
 *
 * Implements the authoritative rules from docs/android/PERMISSION_MATRIX.md:
 * - API 26..28: ACCESS_COARSE_LOCATION
 * - API 29..30: ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION
 * - API 31..32: BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT + ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION
 * - API 33..36: BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT + NEARBY_WIFI_DEVICES
 * - API 37+: Above + ACCESS_LOCAL_NETWORK
 * - Notifications on API 33+: POST_NOTIFICATIONS (for background progress)
 * - Camera: CAMERA strictly at point-of-use for QR scanner
 * - LAN: Direct sockets/NSD without Bluetooth runtime dependencies
 */
object PermissionPolicy {

    // Permission constants matching android.Manifest.permission
    const val PERMISSION_BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
    const val PERMISSION_BLUETOOTH_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"
    const val PERMISSION_BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val PERMISSION_NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES"
    const val PERMISSION_ACCESS_COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
    const val PERMISSION_ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    const val PERMISSION_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val PERMISSION_CAMERA = "android.permission.CAMERA"

    /**
     * Returns the exact set of runtime permissions required for nearby device discovery
     * and peer transfer at point-of-use for the given SDK level.
     *
     * Never called during app launch; strictly evaluated when the user initiates Send or Receive.
     */
    fun nearbyTransferPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> {
        return when {
            sdkInt >= 37 -> listOf(
                PERMISSION_BLUETOOTH_SCAN,
                PERMISSION_BLUETOOTH_ADVERTISE,
                PERMISSION_BLUETOOTH_CONNECT,
                PERMISSION_NEARBY_WIFI_DEVICES,
                PERMISSION_ACCESS_LOCAL_NETWORK
            )
            sdkInt >= 33 -> listOf(
                PERMISSION_BLUETOOTH_SCAN,
                PERMISSION_BLUETOOTH_ADVERTISE,
                PERMISSION_BLUETOOTH_CONNECT,
                PERMISSION_NEARBY_WIFI_DEVICES
            )
            sdkInt >= 31 -> listOf(
                PERMISSION_BLUETOOTH_SCAN,
                PERMISSION_BLUETOOTH_ADVERTISE,
                PERMISSION_BLUETOOTH_CONNECT,
                PERMISSION_ACCESS_FINE_LOCATION,
                PERMISSION_ACCESS_COARSE_LOCATION
            )
            sdkInt >= 29 -> listOf(
                PERMISSION_ACCESS_FINE_LOCATION,
                PERMISSION_ACCESS_COARSE_LOCATION
            )
            else -> listOf(
                PERMISSION_ACCESS_COARSE_LOCATION
            )
        }
    }

    /**
     * Returns the runtime notification permissions required for background transfer progress.
     * Only applies on Android 13+ (API 33+). Denial changes visibility, not authorization.
     */
    fun notificationPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> {
        return if (sdkInt >= 33) {
            listOf(PERMISSION_POST_NOTIFICATIONS)
        } else {
            emptyList()
        }
    }

    /**
     * Returns runtime permissions required for Local Area Network (LAN) discovery and transfer.
     * On API 26..36, standard local sockets require zero runtime permissions.
     * On API 37+, platform local-network privacy requires ACCESS_LOCAL_NETWORK.
     * Crucially: LAN transfers do NOT require Bluetooth or Location permissions.
     */
    fun lanTransferPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> {
        return if (sdkInt >= 37) {
            listOf(PERMISSION_ACCESS_LOCAL_NETWORK)
        } else {
            emptyList()
        }
    }

    /**
     * Returns runtime permissions required for scanning QR pairing invitations.
     * Strictly evaluated only when opening the QR scanner.
     */
    fun scanQrPermissions(): List<String> = listOf(PERMISSION_CAMERA)

    /**
     * Contextual user-facing rationale for nearby transfer permissions at point-of-use.
     */
    fun getNearbyTransferRationale(sdkInt: Int = Build.VERSION.SDK_INT): String {
        return when {
            sdkInt >= 33 -> "Glide needs nearby device permission to find and connect to devices around you over high-speed Wi-Fi and Bluetooth. Glide does not track your location."
            sdkInt >= 31 -> "Glide needs nearby device and location permissions to discover and connect to devices around you. Android requires location access for Bluetooth discovery on this version, but Glide does not track your location."
            else -> "Android needs location permission to find nearby devices. Glide does not track your location."
        }
    }

    /**
     * Contextual user-facing rationale for Local Area Network (LAN) permissions at point-of-use.
     */
    fun getLanTransferRationale(sdkInt: Int = Build.VERSION.SDK_INT): String {
        return if (sdkInt >= 37) {
            "Glide needs local network permission to discover and connect with devices on your Wi-Fi network. Glide does not access the internet or track your location."
        } else {
            "Glide uses your local Wi-Fi network to connect to nearby devices."
        }
    }

    /**
     * Contextual user-facing rationale for QR scanner camera permission at point-of-use.
     */
    fun getScanQrRationale(): String {
        return "Glide needs camera access to scan pairing QR codes. You can also paste an invitation code instead."
    }

    /**
     * Contextual user-facing rationale for background notifications at point-of-use.
     */
    fun getNotificationRationale(): String {
        return "Glide shows a notification with transfer progress and a cancel button when you leave the app."
    }

    /**
     * Returns the required permissions for the given [PermissionAction].
     */
    fun permissionsForAction(action: PermissionAction, sdkInt: Int = Build.VERSION.SDK_INT): List<String> {
        return when (action) {
            PermissionAction.NearbyTransfer -> nearbyTransferPermissions(sdkInt)
            PermissionAction.LanTransfer -> lanTransferPermissions(sdkInt)
            PermissionAction.ScanQr -> scanQrPermissions()
            PermissionAction.Notification -> notificationPermissions(sdkInt)
        }
    }

    /**
     * Returns the contextual user-facing rationale for the given [PermissionAction].
     */
    fun rationaleForAction(action: PermissionAction, sdkInt: Int = Build.VERSION.SDK_INT): String {
        return when (action) {
            PermissionAction.NearbyTransfer -> getNearbyTransferRationale(sdkInt)
            PermissionAction.LanTransfer -> getLanTransferRationale(sdkInt)
            PermissionAction.ScanQr -> getScanQrRationale()
            PermissionAction.Notification -> getNotificationRationale()
        }
    }

    /**
     * Evaluates permission status for a specific [PermissionAction].
     */
    fun evaluateAction(
        action: PermissionAction,
        isGranted: (String) -> Boolean,
        shouldShowRationale: (String) -> Boolean,
        wasPreviouslyRequested: (String) -> Boolean = { false },
        sdkInt: Int = Build.VERSION.SDK_INT
    ): PermissionEvaluation {
        val perms = permissionsForAction(action, sdkInt)
        return evaluate(
            permissions = perms,
            isGranted = isGranted,
            shouldShowRationale = shouldShowRationale,
            wasPreviouslyRequested = wasPreviouslyRequested,
            defaultRationale = rationaleForAction(action, sdkInt)
        )
    }

    /**
     * Evaluates permission state given pure inspection lambdas.
     *
     * @param permissions The list of required permissions to check.
     * @param isGranted Pure function returning true if the given permission is currently granted.
     * @param shouldShowRationale Pure function returning true if rationale should be displayed for the permission.
     * @param wasPreviouslyRequested Pure function returning true if the permission was requested at least once before.
     * @param defaultRationale Optional custom rationale when rationale is needed.
     */
    fun evaluate(
        permissions: List<String>,
        isGranted: (String) -> Boolean,
        shouldShowRationale: (String) -> Boolean,
        wasPreviouslyRequested: (String) -> Boolean = { false },
        defaultRationale: String = getNearbyTransferRationale()
    ): PermissionEvaluation {
        val missing = permissions.filterNot { isGranted(it) }
        if (missing.isEmpty()) {
            return PermissionEvaluation.Granted
        }

        // Check if any missing permission is permanently denied:
        // A permission is permanently denied if it was previously requested, is NOT granted,
        // and shouldShowRequestPermissionRationale returns FALSE.
        val permanentlyDenied = missing.filter { perm ->
            wasPreviouslyRequested(perm) && !shouldShowRationale(perm)
        }
        if (permanentlyDenied.isNotEmpty()) {
            return PermissionEvaluation.PermanentlyDenied(
                deniedPermissions = permanentlyDenied,
                settingsGuidance = "Required permissions were denied. Please enable them in app settings to transfer files."
            )
        }

        // If any missing permission warrants a rationale:
        val needsRationale = missing.any { shouldShowRationale(it) }
        return if (needsRationale) {
            PermissionEvaluation.NeedsRationale(
                permissions = missing,
                rationale = defaultRationale
            )
        } else {
            PermissionEvaluation.Requestable(permissions = missing)
        }
    }
}
