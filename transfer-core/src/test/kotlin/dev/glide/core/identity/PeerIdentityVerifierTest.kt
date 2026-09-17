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
    fun signAndVerifyNonce_validSignature_returnsTrue() {
        val keyPair = generateEcKeyPair()
        val nonce = PeerIdentityVerifier.generateNonce()

        val signature = PeerIdentityVerifier.signNonce(keyPair.private, nonce)
        assertNotNull(signature)
        assertTrue(signature.isNotEmpty())

        val isValid = PeerIdentityVerifier.verifyNonceSignature(keyPair.public.encoded, nonce, signature)
        assertTrue("Signature should verify successfully", isValid)
    }

    @Test
    fun verifyNonceSignature_tamperedNonce_returnsFalse() {
        val keyPair = generateEcKeyPair()
        val nonce = PeerIdentityVerifier.generateNonce()
        val signature = PeerIdentityVerifier.signNonce(keyPair.private, nonce)

        val tamperedNonce = nonce.clone().apply { this[0] = (this[0] + 1).toByte() }
        val isValid = PeerIdentityVerifier.verifyNonceSignature(keyPair.public.encoded, tamperedNonce, signature)
        assertFalse("Tampered nonce must fail verification", isValid)
    }

    @Test
    fun verifyNonceSignature_differentPublicKey_returnsFalse() {
        val keyPair1 = generateEcKeyPair()
        val keyPair2 = generateEcKeyPair()
        val nonce = PeerIdentityVerifier.generateNonce()
        val signature = PeerIdentityVerifier.signNonce(keyPair1.private, nonce)

        val isValid = PeerIdentityVerifier.verifyNonceSignature(keyPair2.public.encoded, nonce, signature)
        assertFalse("Different public key must fail verification", isValid)
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
