package dev.glide.app.release

import dev.glide.app.config.LanFeatureConfig
import dev.glide.app.routing.TransportRouter
import dev.glide.core.discovery.DeclaredPlatform
import dev.glide.core.discovery.PeerCandidate
import dev.glide.core.routing.RouteCandidate
import dev.glide.core.routing.RouteSelectionResult
import dev.glide.core.transport.TransportType
import dev.glide.transport.lan.LanTransportFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * LAN-032: Release Candidate Verification Suite for LAN/QR Transport.
 *
 * Programmatically asserts release candidate packaging, security hardening,
 * local disable-LAN rollback configuration, ProGuard rules, permission boundaries,
 * Data Safety disclosures, and open-source license attribution.
 */
class LanReleaseCandidateVerificationTest {

    @Before
    fun setUp() {
        LanFeatureConfig.reset()
    }

    @After
    fun tearDown() {
        LanFeatureConfig.reset()
    }

    private fun resolveProjectRoot(): File {
        val current = File(System.getProperty("user.dir") ?: ".").canonicalFile
        return if (File(current, "docs").exists()) {
            current
        } else if (File(current.parentFile, "docs").exists()) {
            current.parentFile
        } else {
            File("..").canonicalFile
        }
    }

    // -------------------------------------------------------------------------
    // 1. Local Disable-LAN Rollback Validation
    // -------------------------------------------------------------------------

    @Test
    fun test1_localDisableLanRollback_removesLanFromRoutingAndPreservesNearby() {
        // Step A: When enabled (default), LAN_NSD_TLS is included
        assertTrue(LanFeatureConfig.isLanTransportEnabled)
        val dummyLanFactory = object : LanTransportFactory(null) {}
        val router = TransportRouter(
            context = null,
            lanTransportFactory = dummyLanFactory,
            lanNetworkScope = null,
            nearbyAvailabilityChecker = { dev.glide.transport.nearby.NearbyHardwareStatus.Available },
            permissionChecker = { true },
            apiLevelProvider = { 34 },
            nearbyTransportFactory = null
        )

        val defaultFilter = router.buildFilterContext()
        assertTrue(defaultFilter.implementedTransports.contains(TransportType.GMS_NEARBY_P2P))
        assertTrue(defaultFilter.implementedTransports.contains(TransportType.LAN_NSD_TLS))

        // Step B: Toggle rollback flag to disable LAN
        LanFeatureConfig.isLanTransportEnabled = false
        assertFalse(LanFeatureConfig.isLanTransportEnabled)

        val rolledBackFilter = router.buildFilterContext()
        assertTrue(
            "Nearby must remain implemented when LAN is disabled",
            rolledBackFilter.implementedTransports.contains(TransportType.GMS_NEARBY_P2P)
        )
        assertFalse(
            "LAN must be cleanly omitted from implemented transports when rolled back",
            rolledBackFilter.implementedTransports.contains(TransportType.LAN_NSD_TLS)
        )

        // Step C: Evaluating a candidate with both Nearby and LAN routes selects Nearby
        val dualCandidate = PeerCandidate(
            uiKey = "peer-dual-1",
            displayName = "Dual Device",
            platform = DeclaredPlatform.ANDROID,
            routes = listOf(
                RouteCandidate(
                    routeId = "route-lan-1",
                    transportType = TransportType.LAN_NSD_TLS,
                    endpointHandle = "192.168.1.50:52424"
                ),
                RouteCandidate(
                    routeId = "route-nearby-1",
                    transportType = TransportType.GMS_NEARBY_P2P,
                    endpointHandle = "nearby-ep-1"
                )
            )
        )

        val selection = router.evaluateRoute(dualCandidate, nowMs = 1000L)
        assertTrue(
            "When LAN is rolled back, dual candidate must cleanly resolve to Nearby",
            selection is RouteSelectionResult.Selected
        )
        val selectedRoute = (selection as RouteSelectionResult.Selected).route
        assertEquals(TransportType.GMS_NEARBY_P2P, selectedRoute.transportType)
        assertEquals("nearby-ep-1", selectedRoute.endpointHandle)

        // Step D: Evaluating a LAN-only candidate is rejected gracefully
        val lanOnlyCandidate = PeerCandidate(
            uiKey = "peer-lan-only",
            displayName = "LAN Only Device",
            platform = DeclaredPlatform.ANDROID,
            routes = listOf(
                RouteCandidate(
                    routeId = "route-lan-2",
                    transportType = TransportType.LAN_NSD_TLS,
                    endpointHandle = "192.168.1.50:52424"
                )
            )
        )

        val lanSelection = router.evaluateRoute(lanOnlyCandidate, nowMs = 1000L)
        assertTrue(
            "LAN-only candidate must be rejected when LAN transport is disabled",
            lanSelection is RouteSelectionResult.Rejected
        )
        val rejected = lanSelection as RouteSelectionResult.Rejected
        assertTrue(rejected.reason.contains("not implemented"))
    }

    // -------------------------------------------------------------------------
    // 2. ProGuard / R8 Rules Preservation for LAN & QR
    // -------------------------------------------------------------------------

