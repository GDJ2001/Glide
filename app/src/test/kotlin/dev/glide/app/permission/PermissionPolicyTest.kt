package dev.glide.app.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {

    @Test
    fun api26_to_28_requests_only_coarse_location() {
        for (sdk in 26..28) {
            val perms = PermissionPolicy.nearbyTransferPermissions(sdk)
            assertEquals("SDK $sdk must request only coarse location", listOf("android.permission.ACCESS_COARSE_LOCATION"), perms)
            assertFalse("SDK $sdk must not request bluetooth runtime", perms.any { it.contains("BLUETOOTH") })
            assertFalse("SDK $sdk must not request fine location", perms.contains("android.permission.ACCESS_FINE_LOCATION"))
        }
    }

    @Test
    fun api29_to_30_requests_fine_and_coarse_location() {
        for (sdk in 29..30) {
            val perms = PermissionPolicy.nearbyTransferPermissions(sdk)
            assertEquals(
                "SDK $sdk must request fine and coarse location",
                listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
                perms
            )
            assertFalse("SDK $sdk must not request bluetooth runtime", perms.any { it.contains("BLUETOOTH") })
        }
    }

    @Test
    fun api31_to_32_reconciles_nearby_and_platform_guide_with_bt_and_location() {
        for (sdk in 31..32) {
            val perms = PermissionPolicy.nearbyTransferPermissions(sdk)
            val expected = listOf(
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_ADVERTISE",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION"
            )
            assertEquals("SDK $sdk must request BT runtime and location compatibility", expected, perms)
            assertFalse("SDK $sdk must not request nearby wifi devices before API 33", perms.contains("android.permission.NEARBY_WIFI_DEVICES"))
        }
    }

    @Test
    fun api33_to_36_requests_bt_and_nearby_wifi_with_zero_location() {
        for (sdk in 33..36) {
            val perms = PermissionPolicy.nearbyTransferPermissions(sdk)
            val expected = listOf(
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_ADVERTISE",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.NEARBY_WIFI_DEVICES"
            )
            assertEquals("SDK $sdk must request BT and Nearby Wi-Fi", expected, perms)
            assertFalse("SDK $sdk must not request location", perms.any { it.contains("LOCATION") })
            assertFalse("SDK $sdk must not request local network before API 37", perms.contains("android.permission.ACCESS_LOCAL_NETWORK"))
        }
    }

    @Test
    fun api37_plus_requests_local_network_in_addition_to_nearby_group() {
        val perms = PermissionPolicy.nearbyTransferPermissions(37)
        val expected = listOf(
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.NEARBY_WIFI_DEVICES",
            "android.permission.ACCESS_LOCAL_NETWORK"
        )
        assertEquals("SDK 37 must include ACCESS_LOCAL_NETWORK", expected, perms)
    }

    @Test
    fun notification_permissions_branching() {
        for (sdk in 26..32) {
            assertTrue("SDK $sdk requires no notification permission", PermissionPolicy.notificationPermissions(sdk).isEmpty())
        }
        for (sdk in 33..37) {
            assertEquals(
                "SDK $sdk requires POST_NOTIFICATIONS",
                listOf("android.permission.POST_NOTIFICATIONS"),
                PermissionPolicy.notificationPermissions(sdk)
            )
        }
    }

    @Test
    fun rationales_are_contextual_and_state_no_location_tracking() {
        assertTrue(PermissionPolicy.getNearbyTransferRationale(28).contains("does not track your location"))
        assertTrue(PermissionPolicy.getNearbyTransferRationale(31).contains("does not track your location"))
        assertTrue(PermissionPolicy.getNearbyTransferRationale(33).contains("does not track your location"))
        assertTrue(PermissionPolicy.getNotificationRationale().isNotBlank())
    }

    @Test
    fun evaluate_when_all_granted_returns_granted() {
        val perms = listOf("perm.A", "perm.B")
        val result = PermissionPolicy.evaluate(
            permissions = perms,
            isGranted = { true },
            shouldShowRationale = { false }
        )
        assertTrue(result is PermissionEvaluation.Granted)
    }

    @Test
    fun evaluate_when_missing_and_fresh_returns_requestable() {
        val perms = listOf("perm.A", "perm.B")
        val result = PermissionPolicy.evaluate(
            permissions = perms,
            isGranted = { it == "perm.A" },
            shouldShowRationale = { false },
            wasPreviouslyRequested = { false }
        )
        assertTrue(result is PermissionEvaluation.Requestable)
        assertEquals(listOf("perm.B"), (result as PermissionEvaluation.Requestable).permissions)
    }

    @Test
    fun evaluate_when_rationale_warranted_returns_needs_rationale() {
        val perms = listOf("perm.A", "perm.B")
        val result = PermissionPolicy.evaluate(
            permissions = perms,
            isGranted = { false },
            shouldShowRationale = { true },
            wasPreviouslyRequested = { true }
        )
        assertTrue(result is PermissionEvaluation.NeedsRationale)
        assertEquals(listOf("perm.A", "perm.B"), (result as PermissionEvaluation.NeedsRationale).permissions)
    }

    @Test
    fun evaluate_when_previously_requested_and_no_rationale_returns_permanently_denied() {
        val perms = listOf("perm.A")
        val result = PermissionPolicy.evaluate(
            permissions = perms,
            isGranted = { false },
            shouldShowRationale = { false },
            wasPreviouslyRequested = { true }
        )
        assertTrue(result is PermissionEvaluation.PermanentlyDenied)
        assertEquals(listOf("perm.A"), (result as PermissionEvaluation.PermanentlyDenied).deniedPermissions)
    }

    @Test
    fun lanTransferPermissions_api26_to_36_requires_zero_permissions() {
        for (sdk in 26..36) {
            val perms = PermissionPolicy.lanTransferPermissions(sdk)
            assertTrue("SDK $sdk LAN transfer requires zero runtime permissions", perms.isEmpty())
            assertFalse("SDK $sdk LAN must not request bluetooth", perms.any { it.contains("BLUETOOTH") })
            assertFalse("SDK $sdk LAN must not request location", perms.any { it.contains("LOCATION") })
        }
    }

    @Test
    fun lanTransferPermissions_api37_requires_only_local_network() {
        val perms = PermissionPolicy.lanTransferPermissions(37)
        assertEquals(
            "SDK 37 LAN must require only ACCESS_LOCAL_NETWORK",
            listOf(PermissionPolicy.PERMISSION_ACCESS_LOCAL_NETWORK),
            perms
        )
        assertFalse("SDK 37 LAN must not require bluetooth", perms.any { it.contains("BLUETOOTH") })
    }

    @Test
    fun scanQrPermissions_requires_camera_on_all_versions() {
        val perms = PermissionPolicy.scanQrPermissions()
        assertEquals(listOf(PermissionPolicy.PERMISSION_CAMERA), perms)
        assertTrue(PermissionPolicy.getScanQrRationale().contains("camera"))
        assertTrue(PermissionPolicy.getScanQrRationale().contains("paste an invitation"))
    }

    @Test
    fun evaluateAction_lanTransfer_grantedOnApi33WithoutPermissions() {
        // LAN on API 33 requires zero runtime permissions -> immediate Granted
        val result = PermissionPolicy.evaluateAction(
            action = PermissionAction.LanTransfer,
            isGranted = { false },
            shouldShowRationale = { false },
            sdkInt = 33
        )
        assertTrue("LAN on API 33 must be granted immediately", result is PermissionEvaluation.Granted)
    }

    @Test
    fun evaluateAction_lanTransfer_checksLocalNetworkOnApi37() {
        val resultGranted = PermissionPolicy.evaluateAction(
            action = PermissionAction.LanTransfer,
            isGranted = { it == PermissionPolicy.PERMISSION_ACCESS_LOCAL_NETWORK },
            shouldShowRationale = { false },
            sdkInt = 37
        )
        assertTrue(resultGranted is PermissionEvaluation.Granted)

        val resultDenied = PermissionPolicy.evaluateAction(
            action = PermissionAction.LanTransfer,
            isGranted = { false },
            shouldShowRationale = { true },
            sdkInt = 37
        )
        assertTrue(resultDenied is PermissionEvaluation.NeedsRationale)
        val needsRationale = resultDenied as PermissionEvaluation.NeedsRationale
        assertEquals(listOf(PermissionPolicy.PERMISSION_ACCESS_LOCAL_NETWORK), needsRationale.permissions)
        assertTrue(needsRationale.rationale.contains("local network"))
    }

    @Test
    fun evaluateAction_scanQr_evaluatesCameraPermission() {
        val result = PermissionPolicy.evaluateAction(
            action = PermissionAction.ScanQr,
            isGranted = { false },
            shouldShowRationale = { true }
        )
        assertTrue(result is PermissionEvaluation.NeedsRationale)
        val needsRationale = result as PermissionEvaluation.NeedsRationale
        assertEquals(listOf(PermissionPolicy.PERMISSION_CAMERA), needsRationale.permissions)
        assertTrue(needsRationale.rationale.contains("camera"))
    }
}
