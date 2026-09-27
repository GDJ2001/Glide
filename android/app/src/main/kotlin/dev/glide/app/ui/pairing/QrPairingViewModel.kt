package dev.glide.app.ui.pairing

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.glide.app.qr.QrEncoder
import dev.glide.core.pairing.InvitationCodec
import dev.glide.core.pairing.InvitationException
import dev.glide.core.pairing.LanEndpoint
import dev.glide.core.pairing.LanInvitation
import dev.glide.transport.lan.pairing.AuthorityClock
import dev.glide.transport.lan.pairing.InvitationAuthority
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel governing the Receive QR minting/countdown/refresh/revoke lifecycle
 * and the Sender QR scanning/pasting/confirmation lifecycle (LAN-018).
 *
 * Security Invariants:
 * - 100% offline, zero cloud calls, zero telemetry.
 * - Listener readiness precondition: invitations are only minted after the receiver's listener is bound.
 * - Generation-bound: invitations are cryptographically tied to the current network generation.
 * - Monotonic 120-second countdown with automatic expiry revocation.
 * - Single active invitation: Refresh immediately rotates the ID/token and revokes the previous code.
 * - Cancellation/background/network-change revokes outstanding invitations.
 * - Identical validation for camera scans and paste alternatives.
 * - Explicit sender confirmation required before opening any network connection.
 * - Zero saved-state secret: no invitation payloads, tokens, or private keys are written to SavedStateHandle.
 */
