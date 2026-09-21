package dev.glide.core.protocol

import dev.glide.core.domain.MessageId
import dev.glide.core.domain.SessionId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ResumeProtocolTest {

    private val codec = ProtocolCodec
    private val sessionId =
        SessionId("11111111-1111-4111-8111-111111111111")
    private val challengeMessageId =
        "22222222-2222-4222-8222-222222222222"
    private val originalSessionId =
        "33333333-3333-4333-8333-333333333333"
    private val itemId =
        "44444444-4444-4444-8444-444444444444"
    private val requestMessageId =
        "55555555-5555-4555-8555-555555555555"

    private val signerFingerprint =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val verifierFingerprint =
        "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd"
    private val validNonce =
        "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    private val channelBinding =
        "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100"
    private val capabilityHash =
        "992a66ad3dccb3c5190e046f92b6190751644f1f98946ac6511350952d333b47"
    private val validRootHash =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun identityChallenge_roundtrip_succeeds() {
        val body = buildJsonObject {
            put("profile", IdentityResumeTranscript.IDENTITY_PROFILE)
            put("challengeNonceHex", validNonce)
            put("verifierFingerprintHex", verifierFingerprint)
            put("verifierRole", "receiver")
            put("signerRole", "sender")
            put("channelBindingHex", channelBinding)
            put("capabilityHashHex", capabilityHash)
        }
        val envelope = Envelope(
            v = 1,
            type = MessageTypes.IDENTITY_CHALLENGE,
            sessionId = sessionId,
            messageId = MessageId(challengeMessageId),
            seq = 1L,
            body = body
        )

        val decoded = codec.decode(codec.encode(envelope))
        val challengeDto: IdentityChallengeDto =
            codec.decodeBody(decoded)

        assertEquals(MessageTypes.IDENTITY_CHALLENGE, decoded.type)
        assertEquals(validNonce, challengeDto.challengeNonceHex)
        assertEquals("receiver", challengeDto.verifierRole)
        assertEquals("sender", challengeDto.signerRole)
    }

    @Test
    fun identityResponse_roundtrip_succeeds() {
        val body = buildJsonObject {
            put("profile", IdentityResumeTranscript.IDENTITY_PROFILE)
            put("challengeMessageId", challengeMessageId)
            put("challengeNonceHex", validNonce)
            put("signerFingerprintHex", signerFingerprint)
            put("publicKeyDerHex", "3059")
            put("signatureHex", "30440220")
        }
        val envelope = Envelope(
            v = 1,
            type = MessageTypes.IDENTITY_RESPONSE,
            sessionId = sessionId,
            messageId =
                MessageId("66666666-6666-4666-8666-666666666666"),
            seq = 2L,
            body = body
        )

        val decoded = codec.decode(codec.encode(envelope))
        val responseDto: IdentityResponseDto =
            codec.decodeBody(decoded)

        assertEquals(MessageTypes.IDENTITY_RESPONSE, decoded.type)
        assertEquals(challengeMessageId, responseDto.challengeMessageId)
        assertEquals(signerFingerprint, responseDto.signerFingerprintHex)
    }

    @Test
    fun resumeRequest_roundtrip_succeeds() {
        val body = validResumeRequestBody()
        val envelope = Envelope(
            v = 1,
            type = MessageTypes.RESUME_REQUEST,
            sessionId = sessionId,
            messageId = MessageId(requestMessageId),
            seq = 3L,
            body = body
        )

        val decoded = codec.decode(codec.encode(envelope))
        val requestDto: ResumeRequestDto =
            codec.decodeBody(decoded)

        assertEquals(MessageTypes.RESUME_REQUEST, decoded.type)
        assertEquals(2L, requestDto.verifiedChunkCount)
        assertEquals("2097152", requestDto.contiguousByteOffset)
        assertEquals(1_048_576, requestDto.chunkSize)
        assertEquals(validRootHash, requestDto.chunkRootHashHex)
    }

    @Test
    fun resumeAccept_roundtrip_succeeds() {
        val body = buildJsonObject {
            put("profile", IdentityResumeTranscript.RESUME_PROFILE)
            put("requestMessageId", requestMessageId)
            put("originalSessionId", originalSessionId)
            put("itemId", itemId)
            put("startChunkIndex", 2L)
            put("startByteOffset", "2097152")
        }
        val envelope = Envelope(
            v = 1,
            type = MessageTypes.RESUME_ACCEPT,
            sessionId = sessionId,
            messageId =
                MessageId("77777777-7777-4777-8777-777777777777"),
            seq = 4L,
            body = body
        )

        val decoded = codec.decode(codec.encode(envelope))
        val acceptDto: ResumeAcceptDto =
            codec.decodeBody(decoded)

        assertEquals(MessageTypes.RESUME_ACCEPT, decoded.type)
        assertEquals(2L, acceptDto.startChunkIndex)
        assertEquals("2097152", acceptDto.startByteOffset)
    }

    @Test
    fun resumeReject_roundtrip_succeeds() {
        val body = buildJsonObject {
            put("profile", IdentityResumeTranscript.RESUME_PROFILE)
            put("requestMessageId", requestMessageId)
            put("originalSessionId", originalSessionId)
            put("itemId", itemId)
            put("code", ResumeRejectCodes.FILE_CHANGED)
        }
        val envelope = Envelope(
            v = 1,
            type = MessageTypes.RESUME_REJECT,
            sessionId = sessionId,
            messageId =
                MessageId("88888888-8888-4888-8888-888888888888"),
            seq = 5L,
            body = body
        )

        val decoded = codec.decode(codec.encode(envelope))
        val rejectDto: ResumeRejectDto =
            codec.decodeBody(decoded)

        assertEquals(MessageTypes.RESUME_REJECT, decoded.type)
        assertEquals(ResumeRejectCodes.FILE_CHANGED, rejectDto.code)
    }

    @Test
    fun resumeStreamStart_roundtrip_succeeds() {
        val body =
            buildJsonObject {
                put(
                    "profile",
                    IdentityResumeTranscript
                        .RESUME_PROFILE
                )
                put(
                    "requestMessageId",
                    requestMessageId
                )
                put(
                    "originalSessionId",
                    originalSessionId
                )
                put("itemId", itemId)
                put(
                    "bulkRef",
                    "resume-bulk-1"
                )
                put(
                    "startChunkIndex",
                    2L
                )
                put(
                    "startByteOffset",
                    "2097152"
                )
                put(
                    "remainingBytes",
                    "3145728"
                )
                put(
                    "chunkSize",
                    1_048_576
                )
                put(
                    "totalSizeBytes",
                    "5242880"
                )
                put(
                    "chunkRootHashHex",
                    validRootHash
                )
                put(
                    "wholeFileSha256Hex",
                    signerFingerprint
                )
            }

        val decoded =
            codec.decode(
                codec.encode(
                    Envelope(
                        1,
                        MessageTypes
                            .RESUME_STREAM_START,
                        sessionId,
                        MessageId(
                            "99999999-9999-4999-8999-999999999999"
                        ),
                        6L,
                        body
                    )
                )
            )
        val dto:
            ResumeStreamStartDto =
            codec.decodeBody(decoded)

        assertEquals(
            2L,
            dto.startChunkIndex
        )
        assertEquals(
            "3145728",
            dto.remainingBytes
        )
        assertEquals(
            signerFingerprint,
            dto.wholeFileSha256Hex
        )
    }

    @Test(
        expected =
            ProtocolViolationException::class
    )
    fun resumeStreamStart_incorrectRemainingBytes_isRejected() {
        val body =
            buildJsonObject {
                put(
                    "profile",
                    IdentityResumeTranscript
                        .RESUME_PROFILE
                )
                put(
                    "requestMessageId",
                    requestMessageId
                )
                put(
                    "originalSessionId",
                    originalSessionId
                )
                put("itemId", itemId)
                put(
                    "bulkRef",
                    "resume-bulk-2"
                )
                put(
                    "startChunkIndex",
                    2L
                )
                put(
                    "startByteOffset",
                    "2097152"
                )
                put(
                    "remainingBytes",
                    "1"
                )
                put(
                    "chunkSize",
                    1_048_576
                )
                put(
                    "totalSizeBytes",
                    "5242880"
                )
                put(
                    "chunkRootHashHex",
                    validRootHash
                )
                put(
                    "wholeFileSha256Hex",
                    signerFingerprint
                )
            }

        codec.decode(
            codec.encode(
                Envelope(
                    1,
                    MessageTypes
                        .RESUME_STREAM_START,
                    sessionId,
                    MessageId(
                        "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
                    ),
                    6L,
                    body
                )
            )
        )
    }

    @Test
    fun resumeStreamReady_andVerifiedResult_roundtrip() {
        val readyBody =
            buildJsonObject {
                put(
                    "profile",
                    IdentityResumeTranscript
                        .RESUME_PROFILE
                )
                put(
                    "requestMessageId",
                    requestMessageId
                )
                put(
                    "originalSessionId",
                    originalSessionId
                )
                put("itemId", itemId)
                put(
                    "bulkRef",
                    "resume-bulk-3"
                )
            }
        val ready =
            codec.decode(
                codec.encode(
                    Envelope(
                        1,
                        MessageTypes
                            .RESUME_STREAM_READY,
                        sessionId,
                        MessageId(
                            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
                        ),
                        7L,
                        readyBody
                    )
                )
            )
        val readyDto:
            ResumeStreamReadyDto =
            codec.decodeBody(ready)
        assertEquals(
            "resume-bulk-3",
            readyDto.bulkRef
        )

        val resultBody =
            buildJsonObject {
                put(
                    "profile",
                    IdentityResumeTranscript
                        .RESUME_PROFILE
                )
                put(
                    "requestMessageId",
                    requestMessageId
                )
                put(
                    "originalSessionId",
                    originalSessionId
                )
                put("itemId", itemId)
                put(
                    "status",
                    "verified"
                )
                put(
                    "sizeBytes",
                    "5242880"
                )
                put(
                    "sha256",
                    signerFingerprint
                )
            }
        val result =
            codec.decode(
                codec.encode(
                    Envelope(
                        1,
                        MessageTypes
                            .RESUME_RESULT,
                        sessionId,
                        MessageId(
                            "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
                        ),
                        8L,
                        resultBody
                    )
                )
            )
        val resultDto:
            ResumeResultDto =
            codec.decodeBody(result)
        assertEquals(
            "verified",
            resultDto.status
        )
        assertEquals(
            signerFingerprint,
            resultDto.sha256
        )
    }

    @Test(expected = ProtocolViolationException::class)
    fun resumeRequest_invalidRootHash_throwsProtocolViolationException() {
        val invalid = buildJsonObject {
            validResumeRequestBody().forEach { (key, value) ->
                put(key, value)
            }
            put("chunkRootHashHex", "short-hash")
        }
        codec.decode(
            codec.encode(
                Envelope(
                    1,
                    MessageTypes.RESUME_REQUEST,
                    sessionId,
                    MessageId(requestMessageId),
                    1L,
                    invalid
                )
            )
        )
    }

    @Test(expected = ProtocolViolationException::class)
    fun resumeRequest_mismatchedChunkCount_throwsProtocolViolationException() {
        val invalid = buildJsonObject {
            validResumeRequestBody().forEach { (key, value) ->
                put(key, value)
            }
            put("verifiedChunkCount", 3L)
        }
        codec.decode(
            codec.encode(
                Envelope(
                    1,
                    MessageTypes.RESUME_REQUEST,
                    sessionId,
                    MessageId(requestMessageId),
                    1L,
                    invalid
                )
            )
        )
    }

    @Test(expected = ProtocolViolationException::class)
    fun identityChallenge_uppercaseFingerprint_isRejected() {
        val body = buildJsonObject {
            put("profile", IdentityResumeTranscript.IDENTITY_PROFILE)
            put("challengeNonceHex", validNonce)
            put(
                "verifierFingerprintHex",
                verifierFingerprint.uppercase()
            )
            put("verifierRole", "receiver")
            put("signerRole", "sender")
            put("channelBindingHex", channelBinding)
            put("capabilityHashHex", capabilityHash)
        }

        codec.decode(
            codec.encode(
                Envelope(
                    1,
                    MessageTypes.IDENTITY_CHALLENGE,
                    sessionId,
                    MessageId(challengeMessageId),
                    1L,
                    body
                )
            )
        )
    }

    @Test(expected = ProtocolViolationException::class)
    fun identityChallenge_wrongProfile_isRejected() {
        val body = buildJsonObject {
            put("profile", "identity-v2")
            put("challengeNonceHex", validNonce)
            put("verifierFingerprintHex", verifierFingerprint)
            put("verifierRole", "receiver")
            put("signerRole", "sender")
            put("channelBindingHex", channelBinding)
            put("capabilityHashHex", capabilityHash)
        }

        codec.decode(
            codec.encode(
                Envelope(
                    1,
                    MessageTypes.IDENTITY_CHALLENGE,
                    sessionId,
                    MessageId(challengeMessageId),
                    1L,
                    body
                )
            )
        )
    }

    private fun validResumeRequestBody() = buildJsonObject {
        put("profile", IdentityResumeTranscript.RESUME_PROFILE)
        put("originalSessionId", originalSessionId)
        put("itemId", itemId)
        put("requesterFingerprintHex", verifierFingerprint)
        put("responderFingerprintHex", signerFingerprint)
        put("requesterRole", "receiver")
        put("responderRole", "sender")
        put("verifiedChunkCount", 2L)
        put("contiguousByteOffset", "2097152")
        put("chunkSize", 1_048_576)
        put("totalSizeBytes", "5242880")
        put("chunkRootHashHex", validRootHash)
        put("sessionNonceHex", validNonce)
        put("channelBindingHex", channelBinding)
        put("capabilityHashHex", capabilityHash)
        put("signatureHex", "30440220")
    }
}
