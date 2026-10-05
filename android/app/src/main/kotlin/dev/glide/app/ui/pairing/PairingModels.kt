package dev.glide.app.ui.pairing

import dev.glide.app.ui.discovery.DeviceType
import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId

/**
 * Immutable UI state representing the pairing and link verification phase of a transfer.
 */
sealed interface PairingUiState {
    data object Idle : PairingUiState

    data class Connecting(
        val peerId: PeerId,
        val peerName: String,
        val deviceType: DeviceType = DeviceType.Phone
    ) : PairingUiState

    data class Verifying(
        val peerId: PeerId,
        val peerName: String,
        val authDigits: String,
        val secondsRemaining: Int = 60,
        val localConfirmed: Boolean = false,
        val localDeviceName: String = "This device",
        val isSameNamePeer: Boolean = false,
        val deviceType: DeviceType = DeviceType.Phone
    ) : PairingUiState

    data class Connected(
        val peer: PeerDevice
    ) : PairingUiState

    data class Failed(
        val title: String,
        val message: String,
        val primaryActionLabel: String? = null,
        val secondaryActionLabel: String? = null,
        val troubleshootingHint: String? = null,
        val onPrimaryAction: (() -> Unit)? = null,
        val onSecondaryAction: (() -> Unit)? = null
    ) : PairingUiState
}
