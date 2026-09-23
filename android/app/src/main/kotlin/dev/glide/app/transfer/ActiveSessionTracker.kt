package dev.glide.app.transfer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the global active transfer session state across the application lifecycle.
 *
 * Used by Share Sheet ingress and navigation to enforce that an ongoing active transfer
 * session is never replaced or overwritten by incoming intents (GLIDE-026).
 */
object ActiveSessionTracker {

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    fun setSessionActive(sessionId: String, active: Boolean) {
        if (active) {
            _activeSessionId.value = sessionId
            _isSessionActive.value = true
        } else {
            if (_activeSessionId.value == sessionId || _activeSessionId.value == null) {
                _activeSessionId.value = null
                _isSessionActive.value = false
            }
        }
    }

    fun reset() {
        _activeSessionId.value = null
        _isSessionActive.value = false
    }
}
