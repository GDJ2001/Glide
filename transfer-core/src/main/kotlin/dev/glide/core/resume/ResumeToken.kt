package dev.glide.core.resume

/**
 * Cryptographic resume token representing a verified, resumable transfer session.
 *
 * Enforces:
 * 1. Monotonic non-negative verified offset.
 * 2. Offset cannot exceed total file size.
 * 3. Offset must align with chunk boundary.
 * 4. Merkle root hash must be 32 bytes (64 hex characters).
 * 5. Session nonce must be 32 bytes (64 hex characters).
 * 6. Maximum token validity TTL is 24 hours (86,400,000 ms).
 */
data class ResumeToken(
    val sessionId: String,
    val fileId: String,
    val totalSizeBytes: Long,
    val verifiedOffsetBytes: Long,
    val chunkSize: Int,
    val rootHashHex: String,
    val sessionNonceHex: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val signatureHex: String
) {
    init {
        require(sessionId.isNotBlank()) { "Session ID cannot be blank" }
        require(fileId.isNotBlank()) { "File ID cannot be blank" }
        require(totalSizeBytes > 0) { "Total file size must be positive: $totalSizeBytes" }
        require(verifiedOffsetBytes >= 0) { "Verified offset cannot be negative: $verifiedOffsetBytes" }
        require(verifiedOffsetBytes <= totalSizeBytes) { "Verified offset ($verifiedOffsetBytes) cannot exceed total size ($totalSizeBytes)" }
        require(chunkSize > 0) { "Chunk size must be positive: $chunkSize" }
        require(verifiedOffsetBytes % chunkSize == 0L || verifiedOffsetBytes == totalSizeBytes) {
            "Verified offset ($verifiedOffsetBytes) must be aligned with chunk size ($chunkSize) or match total size"
        }
        require(rootHashHex.length == 64) { "Root hash must be a 64-char hex string (32 bytes)" }
        require(sessionNonceHex.length == 64) { "Session nonce must be a 64-char hex string (32 bytes)" }
        require(expiresAtMillis > issuedAtMillis) { "Expiration must be strictly after issuance time" }
        val ttl = expiresAtMillis - issuedAtMillis
        require(ttl <= MAX_TTL_MILLIS) { "Token TTL ($ttl ms) exceeds maximum permitted TTL ($MAX_TTL_MILLIS ms)" }
        require(signatureHex.isNotBlank()) { "Signature cannot be blank" }
    }

    val verifiedChunkCount: Long get() = verifiedOffsetBytes / chunkSize
    val remainingBytes: Long get() = totalSizeBytes - verifiedOffsetBytes
    val isComplete: Boolean get() = verifiedOffsetBytes == totalSizeBytes

    fun isExpired(currentMillis: Long): Boolean = currentMillis >= expiresAtMillis

    companion object {
        const val MAX_TTL_MILLIS = 24L * 60L * 60L * 1000L // 24 hours
        const val DEFAULT_RESUME_CHUNK_SIZE = 1024 * 1024 // 1 MB
    }
}
