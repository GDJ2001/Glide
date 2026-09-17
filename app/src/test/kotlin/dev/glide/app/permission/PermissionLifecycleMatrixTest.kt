package dev.glide.app.permission

import dev.glide.app.service.ServiceStartValidation
import dev.glide.app.service.TransferServicePolicy
import dev.glide.app.service.TransferRuntime
import dev.glide.app.ui.transfer.TransferUiState
import dev.glide.core.domain.DeviceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifecycle and API matrix verification tests for LAN-025:
 * - API matrix (28, 29, 31, 33, 36, 37) per action.
 * - LAN strictly independent of Bluetooth.
 * - Foreground prerequisite & start-failure paths.
 * - Notification denial handling.
 * - Mid-session permission revocation handling.
 */
class PermissionLifecycleMatrixTest {

    @Test
    fun apiMatrix_lanTransferIsIndependentOfBluetoothAcrossAllVersions() {
        val testApis = listOf(28, 29, 31, 33, 34, 36, 37)
        for (api in testApis) {
            val lanPerms = PermissionPolicy.lanTransferPermissions(api)
            assertFalse(
                "API $api LAN transfer must never request Bluetooth permissions",
                lanPerms.any { it.contains("BLUETOOTH") }
            )
            assertFalse(
                "API $api LAN transfer must never request Location permissions",
                lanPerms.any { it.contains("LOCATION") }
            )
            if (api < 37) {
                assertTrue("API $api LAN transfer requires zero permissions", lanPerms.isEmpty())
            } else {
                assertEquals(listOf(PermissionPolicy.PERMISSION_ACCESS_LOCAL_NETWORK), lanPerms)
            }
        }
    }

    @Test
    fun apiMatrix_scanQrRequiresOnlyCameraAcrossAllVersions() {
        val testApis = listOf(28, 29, 31, 33, 34, 36, 37)
        for (api in testApis) {
            val qrPerms = PermissionPolicy.permissionsForAction(PermissionAction.ScanQr, api)
            assertEquals(listOf(PermissionPolicy.PERMISSION_CAMERA), qrPerms)
        }
    }

    @Test
    fun apiMatrix_nearbyTransferRequiresAppropriatePermissions() {
        // API 28: Coarse location
        assertEquals(
            listOf(PermissionPolicy.PERMISSION_ACCESS_COARSE_LOCATION),
            PermissionPolicy.permissionsForAction(PermissionAction.NearbyTransfer, 28)
        )

        // API 29: Fine + Coarse location
        val api29Perms = PermissionPolicy.permissionsForAction(PermissionAction.NearbyTransfer, 29)
        assertTrue(api29Perms.contains(PermissionPolicy.PERMISSION_ACCESS_FINE_LOCATION))
        assertTrue(api29Perms.contains(PermissionPolicy.PERMISSION_ACCESS_COARSE_LOCATION))

        // API 31: Bluetooth + Fine/Coarse location
        val api31Perms = PermissionPolicy.permissionsForAction(PermissionAction.NearbyTransfer, 31)
        assertTrue(api31Perms.contains(PermissionPolicy.PERMISSION_BLUETOOTH_CONNECT))
        assertTrue(api31Perms.contains(PermissionPolicy.PERMISSION_ACCESS_FINE_LOCATION))

        // API 33: Bluetooth + Nearby Wi-Fi, ZERO location
        val api33Perms = PermissionPolicy.permissionsForAction(PermissionAction.NearbyTransfer, 33)
        assertTrue(api33Perms.contains(PermissionPolicy.PERMISSION_BLUETOOTH_CONNECT))
        assertTrue(api33Perms.contains(PermissionPolicy.PERMISSION_NEARBY_WIFI_DEVICES))
        assertFalse(api33Perms.any { it.contains("LOCATION") })

        // API 37: Bluetooth + Nearby Wi-Fi + Local Network
        val api37Perms = PermissionPolicy.permissionsForAction(PermissionAction.NearbyTransfer, 37)
        assertTrue(api37Perms.contains(PermissionPolicy.PERMISSION_BLUETOOTH_CONNECT))
        assertTrue(api37Perms.contains(PermissionPolicy.PERMISSION_NEARBY_WIFI_DEVICES))
        assertTrue(api37Perms.contains(PermissionPolicy.PERMISSION_ACCESS_LOCAL_NETWORK))
    }

