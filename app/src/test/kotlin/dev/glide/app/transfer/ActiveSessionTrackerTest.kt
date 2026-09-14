package dev.glide.app.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActiveSessionTrackerTest {

    @Before
    fun setup() {
        ActiveSessionTracker.reset()
    }

    @Test
    fun initialState_isNotActive() {
        assertFalse(ActiveSessionTracker.isSessionActive.value)
        assertNull(ActiveSessionTracker.activeSessionId.value)
    }

    @Test
    fun setSessionActive_true_updatesState() {
        ActiveSessionTracker.setSessionActive("session-123", true)

        assertTrue(ActiveSessionTracker.isSessionActive.value)
        assertEquals("session-123", ActiveSessionTracker.activeSessionId.value)
    }

    @Test
    fun setSessionActive_false_matchingSession_resetsState() {
        ActiveSessionTracker.setSessionActive("session-123", true)
        assertTrue(ActiveSessionTracker.isSessionActive.value)

        ActiveSessionTracker.setSessionActive("session-123", false)
        assertFalse(ActiveSessionTracker.isSessionActive.value)
        assertNull(ActiveSessionTracker.activeSessionId.value)
    }

    @Test
    fun setSessionActive_false_mismatchedSession_preservesActiveState() {
        ActiveSessionTracker.setSessionActive("session-123", true)

        // Attempting to clear with an unrelated session ID does not clear the active session
        ActiveSessionTracker.setSessionActive("session-other", false)
        assertTrue(ActiveSessionTracker.isSessionActive.value)
        assertEquals("session-123", ActiveSessionTracker.activeSessionId.value)
    }

    @Test
    fun reset_clearsActiveSession() {
        ActiveSessionTracker.setSessionActive("session-123", true)
        assertTrue(ActiveSessionTracker.isSessionActive.value)

        ActiveSessionTracker.reset()
        assertFalse(ActiveSessionTracker.isSessionActive.value)
        assertNull(ActiveSessionTracker.activeSessionId.value)
    }
}
