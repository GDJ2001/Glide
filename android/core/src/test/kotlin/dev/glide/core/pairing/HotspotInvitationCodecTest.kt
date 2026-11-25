package dev.glide.core.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.util.Base64

class HotspotInvitationCodecTest {
    @Test
    fun wrapsAnOrdinaryPinnedInvitationAndRejectsExpiry() {
        val expiry = Instant.parse("2030-01-01T00:00:00Z")
        val transfer = LanInvitation(
            invitationId = "a116375a-2bc8-40c7-98aa-8799ab2367de",
            presence = "aa".repeat(16),
            name = "Demo peer",
            platform = "android",
            endpoints = listOf(LanEndpoint("192.168.49.1", 42424)),
            spkiSha256 = "4a".repeat(32),
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 }),
            expiresAt = expiry.epochSecond.toString()
        )
        val hotspot = HotspotInvitation("Glide Test", "example-password", transfer)
        val uri = HotspotInvitationCodec.encode(hotspot)
        val decoded = HotspotInvitationCodec.decode(uri, expiry.minusSeconds(1))
        assertEquals(hotspot.ssid, decoded.ssid)
        assertEquals(hotspot.passphrase, decoded.passphrase)
        assertEquals(transfer.spkiSha256, decoded.pairing.spkiSha256)
        assertFalse(decoded.toString().contains(hotspot.passphrase))
        assertFalse(decoded.toString().contains(transfer.token))
        assertThrows(IllegalArgumentException::class.java) { HotspotInvitationCodec.decode(uri, expiry) }
        assertThrows(IllegalArgumentException::class.java) {
            HotspotInvitationCodec.decode("$uri=", expiry.minusSeconds(1))
        }
    }
}
