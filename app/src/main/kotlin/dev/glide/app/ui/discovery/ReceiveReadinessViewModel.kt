package dev.glide.app.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.glide.core.ports.AdvertisingEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel governing the receiver readiness route, 120-second discoverability countdown,
 * and live advertising events with real authentication digits (LAN-004).
 */
class ReceiveReadinessViewModel(
    private val advertisingFlowProvider: ((String) -> Flow<AdvertisingEvent>)? = null,
    private val onStopAdvertisingCallback: (() -> Unit)? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReceiveReadinessUiState>(
        ReceiveReadinessUiState.Advertising(localDeviceName = "Glide Device", secondsRemaining = 120)
    )
    val uiState: StateFlow<ReceiveReadinessUiState> = _uiState.asStateFlow()

    private var countdownJob: Job? = null
    private var advertisingJob: Job? = null

    fun startAdvertising(localDeviceName: String) {
        countdownJob?.cancel()
        advertisingJob?.cancel()

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
                        stopAdvertising()
                        break
                    } else {
                        _uiState.value = current.copy(secondsRemaining = sec)
                    }
                } else {
                    break
                }
            }
        }

        if (advertisingFlowProvider != null) {
            advertisingJob = viewModelScope.launch {
                try {
                    advertisingFlowProvider.invoke(localDeviceName).collect { event ->
                        when (event) {
                            is AdvertisingEvent.ConnectionInitiated -> {
                                onConnectionInitiated(
                                    DiscoveredPeer(
                                        id = event.peer.id,
                                        displayName = event.peer.displayName,
                                        deviceType = DeviceType.fromDeviceName(event.peer.displayName)
                                    ),
                                    event.authDigits
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Cancelled or stopped cleanly
                }
            }
        }
    }

    fun stopAdvertising() {
        countdownJob?.cancel()
        countdownJob = null
        advertisingJob?.cancel()
        advertisingJob = null
        onStopAdvertisingCallback?.invoke()
    }

    fun onConnectionInitiated(peer: DiscoveredPeer, authDigits: String) {
        countdownJob?.cancel()
        _uiState.value = ReceiveReadinessUiState.IncomingConnection(peer, authDigits)
    }

    fun setBlocked(reason: BlockedReason) {
        stopAdvertising()
        _uiState.value = ReceiveReadinessUiState.Blocked(reason)
    }

    fun setUiStateForTesting(state: ReceiveReadinessUiState) {
        stopAdvertising()
        _uiState.value = state
    }

    override fun onCleared() {
        super.onCleared()
        stopAdvertising()
    }
}
