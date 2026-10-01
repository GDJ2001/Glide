package dev.glide.app.release

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GLIDE-068: Historical V1 Closure and Next-Cycle Handoff Compliance Verification Suite.
 *
 * This suite verifies that the preserved V1 closure record remains internally complete while
 * respecting the newer HARD-001–HARD-043 status authority. Historical release/closure claims
 * are evidence records; they must not overwrite the current hardening ledger.
 */
class V1ClosureComplianceTest {

    private fun resolveProjectRoot(): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (current != null) {
            if (File(current, "doc").isDirectory || File(current, "docs").isDirectory) {
                return current
            }
            current = current.parentFile
        }
        return File(".").canonicalFile
    }

    // -------------------------------------------------------------------------
    // 1. V1 Closure Document & Shipped Artifact Hashes
    // -------------------------------------------------------------------------

    @Test
    fun test1_v1ClosureDocument_containsExecutiveSummaryAndArtifactHashes() {
        val root = resolveProjectRoot()
        val docFile = File(root, "doc/release/V1_CLOSURE_AND_NEXT_CYCLE_HANDOFF.md")
        assertTrue("V1_CLOSURE_AND_NEXT_CYCLE_HANDOFF.md must exist", docFile.exists())

        val text = docFile.readText()
        assertTrue("Must specify package dev.glide.app", text.contains("dev.glide.app"))
        assertTrue("Must specify versionName 0.1.0-alpha", text.contains("0.1.0-alpha"))
        assertTrue("Must specify versionCode 1", text.contains("versionCode 1") || text.contains("versionCode: `1`"))
        assertTrue("Must specify target SDK 37", text.contains("37"))
        assertTrue("Must contain signed AAB SHA-256 digest", text.contains("36a575796859556ef38290abf0ee8ba733737b98d9ae4870f2f768b753dc9a73"))
        assertTrue("Must contain signed APK SHA-256 digest", text.contains("798a69d76378c2e7b8f2b74070be292db8323a677353f47e24483756208000ec"))
    }

    // -------------------------------------------------------------------------
    // 2. Historical V1 Closure vs Current HARD Authority
    // -------------------------------------------------------------------------

    @Test
    fun test2_historicalV1Closure_isPreservedWithoutOverridingCurrentHardAuthority() {
        val root = resolveProjectRoot()
        val closureFile = File(root, "doc/release/V1_CLOSURE_AND_NEXT_CYCLE_HANDOFF.md")
        val todoFile = File(root, "doc/TODO_MASTER.md")
        assertTrue("Historical V1 closure record must exist", closureFile.exists())
        assertTrue("Current TODO_MASTER.md authority must exist", todoFile.exists())

        val closureText = closureFile.readText()
        assertTrue(
            "Historical closure must preserve the original 68/68 V1 completion statement",
            closureText.contains("68 of 68") && closureText.contains("100.0%")
        )
        assertTrue(
            "Historical closure must preserve the M12 4/4 completion record",
            closureText.contains("GLIDE-065–068") && closureText.contains("4 / 4")
        )

        val todoText = todoFile.readText()
        assertTrue(
            "Current ledger must explicitly identify itself as the active development authority",
            todoText.contains("Current status authority for active development") ||
                todoText.contains("HARD task history; verify current status against source and fresh checks")
        )
        assertTrue(
            "Current ledger must identify the HARD-001–HARD-043 program",
            todoText.contains("HARD-001–HARD-043")
        )
        assertTrue(
            "Current ledger must preserve the historical GLIDE ledger without treating it as current certification",
            todoText.contains("historical GLIDE-001–072 ledger") && todoText.contains("pre-HARD commit")
        )
    }

    // -------------------------------------------------------------------------
    // 3. Milestone Retrospective Across All 13 Milestones
    // -------------------------------------------------------------------------

    @Test
    fun test3_milestoneRetrospective_reviewsAllThirteenMilestones() {
        val root = resolveProjectRoot()
        val docFile = File(root, "doc/release/V1_CLOSURE_AND_NEXT_CYCLE_HANDOFF.md")
        val text = docFile.readText()

        val milestones = listOf("M0", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "M10", "M11", "M12")
        for (m in milestones) {
            assertTrue("V1 closure retrospective must review milestone: $m", text.contains("**$m**"))
        }

        // Verify key architectural guarantees
        assertTrue("Must review pure JVM isolation of :transfer-core", text.contains(":transfer-core"))
        assertTrue("Must review two-stage commit staging architecture", text.contains("two-stage commit"))
        assertTrue("Must review 16 KB native memory page alignment", text.contains("16 KB"))
    }

    // -------------------------------------------------------------------------
    // 4. Protocol Conformance & Zero Protocol Drift Certification
    // -------------------------------------------------------------------------

    @Test
    fun test4_protocolDriftCertification_certifiesAllEightMessageCodecs() {
        val root = resolveProjectRoot()
        val docFile = File(root, "doc/release/V1_CLOSURE_AND_NEXT_CYCLE_HANDOFF.md")
        val text = docFile.readText()

        assertTrue("Must certify magic header bytes GLID", text.contains("GLID"))
        assertTrue("Must certify 0x47 magic byte", text.contains("0x47"))

        val messageTypes = listOf(
            "DISCOVERY_ANNOUNCE",
            "DISCOVERY_RESPONSE",
            "TRANSFER_REQUEST",
            "TRANSFER_ACCEPT",
            "TRANSFER_REJECT",
            "CHUNK_DATA",
            "CHUNK_ACK",
            "TRANSFER_COMPLETE"
        )
        for (msg in messageTypes) {
            assertTrue("Must certify protocol message codec: $msg", text.contains(msg))
        }

        assertTrue("Must certify 6-digit mutual SAS verification", text.contains("SAS"))
        assertTrue("Must certify Room database Version 1", text.contains("Version 1"))
    }

    // -------------------------------------------------------------------------
    // 5. Historical M13 Handoff vs Current Authorized Hardening Program
    // -------------------------------------------------------------------------

    @Test
    fun test5_historicalM13FutureWork_isPreservedWhileCurrentLedgerOwnsActiveScope() {
        val root = resolveProjectRoot()
        val docFile = File(root, "doc/release/V1_CLOSURE_AND_NEXT_CYCLE_HANDOFF.md")
        val todoFile = File(root, "doc/TODO_MASTER.md")
        val closureText = docFile.readText()

        val futureTasks = listOf("GLIDE-069", "GLIDE-070", "GLIDE-071", "GLIDE-072")
        for (task in futureTasks) {
            assertTrue("Historical V1 closure must document future milestone task: $task", closureText.contains(task))
        }
        assertTrue(
            "Historical V1 closure must preserve M13 as future/not-started at that historical boundary",
            closureText.contains("M13") && closureText.contains("NOT_STARTED")
        )

        val todoText = todoFile.readText()
        assertTrue(
            "Active scope must be governed by the HARD program rather than the historical M13 status",
            todoText.contains("Current program: **HARD-001–HARD-043")
        )
        assertTrue(
            "Current ledger must explicitly explain that historical completion labels do not certify the current candidate",
            todoText.contains("Historical `COMPLETE`, release, shipped, or certified labels do not certify the current multi-platform candidate")
        )
    }

    // -------------------------------------------------------------------------
    // 6. Maintenance Schedule & Security Incident Response SLAs
    // -------------------------------------------------------------------------

    @Test
    fun test6_maintenanceScheduleAndSla_codifiesReviewCadences() {
        val root = resolveProjectRoot()
        val docFile = File(root, "doc/release/V1_CLOSURE_AND_NEXT_CYCLE_HANDOFF.md")
        val text = docFile.readText()

        assertTrue("Must codify monthly dependency review", text.contains("Monthly") || text.contains("monthly"))
        assertTrue("Must codify quarterly target SDK review", text.contains("Quarterly") || text.contains("quarterly"))
        assertTrue("Must codify Critical / P0 SLA (<= 24 hours)", text.contains("24") && (text.contains("hour") || text.contains("hours")))
        assertTrue("Must codify High / P1 SLA (<= 48 hours)", text.contains("48") && (text.contains("hour") || text.contains("hours")))
    }
}
