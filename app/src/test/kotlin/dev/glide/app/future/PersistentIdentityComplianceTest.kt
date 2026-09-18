package dev.glide.app.future

import dev.glide.core.resume.ContentDigestTree
import dev.glide.core.resume.ResumeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * GLIDE-070: Persistent Identity and True Resume Compliance Verification Suite.
 *
 * Programmatically asserts the HARD-018 revision:
 * 1. ADR-0015 is indexed and no longer claims nonce-only authentication.
 * 2. The specification requires protected P-256 identity keys, explicit first-use enrollment,
 *    canonical transcript signatures and channel binding.
 * 3. identity-v1/resume-v1 are versioned and offset-only resume is prohibited.
 * 4. Retained partials remain private, bounded and subject to a 24-hour maximum TTL.
 * 5. The revised threat invariants cover replay, role/capability/channel binding and source integrity.
 * 6. Existing resume digest primitives retain their validation boundaries.
 */
class PersistentIdentityComplianceTest {

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

    @Test
    fun test1_adr0015_existsAndIsIndexed() {
        val root = resolveProjectRoot()
        val adrFile = File(root, "docs/adr/ADR-0015-persistent-identity-and-true-resume.md")
        assertTrue("ADR-0015 must exist", adrFile.exists())

        val adrText = adrFile.readText()
        assertTrue("ADR-0015 must document Android Keystore", adrText.contains("Android Keystore"))
        assertTrue("ADR-0015 must document Merkle digest verification", adrText.contains("Merkle digest"))
        assertTrue("ADR-0015 must ban offset-only resume", adrText.contains("offset-only"))

        val indexFile = File(root, "docs/adr/README.md")
        assertTrue("docs/adr/README.md must exist", indexFile.exists())
        assertTrue("docs/adr/README.md must index ADR-0015", indexFile.readText().contains("ADR-0015"))
    }

    @Test
    fun test2_specification_requiresBoundPersistentIdentityProofs() {
        val root = resolveProjectRoot()
        val specFile =
            File(
                root,
                "docs/future/PERSISTENT_IDENTITY_AND_RESUME_SPECIFICATION.md"
            )
        assertTrue(
            "PERSISTENT_IDENTITY_AND_RESUME_SPECIFICATION.md must exist",
            specFile.exists()
        )

        val specText = specFile.readText()
        assertTrue(
            "Must require Android Keystore",
            specText.contains("Android Keystore")
        )
        assertTrue(
            "Must specify P-256 ECDSA",
            specText.contains("P-256") &&
                specText.contains("ECDSA")
        )
        assertTrue(
            "Must bind persistent proof to the active channel",
            specText.contains("channel binding", ignoreCase = true) &&
                specText.contains("EXPORTER-GLIDE-IDENTITY-V1")
        )
        assertTrue(
            "Must require explicit first-use trust enrollment",
            specText.contains("Trust/Enroll") &&
                specText.contains("explicit", ignoreCase = true)
        )
        assertTrue(
            "Must prohibit nonce-only authentication",
            specText.contains("nonce", ignoreCase = true) &&
                specText.contains("never sufficient authority")
        )
    }

    @Test
    fun test3_specification_versionsIdentityAndTrueResume() {
        val root = resolveProjectRoot()
        val specText =
            File(
                root,
                "docs/future/PERSISTENT_IDENTITY_AND_RESUME_SPECIFICATION.md"
            ).readText()

        assertTrue(
            "Must reject offset-only fake resume",
            specText.contains("Offset-only resume is prohibited")
        )
        assertTrue(
            "Must define identity-v1",
            specText.contains("identity-v1")
        )
        assertTrue(
            "Must define resume-v1",
            specText.contains("resume-v1")
        )
        assertTrue(
            "resume-v1 must require identity-v1",
            specText.contains(
                "resume-v1 requires identity-v1"
            )
        )
        assertTrue(
            "Must define canonical shared vectors",
            specText.contains(
                "protocol/fixtures/identity_resume_v1_vectors.json"
            )
        )
    }

