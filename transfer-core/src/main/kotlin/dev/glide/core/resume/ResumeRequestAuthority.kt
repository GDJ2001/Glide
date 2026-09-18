package dev.glide.core.resume

import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SessionId
import dev.glide.core.identity.PeerIdentityVerifier
import dev.glide.core.identity.PersistentTrustManager
import dev.glide.core.ports.AuthenticatedChannelContext
import dev.glide.core.protocol.DecimalByteCount
import dev.glide.core.protocol.IdentityResumeTranscript
import dev.glide.core.protocol.NegotiationConstants
import dev.glide.core.protocol.ResumeAcceptDto
import dev.glide.core.protocol.ResumeRejectCodes
import dev.glide.core.protocol.ResumeRejectDto
import dev.glide.core.protocol.ResumeRequestDto
import dev.glide.core.protocol.isValidUuidV4

/**
 * Pure protocol authority for HARD-022 reconnect resume.
 *
 * This object does not own network sequencing or Android persistence. It converts already
 * revalidated journal/source authority into a channel-bound signed RESUME_REQUEST and verifies
 * that request against the sender's enrolled peer key plus current source authority.
 */
object ResumeRequestAuthority {

    data class ReceiverJournal(
        val originalSessionId: SessionId,
        val itemId: ItemId,
        val verifiedChunkCount: Long,
        val contiguousByteOffset: Long,
        val chunkSize: Int,
        val totalSizeBytes: Long,
        val chunkRootHashHex: String
    )

    data class SenderSourceAuthority(
        val originalSessionId: SessionId,
        val itemId: ItemId,
        val chunkSize: Int,
        val totalSizeBytes: Long,
        val chunkRootHashHex: String
    )

    data class VerifiedRequest(
        val originalSessionId: SessionId,
        val itemId: ItemId,
        val startChunkIndex: Long,
        val startByteOffset: Long,
        val requesterFingerprintHex: String
    )

    class Rejected(
        val code: String,
        message: String
    ) : SecurityException(message) {
        init {
            require(code in ResumeRejectCodes.ALL) {
                "Unsupported resume rejection code: $code"
            }
        }
    }

    suspend fun createRequest(
        activeSessionId: SessionId,
        channelContext: AuthenticatedChannelContext,
        negotiatedCapabilities: Set<String>,
        trustManager: PersistentTrustManager,
        responderFingerprintHex: String,
        journal: ReceiverJournal
    ): ResumeRequestDto {
        requireResumeNegotiated(
            negotiatedCapabilities
        )
        requireActiveChannel(
            activeSessionId,
            channelContext
        )

        val responder =
            trustManager.trustedPeerByFingerprint(
                responderFingerprintHex
            ) ?: throw Rejected(
                ResumeRejectCodes.UNAUTHORIZED,
                "Resume responder is revoked or not enrolled"
            )

        if (responder.peerFingerprintHex !=
            responderFingerprintHex
        ) {
            throw Rejected(
                ResumeRejectCodes.UNAUTHORIZED,
                "Resume responder fingerprint mismatch"
            )
        }

        val localFingerprint =
            trustManager.localFingerprintHex()
        val capabilityHash =
            IdentityResumeTranscript
                .capabilityHashHex(
                    negotiatedCapabilities
                )
        val sessionNonceHex =
            with(PeerIdentityVerifier) {
                generateNonce().toHex()
            }

        val transcript =
            try {
                IdentityResumeTranscript
                    .resumeRequestBytes(
                        activeSessionId =
                            activeSessionId.value,
                        originalSessionId =
                            journal
                                .originalSessionId
                                .value,
                        itemId =
                            journal.itemId.value,
                        requesterRole =
                            "receiver",
                        responderRole =
                            "sender",
                        requesterFingerprintHex =
                            localFingerprint,
                        responderFingerprintHex =
                            responderFingerprintHex,
                        sessionNonceHex =
                            sessionNonceHex,
                        channelBindingHex =
                            channelContext
                                .channelBindingHex,
                        capabilityHashHex =
                            capabilityHash,
                        chunkSize =
                            journal.chunkSize,
                        totalSizeBytes =
                            journal.totalSizeBytes,
                        verifiedChunkCount =
                            journal
                                .verifiedChunkCount,
                        contiguousByteOffset =
                            journal
                                .contiguousByteOffset,
                        chunkRootHashHex =
                            journal
                                .chunkRootHashHex
                    )
            } catch (error: IllegalArgumentException) {
                throw Rejected(
                    ResumeRejectCodes.INVALID_OFFSET,
                    error.message
                        ?: "Invalid resume journal"
                )
            }

        val signatureHex =
            with(PeerIdentityVerifier) {
                trustManager
                    .signTranscript(transcript)
                    .toHex()
            }

        return ResumeRequestDto(
            profile =
                IdentityResumeTranscript
                    .RESUME_PROFILE,
            originalSessionId =
                journal
                    .originalSessionId
                    .value,
            itemId =
                journal.itemId.value,
            requesterFingerprintHex =
                localFingerprint,
            responderFingerprintHex =
                responderFingerprintHex,
            requesterRole = "receiver",
            responderRole = "sender",
            verifiedChunkCount =
                journal.verifiedChunkCount,
            contiguousByteOffset =
                DecimalByteCount.serialize(
                    journal
                        .contiguousByteOffset
                ),
            chunkSize =
                journal.chunkSize,
            totalSizeBytes =
                DecimalByteCount.serialize(
                    journal.totalSizeBytes
                ),
            chunkRootHashHex =
                journal.chunkRootHashHex,
            sessionNonceHex =
                sessionNonceHex,
            channelBindingHex =
                channelContext
                    .channelBindingHex,
            capabilityHashHex =
                capabilityHash,
            signatureHex =
                signatureHex
        )
    }