class QrPairingViewModel(
    val invitationAuthority: InvitationAuthority = InvitationAuthority(),
    private val clock: AuthorityClock = AuthorityClock.SYSTEM,
    private val qrBitmapGenerator: ((String) -> Bitmap?)? = null
) : ViewModel() {

    private val _receiverState = MutableStateFlow<QrReceiverUiState>(QrReceiverUiState.Idle)
    val receiverState: StateFlow<QrReceiverUiState> = _receiverState.asStateFlow()

    private val _scannerState = MutableStateFlow<QrScannerUiState>(QrScannerUiState.Idle)
    val scannerState: StateFlow<QrScannerUiState> = _scannerState.asStateFlow()

    private val _events = MutableSharedFlow<QrPairingEvent>(replay = 0, extraBufferCapacity = 16)
    val events: SharedFlow<QrPairingEvent> = _events.asSharedFlow()

    private var countdownJob: Job? = null
    private var externalInvitationRevoker: (() -> Unit)? = null
    private var deviceHasFlashlight: Boolean = false

    // -------------------------------------------------------------------------
    // Receiver Flow (Minting, Countdown, Refresh, Revocation)
    // -------------------------------------------------------------------------

    /**
     * Mints a fresh QR invitation and starts the 120-second countdown.
     * Requires listener readiness: valid endpoints, SPKI hash, and device name.
     */
    fun startReceiving(
        deviceName: String,
        endpoints: List<LanEndpoint>,
        spkiSha256: String,
        networkGeneration: Long = 0L,
        ttlSeconds: Int = 120
    ) {
        countdownJob?.cancel()
        externalInvitationRevoker = null

        if (endpoints.isEmpty() || spkiSha256.length != 64) {
            _receiverState.value = QrReceiverUiState.Error(
                message = "Network listener not ready or missing valid on-link endpoints.",
                canRetry = true
            )
            return
        }

        _receiverState.value = QrReceiverUiState.Starting

        val invitation = invitationAuthority.mintInvitation(
            endpoints = endpoints,
            spkiSha256 = spkiSha256,
            deviceName = deviceName,
            platform = "android",
            ttlSeconds = ttlSeconds,
            networkGeneration = networkGeneration
        )

        val uriString = InvitationCodec.encode(invitation)
        val bitmap = try {
            qrBitmapGenerator?.invoke(uriString) ?: QrEncoder.encodeToBitmap(uriString, 512)
        } catch (_: Exception) {
            null
        }

        val endpointSummary = endpoints.joinToString(", ") { "${it.address}:${it.port}" }

        _receiverState.value = QrReceiverUiState.Ready(
            invitation = invitation,
            qrBitmap = bitmap,
            secondsRemaining = ttlSeconds,
            localDeviceName = deviceName,
            endpointsSummary = endpointSummary
        )

        startCountdown(ttlSeconds)
    }

    /**
     * Renders an invitation minted by the service-owned production LAN runtime.
     * The ViewModel never owns the listener, lease key, token authority or socket.
     */
    fun showLiveReceiverInvitation(
        invitation: LanInvitation,
        onRevoke: () -> Unit
    ) {
        countdownJob?.cancel()
        externalInvitationRevoker = onRevoke

        val uriString = InvitationCodec.encode(invitation)
        val bitmap = try {
            qrBitmapGenerator?.invoke(uriString) ?: QrEncoder.encodeToBitmap(uriString, 512)
        } catch (_: Exception) {
            null
        }

        _receiverState.value = QrReceiverUiState.Ready(
            invitation = invitation,
            qrBitmap = bitmap,
            secondsRemaining = invitation.ttlSeconds,
            localDeviceName = invitation.name,
            endpointsSummary = invitation.endpoints.joinToString(", ") {
                "${it.address}:${it.port}"
            }
        )
        startCountdown(invitation.ttlSeconds)
    }

    fun showLiveReceiverStarting() {
        countdownJob?.cancel()
        _receiverState.value = QrReceiverUiState.Starting
    }

    fun showLiveReceiverError(message: String, canRetry: Boolean = true) {
        countdownJob?.cancel()
        _receiverState.value = QrReceiverUiState.Error(message, canRetry)
    }

    fun clearLiveReceiverPresentation() {
        countdownJob?.cancel()
        countdownJob = null
        externalInvitationRevoker = null
        _receiverState.value = QrReceiverUiState.Idle
    }

    private fun startCountdown(initialSeconds: Int) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (sec in (initialSeconds - 1) downTo 0) {
                delay(1000L)
                val current = _receiverState.value
                if (current is QrReceiverUiState.Ready) {
                    if (sec <= 0) {
                        externalInvitationRevoker?.invoke()
                            ?: invitationAuthority.revokeActiveInvitation()
                        _receiverState.value = QrReceiverUiState.Expired
                        break
                    } else {
                        _receiverState.value = current.copy(secondsRemaining = sec)
                    }
                } else {
                    break
                }
            }
        }
    }

    /**
     * Rotates invitation ID and token immediately, revoking the previous invitation in authority.
     */
    fun refreshInvitation(
        deviceName: String,
        endpoints: List<LanEndpoint>,
        spkiSha256: String,
        networkGeneration: Long = 0L,
        ttlSeconds: Int = 120
    ) {
        // Mints a new invitation which atomically marks previous active invitation revoked
        startReceiving(
            deviceName = deviceName,
            endpoints = endpoints,
            spkiSha256 = spkiSha256,
            networkGeneration = networkGeneration,
            ttlSeconds = ttlSeconds
        )
    }

    /**
     * Explicitly revokes active invitation and returns receiver to Idle state.
     */
    fun stopReceiving() {
        countdownJob?.cancel()
        countdownJob = null
        externalInvitationRevoker?.invoke()
            ?: invitationAuthority.revokeActiveInvitation()
        externalInvitationRevoker = null
        _receiverState.value = QrReceiverUiState.Idle
    }

    /**
     * Called when active network drops, generation changes, or app is backgrounded before connection.
     * Immediately revokes outstanding invitations.
     */
    fun onNetworkChangedOrBackgrounded() {
        countdownJob?.cancel()
        countdownJob = null
        externalInvitationRevoker?.invoke()
            ?: invitationAuthority.revokeActiveInvitation()
        externalInvitationRevoker = null
        val current = _receiverState.value
        if (current is QrReceiverUiState.Ready || current is QrReceiverUiState.Starting) {
            _receiverState.value = QrReceiverUiState.Expired
        }
    }

    /**
     * Called when user copies the invitation text.
     */
    fun onInvitationCopied() {
        _events.tryEmit(QrPairingEvent.InvitationCopied)
    }

    // -------------------------------------------------------------------------
    // Sender Flow (Camera Scan, Paste, Confirmation, Replay Check)
    // -------------------------------------------------------------------------

    fun startScanning(hasFlashlight: Boolean = false) {
        this.deviceHasFlashlight = hasFlashlight || this.deviceHasFlashlight
        val current = _scannerState.value
        val torch = if (current is QrScannerUiState.Scanning) current.torchEnabled else false
        _scannerState.value = QrScannerUiState.Scanning(
            torchEnabled = torch,
            hasFlashlight = this.deviceHasFlashlight
        )
    }

    fun stopScanning() {
        _scannerState.value = QrScannerUiState.Idle
    }

    fun toggleTorch() {
        val current = _scannerState.value
        if (current is QrScannerUiState.Scanning && current.hasFlashlight) {
            _scannerState.value = current.copy(torchEnabled = !current.torchEnabled)
        }
    }

    /**
     * Handles decoded QR string from camera frame analysis.
     */
    fun onQrCodeScanned(rawText: String) {
        processInvitationText(rawText)
    }

    /**
     * Handles pasted invitation text from clipboard or manual entry.
     * Enforces IDENTICAL validation to camera decoding.
     */
    fun onInvitationPasted(rawText: String) {
        processInvitationText(rawText)
    }

    private fun processInvitationText(rawText: String) {
        val normalizedText = rawText.trim().let { trimmed ->
            val prefixIdx = trimmed.indexOf(InvitationCodec.URI_PREFIX)
            if (prefixIdx > 0) trimmed.substring(prefixIdx).trim() else trimmed
        }
        val invitation: LanInvitation = try {
            InvitationCodec.decode(normalizedText)
        } catch (_: InvitationException) {
            _scannerState.value = QrScannerUiState.Error(QrScanErrorReason.InvalidFormat)
            return
        } catch (_: Exception) {
            _scannerState.value = QrScannerUiState.Error(QrScanErrorReason.InvalidFormat)
            return
        }

        // 1. Validate transport, version, and profile
        if (invitation.transport != "lan-tls" || invitation.v != 1 || invitation.protocol != 1 || invitation.profile != 1) {
            _scannerState.value = QrScannerUiState.Error(QrScanErrorReason.IncompatibleVersion)
            return
        }

        // 2. Validate cryptographic pin
        if (invitation.spkiSha256.length != 64) {
            _scannerState.value = QrScannerUiState.Error(QrScanErrorReason.InvalidFormat)
            return
        }

        // 3. Validate endpoints
        if (invitation.endpoints.isEmpty() || invitation.endpoints.all { it.address.isBlank() || it.port <= 0 }) {
            _scannerState.value = QrScannerUiState.Error(QrScanErrorReason.NoValidEndpoints)
            return
        }

        // 4. Validate expiration timestamp (allow 300s device clock-skew grace on real hardware;
        // receiver strictly enforces monotonic expiration in InvitationAuthority.consume)
        val expiresAtSec = invitation.expiresAt.toLongOrNull()
        if (expiresAtSec != null) {
            val currentEpochSec = clock.epochMillis() / 1000L
            val clockSkewGraceSec = if (clock === AuthorityClock.SYSTEM) 300L else 0L
            if (currentEpochSec >= expiresAtSec + clockSkewGraceSec) {
                _scannerState.value = QrScannerUiState.Error(QrScanErrorReason.Expired)
                return
            }
        }

        // Invariant: Move to ConfirmationRequired state.
        // Connecting only occurs after explicit user Continue.
        _scannerState.value = QrScannerUiState.ConfirmationRequired(invitation)
    }

    /**
     * User explicitly taps "Connect" on the confirmation screen.
     */
    fun confirmConnection() {
        val current = _scannerState.value
        if (current is QrScannerUiState.ConfirmationRequired) {
            _scannerState.value = QrScannerUiState.Connecting(current.invitation)
            _events.tryEmit(QrPairingEvent.ConnectConfirmed(current.invitation))
        }
    }

    /**
     * User cancels connection on confirmation screen or dismisses dialog.
     */
    fun cancelConfirmation() {
        startScanning()
    }

    /**
     * Resets scanner error and returns to active scanning.
     */
    fun retryScan() {
        startScanning()
    }

    fun onCameraPermissionDenied() {
        _scannerState.value = QrScannerUiState.Error(QrScanErrorReason.CameraPermissionDenied)
    }

    fun onCameraUnavailable() {
        if (_scannerState.value is QrScannerUiState.Scanning || _scannerState.value is QrScannerUiState.Idle) {
            _scannerState.value = QrScannerUiState.Error(QrScanErrorReason.CameraUnavailable)
        }
    }

    fun onConnectionBlocked(message: String) {
        if (_scannerState.value is QrScannerUiState.Connecting) {
            _scannerState.value = QrScannerUiState.Error(QrScanErrorReason.Custom(message))
        }
    }

    fun setScannerStateForTesting(state: QrScannerUiState) {
        _scannerState.value = state
    }

    fun setReceiverStateForTesting(state: QrReceiverUiState) {
        countdownJob?.cancel()
        _receiverState.value = state
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
        externalInvitationRevoker?.invoke()
            ?: invitationAuthority.revokeActiveInvitation()
        externalInvitationRevoker = null
    }
}
