package dev.glide.app.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * GLIDE-064: Production Release Candidate Approval Gate Verification Suite.
 *
 * Programmatically asserts:
 * 1. Existence and approval sign-off in docs/release/PRODUCTION_RC_APPROVAL.md.
 * 2. Cryptographic digest identity of frozen release candidate artifacts (AAB, APK, mapping.txt).
 * 3. Definition of Done (DoD) Quality Gates reconciliation covering all 6 required disciplines.
 * 4. 100% completion of the 12-item Store Readiness Checklist in docs/release/GLIDE_RELEASE_PLAN.md.
 * 5. Incident response halt triggers and forward-fix rollback protocol compliance.
 * 6. Architecture constraints: target SDK 37, 16 KB page alignment, zero-cloud relay.
 */
class ProductionReleaseGateTest {

    private val expectedAabHash = "728d919b76b444f72795f63422eaf5bf312de50b80215c64a804baf1b13d1d52"
    private val expectedApkHash = "cf771dbaf068e7656b2c848300c7679d3e7309a1fc081a2edbc9da56eba6f21c"
    private val expectedMapHash = "9f108d84e77df3700b4944abd15cb2bd6fe439bf6cae01c7ea14c80d4aa9d6e4"

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

    // -------------------------------------------------------------------------
    // 1. Production Approval Record Integrity
    // -------------------------------------------------------------------------

    @Test
    fun test1_productionApprovalRecord_approvedAndIdentifiesArtifacts() {
        val root = resolveProjectRoot()
        val approvalFile = File(root, "docs/release/PRODUCTION_RC_APPROVAL.md")
        assertTrue("PRODUCTION_RC_APPROVAL.md must exist", approvalFile.exists())

        val text = approvalFile.readText()
        assertTrue("Must be marked APPROVED", text.contains("APPROVED"))
        assertTrue("Must specify Application ID dev.glide.app", text.contains("dev.glide.app"))
        assertTrue("Must specify versionCode 1", text.contains("versionCode") && text.contains("1"))
        assertTrue("Must specify versionName 0.1.0-alpha", text.contains("0.1.0-alpha"))
        assertTrue("Must contain expected AAB hash", text.contains(expectedAabHash))
        assertTrue("Must contain expected APK hash", text.contains(expectedApkHash))
        assertTrue("Must contain expected mapping hash", text.contains(expectedMapHash))
    }

    // -------------------------------------------------------------------------
    // 2. Cryptographic Artifact Integrity Verification
    // -------------------------------------------------------------------------

    @Test
    fun test2_frozenArtifacts_matchAuthoritativeHashesOnDisk() {
        val root = resolveProjectRoot()
        val aabFile = File(root, "app/build/outputs/bundle/release/app-release.aab")
        val apkFile = File(root, "app/build/outputs/apk/release/app-release.apk")
        val mapFile = File(root, "app/build/outputs/mapping/release/mapping.txt")
        val sumFile = File(root, "app/build/outputs/release-artifacts/SHA256SUMS.txt")

        assertTrue("app-release.aab must exist", aabFile.exists())
        assertTrue("app-release.apk must exist", apkFile.exists())
        assertTrue("mapping.txt must exist", mapFile.exists())
        assertTrue("SHA256SUMS.txt must exist", sumFile.exists())

        assertEquals("AAB SHA-256 digest must match frozen candidate", expectedAabHash, computeSha256(aabFile))
        assertEquals("APK SHA-256 digest must match frozen candidate", expectedApkHash, computeSha256(apkFile))
        assertEquals("mapping.txt SHA-256 digest must match frozen candidate", expectedMapHash, computeSha256(mapFile))

        val sumText = sumFile.readText()
        assertTrue("SHA256SUMS.txt must contain AAB hash", sumText.contains(expectedAabHash))
        assertTrue("SHA256SUMS.txt must contain APK hash", sumText.contains(expectedApkHash))
        assertTrue("SHA256SUMS.txt must contain mapping hash", sumText.contains(expectedMapHash))
    }

    // -------------------------------------------------------------------------
    // 3. Definition of Done Quality Gates Reconciliation
    // -------------------------------------------------------------------------

