package dev.glide.app.service

import dev.glide.app.permission.PermissionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying connected-device foreground service start restrictions,
 * prerequisite permissions, and notification handling across API levels 31, 34, and 37 (GLIDE-028).
 */
class TransferServicePolicyTest {

    @Test
    fun api31_whenAppInBackground_blocksServiceStart() {
        val result = TransferServicePolicy.validateServiceStart(
            sdkInt = 31,
            isAppInForeground = false,
            isPermissionGranted = { true }
        )

        assertTrue(result is ServiceStartValidation.BlockedByBackground)
        val blocked = result as ServiceStartValidation.BlockedByBackground
        assertTrue(blocked.reason.contains("background", ignoreCase = true))
    }

    @Test
    fun api31_whenAppInForeground_allowsServiceStart() {
        val result = TransferServicePolicy.validateServiceStart(
            sdkInt = 31,
            isAppInForeground = true,
            isPermissionGranted = { true }
        )

        assertEquals(ServiceStartValidation.Allowed, result)
    }

    @Test
    fun api30AndBelow_ignoresBackgroundStartRestrictions() {
        val result = TransferServicePolicy.validateServiceStart(
            sdkInt = 30,
            isAppInForeground = false,
            isPermissionGranted = { false }
        )

        assertEquals(ServiceStartValidation.Allowed, result)
    }

    @Test
    fun api34_whenBluetoothConnectMissing_blocksServiceStartWithMissingPrerequisites() {
        val result = TransferServicePolicy.validateServiceStart(
            sdkInt = 34,
            isAppInForeground = true,
            isPermissionGranted = { permission ->
                // Deny BLUETOOTH_CONNECT
                permission != PermissionPolicy.PERMISSION_BLUETOOTH_CONNECT
            }
        )

        assertTrue(result is ServiceStartValidation.MissingPrerequisites)
        val missing = result as ServiceStartValidation.MissingPrerequisites
        assertTrue(missing.missingPermissions.contains(PermissionPolicy.PERMISSION_BLUETOOTH_CONNECT))
    }

    @Test
    fun api34_whenBluetoothConnectGranted_allowsServiceStart() {
        val result = TransferServicePolicy.validateServiceStart(
            sdkInt = 34,
            isAppInForeground = true,
            isPermissionGranted = { permission ->
                permission == PermissionPolicy.PERMISSION_BLUETOOTH_CONNECT
            }
        )

        assertEquals(ServiceStartValidation.Allowed, result)
    }

    @Test
    fun api37_whenPrerequisitesSatisfied_allowsServiceStart() {
        val result = TransferServicePolicy.validateServiceStart(
            sdkInt = 37,
            isAppInForeground = true,
            isPermissionGranted = { true }
        )

        assertEquals(ServiceStartValidation.Allowed, result)
    }

    @Test
    fun notificationPermissionDenied_doesNotProhibitFgsStart() {
        // Even if POST_NOTIFICATIONS is denied on API 33+, validateServiceStart only checks
        // connectedDevice prerequisites because POST_NOTIFICATIONS changes shade visibility,
        // not FGS authorization (the service still posts the notification).
        val result = TransferServicePolicy.validateServiceStart(
            sdkInt = 34,
            isAppInForeground = true,
            isPermissionGranted = { perm ->
                perm != PermissionPolicy.PERMISSION_POST_NOTIFICATIONS
            }
        )

        assertEquals(ServiceStartValidation.Allowed, result)
    }

    @Test
    fun requiredConnectedDevicePermissions_returnsExpectedSets() {
        val perms31 = TransferServicePolicy.requiredConnectedDevicePermissions(31)
        assertEquals(listOf(PermissionPolicy.PERMISSION_BLUETOOTH_CONNECT), perms31)

        val perms34 = TransferServicePolicy.requiredConnectedDevicePermissions(34)
        assertTrue(perms34.contains(PermissionPolicy.PERMISSION_BLUETOOTH_CONNECT))
        assertTrue(perms34.contains(PermissionPolicy.PERMISSION_NEARBY_WIFI_DEVICES))

        val perms28 = TransferServicePolicy.requiredConnectedDevicePermissions(28)
        assertTrue(perms28.isEmpty())
    }
}
