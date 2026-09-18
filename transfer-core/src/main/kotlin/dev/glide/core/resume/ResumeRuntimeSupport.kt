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


/**
 * Explicit byte-level reconnect mode. Ordinary retry/reattachment never creates this plan.
 */
sealed interface ResumeReconnectPlan {
    val originalSessionId:
        dev.glide.core.domain.SessionId
    val itemId:
        dev.glide.core.domain.ItemId
    val expectedPeerFingerprintHex: String

    data class Sender(
        override val originalSessionId:
            dev.glide.core.domain.SessionId,
        override val itemId:
            dev.glide.core.domain.ItemId,
        override val expectedPeerFingerprintHex:
            String
    ) : ResumeReconnectPlan

    data class Receiver(
        override val originalSessionId:
            dev.glide.core.domain.SessionId,
        override val itemId:
            dev.glide.core.domain.ItemId,
        override val expectedPeerFingerprintHex:
            String
    ) : ResumeReconnectPlan
}

data class ResumeSenderReadySource(
    val authority:
        ResumeRequestAuthority
            .SenderSourceAuthority,
    val sourceHandle:
        dev.glide.core.domain.SourceHandle,
    val wholeFileSha256Hex: String
) {
    init {
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

sealed interface ResumeAuthorityLookup<
    out T
> {
    data class Ready<T>(
        val value: T
    ) : ResumeAuthorityLookup<T>

    data class Rejected(
        val code: String
    ) : ResumeAuthorityLookup<Nothing> {
        init {
            require(
                code in
                    dev.glide.core.protocol
                        .ResumeRejectCodes.ALL
            ) {
                "Unsupported resume rejection code"
            }
        }
    }
}

sealed interface ResumeReconnectState {
    data object Idle :
        ResumeReconnectState

    data class AwaitingResponse(
        val requestMessageId: String,
        val originalSessionId:
            dev.glide.core.domain.SessionId,
        val itemId:
            dev.glide.core.domain.ItemId,
        val requestedChunkIndex: Long,
        val requestedByteOffset: Long
    ) : ResumeReconnectState

    data class ReceiverAccepted(
        val requestMessageId: String,
        val originalSessionId:
            dev.glide.core.domain.SessionId,
        val itemId:
            dev.glide.core.domain.ItemId,
        val startChunkIndex: Long,
        val startByteOffset: Long,
        val chunkSize: Int,
        val totalSizeBytes: Long,
        val chunkRootHashHex: String
    ) : ResumeReconnectState

    data class SenderAccepted(
        val requestMessageId: String,
        val originalSessionId:
            dev.glide.core.domain.SessionId,
        val itemId:
            dev.glide.core.domain.ItemId,
        val startChunkIndex: Long,
        val startByteOffset: Long,
        val chunkSize: Int,
        val totalSizeBytes: Long,
        val chunkRootHashHex: String,
        val sourceHandle:
            dev.glide.core.domain.SourceHandle,
        val wholeFileSha256Hex: String
    ) : ResumeReconnectState

    data class Verified(
        val originalSessionId:
            dev.glide.core.domain.SessionId,
        val itemId:
            dev.glide.core.domain.ItemId,
        val sizeBytes: Long,
        val sha256Hex: String
    ) : ResumeReconnectState

    data class Rejected(
        val code: String
    ) : ResumeReconnectState

    data class Failed(
        val code: String
    ) : ResumeReconnectState
}
