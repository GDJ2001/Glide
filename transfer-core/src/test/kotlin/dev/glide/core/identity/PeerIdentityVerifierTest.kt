package dev.glide.core.identity

import dev.glide.core.domain.TrustStatus
import dev.glide.core.domain.TrustedPeerRecord
import dev.glide.core.identity.PeerIdentityVerifier.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class PeerIdentityVerifierTest {

    private fun generateEcKeyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    @Test
    fun generateNonce_produces32RandomBytes() {
        val n1 = PeerIdentityVerifier.generateNonce()
        val n2 = PeerIdentityVerifier.generateNonce()

        assertEquals(32, n1.size)
        assertEquals(32, n2.size)
        assertFalse("Consecutive nonces must not be identical", n1.contentEquals(n2))
    }

    @Test
    fun deriveFingerprint_producesConsistent64HexCharSha256() {
        val keyPair = generateEcKeyPair()
        val encodedDer = keyPair.public.encoded

        val fp1 = PeerIdentityVerifier.deriveFingerprint(encodedDer)
        val fp2 = PeerIdentityVerifier.deriveFingerprint(encodedDer)

        assertEquals(64, fp1.length)
        assertEquals(fp1, fp2)
    }

    @Test
    fun signAndVerifyTranscript_validSignature_returnsTrue() {
        val keyPair = generateEcKeyPair()
        val transcript =
            "GLIDE-SIGN-V1-bound-test-transcript"
                .toByteArray(Charsets.UTF_8)

        val signature =
            PeerIdentityVerifier.signTranscript(
                keyPair.private,
                transcript
            )
        assertNotNull(signature)
        assertTrue(signature.isNotEmpty())

        val isValid =
            PeerIdentityVerifier.verifyTranscriptSignature(
                keyPair.public.encoded,
                transcript,
                signature
            )
        assertTrue("Signature should verify successfully", isValid)
    }

    @Test
    fun verifyTranscriptSignature_tamperedTranscript_returnsFalse() {
        val keyPair = generateEcKeyPair()
        val transcript =
            "GLIDE-SIGN-V1-bound-test-transcript"
                .toByteArray(Charsets.UTF_8)
        val signature =
            PeerIdentityVerifier.signTranscript(
                keyPair.private,
                transcript
            )

        val tampered = transcript.clone().apply {
            this[lastIndex] =
                (this[lastIndex].toInt() xor 1).toByte()
        }
        val isValid =
            PeerIdentityVerifier.verifyTranscriptSignature(
                keyPair.public.encoded,
                tampered,
                signature
            )
        assertFalse(
            "Tampered transcript must fail verification",
            isValid
        )
    }

    @Test
    fun verifyTranscriptSignature_differentPublicKey_returnsFalse() {
        val keyPair1 = generateEcKeyPair()
        val keyPair2 = generateEcKeyPair()
        val transcript =
            "GLIDE-SIGN-V1-bound-test-transcript"
                .toByteArray(Charsets.UTF_8)
        val signature =
            PeerIdentityVerifier.signTranscript(
                keyPair1.private,
                transcript
            )

        val isValid =
            PeerIdentityVerifier.verifyTranscriptSignature(
                keyPair2.public.encoded,
                transcript,
                signature
            )
        assertFalse(
            "Different public key must fail verification",
            isValid
        )
    }

    @Test
    fun verifyPeerTrust_unknownPeer_returnsFirstUsePairing() {
        val keyPair = generateEcKeyPair()
        val status = PeerIdentityVerifier.verifyPeerTrust(null, keyPair.public.encoded, "Device-A")
        assertEquals(TrustStatus.FirstUsePairing, status)
    }

    @Test
    fun verifyPeerTrust_matchingKnownPeer_returnsTrustedPersistent() {
        val keyPair = generateEcKeyPair()
        val der = keyPair.public.encoded
        val fp = PeerIdentityVerifier.deriveFingerprint(der)

        val record = TrustedPeerRecord(
            peerFingerprintHex = fp,
            publicKeyDerHex = der.toHex(),
            displayName = "My Tablet",
            pairedAtMs = 1000L,
            lastSeenAtMs = 2000L
        )

        val status = PeerIdentityVerifier.verifyPeerTrust(record, der, "My Tablet")
        assertEquals(TrustStatus.TrustedPersistent, status)
    }

    @Test(expected = IdentityMismatchException::class)
    fun verifyPeerTrust_tamperedKeyForKnownFingerprint_throwsIdentityMismatchException() {
        val originalKey = generateEcKeyPair()
        val attackerKey = generateEcKeyPair()

        val fp = PeerIdentityVerifier.deriveFingerprint(originalKey.public.encoded)

        val record = TrustedPeerRecord(
            peerFingerprintHex = fp,
            publicKeyDerHex = originalKey.public.encoded.toHex(),
            displayName = "My Tablet",
            pairedAtMs = 1000L,
            lastSeenAtMs = 2000L
        )

        // Attacker claims the same fingerprint but passes different public key DER
        val forgedRecord = record.copy(peerFingerprintHex = PeerIdentityVerifier.deriveFingerprint(attackerKey.public.encoded))
        PeerIdentityVerifier.verifyPeerTrust(forgedRecord.copy(publicKeyDerHex = originalKey.public.encoded.toHex()), attackerKey.public.encoded, "My Tablet")
    }
}
