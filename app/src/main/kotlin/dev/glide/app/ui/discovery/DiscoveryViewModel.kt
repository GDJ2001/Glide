package dev.glide.app.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.glide.app.service.TransferRuntime
import dev.glide.app.service.TransferRuntimeCommand
import dev.glide.core.domain.PeerId
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Presentation adapter for runtime-owned production discovery (HARD-006).
 *
 * A no-argument instance is deliberately fail-closed and exists only for tests/tooling that do not
 * provide the production runtime. Production Navigation3 always supplies [TransferRuntime].
 */
class DiscoveryViewModel private constructor(
    private val runtime: TransferRuntime?,
    initialState: DiscoveryUiState
) : ViewModel() {

    constructor(runtime: TransferRuntime) : this(runtime, runtime.discoveryState.value)

    constructor() : this(
        runtime = null,
        initialState = DiscoveryUiState.Blocked(
            BlockedReason.TransportError("Production discovery dependency is unavailable")
        )
    )

    private val _uiState = MutableStateFlow(initialState)
    val uiState: StateFlow<DiscoveryUiState> = _uiState.asStateFlow()

    init {
        if (runtime != null) {
            viewModelScope.launch {
                runtime.discoveryState.collect { state ->
                    val current = _uiState.value
                    _uiState.value = if (
                        state is DiscoveryUiState.Searching &&
                        current is DiscoveryUiState.Searching &&
                        current.selectedPeerId != null
                    ) {
                        state.copy(
                            peers = state.peers.map { peer ->
                                val old = current.peers.firstOrNull { it.id == peer.id }
                                if (old != null) {
                                    peer.copy(
                                        isConnecting = old.isConnecting,
                                        isSelected = old.isSelected,
                                        isBusy = old.isBusy
                                    )
                                } else {
                                    peer
                                }
                            },
                            selectedPeerId = current.selectedPeerId
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    /**
     * Starts the real runtime discovery operation. No ViewModel-owned radio/timer job exists.
     */
    fun startDiscovery(): Boolean =
        runtime?.dispatch(TransferRuntimeCommand.StartDiscovery) ?: false

    /**
     * Historical API retained for callers/tests; now delegates to runtime-owned discovery.
     */
    fun startDiscoveryTimer(): Boolean = startDiscovery()

    fun stopDiscovery() {
        runtime?.dispatch(TransferRuntimeCommand.StopDiscovery)
    }

    fun onPermissionDenied() {
        runtime?.dispatch(
            TransferRuntimeCommand.NearbyPermissionDenied(receiveMode = false)
        )
    }

    fun retryDiscovery(): Boolean = startDiscovery()

    /**
     * Taps a peer to initiate connection.
     * HARD-007 will bind this selection to the production pairing/session command.
     */
    fun selectPeer(peerId: PeerId, onInitiateConnection: (PeerId) -> Unit = {}) {
        val current = _uiState.value
        if (current !is DiscoveryUiState.Searching) return
        if (current.selectedPeerId != null) return

        val target = current.peers.find { it.id == peerId }
        if (target == null || !target.isAvailable || target.isBusy) return

        val updatedPeers = current.peers.map { peer ->
            if (peer.id == peerId) {
                peer.copy(isConnecting = true, isSelected = true)
            } else {
                peer.copy(isSelected = false)
            }
        }

        _uiState.value = current.copy(
            peers = updatedPeers,
            selectedPeerId = peerId
        )

        onInitiateConnection(peerId)
    }

    fun setPeerBusy(peerId: PeerId, isBusy: Boolean) {
        val current = _uiState.value
        if (current is DiscoveryUiState.Searching) {
            _uiState.value = current.copy(
                peers = current.peers.map { peer ->
                    if (peer.id == peerId) peer.copy(isBusy = isBusy) else peer
                }
            )
        }
    }

    /**
     * Test/presentation helper; production peer events originate from ProductionDiscoveryController.
     */
    fun onPeerDiscovered(peer: DiscoveredPeer) {
        val current = _uiState.value
        if (current is DiscoveryUiState.Searching) {
            val existingIndex = current.peers.indexOfFirst { it.id == peer.id }
            val peers = if (existingIndex >= 0) {
                current.peers.toMutableList().apply {
                    val existing = this[existingIndex]
                    this[existingIndex] = peer.copy(
                        isConnecting = existing.isConnecting,
                        isSelected = existing.isSelected,
                        isBusy = existing.isBusy
                    )
                }
            } else {
                current.peers + peer
            }
            _uiState.value = current.copy(peers = peers)
        }
    }

    fun onPeerLost(peerId: PeerId) {
        val current = _uiState.value
        if (current is DiscoveryUiState.Searching) {
            _uiState.value = current.copy(
                peers = current.peers.map { peer ->
                    if (peer.id == peerId) peer.copy(isAvailable = false) else peer
                }
            )
        }
    }

    fun setBlocked(reason: BlockedReason) {
        _uiState.value = DiscoveryUiState.Blocked(reason)
    }

    fun setUiStateForTesting(state: DiscoveryUiState) {
        _uiState.value = state
    }

    override fun onCleared() {
        stopDiscovery()
        super.onCleared()
    }

    companion object {
        internal fun forTesting(
            initialState: DiscoveryUiState = DiscoveryUiState.Searching()
        ): DiscoveryViewModel = DiscoveryViewModel(
            runtime = null,
            initialState = initialState
        )
    }
}
