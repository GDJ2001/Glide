package dev.glide.core.identity

import dev.glide.core.domain.TrustedPeerRecord
import dev.glide.core.protocol.IdentityResponseDto
import dev.glide.core.protocol.IdentityResumeTranscript
import kotlinx.coroutines.flow.Flow

data class IdentityProofVerificationContext(
    val activeSessionId: String,
    val challengeMessageId: String,
    val signerRole: String,
    val verifierRole: String,
    val verifierFingerprintHex: String,
    val challengeNonceHex: String,
    val channelBindingHex: String,
    val negotiatedCapabilities: Set<String>,
    val expectedSignerFingerprintHex: String? = null
)

class VerifiedPeerIdentity internal constructor(
    val fingerprintHex: String,
    val publicKeyDerHex: String,
    val displayName: String
)

/**
 * Pure cryptographic conversion from a validated IDENTITY_RESPONSE into an enrollment-capable
 * value. The constructor for [VerifiedPeerIdentity] is module-internal so UI/discovery code cannot
 * manufacture an enrollment authority from arbitrary metadata.
 */
object IdentityProofVerifier {

    private val lowerHexRegex = Regex("^[0-9a-f]+$")

    fun verify(
        context: IdentityProofVerificationContext,
        response: IdentityResponseDto,
        displayName: String
    ): VerifiedPeerIdentity {
        require(response.profile == IdentityResumeTranscript.IDENTITY_PROFILE) {
            "Unsupported identity profile"
        }
        require(response.challengeMessageId == context.challengeMessageId) {
            "Identity response does not correlate to the active challenge"
        }
        require(response.challengeNonceHex == context.challengeNonceHex) {
            "Identity response challenge nonce mismatch"
        }
        require(
            dev.glide.core.protocol.NegotiationConstants
                .CAPABILITY_PERSISTENT_IDENTITY_V1 in
                context.negotiatedCapabilities
        ) {
            "identity-v1 was not negotiated for this session"
        }
        require(displayName.isNotBlank()) {
            "Peer display name cannot be blank"
        }

        val publicKeyDer = decodeCanonicalHex(
            response.publicKeyDerHex,
            "publicKeyDerHex"
        )
        val signature = decodeCanonicalHex(
            response.signatureHex,
            "signatureHex"
        )

        val derivedFingerprint =
            PeerIdentityVerifier.deriveFingerprint(publicKeyDer)
        require(response.signerFingerprintHex == derivedFingerprint) {
            "Identity response fingerprint does not match the presented public key"
        }

        context.expectedSignerFingerprintHex?.let { expected ->
            require(expected == derivedFingerprint) {
                "Presented identity does not match the enrolled peer fingerprint"
            }
        }

        val capabilityHash =
            IdentityResumeTranscript.capabilityHashHex(
                context.negotiatedCapabilities
            )
        val transcript =
            IdentityResumeTranscript.identityProofBytes(
                activeSessionId = context.activeSessionId,
                challengeMessageId = context.challengeMessageId,
                signerRole = context.signerRole,
                verifierRole = context.verifierRole,
                signerFingerprintHex = derivedFingerprint,
                verifierFingerprintHex = context.verifierFingerprintHex,
                challengeNonceHex = context.challengeNonceHex,
                channelBindingHex = context.channelBindingHex,
                capabilityHashHex = capabilityHash
            )

        require(
            PeerIdentityVerifier.verifyTranscriptSignature(
                publicKeyDer = publicKeyDer,
                transcript = transcript,
                signatureBytes = signature
            )
        ) {
            "Persistent identity signature verification failed"
        }

        return VerifiedPeerIdentity(
            fingerprintHex = derivedFingerprint,
            publicKeyDerHex = response.publicKeyDerHex,
            displayName = displayName
        )
    }

    fun verifyPairV2Signature(
        publicKeyDer: ByteArray,
        transcript: ByteArray,
        signatureBytes: ByteArray,
        displayName: String
    ): VerifiedPeerIdentity {
        require(displayName.isNotBlank()) { "Peer display name cannot be blank" }
        val derivedFingerprint = PeerIdentityVerifier.deriveFingerprint(publicKeyDer)
        require(
            PeerIdentityVerifier.verifyTranscriptSignature(
                publicKeyDer = publicKeyDer,
                transcript = transcript,
                signatureBytes = signatureBytes
            )
        ) {
            "Profile 2 persistent identity signature verification failed"
        }
        val publicKeyDerHex = publicKeyDer.joinToString("") { String.format("%02x", it.toInt() and 0xff) }
        return VerifiedPeerIdentity(
            fingerprintHex = derivedFingerprint,
            publicKeyDerHex = publicKeyDerHex,
            displayName = displayName
        )
    }

    private fun decodeCanonicalHex(
        value: String,
        name: String
    ): ByteArray {
        require(
            value.isNotEmpty() &&
                value.length % 2 == 0 &&
                lowerHexRegex.matches(value)
        ) {
            "$name must be non-empty even-length lowercase hexadecimal"
        }
        return with(PeerIdentityVerifier) {
            value.hexToByteArray()
        }
    }
}

sealed interface LocalIdentityInitialization {
    val localFingerprintHex: String

