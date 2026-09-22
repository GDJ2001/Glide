package dev.glide.core.protocol

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.MessageId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.TransferFailure
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class ProtocolCodecTest {

    private fun loadFixtureBytes(name: String): ByteArray {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/$name")
            ?: error("Fixture not found: fixtures/$name")
        return stream.readAllBytes()
    }

    private fun loadFixtureString(name: String): String {
        return String(loadFixtureBytes(name), StandardCharsets.UTF_8)
    }

    @Test
    fun allValidFixtures_decodeAndReencodeSuccessfully() {
        val validFixtures = listOf(
            "hello.json",
            "hello_ack.json",
            "offer_begin.json",
            "offer_page.json",
            "offer_end.json",
            "offer_decision.json",
            "item_start.json",
            "item_ready.json",
            "item_end.json",
            "item_end_zero_byte.json",
            "item_result_verified.json",
            "item_result_failed.json",
            "batch_end.json",
            "batch_result.json",
            "cancel.json",
            "cancel_ack.json",
            "error.json",
            "ping.json",
            "pong.json",
            "message_with_valid_extensions.json"
        )

        for (fixture in validFixtures) {
            val bytes = loadFixtureBytes(fixture)
            val envelope = ProtocolCodec.decode(bytes)
            assertEquals(1, envelope.v)
            assertTrue(envelope.seq >= 1)
            assertNotNull(envelope.type)

            // Re-encode and decode again to ensure round-trip parity
            val encoded = ProtocolCodec.encode(envelope)
            assertTrue("Encoded size must be <= 16384 bytes", encoded.size <= ProtocolCodec.MAX_CONTROL_BYTES)
            val decodedAgain = ProtocolCodec.decode(encoded)
            assertEquals("Round trip type mismatch for $fixture", envelope.type, decodedAgain.type)
            assertEquals("Round trip seq mismatch for $fixture", envelope.seq, decodedAgain.seq)
            assertEquals("Round trip sessionId mismatch for $fixture", envelope.sessionId, decodedAgain.sessionId)
            assertEquals("Round trip messageId mismatch for $fixture", envelope.messageId, decodedAgain.messageId)
        }
    }

    @Test
    fun typedBodyDecoding_verifiesExpectedTypes() {
        val helloEnv = ProtocolCodec.decode(loadFixtureBytes("hello.json"))
        val helloBody = ProtocolCodec.decodeBody<HelloBody>(helloEnv)
        assertEquals("sender", helloBody.role)
        assertEquals(listOf(1), helloBody.supportedMajors)
        assertEquals("Pixel Sender", helloBody.displayName)

        val helloAckEnv = ProtocolCodec.decode(loadFixtureBytes("hello_ack.json"))
        val helloAckBody = ProtocolCodec.decodeBody<HelloAckBody>(helloAckEnv)
        assertEquals(1, helloAckBody.selectedMajor)
        assertEquals(1000, helloAckBody.limits.maxItems)

        val offerBeginEnv = ProtocolCodec.decode(loadFixtureBytes("offer_begin.json"))
        val offerBeginBody = ProtocolCodec.decodeBody<OfferBeginBody>(offerBeginEnv)
        assertEquals(2, offerBeginBody.itemCount)
        assertEquals(153600L, DecimalByteCount.parse(offerBeginBody.totalBytes))

        val offerPageEnv = ProtocolCodec.decode(loadFixtureBytes("offer_page.json"))
        val offerPageBody = ProtocolCodec.decodeBody<OfferPageBody>(offerPageEnv)
        assertEquals(2, offerPageBody.items.size)
        assertEquals("photo1.jpg", offerPageBody.items[0].name)
        assertEquals("image/jpeg", offerPageBody.items[0].mime)
    }

    @Test
    fun streamFraming_roundTripSucceeds() {
        val original = ProtocolCodec.decode(loadFixtureBytes("ping.json"))
        val framedBytes = ProtocolCodec.encodeFramed(original)

        // 4-byte length prefix + payload
        assertTrue(framedBytes.size > 4)
        val stream = ByteArrayInputStream(framedBytes)
        val decoded = ProtocolCodec.decodeFramed(stream)

        assertEquals(original.type, decoded.type)
        assertEquals(original.sessionId, decoded.sessionId)
        assertEquals(original.messageId, decoded.messageId)
    }

    @Test
    fun streamFraming_rejectsTruncatedLengthPrefix() {
        val truncatedStream = ByteArrayInputStream(byteArrayOf(0x00, 0x00))
        try {
            ProtocolCodec.decodeFramed(truncatedStream)
            fail("Expected ProtocolViolationException for truncated length prefix")
        } catch (e: ProtocolViolationException) {
            assertEquals("MALFORMED_MESSAGE", e.failure.code)
        }
    }

    @Test
    fun streamFraming_rejectsOversizedPayloadLength() {
        val buffer = ByteArray(8)
        // Length 20,000 > 16,384
        val len = 20000
        buffer[0] = (len ushr 24).toByte()
        buffer[1] = (len ushr 16).toByte()
        buffer[2] = (len ushr 8).toByte()
        buffer[3] = len.toByte()

        try {
            ProtocolCodec.decodeFramed(ByteArrayInputStream(buffer))
            fail("Expected ProtocolViolationException for oversized framed length")
        } catch (e: ProtocolViolationException) {
            assertEquals("LIMIT_EXCEEDED", e.failure.code)
        }
    }

    @Test
    fun rejectInvalidBom() {
        try {
            ProtocolCodec.decode(loadFixtureBytes("invalid_bom.bin"))
            fail("Expected rejection of UTF-8 BOM")
        } catch (e: ProtocolViolationException) {
            assertEquals("MALFORMED_MESSAGE", e.failure.code)
        }
    }

    @Test
    fun rejectMalformedUtf8() {
        try {
            ProtocolCodec.decode(loadFixtureBytes("invalid_malformed_utf8.bin"))
            fail("Expected rejection of malformed UTF-8")
        } catch (e: ProtocolViolationException) {
            assertEquals("MALFORMED_MESSAGE", e.failure.code)
        }
    }

    @Test
    fun rejectDuplicateKeys() {
        try {
            ProtocolCodec.decode(loadFixtureBytes("invalid_duplicate_keys.json"))
            fail("Expected rejection of duplicate JSON keys")
        } catch (e: ProtocolViolationException) {
            assertEquals("MALFORMED_MESSAGE", e.failure.code)
        }
    }

    @Test
    fun rejectExceeds16KiB() {
        try {
            ProtocolCodec.decode(loadFixtureBytes("invalid_exceeds_16kib.json"))
            fail("Expected rejection of payload exceeding 16 KiB")
        } catch (e: ProtocolViolationException) {
            assertEquals("LIMIT_EXCEEDED", e.failure.code)
        }
    }

    @Test
    fun rejectDepthExceeded() {
        try {
            ProtocolCodec.decode(loadFixtureBytes("invalid_depth_exceeded.json"))
            fail("Expected rejection of JSON nesting depth > 8")
        } catch (e: ProtocolViolationException) {
            assertEquals("MALFORMED_MESSAGE", e.failure.code)
        }
    }

    @Test
    fun rejectUnknownBodyField() {
        try {
            ProtocolCodec.decode(loadFixtureBytes("invalid_unknown_body_field.json"))
            fail("Expected rejection of unknown body field")
        } catch (e: ProtocolViolationException) {
            assertEquals("MALFORMED_MESSAGE", e.failure.code)
        }
    }

    @Test
    fun rejectOversizedExtensions() {
        try {
            ProtocolCodec.decode(loadFixtureBytes("invalid_oversized_extensions.json"))
            fail("Expected rejection of extensions > 1024 bytes")
        } catch (e: ProtocolViolationException) {
            assertEquals("LIMIT_EXCEEDED", e.failure.code)
        }
    }

    @Test
    fun rejectNonCanonicalUuid() {
        try {
            ProtocolCodec.decode(loadFixtureBytes("invalid_non_canonical_uuid.json"))
            fail("Expected rejection of non-canonical UUIDv4")
        } catch (e: ProtocolViolationException) {
            assertEquals("MALFORMED_MESSAGE", e.failure.code)
        }
    }

    @Test
    fun rejectDecimalOverflow() {
        try {
            ProtocolCodec.decode(loadFixtureBytes("invalid_decimal_overflow.json"))
            fail("Expected rejection of overflowing decimal byte count")
        } catch (e: ProtocolViolationException) {
            assertEquals("MALFORMED_MESSAGE", e.failure.code)
        }
    }

    @Test
    fun rejectNegativeSeq() {
        try {
            ProtocolCodec.decode(loadFixtureBytes("invalid_negative_seq.json"))
            fail("Expected rejection of seq <= 0")
        } catch (e: ProtocolViolationException) {
            assertEquals("MALFORMED_MESSAGE", e.failure.code)
        }
    }

    @Test
    fun rejectUnsupportedVersion() {
        try {
            ProtocolCodec.decode(loadFixtureBytes("invalid_unsupported_version.json"))
            fail("Expected rejection of unsupported major version")
        } catch (e: ProtocolViolationException) {
            assertEquals("UNSUPPORTED_PROTOCOL", e.failure.code)
        }
    }

    @Test
    fun decimalByteCount_validAndInvalidCases() {
        assertEquals(0L, DecimalByteCount.parse("0"))
        assertEquals(1024L, DecimalByteCount.parse("1024"))
        assertEquals(Long.MAX_VALUE, DecimalByteCount.parse(Long.MAX_VALUE.toString()))

        assertEquals("0", DecimalByteCount.serialize(0L))
        assertEquals("4096", DecimalByteCount.serialize(4096L))

        // Invalid: leading zeros
        try {
            DecimalByteCount.parse("01")
            fail("Expected error for leading zero")
        } catch (_: IllegalArgumentException) {}

        // Invalid: negative
        try {
            DecimalByteCount.parse("-1")
            fail("Expected error for negative byte count")
        } catch (_: IllegalArgumentException) {}

        // Invalid: non-digit
        try {
            DecimalByteCount.parse("12a3")
            fail("Expected error for non-digit string")
        } catch (_: IllegalArgumentException) {}
    }
}