    suspend fun verifyRequest(
        activeSessionId: SessionId,
        channelContext: AuthenticatedChannelContext,
        negotiatedCapabilities: Set<String>,
        trustManager: PersistentTrustManager,
        authenticatedRequesterFingerprintHex: String,
        request: ResumeRequestDto,
        sourceAuthority: SenderSourceAuthority
    ): VerifiedRequest {
        requireResumeNegotiated(
            negotiatedCapabilities
        )
        requireActiveChannel(
            activeSessionId,
            channelContext
        )

        if (request.profile !=
            IdentityResumeTranscript
                .RESUME_PROFILE
        ) {
            throw Rejected(
                ResumeRejectCodes.CAPABILITY_MISMATCH,
                "Unsupported resume profile"
            )
        }
        if (request.requesterRole !=
                "receiver" ||
            request.responderRole !=
                "sender"
        ) {
            throw Rejected(
                ResumeRejectCodes.UNAUTHORIZED,
                "Resume roles do not match receiver-to-sender flow"
            )
        }
        if (request.requesterFingerprintHex !=
            authenticatedRequesterFingerprintHex
        ) {
            throw Rejected(
                ResumeRejectCodes.UNAUTHORIZED,
                "Resume requester differs from authenticated peer"
            )
        }

        val localFingerprint =
            trustManager.localFingerprintHex()
        if (request.responderFingerprintHex !=
            localFingerprint
        ) {
            throw Rejected(
                ResumeRejectCodes.UNAUTHORIZED,
                "Resume responder fingerprint does not match local identity"
            )
        }

        if (request.channelBindingHex !=
            channelContext.channelBindingHex
        ) {
            throw Rejected(
                ResumeRejectCodes.UNAUTHORIZED,
                "Resume request is bound to another secure channel"
            )
        }

        val capabilityHash =
            IdentityResumeTranscript
                .capabilityHashHex(
                    negotiatedCapabilities
                )
        if (request.capabilityHashHex !=
            capabilityHash
        ) {
            throw Rejected(
                ResumeRejectCodes.CAPABILITY_MISMATCH,
                "Resume capability hash does not match negotiated session"
            )
        }

        if (!isValidUuidV4(
                request.originalSessionId
            ) ||
            !isValidUuidV4(
                request.itemId
            )
        ) {
            throw Rejected(
                ResumeRejectCodes.JOURNAL_MISMATCH,
                "Resume journal identifiers are invalid"
            )
        }

        if (request.originalSessionId !=
                sourceAuthority
                    .originalSessionId
                    .value ||
            request.itemId !=
                sourceAuthority.itemId.value
        ) {
            throw Rejected(
                ResumeRejectCodes.JOURNAL_MISMATCH,
                "Resume request does not match retained sender authority"
            )
        }

        val totalBytes =
            parseByteCountOrReject(
                request.totalSizeBytes
            )
        val offset =
            parseByteCountOrReject(
                request.contiguousByteOffset
            )
        if (request.chunkSize !=
                sourceAuthority.chunkSize ||
            totalBytes !=
                sourceAuthority
                    .totalSizeBytes ||
            request.chunkRootHashHex !=
                sourceAuthority
                    .chunkRootHashHex
        ) {
            throw Rejected(
                ResumeRejectCodes.FILE_CHANGED,
                "Sender source no longer matches resume authority"
            )
        }

        val enrolled =
            trustManager
                .trustedPeerByFingerprint(
                    request
                        .requesterFingerprintHex
                )
                ?: throw Rejected(
                    ResumeRejectCodes.UNAUTHORIZED,
                    "Resume requester is revoked or not enrolled"
                )

        val publicKeyDer =
            try {
                with(PeerIdentityVerifier) {
                    enrolled
                        .publicKeyDerHex
                        .hexToByteArray()
                }
            } catch (
                error: IllegalArgumentException
            ) {
                throw Rejected(
                    ResumeRejectCodes.UNAUTHORIZED,
                    "Stored requester public key is invalid"
                )
            }

        val transcript =
            try {
                IdentityResumeTranscript
                    .resumeRequestBytes(
                        activeSessionId =
                            activeSessionId.value,
                        originalSessionId =
                            request
                                .originalSessionId,
                        itemId =
                            request.itemId,
                        requesterRole =
                            request.requesterRole,
                        responderRole =
                            request.responderRole,
                        requesterFingerprintHex =
                            request
                                .requesterFingerprintHex,
                        responderFingerprintHex =
                            request
                                .responderFingerprintHex,
                        sessionNonceHex =
                            request.sessionNonceHex,
                        channelBindingHex =
                            request.channelBindingHex,
                        capabilityHashHex =
                            request
                                .capabilityHashHex,
                        chunkSize =
                            request.chunkSize,
                        totalSizeBytes =
                            totalBytes,
                        verifiedChunkCount =
                            request
                                .verifiedChunkCount,
                        contiguousByteOffset =
                            offset,
                        chunkRootHashHex =
                            request
                                .chunkRootHashHex
                    )
            } catch (
                error: IllegalArgumentException
            ) {
                throw Rejected(
                    ResumeRejectCodes.INVALID_OFFSET,
                    error.message
                        ?: "Invalid resume offset"
                )
            }

        val signature =
            try {
                with(PeerIdentityVerifier) {
                    request
                        .signatureHex
                        .hexToByteArray()
                }
            } catch (
                error: IllegalArgumentException
            ) {
                throw Rejected(
                    ResumeRejectCodes.UNAUTHORIZED,
                    "Resume signature encoding is invalid"
                )
            }

        if (!PeerIdentityVerifier
                .verifyTranscriptSignature(
                    publicKeyDer =
                        publicKeyDer,
                    transcript =
                        transcript,
                    signatureBytes =
                        signature
                )
        ) {
            throw Rejected(
                ResumeRejectCodes.UNAUTHORIZED,
                "Resume signature verification failed"
            )
        }

        return VerifiedRequest(
            originalSessionId =
                sourceAuthority
                    .originalSessionId,
            itemId =
                sourceAuthority.itemId,
            startChunkIndex =
                request.verifiedChunkCount,
            startByteOffset =
                offset,
            requesterFingerprintHex =
                request
                    .requesterFingerprintHex
        )
    }

