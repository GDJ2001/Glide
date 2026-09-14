package dev.glide.core.protocol

import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.EffectiveLimits
import dev.glide.core.domain.ProtocolLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NegotiationValidatorTest {

    private val validSenderPeerId = "a0000000-0000-4000-8000-000000000001"
    private val validReceiverPeerId = "b0000000-0000-4000-8000-000000000002"

    @Test
    fun validNegotiation_createsAndValidatesSession() {
        val hello = NegotiationValidator.createHello(
            role = DeviceRole.Sender,
            ephemeralPeerId = validSenderPeerId,
            displayName = "Pixel 8 Pro"
        )
        assertEquals("sender", hello.role)
        assertEquals(listOf(1), hello.supportedMajors)
        assertTrue(hello.requiredCapabilities.contains("stream-v1"))
        assertTrue(hello.requiredCapabilities.contains("sha256"))

        val ack = NegotiationValidator.createHelloAck(
            hello = hello,
            ephemeralPeerId = validReceiverPeerId,
            displayName = "Galaxy S24",
            requestedLimits = EffectiveLimits(
                maxItems = 500,
                maxItemBytes = 10_000_000_000L,
                maxBatchBytes = 50_000_000_000L,
                maxControlBytes = 8192
            )
        )

        val session = NegotiationValidator.validateHelloAck(hello, ack)
        assertEquals(1, session.selectedMajor)
        assertEquals(validReceiverPeerId, session.peerId.value)
        assertEquals("Galaxy S24", session.peerDisplayName)
        assertEquals(500, session.effectiveLimits.maxItems)
        assertEquals(10_000_000_000L, session.effectiveLimits.maxItemBytes)
        assertEquals(50_000_000_000L, session.effectiveLimits.maxBatchBytes)
        assertEquals(8192, session.effectiveLimits.maxControlBytes)
    }

    @Test
    fun helloAckDowngradeMajor_failsUnsupportedProtocol() {
        val hello = NegotiationValidator.createHello(
            role = DeviceRole.Sender,
            ephemeralPeerId = validSenderPeerId,
            displayName = "Pixel 8 Pro"
        )
        val validAck = NegotiationValidator.createHelloAck(
            hello = hello,
            ephemeralPeerId = validReceiverPeerId,
            displayName = "Receiver"
        )
        // Attempt downgrade to unoffered major version 2
        val tamperedAck = validAck.copy(selectedMajor = 2)

        try {
            NegotiationValidator.validateHelloAck(hello, tamperedAck)
            fail("Expected rejection of unoffered selectedMajor")
        } catch (e: ProtocolViolationException) {
            assertEquals("UNSUPPORTED_PROTOCOL", e.failure.code)
        }
    }

    @Test
    fun helloAckMissingRequiredCapability_failsUnsupportedProtocol() {
        val hello = NegotiationValidator.createHello(
            role = DeviceRole.Sender,
            ephemeralPeerId = validSenderPeerId,
            displayName = "Pixel 8 Pro"
        )
        val validAck = NegotiationValidator.createHelloAck(
            hello = hello,
            ephemeralPeerId = validReceiverPeerId,
            displayName = "Receiver"
        )
        // Strip sha256 capability
        val tamperedAck = validAck.copy(capabilities = listOf("stream-v1"))

        try {
            NegotiationValidator.validateHelloAck(hello, tamperedAck)
            fail("Expected rejection of missing sha256 capability")
        } catch (e: ProtocolViolationException) {
            assertEquals("UNSUPPORTED_PROTOCOL", e.failure.code)
        }
    }

    @Test
    fun helloAckExceedsMaxItemBytes_failsLimitExceeded() {
        val hello = NegotiationValidator.createHello(
            role = DeviceRole.Sender,
            ephemeralPeerId = validSenderPeerId,
            displayName = "Pixel 8 Pro"
        )
        val validAck = NegotiationValidator.createHelloAck(
            hello = hello,
            ephemeralPeerId = validReceiverPeerId,
            displayName = "Receiver"
        )
        // 100 GiB > 64 GiB limit
        val tamperedAck = validAck.copy(
            limits = validAck.limits.copy(maxItemBytes = (ProtocolLimits.MAX_ITEM_BYTES + 1000).toString())
        )

        try {
            NegotiationValidator.validateHelloAck(hello, tamperedAck)
            fail("Expected rejection of maxItemBytes exceeding ceiling")
        } catch (e: ProtocolViolationException) {
            assertEquals("LIMIT_EXCEEDED", e.failure.code)
        }
    }

    @Test
    fun helloAckControlBytesOutOfBounds_fails() {
        val hello = NegotiationValidator.createHello(
            role = DeviceRole.Sender,
            ephemeralPeerId = validSenderPeerId,
            displayName = "Pixel 8 Pro"
        )
        val validAck = NegotiationValidator.createHelloAck(
            hello = hello,
            ephemeralPeerId = validReceiverPeerId,
            displayName = "Receiver"
        )
        // maxControlBytes < 1024 is unsupported
        val tamperedAck = validAck.copy(
            limits = validAck.limits.copy(maxControlBytes = 512)
        )

        try {
            NegotiationValidator.validateHelloAck(hello, tamperedAck)
            fail("Expected rejection of maxControlBytes < 1024")
        } catch (e: ProtocolViolationException) {
            assertEquals("UNSUPPORTED_PROTOCOL", e.failure.code)
        }
    }

    @Test
    fun invalidPeerIdOrDisplayName_fails() {
        try {
            NegotiationValidator.createHello(
                role = DeviceRole.Sender,
                ephemeralPeerId = "not-a-uuid",
                displayName = "Valid Name"
            )
            fail("Expected rejection of invalid peerId")
        } catch (_: IllegalArgumentException) {}

        try {
            NegotiationValidator.createHello(
                role = DeviceRole.Sender,
                ephemeralPeerId = validSenderPeerId,
                displayName = "x".repeat(65)
            )
            fail("Expected rejection of oversized displayName")
        } catch (e: ProtocolViolationException) {
            assertEquals("LIMIT_EXCEEDED", e.failure.code)
        }
    }
}
