package dev.glide.core.ports

import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationEvidenceTest {

    @Test
    fun nearbyDigits_validatesNonBlank() {
        val evidence = VerificationEvidence.NearbyDigits("849201")
        assertEquals("849201", evidence.digits)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nearbyDigits_rejectsBlank() {
        VerificationEvidence.NearbyDigits("   ")
    }

    @Test
    fun invitationVerified_validatesNonBlankFields() {
        val evidence = VerificationEvidence.InvitationVerified(
            invitationId = "00000000-0000-4000-8000-000000000001",
            serverPin = "492018"
        )
        assertEquals("00000000-0000-4000-8000-000000000001", evidence.invitationId)
        assertEquals("492018", evidence.serverPin)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invitationVerified_rejectsBlankInvitationId() {
        VerificationEvidence.InvitationVerified("", "123456")
    }

    @Test(expected = IllegalArgumentException::class)
    fun invitationVerified_rejectsBlankServerPin() {
        VerificationEvidence.InvitationVerified("00000000-0000-4000-8000-000000000001", "")
    }

    @Test
    fun connectionEventInitiated_withNearbyEvidence_exposesAuthDigits() {
        val peer = PeerDevice(PeerId("peer-1"), "Pixel 9")
        val event = ConnectionEvent.Initiated(peer, VerificationEvidence.NearbyDigits("123456"))

        assertEquals("123456", event.authDigits)
        assertTrue(event.evidence is VerificationEvidence.NearbyDigits)
    }

    @Test
    fun connectionEventInitiated_withInvitationEvidence_hasNullAuthDigits() {
        val peer = PeerDevice(PeerId("peer-1"), "Galaxy Tab")
        val event = ConnectionEvent.Initiated(
            peer,
            VerificationEvidence.InvitationVerified("00000000-0000-4000-8000-000000000001", "654321")
        )

        assertEquals("", event.authDigits)
        assertTrue(event.evidence is VerificationEvidence.InvitationVerified)
        assertEquals("654321", (event.evidence as VerificationEvidence.InvitationVerified).serverPin)
    }

    @Test
    fun connectionEventInitiated_legacyConstructor_createsNearbyDigitsEvidence() {
        val peer = PeerDevice(PeerId("peer-1"), "Desktop")
        val event = ConnectionEvent.Initiated(peer, "998877")

        assertEquals("998877", event.authDigits)
        assertTrue(event.evidence is VerificationEvidence.NearbyDigits)
        assertEquals("998877", (event.evidence as VerificationEvidence.NearbyDigits).digits)
    }

    @Test
    fun advertisingEventConnectionInitiated_withNearbyEvidence_exposesAuthDigits() {
        val peer = PeerDevice(PeerId("peer-2"), "iPhone")
        val event = AdvertisingEvent.ConnectionInitiated(peer, VerificationEvidence.NearbyDigits("334455"))

        assertEquals("334455", event.authDigits)
        assertTrue(event.evidence is VerificationEvidence.NearbyDigits)
    }

    @Test
    fun advertisingEventConnectionInitiated_legacyConstructor_createsNearbyDigitsEvidence() {
        val peer = PeerDevice(PeerId("peer-2"), "Laptop")
        val event = AdvertisingEvent.ConnectionInitiated(peer, "112233")

        assertEquals("112233", event.authDigits)
        assertTrue(event.evidence is VerificationEvidence.NearbyDigits)
    }
}
