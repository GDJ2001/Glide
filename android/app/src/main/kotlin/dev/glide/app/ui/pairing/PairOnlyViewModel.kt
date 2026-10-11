package dev.glide.app.ui.pairing

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.glide.core.identity.PersistentTrustManager
import dev.glide.core.identity.VerifiedPeerIdentity
import dev.glide.core.pairing.LanPairV2Invitation
import dev.glide.transport.lan.network.LanNetworkScope
import dev.glide.transport.lan.pairing.Profile2PairingClient
import dev.glide.transport.lan.tls.PinnedTlsConnector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PairOnlyUiState {
    data object Idle : PairOnlyUiState
    data object Verifying : PairOnlyUiState
    data class Approval(val peerName: String) : PairOnlyUiState
    data class Complete(val peerName: String) : PairOnlyUiState
    data class Failure(val message: String) : PairOnlyUiState
}

/** Pair-only QR/link work belongs to this screen; it never invokes a transfer session. */
class PairOnlyViewModel(
    private val appContext: Context,
    private val trustManager: PersistentTrustManager
) : ViewModel() {
    private val _state = MutableStateFlow<PairOnlyUiState>(PairOnlyUiState.Idle)
    val state: StateFlow<PairOnlyUiState> = _state.asStateFlow()
    private var approval: CompletableDeferred<Boolean>? = null
    private var job: Job? = null

    fun start(invitation: LanPairV2Invitation) {
        if (job?.isActive == true) return
        _state.value = PairOnlyUiState.Verifying
        job = viewModelScope.launch {
            val network = LanNetworkScope(appContext.applicationContext)
            try {
                trustManager.initialize()
                val client = Profile2PairingClient(PinnedTlsConnector(network))
                val outcome = client.runPairOnlyWithQrAsync(
                    invitation = invitation,
                    localDeviceName = Build.MODEL?.take(64)?.ifBlank { "Android device" } ?: "Android device",
                    trustManager = trustManager,
                    approvePeer = { peer: VerifiedPeerIdentity ->
                        val decision = CompletableDeferred<Boolean>()
                        approval = decision
                        _state.value = PairOnlyUiState.Approval(peer.displayName.take(64))
                        try { decision.await() } finally { approval = null }
                    },
                    onVerifying = { _state.value = PairOnlyUiState.Verifying }
                )
                _state.value = if (outcome.succeeded)
                    PairOnlyUiState.Complete(outcome.peer?.displayName ?: invitation.name)
                else PairOnlyUiState.Failure(outcome.userMessage)
            } catch (_: Exception) {
                _state.value = PairOnlyUiState.Failure("Couldn’t reach this device — check the local network")
            } finally {
                network.close()
            }
        }
    }

    fun approve() { approval?.complete(true) }
    fun decline() { approval?.complete(false) }
    fun dismiss() { _state.value = PairOnlyUiState.Idle }

    override fun onCleared() {
        approval?.complete(false)
        job?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(context: Context, trustManager: PersistentTrustManager): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PairOnlyViewModel(context.applicationContext, trustManager) as T
            }
    }
}
