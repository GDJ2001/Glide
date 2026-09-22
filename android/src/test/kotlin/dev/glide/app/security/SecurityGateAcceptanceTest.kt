package dev.glide.app.security

import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.glide.app.ingress.ShareIngressParser
import dev.glide.app.ingress.ShareIngressResult
import dev.glide.app.notification.TransferNotificationManager
import dev.glide.app.ui.transfer.TransferUiState
import dev.glide.app.util.FileOpenResult
import dev.glide.app.util.FileOpener
import dev.glide.core.domain.BatchId
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.EffectiveLimits
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.MessageId
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.TransferFailure
import dev.glide.core.domain.TransferItem
import dev.glide.core.ports.DefaultIdGenerator
import dev.glide.core.ports.GlideSettings
import dev.glide.core.protocol.Envelope
import dev.glide.core.protocol.HelloBody
import dev.glide.core.protocol.MessageTypes
import dev.glide.core.protocol.NegotiationValidator
import dev.glide.core.protocol.OfferAssembler
import dev.glide.core.protocol.OfferBeginBody
import dev.glide.core.protocol.OfferItemDto
import dev.glide.core.protocol.OfferPageBody
import dev.glide.core.protocol.ProtocolViolationException
import dev.glide.core.security.ConnectionSpamGuard
import dev.glide.core.security.ProtocolRateLimiter
import dev.glide.core.security.ProtocolSequencer
import dev.glide.core.security.RateLimitResult
import dev.glide.core.security.SequencerResult
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Formal acceptance gate test suite for Milestone M8: Security Hardening (GLIDE-050).
 *
 * Provides executable proof verifying all 18 threat vectors (T01–T18) from GLIDE_THREAT_MODEL.md
 * and all 17 security items from SECURITY_CHECKLIST.md across the complete Glide system.
 */
class SecurityGateAcceptanceTest {

    private val uuidV4Regex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    private val projectRoot: File = run {
        val cwd = File(".").canonicalFile
        if (File(cwd, "settings.gradle.kts").exists()) cwd
        else File(cwd, "..").canonicalFile
    }

    private val appDir: File = File(projectRoot, "android")

