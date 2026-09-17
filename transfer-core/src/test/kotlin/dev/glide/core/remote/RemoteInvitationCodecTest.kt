package dev.glide.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

class RemoteInvitationCodecTest {

    private val validRendezvousId = "8a32a67e-071a-4d23-92b4-3ec9fef39db3"
    private val validHexKey = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val validSignaling = "wss://relay.glide.app/v1/signal"
    private val validToken = "eyJhbGciOiJub25lIn0"

    @Test
    fun roundTrip_preservesAllFields() {
        val futureExp = Instant.now().epochSecond + 100
        val original = RemoteInvitation(
            rendezvousId = validRendezvousId,
            ephemeralPublicKeyHex = validHexKey,
            signalingEndpoint = validSignaling,
            relayBlindToken = validToken,
            expiresAt = futureExp,
            ttlSeconds = 120
        )

        val encodedUri = RemoteInvitationCodec.encode(original)

        assertTrue(encodedUri.startsWith("glide://remote/v1#"))
        assertTrue(encodedUri.contains("id=$validRendezvousId"))
        assertTrue(encodedUri.contains("key=$validHexKey"))
        assertTrue(encodedUri.contains("token=$validToken"))
        assertTrue(encodedUri.contains("exp=$futureExp"))

        val decoded = RemoteInvitationCodec.decode(encodedUri, checkExpiry = true)

        assertEquals(original.rendezvousId, decoded.rendezvousId)
        assertEquals(original.ephemeralPublicKeyHex, decoded.ephemeralPublicKeyHex)
        assertEquals(original.signalingEndpoint, decoded.signalingEndpoint)
        assertEquals(original.relayBlindToken, decoded.relayBlindToken)
        assertEquals(original.expiresAt, decoded.expiresAt)
        assertFalse(decoded.isExpired())
    }

    @Test(expected = RemoteInvitationException::class)
    fun encode_invalidRendezvousId_throws() {
        val inv = RemoteInvitation(
            rendezvousId = "not-a-valid-uuid",
            ephemeralPublicKeyHex = validHexKey,
            signalingEndpoint = validSignaling,
            relayBlindToken = validToken,
            expiresAt = Instant.now().epochSecond + 60
        )
        RemoteInvitationCodec.encode(inv)
    }

    @Test(expected = RemoteInvitationException::class)
    fun encode_invalidHexKey_throws() {
        val inv = RemoteInvitation(
            rendezvousId = validRendezvousId,
            ephemeralPublicKeyHex = "too-short",
            signalingEndpoint = validSignaling,
            relayBlindToken = validToken,
            expiresAt = Instant.now().epochSecond + 60
        )
        RemoteInvitationCodec.encode(inv)
    }

    @Test(expected = RemoteInvitationException::class)
    fun encode_invalidSignalingUri_throws() {
        val inv = RemoteInvitation(
            rendezvousId = validRendezvousId,
            ephemeralPublicKeyHex = validHexKey,
            signalingEndpoint = "http://insecure.endpoint",
            relayBlindToken = validToken,
            expiresAt = Instant.now().epochSecond + 60
        )
        RemoteInvitationCodec.encode(inv)
    }

    @Test
    fun decode_expiredUri_throwsWhenCheckEnabled() {
        val pastExp = Instant.now().epochSecond - 10
        val uri = "glide://remote/v1#id=$validRendezvousId&key=$validHexKey&sig=${URLEncoder.encode(validSignaling, StandardCharsets.UTF_8.name())}&token=$validToken&exp=$pastExp"

        try {
            RemoteInvitationCodec.decode(uri, checkExpiry = true)
            fail("Expected RemoteInvitationException for expired URI")
        } catch (expected: RemoteInvitationException) {
            // expected
        }

        val decoded = RemoteInvitationCodec.decode(uri, checkExpiry = false)
        assertNotNull(decoded)
        assertTrue(decoded.isExpired())
    }

    @Test(expected = RemoteInvitationException::class)
    fun decode_unknownParameter_throws() {
        val exp = Instant.now().epochSecond + 60
        val uri = "glide://remote/v1#id=$validRendezvousId&key=$validHexKey&sig=${URLEncoder.encode(validSignaling, StandardCharsets.UTF_8.name())}&token=$validToken&exp=$exp&evil=true"
        RemoteInvitationCodec.decode(uri)
    }

    @Test(expected = RemoteInvitationException::class)
    fun decode_duplicateParameter_throws() {
        val exp = Instant.now().epochSecond + 60
        val uri = "glide://remote/v1#id=$validRendezvousId&id=$validRendezvousId&key=$validHexKey&sig=${URLEncoder.encode(validSignaling, StandardCharsets.UTF_8.name())}&token=$validToken&exp=$exp"
        RemoteInvitationCodec.decode(uri)
    }

    @Test(expected = RemoteInvitationException::class)
    fun decode_missingParameter_throws() {
        val uri = "glide://remote/v1#id=$validRendezvousId&key=$validHexKey&token=$validToken&exp=1700000000"
        RemoteInvitationCodec.decode(uri)
    }

    @Test(expected = RemoteInvitationException::class)
    fun decode_oversizedUri_throws() {
        val padding = "a".repeat(2500)
        val uri = "glide://remote/v1#id=$validRendezvousId&key=$validHexKey&sig=${URLEncoder.encode(validSignaling, StandardCharsets.UTF_8.name())}&token=$padding&exp=1700000000"
        RemoteInvitationCodec.decode(uri)
    }
}
