package dev.glide.core.transport.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets

class LanBootstrapCodecTest {

    companion object {
        private const val VALID_INVITATION_ID = "00000000-0000-4000-8000-000000000001"
        private const val VALID_SESSION_ID = "00000000-0000-4000-8000-000000000002"
        private const val VALID_TOKEN = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"
        private const val VALID_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }

    @Test
    fun testBootstrapMessage_canonicalRoundTrip() {
        val original = LanBootstrapMessage(
            v = 1,
            invitationId = VALID_INVITATION_ID,
            token = VALID_TOKEN,
            invitationHash = VALID_HASH,
            sessionId = VALID_SESSION_ID,
            role = "sender"
        )

        val encoded = LanBootstrapCodec.encodeBootstrap(original)
        assertTrue(encoded.size <= LanBootstrapCodec.MAX_BOOTSTRAP_JSON_BYTES)

        val decoded = LanBootstrapCodec.decodeBootstrap(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun testBootstrapMessage_tokenRedactedInToString() {
        val msg = LanBootstrapMessage(
            v = 1,
            invitationId = VALID_INVITATION_ID,
            token = VALID_TOKEN,
            invitationHash = VALID_HASH,
            sessionId = VALID_SESSION_ID,
            role = "sender"
        )

        val str = msg.toString()
        assertFalse("Token must not appear in toString()", str.contains(VALID_TOKEN))
        assertTrue("ToString must show [REDACTED]", str.contains("[REDACTED]"))
    }

    @Test
    fun testBootstrapResultAccepted_canonicalRoundTrip() {
        val result = LanBootstrapResult.accepted(VALID_SESSION_ID)
        val encoded = LanBootstrapCodec.encodeResult(result)
        assertTrue(encoded.size <= LanBootstrapCodec.MAX_RESULT_JSON_BYTES)

        val decoded = LanBootstrapCodec.decodeResult(encoded)
        assertEquals(result, decoded)
        assertEquals("accepted", decoded.status)
        assertEquals(VALID_SESSION_ID, decoded.sessionId)
        assertNull(decoded.code)
        assertTrue(decoded.isAccepted)
    }

    @Test
    fun testBootstrapResultRejected_allCatalogCodes() {
        for (code in LanBootstrapFailureCode.entries) {
            val result = LanBootstrapResult.rejected(code)
            val encoded = LanBootstrapCodec.encodeResult(result)
            val decoded = LanBootstrapCodec.decodeResult(encoded)

            assertEquals(result, decoded)
            assertEquals("rejected", decoded.status)
            assertEquals(code.name, decoded.code)
            assertNull(decoded.sessionId)
            assertFalse(decoded.isAccepted)
        }
    }

    @Test
    fun testBootstrapMessage_rejectsUnknownKeys() {
        val json = """{"v":1,"invitationId":"$VALID_INVITATION_ID","token":"$VALID_TOKEN","invitationHash":"$VALID_HASH","sessionId":"$VALID_SESSION_ID","role":"sender","extra":"evil"}"""
        try {
            LanBootstrapCodec.decodeBootstrap(json.toByteArray(StandardCharsets.UTF_8))
            fail("Expected rejection for unknown key")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Unknown keys") == true)
        }
    }

    @Test
    fun testBootstrapResult_rejectsUnknownKeys() {
        val json = """{"v":1,"status":"accepted","sessionId":"$VALID_SESSION_ID","extra":"evil"}"""
        try {
            LanBootstrapCodec.decodeResult(json.toByteArray(StandardCharsets.UTF_8))
            fail("Expected rejection for unknown key")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Unknown keys") == true)
        }
    }

    @Test
    fun testBootstrapMessage_rejectsInvalidVersion() {
        val json = """{"v":2,"invitationId":"$VALID_INVITATION_ID","token":"$VALID_TOKEN","invitationHash":"$VALID_HASH","sessionId":"$VALID_SESSION_ID","role":"sender"}"""
        try {
            LanBootstrapCodec.decodeBootstrap(json.toByteArray(StandardCharsets.UTF_8))
            fail("Expected rejection for version != 1")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Unsupported bootstrap version") == true)
        }
    }

    @Test
    fun testBootstrapMessage_acceptsReceiverRoleForSenderHostedPairing() {
        val json = """{"v":1,"invitationId":"$VALID_INVITATION_ID","token":"$VALID_TOKEN","invitationHash":"$VALID_HASH","sessionId":"$VALID_SESSION_ID","role":"receiver"}"""
        val decoded = LanBootstrapCodec.decodeBootstrap(json.toByteArray(StandardCharsets.UTF_8))
        assertEquals(LanBootstrapMessage.ROLE_RECEIVER, decoded.role)
    }

    @Test
    fun testBootstrapMessage_rejectsUnknownRole() {
        val json = """{"v":1,"invitationId":"$VALID_INVITATION_ID","token":"$VALID_TOKEN","invitationHash":"$VALID_HASH","sessionId":"$VALID_SESSION_ID","role":"observer"}"""
        try {
            LanBootstrapCodec.decodeBootstrap(json.toByteArray(StandardCharsets.UTF_8))
            fail("Expected rejection for unknown role")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("role must be") == true)
        }
    }

    @Test
    fun testBootstrapMessage_rejectsInvalidToken() {
        val shortToken = "0123456789"
        val json = """{"v":1,"invitationId":"$VALID_INVITATION_ID","token":"$shortToken","invitationHash":"$VALID_HASH","sessionId":"$VALID_SESSION_ID","role":"sender"}"""
        try {
            LanBootstrapCodec.decodeBootstrap(json.toByteArray(StandardCharsets.UTF_8))
            fail("Expected rejection for token length != 43")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("token must be exactly 43") == true)
        }
    }

    @Test
    fun testBootstrapMessage_rejectsInvalidInvitationHash() {
        val shortHash = "0123456789abcdef"
        val json = """{"v":1,"invitationId":"$VALID_INVITATION_ID","token":"$VALID_TOKEN","invitationHash":"$shortHash","sessionId":"$VALID_SESSION_ID","role":"sender"}"""
        try {
            LanBootstrapCodec.decodeBootstrap(json.toByteArray(StandardCharsets.UTF_8))
            fail("Expected rejection for non-64-char hash")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("invitationHash") == true)
        }
    }

    @Test
    fun testBootstrapResult_rejectsInvalidCode() {
        val json = """{"v":1,"status":"rejected","code":"INVALID_CODE_XYZ"}"""
        try {
            LanBootstrapCodec.decodeResult(json.toByteArray(StandardCharsets.UTF_8))
            fail("Expected rejection for invalid failure code")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Invalid rejection code") == true)
        }
    }

    @Test
    fun testBootstrapMessage_rejectsExceedingMaxBytes() {
        val padding = "a".repeat(3000)
        val json = """{"v":1,"invitationId":"$VALID_INVITATION_ID","token":"$VALID_TOKEN","invitationHash":"$VALID_HASH","sessionId":"$VALID_SESSION_ID","role":"sender","$padding":1}"""
        try {
            LanBootstrapCodec.decodeBootstrap(json.toByteArray(StandardCharsets.UTF_8))
            fail("Expected rejection for exceeding max bytes")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("exceeds 2048") == true)
        }
    }
}