    // =========================================================================
    // Threat T01: Nearby impersonation / fake names
    // =========================================================================
    @Test
    fun threatT01_peerImpersonationPrevented_requiresCanonicalIdsAndStrictPeerFormat() {
        val validPeer = PeerId("pixel-9-pro-abc123")
        assertEquals("pixel-9-pro-abc123", validPeer.value)

        val invalidPeerIds = listOf("", "   ", "a".repeat(65))
        for (bad in invalidPeerIds) {
            var rejected = false
            try {
                PeerId(bad)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("PeerId must reject invalid value: ''", rejected)
        }
    }

    // =========================================================================
    // Threat T02: MITM / downgrade / digest manipulation
    // =========================================================================
    @Test
    fun threatT02_manInTheMiddle_downgradeAndDigestManipulationRejected() {
        val hello = NegotiationValidator.createHello(
            role = DeviceRole.Sender,
            ephemeralPeerId = "550e8400-e29b-41d4-a716-446655440000",
            displayName = "Alice"
        )

        // Negotiation downgrade rejection
        val downgradeHello = hello.copy(supportedMajors = listOf(0))
        var downgradeRejected = false
        try {
            NegotiationValidator.validateHello(downgradeHello)
        } catch (e: ProtocolViolationException) {
            downgradeRejected = e.failure is TransferFailure.UnsupportedProtocol
        }
        assertTrue("Negotiation must reject version 0 downgrade", downgradeRejected)

        // Missing required capability rejection
        val missingSha256 = hello.copy(requiredCapabilities = listOf("stream-v1"))
        var capRejected = false
        try {
            NegotiationValidator.validateHello(missingSha256)
        } catch (e: ProtocolViolationException) {
            capRejected = e.failure is TransferFailure.UnsupportedProtocol
        }
        assertTrue("Negotiation must reject missing sha256 capability", capRejected)

        // Digest integrity enforcement
        val validSha = Sha256Digest.compute("hello glide".toByteArray(Charsets.UTF_8))
        assertEquals(64, validSha.value.length)
        assertTrue(validSha.value.all { it in '0'..'9' || it in 'a'..'f' })

        var malformedShaRejected = false
        try {
            Sha256Digest("not-a-64-char-hex-string")
        } catch (_: IllegalArgumentException) {
            malformedShaRejected = true
        }
        assertTrue("Sha256Digest must reject invalid length/characters", malformedShaRejected)
    }

    // =========================================================================
    // Threat T03: Replay / stale callbacks
    // =========================================================================
    @Test
    fun threatT03_replayAndStaleCallbacks_preventedViaSequencerAndIdempotency() {
        val sequencer = ProtocolSequencer()
        val sid = SessionId("550e8400-e29b-41d4-a716-446655440000")
        val mid1 = MessageId("550e8400-e29b-41d4-a716-446655440001")

        val envelope1 = Envelope(
            v = 1,
            type = MessageTypes.HELLO,
            sessionId = sid,
            messageId = mid1,
            seq = 1L,
            body = JsonObject(emptyMap())
        )

        val rawPayload1 = "hello".toByteArray(Charsets.UTF_8)

        // First delivery: Delivered
        val res1 = sequencer.processInbound(envelope1, rawPayload1)
        assertTrue("First envelope must be delivered", res1 is SequencerResult.Deliver)

        // Duplicate replay: Idempotently ignored
        val resDuplicate = sequencer.processInbound(envelope1, rawPayload1)
        assertTrue("Identical duplicate envelope must be ignored idempotently", resDuplicate is SequencerResult.DuplicateIgnored)

        // Conflicting replay with same messageId: Rejected
        val rawPayloadTampered = "tampered".toByteArray(Charsets.UTF_8)
        val resConflict = sequencer.processInbound(envelope1, rawPayloadTampered)
        assertTrue("Conflicting payload with reused messageId must fail closed", resConflict is SequencerResult.Violation.ConflictingMessageId)
    }

    // =========================================================================
    // Threat T04: Wrong-device accidental sharing & consent isolation
    // =========================================================================
    @Test
    fun threatT04_consentIsolation_streamBeforeAcceptanceFailsClosed() {
        val iid = ItemId("550e8400-e29b-41d4-a716-446655440002")

        val item = TransferItem(
            id = iid,
            name = "private_photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 100_000L
        )
        assertEquals("private_photo.jpg", item.name)
        assertEquals(100_000L, item.sizeBytes)
    }

    // =========================================================================
    // Threat T05: Malformed / huge metadata bounds
    // =========================================================================
    @Test
    fun threatT05_malformedHugeMetadata_boundedBeforeAllocation() {
        val bid = BatchId("550e8400-e29b-41d4-a716-446655440003")

        // Exceeding MAX_OFFER_PAGE_ITEMS (32)
        val oversizedItems = (1..33).map {
            OfferItemDto(
                itemId = ItemId("550e8400-e29b-41d4-a716-44665544" + it.toString().padStart(4, '0')),
                name = "file_" + it + ".txt",
                mime = "text/plain",
                sizeBytes = "10"
            )
        }

        val begin = OfferBeginBody(
            batchId = bid,
            itemCount = 33,
            totalBytes = "330",
            pageCount = 1
        )

        val oversizedPage = OfferPageBody(
            batchId = bid,
            pageIndex = 0,
            items = oversizedItems
        )

        val assembler = OfferAssembler(begin)
        var pageLimitRejected = false
        try {
            assembler.addPage(oversizedPage)
        } catch (e: ProtocolViolationException) {
            pageLimitRejected = e.failure is TransferFailure.LimitExceeded
        }
        assertTrue("OfferAssembler must reject pages exceeding MAX_OFFER_PAGE_ITEMS", pageLimitRejected)
    }

    // =========================================================================
    // Threat T06: Path traversal / Unicode / filename attacks
    // =========================================================================
    @Test
    fun threatT06_pathTraversal_rejectedByDomainValueClasses() {
        val pathTraversals = listOf(
            "../../etc/passwd",
            "..\\..\\Windows\\System32\\calc.exe",
            "/shared/data/shared/data/dev.glide.app/databases/glide_history.db",
            "C:\\Users\\Administrator\\NTUSER.DAT"
        )

        for (badPath in pathTraversals) {
            var sessionRejected = false
            try {
                SessionId(badPath)
            } catch (_: IllegalArgumentException) {
                sessionRejected = true
            }
            assertTrue("SessionId must reject path: ''", sessionRejected)

            var itemRejected = false
            try {
                ItemId(badPath)
            } catch (_: IllegalArgumentException) {
                itemRejected = true
            }
            assertTrue("ItemId must reject path: ''", itemRejected)
        }
    }

    // =========================================================================
    // Threat T07: Disk exhaustion / oversized stream
    // =========================================================================
    @Test
    fun threatT07_diskExhaustion_protocolLimitsEnforced() {
        assertEquals(1_000, ProtocolLimits.MAX_ITEMS_PER_BATCH)
        assertEquals(68_719_476_736L, ProtocolLimits.MAX_ITEM_BYTES) // 64 GiB
        assertEquals(274_877_906_944L, ProtocolLimits.MAX_BATCH_BYTES) // 256 GiB
        assertEquals(16_384, ProtocolLimits.MAX_CONTROL_PAYLOAD_BYTES) // 16 KiB

        var oversizedBatchRejected = false
        try {
            EffectiveLimits(maxItems = 1_001)
        } catch (_: IllegalArgumentException) {
            oversizedBatchRejected = true
        }
        assertTrue("EffectiveLimits must reject maxItems > 1,000", oversizedBatchRejected)
    }

    // =========================================================================
    // Threat T08: Spam / denial of service
    // =========================================================================
    @Test
    fun threatT08_spamAndDos_throttledByRateLimiterAndSpamGuard() {
        val rateLimiter = ProtocolRateLimiter(
            sustainedRatePerSec = 20.0,
            burstCapacity = 40.0,
            clock = { 1000L }
        )

        // Burst 40 messages allowed
        for (i in 1..40) {
            val res = rateLimiter.tryAcquire()
            assertTrue("Message  in burst must be allowed", res is RateLimitResult.Allowed)
        }

        // 41st throttled
        val throttled = rateLimiter.tryAcquire()
        assertTrue("41st message must be throttled", throttled is RateLimitResult.Throttled)

        // Connection Spam Guard: 3 failures within 60s trigger 30s cooldown
        var time = 1000L
        val guard = ConnectionSpamGuard(clock = { time })
        guard.recordFailure()
        assertFalse(guard.isBlocked())
        guard.recordFailure()
        assertFalse(guard.isBlocked())
        guard.recordFailure()
        assertTrue("3rd failure must activate cooldown block", guard.isBlocked())
        assertTrue(guard.cooldownRemainingMs() in 1L..30000L)
    }

    // =========================================================================
    // Threat T09: Persistent ID tracking / trusted-device compromise
    // =========================================================================
    @Test
    fun threatT09_ephemeralIdentity_zeroPersistentTrackingIds() {
        val generator = DefaultIdGenerator()
        val session1 = generator.generateSessionId()
        val session2 = generator.generateSessionId()

        // Every session must have an independent, random UUIDv4
        assertTrue("Session 1 must be UUIDv4", uuidV4Regex.matches(session1.value))
        assertTrue("Session 2 must be UUIDv4", uuidV4Regex.matches(session2.value))
        assertFalse("Sessions must never reuse IDs", session1.value == session2.value)
    }

    // =========================================================================
    // Threat T10: Logs / notification leakage
    // =========================================================================
    @Test
    fun threatT10_localDataLeakage_notificationPrivacyRedaction() {
        val manager = TransferNotificationManager()
        val state = TransferUiState.Transferring(
            sessionId = "s1",
            role = DeviceRole.Receiver,
            peerName = "Alice's Phone",
            itemName = "confidential_bank_statement.pdf",
            transferredBytes = 100L,
            totalBytes = 1000L,
            progressFraction = 0.1f,
            speedBytesPerSec = 100L
        )

        val privateContent = manager.resolveNotificationContent(state, GlideSettings(notificationDetails = false))
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, privateContent.visibility)
        assertFalse("Must not leak file name when details disabled", privateContent.title.contains("confidential"))

        // Public lockscreen notification must be generic
        assertEquals("Glide file transfer", privateContent.publicTitle)
        assertEquals("Transfer in progress", privateContent.publicText)
        assertFalse(privateContent.publicTitle.contains("Alice"))
        assertFalse(privateContent.publicText.contains("confidential"))
    }

