package dev.glide.transport.lan.security

import dev.glide.core.pairing.LanEndpoint
import dev.glide.core.pairing.LanInvitation
import dev.glide.core.ports.VerificationEvidence
import dev.glide.core.transport.lan.LanBootstrapMessage
import dev.glide.core.transport.lan.LanBootstrapResult
import dev.glide.transport.lan.tls.LanLeaseIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Audit tests verifying Section 20 Requirement 10:
 * "Secrets/privacy: static/log/export review for token, QR URI, keystore material, full pins,
 * IP/SSID and filenames; default production logs contain only error category, attempt-local random
 * correlation and stage."
 */
class SecretsAndPrivacyLeakageAuditTest {

    private val secretToken = "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8S9t0U1v"
    private val fullServerPin = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun lanInvitation_toString_redactsToken() {
        val invitation = LanInvitation(
            invitationId = UUID.randomUUID().toString(),
            presence = "0123456789abcdef0123456789abcdef",
            name = "Test Receiver",
            platform = "android",
            endpoints = listOf(LanEndpoint("192.168.1.50", 42424)),
            spkiSha256 = fullServerPin,
            token = secretToken,
            expiresAt = "1750000000",
            ttlSeconds = 120
        )

        val stringRep = invitation.toString()
        assertTrue("toString() must indicate token is redacted", stringRep.contains("token=[REDACTED]"))
        assertFalse("toString() must NEVER contain raw secret token", stringRep.contains(secretToken))
    }

    @Test
    fun lanBootstrapMessage_toString_redactsToken() {
        val message = LanBootstrapMessage(
            v = 1,
            invitationId = UUID.randomUUID().toString(),
            token = secretToken,
            invitationHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sessionId = UUID.randomUUID().toString(),
            role = LanBootstrapMessage.ROLE_SENDER
        )

        val stringRep = message.toString()
        assertTrue("toString() must indicate token is redacted", stringRep.contains("token=[REDACTED]"))
        assertFalse("toString() must NEVER contain raw secret token", stringRep.contains(secretToken))
    }

    @Test
    fun verificationEvidence_invitationVerified_toString_truncatesServerPin() {
        val evidence = VerificationEvidence.InvitationVerified(
            invitationId = "00000000-0000-4000-8000-000000000001",
            serverPin = fullServerPin
        )

        val stringRep = evidence.toString()
        assertTrue("toString() must contain truncated 8-character pin prefix", stringRep.contains("serverPin='e3b0c442...'"))
        assertFalse("toString() must NEVER contain full 64-character server pin", stringRep.contains(fullServerPin))

        // Ensure underlying property is still accessible in full programmatically
        assertEquals(fullServerPin, evidence.serverPin)
    }

    @Test
    fun lanLeaseIdentity_toString_redactsPrivateKey() {
        val identity = LanLeaseIdentity.create(preferAndroidKeyStore = false)
        try {
            val stringRep = identity.toString()
            assertFalse("toString() must not contain 'privateKey'", stringRep.contains("privateKey", ignoreCase = true))
            assertFalse("toString() must not contain 'sun.security'", stringRep.contains("sun.security", ignoreCase = true))
            assertFalse("toString() must not contain full SPKI pin", stringRep.contains(identity.spkiSha256))
            assertTrue("toString() must contain truncated SPKI pin", stringRep.contains("${identity.spkiSha256.take(8)}..."))
        } finally {
            identity.close()
        }
    }

    @Test
    fun lanBootstrapResult_doesNotLeakSecrets() {
        val accepted = LanBootstrapResult.accepted("00000000-0000-4000-8000-000000000001")
        val rejected = LanBootstrapResult.rejected(dev.glide.core.transport.lan.LanBootstrapFailureCode.AUTHENTICATION_FAILED)

        val acceptedStr = accepted.toString()
        val rejectedStr = rejected.toString()

        assertFalse(acceptedStr.contains(secretToken))
        assertFalse(rejectedStr.contains(secretToken))
        assertFalse(acceptedStr.contains(fullServerPin))
        assertFalse(rejectedStr.contains(fullServerPin))
    }
}