    @Test
    fun test4_retainedPartialLifecycle_enforces24HourTtlAndSanctity() {
        val root = resolveProjectRoot()
        val specText =
            File(
                root,
                "docs/future/PERSISTENT_IDENTITY_AND_RESUME_SPECIFICATION.md"
            ).readText()

        assertTrue(
            "Must enforce 24-hour maximum TTL",
            specText.contains("Maximum 24 hours")
        )
        assertTrue(
            "Retained bytes must remain in private staging",
            specText.contains(
                "Private application staging only"
            )
        )
        assertTrue(
            "Final public commit must require verification",
            specText.contains(
                "Only after complete-file verification"
            )
        )
        assertTrue(
            "Process death must not reconstruct a live connection",
            specText.contains(
                "No retained journal may reconstruct an active network connection"
            )
        )
    }

    @Test
    fun test5_threatMatrix_coversBoundAuthenticationThreats() {
        val root = resolveProjectRoot()
        val specText =
            File(
                root,
                "docs/future/PERSISTENT_IDENTITY_AND_RESUME_SPECIFICATION.md"
            ).readText()

        val requiredThreats =
            listOf(
                "Replay",
                "Role swap",
                "Capability downgrade",
                "Cross-connection signature reuse",
                "Resume offset tampering",
                "Source substitution",
                "Unauthorized headless receive",
                "Partial-file exposure"
            )
        requiredThreats.forEach { threat ->
            assertTrue(
                "Spec must analyze threat: $threat",
                specText.contains(threat)
            )
        }
    }

