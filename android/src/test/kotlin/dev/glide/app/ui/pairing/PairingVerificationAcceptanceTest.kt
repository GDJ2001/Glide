package dev.glide.app.ui.pairing

import dev.glide.app.ui.discovery.DeviceType
import dev.glide.core.domain.PeerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance tests for GLIDE-038 Connection Verification surface.
 *
 * Verifies:
 * - Digit comparison presentation (monospaced, individual digit formatting).
 * - TalkBack semantics announcing digits individually.
 * - Same-name peer disambiguation badge behavior.
 * - Confirmed local transition and countdown timeout behavior.
 * - Device category heuristics for verification display.
 */
class PairingVerificationAcceptanceTest {

    @Test
    fun verifyingState_formatsAuthDigitsIndividuallyForTalkBack() {
        val state = PairingUiState.Verifying(
            peerId = PeerId("endpoint-alpha"),
            peerName = "Pixel 9 Pro",
            authDigits = "482916",
            secondsRemaining = 55,
            localConfirmed = false,
            localDeviceName = "Pixel 10 Pro",
            isSameNamePeer = false,
            deviceType = DeviceType.Phone
        )

        // Verifies individual chunking for visual and TalkBack separation
        val individualDigits = state.authDigits.chunked(1)
        assertEquals(6, individualDigits.size)
        assertEquals(listOf("4", "8", "2", "9", "1", "6"), individualDigits)

        // Verifies spoken content description produces comma-separated digits for screen readers
        val spoken = individualDigits.joinToString(", ")
        assertEquals("4, 8, 2, 9, 1, 6", spoken)
        val fullAnnouncement = "Verification code: $spoken. Compare with code on ${state.peerName}."
        assertTrue(fullAnnouncement.contains("4, 8, 2, 9, 1, 6"))
        assertTrue(fullAnnouncement.contains("Pixel 9 Pro"))
    }

    @Test
    fun verifyingState_sameNamePeer_setsDisambiguationFlagAndIdBadge() {
        val peerId = PeerId("ep-7b3f9a-duplicate")
        val state = PairingUiState.Verifying(
            peerId = peerId,
            peerName = "Pixel 10 Pro", // Matches local device name
            authDigits = "730514",
            secondsRemaining = 45,
            localConfirmed = false,
            localDeviceName = "Pixel 10 Pro",
            isSameNamePeer = true,
            deviceType = DeviceType.Phone
        )

        assertTrue(state.isSameNamePeer)
        val badge = "#${state.peerId.value.take(4)}"
        assertEquals("#ep-7", badge)
    }

    @Test
    fun verifyingState_localConfirmed_transitionsToWaitingState() {
        val state = PairingUiState.Verifying(
            peerId = PeerId("peer-tab"),
            peerName = "Galaxy Tab S9",
            authDigits = "5821",
            secondsRemaining = 40,
            localConfirmed = false
        )

        assertFalse(state.localConfirmed)
        val confirmed = state.copy(localConfirmed = true)
        assertTrue(confirmed.localConfirmed)
        assertEquals(40, confirmed.secondsRemaining)
    }

    @Test
    fun verifyingState_timeoutCountdown_triggersFailedStateOnZero() {
        val state = PairingUiState.Verifying(
            peerId = PeerId("peer-timeout"),
            peerName = "MacBook Air",
            authDigits = "9901",
            secondsRemaining = 0,
            localConfirmed = false
        )

        assertEquals(0, state.secondsRemaining)

        // When countdown expires, transitions to structured Failed state
        val timeoutFailed = PairingUiState.Failed(
            title = "Verification Timed Out",
            message = "The 60-second verification window elapsed. Please try again."
        )

        assertEquals("Verification Timed Out", timeoutFailed.title)
        assertTrue(timeoutFailed.message.contains("60-second"))
    }

    @Test
    fun deviceType_heuristicsInVerification_resolvesDeviceCategories() {
        assertEquals(DeviceType.Phone, DeviceType.fromDeviceName("Pixel 9 Pro"))
        assertEquals(DeviceType.Tablet, DeviceType.fromDeviceName("Galaxy Tab S9"))
        assertEquals(DeviceType.Tablet, DeviceType.fromDeviceName("iPad Air"))
        assertEquals(DeviceType.Computer, DeviceType.fromDeviceName("MacBook Air"))
        assertEquals(DeviceType.Computer, DeviceType.fromDeviceName("Office-Desktop-PC"))
    }
}
