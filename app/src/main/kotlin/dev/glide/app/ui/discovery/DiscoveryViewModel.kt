package dev.glide.app.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.glide.core.domain.PeerId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel governing the Discovery route UI state, 120-second timeout,
 * and stable non-jumping peer cards.
 */
class DiscoveryViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<DiscoveryUiState>(DiscoveryUiState.Searching())
    val uiState: StateFlow<DiscoveryUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null

    init {
        startDiscoveryTimer()
    }

    fun startDiscoveryTimer() {
        timerJob?.cancel()
        _uiState.value = DiscoveryUiState.Searching(
            peers = (_uiState.value as? DiscoveryUiState.Searching)?.peers ?: emptyList(),
            secondsElapsed = 0,
            selectedPeerId = null
        )

        timerJob = viewModelScope.launch {
            for (sec in 1..120) {
                delay(1000L)
                val current = _uiState.value
                if (current is DiscoveryUiState.Searching) {
                    if (sec >= 120) {
                        _uiState.value = DiscoveryUiState.TimedOut(peers = current.peers)
                        break
                    } else {
                        _uiState.value = current.copy(secondsElapsed = sec)
                    }
                } else {
                    break
                }
            }
        }
    }

    /**
     * Taps a peer to initiate connection.
     * Enforces single-click selection and disables duplicate attempts.
     */
    fun selectPeer(peerId: PeerId, onInitiateConnection: (PeerId) -> Unit = {}) {
        val current = _uiState.value
        if (current !is DiscoveryUiState.Searching) return
        if (current.selectedPeerId != null) return // Already connecting to a peer; ignore duplicate tap

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

    /**
     * Adds a newly discovered peer or updates an existing one without changing list indices.
     */
    fun onPeerDiscovered(peer: DiscoveredPeer) {
        val current = _uiState.value
        if (current is DiscoveryUiState.Searching) {
            val existingIndex = current.peers.indexOfFirst { it.id == peer.id }
            val newPeers = if (existingIndex >= 0) {
                current.peers.toMutableList().apply {
                    this[existingIndex] = peer.copy(
                        isConnecting = current.peers[existingIndex].isConnecting,
                        isSelected = current.peers[existingIndex].isSelected
                    )
                }
            } else {
                current.peers + peer
            }
            _uiState.value = current.copy(peers = newPeers)
        }
    }

    /**
     * Marks a peer as unavailable rather than removing it immediately, preventing list jumping.
     */
    fun onPeerLost(peerId: PeerId) {
        val current = _uiState.value
        if (current is DiscoveryUiState.Searching) {
            val updated = current.peers.map { peer ->
                if (peer.id == peerId) peer.copy(isAvailable = false) else peer
            }
            _uiState.value = current.copy(peers = updated)
        }
    }

    fun setBlocked(reason: BlockedReason) {
        timerJob?.cancel()
        _uiState.value = DiscoveryUiState.Blocked(reason)
    }

    fun retryDiscovery() {
        startDiscoveryTimer()
    }

    fun setUiStateForTesting(state: DiscoveryUiState) {
        timerJob?.cancel()
        _uiState.value = state
    }
}