    @Test
    fun test2_proguardRules_preservesCameraXZXingAndLanSymbols() {
        val root = resolveProjectRoot()
        val proguardFile = File(root, "android/proguard-rules.pro")
        assertTrue("android/proguard-rules.pro must exist", proguardFile.exists())

        val content = proguardFile.readText()

        val requiredLanAndQrRules = listOf(
            "androidx.camera.**",
            "com.google.zxing.**",
            "dev.glide.transport.lan.**",
            "dev.glide.core.pairing.**",
            "dev.glide.core.routing.**",
            "dev.glide.core.discovery.**"
        )

        for (rule in requiredLanAndQrRules) {
            assertTrue(
                "android/proguard-rules.pro must preserve symbol: $rule",
                content.contains(rule)
            )
        }
    }

    // -------------------------------------------------------------------------
    // 3. Manifest Security Hardening & Camera Optionality
    // -------------------------------------------------------------------------

    @Test
    fun test3_manifest_cameraOptionalAndLocalNetworkDeclared() {
        val root = resolveProjectRoot()
        val manifestFile = File(root, "android/src/main/AndroidManifest.xml")
        assertTrue("android/src/main/AndroidManifest.xml must exist", manifestFile.exists())

        val content = manifestFile.readText()

        // ACCESS_LOCAL_NETWORK declared for API 37+
        assertTrue(
            "Manifest must declare ACCESS_LOCAL_NETWORK",
            content.contains("android.permission.ACCESS_LOCAL_NETWORK")
        )

        // CAMERA permission declared
        assertTrue(
            "Manifest must declare CAMERA permission for QR scanning",
            content.contains("android.permission.CAMERA")
        )

        // Camera hardware declared optional (required = false)
        assertTrue(
            "Manifest must declare android.hardware.camera required=\"false\"",
            content.contains("android:name=\"android.hardware.camera\"") &&
                content.contains("android:required=\"false\"")
        )

        // Allow backup disabled
        assertTrue(
            "Manifest must set android:allowBackup=\"false\"",
            content.contains("android:allowBackup=\"false\"")
        )

        // Connected device foreground service
        assertTrue(
            "Manifest must declare connectedDevice foreground service type",
            content.contains("foregroundServiceType=\"connectedDevice\"")
        )
    }

    // -------------------------------------------------------------------------
    // 4. Permission Matrix & Architecture Support Boundaries
    // -------------------------------------------------------------------------

    @Test
    fun test4_permissionMatrix_documentsLanBoundariesAndZeroBluetoothRequirement() {
        val root = resolveProjectRoot()
        val matrixFile = File(root, "doc/android/PERMISSION_MATRIX.md")
        assertTrue("PERMISSION_MATRIX.md must exist", matrixFile.exists())

        val content = matrixFile.readText()

        assertTrue(
            "Matrix must document API 29+ requirement for LAN",
            content.contains("API 29+")
        )
        assertTrue(
            "Matrix must document zero Bluetooth permissions required for LAN",
            content.contains("Zero Bluetooth permissions required")
        )
        assertTrue(
            "Matrix must document zero Location permissions required for LAN",
            content.contains("Zero Location permissions required")
        )
        assertTrue(
            "Matrix must document camera hardware optionality",
            content.contains("required=\"false\"")
        )
        assertTrue(
            "Matrix must document rollback via LanFeatureConfig",
            content.contains("LanFeatureConfig")
        )
    }

    // -------------------------------------------------------------------------
    // 5. Data Safety Disclosures
    // -------------------------------------------------------------------------

    @Test
    fun test5_dataSafetyDeclaration_coversLanTlsAndQrScanning() {
        val root = resolveProjectRoot()
        val dataSafetyFile = File(root, "doc/release/DATA_SAFETY_DECLARATION.md")
        assertTrue("DATA_SAFETY_DECLARATION.md must exist", dataSafetyFile.exists())

        val content = dataSafetyFile.readText()

        assertTrue(
            "Data Safety must disclose TLS 1.3 with pinned SHA-256 SPKI key fingerprints",
            content.contains("TLS 1.3") && content.contains("SPKI")
        )
        assertTrue(
            "Data Safety must declare in-memory QR frame processing with zero saved images",
            content.contains("CameraX") && content.contains("zero photos")
        )
        assertTrue(
            "Data Safety must declare ephemeral key lifecycle",
            content.contains("P-256")
        )
    }

    // -------------------------------------------------------------------------
    // 6. Open Source Licenses Completeness
    // -------------------------------------------------------------------------

    @Test
    fun test6_openSourceLicenses_includesCameraXAndZXing() {
        val root = resolveProjectRoot()
        val licensesFile = File(root, "doc/release/OPEN_SOURCE_LICENSES.md")
        assertTrue("OPEN_SOURCE_LICENSES.md must exist", licensesFile.exists())

        val docContent = licensesFile.readText()
        assertTrue("OPEN_SOURCE_LICENSES.md must list AndroidX CameraX", docContent.contains("AndroidX CameraX"))
        assertTrue("OPEN_SOURCE_LICENSES.md must list ZXing Core", docContent.contains("ZXing Core"))

        val settingsScreenFile = File(root, "android/src/main/kotlin/dev/glide/app/ui/settings/SettingsScreen.kt")
        assertTrue("SettingsScreen.kt must exist", settingsScreenFile.exists())

        val screenContent = settingsScreenFile.readText()
        assertTrue("In-app LicensesDialog must list AndroidX CameraX", screenContent.contains("AndroidX CameraX"))
        assertTrue("In-app LicensesDialog must list ZXing Core", screenContent.contains("ZXing Core"))
    }
}
