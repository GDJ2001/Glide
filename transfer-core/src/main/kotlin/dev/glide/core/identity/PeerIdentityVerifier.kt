package dev.glide.core.identity

import dev.glide.core.domain.TrustStatus
import dev.glide.core.domain.TrustedPeerRecord
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Thrown when an advertised identity fingerprint matches a known trusted peer,
 * but presents a modified or altered public key (ADR-0015, T01/T05/T08).
 */
class IdentityMismatchException(message: String) : SecurityException(message)

/**
 * Pure JVM cryptographic verifier for persistent peer identities and mutual challenge-response authentication.
 *
 * Requirements (ADR-0015, PERSISTENT_IDENTITY_AND_RESUME_SPECIFICATION):
 * 1. Derives 32-byte SHA-256 fingerprint from encoded public key.
 * 2. Issues random 32-byte challenge nonces.
 * 3. Signs caller-supplied canonical protocol transcripts with EC P-256 (SHA256withECDSA).
 * 4. Verifies signatures against pinned public keys.
 * 5. Strictly detects public key tampering / MITM attacks.
 *
 * This class deliberately does not expose a nonce-only signing primitive. HARD-018 defines the
 * complete signed transcript; callers must build that transcript before asking a key to sign it.
 */
object PeerIdentityVerifier {

    private val secureRandom = SecureRandom()

    fun generateNonce(): ByteArray {
        val nonce = ByteArray(32)
        secureRandom.nextBytes(nonce)
        return nonce
    }

    fun deriveFingerprint(encodedPublicKeyDer: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(encodedPublicKeyDer)
        return digest.toHex()
    }

    fun decodePublicKey(encodedDer: ByteArray): PublicKey {
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(X509EncodedKeySpec(encodedDer))
    }

    fun signTranscript(
        privateKey: PrivateKey,
        transcript: ByteArray
    ): ByteArray {
        require(transcript.isNotEmpty()) {
            "Canonical signature transcript cannot be empty"
        }

        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(transcript)
        return signature.sign()
    }

    fun verifyTranscriptSignature(
        publicKeyDer: ByteArray,
        transcript: ByteArray,
        signatureBytes: ByteArray
    ): Boolean {
        if (transcript.isEmpty() || signatureBytes.isEmpty()) {
            return false
        }

        return try {
            val pubKey = decodePublicKey(publicKeyDer)
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initVerify(pubKey)
            signature.update(transcript)
            signature.verify(signatureBytes)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Evaluates trust status for a peer presenting [presentedKeyDer].
     * If the peer's fingerprint matches a known peer but the public key differs,
     * immediately raises [IdentityMismatchException] to abort connection.
     */
    fun verifyPeerTrust(
        knownPeer: TrustedPeerRecord?,
        presentedKeyDer: ByteArray,
        displayName: String
    ): TrustStatus {
        if (knownPeer == null) {
            return TrustStatus.FirstUsePairing
        }

        val presentedFingerprint = deriveFingerprint(presentedKeyDer)
        val presentedKeyDerHex = presentedKeyDer.toHex()

        if (knownPeer.peerFingerprintHex.equals(presentedFingerprint, ignoreCase = true)) {
            if (knownPeer.publicKeyDerHex.equals(presentedKeyDerHex, ignoreCase = true)) {
                return TrustStatus.TrustedPersistent
            } else {
                throw IdentityMismatchException(
                    "Security Warning: Public key mismatch for known peer '$displayName' (fingerprint: $presentedFingerprint). Possible impersonation attack."
                )
            }
        }

        return TrustStatus.FirstUsePairing
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0) { "Hex string length must be even" }
        val result = ByteArray(length / 2)
        for (i in 0 until length step 2) {
            val high = Character.digit(this[i], 16)
            val low = Character.digit(this[i + 1], 16)
            require(high >= 0 && low >= 0) { "Invalid hex character" }
            result[i / 2] = ((high shl 4) or low).toByte()
        }
        return result
    }
}
