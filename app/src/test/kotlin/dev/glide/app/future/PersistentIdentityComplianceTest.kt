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
 * Programmatically asserts:
 * 1. ADR-0015 exists, documents Keystore identity & Merkle digest verification, and is indexed in docs/adr/README.md.
 * 2. PERSISTENT_IDENTITY_AND_RESUME_SPECIFICATION.md specifies Android Keystore keys and TOFU pairing.
 * 3. Specification strictly rejects offset-only fake resume and mandates Merkle chunk digests.
 * 4. Retained-partial lifecycle enforces 24-hour expiration TTL and User Data Sanctity.
 * 5. Threat model matrix covers T01 through T08.
 * 6. Pure JVM ResumeToken and ContentDigestTree enforce security and validation boundaries.
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
    fun test2_specification_mandatesKeystoreAndTofuPairing() {
        val root = resolveProjectRoot()
        val specFile = File(root, "docs/future/PERSISTENT_IDENTITY_AND_RESUME_SPECIFICATION.md")
        assertTrue("PERSISTENT_IDENTITY_AND_RESUME_SPECIFICATION.md must exist", specFile.exists())

        val specText = specFile.readText()
        assertTrue("Must require Android Keystore", specText.contains("Android Keystore"))
        assertTrue("Must specify PURPOSE_SIGN", specText.contains("PURPOSE_SIGN"))
        assertTrue("Must specify PURPOSE_VERIFY", specText.contains("PURPOSE_VERIFY"))
        assertTrue("Must document TOFU pairing flow", specText.contains("TOFU") || specText.contains("Trust-On-First-Use"))
    }

    @Test
    fun test3_specification_rejectsOffsetOnlyAndMandatesMerkleDigest() {
        val root = resolveProjectRoot()
        val specFile = File(root, "docs/future/PERSISTENT_IDENTITY_AND_RESUME_SPECIFICATION.md")
        val specText = specFile.readText()

        assertTrue("Must reject offset-only fake resume", specText.contains("offset-only"))
        assertTrue("Must mandate Merkle digest tree", specText.contains("Merkle"))
        assertTrue("Must specify CAP_PERSISTENT_IDENTITY", specText.contains("CAP_PERSISTENT_IDENTITY"))
        assertTrue("Must specify CAP_TRUE_RESUME", specText.contains("CAP_TRUE_RESUME"))
    }

    @Test
    fun test4_retainedPartialLifecycle_enforces24HourTtlAndSanctity() {
        val root = resolveProjectRoot()
        val specFile = File(root, "docs/future/PERSISTENT_IDENTITY_AND_RESUME_SPECIFICATION.md")
        val specText = specFile.readText()

        assertTrue("Must enforce 24-hour TTL", specText.contains("24") && (specText.contains("hour") || specText.contains("hours")))
        assertTrue("Must specify staging isolation in cacheDir", specText.contains("cacheDir/staging/resumable"))
        assertTrue("Must affirm User Data Sanctity", specText.contains("User Data Sanctity"))
    }

    @Test
    fun test5_threatMatrix_coversAllCriticalThreats() {
        val root = resolveProjectRoot()
        val specFile = File(root, "docs/future/PERSISTENT_IDENTITY_AND_RESUME_SPECIFICATION.md")
        val specText = specFile.readText()

        val threats = listOf("T01", "T02", "T03", "T04", "T05", "T06", "T07", "T08")
        for (threat in threats) {
            assertTrue("Spec must analyze threat: $threat", specText.contains("**$threat**"))
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

        // Mutual challenge response
        val challengeNonce = dev.glide.core.identity.PeerIdentityVerifier.generateNonce()
        val bobSignature = dev.glide.core.identity.PeerIdentityVerifier.signNonce(bobKeyPair.private, challengeNonce)

        // Valid verification by Alice
        val verified = dev.glide.core.identity.PeerIdentityVerifier.verifyNonceSignature(
            publicKeyDer = bobKeyPair.public.encoded,
            nonce = challengeNonce,
            signatureBytes = bobSignature
        )
        assertTrue("Legitimate signature must verify successfully", verified)

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
        // Assert capability strings
        assertTrue(
            "Negotiation constants must offer identity-v1",
            dev.glide.core.protocol.NegotiationConstants.OPTIONAL_CAPABILITIES.contains(
                dev.glide.core.protocol.NegotiationConstants.CAPABILITY_PERSISTENT_IDENTITY_V1
            )
        )
        assertTrue(
            "Negotiation constants must offer resume-v1",
            dev.glide.core.protocol.NegotiationConstants.OPTIONAL_CAPABILITIES.contains(
                dev.glide.core.protocol.NegotiationConstants.CAPABILITY_TRUE_RESUME_V1
            )
        )

        // IDENTITY_CHALLENGE roundtrip
        val body = buildJsonObject {
            put("nonceHex", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        }
        val envelope = dev.glide.core.protocol.Envelope(
            v = 1,
            type = dev.glide.core.protocol.MessageTypes.IDENTITY_CHALLENGE,
            sessionId = dev.glide.core.domain.SessionId("11111111-1111-4111-8111-111111111111"),
            messageId = dev.glide.core.domain.MessageId("22222222-2222-4222-8222-222222222222"),
            seq = 1L,
            body = body
        )
        val encoded = dev.glide.core.protocol.ProtocolCodec.encode(envelope)
        val decoded = dev.glide.core.protocol.ProtocolCodec.decode(encoded)
        assertEquals(dev.glide.core.protocol.MessageTypes.IDENTITY_CHALLENGE, decoded.type)
        val challengeDto: dev.glide.core.protocol.IdentityChallengeDto = dev.glide.core.protocol.ProtocolCodec.decodeBody(decoded)
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", challengeDto.nonceHex)
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
            val hash0 = dev.glide.core.resume.ContentDigestTree.sha256(chunk0)
            val hash1 = dev.glide.core.resume.ContentDigestTree.sha256(chunk1)
            val digestTree = dev.glide.core.resume.ContentDigestTree(listOf(hash0, hash1))

            store.initSession(sessionId, fileId, "test.bin", (chunk0.size + chunk1.size).toLong(), chunk0.size, "dummyRoot")
            assertTrue("Chunk 0 must write", store.writeAndVerifyChunk(sessionId, 0, chunk0, digestTree))
            assertTrue("Chunk 1 must write", store.writeAndVerifyChunk(sessionId, 1, chunk1, digestTree))

            val corruptedChunk = "Tampered".toByteArray(Charsets.UTF_8)
            assertFalse("Corrupted chunk must be rejected", store.writeAndVerifyChunk(sessionId, 0, corruptedChunk, digestTree))

            val destinationFile = File(tempDir, "assembled.bin")
            val assembled = store.assembleVerifiedFile(sessionId, destinationFile)
            assertTrue("Assembled file must succeed", assembled)
            assertEquals((chunk0.size + chunk1.size).toLong(), destinationFile.length())

            store.purgeSession(sessionId)
            assertFalse(File(tempDir, sessionId).exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun test10_settingsUi_trustedDevicesSectionAndDialogEnforcement() {
        val root = resolveProjectRoot()
        val settingsScreenFile = File(root, "app/src/main/kotlin/dev/glide/app/ui/settings/SettingsScreen.kt")
        assertTrue("SettingsScreen.kt must exist", settingsScreenFile.exists())

        val screenContent = settingsScreenFile.readText()
        assertTrue("SettingsScreen must render TrustedDevicesSection", screenContent.contains("TrustedDevicesSection"))
        assertTrue("SettingsScreen must render TrustedDevicesDialog", screenContent.contains("TrustedDevicesDialog"))
        assertTrue("SettingsScreen must allow revoking peer trust", screenContent.contains("onRevokePeer"))

        val uiState = dev.glide.app.ui.settings.SettingsUiState()
        assertFalse("Trusted devices dialog must be closed by default", uiState.isTrustedDevicesDialogOpen)
        assertTrue("Trusted peers list must default to empty", uiState.trustedPeers.isEmpty())

        // Ensure TrustedPeerDao is exposed on GlideDatabase
        val dbClass = dev.glide.data.db.GlideDatabase::class.java
        val method = dbClass.methods.find { it.name == "trustedPeerDao" }
        assertNotNull("GlideDatabase must provide trustedPeerDao()", method)
    }
}

private class ComplianceResumableSessionDao : dev.glide.data.db.ResumableSessionDao {
    private val sessions = mutableMapOf<String, dev.glide.data.db.ResumableSessionEntity>()

    override suspend fun insertOrUpdate(session: dev.glide.data.db.ResumableSessionEntity) {
        sessions[session.sessionId] = session
    }

    override suspend fun getBySessionId(sessionId: String): dev.glide.data.db.ResumableSessionEntity? {
        return sessions[sessionId]
    }

    override suspend fun getAllResumableSessions(): List<dev.glide.data.db.ResumableSessionEntity> {
        return sessions.values.toList()
    }

    override suspend fun updateVerifiedChunks(sessionId: String, verifiedChunks: Long) {
        sessions[sessionId]?.let {
            sessions[sessionId] = it.copy(verifiedChunks = verifiedChunks)
        }
    }

    override suspend fun deleteBySessionId(sessionId: String) {
        sessions.remove(sessionId)
    }

    override suspend fun deleteExpired(currentMs: Long): Int {
        val expired = sessions.values.filter { it.expiresAtMs <= currentMs }
        expired.forEach { sessions.remove(it.sessionId) }
        return expired.size
    }

    override suspend fun getExpiredSessions(currentMs: Long): List<dev.glide.data.db.ResumableSessionEntity> {
        return sessions.values.filter { it.expiresAtMs <= currentMs }
    }

    override suspend fun getSessionCount(): Int = sessions.size
}

