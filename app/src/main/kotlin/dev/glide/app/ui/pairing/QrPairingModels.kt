package dev.glide.app.ui.pairing

import android.graphics.Bitmap
import dev.glide.core.pairing.LanInvitation

/**
 * Immutable UI state representing the Receive QR code display and countdown lifecycle (LAN-018).
 */
sealed interface QrReceiverUiState {
    /**
     * Initial state before QR display is requested.
     */
    data object Idle : QrReceiverUiState

    /**
     * Preparing listener, network scope, and generating lease key.
     */
    data object Starting : QrReceiverUiState

    /**
     * Listener is ready, invitation is minted, and countdown is actively ticking.
     */
    data class Ready(
        val invitation: LanInvitation,
        val qrBitmap: Bitmap?,
        val secondsRemaining: Int,
        val localDeviceName: String,
        val endpointsSummary: String
    ) : QrReceiverUiState

    /**
     * The 120-second countdown reached 0. Active invitation has been revoked in authority.
     * User can refresh to generate a new invitation.
     */
    data object Expired : QrReceiverUiState

    /**
     * Listener failed to start, network is unavailable, or active VPN blocks LAN transport.
     */
    data class Error(
        val message: String,
        val canRetry: Boolean = true
    ) : QrReceiverUiState
}

/**
 * Error reasons encountered during QR scanning or invitation pasting (LAN-018, Section 20).
 */
sealed interface QrScanErrorReason {
    /** QR code syntax or schema is invalid */
    data object InvalidFormat : QrScanErrorReason

    /** Invitation expiration timestamp is in the past */
    data object Expired : QrScanErrorReason

    /** Unsupported transport (not lan-tls), protocol version, or profile */
    data object IncompatibleVersion : QrScanErrorReason

    /** No valid on-link IPv4/IPv6 endpoints found in invitation */
    data object NoValidEndpoints : QrScanErrorReason

    /** Camera runtime permission was denied */
    data object CameraPermissionDenied : QrScanErrorReason

    /** Device has no camera or camera failed to open */
    data object CameraUnavailable : QrScanErrorReason

    /** Pin or certificate verification failed against the out-of-band invitation */
    data object AuthenticationMismatch : QrScanErrorReason

    /** Connection or handshake timed out, possibly due to AP/client isolation */
    data object ConnectionTimeout : QrScanErrorReason

    /** Target peer is already engaged in an active transfer session */
    data object PeerBusy : QrScanErrorReason

    /** Device has no active local network or Wi-Fi connection */
    data object NoLocalNetwork : QrScanErrorReason

    /** Specific error message (sanitized before display) */
    data class Custom(val message: String) : QrScanErrorReason
}

/**
 * Immutable UI state representing the Sender's QR scanner / paste lifecycle (LAN-018).
 */
sealed interface QrScannerUiState {
    /** Scanner inactive */
    data object Idle : QrScannerUiState

    /** Camera active, analyzing incoming frames */
    data class Scanning(
        val torchEnabled: Boolean = false,
        val hasFlashlight: Boolean = false
    ) : QrScannerUiState

    /**
     * A valid QR invitation was decoded or pasted.
     * Invariant: Requires explicit user confirmation before any network connection is opened.
     */
    data class ConfirmationRequired(
        val invitation: LanInvitation
    ) : QrScannerUiState

    /** Outbound connection initiated after explicit confirmation */
    data class Connecting(
        val invitation: LanInvitation
    ) : QrScannerUiState

    /** Connection handshake succeeded */
    data class Success(
        val invitation: LanInvitation
    ) : QrScannerUiState

    /** Scanning or parsing error */
    data class Error(
        val reason: QrScanErrorReason
    ) : QrScannerUiState
}

/**
 * One-shot pairing UI events.
 */
sealed interface QrPairingEvent {
    data object InvitationCopied : QrPairingEvent
    data class ConnectConfirmed(val invitation: LanInvitation) : QrPairingEvent
}
