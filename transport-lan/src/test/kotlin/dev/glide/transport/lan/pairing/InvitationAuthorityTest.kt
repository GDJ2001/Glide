package dev.glide.transport.lan.pairing

import dev.glide.core.pairing.LanEndpoint
import dev.glide.core.transport.lan.LanBootstrapFailureCode
import dev.glide.core.transport.lan.LanBootstrapMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class InvitationAuthorityTest {

    private class TestAuthorityClock(
        var currentEpochMs: Long = 1_700_000_000_000L,
        var currentMonotonicNs: Long = 10_000_000_000_000L
    ) : AuthorityClock {
        override fun epochMillis(): Long = currentEpochMs
        override fun monotonicNanos(): Long = currentMonotonicNs

        fun advanceSeconds(seconds: Long) {
            currentEpochMs += seconds * 1000L
            currentMonotonicNs += seconds * 1_000_000_000L
        }
    }

    private val fakeSpkiSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val testEndpoints = listOf(LanEndpoint("192.168.1.100", 49152))

    @Test
    fun testSuccessfulBootstrapConsumption() {
        val clock = TestAuthorityClock()
        val authority = InvitationAuthority(clock = clock)

        val invitation = authority.mintInvitation(
            endpoints = testEndpoints,
            spkiSha256 = fakeSpkiSha256,
            deviceName = "Receiver Device",
            ttlSeconds = 120,
            networkGeneration = 1L
        )

        val sessionId = UUID.randomUUID().toString()
        val message = LanBootstrapMessage(
            invitationId = invitation.invitationId,
            token = invitation.token,
            invitationHash = invitation.invitationHash,
            sessionId = sessionId
        )

        val result = authority.consume(
            message = message,
            currentNetworkGeneration = 1L,
            isBusy = false
        )

        assertTrue(result.isAccepted)
        assertEquals(sessionId, result.sessionId)
        assertNull(result.code)
        assertTrue(authority.isConsumed(invitation.invitationId))
        assertNull(authority.getActiveInvitation())
    }

    @Test
    fun testReplayAfterSuccessRejectedWithInvitationUsed() {
        val clock = TestAuthorityClock()
        val authority = InvitationAuthority(clock = clock)

        val invitation = authority.mintInvitation(
            endpoints = testEndpoints,
            spkiSha256 = fakeSpkiSha256,
            deviceName = "Receiver Device",
            networkGeneration = 1L
        )

        val sessionId1 = UUID.randomUUID().toString()
        val message1 = LanBootstrapMessage(
            invitationId = invitation.invitationId,
            token = invitation.token,
            invitationHash = invitation.invitationHash,
            sessionId = sessionId1
        )

        val result1 = authority.consume(message1, currentNetworkGeneration = 1L, isBusy = false)
        assertTrue(result1.isAccepted)

        // Replay attempt with same invitation ID and token
        val sessionId2 = UUID.randomUUID().toString()
        val message2 = LanBootstrapMessage(
            invitationId = invitation.invitationId,
            token = invitation.token,
            invitationHash = invitation.invitationHash,
            sessionId = sessionId2
        )

        val result2 = authority.consume(message2, currentNetworkGeneration = 1L, isBusy = false)
        assertFalse(result2.isAccepted)
        assertEquals(LanBootstrapFailureCode.INVITATION_USED.name, result2.code)
    }

    @Test
    fun testSimultaneousRaceOnlyOneSucceeds() = runBlocking(Dispatchers.Default) {
        val clock = TestAuthorityClock()
        val authority = InvitationAuthority(clock = clock)

        val invitation = authority.mintInvitation(
            endpoints = testEndpoints,
            spkiSha256 = fakeSpkiSha256,
            deviceName = "Receiver Device",
            networkGeneration = 1L
        )

        val deferreds = (1..10).map {
            async {
                val sessionId = UUID.randomUUID().toString()
                val message = LanBootstrapMessage(
                    invitationId = invitation.invitationId,
                    token = invitation.token,
                    invitationHash = invitation.invitationHash,
                    sessionId = sessionId
                )
                authority.consume(message, currentNetworkGeneration = 1L, isBusy = false)
            }
        }

        val results = deferreds.awaitAll()

        // Exactly one attempt must be accepted
        val acceptedCount = results.count { it.isAccepted }
        assertEquals(1, acceptedCount)

        // All other attempts must be rejected with INVITATION_USED
        val rejectedCount = results.count { !it.isAccepted && it.code == LanBootstrapFailureCode.INVITATION_USED.name }
        assertEquals(9, rejectedCount)
    }

    @Test
    fun testMonotonicExpiryBoundary() {
        val clock = TestAuthorityClock()
        val authority = InvitationAuthority(clock = clock)

        val invitation = authority.mintInvitation(
            endpoints = testEndpoints,
            spkiSha256 = fakeSpkiSha256,
            deviceName = "Receiver Device",
            ttlSeconds = 120,
            networkGeneration = 1L
        )

        val message = LanBootstrapMessage(
            invitationId = invitation.invitationId,
            token = invitation.token,
            invitationHash = invitation.invitationHash,
            sessionId = UUID.randomUUID().toString()
        )

        // Advance 119 seconds (still valid)
        clock.advanceSeconds(119)
        // Not expired yet; advance 2 more seconds -> 121 seconds (expired)
        clock.advanceSeconds(2)

        val result = authority.consume(message, currentNetworkGeneration = 1L, isBusy = false)
        assertFalse(result.isAccepted)
        assertEquals(LanBootstrapFailureCode.INVITATION_EXPIRED.name, result.code)
    }

    @Test
    fun testClockRollbackDoesNotExtendExpiry() {
        val clock = TestAuthorityClock()
        val authority = InvitationAuthority(clock = clock)

        val invitation = authority.mintInvitation(
            endpoints = testEndpoints,
            spkiSha256 = fakeSpkiSha256,
            deviceName = "Receiver Device",
            ttlSeconds = 120,
            networkGeneration = 1L
        )

        val message = LanBootstrapMessage(
            invitationId = invitation.invitationId,
            token = invitation.token,
            invitationHash = invitation.invitationHash,
            sessionId = UUID.randomUUID().toString()
        )

        // Monotonic time advances by 121 seconds, but wall clock rolls back!
        clock.currentMonotonicNs += 121L * 1_000_000_000L
        clock.currentEpochMs -= 50_000L // wall clock jumped back

        val result = authority.consume(message, currentNetworkGeneration = 1L, isBusy = false)
        assertFalse(result.isAccepted)
        assertEquals(LanBootstrapFailureCode.INVITATION_EXPIRED.name, result.code)
    }

    @Test
    fun testWrongTokenReturnsAuthenticationFailed() {
        val clock = TestAuthorityClock()
        val authority = InvitationAuthority(clock = clock)

        val invitation = authority.mintInvitation(
            endpoints = testEndpoints,
            spkiSha256 = fakeSpkiSha256,
            deviceName = "Receiver Device",
            networkGeneration = 1L
        )

        val wrongToken = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val message = LanBootstrapMessage(
            invitationId = invitation.invitationId,
            token = wrongToken,
            invitationHash = invitation.invitationHash,
            sessionId = UUID.randomUUID().toString()
        )

        val result = authority.consume(message, currentNetworkGeneration = 1L, isBusy = false)
        assertFalse(result.isAccepted)
        assertEquals(LanBootstrapFailureCode.AUTHENTICATION_FAILED.name, result.code)
    }

    @Test
    fun testWrongInvitationHashReturnsAuthenticationFailed() {
        val clock = TestAuthorityClock()
        val authority = InvitationAuthority(clock = clock)

        val invitation = authority.mintInvitation(
            endpoints = testEndpoints,
            spkiSha256 = fakeSpkiSha256,
            deviceName = "Receiver Device",
            networkGeneration = 1L
        )

        val wrongHash = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        val message = LanBootstrapMessage(
            invitationId = invitation.invitationId,
            token = invitation.token,
            invitationHash = wrongHash,
            sessionId = UUID.randomUUID().toString()
        )

        val result = authority.consume(message, currentNetworkGeneration = 1L, isBusy = false)
        assertFalse(result.isAccepted)
        assertEquals(LanBootstrapFailureCode.AUTHENTICATION_FAILED.name, result.code)
    }

    @Test
    fun testWrongNetworkGenerationReturnsAuthenticationFailed() {
        val clock = TestAuthorityClock()
        val authority = InvitationAuthority(clock = clock)

        val invitation = authority.mintInvitation(
            endpoints = testEndpoints,
            spkiSha256 = fakeSpkiSha256,
            deviceName = "Receiver Device",
            networkGeneration = 1L
        )

        val message = LanBootstrapMessage(
            invitationId = invitation.invitationId,
            token = invitation.token,
            invitationHash = invitation.invitationHash,
            sessionId = UUID.randomUUID().toString()
        )

        // Attempted on generation 2
        val result = authority.consume(message, currentNetworkGeneration = 2L, isBusy = false)
        assertFalse(result.isAccepted)
        assertEquals(LanBootstrapFailureCode.AUTHENTICATION_FAILED.name, result.code)
    }

    @Test
    fun testRefreshRotatesAndRevokesPrevious() {
        val clock = TestAuthorityClock()
        val authority = InvitationAuthority(clock = clock)

        val invitation1 = authority.mintInvitation(
            endpoints = testEndpoints,
            spkiSha256 = fakeSpkiSha256,
            deviceName = "Receiver Device",
            networkGeneration = 1L
        )

        val invitation2 = authority.mintInvitation(
            endpoints = testEndpoints,
            spkiSha256 = fakeSpkiSha256,
            deviceName = "Receiver Device",
            networkGeneration = 1L
        )

        // Invitation 1 is now revoked
        val message1 = LanBootstrapMessage(
            invitationId = invitation1.invitationId,
            token = invitation1.token,
            invitationHash = invitation1.invitationHash,
            sessionId = UUID.randomUUID().toString()
        )
        val result1 = authority.consume(message1, currentNetworkGeneration = 1L, isBusy = false)
        assertFalse(result1.isAccepted)
        assertEquals(LanBootstrapFailureCode.AUTHENTICATION_FAILED.name, result1.code)

        // Invitation 2 succeeds
        val message2 = LanBootstrapMessage(
            invitationId = invitation2.invitationId,
            token = invitation2.token,
            invitationHash = invitation2.invitationHash,
            sessionId = UUID.randomUUID().toString()
        )
        val result2 = authority.consume(message2, currentNetworkGeneration = 1L, isBusy = false)
        assertTrue(result2.isAccepted)
    }

    @Test
    fun testRevokeAllRejectsSubsequentAttempt() {
        val clock = TestAuthorityClock()
        val authority = InvitationAuthority(clock = clock)

        val invitation = authority.mintInvitation(
            endpoints = testEndpoints,
            spkiSha256 = fakeSpkiSha256,
            deviceName = "Receiver Device",
            networkGeneration = 1L
        )

        authority.revokeAll()

        val message = LanBootstrapMessage(
            invitationId = invitation.invitationId,
            token = invitation.token,
            invitationHash = invitation.invitationHash,
            sessionId = UUID.randomUUID().toString()
        )
        val result = authority.consume(message, currentNetworkGeneration = 1L, isBusy = false)
        assertFalse(result.isAccepted)
        assertEquals(LanBootstrapFailureCode.AUTHENTICATION_FAILED.name, result.code)
    }

    @Test
    fun testBusyServerRejectsWithBusy() {
        val clock = TestAuthorityClock()
        val authority = InvitationAuthority(clock = clock)

        val invitation = authority.mintInvitation(
            endpoints = testEndpoints,
            spkiSha256 = fakeSpkiSha256,
            deviceName = "Receiver Device",
            networkGeneration = 1L
        )

        val message = LanBootstrapMessage(
            invitationId = invitation.invitationId,
            token = invitation.token,
            invitationHash = invitation.invitationHash,
            sessionId = UUID.randomUUID().toString()
        )

        // Busy server rejects with BUSY
        val busyResult = authority.consume(message, currentNetworkGeneration = 1L, isBusy = true)
        assertFalse(busyResult.isAccepted)
        assertEquals(LanBootstrapFailureCode.BUSY.name, busyResult.code)

        // Invitation was NOT consumed, so it can still succeed once server is not busy
        val normalResult = authority.consume(message, currentNetworkGeneration = 1L, isBusy = false)
        assertTrue(normalResult.isAccepted)
    }
}
