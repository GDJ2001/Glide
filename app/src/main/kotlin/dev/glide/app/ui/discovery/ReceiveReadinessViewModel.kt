package dev.glide.app.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel governing the receiver readiness route and 120-second discoverability countdown.
 */
class ReceiveReadinessViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<ReceiveReadinessUiState>(
        ReceiveReadinessUiState.Advertising(localDeviceName = "Glide Device", secondsRemaining = 120)
    )
    val uiState: StateFlow<ReceiveReadinessUiState> = _uiState.asStateFlow()

    private var countdownJob: Job? = null

    fun startAdvertising(localDeviceName: String) {
        countdownJob?.cancel()
        _uiState.value = ReceiveReadinessUiState.Advertising(
            localDeviceName = localDeviceName,
            secondsRemaining = 120
        )

        countdownJob = viewModelScope.launch {
            for (sec in 119 downTo 0) {
                delay(1000L)
                val current = _uiState.value
                if (current is ReceiveReadinessUiState.Advertising) {
                    if (sec <= 0) {
                        _uiState.value = ReceiveReadinessUiState.TimedOut
                        break
                    } else {
                        _uiState.value = current.copy(secondsRemaining = sec)
                    }
                } else {
                    break
                }
            }
        }
    }

    fun stopAdvertising() {
        countdownJob?.cancel()
    }

    fun onConnectionInitiated(peer: DiscoveredPeer, authDigits: String) {
        countdownJob?.cancel()
        _uiState.value = ReceiveReadinessUiState.IncomingConnection(peer, authDigits)
    }

    fun setBlocked(reason: BlockedReason) {
        countdownJob?.cancel()
        _uiState.value = ReceiveReadinessUiState.Blocked(reason)
    }

    fun setUiStateForTesting(state: ReceiveReadinessUiState) {
        countdownJob?.cancel()
        _uiState.value = state
    }
}
