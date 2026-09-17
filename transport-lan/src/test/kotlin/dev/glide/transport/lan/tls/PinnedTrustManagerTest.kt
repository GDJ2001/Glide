package dev.glide.transport.lan.tls

import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.cert.CertificateException
import java.security.spec.ECGenParameterSpec
import java.util.Date

class PinnedTrustManagerTest {

    private fun generateEcKeyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    @Test
    fun testMatchingSpkiSucceeds() {
        val keyPair = generateEcKeyPair()
        val cert = SelfSignedCertificateGenerator.generate(keyPair)
        val pin = LanLeaseIdentity.computeSpkiSha256(cert)

        val trustManager = PinnedTrustManager(pin)
        trustManager.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
    }

    @Test
    fun testMismatchSpkiThrowsCertificateException() {
        val keyPair1 = generateEcKeyPair()
        val cert1 = SelfSignedCertificateGenerator.generate(keyPair1)

        val keyPair2 = generateEcKeyPair()
        val cert2 = SelfSignedCertificateGenerator.generate(keyPair2)
        val pin2 = LanLeaseIdentity.computeSpkiSha256(cert2)

        val trustManager = PinnedTrustManager(pin2)
        assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(arrayOf(cert1), "ECDHE_ECDSA")
        }
    }

    @Test
    fun testExpiredCertificateThrowsCertificateException() {
        val keyPair = generateEcKeyPair()
        // Validity in the past
        val notBefore = Date(System.currentTimeMillis() - 100_000)
        val notAfter = Date(System.currentTimeMillis() - 50_000)
        val cert = SelfSignedCertificateGenerator.generate(keyPair, notBefore = notBefore, notAfter = notAfter)
        val pin = LanLeaseIdentity.computeSpkiSha256(cert)

        val trustManager = PinnedTrustManager(pin)
        assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
        }
    }

    @Test
    fun testClientVerificationThrowsCertificateException() {
        val keyPair = generateEcKeyPair()
        val cert = SelfSignedCertificateGenerator.generate(keyPair)
        val pin = LanLeaseIdentity.computeSpkiSha256(cert)

        val trustManager = PinnedTrustManager(pin)
        assertThrows(CertificateException::class.java) {
            trustManager.checkClientTrusted(arrayOf(cert), "ECDHE_ECDSA")
        }
    }

    @Test
    fun testInvalidPinHexThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException::class.java) {
            PinnedTrustManager("invalid_short_hex")
        }
    }
}
