package dev.glide.app.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.glide.app.service.TransferRuntime
import dev.glide.app.service.TransferRuntimeCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Presentation adapter for runtime-owned Nearby advertising (HARD-006).
 *
 * Production Navigation3 supplies [TransferRuntime]. A no-argument instance is deliberately
 * fail-closed so optional/fake advertising providers cannot accidentally enter production.
 */
class ReceiveReadinessViewModel private constructor(
    private val runtime: TransferRuntime?,
    initialState: ReceiveReadinessUiState
) : ViewModel() {

    constructor(runtime: TransferRuntime) : this(runtime, runtime.receiveReadinessState.value)

    constructor() : this(
        runtime = null,
        initialState = ReceiveReadinessUiState.Blocked(
            BlockedReason.TransportError("Production advertising dependency is unavailable")
        )
    )

    private val _uiState = MutableStateFlow(initialState)
    val uiState: StateFlow<ReceiveReadinessUiState> = _uiState.asStateFlow()

    init {
        if (runtime != null) {
            viewModelScope.launch {
                runtime.receiveReadinessState.collect { state ->
                    _uiState.value = state
                }
            }
        }
    }

    fun startAdvertising(localDeviceName: String): Boolean =
        runtime?.dispatch(
            TransferRuntimeCommand.StartReceiveAvailability(localDeviceName)
        ) ?: false

    fun stopAdvertising() {
        runtime?.dispatch(TransferRuntimeCommand.StopReceiveAvailability)
    }

    fun onPermissionDenied() {
        runtime?.dispatch(
            TransferRuntimeCommand.NearbyPermissionDenied(receiveMode = true)
        )
    }

    /**
     * Test/presentation helper. Production connection initiation comes from the runtime controller.
     */
    fun onConnectionInitiated(peer: DiscoveredPeer, authDigits: String) {
        _uiState.value = ReceiveReadinessUiState.IncomingConnection(peer, authDigits)
    }

    fun setBlocked(reason: BlockedReason) {
        _uiState.value = ReceiveReadinessUiState.Blocked(reason)
    }

    fun setUiStateForTesting(state: ReceiveReadinessUiState) {
        _uiState.value = state
    }

    override fun onCleared() {
        stopAdvertising()
        super.onCleared()
    }

    companion object {
        internal fun forTesting(
            initialState: ReceiveReadinessUiState = ReceiveReadinessUiState.Advertising(
                localDeviceName = "Glide Device",
                secondsRemaining = 120
            )
        ): ReceiveReadinessViewModel = ReceiveReadinessViewModel(
            runtime = null,
            initialState = initialState
        )
    }
}