    data class Ready(
        override val localFingerprintHex: String
    ) : LocalIdentityInitialization

    data class ResetAfterKeyLoss(
        override val localFingerprintHex: String,
        val revokedPeerCount: Int
    ) : LocalIdentityInitialization
}

sealed interface PeerEnrollmentResult {
    data class Enrolled(
        val peer: TrustedPeerRecord
    ) : PeerEnrollmentResult

    data class AlreadyEnrolled(
        val peer: TrustedPeerRecord
    ) : PeerEnrollmentResult

    data object Declined : PeerEnrollmentResult
}

/**
 * Platform-neutral persistent trust lifecycle.
 *
 * HARD-019 wires this manager to Android Keystore + Room. HARD-020 supplies desktop adapters.
 * HARD-021 owns live protocol exchange/channel binding and is the only production source of
 * [VerifiedPeerIdentity] values.
 */
class PersistentTrustManager(
    private val identityStore: PersistentIdentityStore,
    private val trustedPeerStore: TrustedPeerStore,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    val trustedPeers: Flow<List<TrustedPeerRecord>> =
        trustedPeerStore.observeAll()

    /**
     * Initializes the protected local identity.
     *
     * If trust records exist but the platform identity key is missing, the database may have been
     * restored without its non-exportable key or the key may have been lost. Existing trust is
     * invalidated rather than silently attaching it to a newly generated local identity.
     */
    suspend fun initialize(): LocalIdentityInitialization {
        val hadLocalIdentity =
            identityStore.hasIdentity()
        val existingPeerCount =
            trustedPeerStore.count()

        val fingerprint =
            identityStore.getFingerprint()

        return if (!hadLocalIdentity && existingPeerCount > 0) {
            trustedPeerStore.revokeAll()
            LocalIdentityInitialization.ResetAfterKeyLoss(
                localFingerprintHex = fingerprint,
                revokedPeerCount = existingPeerCount
            )
        } else {
            LocalIdentityInitialization.Ready(
                localFingerprintHex = fingerprint
            )
        }
    }

    fun localFingerprintHex(): String =
        identityStore.getFingerprint()

    fun localPublicKeyDer(): ByteArray =
        identityStore.getOrCreatePublicKeyDer()

    fun signTranscript(transcript: ByteArray): ByteArray =
        identityStore.signTranscript(transcript)

    suspend fun enrollVerifiedPeer(
        verifiedPeer: VerifiedPeerIdentity,
        userApproved: Boolean
    ): PeerEnrollmentResult {
        if (!userApproved) {
            return PeerEnrollmentResult.Declined
        }

        val now = nowMs()
        val existing =
            trustedPeerStore.getByFingerprint(
                verifiedPeer.fingerprintHex
            )

        if (existing != null) {
            if (existing.publicKeyDerHex !=
                verifiedPeer.publicKeyDerHex
            ) {
                throw IdentityMismatchException(
                    "Stored key material changed for enrolled fingerprint " +
                        verifiedPeer.fingerprintHex
                )
            }

            val refreshed =
                existing.copy(
                    displayName =
                        verifiedPeer.displayName,
                    lastSeenAtMs =
                        maxOf(
                            existing.lastSeenAtMs,
                            now
                        )
                )
            trustedPeerStore.upsert(refreshed)
            return PeerEnrollmentResult.AlreadyEnrolled(
                refreshed
            )
        }

        val record =
            TrustedPeerRecord(
                peerFingerprintHex =
                    verifiedPeer.fingerprintHex,
                publicKeyDerHex =
                    verifiedPeer.publicKeyDerHex,
                displayName =
                    verifiedPeer.displayName,
                pairedAtMs = now,
                lastSeenAtMs = now
            )
        trustedPeerStore.upsert(record)
        return PeerEnrollmentResult.Enrolled(record)
    }

    suspend fun trustedPeerSnapshot(): List<TrustedPeerRecord> =
        trustedPeerStore.getAll()

    suspend fun trustedPeerByFingerprint(
        fingerprintHex: String
    ): TrustedPeerRecord? =
        trustedPeerStore.getByFingerprint(
            fingerprintHex
        )

    suspend fun markSeen(
        fingerprintHex: String
    ) {
        val existing =
            trustedPeerStore.getByFingerprint(
                fingerprintHex
            ) ?: return

        trustedPeerStore.updateLastSeen(
            fingerprintHex,
            maxOf(
                existing.lastSeenAtMs,
                nowMs()
            )
        )
    }

    suspend fun revokePeer(
        fingerprintHex: String
    ) {
        trustedPeerStore.revoke(
            fingerprintHex
        )
    }

    /**
     * Explicit local identity rotation invalidates all enrolled trust. This avoids one-sided trust
     * surviving a local key change that remote peers will correctly reject.
     */
    suspend fun rotateLocalIdentity():
        LocalIdentityInitialization.ResetAfterKeyLoss {
        val revoked =
            trustedPeerStore.count()
        trustedPeerStore.revokeAll()
        identityStore.deleteIdentity()
        val fingerprint =
            identityStore.getFingerprint()

        return LocalIdentityInitialization.ResetAfterKeyLoss(
            localFingerprintHex = fingerprint,
            revokedPeerCount = revoked
        )
    }
}