    @Test
    fun test6_pureJvmResumeModels_enforceSecurityBounds() {
        val rootHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val nonce = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val now = System.currentTimeMillis()

        // Valid ResumeToken
        val token = ResumeToken(
            sessionId = "s1",
            fileId = "f1",
            totalSizeBytes = 5 * 1024 * 1024,
            verifiedOffsetBytes = 2 * 1024 * 1024,
            chunkSize = 1024 * 1024,
            rootHashHex = rootHash,
            sessionNonceHex = nonce,
            issuedAtMillis = now,
            expiresAtMillis = now + 3600_000,
            signatureHex = "valid-sig"
        )
        assertEquals(2L, token.verifiedChunkCount)
        assertFalse(token.isExpired(now))

        // ContentDigestTree verification
        val chunkData = "Test Chunk Content".toByteArray(Charsets.UTF_8)
        val chunkHash = ContentDigestTree.sha256(chunkData)
        val tree = ContentDigestTree(listOf(chunkHash))
        assertTrue(tree.verifyChunk(0, chunkData))
        assertFalse(tree.verifyChunk(0, "Tampered".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun test7_peerIdentityVerifier_challengeResponseAndMitmDefense() {
        val kpg = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }
        val aliceKeyPair = kpg.generateKeyPair()
        val bobKeyPair = kpg.generateKeyPair()
        val eveKeyPair = kpg.generateKeyPair()

        val aliceFingerprint = dev.glide.core.identity.PeerIdentityVerifier.deriveFingerprint(aliceKeyPair.public.encoded)
        val bobFingerprint = dev.glide.core.identity.PeerIdentityVerifier.deriveFingerprint(bobKeyPair.public.encoded)
        assertFalse(aliceFingerprint == bobFingerprint)

        // Authentication signs the complete HARD-018 transcript, never a bare nonce.
        val challengeNonce =
            dev.glide.core.identity.PeerIdentityVerifier.generateNonce()
                .joinToString("") { "%02x".format(it) }
        val capabilityHash =
            dev.glide.core.protocol.IdentityResumeTranscript
                .capabilityHashHex(
                    listOf(
                        "folder-v1",
                        "identity-v1",
                        "sha256",
                        "stream-v1"
                    )
                )
        val transcript =
            dev.glide.core.protocol.IdentityResumeTranscript
                .identityProofBytes(
                    activeSessionId =
                        "11111111-1111-4111-8111-111111111111",
                    challengeMessageId =
                        "22222222-2222-4222-8222-222222222222",
                    signerRole = "sender",
                    verifierRole = "receiver",
                    signerFingerprintHex = bobFingerprint,
                    verifierFingerprintHex = aliceFingerprint,
                    challengeNonceHex = challengeNonce,
                    channelBindingHex =
                        "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100",
                    capabilityHashHex = capabilityHash
                )
        val bobSignature =
            dev.glide.core.identity.PeerIdentityVerifier
                .signTranscript(
                    bobKeyPair.private,
                    transcript
                )

        val verified =
            dev.glide.core.identity.PeerIdentityVerifier
                .verifyTranscriptSignature(
                    publicKeyDer =
                        bobKeyPair.public.encoded,
                    transcript = transcript,
                    signatureBytes = bobSignature
                )
        assertTrue(
            "Legitimate bound transcript signature must verify successfully",
            verified
        )

        val record = dev.glide.core.domain.TrustedPeerRecord(
            peerFingerprintHex = bobFingerprint,
            publicKeyDerHex = with(dev.glide.core.identity.PeerIdentityVerifier) { bobKeyPair.public.encoded.toHex() },
            displayName = "Bob Phone",
            pairedAtMs = 1000L,
            lastSeenAtMs = 2000L
        )
        val trustStatus = dev.glide.core.identity.PeerIdentityVerifier.verifyPeerTrust(record, bobKeyPair.public.encoded, "Bob Phone")
        assertEquals(dev.glide.core.domain.TrustStatus.TrustedPersistent, trustStatus)

        // MITM Defense: Attacker Eve presents Bob's identity with Eve's key
        try {
            val forgedRecord = record.copy(peerFingerprintHex = dev.glide.core.identity.PeerIdentityVerifier.deriveFingerprint(eveKeyPair.public.encoded))
            dev.glide.core.identity.PeerIdentityVerifier.verifyPeerTrust(
                forgedRecord.copy(publicKeyDerHex = record.publicKeyDerHex),
                eveKeyPair.public.encoded,
                "Bob Phone"
            )
            org.junit.Assert.fail("IdentityMismatchException expected for MITM key mismatch")
        } catch (e: dev.glide.core.identity.IdentityMismatchException) {
            assertTrue("Exception message must cite identity mismatch", e.message?.contains("mismatch") == true)
        }
    }

    @Test
    fun test8_protocolEnvelopes_serializationAndCapabilityNegotiation() {
        // identity-v1/resume-v1 are known extension capabilities but MUST NOT be
        // advertised by default before HARD-019..021 wire trusted-session enforcement.
        assertFalse(
            "Production optional capabilities must not advertise identity-v1 yet",
            dev.glide.core.protocol.NegotiationConstants
                .OPTIONAL_CAPABILITIES
                .contains(
                    dev.glide.core.protocol.NegotiationConstants
                        .CAPABILITY_PERSISTENT_IDENTITY_V1
                )
        )
        assertFalse(
            "Production optional capabilities must not advertise resume-v1 yet",
            dev.glide.core.protocol.NegotiationConstants
                .OPTIONAL_CAPABILITIES
                .contains(
                    dev.glide.core.protocol.NegotiationConstants
                        .CAPABILITY_TRUE_RESUME_V1
                )
        )
        assertTrue(
            dev.glide.core.protocol.NegotiationConstants
                .TRUST_EXTENSION_CAPABILITIES
                .contains(
                    dev.glide.core.protocol.NegotiationConstants
                        .CAPABILITY_PERSISTENT_IDENTITY_V1
                )
        )
        assertTrue(
            dev.glide.core.protocol.NegotiationConstants
                .TRUST_EXTENSION_CAPABILITIES
                .contains(
                    dev.glide.core.protocol.NegotiationConstants
                        .CAPABILITY_TRUE_RESUME_V1
                )
        )

        val capabilityHash =
            dev.glide.core.protocol.IdentityResumeTranscript
                .capabilityHashHex(
                    listOf(
                        "folder-v1",
                        "identity-v1",
                        "sha256",
                        "stream-v1"
                    )
                )

        val body = buildJsonObject {
            put(
                "profile",
                dev.glide.core.protocol.IdentityResumeTranscript
                    .IDENTITY_PROFILE
            )
            put(
                "challengeNonceHex",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            )
            put(
                "verifierFingerprintHex",
                "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd"
            )
            put("verifierRole", "receiver")
            put("signerRole", "sender")
            put(
                "channelBindingHex",
                "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100"
            )
            put("capabilityHashHex", capabilityHash)
        }
        val envelope =
            dev.glide.core.protocol.Envelope(
                v = 1,
                type =
                    dev.glide.core.protocol.MessageTypes
                        .IDENTITY_CHALLENGE,
                sessionId =
                    dev.glide.core.domain.SessionId(
                        "11111111-1111-4111-8111-111111111111"
                    ),
                messageId =
                    dev.glide.core.domain.MessageId(
                        "22222222-2222-4222-8222-222222222222"
                    ),
                seq = 1L,
                body = body
            )
        val encoded =
            dev.glide.core.protocol.ProtocolCodec
                .encode(envelope)
        val decoded =
            dev.glide.core.protocol.ProtocolCodec
                .decode(encoded)
        assertEquals(
            dev.glide.core.protocol.MessageTypes.IDENTITY_CHALLENGE,
            decoded.type
        )
        val challengeDto:
            dev.glide.core.protocol.IdentityChallengeDto =
            dev.glide.core.protocol.ProtocolCodec
                .decodeBody(decoded)
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            challengeDto.challengeNonceHex
        )
    }

    @Test
    fun test9_resumableStagingStore_lifecycleAndIntegrity() = kotlinx.coroutines.runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("glide-staging-compliance").toFile()
        try {
            val dao = ComplianceResumableSessionDao()
            val store = dev.glide.data.store.ResumableStagingStore(tempDir, dao)
            val sessionId = "session-test-compliance"
            val fileId = "file-99"

            val chunk0 = "Chunk zero content".toByteArray(Charsets.UTF_8)
            val chunk1 = "Chunk one content".toByteArray(Charsets.UTF_8)
            val digestTree =
                dev.glide.core.resume.ContentDigestTree(
                    listOf(
                        dev.glide.core.resume.ContentDigestTree.sha256(chunk0),
                        dev.glide.core.resume.ContentDigestTree.sha256(chunk1)
                    )
                )
            val wholeDigest =
                java.security.MessageDigest.getInstance("SHA-256")
                    .apply {
                        update(chunk0)
                        update(chunk1)
                    }
                    .digest()
                    .joinToString("") { "%02x".format(it) }

            store.initSession(
                originalSessionId = sessionId,
                itemId = fileId,
                itemName = "test.bin",
                peerFingerprintHex = "a".repeat(64),
                sourceIdentity = "compliance-source:v1",
                totalBytes = (chunk0.size + chunk1.size).toLong(),
                chunkSize = chunk0.size,
                digestTree = digestTree,
                wholeFileSha256Hex = wholeDigest
            )

            assertFalse(
                "Out-of-order chunk must not skip the contiguous prefix",
                store.writeAndVerifyChunk(
                    sessionId,
                    fileId,
                    1L,
                    chunk1
                )
            )
            assertTrue(
                "Chunk 0 must write",
                store.writeAndVerifyChunk(
                    sessionId,
                    fileId,
                    0L,
                    chunk0
                )
            )
            assertTrue(
                "Chunk 1 must write",
                store.writeAndVerifyChunk(
                    sessionId,
                    fileId,
                    1L,
                    chunk1
                )
            )

            val corruptedChunk = "Tampered".toByteArray(Charsets.UTF_8)
            assertFalse(
                "Already-verified/corrupt duplicate chunk must be rejected",
                store.writeAndVerifyChunk(
                    sessionId,
                    fileId,
                    1L,
                    corruptedChunk
                )
            )

            val destinationFile = File(tempDir, "assembled.bin")
            val assembled =
                store.assembleVerifiedFile(
                    sessionId,
                    fileId,
                    destinationFile
                )
            assertTrue("Assembled file must succeed", assembled)
            assertEquals(
                (chunk0.size + chunk1.size).toLong(),
                destinationFile.length()
            )

            store.purgeSession(
                sessionId,
                fileId
            )
            assertEquals(
                0,
                dao.getSessionCount()
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun test10_androidTrustUi_requiresExplicitEnrollmentAndRuntimeRevocation() {
        val root = resolveProjectRoot()
        val settingsScreenFile =
            File(
                root,
                "app/src/main/kotlin/dev/glide/app/ui/settings/SettingsScreen.kt"
            )
        val transferScreenFile =
            File(
                root,
                "app/src/main/kotlin/dev/glide/app/ui/TransferScreen.kt"
            )
        val runtimeFile =
            File(
                root,
                "app/src/main/kotlin/dev/glide/app/service/TransferRuntime.kt"
            )

        assertTrue(settingsScreenFile.exists())
        assertTrue(transferScreenFile.exists())
        assertTrue(runtimeFile.exists())

        val settingsContent = settingsScreenFile.readText()
        assertTrue(
            "Settings must render trusted devices management",
            settingsContent.contains("TrustedDevicesDialog") &&
                settingsContent.contains("onRevokePeer")
        )
        assertFalse(
            "Hardened trust UI must not claim legacy PIN enrollment",
            settingsContent.contains("mutual PIN", ignoreCase = true)
        )

        val transferContent = transferScreenFile.readText()
        assertTrue(
            "Transfer UI must ask for explicit trust approval",
            transferContent.contains("Trust this device?") &&
                transferContent.contains("Trust device")
        )
        assertTrue(
            "Enrollment UI must state that trust does not auto-accept files",
            transferContent.contains("does not automatically accept future file offers")
        )

        val runtimeContent = runtimeFile.readText()
        assertTrue(
            "Runtime must expose explicit approve/reject trust commands",
            runtimeContent.contains("ApprovePeerEnrollment") &&
                runtimeContent.contains("RejectPeerEnrollment")
        )
        assertTrue(
            "Runtime must expose revocation through the trust manager",
            runtimeContent.contains("revokeTrustedPeerNow")
        )

        val uiState = dev.glide.app.ui.settings.SettingsUiState()
        assertFalse(
            "Trusted devices dialog must be closed by default",
            uiState.isTrustedDevicesDialogOpen
        )
        assertTrue(
            "Trusted peers list must default to empty",
            uiState.trustedPeers.isEmpty()
        )

        val dbClass = dev.glide.data.db.GlideDatabase::class.java
        val method = dbClass.methods.find { it.name == "trustedPeerDao" }
        assertNotNull(
            "GlideDatabase must provide trustedPeerDao()",
            method
        )
    }
}

private class ComplianceResumableSessionDao : dev.glide.data.db.ResumableSessionDao {
    private val sessions =
        mutableMapOf<
            Pair<String, String>,
            dev.glide.data.db.ResumableSessionEntity
        >()

    override suspend fun insertOrUpdate(
        session: dev.glide.data.db.ResumableSessionEntity
    ) {
        sessions[
            session.originalSessionId to
                session.itemId
        ] = session
    }

    override suspend fun get(
        originalSessionId: String,
        itemId: String
    ): dev.glide.data.db.ResumableSessionEntity? =
        sessions[
            originalSessionId to
                itemId
        ]

    override suspend fun getAllResumableSessions():
        List<dev.glide.data.db.ResumableSessionEntity> =
        sessions.values.toList()

    override suspend fun compareAndAdvance(
        originalSessionId: String,
        itemId: String,
        expectedVerifiedChunks: Long,
        expectedContiguousByteOffset: Long,
        newVerifiedChunks: Long,
        newContiguousByteOffset: Long,
        updatedAtMs: Long
    ): Int {
        val key =
            originalSessionId to
                itemId
        val current =
            sessions[key]
                ?: return 0
        if (current.verifiedChunks !=
                expectedVerifiedChunks ||
            current.contiguousByteOffset !=
                expectedContiguousByteOffset
        ) {
            return 0
        }

        sessions[key] =
            current.copy(
                verifiedChunks =
                    newVerifiedChunks,
                contiguousByteOffset =
                    newContiguousByteOffset,
                updatedAtMs =
                    updatedAtMs
            )
        return 1
    }

    override suspend fun rewindProgress(
        originalSessionId: String,
        itemId: String,
        verifiedChunks: Long,
        contiguousByteOffset: Long,
        updatedAtMs: Long
    ): Int {
        val key =
            originalSessionId to
                itemId
        val current =
            sessions[key]
                ?: return 0
        if (current.verifiedChunks <
                verifiedChunks ||
            current.contiguousByteOffset <
                contiguousByteOffset
        ) {
            return 0
        }

        sessions[key] =
            current.copy(
                verifiedChunks =
                    verifiedChunks,
                contiguousByteOffset =
                    contiguousByteOffset,
                updatedAtMs =
                    updatedAtMs
            )
        return 1
    }

    override suspend fun delete(
        originalSessionId: String,
        itemId: String
    ) {
        sessions.remove(
            originalSessionId to
                itemId
        )
    }

    override suspend fun getExpiredSessions(
        currentMs: Long
    ): List<dev.glide.data.db.ResumableSessionEntity> =
        sessions.values.filter {
            it.expiresAtMs <= currentMs
        }

    override suspend fun deleteExpired(
        currentMs: Long
    ): Int {
        val expiredKeys =
            sessions
                .filterValues {
                    it.expiresAtMs <= currentMs
                }
                .keys
                .toList()
        expiredKeys.forEach {
            sessions.remove(it)
        }
        return expiredKeys.size
    }

    override suspend fun getSessionCount(): Int =
        sessions.size
}
