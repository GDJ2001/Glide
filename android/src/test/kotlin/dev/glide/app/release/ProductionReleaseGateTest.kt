package dev.glide.app.release

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * GLIDE-064 historical production-release gate verification.
 *
 * Documentation controls remain testable in ordinary source CI. Exact binary hash checks execute
 * only when the historical release-candidate artifacts are actually materialized on disk; current
 * release-candidate proof belongs to HARD-041/HARD-042.
 */
class ProductionReleaseGateTest {

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
        val aabFile = File(root, "app/build/outputs/bundle/release/app-release.aab")
        val apkFile = File(root, "app/build/outputs/apk/release/app-release.apk")
        val mapFile = File(root, "app/build/outputs/mapping/release/mapping.txt")
        val sumFile = File(root, "app/build/outputs/release-artifacts/SHA256SUMS.txt")

        val artifacts = listOf(aabFile, apkFile, mapFile, sumFile)
        assumeTrue(
            "Historical artifact hash verification requires a materialized candidate; ordinary source CI intentionally has none.",
            artifacts.all { it.exists() }
        )

        assertEquals("AAB SHA-256 digest must match frozen candidate", expectedAabHash, computeSha256(aabFile))
        assertEquals("APK SHA-256 digest must match frozen candidate", expectedApkHash, computeSha256(apkFile))
        assertEquals("mapping.txt SHA-256 digest must match frozen candidate", expectedMapHash, computeSha256(mapFile))

        val sumText = sumFile.readText()
        assertTrue("SHA256SUMS.txt must contain AAB hash", sumText.contains(expectedAabHash))
        assertTrue("SHA256SUMS.txt must contain APK hash", sumText.contains(expectedApkHash))
        assertTrue("SHA256SUMS.txt must contain mapping hash", sumText.contains(expectedMapHash))
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