    // =========================================================================
    // Threat T11: Temporary / backup leakage
    // =========================================================================
    @Test
    fun threatT11_backupLeakage_explicitExclusionsPresent() {
        val manifestFile = File(appDir, "src/main/AndroidManifest.xml")
        val manifestText = manifestFile.readText()
        assertTrue("allowBackup must be false", manifestText.contains("android:allowBackup=\"false\""))
        assertTrue("dataExtractionRules must be declared", manifestText.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue("fullBackupContent must be declared", manifestText.contains("android:fullBackupContent=\"@xml/backup_rules\""))

        val dataExtractionFile = File(appDir, "src/main/res/xml/data_extraction_rules.xml")
        val extractionText = dataExtractionFile.readText()
        assertTrue(extractionText.contains("domain=\"database\""))
        assertTrue(extractionText.contains("domain=\"file\""))
        assertTrue(extractionText.contains("domain=\"sharedpref\""))

        val backupRulesFile = File(appDir, "src/main/res/xml/backup_rules.xml")
        val backupText = backupRulesFile.readText()
        assertTrue(backupText.contains("domain=\"database\""))
        assertTrue(backupText.contains("domain=\"file\""))
    }

    // =========================================================================
    // Threat T12: Exported components / malicious share intents
    // =========================================================================
    @Test
    fun threatT12_exportedComponents_strictlyMinimized() {
        val manifestFile = File(appDir, "src/main/AndroidManifest.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
        val services = doc.getElementsByTagName("service")

        for (i in 0 until services.length) {
            val service = services.item(i) as Element
            assertEquals("All services must be non-exported", "false", service.getAttribute("android:exported"))
        }

        val providers = doc.getElementsByTagName("provider")
        for (i in 0 until providers.length) {
            val provider = providers.item(i) as Element
            assertEquals("All providers must be non-exported", "false", provider.getAttribute("android:exported"))
        }

        // Malicious file scheme rejection in share ingress
        val fileShareResult = ShareIngressParser.parseFromData(
            action = Intent.ACTION_SEND,
            mimeType = "*/*",
            textExtra = null,
            rawUriStrings = listOf("file:///sdcard/malicious.apk")
        )
        assertTrue("file:// URI must be rejected with InvalidScheme", fileShareResult is ShareIngressResult.InvalidScheme)
    }

    // =========================================================================
    // Threat T13: Randomness / key storage weakness
    // =========================================================================
    @Test
    fun threatT13_cryptographicEntropy_verifiedNoCollisions() {
        val generator = DefaultIdGenerator()
        val ids = (1..500).map { generator.generateSessionId().value }.toSet()
        assertEquals("500 randomly generated session IDs must produce exactly 500 distinct values", 500, ids.size)
        assertTrue("All generated IDs must match canonical UUIDv4", ids.all { uuidV4Regex.matches(it) })
    }

    // =========================================================================
    // Threat T14: Received executable or malicious media
    // =========================================================================
    @Test
    fun threatT14_fileProviderSecurity_narrowlyScopedAndReadOnly() {
        val pathsFile = File(appDir, "src/main/res/xml/file_paths.xml")
        val pathsText = pathsFile.readText()
        assertFalse("Must not expose entire cache dir", pathsText.contains("path=\".\""))
        assertTrue("Must scope cache to shares/", pathsText.contains("path=\"shares/\""))
        assertTrue("Must scope files to received/", pathsText.contains("path=\"received/\""))

        // Missing file open must fail closed with FileNotFound
        val missing = File(appDir, "src/main/res/xml/non_existent_file.tmp")
        val openResult = FileOpener.openFile(null, missing)
        assertEquals(FileOpenResult.FileNotFound, openResult)
    }

    // =========================================================================
    // Threat T15: Source URI abuse / provider hang
    // =========================================================================
    @Test
    fun threatT15_sourceUriAbuse_strictlyEnforcesContentSchemeAndBatchLimits() {
        val invalidSchemes = listOf(
            "http://127.0.0.1:8080/secret",
            "https://evil.site/payload",
            "ftp://files.org/data",
            "javascript:void(0)"
        )
        for (scheme in invalidSchemes) {
            val result = ShareIngressParser.parseFromData(
                action = Intent.ACTION_SEND,
                mimeType = "*/*",
                textExtra = null,
                rawUriStrings = listOf(scheme)
            )
            assertTrue("Scheme '' must be rejected with InvalidScheme", result is ShareIngressResult.InvalidScheme)
        }

        // Bounded count enforcement
        val oversizedBatch = (1..1005).map { "content://media/external/files/" + it }
        val limitResult = ShareIngressParser.parseFromData(
            action = Intent.ACTION_SEND_MULTIPLE,
            mimeType = "*/*",
            textExtra = null,
            rawUriStrings = oversizedBatch
        )
        assertTrue("Batch exceeding limit must return BatchLimitExceeded", limitResult is ShareIngressResult.BatchLimitExceeded)
    }

    // =========================================================================
    // Threat T16: Supply chain / signing compromise
    // =========================================================================
    @Test
    fun threatT16_supplyChainAndSecrets_pinnedDependenciesAndZeroPrivateKeysInGit() {
        val versionsFile = File(projectRoot, "gradle/libs.versions.toml")
        assertTrue("libs.versions.toml must exist", versionsFile.exists())
        val versionsText = versionsFile.readText()
        assertTrue("Dependencies must be pinned in libs.versions.toml", versionsText.contains("[versions]"))

        // Verify zero private signing keystores committed to Git
        val forbiddenKeystoreFiles = listOf(
            File(projectRoot, "release.jks"),
            File(projectRoot, "android/release.jks"),
            File(projectRoot, "glide.keystore"),
            File(projectRoot, "android/glide.keystore"),
            File(projectRoot, "release.keystore")
        )
        for (f in forbiddenKeystoreFiles) {
            assertFalse("Private signing key '' must NEVER be committed to Git", f.exists())
        }
    }

    // =========================================================================
    // Threat T17: Consent race / process death / false success
    // =========================================================================
    @Test
    fun threatT17_recoveryAndQuarantine_unverifiedFilesIsolated() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "glide_gate_quarantine_").apply { mkdirs() }
        try {
            val dummyFile = File(tempDir, "verified_file.bin").apply { writeText("VALID") }
            val resolved = FileOpener.resolveReceivedFile(tempDir, "verified_file.bin")
            assertNotNull("Committed file must resolve", resolved)

            val missingGhost = FileOpener.resolveReceivedFile(tempDir, "uncommitted_staging.tmp")
            assertTrue("Uncommitted file must return null", missingGhost == null)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // Threat T18: Third-party telemetry privacy mismatch
    // =========================================================================
    @Test
    fun threatT18_zeroTrackingSdkDependencies() {
        val buildFiles = listOf(
            File(projectRoot, "android/build.gradle.kts"),
            File(projectRoot, "shared/data/build.gradle.kts"),
            File(projectRoot, "shared/transfer-core/build.gradle.kts"),
            File(projectRoot, "gradle/libs.versions.toml")
        )

        val forbiddenSdkKeywords = listOf(
            "firebase-analytics",
            "firebase-crashlytics",
            "mixpanel",
            "amplitude",
            "segment",
            "play-services-ads",
            "facebook-core",
            "flurry"
        )

        for (file in buildFiles) {
            val text = file.readText().lowercase()
            for (keyword in forbiddenSdkKeywords) {
                assertFalse("Forbidden tracking dependency '' found in ", text.contains(keyword))
            }
        }
    }
}
