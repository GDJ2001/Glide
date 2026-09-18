package dev.glide.core.resume

/**
 * Platform-neutral bounded source authority prepared before a resume-capable offer.
 *
 * The platform implementation owns how the source is re-opened and hashed. Core only consumes
 * these canonical values and never treats a URI/path by itself as source continuity proof.
 */
data class ResumeSourcePreflight(
    val sourceIdentity: String,
    val totalBytes: Long,
    val chunkSize: Int,
    val chunkRootHashHex: String,
    val wholeFileSha256Hex: String
) {
    init {
        require(
            sourceIdentity.length == 64 &&
                sourceIdentity.all {
                    it in '0'..'9' ||
                        it in 'a'..'f'
                }
        ) {
            "sourceIdentity must be canonical lowercase SHA-256"
        }
        require(totalBytes > 0) {
            "Resumable source must be non-empty"
        }
        require(
            chunkSize in
                1..16 * 1024 * 1024
        ) {
            "Resume chunk size must be in 1..16 MiB"
        }
        require(
            chunkRootHashHex.length == 64 &&
                chunkRootHashHex.all {
                    it in '0'..'9' ||
                        it in 'a'..'f'
                }
        ) {
            "chunkRootHashHex must be canonical lowercase SHA-256"
        }
        require(
            wholeFileSha256Hex.length == 64 &&
                wholeFileSha256Hex.all {
                    it in '0'..'9' ||
                        it in 'a'..'f'
                }
        ) {
            "wholeFileSha256Hex must be canonical lowercase SHA-256"
        }
    }
}
