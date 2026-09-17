package dev.glide.core.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class InvitationCodecTest {

    private fun sampleValidInvitation(): LanInvitation {
        return LanInvitation(
            v = 1,
            transport = "lan-tls",
            profile = 1,
            protocol = 1,
            invitationId = "00000000-0000-4000-8000-000000000001",
            presence = "00112233445566778899aabbccddeeff",
            name = "Pixel 9 Pro",
            platform = "android",
            endpoints = listOf(
                LanEndpoint("192.168.1.50", 49152),
                LanEndpoint("2001:db8::1", 49153)
            ),
            spkiSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            token = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLM_012",
            expiresAt = "1773729600",
            ttlSeconds = 120
        )
    }

    @Test
    fun roundTrip_canonicalFixture_preservesAllFieldsAndComputesHash() {
        val original = sampleValidInvitation()
        val uri = InvitationCodec.encode(original)

        assertTrue(uri.startsWith("glide://pair/v1#"))
        assertTrue(uri.length <= InvitationCodec.MAX_URI_CHARS)

        val decoded = InvitationCodec.decode(uri)

        assertEquals(original.v, decoded.v)
        assertEquals(original.transport, decoded.transport)
        assertEquals(original.profile, decoded.profile)
        assertEquals(original.protocol, decoded.protocol)
        assertEquals(original.invitationId, decoded.invitationId)
        assertEquals(original.presence, decoded.presence)
        assertEquals(original.name, decoded.name)
        assertEquals(original.platform, decoded.platform)
        assertEquals(original.endpoints.size, decoded.endpoints.size)
        assertEquals(original.endpoints[0], decoded.endpoints[0])
        assertEquals(original.endpoints[1], decoded.endpoints[1])
        assertEquals(original.spkiSha256, decoded.spkiSha256)
        assertEquals(original.token, decoded.token)
        assertEquals(original.expiresAt, decoded.expiresAt)
        assertEquals(original.ttlSeconds, decoded.ttlSeconds)

        assertTrue(decoded.invitationHash.isNotBlank())
        assertEquals(64, decoded.invitationHash.length)
        assertTrue(decoded.invitationHash.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun secretToken_isStrictlyRedactedFromToString() {
        val invitation = sampleValidInvitation()
        val stringRepresentation = invitation.toString()

        assertFalse(
            "Secret token must NOT be exposed in toString()",
            stringRepresentation.contains(invitation.token)
        )
        assertTrue(
            "toString() must contain redacted token indicator",
            stringRepresentation.contains("token=[REDACTED]")
        )
    }

    @Test
    fun decode_rejectsUriWithoutPrefix() {
        val validUri = InvitationCodec.encode(sampleValidInvitation())
        val invalidUri = validUri.removePrefix("glide://pair/v1#")

        try {
            InvitationCodec.decode(invalidUri)
            fail("Expected InvitationException for missing prefix")
        } catch (e: InvitationException) {
            assertTrue(e.message!!.contains("prefix"))
        }
    }

    @Test
    fun decode_rejectsUriExceedingMaxChars() {
        val longUri = "glide://pair/v1#" + "a".repeat(InvitationCodec.MAX_URI_CHARS)

        try {
            InvitationCodec.decode(longUri)
            fail("Expected InvitationException for oversized URI")
        } catch (e: InvitationException) {
            assertTrue(e.message!!.contains("exceeds maximum"))
        }
    }

    @Test
    fun decode_rejectsBase64Padding() {
        // Base64url with trailing padding '='
        val payloadWithPadding = "glide://pair/v1#eyAiaW52aXRhdGlvbklkIjogIjAwMDAwMDAwLTAwMDAtNDAwMC04MDAwLTAwMDAwMDAwMDAwMSIgfQ=="
        try {
            InvitationCodec.decode(payloadWithPadding)
            fail("Expected InvitationException for padding '='")
        } catch (e: InvitationException) {
            assertTrue(e.message!!.contains("Padding"))
        }
    }

    @Test
    fun decode_rejectsInvalidBase64Characters() {
        val invalidChars = "glide://pair/v1#abc+def/ghi"
        try {
            InvitationCodec.decode(invalidChars)
            fail("Expected InvitationException for invalid base64 characters")
        } catch (e: InvitationException) {
            assertTrue(e.message!!.contains("Invalid characters"))
        }
    }

    @Test
    fun decode_rejectsPayloadExceedingMaxJsonBytes() {
        val bigJson = "{\"v\":1,\"data\":\"" + "x".repeat(2100) + "\"}"
        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(bigJson.toByteArray(StandardCharsets.UTF_8))
        val uri = "glide://pair/v1#$base64"

        try {
            InvitationCodec.decode(uri)
            fail("Expected InvitationException for JSON exceeding 2,048 bytes")
        } catch (e: InvitationException) {
            assertTrue(e.message!!.contains("exceeds maximum"))
        }
    }

    @Test
    fun decode_rejectsMalformedUtf8() {
        // Construct malformed UTF-8 byte sequence
        val badBytes = byteArrayOf(0x7b, 0x22, 0x76, 0x22, 0x3a, 0x31, 0x2c, 0xff.toByte(), 0x7d)
        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(badBytes)

        try {
            InvitationCodec.decode("glide://pair/v1#$base64")
            fail("Expected InvitationException for malformed UTF-8")
        } catch (e: InvitationException) {
            assertTrue(e.message!!.contains("UTF-8"))
        }
    }

    @Test
    fun decode_rejectsNestingDepthGreaterThanFour() {
        // Depth 5 structure
        val deepJson = "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":1}}}}}"
        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(deepJson.toByteArray(StandardCharsets.UTF_8))

        try {
            InvitationCodec.decode("glide://pair/v1#$base64")
            fail("Expected InvitationException for depth > 4")
        } catch (e: InvitationException) {
            assertTrue(e.message!!.contains("depth"))
        }
    }

    @Test
    fun decode_rejectsDuplicateKeys() {
        val duplicateKeyJson = """
            {
              "v": 1,
              "v": 1,
              "transport": "lan-tls",
              "profile": 1,
              "protocol": 1,
              "invitationId": "00000000-0000-4000-8000-000000000001",
              "presence": "00112233445566778899aabbccddeeff",
              "name": "Pixel",
              "platform": "android",
              "endpoints": [{"address": "192.168.1.1", "port": 8080}],
              "spkiSha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
              "token": "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLM_012",
              "expiresAt": "1773729600",
              "ttlSeconds": 120
            }
        """.trimIndent()
        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(duplicateKeyJson.toByteArray(StandardCharsets.UTF_8))

        try {
            InvitationCodec.decode("glide://pair/v1#$base64")
            fail("Expected InvitationException for duplicate key")
        } catch (e: InvitationException) {
            assertTrue(e.message!!.contains("Duplicate key"))
        }
    }

    @Test
    fun decode_rejectsUnknownKeys() {
        val unknownKeyJson = """
            {
              "v": 1,
              "transport": "lan-tls",
              "profile": 1,
              "protocol": 1,
              "invitationId": "00000000-0000-4000-8000-000000000001",
              "presence": "00112233445566778899aabbccddeeff",
              "name": "Pixel",
              "platform": "android",
              "endpoints": [{"address": "192.168.1.1", "port": 8080}],
              "spkiSha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
              "token": "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLM_012",
              "expiresAt": "1773729600",
              "ttlSeconds": 120,
              "unsupportedField": "bad"
            }
        """.trimIndent()
        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(unknownKeyJson.toByteArray(StandardCharsets.UTF_8))

        try {
            InvitationCodec.decode("glide://pair/v1#$base64")
            fail("Expected InvitationException for unknown key")
        } catch (e: InvitationException) {
            assertTrue(e.message!!.contains("Unknown keys"))
        }
    }

    @Test
    fun decode_rejectsMissingRequiredKeys() {
        val missingKeyJson = """
            {
              "v": 1,
              "transport": "lan-tls",
              "profile": 1,
              "protocol": 1,
              "invitationId": "00000000-0000-4000-8000-000000000001"
            }
        """.trimIndent()
        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(missingKeyJson.toByteArray(StandardCharsets.UTF_8))

        try {
            InvitationCodec.decode("glide://pair/v1#$base64")
            fail("Expected InvitationException for missing required keys")
        } catch (e: InvitationException) {
            assertTrue(e.message!!.contains("Missing required keys"))
        }
    }

    @Test
    fun decode_rejectsInvalidPlatform() {
        val json = """
            {
              "v": 1,
              "transport": "lan-tls",
              "profile": 1,
              "protocol": 1,
              "invitationId": "00000000-0000-4000-8000-000000000001",
              "presence": "00112233445566778899aabbccddeeff",
              "name": "Pixel",
              "platform": "toaster_os",
              "endpoints": [{"address": "192.168.1.1", "port": 8080}],
              "spkiSha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
              "token": "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLM_012",
              "expiresAt": "1773729600",
              "ttlSeconds": 120
            }
        """.trimIndent()
        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(StandardCharsets.UTF_8))

        try {
            InvitationCodec.decode("glide://pair/v1#$base64")
            fail("Expected InvitationException for invalid platform")
        } catch (e: InvitationException) {
            assertTrue(e.message!!.contains("platform"))
        }
    }

    @Test
    fun decode_rejectsDuplicateEndpoints() {
        val json = """
            {
              "v": 1,
              "transport": "lan-tls",
              "profile": 1,
              "protocol": 1,
              "invitationId": "00000000-0000-4000-8000-000000000001",
              "presence": "00112233445566778899aabbccddeeff",
              "name": "Pixel",
              "platform": "android",
              "endpoints": [
                {"address": "192.168.1.1", "port": 8080},
                {"address": "192.168.1.1", "port": 8080}
              ],
              "spkiSha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
              "token": "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLM_012",
              "expiresAt": "1773729600",
              "ttlSeconds": 120
            }
        """.trimIndent()
        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(StandardCharsets.UTF_8))

        try {
            InvitationCodec.decode("glide://pair/v1#$base64")
            fail("Expected InvitationException for duplicate endpoints")
        } catch (e: InvitationException) {
            assertTrue(e.message!!.contains("unique"))
        }
    }

    @Test
    fun decode_rejectsHostnamesInEndpoints() {
        val json = """
            {
              "v": 1,
              "transport": "lan-tls",
              "profile": 1,
              "protocol": 1,
              "invitationId": "00000000-0000-4000-8000-000000000001",
              "presence": "00112233445566778899aabbccddeeff",
              "name": "Pixel",
              "platform": "android",
              "endpoints": [{"address": "mydevice.local", "port": 8080}],
              "spkiSha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
              "token": "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLM_012",
              "expiresAt": "1773729600",
              "ttlSeconds": 120
            }
        """.trimIndent()
        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(StandardCharsets.UTF_8))

        try {
            InvitationCodec.decode("glide://pair/v1#$base64")
            fail("Expected InvitationException for hostname in endpoint")
        } catch (e: InvitationException) {
            assertTrue(e.message!!.contains("numeric IPv4 or IPv6"))
        }
    }

    @Test
    fun decode_rejectsOutOfRangePort() {
        val json = """
            {
              "v": 1,
              "transport": "lan-tls",
              "profile": 1,
              "protocol": 1,
              "invitationId": "00000000-0000-4000-8000-000000000001",
              "presence": "00112233445566778899aabbccddeeff",
              "name": "Pixel",
              "platform": "android",
              "endpoints": [{"address": "192.168.1.1", "port": 70000}],
              "spkiSha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
              "token": "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLM_012",
              "expiresAt": "1773729600",
              "ttlSeconds": 120
            }
        """.trimIndent()
        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(StandardCharsets.UTF_8))

        try {
            InvitationCodec.decode("glide://pair/v1#$base64")
            fail("Expected InvitationException for port > 65535")
        } catch (e: InvitationException) {
            assertTrue(e.message!!.contains("port"))
        }
    }

    @Test
    fun decode_rejectsInvalidSpkiSha256() {
        val json = """
            {
              "v": 1,
              "transport": "lan-tls",
              "profile": 1,
              "protocol": 1,
              "invitationId": "00000000-0000-4000-8000-000000000001",
              "presence": "00112233445566778899aabbccddeeff",
              "name": "Pixel",
              "platform": "android",
              "endpoints": [{"address": "192.168.1.1", "port": 8080}],
              "spkiSha256": "invalid_spki_hash_not_64_hex",
              "token": "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLM_012",
              "expiresAt": "1773729600",
              "ttlSeconds": 120
            }
        """.trimIndent()
        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(StandardCharsets.UTF_8))

        try {
            InvitationCodec.decode("glide://pair/v1#$base64")
            fail("Expected InvitationException for non-hex spkiSha256")
        } catch (e: InvitationException) {
            assertTrue(e.message!!.contains("spkiSha256"))
        }
    }

    @Test
    fun decode_rejectsInvalidTokenLength() {
        val json = """
            {
              "v": 1,
              "transport": "lan-tls",
              "profile": 1,
              "protocol": 1,
              "invitationId": "00000000-0000-4000-8000-000000000001",
              "presence": "00112233445566778899aabbccddeeff",
              "name": "Pixel",
              "platform": "android",
              "endpoints": [{"address": "192.168.1.1", "port": 8080}],
              "spkiSha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
              "token": "too_short_token",
              "expiresAt": "1773729600",
              "ttlSeconds": 120
            }
        """.trimIndent()
        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(StandardCharsets.UTF_8))

        try {
            InvitationCodec.decode("glide://pair/v1#$base64")
            fail("Expected InvitationException for token length != 43")
        } catch (e: InvitationException) {
            assertTrue(e.message!!.contains("token"))
        }
    }
}
