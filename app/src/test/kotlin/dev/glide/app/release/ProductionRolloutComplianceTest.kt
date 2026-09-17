package dev.glide.app.release

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * GLIDE-065 historical controlled-rollout verification.
 *
 * Rollout runbook/document checks remain valid historical evidence. Binary identity checks run
 * only when that frozen candidate is actually materialized; current release proof belongs to
 * HARD-041/HARD-042 rather than ordinary source CI.
 */
class ProductionRolloutComplianceTest {

    private val expectedAabHash = "728d919b76b444f72795f63422eaf5bf312de50b80215c64a804baf1b13d1d52"
    private val expectedApkHash = "cf771dbaf068e7656b2c848300c7679d3e7309a1fc081a2edbc9da56eba6f21c"
    private val expectedMapHash = "9f108d84e77df3700b4944abd15cb2bd6fe439bf6cae01c7ea14c80d4aa9d6e4"

    private fun resolveProjectRoot(): File {
        val current = File(System.getProperty("user.dir") ?: ".").canonicalFile
        return if (File(current, "docs").exists()) current
        else if (File(current.parentFile, "docs").exists()) current.parentFile
        else File("..").canonicalFile
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun test1_rolloutProtocol_documentsPhasedScheduleAnd24hWindow() {
        val root = resolveProjectRoot()
        val protocolFile = File(root, "docs/release/PRODUCTION_ROLLOUT_PROTOCOL.md")
        assertTrue("PRODUCTION_ROLLOUT_PROTOCOL.md must exist", protocolFile.exists())

        val text = protocolFile.readText()
        assertTrue("Must document Application ID dev.glide.app", text.contains("dev.glide.app"))
        assertTrue("Must document versionCode 1", text.contains("versionCode") && text.contains("1"))
        assertTrue("Must document versionName 0.1.0-alpha", text.contains("0.1.0-alpha"))
        for (phase in listOf("5%", "20%", "50%", "100%")) {
            assertTrue("Rollout protocol must specify phase: $phase", text.contains(phase))
        }
        assertTrue("Rollout protocol must require 24-hour observation window", text.contains("24") && (text.contains("hours") || text.contains("h")))
    }

    @Test
    fun test2_cryptographicIdentity_matchesAcrossProtocolAndDisk_whenMaterialized() {
        val root = resolveProjectRoot()
        val protocolFile = File(root, "docs/release/PRODUCTION_ROLLOUT_PROTOCOL.md")
        val aabFile = File(root, "app/build/outputs/bundle/release/app-release.aab")
        val apkFile = File(root, "app/build/outputs/apk/release/app-release.apk")
        val mapFile = File(root, "app/build/outputs/mapping/release/mapping.txt")
        val sumFile = File(root, "app/build/outputs/release-artifacts/SHA256SUMS.txt")

        val artifacts = listOf(aabFile, apkFile, mapFile, sumFile)
        assumeTrue(
            "Historical rollout artifact identity is checked only when the frozen candidate is materialized.",
            artifacts.all { it.exists() }
        )

        val protocolText = protocolFile.readText()
        assertTrue("Protocol must contain expected AAB hash", protocolText.contains(expectedAabHash))
        assertTrue("Protocol must contain expected APK hash", protocolText.contains(expectedApkHash))
        assertTrue("Protocol must contain expected mapping hash", protocolText.contains(expectedMapHash))

        assertEquals("AAB SHA-256 digest on disk must match frozen manifest", expectedAabHash, computeSha256(aabFile))
        assertEquals("APK SHA-256 digest on disk must match frozen manifest", expectedApkHash, computeSha256(apkFile))
        assertEquals("mapping.txt SHA-256 digest on disk must match frozen manifest", expectedMapHash, computeSha256(mapFile))
    }

    @Test
    fun test3_androidVitals_strictProductionThresholds() {
        val root = resolveProjectRoot()
        val text = File(root, "docs/release/PRODUCTION_ROLLOUT_PROTOCOL.md").readText()
        assertTrue("Crash rate threshold must be specified (< 0.10%)", text.contains("0.10"))
        assertTrue("ANR rate threshold must be specified (< 0.05%)", text.contains("0.05"))
        assertTrue("Must monitor Wake Locks", text.contains("Wake Locks") || text.contains("wake locks"))
        assertTrue("Must monitor Wi-Fi/BLE background scans", text.contains("Scans") || text.contains("scans"))
    }

    @Test
    fun test4_immediateHaltTriggersAndForwardFix_documented() {
        val root = resolveProjectRoot()
        val text = File(root, "docs/release/PRODUCTION_ROLLOUT_PROTOCOL.md").readText()
        for (trigger in listOf(
            "Data Corruption", "Consent Bypass", "Unverified Exposure",
            "Vitals Regression", "Critical Vulnerability"
        )) {
            assertTrue("Must specify halt trigger: $trigger", text.contains(trigger))
        }
        assertTrue("Must mandate Forward-Fix Rollback", text.contains("Forward-Fix Rollback"))
        assertTrue("Must preserve Room database schema", text.contains("database schema") || text.contains("database migrations"))
        assertTrue("Must never wipe user files committed to public storage", text.contains("never delete user files") || text.contains("user files"))
    }

    @Test
    fun test5_initialRelease_boundedDistributionContingencyDocumented() {
        val root = resolveProjectRoot()
        val text = File(root, "docs/release/PRODUCTION_ROLLOUT_PROTOCOL.md").readText()
        assertTrue("Must document Bounded Distribution contingency", text.contains("Bounded Distribution") || text.contains("bounded distribution"))
        assertTrue("Must reference Closed Testing Track as pre-production boundary", text.contains("Closed Testing Track"))
        assertTrue("Must document 24/7 active watch during initial launch", text.contains("24/7") || text.contains("active watch"))
    }

    @Test
    fun test6_operatorRunbookAndTwoDeviceSmoke_documented() {
        val root = resolveProjectRoot()
        val text = File(root, "docs/release/PRODUCTION_ROLLOUT_PROTOCOL.md").readText()
        assertTrue("Operator runbook must explain initiating rollout", text.contains("Initiating Staged Rollout") || text.contains("Staged rollout"))
        assertTrue("Operator runbook must explain halting rollout", text.contains("Halt rollout") || text.contains("Halt Rollout"))
        assertTrue("Must document Two-Device Smoke Verification", text.contains("Two-Device Smoke Verification") || text.contains("Two-Device"))
        assertTrue("Must require mutual 6-digit SAS verification", text.contains("SAS"))
    }
}
