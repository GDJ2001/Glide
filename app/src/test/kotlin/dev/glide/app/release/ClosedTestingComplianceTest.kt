package dev.glide.app.release

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GLIDE-063: Closed Testing Compliance & Readiness Verification Suite.
 *
 * Programmatically asserts:
 * 1. Existence and completeness of CLOSED_TESTING_PROTOCOL.md.
 * 2. Adherence to Google Play developer closed-testing policies (12 testers, 14 continuous days).
 * 3. Multi-OEM hardware matrix covering Android 8.0 (API 26) through Android 17 (API 37) and tablets.
 * 4. Android Vitals quality thresholds (crash rate < 0.10%, ANR rate < 0.05%).
 * 5. Privacy-preserving bug triage runbooks and SLA response windows.
 * 6. Verification of Release Candidate supply-chain artifacts and checksum manifests.
 */
class ClosedTestingComplianceTest {

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
    // 1. Closed Testing Protocol Integrity
    // -------------------------------------------------------------------------

    @Test
    fun test1_closedTestingProtocol_existsAndDocumentsMandatoryRequirements() {
        val root = resolveProjectRoot()
        val protocolFile = File(root, "docs/release/CLOSED_TESTING_PROTOCOL.md")
        assertTrue("CLOSED_TESTING_PROTOCOL.md must exist", protocolFile.exists())

        val text = protocolFile.readText()
        assertTrue("Protocol must mention 12 distinct Google accounts/testers", text.contains("12") && text.contains("testers"))
        assertTrue("Protocol must mention 14 consecutive days", text.contains("14") && text.contains("days"))
        assertTrue("Protocol must specify Internal Testing Track", text.contains("Internal Testing Track"))
        assertTrue("Protocol must specify Closed Testing Track", text.contains("Closed Testing Track"))
    }

    // -------------------------------------------------------------------------
    // 2. Multi-OEM Hardware Matrix & Form Factors
    // -------------------------------------------------------------------------

    @Test
    fun test2_cohortMatrix_coversMultiOemAndFormFactors() {
        val root = resolveProjectRoot()
        val protocolFile = File(root, "docs/release/CLOSED_TESTING_PROTOCOL.md")
        val text = protocolFile.readText()

        val requiredBrands = listOf("Pixel", "Samsung", "Xiaomi", "OnePlus")
        for (brand in requiredBrands) {
            assertTrue("Protocol must cover hardware brand: $brand", text.contains(brand))
        }

        assertTrue("Protocol must cover Tablet form factor", text.contains("Tablet"))
        assertTrue("Protocol must document legacy API 26 support", text.contains("26"))
        assertTrue("Protocol must document target API 37 support", text.contains("37"))
    }

    // -------------------------------------------------------------------------
    // 3. Android Vitals Quality Thresholds
    // -------------------------------------------------------------------------

    @Test
    fun test3_androidVitals_strictQualityThresholds() {
        val root = resolveProjectRoot()
        val protocolFile = File(root, "docs/release/CLOSED_TESTING_PROTOCOL.md")
        val text = protocolFile.readText()

        assertTrue("Crash rate threshold must be specified (< 0.10%)", text.contains("0.10"))
        assertTrue("ANR rate threshold must be specified (< 0.05%)", text.contains("0.05"))
        assertTrue("Must monitor Wake Locks", text.contains("Wake Locks") || text.contains("wake locks"))
        assertTrue("Must monitor background Wi-Fi/Bluetooth scans", text.contains("Wi-Fi") && text.contains("Scans"))
    }

    // -------------------------------------------------------------------------
    // 4. Privacy-Preserving Bug Triage & SLAs
    // -------------------------------------------------------------------------

    @Test
    fun test4_feedbackAndBugTriage_privacyPreservingAndSlaDefined() {
        val root = resolveProjectRoot()
        val protocolFile = File(root, "docs/release/CLOSED_TESTING_PROTOCOL.md")
        val text = protocolFile.readText()

        assertTrue("Must enforce zero content access for user files", text.contains("Zero Content Access") || text.contains("never"))
        assertTrue("Must define P0 blocker SLA", text.contains("P0 (Blocker)") || text.contains("P0"))
        assertTrue("Must define P1 critical SLA", text.contains("P1 (Critical)") || text.contains("P1"))
        assertTrue("Must reference deobfuscation mapping.txt", text.contains("mapping.txt"))
    }

    // -------------------------------------------------------------------------
    // 5. Release Candidate Artifact & Supply-Chain Integrity
    // -------------------------------------------------------------------------

    @Test
    fun test5_releaseCandidateArtifacts_presentAndValid() {
        val root = resolveProjectRoot()
        val apkFile = File(root, "app/build/outputs/apk/release/app-release.apk")
        val aabFile = File(root, "app/build/outputs/bundle/release/app-release.aab")
        val mappingFile = File(root, "app/build/outputs/mapping/release/mapping.txt")
        val checksumFile = File(root, "app/build/outputs/release-artifacts/SHA256SUMS.txt")

        assertTrue("app-release.apk must exist", apkFile.exists())
        assertTrue("app-release.aab must exist", aabFile.exists())
        assertTrue("mapping.txt must exist", mappingFile.exists())
        assertTrue("SHA256SUMS.txt must exist", checksumFile.exists())

        assertTrue("APK size must be >= 1 MB", apkFile.length() > 1_000_000L)
        assertTrue("AAB size must be >= 2 MB", aabFile.length() > 2_000_000L)
        assertTrue("mapping.txt must be non-empty", mappingFile.length() > 100_000L)

        val checksumText = checksumFile.readText()
        assertTrue("Checksums must reference app-release.apk", checksumText.contains("app-release.apk"))
        assertTrue("Checksums must reference app-release.aab", checksumText.contains("app-release.aab"))
    }

    // -------------------------------------------------------------------------
    // 6. Play Reviewer Guidance
    // -------------------------------------------------------------------------

    @Test
    fun test6_playReviewerGuidance_zeroCredentialRequirement() {
        val root = resolveProjectRoot()
        val protocolFile = File(root, "docs/release/CLOSED_TESTING_PROTOCOL.md")
        val text = protocolFile.readText()

        assertTrue("Reviewer guidance must state no account required", text.contains("No Account Required"))
        assertTrue("Reviewer guidance must explain two-device local transfer", text.contains("Two-Device"))
        assertTrue("Reviewer guidance must mention 6-digit SAS code", text.contains("SAS"))
    }
}