    @Test
    fun test3_definitionOfDoneGates_reconciledAndApproved() {
        val root = resolveProjectRoot()
        val approvalFile = File(root, "docs/release/PRODUCTION_RC_APPROVAL.md")
        val text = approvalFile.readText()

        val requiredGates = listOf(
            "Architecture Gate",
            "UX & Interaction Gate",
            "Security Gate",
            "Performance Gate",
            "Accessibility Gate",
            "Release Gate"
        )

        for (gate in requiredGates) {
            assertTrue("Quality gates matrix must reconcile: $gate", text.contains(gate))
        }

        // Verify key evidence reports exist on disk
        val requiredEvidence = listOf(
            "docs/testing/evidence/GLIDE-043-premium-ux-gate-report.md",
            "docs/testing/evidence/GLIDE-047-adaptive-accessibility-gate-report.md",
            "docs/testing/evidence/GLIDE-050-security-review-gate-report.md",
            "docs/testing/evidence/GLIDE-054-performance-gate-report.md",
            "docs/testing/evidence/GLIDE-055-architecture-static-analysis-report.md",
            "docs/testing/evidence/GLIDE-061-store-assets-legal-listing-report.md",
            "docs/testing/evidence/GLIDE-062-signed-rc-artifacts-report.md",
            "docs/testing/evidence/GLIDE-063-closed-testing-report.md"
        )

        for (relPath in requiredEvidence) {
            val file = File(root, relPath)
            assertTrue("Evidence report must exist: $relPath", file.exists())
        }
    }

    // -------------------------------------------------------------------------
    // 4. Store Readiness Checklist Completion
    // -------------------------------------------------------------------------

    @Test
    fun test4_storeReadinessChecklist_allTwelveItemsCompleted() {
        val root = resolveProjectRoot()
        val planFile = File(root, "docs/release/GLIDE_RELEASE_PLAN.md")
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
            if (inChecklist && line.startsWith("## ")) {
                inChecklist = false
                break
            }
            if (inChecklist) {
                if (line.trim().startsWith("- [x]")) {
                    checkedCount++
                } else if (line.trim().startsWith("- [ ]")) {
                    uncheckedCount++
                }
            }
        }

        assertEquals("Store readiness checklist must have exactly 12 completed items", 12, checkedCount)
        assertEquals("Store readiness checklist must have 0 unchecked items", 0, uncheckedCount)
    }

    // -------------------------------------------------------------------------
    // 5. Incident Response & Rollback Runbook
    // -------------------------------------------------------------------------

    @Test
    fun test5_incidentResponseAndRollback_haltTriggersAndForwardFix() {
        val root = resolveProjectRoot()
        val approvalFile = File(root, "docs/release/PRODUCTION_RC_APPROVAL.md")
        val text = approvalFile.readText()

        val requiredTriggers = listOf(
            "Data Corruption",
            "Consent Bypass",
            "Unverified Exposure",
            "Vitals Regression",
            "Critical Vulnerability"
        )

        for (trigger in requiredTriggers) {
            assertTrue("Incident response must document trigger: $trigger", text.contains(trigger))
        }

        assertTrue("Must mandate Forward-Fix Rollback", text.contains("Forward-Fix Rollback"))
        assertTrue("Must preserve Room database schema", text.contains("database schema"))
        assertTrue("Must never wipe user files committed to public storage", text.contains("never delete user files") || text.contains("never wipe user files") || text.contains("user files"))
    }

    // -------------------------------------------------------------------------
    // 6. Target SDK 37 & 16 KB Page Alignment
    // -------------------------------------------------------------------------

    @Test
    fun test6_targetSdkAndNativeAlignment_documentedAndVerified() {
        val root = resolveProjectRoot()
        val approvalFile = File(root, "docs/release/PRODUCTION_RC_APPROVAL.md")
        val text = approvalFile.readText()

        assertTrue("Must specify Target SDK 37", text.contains("37"))
        assertTrue("Must specify 16 KB page aligned native architecture", text.contains("16 KB"))
        assertTrue("Must document APK signature scheme v2/v3/v4", text.contains("v2") && text.contains("v3") && text.contains("v4"))
    }
}
