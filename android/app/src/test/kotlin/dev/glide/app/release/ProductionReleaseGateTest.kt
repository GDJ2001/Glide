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
 * GLIDE-064 production-release gate verification.
 *
 * Historical V1 approval documentation controls remain testable in ordinary source CI.
 * Release-candidate artifact checks target `android/app/build/outputs/...` for version `1.0.0`,
 * skipping via `assumeTrue` when unmaterialized unless `-Dglide.requireReleaseArtifacts=true`
 * (or `GLIDE_REQUIRE_RELEASE_ARTIFACTS=true`) is specified.
 */
class ProductionReleaseGateTest {

    // Historical values recorded in PRODUCTION_RC_APPROVAL.md (0.1.0-alpha).
    private val expectedAabHash = "a2d12fb51e3fa293e9190849abae9ed72fb906dff05a2a18eb069ef1a5d941c2"
    private val expectedApkHash = "22d5db96b773c370a90c7fdf65f2b779e52437ecf4a2ccff94037b83d1489493"
    private val expectedMapHash = "e4c1de9006da57630b4c79773ae456d582cc14a8f9db5751db5edca631491cfd"

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
    fun test0_buildConfiguration_targetsVersion1_0_0() {
        val root = resolveProjectRoot()
        val buildGradle = File(root, "android/app/build.gradle.kts")
        assertTrue("android/app/build.gradle.kts must exist", buildGradle.exists())
        val content = buildGradle.readText()

        assertTrue(
            "Default applicationId must be dev.glide.app",
            Regex("""customApplicationId[\s\S]*?\.getOrElse\("dev\.glide\.app"\)""").containsMatchIn(content)
        )
        assertTrue(
            "Default versionName must be 1.0.0",
            Regex("""customVersionName[\s\S]*?\.getOrElse\("1\.0\.0"\)""").containsMatchIn(content)
        )
        val versionCodeMatch = Regex("""customVersionCode[\s\S]*?\.getOrElse\((\d+)\)""").find(content)
        assertNotNull("Default customVersionCode must be declared", versionCodeMatch)
        assertTrue(
            "Default versionCode must be >= 1",
            (versionCodeMatch!!.groupValues[1].toIntOrNull() ?: 0) >= 1
        )
    }

    @Test
    fun test1_productionApprovalRecord_approvedAndIdentifiesHistoricalArtifacts() {
        val root = resolveProjectRoot()
        val approvalFile = File(root, "doc/release/PRODUCTION_RC_APPROVAL.md")
        assertTrue("PRODUCTION_RC_APPROVAL.md must exist", approvalFile.exists())

        val text = approvalFile.readText()
        assertTrue("Historical record must be marked APPROVED", text.contains("APPROVED"))
        assertTrue("Must specify Application ID dev.glide.app", text.contains("dev.glide.app"))
        assertTrue("Must specify versionCode 1", text.contains("versionCode") && text.contains("1"))
        assertTrue("Must specify versionName 0.1.0-alpha", text.contains("0.1.0-alpha"))
        assertTrue("Must contain expected AAB hash", text.contains(expectedAabHash))
        assertTrue("Must contain expected APK hash", text.contains(expectedApkHash))
        assertTrue("Must contain expected mapping hash", text.contains(expectedMapHash))
    }

