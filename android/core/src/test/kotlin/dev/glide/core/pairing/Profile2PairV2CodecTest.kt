package dev.glide.core.pairing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Profile2PairV2CodecTest {

    private fun findRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).canonicalFile
        while (dir != null) {
            if (File(dir, "AGENTS.md").exists()) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("Could not find repository root containing AGENTS.md")
    }

    @Test
    fun `golden fixtures validate v1 compatibility v2 pair-only invitation and GLIDE-PAIR-V2 transcript`() {
        val fixtureFile = File(findRepoRoot(), "doc/protocol/fixtures/profile2-fixtures.json")
        val root = Json.parseToJsonElement(fixtureFile.readText()).jsonObject

        // 1. V1 compatibility
        val v1 = root.getValue("v1Compatibility").jsonObject
        val v1Uri = v1.getValue("validUri").jsonPrimitive.content
        val decodedV1 = InvitationCodec.decode(v1Uri)
        assertEquals(v1.getValue("expectedInvitationHash").jsonPrimitive.content, decodedV1.invitationHash)
        assertEquals(v1.getValue("expectedInvitationId").jsonPrimitive.content, decodedV1.invitationId)
        assertEquals(v1.getValue("expectedName").jsonPrimitive.content, decodedV1.name)
        assertEquals(v1.getValue("expectedPlatform").jsonPrimitive.content, decodedV1.platform)
        assertEquals(v1.getValue("expectedPresence").jsonPrimitive.content, decodedV1.presence)
        assertEquals(v1.getValue("expectedSpkiSha256").jsonPrimitive.content, decodedV1.spkiSha256)
        assertEquals(v1.getValue("expectedToken").jsonPrimitive.content, decodedV1.token)

        val roundTripV1 = InvitationCodec.decode(InvitationCodec.encode(decodedV1))
        assertEquals(decodedV1.invitationId, roundTripV1.invitationId)
        assertEquals(decodedV1.token, roundTripV1.token)

        // 2. V2 pair-only invitation
        val v2 = root.getValue("v2PairOnlyInvitation").jsonObject
        val v2Uri = v2.getValue("validUri").jsonPrimitive.content
        val decodedV2 = InvitationCodec.decodePairV2(v2Uri)
        assertEquals(v2.getValue("expectedInvitationHash").jsonPrimitive.content, decodedV2.invitationHash)
        assertEquals(v2.getValue("expectedInvitationId").jsonPrimitive.content, decodedV2.invitationId)
        assertEquals(v2.getValue("expectedPurpose").jsonPrimitive.content, decodedV2.purpose)
        assertEquals(v2.getValue("expectedName").jsonPrimitive.content, decodedV2.name)
        assertEquals(v2.getValue("expectedPlatform").jsonPrimitive.content, decodedV2.platform)
        assertEquals(v2.getValue("expectedPresence").jsonPrimitive.content, decodedV2.presence)
        assertEquals(v2.getValue("expectedSpkiSha256").jsonPrimitive.content, decodedV2.spkiSha256)
        assertEquals(v2.getValue("expectedToken").jsonPrimitive.content, decodedV2.token)

        val roundTripV2 = InvitationCodec.decodePairV2(InvitationCodec.encodePairV2(decodedV2))
        assertEquals(decodedV2.invitationId, roundTripV2.invitationId)
        assertEquals(decodedV2.purpose, roundTripV2.purpose)
        assertEquals(decodedV2.token, roundTripV2.token)

        // Ensure v1 decode surfaces "Update Glide to pair this device" when given a v2 URI
        try {
            InvitationCodec.decode(v2Uri)
            fail("Expected InvitationException when decoding v2 URI with v1 decoder")
        } catch (e: InvitationException) {
            assertEquals("Update Glide to pair this device", e.message)
        }

        // Ensure decodeAny routes both v1 and v2 accurately
        assertTrue(InvitationCodec.decodeAny(v1Uri) is ParsedGlideInvitation.TransferV1)
        assertTrue(InvitationCodec.decodeAny(v2Uri) is ParsedGlideInvitation.PairOnlyV2)

        // 3. Invalid v2 cases
        for (invalid in v2.getValue("invalidCases").jsonArray) {
            val badUri = invalid.jsonObject.getValue("uri").jsonPrimitive.content
            try {
                InvitationCodec.decodePairV2(badUri)
                fail("Expected InvitationException for invalid v2 case: $badUri")
            } catch (_: InvitationException) {
                // Expected
            }
        }

        // 4. GLIDE-PAIR-V2 binary transcript fixture
        val tx = root.getValue("pairV2Transcript").jsonObject
        val transcript = Profile2Crypto.buildPairV2TranscriptBytes(
            method = tx.getValue("method").jsonPrimitive.content,
            sessionId = tx.getValue("sessionId").jsonPrimitive.content,
            initiatorNonceHex = tx.getValue("initiatorNonceHex").jsonPrimitive.content,
            responderNonceHex = tx.getValue("responderNonceHex").jsonPrimitive.content,
            initiatorFingerprintHex = tx.getValue("initiatorFingerprintHex").jsonPrimitive.content,
            initiatorPublicKeyDerHex = tx.getValue("initiatorPublicKeyDerHex").jsonPrimitive.content,
            responderFingerprintHex = tx.getValue("responderFingerprintHex").jsonPrimitive.content,
            responderPublicKeyDerHex = tx.getValue("responderPublicKeyDerHex").jsonPrimitive.content,
            responderPresenceHex = tx.getValue("responderPresenceHex").jsonPrimitive.content,
            tlsServerSpkiSha256Hex = tx.getValue("tlsServerSpkiSha256Hex").jsonPrimitive.content,
            purpose = tx.getValue("purpose").jsonPrimitive.content
        )

        assertEquals(tx.getValue("expectedTranscriptLength").jsonPrimitive.int, transcript.size)
        assertEquals(
            tx.getValue("expectedTranscriptSha256Hex").jsonPrimitive.content,
            Profile2Crypto.sha256Hex(transcript)
        )
    }
}
