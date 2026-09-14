package dev.glide.app.service

import dev.glide.app.ui.transfer.TransferUiState
import dev.glide.core.domain.DeviceRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying that [TransferService] strictly rejects stale or mismatched
 * cancel intents and only cancels when the exact active session ID matches (GLIDE-029).
 */
class StaleCancelActionTest {

    private lateinit var service: TransferService

    @Before
    fun setUp() {
        TransferSessionManager.resetForTesting()
        service = TransferService()
    }

    @After
    fun tearDown() {
        TransferSessionManager.resetForTesting()
    }

    @Test
    fun policy_evaluatesStaleAndMatchingCancelActions() {
        val activeSession = "active-session-100"

        // 1. Exact match accepted
        assertTrue(TransferServicePolicy.shouldAcceptCancelAction(activeSession, activeSession))

        // 2. Mismatched session ID rejected as stale
        assertFalse(TransferServicePolicy.shouldAcceptCancelAction("old-stale-session-99", activeSession))

        // 3. Null or blank target session rejected
        assertFalse(TransferServicePolicy.shouldAcceptCancelAction(null, activeSession))
        assertFalse(TransferServicePolicy.shouldAcceptCancelAction("", activeSession))

        // 4. Null active session (already terminated) rejected
        assertFalse(TransferServicePolicy.shouldAcceptCancelAction(activeSession, null))
        assertFalse(TransferServicePolicy.shouldAcceptCancelAction(null, null))
    }

    @Test
    fun matchingSessionId_cancelsActiveSession() {
        val activeSessionId = "active-transfer-session-1"
        TransferSessionManager.setUiStateForTesting(
            TransferUiState.Transferring(
                sessionId = activeSessionId,
                role = DeviceRole.Sender,
                peerName = "Pixel 9 Pro",
                itemName = "test.txt",
                transferredBytes = 100L,
                totalBytes = 1000L,
                progressFraction = 0.1f
            )
        )

        val cancelled = service.handleCancelAction(activeSessionId)
        assertTrue(cancelled)

        val currentState = TransferSessionManager.uiState.value
        assertTrue(currentState is TransferUiState.Cancelled)
        assertEquals(activeSessionId, currentState.sessionId)
    }

    @Test
    fun mismatchedSessionId_isRejectedAndActiveSessionContinues() {
        val activeSessionId = "active-transfer-session-1"
        val activeState = TransferUiState.Transferring(
            sessionId = activeSessionId,
            role = DeviceRole.Sender,
            peerName = "Pixel 9 Pro",
            itemName = "test.txt",
            transferredBytes = 100L,
            totalBytes = 1000L,
            progressFraction = 0.1f
        )
        TransferSessionManager.setUiStateForTesting(activeState)

        val cancelled = service.handleCancelAction("stale-session-999")
        assertFalse(cancelled)

        // Active transfer must NOT be cancelled!
        val currentState = TransferSessionManager.uiState.value
        assertTrue(currentState is TransferUiState.Transferring)
        assertEquals(activeSessionId, currentState.sessionId)
        assertNotEquals("stale-session-999", currentState.sessionId)
    }

    @Test
    fun missingSessionId_isRejectedAndActiveSessionContinues() {
        val activeSessionId = "active-transfer-session-1"
        val activeState = TransferUiState.Transferring(
            sessionId = activeSessionId,
            role = DeviceRole.Sender,
            peerName = "Pixel 9 Pro",
            itemName = "test.txt",
            transferredBytes = 100L,
            totalBytes = 1000L,
            progressFraction = 0.1f
        )
        TransferSessionManager.setUiStateForTesting(activeState)

        val cancelled = service.handleCancelAction(null)
        assertFalse(cancelled)

        // Active transfer must NOT be cancelled!
        val currentState = TransferSessionManager.uiState.value
        assertTrue(currentState is TransferUiState.Transferring)
        assertEquals(activeSessionId, currentState.sessionId)
    }
}
