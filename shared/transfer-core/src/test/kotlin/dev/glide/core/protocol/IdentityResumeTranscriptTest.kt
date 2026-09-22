package dev.glide.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class IdentityResumeTranscriptTest {

    private val fixture: JsonObject by lazy {
        val file = locateFixture()
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    @Test
    fun capabilityVector_matchesSharedFixture() {
        val vector =
            fixture["capabilityVector"]!!.jsonObject
        val capabilities =
            vector["capabilities"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }

        val canonical =
            IdentityResumeTranscript
                .capabilityCanonicalBytes(capabilities)

        assertEquals(
            vector["canonicalHex"]!!.jsonPrimitive.content,
            canonical.toHex()
        )
        assertEquals(
            vector["sha256Hex"]!!.jsonPrimitive.content,
            IdentityResumeTranscript
                .capabilityHashHex(capabilities)
        )
    }

    @Test
    fun identityVector_matchesSharedFixtureByteForByte() {
        val vector =
            fixture["identityVector"]!!.jsonObject

        val transcript =
            IdentityResumeTranscript.identityProofBytes(
                activeSessionId = value(vector, "activeSessionId"),
                challengeMessageId = value(vector, "challengeMessageId"),
                signerRole = value(vector, "signerRole"),
                verifierRole = value(vector, "verifierRole"),
                signerFingerprintHex =
                    value(vector, "signerFingerprintHex"),
                verifierFingerprintHex =
                    value(vector, "verifierFingerprintHex"),
                challengeNonceHex =
                    value(vector, "challengeNonceHex"),
                channelBindingHex =
                    value(vector, "channelBindingHex"),
                capabilityHashHex =
                    value(vector, "capabilityHashHex")
            )

        assertEquals(
            value(vector, "transcriptHex"),
            transcript.toHex()
        )
        assertEquals(
            value(vector, "transcriptSha256Hex"),
            IdentityResumeTranscript
                .sha256Hex(transcript)
        )
    }

    @Test
    fun resumeVector_matchesSharedFixtureByteForByte() {
        val vector =
            fixture["resumeVector"]!!.jsonObject

        val transcript =
            IdentityResumeTranscript.resumeRequestBytes(
                activeSessionId =
                    value(vector, "activeSessionId"),
                originalSessionId =
                    value(vector, "originalSessionId"),
                itemId = value(vector, "itemId"),
                requesterRole =
                    value(vector, "requesterRole"),
                responderRole =
                    value(vector, "responderRole"),
                requesterFingerprintHex =
                    value(vector, "requesterFingerprintHex"),
                responderFingerprintHex =
                    value(vector, "responderFingerprintHex"),
                sessionNonceHex =
                    value(vector, "sessionNonceHex"),
                channelBindingHex =
                    value(vector, "channelBindingHex"),
                capabilityHashHex =
                    value(vector, "capabilityHashHex"),
                chunkSize =
                    vector["chunkSize"]!!
                        .jsonPrimitive
                        .content
                        .toInt(),
                totalSizeBytes =
                    vector["totalSizeBytes"]!!
                        .jsonPrimitive
                        .content
                        .toLong(),
                verifiedChunkCount =
                    vector["verifiedChunkCount"]!!
                        .jsonPrimitive
                        .content
                        .toLong(),
                contiguousByteOffset =
                    vector["contiguousByteOffset"]!!
                        .jsonPrimitive
                        .content
                        .toLong(),
                chunkRootHashHex =
                    value(vector, "chunkRootHashHex")
            )

        assertEquals(
            value(vector, "transcriptHex"),
            transcript.toHex()
        )
        assertEquals(
            value(vector, "transcriptSha256Hex"),
            IdentityResumeTranscript
                .sha256Hex(transcript)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun resumeVector_roleSwap_isRejected() {
        val vector =
            fixture["resumeVector"]!!.jsonObject

        IdentityResumeTranscript.resumeRequestBytes(
            activeSessionId =
                value(vector, "activeSessionId"),
            originalSessionId =
                value(vector, "originalSessionId"),
            itemId = value(vector, "itemId"),
            requesterRole = "sender",
            responderRole = "sender",
            requesterFingerprintHex =
                value(vector, "requesterFingerprintHex"),
            responderFingerprintHex =
                value(vector, "responderFingerprintHex"),
            sessionNonceHex =
                value(vector, "sessionNonceHex"),
            channelBindingHex =
                value(vector, "channelBindingHex"),
            capabilityHashHex =
                value(vector, "capabilityHashHex"),
            chunkSize = 1_048_576,
            totalSizeBytes = 5_242_880,
            verifiedChunkCount = 2,
            contiguousByteOffset = 2_097_152,
            chunkRootHashHex =
                value(vector, "chunkRootHashHex")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun identityVector_zeroChannelBinding_isRejected() {
        val vector = fixture["identityVector"]!!.jsonObject

        IdentityResumeTranscript.identityProofBytes(
            activeSessionId = value(vector, "activeSessionId"),
            challengeMessageId = value(vector, "challengeMessageId"),
            signerRole = value(vector, "signerRole"),
            verifierRole = value(vector, "verifierRole"),
            signerFingerprintHex = value(vector, "signerFingerprintHex"),
            verifierFingerprintHex = value(vector, "verifierFingerprintHex"),
            challengeNonceHex = value(vector, "challengeNonceHex"),
            channelBindingHex = "0".repeat(64),
            capabilityHashHex = value(vector, "capabilityHashHex")
        )
    }
    private fun value(
        objectValue: JsonObject,
        key: String
    ): String =
        objectValue[key]!!
            .jsonPrimitive
            .content

    private fun locateFixture(): File {
        var current =
            File(
                System.getProperty("user.dir")
                    ?: "."
            ).canonicalFile

        while (true) {
            val candidate =
                File(
                    current,
                    "protocol/fixtures/identity_resume_v1_vectors.json"
                )
            if (candidate.isFile) {
                return candidate
            }

            current =
                current.parentFile
                    ?: error(
                        "Could not locate identity_resume_v1_vectors.json"
                    )
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") {
            "%02x".format(it)
        }
}
