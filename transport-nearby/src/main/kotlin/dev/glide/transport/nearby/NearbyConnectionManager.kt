package dev.glide.transport.nearby

import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import dev.glide.core.ports.ConnectionEvent
import dev.glide.core.ports.VerificationEvidence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Verified link states for peer transfer connection.
 */
sealed class LinkState {
    object Disconnected : LinkState()
    data class Connecting(val peerId: PeerId, val peerName: String) : LinkState()
    data class Verifying(
        val peerId: PeerId,
        val peerName: String,
        val authDigits: String,
        val timeoutSeconds: Int = 60,
        val isIncoming: Boolean = false,
        val localAccepted: Boolean = false
    ) : LinkState()
    data class Connected(val peer: PeerDevice) : LinkState()
    data class Failed(val reason: LinkFailure) : LinkState()
}

/**
 * Normalized failure reasons for connection link establishment.
 */
sealed class LinkFailure {
    object Mismatch : LinkFailure()
    object PeerRejected : LinkFailure()
    object Timeout : LinkFailure()
    object PeerDisappeared : LinkFailure()
    data class TransportError(val message: String) : LinkFailure()
}

/**
 * Manager responsible for point-to-point connection lifecycle between sender and receiver.
 * Enforces UKEY2 authentication verification, explicit mutual user confirmation, 60s timeout,
 * and rejection of any payload bytes prior to authenticated link success.
 */
