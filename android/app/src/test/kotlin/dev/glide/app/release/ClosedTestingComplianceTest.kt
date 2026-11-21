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
 * GLIDE-063: Closed Testing Compliance & Readiness Verification Suite.
 *
 * Historical V1 release evidence remains useful, but exact release-artifact checks are only
 * meaningful in a release-candidate workspace where those artifacts have been materialized.
 * Ordinary source CI must not pretend historical binary outputs exist in a clean checkout,
 * while setting `-Dglide.requireReleaseArtifacts=true` (or `GLIDE_REQUIRE_RELEASE_ARTIFACTS=true`)
 * fails closed if release artifacts are missing.
 */
class ClosedTestingComplianceTest {

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
    fun test1_closedTestingProtocol_existsAndDocumentsMandatoryRequirements() {
        val root = resolveProjectRoot()
        val protocolFile = File(root, "doc/release/CLOSED_TESTING_PROTOCOL.md")
        assertTrue("CLOSED_TESTING_PROTOCOL.md must exist", protocolFile.exists())

        val text = protocolFile.readText()
        assertTrue("Protocol must mention 12 distinct Google accounts/testers", text.contains("12") && text.contains("testers"))
        assertTrue("Protocol must mention 14 consecutive days", text.contains("14") && text.contains("days"))
        assertTrue("Protocol must specify Internal Testing Track", text.contains("Internal Testing Track"))
        assertTrue("Protocol must specify Closed Testing Track", text.contains("Closed Testing Track"))
    }

    @Test
    fun test2_cohortMatrix_coversMultiOemAndFormFactors() {
        val root = resolveProjectRoot()
        val text = File(root, "doc/release/CLOSED_TESTING_PROTOCOL.md").readText()

        for (brand in listOf("Pixel", "Samsung", "Xiaomi", "OnePlus")) {
            assertTrue("Protocol must cover hardware brand: $brand", text.contains(brand))
        }
        assertTrue("Protocol must cover Tablet form factor", text.contains("Tablet"))
        assertTrue("Protocol must document legacy API 26 support", text.contains("26"))
        assertTrue("Protocol must document target API 37 support", text.contains("37"))
    }

    @Test
    fun test3_androidVitals_strictQualityThresholds() {
        val root = resolveProjectRoot()
        val text = File(root, "doc/release/CLOSED_TESTING_PROTOCOL.md").readText()

        assertTrue("Crash rate threshold must be specified (< 0.10%)", text.contains("0.10"))
        assertTrue("ANR rate threshold must be specified (< 0.05%)", text.contains("0.05"))
        assertTrue("Must monitor Wake Locks", text.contains("Wake Locks") || text.contains("wake locks"))
        assertTrue("Must monitor background Wi-Fi/Bluetooth scans", text.contains("Wi-Fi") && text.contains("Scans"))
    }

    @Test
    fun test4_feedbackAndBugTriage_privacyPreservingAndSlaDefined() {
        val root = resolveProjectRoot()
        val text = File(root, "doc/release/CLOSED_TESTING_PROTOCOL.md").readText()

        assertTrue("Must enforce zero content access for user files", text.contains("Zero Content Access") || text.contains("never"))
        assertTrue("Must define P0 blocker SLA", text.contains("P0 (Blocker)") || text.contains("P0"))
        assertTrue("Must define P1 critical SLA", text.contains("P1 (Critical)") || text.contains("P1"))
        assertTrue("Must reference deobfuscation mapping.txt", text.contains("mapping.txt"))
    }

    @Test
    fun test5_releaseCandidateArtifacts_presentAndValid_whenMaterialized() {
        val root = resolveProjectRoot()
        val apkFile = File(root, "android/app/build/outputs/apk/release/app-release.apk")
        val aabFile = File(root, "android/app/build/outputs/bundle/release/app-release.aab")
        val mappingFile = File(root, "android/app/build/outputs/mapping/release/mapping.txt")
        val checksumFile = File(root, "android/app/build/outputs/release-artifacts/SHA256SUMS.txt")
        val metadataFile = File(root, "android/app/build/outputs/apk/release/output-metadata.json")

        val artifacts = listOf(apkFile, aabFile, mappingFile, checksumFile, metadataFile)
        if (isReleaseArtifactCheckRequired()) {
            for (artifact in artifacts) {
                assertTrue("Required release artifact missing: ${artifact.absolutePath}", artifact.exists())
            }
        } else {
            assumeTrue(
                "Exact release-candidate artifact checks run only after a candidate is materialized; HARD-041/042 own that gate.",
                artifacts.all { it.exists() }
            )
        }

        assertTrue("APK size must be >= 1 MB", apkFile.length() > 1_000_000L)
        assertTrue("AAB size must be >= 2 MB", aabFile.length() > 2_000_000L)
        assertTrue("mapping.txt must be non-empty", mappingFile.length() > 100_000L)

        val checksums = parseSha256Sums(checksumFile)
        assertEquals("SHA256SUMS.txt must match app-release.apk SHA-256", computeSha256(apkFile), checksums["app-release.apk"])
        assertEquals("SHA256SUMS.txt must match app-release.aab SHA-256", computeSha256(aabFile), checksums["app-release.aab"])
        checksums["mapping.txt"]?.let { recordedMapHash ->
            assertEquals("SHA256SUMS.txt must match mapping.txt SHA-256", computeSha256(mappingFile), recordedMapHash)
        }

        val metadata = Json.parseToJsonElement(metadataFile.readText()).jsonObject
        assertEquals("dev.glide.app", metadata["applicationId"]?.jsonPrimitive?.content)
        assertEquals("release", metadata["variantName"]?.jsonPrimitive?.content)
        val firstElement = metadata["elements"]?.jsonArray?.firstOrNull()?.jsonObject
        assertNotNull("output-metadata.json must contain at least one element", firstElement)
        assertEquals("1.0.0", firstElement!!["versionName"]?.jsonPrimitive?.content)
        assertTrue((firstElement["versionCode"]?.jsonPrimitive?.intOrNull ?: 0) >= 1)
        assertEquals("app-release.apk", firstElement["outputFile"]?.jsonPrimitive?.content)
    }

    @Test
    fun test6_playReviewerGuidance_zeroCredentialRequirement() {
        val root = resolveProjectRoot()
        val text = File(root, "doc/release/CLOSED_TESTING_PROTOCOL.md").readText()

        assertTrue("Reviewer guidance must state no account required", text.contains("No Account Required"))
        assertTrue("Reviewer guidance must explain two-device local transfer", text.contains("Two-Device"))
        assertTrue("Reviewer guidance must mention 6-digit SAS code", text.contains("SAS"))
    }
}
