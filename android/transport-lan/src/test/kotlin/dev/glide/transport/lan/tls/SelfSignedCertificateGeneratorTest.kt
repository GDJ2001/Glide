package dev.glide.transport.lan.tls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec

class SelfSignedCertificateGeneratorTest {

    @Test
    fun testGenerateAndVerifySelfSignedCertificate() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()

        val cert = SelfSignedCertificateGenerator.generate(
            keyPair = keyPair,
            commonName = "Glide Test Lease"
        )

        assertNotNull(cert)
        assertEquals("EC", cert.publicKey.algorithm)
        cert.checkValidity()
        cert.verify(keyPair.public)

        val spki = cert.publicKey.encoded
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(spki)
        val hex = digest.joinToString("") { "%02x".format(it) }

        assertEquals(64, hex.length)
        assertTrue(hex.matches(Regex("^[0-9a-f]{64}$")))
    }
}