class NearbyConnectionManager(
    private val clientWrapper: NearbyClientWrapper,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    customPayloadCallback: PayloadCallback? = null
) {
    companion object {
        const val VERIFICATION_TIMEOUT_MS: Long = 60_000L
    }

    private val _linkState = MutableStateFlow<LinkState>(LinkState.Disconnected)
    val linkState: StateFlow<LinkState> = _linkState.asStateFlow()

    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 64)
    val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents.asSharedFlow()

    private var timeoutJob: Job? = null
    private var sdkApprovedBeforeLocal: Boolean = false

    val payloadCallback = customPayloadCallback ?: object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val current = _linkState.value
            if (current !is LinkState.Connected || current.peer.id.value != endpointId) {
                // Threat model T01/T02: strictly drop unauthenticated payloads
                return
            }
            // Payloads forwarded to stream/control observers in GLIDE-013/014
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val current = _linkState.value
            if (current !is LinkState.Connected || current.peer.id.value != endpointId) {
                return
            }
        }
    }

    val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            handleConnectionInitiated(endpointId, info)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            handleConnectionResult(endpointId, resolution)
        }

        override fun onDisconnected(endpointId: String) {
            handleDisconnected(endpointId)
        }
    }

    fun handleConnectionInitiated(endpointId: String, info: ConnectionInfo) {
        val peerId = NearbyDiscoveryAdapter.sanitizeEndpointId(endpointId)
        val peerName = NearbyDiscoveryAdapter.sanitizeDeviceName(info.endpointName)
        val authDigits = info.authenticationDigits.takeIf { it.isNotBlank() } ?: "0000"

        timeoutJob?.cancel()
        sdkApprovedBeforeLocal = false

        val verifyingState = LinkState.Verifying(
            peerId = peerId,
            peerName = peerName,
            authDigits = authDigits,
            timeoutSeconds = 60,
            isIncoming = info.isIncomingConnection,
            localAccepted = false
        )
        _linkState.value = verifyingState

        scope.launch {
            _connectionEvents.emit(ConnectionEvent.Initiated(PeerDevice(peerId, peerName), VerificationEvidence.NearbyDigits(authDigits)))
        }

        timeoutJob = scope.launch {
            delay(VERIFICATION_TIMEOUT_MS)
            handleTimeout(peerId)
        }
    }

    fun handleConnectionResult(endpointId: String, resolution: ConnectionResolution) {
        val peerId = PeerId(endpointId.take(64))
        val current = _linkState.value

        if (current is LinkState.Verifying && current.peerId == peerId) {
            when (resolution.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    if (current.localAccepted) {
                        timeoutJob?.cancel()
                        val peer = PeerDevice(peerId, current.peerName)
                        _linkState.value = LinkState.Connected(peer)
                        scope.launch {
                            _connectionEvents.emit(ConnectionEvent.Connected(peer))
                        }
                    } else {
                        // SDK returned OK before local confirmation; wait for user Match
                        sdkApprovedBeforeLocal = true
                    }
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    timeoutJob?.cancel()
                    _linkState.value = LinkState.Failed(LinkFailure.PeerRejected)
                    scope.launch {
                        _connectionEvents.emit(ConnectionEvent.Rejected(peerId))
                    }
                }
                else -> {
                    timeoutJob?.cancel()
                    _linkState.value = LinkState.Failed(LinkFailure.PeerDisappeared)
                    scope.launch {
                        _connectionEvents.emit(ConnectionEvent.Failed(peerId, "Connection failed with status ${resolution.status.statusCode}"))
                    }
                }
            }
        }
    }

    fun handleDisconnected(endpointId: String) {
        timeoutJob?.cancel()
        val peerId = PeerId(endpointId.take(64))
        val current = _linkState.value

        if ((current is LinkState.Connected && current.peer.id == peerId) ||
            (current is LinkState.Verifying && current.peerId == peerId) ||
            (current is LinkState.Connecting && current.peerId == peerId)) {
            _linkState.value = LinkState.Disconnected
            scope.launch {
                _connectionEvents.emit(ConnectionEvent.Disconnected(peerId))
            }
        }
    }

    suspend fun initiateConnection(
        peerId: PeerId,
        peerName: String,
        localDeviceName: String
    ) {
        disconnectActiveConnection()
        val safeLocalName = NearbyDiscoveryAdapter.sanitizeDeviceName(localDeviceName, fallback = "Glide Sender")
        _linkState.value = LinkState.Connecting(peerId, peerName)

        try {
            clientWrapper.requestConnection(safeLocalName, peerId.value, connectionLifecycleCallback)
        } catch (e: Exception) {
            _linkState.value = LinkState.Failed(LinkFailure.TransportError(e.message ?: "Failed to request connection"))
            _connectionEvents.emit(ConnectionEvent.Failed(peerId, e.message ?: "Request connection error"))
        }
    }

    suspend fun acceptConnection(peerId: PeerId) {
        val current = _linkState.value
        // Stale confirm check: Must be in Verifying state for this exact peerId
        if (current !is LinkState.Verifying || current.peerId != peerId) {
            return
        }
        if (current.localAccepted) {
            return
        }

        if (sdkApprovedBeforeLocal) {
            // Both sides confirmed and SDK was already OK
            timeoutJob?.cancel()
            sdkApprovedBeforeLocal = false
            try {
                clientWrapper.acceptConnection(peerId.value, payloadCallback)
            } catch (_: Exception) {}
            val peer = PeerDevice(peerId, current.peerName)
            _linkState.value = LinkState.Connected(peer)
            _connectionEvents.emit(ConnectionEvent.Connected(peer))
            return
        }

        _linkState.value = current.copy(localAccepted = true)

        try {
            clientWrapper.acceptConnection(peerId.value, payloadCallback)
        } catch (e: Exception) {
            timeoutJob?.cancel()
            _linkState.value = LinkState.Failed(LinkFailure.TransportError(e.message ?: "Failed to accept connection"))
            _connectionEvents.emit(ConnectionEvent.Failed(peerId, e.message ?: "Accept connection error"))
        }
    }

    suspend fun rejectConnection(peerId: PeerId) {
        val current = _linkState.value
        if (current is LinkState.Verifying && current.peerId == peerId) {
            timeoutJob?.cancel()
            try {
                clientWrapper.rejectConnection(peerId.value)
            } catch (_: Exception) {}
            _linkState.value = LinkState.Failed(LinkFailure.Mismatch)
            _connectionEvents.emit(ConnectionEvent.Rejected(peerId))
        } else if (current is LinkState.Connecting && current.peerId == peerId) {
            timeoutJob?.cancel()
            try {
                clientWrapper.disconnectFromEndpoint(peerId.value)
            } catch (_: Exception) {}
            _linkState.value = LinkState.Disconnected
            _connectionEvents.emit(ConnectionEvent.Disconnected(peerId))
        }
    }

    private suspend fun handleTimeout(peerId: PeerId) {
        val current = _linkState.value
        if (current is LinkState.Verifying && current.peerId == peerId) {
            try {
                clientWrapper.rejectConnection(peerId.value)
            } catch (_: Exception) {
                clientWrapper.disconnectFromEndpoint(peerId.value)
            }
            _linkState.value = LinkState.Failed(LinkFailure.Timeout)
            _connectionEvents.emit(ConnectionEvent.Failed(peerId, "Human code confirmation timeout (60s)"))
        }
    }

    suspend fun disconnect(peerId: PeerId) {
        timeoutJob?.cancel()
        try {
            clientWrapper.disconnectFromEndpoint(peerId.value)
        } catch (_: Exception) {}
        val current = _linkState.value
        if ((current is LinkState.Connected && current.peer.id == peerId) ||
            (current is LinkState.Verifying && current.peerId == peerId) ||
            (current is LinkState.Connecting && current.peerId == peerId)) {
            _linkState.value = LinkState.Disconnected
            _connectionEvents.emit(ConnectionEvent.Disconnected(peerId))
        }
    }

    private fun disconnectActiveConnection() {
        timeoutJob?.cancel()
        val current = _linkState.value
        when (current) {
            is LinkState.Connected -> {
                clientWrapper.disconnectFromEndpoint(current.peer.id.value)
            }
            is LinkState.Verifying -> {
                clientWrapper.disconnectFromEndpoint(current.peerId.value)
            }
            is LinkState.Connecting -> {
                clientWrapper.disconnectFromEndpoint(current.peerId.value)
            }
            else -> {}
        }
        _linkState.value = LinkState.Disconnected
    }
}
