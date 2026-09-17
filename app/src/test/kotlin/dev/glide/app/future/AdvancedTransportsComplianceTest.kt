package dev.glide.app.future

import dev.glide.core.transport.DeviceEnvironment
import dev.glide.core.transport.TransportProfiles
import dev.glide.core.transport.TransportSelectionEngine
import dev.glide.core.transport.TransportTier
import dev.glide.core.transport.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GLIDE-071: Advanced Android Transports Evaluation Compliance Verification Suite.
 *
 * Programmatically asserts:
 * 1. ADR-0016 exists, defines the multi-tier transport architecture, and is indexed in docs/adr/README.md.
 * 2. ADVANCED_ANDROID_TRANSPORTS_EVALUATION.md provides complete technical evaluation matrix, radio coexistence analysis, and TLS 1.3 mandate.
 * 3. V1 production manifest adheres strictly to zero silent permission expansion (no broad storage, no background location, bounded legacy location).
 * 4. Threat model matrix covers threats T01-T08, T10-T15, T17, and T18 with cryptographic SAS/TOFU binding.
 * 5. Pure JVM domain models in transfer-core enforce transport tiers, hardware constraints, and TLS 1.3 security.
 * 6. TransportSelectionEngine accurately implements heuristic prioritization across device environments.
 */
class AdvancedTransportsComplianceTest {

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

    @Test
    fun test1_adr0016_existsAndIsIndexed() {
        val root = resolveProjectRoot()
        val adrFile = File(root, "docs/adr/ADR-0016-advanced-android-transports.md")
        assertTrue("ADR-0016 must exist", adrFile.exists())

        val adrText = adrFile.readText()
        assertTrue("ADR-0016 must document Multi-Tier Transport Architecture", adrText.contains("Multi-Tier Transport Architecture"))
        assertTrue("ADR-0016 must document Tier 1", adrText.contains("Tier 1"))
        assertTrue("ADR-0016 must document Tier 2", adrText.contains("Tier 2"))
        assertTrue("ADR-0016 must document Tier 3", adrText.contains("Tier 3"))
        assertTrue("ADR-0016 must document Autonomous Wi-Fi Direct", adrText.contains("Autonomous Wi-Fi Direct"))
        assertTrue("ADR-0016 must document Wi-Fi Aware", adrText.contains("Wi-Fi Aware"))
        assertTrue("ADR-0016 must document LAN + NSD", adrText.contains("LAN + Network Service Discovery"))

        val indexFile = File(root, "docs/adr/README.md")
        assertTrue("docs/adr/README.md must exist", indexFile.exists())
        assertTrue("docs/adr/README.md must index ADR-0016", indexFile.readText().contains("ADR-0016"))
    }

    @Test
    fun test2_specification_mandatesEvaluationMatrixAndCoexistence() {
        val root = resolveProjectRoot()
        val specFile = File(root, "docs/future/ADVANCED_ANDROID_TRANSPORTS_EVALUATION.md")
        assertTrue("ADVANCED_ANDROID_TRANSPORTS_EVALUATION.md must exist", specFile.exists())

        val specText = specFile.readText()
        assertTrue("Must include evaluation matrix", specText.contains("Candidate Transport Evaluation Matrix"))
        assertTrue("Must analyze Wi-Fi Aware", specText.contains("Wi-Fi Aware"))
        assertTrue("Must analyze Autonomous Group Owner", specText.contains("Autonomous Group Owner"))
        assertTrue("Must analyze Local-Only Hotspot", specText.contains("Local-Only Hotspot"))
        assertTrue("Must analyze LAN + NSD", specText.contains("LAN + NSD"))
        assertTrue("Must analyze radio coexistence", specText.contains("Radio Coexistence"))
        assertTrue("Must analyze Single-PHY and Dual-PHY", specText.contains("Single-PHY") && specText.contains("Dual-PHY"))
        assertTrue("Must mandate Application-Layer TLS 1.3", specText.contains("Application-Layer TLS 1.3"))
    }