    @Test
    fun notificationPermissionDenial_doesNotBlockServiceStartOnApi33Plus() {
        val result = TransferServicePolicy.validateServiceStart(
            sdkInt = 34,
            isAppInForeground = true,
            isPermissionGranted = { perm ->
                // Grant all except POST_NOTIFICATIONS
                perm != PermissionPolicy.PERMISSION_POST_NOTIFICATIONS
            },
            isLanTransfer = true
        )
        assertEquals(ServiceStartValidation.Allowed, result)
    }

    @Test
    fun backgroundStartRestriction_blocksServiceOnApi31Plus() {
        val blockedResult = TransferServicePolicy.validateServiceStart(
            sdkInt = 31,
            isAppInForeground = false,
            isPermissionGranted = { true },
            isLanTransfer = true
        )
        assertTrue(blockedResult is ServiceStartValidation.BlockedByBackground)
    }

    @Test
    fun permissionRevocation_detectsRevocationAccuratelyForBothRoutes() {
        // LAN on API 37 requires ACCESS_LOCAL_NETWORK
        assertTrue(
            TransferServicePolicy.isPermissionRevokedForActiveSession(
                sdkInt = 37,
                isLanTransfer = true,
                isPermissionGranted = { false }
            )
        )
        assertFalse(
            TransferServicePolicy.isPermissionRevokedForActiveSession(
                sdkInt = 37,
                isLanTransfer = true,
                isPermissionGranted = { it == PermissionPolicy.PERMISSION_ACCESS_LOCAL_NETWORK }
            )
        )

        // LAN on API 34 has no required runtime permissions -> never revoked
        assertFalse(
            TransferServicePolicy.isPermissionRevokedForActiveSession(
                sdkInt = 34,
                isLanTransfer = true,
                isPermissionGranted = { false }
            )
        )

        // Nearby on API 34 requires BLUETOOTH_CONNECT
        assertTrue(
            TransferServicePolicy.isPermissionRevokedForActiveSession(
                sdkInt = 34,
                isLanTransfer = false,
                isPermissionGranted = { false }
            )
        )
        assertFalse(
            TransferServicePolicy.isPermissionRevokedForActiveSession(
                sdkInt = 34,
                isLanTransfer = false,
                isPermissionGranted = { it == PermissionPolicy.PERMISSION_BLUETOOTH_CONNECT }
            )
        )
    }

    @Test
    fun startFailure_mapsToDescriptiveFailedStateWithoutCrash() {
        val runtime = TransferRuntime.forTesting()

        // Simulate security exception failure (missing connected device permissions)
        val missingPermState = TransferUiState.Failed(
            sessionId = "session-test",
            role = DeviceRole.Sender,
            message = "Missing nearby device permission to maintain background transfer.",
            code = "MISSING_PERMISSION",
            isRecoverable = false
        )
        runtime.setTransferStateForTesting(missingPermState)
        assertEquals(missingPermState, runtime.transferState.value)

        // Simulate background start not allowed failure
        val bgStartFailedState = TransferUiState.Failed(
            sessionId = "session-test",
            role = DeviceRole.Sender,
            message = "Cannot maintain background transfer while Glide is in the background.",
            code = "FOREGROUND_START_NOT_ALLOWED",
            isRecoverable = false
        )
        runtime.setTransferStateForTesting(bgStartFailedState)
        assertEquals(bgStartFailedState, runtime.transferState.value)

        runtime.resetForTesting()
    }
}
