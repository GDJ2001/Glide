package dev.glide.core.protocol

import dev.glide.core.domain.MessageId
import dev.glide.core.domain.SessionId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeProtocolTest {

    private val codec = ProtocolCodec
    private val sessionId = SessionId("11111111-1111-4111-8111-111111111111")
    private val validNonce = "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0"
    private val validRootHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun identityChallenge_roundtrip_succeeds() {
        val body = buildJsonObject {
            put("nonceHex", validNonce)
        }
        val envelope = Envelope(
            v = 1,
            type = MessageTypes.IDENTITY_CHALLENGE,
            sessionId = sessionId,
            messageId = MessageId("22222222-2222-4222-8222-222222222222"),
            seq = 1L,
            body = body
        )

        val bytes = codec.encode(envelope)
        val decoded = codec.decode(bytes)

        assertEquals(MessageTypes.IDENTITY_CHALLENGE, decoded.type)
        val challengeDto: IdentityChallengeDto = codec.decodeBody(decoded)
        assertEquals(validNonce, challengeDto.nonceHex)
    }

    @Test
    fun identityResponse_roundtrip_succeeds() {
        val body = buildJsonObject {
            put("signatureHex", "3045022100a1b2c3d4e5")
            put("publicKeyDerHex", "3059301306072a8648ce3d020106082a8648ce3d03010703420004...")
        }
        val envelope = Envelope(
            v = 1,
            type = MessageTypes.IDENTITY_RESPONSE,
            sessionId = sessionId,
            messageId = MessageId("22222222-2222-4222-8222-222222222222"),
            seq = 2L,
            body = body
        )

        val bytes = codec.encode(envelope)
        val decoded = codec.decode(bytes)

        assertEquals(MessageTypes.IDENTITY_RESPONSE, decoded.type)
        val responseDto: IdentityResponseDto = codec.decodeBody(decoded)
        assertEquals("3045022100a1b2c3d4e5", responseDto.signatureHex)
    }

    @Test
    fun resumeRequest_roundtrip_succeeds() {
        val body = buildJsonObject {
            put("sessionId", sessionId.value)
            put("itemId", "item-1")
            put("verifiedChunkCount", 5L)
            put("contiguousByteOffset", 5L * 1024 * 1024)
            put("chunkRootHashHex", validRootHash)
            put("sessionNonceHex", validNonce)
            put("signatureHex", "sig123")
        }
        val envelope = Envelope(
            v = 1,
            type = MessageTypes.RESUME_REQUEST,
            sessionId = sessionId,
            messageId = MessageId("22222222-2222-4222-8222-222222222222"),
            seq = 3L,
            body = body
        )

        val bytes = codec.encode(envelope)
        val decoded = codec.decode(bytes)

        assertEquals(MessageTypes.RESUME_REQUEST, decoded.type)
        val requestDto: ResumeRequestDto = codec.decodeBody(decoded)
        assertEquals(5L, requestDto.verifiedChunkCount)
        assertEquals(validRootHash, requestDto.chunkRootHashHex)
    }

    @Test
    fun resumeAccept_roundtrip_succeeds() {
        val body = buildJsonObject {
            put("sessionId", sessionId.value)
            put("itemId", "item-1")
            put("startChunkIndex", 5L)
            put("startByteOffset", 5L * 1024 * 1024)
        }
        val envelope = Envelope(
            v = 1,
            type = MessageTypes.RESUME_ACCEPT,
            sessionId = sessionId,
            messageId = MessageId("22222222-2222-4222-8222-222222222222"),
            seq = 4L,
            body = body
        )

        val bytes = codec.encode(envelope)
        val decoded = codec.decode(bytes)

        assertEquals(MessageTypes.RESUME_ACCEPT, decoded.type)
        val acceptDto: ResumeAcceptDto = codec.decodeBody(decoded)
        assertEquals(5L, acceptDto.startChunkIndex)
    }

    @Test
    fun resumeReject_roundtrip_succeeds() {
        val body = buildJsonObject {
            put("sessionId", sessionId.value)
            put("itemId", "item-1")
            put("reason", "File modified on sender")
        }
        val envelope = Envelope(
            v = 1,
            type = MessageTypes.RESUME_REJECT,
            sessionId = sessionId,
            messageId = MessageId("22222222-2222-4222-8222-222222222222"),
            seq = 5L,
            body = body
        )

        val bytes = codec.encode(envelope)
        val decoded = codec.decode(bytes)

        assertEquals(MessageTypes.RESUME_REJECT, decoded.type)
        val rejectDto: ResumeRejectDto = codec.decodeBody(decoded)
        assertEquals("File modified on sender", rejectDto.reason)
    }

    @Test(expected = ProtocolViolationException::class)
    fun resumeRequest_invalidRootHash_throwsProtocolViolationException() {
        val body = buildJsonObject {
            put("sessionId", sessionId.value)
            put("itemId", "item-1")
            put("verifiedChunkCount", 5L)
            put("contiguousByteOffset", 5000L)
            put("chunkRootHashHex", "short-hash")
            put("sessionNonceHex", validNonce)
            put("signatureHex", "sig123")
        }
        val envelope = Envelope(
            v = 1,
            type = MessageTypes.RESUME_REQUEST,
            sessionId = sessionId,
            messageId = MessageId("22222222-2222-4222-8222-222222222222"),
            seq = 1L,
            body = body
        )

        val bytes = codec.encode(envelope)
        codec.decode(bytes)
    }
}
