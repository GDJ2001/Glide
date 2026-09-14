package dev.glide.core.resume

import java.security.MessageDigest

/**
 * Pure JVM Merkle tree and chunk-digest verification utility for True Resume (GLIDE-070).
 */
class ContentDigestTree(
    val chunkHashes: List<ByteArray>
) {
    init {
        require(chunkHashes.isNotEmpty()) { "Chunk hashes list cannot be empty" }
    }

    val rootHash: ByteArray = computeRoot(chunkHashes)
    val rootHashHex: String = rootHash.joinToString("") { "%02x".format(it) }

    fun verifyChunk(chunkIndex: Int, chunkData: ByteArray): Boolean {
        if (chunkIndex < 0 || chunkIndex >= chunkHashes.size) return false
        val computedHash = sha256(chunkData)
        return computedHash.contentEquals(chunkHashes[chunkIndex])
    }

    companion object {
        fun sha256(data: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(data)
        }

        fun computeRoot(hashes: List<ByteArray>): ByteArray {
            if (hashes.isEmpty()) return ByteArray(32)
            if (hashes.size == 1) return hashes[0]

            var currentLevel = hashes
            while (currentLevel.size > 1) {
                val nextLevel = mutableListOf<ByteArray>()
                for (i in 0 until currentLevel.size step 2) {
                    if (i + 1 < currentLevel.size) {
                        val combined = currentLevel[i] + currentLevel[i + 1]
                        nextLevel.add(sha256(combined))
                    } else {
                        nextLevel.add(currentLevel[i])
                    }
                }
                currentLevel = nextLevel
            }
            return currentLevel[0]
        }
    }
}
