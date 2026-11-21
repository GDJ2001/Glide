package dev.glide.app.release

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * GLIDE-065 controlled-rollout verification.
 *
 * Historical rollout runbook/document checks remain valid evidence. Materialized artifact checks
 * verify `android/app/build/outputs/...` for version `1.0.0` against `SHA256SUMS.txt` and
 * `output-metadata.json`, failing closed when `-Dglide.requireReleaseArtifacts=true`
 * (or `GLIDE_REQUIRE_RELEASE_ARTIFACTS=true`) is set.
 */
class ProductionRolloutComplianceTest {

    private fun resolveProjectRoot(): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (current != null) {
            if (File(current, "doc").isDirectory && File(current, "android").isDirectory) {
                return current
            }
            current = current.parentFile
        }
        return File(".").canonicalFile
    }

    private fun isReleaseArtifactCheckRequired(): Boolean =
        System.getProperty("glide.requireReleaseArtifacts").equals("true", ignoreCase = true) ||
            System.getenv("GLIDE_REQUIRE_RELEASE_ARTIFACTS").equals("true", ignoreCase = true)

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

    private fun parseSha256Sums(checksumFile: File): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        val lineRegex = Regex("""^([0-9a-fA-F]{64})\s+(.+)$""")
        checksumFile.readLines().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isNotEmpty()) {
                val match = lineRegex.matchEntire(trimmed)
                if (match != null) {
                    val hash = match.groupValues[1].lowercase()
                    val name = match.groupValues[2].trim().removePrefix("*").substringAfterLast('/').substringAfterLast('\\')
                    entries[name] = hash
                }
            }
        }
        return entries
    }

    @Test
    fun test1_rolloutProtocol_documentsPhasedScheduleAnd24hWindow() {
        val root = resolveProjectRoot()
        val protocolFile = File(root, "doc/release/PRODUCTION_ROLLOUT_PROTOCOL.md")
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
        val aabFile = File(root, "android/app/build/outputs/bundle/release/app-release.aab")
        val apkFile = File(root, "android/app/build/outputs/apk/release/app-release.apk")
        val mapFile = File(root, "android/app/build/outputs/mapping/release/mapping.txt")
        val sumFile = File(root, "android/app/build/outputs/release-artifacts/SHA256SUMS.txt")
        val metadataFile = File(root, "android/app/build/outputs/apk/release/output-metadata.json")

        val artifacts = listOf(aabFile, apkFile, mapFile, sumFile, metadataFile)
        if (isReleaseArtifactCheckRequired()) {
            for (artifact in artifacts) {
                assertTrue("Required release artifact missing: ${artifact.absolutePath}", artifact.exists())
            }
        } else {
            assumeTrue(
                "Rollout artifact identity is checked only when a 1.0.0 candidate is materialized.",
                artifacts.all { it.exists() }
            )
        }

        assertTrue("APK size must be >= 1 MB", apkFile.length() > 1_000_000L)
        assertTrue("AAB size must be >= 2 MB", aabFile.length() > 2_000_000L)
        assertTrue("mapping.txt must be >= 100 KB", mapFile.length() > 100_000L)

        val checksums = parseSha256Sums(sumFile)
        assertEquals("AAB SHA-256 digest on disk must match SHA256SUMS.txt", computeSha256(aabFile), checksums["app-release.aab"])
        assertEquals("APK SHA-256 digest on disk must match SHA256SUMS.txt", computeSha256(apkFile), checksums["app-release.apk"])
        assertEquals("mapping.txt SHA-256 digest on disk must match SHA256SUMS.txt", computeSha256(mapFile), checksums["mapping.txt"])

        val metadata = Json.parseToJsonElement(metadataFile.readText()).jsonObject
        assertEquals("dev.glide.app", metadata["applicationId"]?.jsonPrimitive?.content)
        assertEquals("release", metadata["variantName"]?.jsonPrimitive?.content)
        val firstElement = metadata["elements"]?.jsonArray?.firstOrNull()?.jsonObject
        assertNotNull("output-metadata.json must contain an element", firstElement)
        assertEquals("1.0.0", firstElement!!["versionName"]?.jsonPrimitive?.content)
        assertTrue((firstElement["versionCode"]?.jsonPrimitive?.intOrNull ?: 0) >= 1)
        assertEquals("app-release.apk", firstElement["outputFile"]?.jsonPrimitive?.content)
    }

    @Test
    fun test3_androidVitals_strictProductionThresholds() {
        val root = resolveProjectRoot()
        val text = File(root, "doc/release/PRODUCTION_ROLLOUT_PROTOCOL.md").readText()
        assertTrue("Crash rate threshold must be specified (< 0.10%)", text.contains("0.10"))
        assertTrue("ANR rate threshold must be specified (< 0.05%)", text.contains("0.05"))
        assertTrue("Must monitor Wake Locks", text.contains("Wake Locks") || text.contains("wake locks"))
        assertTrue("Must monitor Wi-Fi/BLE background scans", text.contains("Scans") || text.contains("scans"))
    }

    @Test
    fun test4_immediateHaltTriggersAndForwardFix_documented() {
        val root = resolveProjectRoot()
        val text = File(root, "doc/release/PRODUCTION_ROLLOUT_PROTOCOL.md").readText()
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
        val text = File(root, "doc/release/PRODUCTION_ROLLOUT_PROTOCOL.md").readText()
        assertTrue("Must document Bounded Distribution contingency", text.contains("Bounded Distribution") || text.contains("bounded distribution"))
        assertTrue("Must reference Closed Testing Track as pre-production boundary", text.contains("Closed Testing Track"))
        assertTrue("Must document 24/7 active watch during initial launch", text.contains("24/7") || text.contains("active watch"))
    }

    @Test
    fun test6_operatorRunbookAndTwoDeviceSmoke_documented() {
        val root = resolveProjectRoot()
        val text = File(root, "doc/release/PRODUCTION_ROLLOUT_PROTOCOL.md").readText()
        assertTrue("Operator runbook must explain initiating rollout", text.contains("Initiating Staged Rollout") || text.contains("Staged rollout"))
        assertTrue("Operator runbook must explain halting rollout", text.contains("Halt rollout") || text.contains("Halt Rollout"))
        assertTrue("Must document Two-Device Smoke Verification", text.contains("Two-Device Smoke Verification") || text.contains("Two-Device"))
        assertTrue("Must require mutual 6-digit SAS verification", text.contains("SAS"))
    }
}
