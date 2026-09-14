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
     * Contextual user-facing rationale for background notifications at point-of-use.
     */
    fun getNotificationRationale(): String {
        return "Glide shows a notification with transfer progress and a cancel button when you leave the app."
    }

    /**
     * Evaluates permission state given pure inspection lambdas.
     *
     * @param permissions The list of required permissions to check.
     * @param isGranted Pure function returning true if the given permission is currently granted.
     * @param shouldShowRationale Pure function returning true if rationale should be displayed for the permission.
     * @param wasPreviouslyRequested Pure function returning true if the permission was requested at least once before.
     */
    fun evaluate(
        permissions: List<String>,
        isGranted: (String) -> Boolean,
        shouldShowRationale: (String) -> Boolean,
        wasPreviouslyRequested: (String) -> Boolean = { false }
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
                rationale = getNearbyTransferRationale()
            )
        } else {
            PermissionEvaluation.Requestable(permissions = missing)
        }
    }
}
