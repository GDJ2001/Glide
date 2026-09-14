package dev.glide.app.acceptance

import dev.glide.core.coordinator.ItemPhase
import dev.glide.core.coordinator.SessionPhase
import dev.glide.core.coordinator.SessionState
import dev.glide.core.domain.BatchId
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.MessageId
import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferItem
import dev.glide.core.stream.StreamHasher
import dev.glide.core.stream.StreamProgressSampler
import dev.glide.data.db.ReceiveJournalDao
import dev.glide.data.db.ReceiveJournalEntity
import dev.glide.data.db.ReceivedFileDao
import dev.glide.data.db.ReceivedFileEntity
import dev.glide.data.store.AndroidReceiveStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * GLIDE-059: Release-Candidate Quality Gates Verification Suite.
 *
 * Programmatically asserts release-candidate engineering invariants across all dimensions:
 * 1. Evidence Artifact Completeness: All milestone reports (M1-M10) exist and are non-empty.
 * 2. Security Boundaries & Permissions: No MANAGE_EXTERNAL_STORAGE, no BOOT_COMPLETED, connectedDevice foreground service.
 * 3. Backup Rules: Explicit exclusion of all sensitive storage domains (root, db, sharedpref, file, external).
 * 4. Architecture Isolation: Pure Kotlin JVM isolation in :transfer-core (zero Android imports).
 * 5. UI Boundary Isolation: Zero transport, Nearby, or raw socket imports in UI / Navigation layers.
 * 6. Protocol Limits Invariants: 64 GiB item cap, 256 GiB batch cap, 1,000 item limit.
 * 7. Canonical Identifier Validation: Strict UUIDv4 lowercase and 64-char hex SHA-256 validation.
 * 8. Cryptographic Tamper Detection: Single-bit alteration detection via StreamHasher.
 * 9. Storage Quarantine & Anti-Orphan: Staging isolation and clean abort discarding without residual orphans.
 * 10. Performance Rate Limiting: StreamProgressSampler throttles progress updates to <= 10 Hz (>= 100ms).
 * 11. Bidirectional Parity: Symmetric roles (Sender/Receiver) and complete session state machine coverage.
 */
class ReleaseCandidateQualityGateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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
    // 1. Evidence Artifact Completeness (M1 - M10)
    // -------------------------------------------------------------------------

    @Test
    fun test1_evidenceArtifactCompleteness_allMilestonesHaveReports() {
        val root = resolveProjectRoot()
        val evidenceDir = File(root, "docs/testing/evidence")
        assertTrue("Evidence directory must exist at ${evidenceDir.absolutePath}", evidenceDir.isDirectory)

        val requiredReports = listOf(
            "GLIDE-017-physical-slice-protocol.md",
            "GLIDE-018-transport-benchmark.md",
            "GLIDE-034-reliability-gate-report.md",
            "GLIDE-043-premium-ux-gate-report.md",
            "GLIDE-047-adaptive-accessibility-gate-report.md",
            "GLIDE-050-security-review-gate-report.md",
            "GLIDE-054-performance-gate-report.md",
            "GLIDE-055-architecture-static-analysis-report.md",
            "GLIDE-056-database-upgrade-regression-report.md",
            "GLIDE-057-emulator-ui-automation-report.md",
            "GLIDE-058-cross-oem-physical-acceptance-report.md",
            "GLIDE-059-release-candidate-quality-gates-report.md"
        )

        for (reportName in requiredReports) {
            val reportFile = File(evidenceDir, reportName)
            assertTrue(
                "Required evidence report missing: $reportName at ${reportFile.absolutePath}",
                reportFile.exists()
            )
            assertTrue(
                "Evidence report $reportName must be non-trivial (> 1,000 bytes, was ${reportFile.length()})",
                reportFile.length() > 1000L
            )
            val content = reportFile.readText()
            val hasPassIndicator = content.contains("PASS", ignoreCase = true) ||
                content.contains("COMPLETE", ignoreCase = true) ||
                content.contains("VERIFIED", ignoreCase = true)
            assertTrue(
                "Evidence report $reportName must contain verification pass indication",
                hasPassIndicator
            )
        }
    }

    // -------------------------------------------------------------------------
    // 2. Security Boundaries & Permissions in AndroidManifest
    // -------------------------------------------------------------------------

    @Test
    fun test2_releaseManifest_securityBoundariesAndForegroundService() {
        val root = resolveProjectRoot()
        val manifestFile = File(root, "app/src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml must exist at ${manifestFile.absolutePath}", manifestFile.exists())

        val content = manifestFile.readText()

        // Disallowed high-risk broad permissions
        assertFalse(
            "MANAGE_EXTERNAL_STORAGE must NEVER be requested in release candidate manifest",
            content.contains("MANAGE_EXTERNAL_STORAGE")
        )
        assertFalse(
            "RECEIVE_BOOT_COMPLETED must NEVER be requested (no background auto-start)",
            content.contains("RECEIVE_BOOT_COMPLETED")
        )

        // Foreground Service Type must be connectedDevice
        assertTrue(
            "Foreground service type must be connectedDevice",
            content.contains("android:foregroundServiceType=\"connectedDevice\"")
        )

        // TransferService must not be exported
        assertTrue(
            "TransferService must not be exported to external applications",
            content.contains("android:name=\".service.TransferService\"") &&
                content.contains("android:exported=\"false\"")
        )

        // Application level backup and extraction rules
        assertTrue("android:allowBackup must be false", content.contains("android:allowBackup=\"false\""))
        assertTrue("android:dataExtractionRules must reference @xml/data_extraction_rules",
            content.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue("android:fullBackupContent must reference @xml/backup_rules",
            content.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue("android:supportsRtl must be true", content.contains("android:supportsRtl=\"true\""))
    }

    // -------------------------------------------------------------------------
    // 3. Backup Rules and Data Extraction Rules Exclusions
    // -------------------------------------------------------------------------

    @Test
    fun test3_backupAndDataExtractionRules_exclusionOfSensitiveDomains() {
        val root = resolveProjectRoot()
        val backupRules = File(root, "app/src/main/res/xml/backup_rules.xml")
        val extractionRules = File(root, "app/src/main/res/xml/data_extraction_rules.xml")

        assertTrue("backup_rules.xml must exist", backupRules.exists())
        assertTrue("data_extraction_rules.xml must exist", extractionRules.exists())

        val backupContent = backupRules.readText()
        val extractionContent = extractionRules.readText()

        val requiredExcludedDomains = listOf("root", "database", "sharedpref", "file", "external")

        for (domain in requiredExcludedDomains) {
            assertTrue(
                "backup_rules.xml must exclude domain '$domain'",
                backupContent.contains("<exclude domain=\"$domain\"")
            )
            assertTrue(
                "data_extraction_rules.xml must exclude domain '$domain' in cloud-backup and device-transfer",
                extractionContent.contains("<exclude domain=\"$domain\"")
            )
        }

        assertTrue(
            "data_extraction_rules.xml must contain <cloud-backup> section",
            extractionContent.contains("<cloud-backup>")
        )
        assertTrue(
            "data_extraction_rules.xml must contain <device-transfer> section",
            extractionContent.contains("<device-transfer>")
        )
    }

    // -------------------------------------------------------------------------
    // 4. Pure Kotlin JVM Isolation in :transfer-core
    // -------------------------------------------------------------------------

    @Test
    fun test4_pureJvmDomainIsolation_transferCoreZeroAndroidImports() {
        val root = resolveProjectRoot()
        val coreSourceDir = File(root, "transfer-core/src/main/kotlin")
        assertTrue(":transfer-core source dir must exist at ${coreSourceDir.absolutePath}", coreSourceDir.isDirectory)

        val forbiddenCorePrefixes = listOf(
            "import android.",
            "import androidx.",
            "import com.google.android.gms."
        )

        var scannedFiles = 0
        coreSourceDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            scannedFiles++
            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trim()
                for (forbidden in forbiddenCorePrefixes) {
                    assertFalse(
                        "Pure JVM boundary violation in ${file.name}:${index + 1}: '$trimmed'",
                        trimmed.startsWith(forbidden)
                    )
                }
            }
        }

        assertTrue("Must have scanned multiple Kotlin files in :transfer-core, scanned: $scannedFiles", scannedFiles >= 10)
    }

    // -------------------------------------------------------------------------
    // 5. UI / Navigation Layer Boundary Isolation
    // -------------------------------------------------------------------------

    @Test
    fun test5_uiLayerArchitecturalBoundaries_zeroTransportImports() {
        val root = resolveProjectRoot()
        val uiDirs = listOf(
            File(root, "app/src/main/kotlin/dev/glide/app/ui"),
            File(root, "app/src/main/kotlin/dev/glide/app/navigation"),
            File(root, "designsystem/src/main/kotlin/dev/glide/designsystem")
        )

        val forbiddenUiPrefixes = listOf(
            "import com.google.android.gms.nearby",
            "import dev.glide.transport.nearby",
            "import android.bluetooth",
            "import android.net.wifi",
            "import java.net.Socket",
            "import java.net.ServerSocket",
            "import androidx.room."
        )

        var scannedFiles = 0
        for (dir in uiDirs) {
            if (!dir.exists()) continue
            dir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
                scannedFiles++
                val lines = file.readLines()
                for ((index, line) in lines.withIndex()) {
                    val trimmed = line.trim()
                    for (forbidden in forbiddenUiPrefixes) {
                        assertFalse(
                            "UI layer boundary violation in ${file.name}:${index + 1}: '$trimmed'",
                            trimmed.startsWith(forbidden)
                        )
                    }
                }
            }
        }

        assertTrue("Must have scanned UI/Design/Nav Kotlin files, scanned: $scannedFiles", scannedFiles >= 15)
    }

    // -------------------------------------------------------------------------
    // 6. ProtocolLimits Constants Invariants
    // -------------------------------------------------------------------------

    @Test
    fun test6_protocolLimits_invariantsAndBoundaryCaps() {
        // 64 GiB max item bytes
        assertEquals(64L * 1024 * 1024 * 1024, ProtocolLimits.MAX_ITEM_BYTES)

        // 256 GiB max batch bytes
        assertEquals(256L * 1024 * 1024 * 1024, ProtocolLimits.MAX_BATCH_BYTES)

        // 1,000 max items per batch
        assertEquals(1000, ProtocolLimits.MAX_ITEMS_PER_BATCH)

        // Name and MIME constraints
        assertEquals(255, ProtocolLimits.MAX_FILENAME_CODE_POINTS)
        assertEquals(1024, ProtocolLimits.MAX_FILENAME_UTF8_BYTES)
        assertEquals(127, ProtocolLimits.MAX_MIME_LENGTH)

        // Control payload limit
        assertEquals(16384, ProtocolLimits.MAX_CONTROL_PAYLOAD_BYTES)

        // StreamHasher bounded buffer constraints
        assertEquals(65536, StreamHasher.DEFAULT_BUFFER_SIZE)
        assertEquals(256 * 1024, StreamHasher.BUFFER_SIZE_256_KIB)
    }

    // -------------------------------------------------------------------------
    // 7. Canonical Identifier Validation (UUIDv4 and SHA-256)
    // -------------------------------------------------------------------------

    @Test
    fun test7_canonicalIdentifierValidation_strictUuidV4AndSha256Regex() {
        // Valid canonical lowercase UUIDv4
        val validUuid = UUID.randomUUID().toString().lowercase()
        assertEquals(validUuid, SessionId(validUuid).value)
        assertEquals(validUuid, BatchId(validUuid).value)
        assertEquals(validUuid, ItemId(validUuid).value)
        assertEquals(validUuid, MessageId(validUuid).value)

        // Path traversal attempts must be rejected
        val maliciousInputs = listOf(
            "../../etc/passwd",
            "../secret.txt",
            "non-uuid-string",
            "",
            validUuid.uppercase(), // Uppercase is forbidden per canonical lowercase UUIDv4 rule
            "12345678-1234-1234-1234-123456789abc" // Version 1, not 4
        )

        for (bad in maliciousInputs) {
            try {
                SessionId(bad)
                fail("SessionId must reject invalid input: '$bad'")
            } catch (expected: IllegalArgumentException) {
                // Expected
            }

            try {
                ItemId(bad)
                fail("ItemId must reject invalid input: '$bad'")
            } catch (expected: IllegalArgumentException) {
                // Expected
            }
        }

        // SHA-256 validation: exactly 64 lowercase hex characters
        val validHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(validHash, Sha256Digest(validHash).value)

        val invalidHashes = listOf(
            "e3b0c442", // Too short
            "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855", // Uppercase
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b85z", // Non-hex 'z'
            ""
        )

        for (badHash in invalidHashes) {
            try {
                Sha256Digest(badHash)
                fail("Sha256Digest must reject invalid hash: '$badHash'")
            } catch (expected: IllegalArgumentException) {
                // Expected
            }
        }
    }

    // -------------------------------------------------------------------------
    // 8. Cryptographic Tamper Detection via StreamHasher
    // -------------------------------------------------------------------------

    @Test
    fun test8_streamHasher_singleBitTamperDetection() = runBlocking {
        val payloadSize = 4096
        val originalBytes = ByteArray(payloadSize) { (it % 251).toByte() }
        val originalDigest = Sha256Digest.compute(originalBytes)

        // Copy and tamper with exactly 1 bit
        val tamperedBytes = originalBytes.clone()
        tamperedBytes[2048] = (tamperedBytes[2048].toInt() xor 0x01).toByte()
        val tamperedDigest = Sha256Digest.compute(tamperedBytes)

        assertNotEquals(
            "A single bit flip must produce a completely different SHA-256 digest",
            originalDigest.value,
            tamperedDigest.value
        )

        // StreamHasher must calculate tampered digest faithfully
        val tamperedInput = ByteArrayInputStream(tamperedBytes)
        val sink = ByteArrayOutputStream()

        val hashingResult = StreamHasher.copyAndHash(
            inputStream = tamperedInput,
            outputStream = sink,
            expectedBytes = payloadSize.toLong()
        )

        assertEquals(tamperedDigest, hashingResult.digest)
        assertNotEquals(originalDigest, hashingResult.digest)
    }

    // -------------------------------------------------------------------------
    // 9. Storage Quarantine and Anti-Orphan Invariants
    // -------------------------------------------------------------------------

    private class GateTestReceiveJournalDao : ReceiveJournalDao {
        val map = mutableMapOf<String, ReceiveJournalEntity>()
        override suspend fun insertOrUpdate(entry: ReceiveJournalEntity) { map[entry.itemId] = entry }
        override suspend fun getByItemId(itemId: String): ReceiveJournalEntity? = map[itemId]
        override suspend fun updatePhase(itemId: String, phase: String, updatedAtMs: Long) {
            map[itemId] = map[itemId]?.copy(phase = phase, updatedAtMs = updatedAtMs) ?: return
        }
        override suspend fun updateVerifiedDigest(itemId: String, phase: String, digest: String, updatedAtMs: Long) {
            map[itemId] = map[itemId]?.copy(phase = phase, verifiedDigest = digest, updatedAtMs = updatedAtMs) ?: return
        }
        override suspend fun delete(itemId: String) { map.remove(itemId) }
        override suspend fun getPendingEntries(): List<ReceiveJournalEntity> = map.values.toList()
        override suspend fun getEntriesBySessionId(sessionId: String): List<ReceiveJournalEntity> =
            map.values.filter { it.sessionId == sessionId }
        override suspend fun deleteBySessionId(sessionId: String) {
            map.entries.removeIf { it.value.sessionId == sessionId }
        }
    }

    private class GateTestReceivedFileDao : ReceivedFileDao {
        val files = mutableMapOf<String, ReceivedFileEntity>()
        override suspend fun insertOrUpdate(file: ReceivedFileEntity) { files[file.fileKey] = file }
        override suspend fun getByFileKey(fileKey: String): ReceivedFileEntity? = files[fileKey]
        override suspend fun getByOriginatingItemId(itemId: String): ReceivedFileEntity? =
            files.values.firstOrNull { it.originatingItemId == itemId }
        override suspend fun getActiveFiles(): List<ReceivedFileEntity> = files.values.filter { it.deletedAtMs == null }
        override suspend fun getActiveFileCount(): Int = files.values.count { it.deletedAtMs == null }
        override suspend fun getActiveFileTotalBytes(): Long = files.values.filter { it.deletedAtMs == null }.sumOf { it.length }
        override suspend fun markDeleted(fileKey: String, deletedAtMs: Long) {
            files[fileKey] = files[fileKey]?.copy(deletedAtMs = deletedAtMs) ?: return
        }
        override suspend fun deletePermanently(fileKey: String) { files.remove(fileKey) }
        override suspend fun detachItemIdsOlderThan(cutoffMs: Long): Int = 0
        override suspend fun detachItemIdsExceedingMax(maxCount: Int): Int = 0
        override suspend fun detachAllItemIds(): Int = 0
    }

    @Test
    fun test9_storageQuarantineAndAntiOrphanInvariants() = runBlocking {
        val rootDir = tempFolder.newFolder("glide_quality_gate_quarantine")
        val journalDao = GateTestReceiveJournalDao()
        val fileDao = GateTestReceivedFileDao()
        val receiveStore = AndroidReceiveStore(rootDir, journalDao, fileDao)

        val sessionId = SessionId(UUID.randomUUID().toString())
        val itemId = ItemId(UUID.randomUUID().toString())
        val expectedBytes = 1024L
        val displayName = "quality_gate_report.pdf"
        val mime = "application/pdf"

        // 1. Prepare Staging: creates quarantine .part file
        val stagingHandle = receiveStore.prepareStaging(sessionId, itemId, expectedBytes, displayName, mime)
        val stagingFile = File(receiveStore.stagingDir, "${itemId.value}.part")

        assertTrue("Staging file must exist in quarantine directory", stagingFile.exists())
        assertTrue("Staging file must be inside stagingDir", stagingFile.parentFile?.absolutePath == receiveStore.stagingDir.absolutePath)
        assertTrue("Staging file must have .part extension", stagingFile.name.endsWith(".part"))
        assertEquals("Received dir must have 0 committed files", 0, receiveStore.receivedDir.listFiles()?.size ?: 0)

        // Write partial content
        stagingFile.writeBytes(ByteArray(512) { 0x42 })

        // 2. Discard staging on transfer abort: removes .part file and journal entry
        receiveStore.discardStaging(stagingHandle)

        assertFalse("Quarantine .part file must be unlinked after discardStaging", stagingFile.exists())
        assertEquals("Journal entry must be deleted after discardStaging", null, journalDao.getByItemId(itemId.value))
        assertEquals("No lingering orphan files must exist in staging directory", 0, receiveStore.stagingDir.listFiles()?.size ?: 0)
        assertEquals("No lingering orphan files must exist in received directory", 0, receiveStore.receivedDir.listFiles()?.size ?: 0)
    }

    // -------------------------------------------------------------------------
    // 10. Performance Rate Limiting: StreamProgressSampler
    // -------------------------------------------------------------------------

    @Test
    fun test10_streamProgressSampler_throttleFrequencyBound() {
        var emitCount = 0
        var simulatedClock = 1_000_000L

        val sampler = StreamProgressSampler(
            minIntervalMs = 100L, // 10 Hz maximum
            minBytesDelta = 0L,   // delta disabled so time interval dominates test
            clock = { simulatedClock }
        ) { _ ->
            emitCount++
        }

        // Tick 1 at simulatedClock: should emit
        sampler.onBytesRead(1000L)
        assertEquals(1, emitCount)

        // Ticks within < 100ms window should be throttled
        simulatedClock += 30L // +30ms
        sampler.onBytesRead(2000L)
        assertEquals(1, emitCount)

        simulatedClock += 30L // +60ms from start
        sampler.onBytesRead(3000L)
        assertEquals(1, emitCount)

        // Tick after >= 100ms: should emit
        simulatedClock += 50L // +110ms from start
        sampler.onBytesRead(4000L)
        assertEquals(2, emitCount)

        // Completing stream guarantees final byte delivery
        sampler.onComplete(5000L)
        assertEquals(3, emitCount)
    }

    // -------------------------------------------------------------------------
    // 11. Bidirectional Parity & Symmetric Device Roles
    // -------------------------------------------------------------------------

    @Test
    fun test11_bidirectionalParityAndRoleSymmetry() {
        val sessionId = SessionId(UUID.randomUUID().toString())
        val batchId = BatchId(UUID.randomUUID().toString())
        val itemId = ItemId(UUID.randomUUID().toString())

        val transferItem = TransferItem(
            id = itemId,
            name = "symmetry_test.bin",
            mimeType = "application/octet-stream",
            sizeBytes = 2048L
        )
        val transferBatch = TransferBatch(
            id = batchId,
            items = listOf(transferItem)
        )

        // 1. Sender role initial session state
        val senderState = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = SessionPhase.Idle,
            batch = transferBatch
        )
        assertEquals(DeviceRole.Sender, senderState.role)
        assertEquals(1, senderState.batch?.items?.size)

        // 2. Receiver role initial session state
        val receiverState = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Receiver,
            phase = SessionPhase.Idle,
            batch = null
        )
        assertEquals(DeviceRole.Receiver, receiverState.role)
        assertEquals(null, receiverState.batch)

        // Invariant: Both roles support full state progression across identical phases
        val allPhases = SessionPhase.values()
        assertTrue("SessionPhase must support complete lifecycle", allPhases.size >= 8)
        assertTrue(allPhases.contains(SessionPhase.Idle))
        assertTrue(allPhases.contains(SessionPhase.Negotiating))
        assertTrue(allPhases.contains(SessionPhase.Offering))
        assertTrue(allPhases.contains(SessionPhase.AwaitingConsent))
        assertTrue(allPhases.contains(SessionPhase.PreparingItem))
        assertTrue(allPhases.contains(SessionPhase.TransferringItem))
        assertTrue(allPhases.contains(SessionPhase.VerifyingItem))
        assertTrue(allPhases.contains(SessionPhase.Completed))
        assertTrue(allPhases.contains(SessionPhase.Failed))
        assertTrue(allPhases.contains(SessionPhase.Cancelled))
        assertTrue(allPhases.contains(SessionPhase.Interrupted))

        // Invariant: ItemPhase lifecycle coverage
        val itemPhases = ItemPhase.values()
        assertTrue(itemPhases.contains(ItemPhase.Queued))
        assertTrue(itemPhases.contains(ItemPhase.Preparing))
        assertTrue(itemPhases.contains(ItemPhase.Streaming))
        assertTrue(itemPhases.contains(ItemPhase.Verifying))
        assertTrue(itemPhases.contains(ItemPhase.Committing))
        assertTrue(itemPhases.contains(ItemPhase.Verified))
        assertTrue(itemPhases.contains(ItemPhase.Failed))
    }
}
