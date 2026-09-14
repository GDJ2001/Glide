package dev.glide.core.resume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GLIDE-070: Pure JVM unit tests verifying ResumeToken and ContentDigestTree security invariants.
 */
class ResumeSecurityTest {

    private val validRootHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val validNonce = "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0"
    private val now = System.currentTimeMillis()

    @Test
    fun validResumeToken_constructsSuccessfully() {
        val token = ResumeToken(
            sessionId = "session-123",
            fileId = "file-abc",
            totalSizeBytes = 10L * 1024 * 1024, // 10 MB
            verifiedOffsetBytes = 4L * 1024 * 1024, // 4 MB
            chunkSize = 1024 * 1024, // 1 MB
            rootHashHex = validRootHash,
            sessionNonceHex = validNonce,
            issuedAtMillis = now,
            expiresAtMillis = now + 3600_000L, // 1 hour
            signatureHex = "valid-sig"
        )

        assertEquals(4L, token.verifiedChunkCount)
        assertEquals(6L * 1024 * 1024, token.remainingBytes)
        assertFalse("Token should not be expired", token.isExpired(now + 1000L))
        assertFalse("Token is not complete", token.isComplete)
    }

    @Test
    fun expiredResumeToken_isDetected() {
        val token = ResumeToken(
            sessionId = "session-123",
            fileId = "file-abc",
            totalSizeBytes = 1024 * 1024,
            verifiedOffsetBytes = 0,
            chunkSize = 1024 * 1024,
            rootHashHex = validRootHash,
            sessionNonceHex = validNonce,
            issuedAtMillis = now - 7200_000L,
            expiresAtMillis = now - 3600_000L,
            signatureHex = "valid-sig"
        )

        assertTrue("Token should be reported as expired", token.isExpired(now))
    }

    @Test(expected = IllegalArgumentException::class)
    fun ttlExceeding24Hours_isRejected() {
        ResumeToken(
            sessionId = "session-123",
            fileId = "file-abc",
            totalSizeBytes = 1024 * 1024,
            verifiedOffsetBytes = 0,
            chunkSize = 1024 * 1024,
            rootHashHex = validRootHash,
            sessionNonceHex = validNonce,
            issuedAtMillis = now,
            expiresAtMillis = now + 25L * 3600_000L, // 25 hours (> 24h limit)
            signatureHex = "valid-sig"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun offsetExceedingTotalSize_isRejected() {
        ResumeToken(
            sessionId = "session-123",
            fileId = "file-abc",
            totalSizeBytes = 1024 * 1024,
            verifiedOffsetBytes = 2 * 1024 * 1024, // Exceeds total
            chunkSize = 1024 * 1024,
            rootHashHex = validRootHash,
            sessionNonceHex = validNonce,
            issuedAtMillis = now,
            expiresAtMillis = now + 3600_000L,
            signatureHex = "valid-sig"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun unalignedOffset_isRejected() {
        ResumeToken(
            sessionId = "session-123",
            fileId = "file-abc",
            totalSizeBytes = 10 * 1024 * 1024,
            verifiedOffsetBytes = 123456L, // Not aligned to 1 MB chunk size
            chunkSize = 1024 * 1024,
            rootHashHex = validRootHash,
            sessionNonceHex = validNonce,
            issuedAtMillis = now,
            expiresAtMillis = now + 3600_000L,
            signatureHex = "valid-sig"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidHashOrNonceLength_isRejected() {
        ResumeToken(
            sessionId = "session-123",
            fileId = "file-abc",
            totalSizeBytes = 1024 * 1024,
            verifiedOffsetBytes = 0,
            chunkSize = 1024 * 1024,
            rootHashHex = "too-short", // Must be 64 chars
            sessionNonceHex = validNonce,
            issuedAtMillis = now,
            expiresAtMillis = now + 3600_000L,
            signatureHex = "valid-sig"
        )
    }

    @Test
    fun contentDigestTree_verifiesChunksAndRootHash() {
        val chunk0 = "First chunk of data".toByteArray(Charsets.UTF_8)
        val chunk1 = "Second chunk of data".toByteArray(Charsets.UTF_8)
        val chunk2 = "Third chunk of data".toByteArray(Charsets.UTF_8)

        val hash0 = ContentDigestTree.sha256(chunk0)
        val hash1 = ContentDigestTree.sha256(chunk1)
        val hash2 = ContentDigestTree.sha256(chunk2)

        val tree = ContentDigestTree(listOf(hash0, hash1, hash2))
        assertEquals(64, tree.rootHashHex.length)

        assertTrue("Chunk 0 should verify", tree.verifyChunk(0, chunk0))
        assertTrue("Chunk 1 should verify", tree.verifyChunk(1, chunk1))
        assertTrue("Chunk 2 should verify", tree.verifyChunk(2, chunk2))

        val corruptedChunk0 = "Corrupted chunk of data".toByteArray(Charsets.UTF_8)
        assertFalse("Corrupted chunk should fail verification", tree.verifyChunk(0, corruptedChunk0))
        assertFalse("Out of bounds chunk should fail", tree.verifyChunk(3, chunk0))
    }
}