    @Test
    fun test3_v1PermissionIsolation_strictNonExpansion() {
        val root = resolveProjectRoot()
        val manifestFile = File(root, "app/src/main/AndroidManifest.xml")
        assertTrue("app AndroidManifest.xml must exist", manifestFile.exists())

        val manifestText = manifestFile.readText()

        // Assert no broad storage permissions
        val bannedStorage = listOf(
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO"
        )
        for (perm in bannedStorage) {
            assertTrue("V1 must not declare $perm", !manifestText.contains(perm))
        }

        // Assert no background location or intrusive device permissions
        val bannedIntrusive = listOf(
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.RECORD_AUDIO",
            "android.permission.READ_CONTACTS",
            "android.permission.RECEIVE_BOOT_COMPLETED"
        )
        for (perm in bannedIntrusive) {
            assertTrue("Must not declare $perm", !manifestText.contains(perm))
        }

        // Camera is permitted for offline QR scanning only, with hardware marked strictly optional
        assertTrue("Camera permission must be paired with required=false feature",
            Regex("""<uses-feature\s+android:name="android\.hardware\.camera"\s+android:required="false"""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(manifestText))

        // Assert legacy location permissions are bounded by maxSdkVersion="32"
        assertTrue("ACCESS_FINE_LOCATION must be bounded by maxSdkVersion=32",
            Regex("""android\.permission\.ACCESS_FINE_LOCATION".*?android:maxSdkVersion="32"""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(manifestText))
        assertTrue("ACCESS_COARSE_LOCATION must be bounded by maxSdkVersion=32",
            Regex("""android\.permission\.ACCESS_COARSE_LOCATION".*?android:maxSdkVersion="32"""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(manifestText))

        // Assert privacy flag neverForLocation is preserved
        assertTrue("Bluetooth and Wi-Fi permissions must assert neverForLocation", manifestText.contains("neverForLocation"))
    }

    @Test
    fun test4_threatModel_mitigationsVerified() {
        val root = resolveProjectRoot()
        val specFile = File(root, "docs/future/ADVANCED_ANDROID_TRANSPORTS_EVALUATION.md")
        val specText = specFile.readText()

        val requiredThreats = listOf("T01", "T02", "T03", "T04", "T05", "T06", "T07", "T08", "T10", "T11", "T12", "T13", "T14", "T15", "T17", "T18")
        for (threat in requiredThreats) {
            assertTrue("Threat $threat must be documented in evaluation matrix", specText.contains("**$threat**"))
        }

        assertTrue("Must specify SAS derivation", specText.contains("ExtractDecimal6") || specText.contains("SAS"))
        assertTrue("Must specify zero silent permission expansion", specText.contains("Zero Silent Expansion of V1 Permissions") || specText.contains("Zero silent expansion of V1 permissions"))
    }

    @Test
    fun test5_pureJvmDomainModels_enforceTiersAndTls13() {
        assertEquals("Must evaluate 5 standard transport profiles", 5, TransportProfiles.ALL.size)

        for (profile in TransportProfiles.ALL) {
            assertTrue("Profile minApiLevel must be >= 14", profile.minApiLevel >= 14)
            assertTrue("Profile throughput must be positive", profile.maxThroughputMbps > 0)
            assertTrue("Availability ratio must be in 0..1", profile.hardwareAvailabilityRatio in 0.0..1.0)
            if (profile.type != TransportType.GMS_NEARBY_P2P) {
                assertTrue("Non-GMS socket transport ${profile.type} must require TLS 1.3", profile.requiresTls13)
            }
        }
    }

    @Test
    fun test6_transportSelectionEngine_prioritizesCorrectly() {
        // Standard GMS phone -> Tier 1
        val gmsEnv = DeviceEnvironment(
            apiLevel = 34,
            hasGms = true,
            hasWifiAwareHardware = false,
            hasWifiDirectHardware = true,
            isConnectedToLocalNetwork = true,
            peerSupportsCrossPlatform = false
        )
        val selectedGms = TransportSelectionEngine.selectBestTransport(gmsEnv)
        assertNotNull(selectedGms)
        assertEquals(TransportType.GMS_NEARBY_P2P, selectedGms?.type)
        assertEquals(TransportTier.TIER_1_DEFAULT_P2P, selectedGms?.tier)

        // Cross-platform desktop peer -> Tier 2 (LAN + NSD)
        val crossPlatformEnv = gmsEnv.copy(peerSupportsCrossPlatform = true)
        val selectedCrossPlatform = TransportSelectionEngine.selectBestTransport(crossPlatformEnv)
        assertNotNull(selectedCrossPlatform)
        assertEquals(TransportType.LAN_NSD_TLS, selectedCrossPlatform?.type)
        assertEquals(TransportTier.TIER_2_CROSS_PLATFORM_LAN, selectedCrossPlatform?.tier)

        // De-Googled AOSP phone on local network -> Tier 2
        val aospLanEnv = DeviceEnvironment(
            apiLevel = 33,
            hasGms = false,
            hasWifiAwareHardware = false,
            hasWifiDirectHardware = true,
            isConnectedToLocalNetwork = true,
            peerSupportsCrossPlatform = false
        )
        val selectedAospLan = TransportSelectionEngine.selectBestTransport(aospLanEnv)
        assertNotNull(selectedAospLan)
        assertEquals(TransportType.LAN_NSD_TLS, selectedAospLan?.type)
        assertEquals(TransportTier.TIER_2_CROSS_PLATFORM_LAN, selectedAospLan?.tier)

        // De-Googled AOSP phone offline (no router) -> Tier 3 (Autonomous Wi-Fi Direct)
        val aospOfflineEnv = aospLanEnv.copy(isConnectedToLocalNetwork = false)
        val selectedAospOffline = TransportSelectionEngine.selectBestTransport(aospOfflineEnv)
        assertNotNull(selectedAospOffline)
        assertEquals(TransportType.WIFI_DIRECT_P2P, selectedAospOffline?.type)
        assertEquals(TransportTier.TIER_3_GMS_FREE_OFFLINE_P2P, selectedAospOffline?.tier)
    }
}
