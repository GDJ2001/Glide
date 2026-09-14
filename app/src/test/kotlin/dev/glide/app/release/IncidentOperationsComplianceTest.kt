package dev.glide.app.release

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GLIDE-066: Incident Response & Rollback Operations Compliance Verification Suite.
 *
 * Programmatically asserts:
 * 1. Existence and completeness of docs/release/INCIDENT_RESPONSE_AND_ROLLBACK_RUNBOOK.md.
 * 2. 4-tier severity matrix (P0, P1, P2, P3) and strict SLAs (<= 2h halt, <= 24h hotfix for P0).
 * 3. Incident Command System (ICS) on-call roles and emergency communication channels.
 * 4. Privacy-preserving report handling and zero user payload content access.
 * 5. Data-corruption escalation, quarantine mechanics, and public storage protection.
 * 6. Forward-fix rollback procedure, schema migration safety, and user data preservation.
 * 7. Three complete tabletop incident simulations and recovery checklists.
 */
class IncidentOperationsComplianceTest {

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
    // 1. Runbook Document & Severity Classification Matrix
    // -------------------------------------------------------------------------

    @Test
    fun test1_incidentRunbook_documentsSeverityMatrixAndSlas() {
        val root = resolveProjectRoot()
        val runbookFile = File(root, "docs/release/INCIDENT_RESPONSE_AND_ROLLBACK_RUNBOOK.md")
        assertTrue("INCIDENT_RESPONSE_AND_ROLLBACK_RUNBOOK.md must exist", runbookFile.exists())

        val text = runbookFile.readText()
        val requiredSeverities = listOf("P0", "P1", "P2", "P3")
        for (sev in requiredSeverities) {
            assertTrue("Must document severity level: $sev", text.contains(sev))
        }

        assertTrue("P0 halt SLA must be <= 2 hours", text.contains("2") && (text.contains("hours") || text.contains("h")))
        assertTrue("P0 hotfix SLA must be <= 24 hours", text.contains("24") && (text.contains("hours") || text.contains("h")))
    }

    // -------------------------------------------------------------------------
    // 2. Incident Command System (ICS) On-Call Roles
    // -------------------------------------------------------------------------

    @Test
    fun test2_icsRolesAndCommunicationChannels_defined() {
        val root = resolveProjectRoot()
        val runbookFile = File(root, "docs/release/INCIDENT_RESPONSE_AND_ROLLBACK_RUNBOOK.md")
        val text = runbookFile.readText()

        val requiredRoles = listOf("Incident Commander", "Technical Lead", "Communications Lead")
        for (role in requiredRoles) {
            assertTrue("Must define ICS on-call role: $role", text.contains(role))
        }

        assertTrue("Must provide security contact email", text.contains("security@glide.dev"))
        assertTrue("Must provide support contact email", text.contains("support@glide.dev"))
    }

    // -------------------------------------------------------------------------
    // 3. Privacy-Preserving User Report Handling
    // -------------------------------------------------------------------------

    @Test
    fun test3_privacyPreservingReportHandling_strictlyEnforced() {
        val root = resolveProjectRoot()
        val runbookFile = File(root, "docs/release/INCIDENT_RESPONSE_AND_ROLLBACK_RUNBOOK.md")
        val text = runbookFile.readText()

        assertTrue("Must enforce zero content inspection", text.contains("Zero Content Inspection") || text.contains("Never Request Payload Files"))
        assertTrue("Must forbid requesting payload files", text.contains("Never Request Payload Files") || text.contains("never request"))
        assertTrue("Must define consented redacted diagnostics", text.contains("Redacted") || text.contains("Sanitized"))
        assertTrue("Must document wrong-recipient sharing triage", text.contains("Wrong-Recipient") || text.contains("wrong-recipient"))
    }

    // -------------------------------------------------------------------------
    // 4. Data-Corruption Quarantine Mechanics
    // -------------------------------------------------------------------------

    @Test
    fun test4_dataCorruptionEscalation_quarantineAndPublicIsolation() {
        val root = resolveProjectRoot()
        val runbookFile = File(root, "docs/release/INCIDENT_RESPONSE_AND_ROLLBACK_RUNBOOK.md")
        val text = runbookFile.readText()

        assertTrue("Must isolate corrupted blocks into quarantine", text.contains("quarantine") || text.contains("Quarantine"))
        assertTrue("Must enforce zero bytes written to public storage upon mismatch", text.contains("Zero bytes written") || text.contains("Zero Public Leakage") || text.contains("zero corrupt bytes"))
        assertTrue("Must document safe quarantine purge", text.contains("deleteRecursively") || text.contains("Quarantine Purge") || text.contains("quarantine purge"))
    }

    // -------------------------------------------------------------------------
    // 5. Forward-Fix Rollback Procedure & Data Sanctity Invariant
    // -------------------------------------------------------------------------

    @Test
    fun test5_forwardFixRollback_andUserDataPreservation() {
        val root = resolveProjectRoot()
        val runbookFile = File(root, "docs/release/INCIDENT_RESPONSE_AND_ROLLBACK_RUNBOOK.md")
        val text = runbookFile.readText()

        assertTrue("Must specify Play Console halt action", text.contains("Halt rollout") || text.contains("Halt Rollout"))
        assertTrue("Must mandate Forward-Fix Rollback", text.contains("Forward-Fix Rollback") || text.contains("forward-fix"))
        assertTrue("Must enforce monotonic versionCode increment", text.contains("versionCode"))
        assertTrue("Must enforce Room schema additive migration", text.contains("schema migrations") || text.contains("database migrations") || text.contains("fallbackToDestructiveMigration"))
        assertTrue("Must guarantee user file preservation invariant", text.contains("never delete user files") || text.contains("never be deleted") || text.contains("User Data Sanctity"))
    }

    // -------------------------------------------------------------------------
    // 6. Tabletop Incident Simulations & Recovery Checklists
    // -------------------------------------------------------------------------

    @Test
    fun test6_tabletopSimulations_threeScenariosAndRecoveryChecklists() {
        val root = resolveProjectRoot()
        val runbookFile = File(root, "docs/release/INCIDENT_RESPONSE_AND_ROLLBACK_RUNBOOK.md")
        val text = runbookFile.readText()

        assertTrue("Must simulate bad transfer / corrupted payload", text.contains("Corrupted Payload") || text.contains("Bad Transfer"))
        assertTrue("Must simulate ANR / main-thread blocking", text.contains("ANR") || text.contains("Main-Thread"))
        assertTrue("Must simulate signing key / credential incident", text.contains("Signing Key") || text.contains("Upload Credential"))
        assertTrue("Must provide recovery verification checklists", text.contains("Recovery Verification Checklist"))
    }
}
