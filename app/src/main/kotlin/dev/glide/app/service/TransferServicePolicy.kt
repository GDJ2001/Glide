package dev.glide.app.service

import dev.glide.app.permission.PermissionPolicy

/**
 * Result of validating whether the application can start a connected-device foreground service.
 */
sealed interface ServiceStartValidation {
    /**
     * Service start is permitted by platform rules and all prerequisite permissions are granted.
     */
    data object Allowed : ServiceStartValidation

    /**
     * Service start is prohibited because the application is running in the background (API 31+ restriction).
     */
    data class BlockedByBackground(val reason: String) : ServiceStartValidation

    /**
     * Service start is prohibited because one or more required connected-device permissions are missing (API 34+ requirement).
     */
    data class MissingPrerequisites(
        val missingPermissions: List<String>,
        val reason: String
    ) : ServiceStartValidation
}

/**
 * Pure policy engine governing connected-device foreground service start restrictions,
 * prerequisite permissions, and lifecycle rules across Android API levels (GLIDE-028).
 *
 * Implements authoritative constraints from docs/android/STORAGE_AND_BACKGROUND.md and PERMISSION_MATRIX.md:
 * - API 31+ (Android 12+): FGS cannot be started from the background (ForegroundServiceStartNotAllowedException).
 * - API 34+ (Android 14+): FGS type `connectedDevice` requires FOREGROUND_SERVICE_CONNECTED_DEVICE in manifest
 *   and runtime prerequisite permissions (BLUETOOTH_CONNECT on 31+, NEARBY_WIFI_DEVICES on 33+).
 * - API 37 (Android 17): compileSdk 37 / targetSdk 37 compliance.
 * - API 33+ Notifications: POST_NOTIFICATIONS denial suppresses notification visibility in shade, but does
 *   NOT prohibit FGS start (the service must still post the notification).
 */
object TransferServicePolicy {

    /**
     * Returns the runtime permissions required specifically to authorize starting a
     * `connectedDevice` foreground service on the given SDK level.
     */
    fun requiredConnectedDevicePermissions(sdkInt: Int): List<String> {
        return when {
            sdkInt >= 33 -> listOf(
                PermissionPolicy.PERMISSION_BLUETOOTH_CONNECT,
                PermissionPolicy.PERMISSION_NEARBY_WIFI_DEVICES
            )
            sdkInt >= 31 -> listOf(
                PermissionPolicy.PERMISSION_BLUETOOTH_CONNECT
            )
            else -> emptyList()
        }
    }

    /**
     * Validates whether a connected-device foreground service start request is permitted.
     *
     * @param sdkInt The platform API level (e.g. Build.VERSION.SDK_INT).
     * @param isAppInForeground True if the calling app currently has a visible/resumed Activity or foreground presence.
     * @param isPermissionGranted Predicate evaluating whether a runtime permission string is currently granted.
     */
    fun validateServiceStart(
        sdkInt: Int,
        isAppInForeground: Boolean,
        isPermissionGranted: (String) -> Boolean
    ): ServiceStartValidation {
        // 1. Check API 31+ background start restriction
        if (sdkInt >= 31 && !isAppInForeground) {
            return ServiceStartValidation.BlockedByBackground(
                "Cannot start transfer service while Glide is in the background. Keep Glide open to transfer."
            )
        }

        // 2. Check API 34+ connectedDevice runtime prerequisites
        if (sdkInt >= 34) {
            val required = requiredConnectedDevicePermissions(sdkInt)
            val missing = required.filterNot { isPermissionGranted(it) }
            // For connectedDevice FGS, at least BLUETOOTH_CONNECT or NEARBY_WIFI_DEVICES must be granted
            if (missing.contains(PermissionPolicy.PERMISSION_BLUETOOTH_CONNECT)) {
                return ServiceStartValidation.MissingPrerequisites(
                    missingPermissions = missing,
                    reason = "Required nearby device permissions are missing to maintain background transfer."
                )
            }
        }

        return ServiceStartValidation.Allowed
    }

    /**
     * Evaluates whether an incoming session-bound action request (accept, reject, cancel)
     * should be accepted or rejected as stale.
     *
     * @param targetSessionId The session ID passed in the intent extra or action caller.
     * @param activeSessionId The currently active session ID in TransferSessionManager.
     * @return True if targetSessionId is non-null and exactly matches activeSessionId.
     */
    fun shouldAcceptSessionAction(
        targetSessionId: String?,
        activeSessionId: String?
    ): Boolean {
        if (targetSessionId.isNullOrEmpty() || activeSessionId.isNullOrEmpty()) {
            return false
        }
        return targetSessionId == activeSessionId
    }

    /**
     * Evaluates whether an incoming cancel request should be accepted or rejected as stale.
     *
     * @param targetSessionId The session ID passed in the cancel intent extra.
     * @param activeSessionId The currently active session ID in TransferSessionManager.
     * @return True if targetSessionId is non-null and exactly matches activeSessionId.
     */
    fun shouldAcceptCancelAction(
        targetSessionId: String?,
        activeSessionId: String?
    ): Boolean = shouldAcceptSessionAction(targetSessionId, activeSessionId)
}
