package dev.glide.core.coordinator

import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.SessionId
import dev.glide.core.identity.IdentityProofVerificationContext
import dev.glide.core.identity.IdentityProofVerifier
import dev.glide.core.identity.PeerIdentityVerifier
import dev.glide.core.identity.PersistentTrustManager
import dev.glide.core.identity.VerifiedPeerIdentity
import dev.glide.core.ports.AuthenticatedChannelContext
import dev.glide.core.protocol.Envelope
import dev.glide.core.protocol.IdentityChallengeDto
import dev.glide.core.protocol.IdentityResponseDto
import dev.glide.core.protocol.IdentityResumeTranscript
import dev.glide.core.protocol.MessageTypes
import dev.glide.core.protocol.NegotiationConstants
import dev.glide.core.protocol.ProtocolCodec
import kotlinx.serialization.json.JsonObject

internal class TrustedSessionAuthenticationException(
    message: String,
    cause: Throwable? = null
) : SecurityException(message, cause)

internal class TrustedSessionHandshake(
    private val role: DeviceRole,
    private val sessionId: () -> SessionId,
    private val trustManager: PersistentTrustManager,
    private val expectedPeerFingerprintHex: String?,
    private val channelContext: () -> AuthenticatedChannelContext?,
    private val negotiatedCapabilities: () -> Set<String>,
    private val remoteDisplayName: () -> String,
    private val onVerifiedPeerIdentity: ((VerifiedPeerIdentity) -> Unit)?
) {
    private enum class Stage {
        Idle,
        SenderAwaitingPeerResponse,
        SenderAwaitingReciprocalChallenge,
        ReceiverAwaitingSenderChallenge,
        ReceiverAwaitingPeerResponse,
        Complete
    }

    private data class PendingChallenge(
        val envelope: Envelope,
        val body: IdentityChallengeDto
    )

    private var stage: Stage = Stage.Idle
    private var pendingLocalChallenge: PendingChallenge? = null
    private var claimedRemoteFingerprintHex: String? = null
    private var verifiedPeer: VerifiedPeerIdentity? = null
    private var verifiedPeerWasEnrolled: Boolean = false

    val isComplete: Boolean
        get() = stage == Stage.Complete

    val authenticatedPeerFingerprintHex: String?
        get() =
            if (isComplete) {
                verifiedPeer?.fingerprintHex
            } else {
                null
            }

    fun identityCapabilityAvailable(): Boolean {
        val context = channelContext() ?: return false
        return context.sessionId == sessionId() &&
            context.channelBindingHex.length == 64 &&
            context.channelBindingHex.any { it != '0' }
    }

    fun localOptionalCapabilities(): List<String> {
        val optional =
            NegotiationConstants.OPTIONAL_CAPABILITIES
                .toMutableSet()
        if (identityCapabilityAvailable()) {
            optional +=
                NegotiationConstants
                    .CAPABILITY_PERSISTENT_IDENTITY_V1
        }
        return optional.sorted()
    }

    fun isReadyForOffers(): Boolean {
        val capabilities = negotiatedCapabilities()
        val identitySelected =
            NegotiationConstants
                .CAPABILITY_PERSISTENT_IDENTITY_V1 in
                capabilities

        if (expectedPeerFingerprintHex != null &&
            !identitySelected
        ) {
            return false
        }

        return !identitySelected || isComplete
    }

    suspend fun onNegotiated(
        sendControl: suspend (String, JsonObject) -> Envelope
    ) {
        val capabilities = negotiatedCapabilities()
        val identitySelected =
            NegotiationConstants
                .CAPABILITY_PERSISTENT_IDENTITY_V1 in
                capabilities

        if (!identitySelected) {
            if (expectedPeerFingerprintHex != null) {
                throw TrustedSessionAuthenticationException(
                    "Known trusted peer omitted identity-v1 from negotiation."
                )
            }
            return
        }

        requireActiveChannelContext()
        requireExpectedPeerStillEnrolled()

        if (stage != Stage.Idle) {
            throw TrustedSessionAuthenticationException(
                "Identity authentication was started more than once."
            )
        }

        if (role == DeviceRole.Sender) {
            val challenge =
                createChallenge(
                    verifierRole = "sender",
                    signerRole = "receiver",
                    sendControl = sendControl
                )
            pendingLocalChallenge = challenge
            stage = Stage.SenderAwaitingPeerResponse
        } else {
            stage = Stage.ReceiverAwaitingSenderChallenge
        }
    }

    suspend fun onChallenge(
        envelope: Envelope,
        challenge: IdentityChallengeDto,
        sendControl: suspend (String, JsonObject) -> Envelope
    ) {
        requireIdentityNegotiated()

        when (role) {
            DeviceRole.Receiver -> {
                if (stage != Stage.ReceiverAwaitingSenderChallenge) {
                    throw TrustedSessionAuthenticationException(
                        "Receiver got IDENTITY_CHALLENGE out of order."
                    )
                }

                validateChallenge(
                    challenge = challenge,
                    verifierRole = "sender",
                    signerRole = "receiver"
                )

                if (expectedPeerFingerprintHex != null &&
                    challenge.verifierFingerprintHex !=
                    expectedPeerFingerprintHex
                ) {
                    throw TrustedSessionAuthenticationException(
                        "Trusted sender fingerprint changed before identity proof."
                    )
                }

                claimedRemoteFingerprintHex =
                    challenge.verifierFingerprintHex

                sendResponse(
                    challengeEnvelope = envelope,
                    challenge = challenge,
                    signerRole = "receiver",
                    verifierRole = "sender",
                    sendControl = sendControl
                )

                val reciprocal =
                    createChallenge(
                        verifierRole = "receiver",
                        signerRole = "sender",
                        sendControl = sendControl
                    )
                pendingLocalChallenge = reciprocal
                stage = Stage.ReceiverAwaitingPeerResponse
            }

            DeviceRole.Sender -> {
                if (stage !=
                    Stage.SenderAwaitingReciprocalChallenge
                ) {
                    throw TrustedSessionAuthenticationException(
                        "Sender got reciprocal IDENTITY_CHALLENGE out of order."
                    )
                }

                val peer = verifiedPeer
                    ?: throw TrustedSessionAuthenticationException(
                        "Peer response must verify before reciprocal challenge."
                    )

                validateChallenge(
                    challenge = challenge,
                    verifierRole = "receiver",
                    signerRole = "sender"
                )

                if (challenge.verifierFingerprintHex !=
                    peer.fingerprintHex
                ) {
                    throw TrustedSessionAuthenticationException(
                        "Reciprocal challenge identity does not match the verified peer."
                    )
                }

                sendResponse(
                    challengeEnvelope = envelope,
                    challenge = challenge,
                    signerRole = "sender",
                    verifierRole = "receiver",
                    sendControl = sendControl
                )

                complete(peer, verifiedPeerWasEnrolled)
            }
        }
    }

    suspend fun onResponse(
        response: IdentityResponseDto
    ) {
        requireIdentityNegotiated()

        val pending =
            pendingLocalChallenge
                ?: throw TrustedSessionAuthenticationException(
                    "IDENTITY_RESPONSE arrived without an active local challenge."
                )

        when (role) {
            DeviceRole.Sender -> {
                if (stage != Stage.SenderAwaitingPeerResponse) {
                    throw TrustedSessionAuthenticationException(
                        "Sender got IDENTITY_RESPONSE out of order."
                    )
                }

                val peer =
                    verifyResponse(
                        response = response,
                        pending = pending,
                        signerRole = "receiver",
                        verifierRole = "sender",
                        expectedSignerFingerprintHex =
                            expectedPeerFingerprintHex
                    )
                val wasEnrolled =
                    validatePeerTrust(peer)

                verifiedPeer = peer
                verifiedPeerWasEnrolled = wasEnrolled
                pendingLocalChallenge = null
                stage =
                    Stage.SenderAwaitingReciprocalChallenge
            }

            DeviceRole.Receiver -> {
                if (stage != Stage.ReceiverAwaitingPeerResponse) {
                    throw TrustedSessionAuthenticationException(
                        "Receiver got IDENTITY_RESPONSE out of order."
                    )
                }

                val claimed =
                    claimedRemoteFingerprintHex
                        ?: throw TrustedSessionAuthenticationException(
                            "Sender fingerprint claim is missing from the first challenge."
                        )

                val expected =
                    expectedPeerFingerprintHex ?: claimed

                val peer =
                    verifyResponse(
                        response = response,
                        pending = pending,
                        signerRole = "sender",
                        verifierRole = "receiver",
                        expectedSignerFingerprintHex = expected
                    )
                val wasEnrolled =
                    validatePeerTrust(peer)

                if (peer.fingerprintHex != claimed) {
                    throw TrustedSessionAuthenticationException(
                        "Peer changed persistent identity during mutual proof."
                    )
                }

                pendingLocalChallenge = null
                complete(peer, wasEnrolled)
            }
        }
    }

    private suspend fun createChallenge(
        verifierRole: String,
        signerRole: String,
        sendControl: suspend (String, JsonObject) -> Envelope
    ): PendingChallenge {
        val context =
            requireActiveChannelContext()
        val fingerprint =
            trustManager.localFingerprintHex()
        val nonceHex =
            with(PeerIdentityVerifier) {
                generateNonce().toHex()
            }
        val capabilityHash =
            IdentityResumeTranscript
                .capabilityHashHex(
                    negotiatedCapabilities()
                )

        val body =
            IdentityChallengeDto(
                profile =
                    IdentityResumeTranscript
                        .IDENTITY_PROFILE,
                challengeNonceHex = nonceHex,
                verifierFingerprintHex = fingerprint,
                verifierRole = verifierRole,
                signerRole = signerRole,
                channelBindingHex =
                    context.channelBindingHex,
                capabilityHashHex =
                    capabilityHash
            )

        val envelope =
            sendControl(
                MessageTypes.IDENTITY_CHALLENGE,
                ProtocolCodec.encodeBody(body)
            )

        return PendingChallenge(
            envelope = envelope,
            body = body
        )
    }

    private suspend fun sendResponse(
        challengeEnvelope: Envelope,
        challenge: IdentityChallengeDto,
        signerRole: String,
        verifierRole: String,
        sendControl: suspend (String, JsonObject) -> Envelope
    ) {
        val context =
            requireActiveChannelContext()
        val capabilities =
            negotiatedCapabilities()
        val capabilityHash =
            IdentityResumeTranscript
                .capabilityHashHex(capabilities)

        if (challenge.capabilityHashHex != capabilityHash) {
            throw TrustedSessionAuthenticationException(
                "Identity challenge capability hash does not match the negotiated session."
            )
        }

        val localFingerprint =
            trustManager.localFingerprintHex()
        val transcript =
            IdentityResumeTranscript
                .identityProofBytes(
                    activeSessionId =
                        sessionId().value,
                    challengeMessageId =
                        challengeEnvelope
                            .messageId.value,
                    signerRole = signerRole,
                    verifierRole = verifierRole,
                    signerFingerprintHex =
                        localFingerprint,
                    verifierFingerprintHex =
                        challenge
                            .verifierFingerprintHex,
                    challengeNonceHex =
                        challenge.challengeNonceHex,
                    channelBindingHex =
                        context.channelBindingHex,
                    capabilityHashHex =
                        capabilityHash
                )

        val signature =
            trustManager.signTranscript(
                transcript
            )

        val publicKeyHex =
            with(PeerIdentityVerifier) {
                trustManager
                    .localPublicKeyDer()
                    .toHex()
            }
        val signatureHex =
            with(PeerIdentityVerifier) {
                signature.toHex()
            }

        val response =
            IdentityResponseDto(
                profile =
                    IdentityResumeTranscript
                        .IDENTITY_PROFILE,
                challengeMessageId =
                    challengeEnvelope
                        .messageId.value,
                challengeNonceHex =
                    challenge.challengeNonceHex,
                signerFingerprintHex =
                    localFingerprint,
                publicKeyDerHex =
                    publicKeyHex,
                signatureHex =
                    signatureHex
            )

        sendControl(
            MessageTypes.IDENTITY_RESPONSE,
            ProtocolCodec.encodeBody(response)
        )
    }

    private fun validateChallenge(
        challenge: IdentityChallengeDto,
        verifierRole: String,
        signerRole: String
    ) {
        val context =
            requireActiveChannelContext()
        val capabilityHash =
            IdentityResumeTranscript
                .capabilityHashHex(
                    negotiatedCapabilities()
                )

        if (challenge.profile !=
            IdentityResumeTranscript.IDENTITY_PROFILE
        ) {
            throw TrustedSessionAuthenticationException(
                "Unsupported identity profile."
            )
        }
        if (challenge.verifierRole != verifierRole ||
            challenge.signerRole != signerRole
        ) {
            throw TrustedSessionAuthenticationException(
                "Identity proof roles do not match the active transfer direction."
            )
        }
        if (challenge.channelBindingHex !=
            context.channelBindingHex
        ) {
            throw TrustedSessionAuthenticationException(
                "Identity proof is bound to a different TLS server identity."
            )
        }
        if (challenge.capabilityHashHex !=
            capabilityHash
        ) {
            throw TrustedSessionAuthenticationException(
                "Identity proof capability hash mismatch."
            )
        }
    }

    private fun verifyResponse(
        response: IdentityResponseDto,
        pending: PendingChallenge,
        signerRole: String,
        verifierRole: String,
        expectedSignerFingerprintHex: String?
    ): VerifiedPeerIdentity {
        val context =
            requireActiveChannelContext()

        return try {
            IdentityProofVerifier.verify(
                context =
                    IdentityProofVerificationContext(
                        activeSessionId =
                            sessionId().value,
                        challengeMessageId =
                            pending.envelope
                                .messageId.value,
                        signerRole = signerRole,
                        verifierRole = verifierRole,
                        verifierFingerprintHex =
                            pending.body
                                .verifierFingerprintHex,
                        challengeNonceHex =
                            pending.body
                                .challengeNonceHex,
                        channelBindingHex =
                            context.channelBindingHex,
                        negotiatedCapabilities =
                            negotiatedCapabilities(),
                        expectedSignerFingerprintHex =
                            expectedSignerFingerprintHex
                    ),
                response = response,
                displayName =
                    remoteDisplayName()
            )
        } catch (error: Exception) {
            throw TrustedSessionAuthenticationException(
                "Persistent peer identity proof failed: " +
                    (error.message
                        ?: error::class.java.simpleName),
                error
            )
        }
    }

    private suspend fun validatePeerTrust(
        peer: VerifiedPeerIdentity
    ): Boolean {
        expectedPeerFingerprintHex?.let { expected ->
            if (peer.fingerprintHex != expected) {
                throw TrustedSessionAuthenticationException(
                    "Presented peer does not match the required enrolled identity."
                )
            }

            val enrolled =
                trustManager
                    .trustedPeerByFingerprint(expected)
                    ?: throw TrustedSessionAuthenticationException(
                        "Required peer identity is revoked or not enrolled."
                    )

            if (enrolled.publicKeyDerHex !=
                peer.publicKeyDerHex
            ) {
                throw TrustedSessionAuthenticationException(
                    "Enrolled peer public key changed."
                )
            }
            return true
        }

        val enrolled =
            trustManager
                .trustedPeerByFingerprint(
                    peer.fingerprintHex
                )
                ?: return false

        if (enrolled.publicKeyDerHex !=
            peer.publicKeyDerHex
        ) {
            throw TrustedSessionAuthenticationException(
                "Enrolled peer public key changed."
            )
        }
        return true
    }

    private suspend fun requireExpectedPeerStillEnrolled() {
        val expected =
            expectedPeerFingerprintHex
                ?: return

        if (trustManager
                .trustedPeerByFingerprint(expected) == null
        ) {
            throw TrustedSessionAuthenticationException(
                "Required trusted peer is revoked or not enrolled."
            )
        }
    }

    private suspend fun complete(
        peer: VerifiedPeerIdentity,
        wasEnrolled: Boolean
    ) {
        verifiedPeer = peer
        verifiedPeerWasEnrolled = wasEnrolled
        claimedRemoteFingerprintHex = null
        pendingLocalChallenge = null
        stage = Stage.Complete

        if (wasEnrolled) {
            trustManager.markSeen(
                peer.fingerprintHex
            )
        } else {
            onVerifiedPeerIdentity
                ?.invoke(peer)
        }
    }

    private fun requireIdentityNegotiated() {
        if (NegotiationConstants
                .CAPABILITY_PERSISTENT_IDENTITY_V1 !in
            negotiatedCapabilities()
        ) {
            throw TrustedSessionAuthenticationException(
                "identity-v1 message received without negotiated identity capability."
            )
        }
    }

    private fun requireActiveChannelContext():
        AuthenticatedChannelContext {
        val context =
            channelContext()
                ?: throw TrustedSessionAuthenticationException(
                    "Authenticated LAN channel binding is unavailable."
                )

        if (context.sessionId != sessionId()) {
            throw TrustedSessionAuthenticationException(
                "Authenticated BOOTSTRAP session differs from transfer session."
            )
        }

        if (context.channelBindingHex.length != 64 ||
            context.channelBindingHex.all { it == '0' }
        ) {
            throw TrustedSessionAuthenticationException(
                "Authenticated TLS server SPKI binding is invalid."
            )
        }

        return context
    }
}