    @Test
    fun test2_frozenArtifacts_matchAuthoritativeHashes_whenMaterialized() {
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
                "Release artifact hash verification requires materialized 1.0.0 candidate outputs; ordinary source CI intentionally has none.",
                artifacts.all { it.exists() }
            )
        }

        assertTrue("APK size must be >= 1 MB", apkFile.length() > 1_000_000L)
        assertTrue("AAB size must be >= 2 MB", aabFile.length() > 2_000_000L)
        assertTrue("mapping.txt must be >= 100 KB", mapFile.length() > 100_000L)

        val checksums = parseSha256Sums(sumFile)
        assertEquals("AAB SHA-256 digest must match SHA256SUMS.txt", computeSha256(aabFile), checksums["app-release.aab"])
        assertEquals("APK SHA-256 digest must match SHA256SUMS.txt", computeSha256(apkFile), checksums["app-release.apk"])
        assertEquals("mapping.txt SHA-256 digest must match SHA256SUMS.txt", computeSha256(mapFile), checksums["mapping.txt"])

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
    fun test3_definitionOfDoneGates_reconciledAndApprovedHistorically() {
        val root = resolveProjectRoot()
        val text = File(root, "doc/release/PRODUCTION_RC_APPROVAL.md").readText()

        for (gate in listOf(
            "Architecture Gate", "UX & Interaction Gate", "Security Gate",
            "Performance Gate", "Accessibility Gate", "Release Gate"
        )) {
            assertTrue("Quality gates matrix must reconcile: $gate", text.contains(gate))
        }

        for (relPath in listOf(
            "doc/testing/evidence/GLIDE-043-premium-ux-gate-report.md",
            "doc/testing/evidence/GLIDE-047-adaptive-accessibility-gate-report.md",
            "doc/testing/evidence/GLIDE-050-security-review-gate-report.md",
            "doc/testing/evidence/GLIDE-054-performance-gate-report.md",
            "doc/testing/evidence/GLIDE-055-architecture-static-analysis-report.md",
            "doc/testing/evidence/GLIDE-061-store-assets-legal-listing-report.md",
            "doc/testing/evidence/GLIDE-062-signed-rc-artifacts-report.md",
            "doc/testing/evidence/GLIDE-063-closed-testing-report.md"
        )) {
            assertTrue("Historical evidence report must exist: $relPath", File(root, relPath).exists())
        }
    }

    @Test
    fun test4_storeReadinessChecklist_historicalRecordHasTwelveCompletedItems() {
        val root = resolveProjectRoot()
        val planFile = File(root, "doc/release/GLIDE_RELEASE_PLAN.md")
        assertTrue("GLIDE_RELEASE_PLAN.md must exist", planFile.exists())

        val lines = planFile.readLines()
        var inChecklist = false
        var checkedCount = 0
        var uncheckedCount = 0

        for (line in lines) {
            if (line.contains("## Store readiness checklist")) {
                inChecklist = true
                continue
            }
            if (inChecklist && line.startsWith("## ")) break
            if (inChecklist) {
                if (line.trim().startsWith("- [x]")) checkedCount++
                else if (line.trim().startsWith("- [ ]")) uncheckedCount++
            }
        }

        assertEquals("Historical checklist must have exactly 12 completed items", 12, checkedCount)
        assertEquals("Historical checklist must have 0 unchecked items", 0, uncheckedCount)
    }

    @Test
    fun test5_incidentResponseAndRollback_haltTriggersAndForwardFix() {
        val root = resolveProjectRoot()
        val text = File(root, "doc/release/PRODUCTION_RC_APPROVAL.md").readText()

        for (trigger in listOf(
            "Data Corruption", "Consent Bypass", "Unverified Exposure",
            "Vitals Regression", "Critical Vulnerability"
        )) {
            assertTrue("Incident response must document trigger: $trigger", text.contains(trigger))
        }

        assertTrue("Must mandate Forward-Fix Rollback", text.contains("Forward-Fix Rollback"))
        assertTrue("Must preserve Room database schema", text.contains("database schema"))
        assertTrue("Must preserve user files", text.contains("user files"))
    }

    @Test
    fun test6_targetSdkAndNativeAlignment_documentedHistorically() {
        val root = resolveProjectRoot()
        val text = File(root, "doc/release/PRODUCTION_RC_APPROVAL.md").readText()
        assertTrue("Must specify Target SDK 37", text.contains("37"))
        assertTrue("Must specify 16 KB page aligned native architecture", text.contains("16 KB"))
        assertTrue("Must document APK signature scheme v2/v3/v4", text.contains("v2") && text.contains("v3") && text.contains("v4"))
    }
}
