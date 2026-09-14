package dev.glide.app.future

import dev.glide.core.resume.ContentDigestTree
import dev.glide.core.resume.ResumeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
}