    fun createAccept(
        requestMessageId: String,
        verified: VerifiedRequest
    ): ResumeAcceptDto =
        ResumeAcceptDto(
            profile =
                IdentityResumeTranscript
                    .RESUME_PROFILE,
            requestMessageId =
                requestMessageId,
            originalSessionId =
                verified
                    .originalSessionId
                    .value,
            itemId =
                verified.itemId.value,
            startChunkIndex =
                verified.startChunkIndex,
            startByteOffset =
                DecimalByteCount.serialize(
                    verified
                        .startByteOffset
                )
        )

    fun createReject(
        requestMessageId: String,
        originalSessionId: String,
        itemId: String,
        code: String
    ): ResumeRejectDto {
        require(code in ResumeRejectCodes.ALL) {
            "Unsupported resume rejection code"
        }
        return ResumeRejectDto(
            profile =
                IdentityResumeTranscript
                    .RESUME_PROFILE,
            requestMessageId =
                requestMessageId,
            originalSessionId =
                originalSessionId,
            itemId = itemId,
            code = code
        )
    }

    private fun requireResumeNegotiated(
        capabilities: Set<String>
    ) {
        if (NegotiationConstants
                .CAPABILITY_PERSISTENT_IDENTITY_V1 !in
                capabilities ||
            NegotiationConstants
                .CAPABILITY_TRUE_RESUME_V1 !in
                capabilities
        ) {
            throw Rejected(
                ResumeRejectCodes.CAPABILITY_MISMATCH,
                "resume-v1 requires negotiated identity-v1 and resume-v1"
            )
        }
    }

    private fun requireActiveChannel(
        activeSessionId: SessionId,
        context: AuthenticatedChannelContext
    ) {
        if (context.sessionId !=
            activeSessionId
        ) {
            throw Rejected(
                ResumeRejectCodes.UNAUTHORIZED,
                "Authenticated BOOTSTRAP session differs from resume session"
            )
        }
    }

    private fun parseByteCountOrReject(
        value: String
    ): Long =
        try {
            DecimalByteCount.parse(
                value
            )
        } catch (
            error: IllegalArgumentException
        ) {
            throw Rejected(
                ResumeRejectCodes.INVALID_OFFSET,
                error.message
                    ?: "Invalid resume byte count"
            )
        }
}
