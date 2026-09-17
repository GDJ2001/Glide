package dev.glide.app.service

import dev.glide.app.permission.PermissionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun shouldAcceptSessionAction_validatesExactMatchingSessionId() {
        // Matching IDs accepted
        assertTrue(TransferServicePolicy.shouldAcceptSessionAction("session-123", "session-123"))
        assertTrue(TransferServicePolicy.shouldAcceptCancelAction("session-123", "session-123"))

        // Mismatched IDs rejected
        assertFalse(TransferServicePolicy.shouldAcceptSessionAction("session-wrong", "session-123"))
        assertFalse(TransferServicePolicy.shouldAcceptCancelAction("session-wrong", "session-123"))

        // Null / empty rejected
        assertFalse(TransferServicePolicy.shouldAcceptSessionAction(null, "session-123"))
        assertFalse(TransferServicePolicy.shouldAcceptSessionAction("session-123", null))
        assertFalse(TransferServicePolicy.shouldAcceptSessionAction("", "session-123"))
        assertFalse(TransferServicePolicy.shouldAcceptSessionAction("session-123", ""))
        assertFalse(TransferServicePolicy.shouldAcceptSessionAction(null, null))
    }

    @Test
    fun validateServiceStart_lanTransfer_api34_allowsWithoutBluetooth() {
        // LAN transfers are strictly independent of Bluetooth (LAN-025)
        val result = TransferServicePolicy.validateServiceStart(
            sdkInt = 34,
            isAppInForeground = true,
            isPermissionGranted = { false }, // Zero Bluetooth permissions granted
            isLanTransfer = true
        )

        assertEquals(ServiceStartValidation.Allowed, result)
    }

    @Test
    fun validateServiceStart_lanTransfer_api37_checksLocalNetwork() {
        val deniedResult = TransferServicePolicy.validateServiceStart(
            sdkInt = 37,
            isAppInForeground = true,
            isPermissionGranted = { false },
            isLanTransfer = true
        )
        assertTrue(deniedResult is ServiceStartValidation.MissingPrerequisites)
        val missing = deniedResult as ServiceStartValidation.MissingPrerequisites
        assertEquals(listOf(PermissionPolicy.PERMISSION_ACCESS_LOCAL_NETWORK), missing.missingPermissions)

        val allowedResult = TransferServicePolicy.validateServiceStart(
            sdkInt = 37,
            isAppInForeground = true,
            isPermissionGranted = { it == PermissionPolicy.PERMISSION_ACCESS_LOCAL_NETWORK },
            isLanTransfer = true
        )
        assertEquals(ServiceStartValidation.Allowed, allowedResult)
    }

    @Test
    fun validateServiceStart_nearbyTransfer_api34_requiresBluetooth() {
        val result = TransferServicePolicy.validateServiceStart(
            sdkInt = 34,
            isAppInForeground = true,
            isPermissionGranted = { false },
            isLanTransfer = false
        )
        assertTrue(result is ServiceStartValidation.MissingPrerequisites)
    }

    @Test
    fun isPermissionRevokedForActiveSession_distinguishesLanAndNearby() {
        // LAN on API 34 does not depend on Bluetooth revocation
        val lanRevoked34 = TransferServicePolicy.isPermissionRevokedForActiveSession(
            sdkInt = 34,
            isLanTransfer = true,
            isPermissionGranted = { false }
        )
        assertFalse("LAN on API 34 must not be affected by Bluetooth revocation", lanRevoked34)

        // LAN on API 37 is revoked when ACCESS_LOCAL_NETWORK is revoked
        val lanRevoked37 = TransferServicePolicy.isPermissionRevokedForActiveSession(
            sdkInt = 37,
            isLanTransfer = true,
            isPermissionGranted = { false }
        )
        assertTrue("LAN on API 37 must detect local network revocation", lanRevoked37)

        // Nearby on API 34 is revoked when BLUETOOTH_CONNECT is revoked
        val nearbyRevoked34 = TransferServicePolicy.isPermissionRevokedForActiveSession(
            sdkInt = 34,
            isLanTransfer = false,
            isPermissionGranted = { false }
        )
        assertTrue("Nearby on API 34 must detect Bluetooth revocation", nearbyRevoked34)
    }
}
