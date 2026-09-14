package dev.glide.app.ui.discovery

import androidx.compose.runtime.Immutable
import dev.glide.core.domain.PeerId

/**
 * High-level device categories for discovered peers.
 */
enum class DeviceType {
    Phone,
    Tablet,
    Computer,
    Unknown;

    companion object {
        fun fromDeviceName(name: String): DeviceType {
            val lower = name.lowercase()
            return when {
                lower.contains("tab") || lower.contains("pad") -> Tablet
                lower.contains("mac") || lower.contains("book") || lower.contains("pc") || lower.contains("laptop") || lower.contains("desktop") -> Computer
                lower.contains("pixel") || lower.contains("phone") || lower.contains("galaxy") || lower.contains("iphone") -> Phone
                else -> Phone
            }
        }
    }
}

/**
 * Immutable representation of a discovered peer endpoint in the UI.
 *
 * Enforces stable keys via [id], accessible device names, and non-jumping list behavior
 * by maintaining [isAvailable] rather than abruptly deleting elements from the list.
 */
@Immutable
data class DiscoveredPeer(
    val id: PeerId,
    val displayName: String,
    val deviceType: DeviceType = DeviceType.Phone,
    val isAvailable: Boolean = true,
    val isConnecting: Boolean = false,
    val isSelected: Boolean = false
)

/**
 * Blocked reasons preventing discovery or receive readiness from operating.
 */
sealed interface BlockedReason {
    data object BluetoothDisabled : BlockedReason
    data object WifiDisabled : BlockedReason
    data object PermissionsRequired : BlockedReason
    data class TransportError(val message: String) : BlockedReason
}

/**
 * Immutable UI state governing the sender discovery route.
 */
sealed interface DiscoveryUiState {
    /**
     * Active scanning state. Peers are listed deterministically with stable keys.
     */
    data class Searching(
        val peers: List<DiscoveredPeer> = emptyList(),
        val secondsElapsed: Int = 0,
        val selectedPeerId: PeerId? = null
    ) : DiscoveryUiState

    /**
     * 120-second timeout state when no peer was found or selected.
     */
    data class TimedOut(
        val peers: List<DiscoveredPeer> = emptyList()
    ) : DiscoveryUiState

    /**
     * Blocked state due to hardware radios off, missing permissions, or transport errors.
     */
    data class Blocked(
        val reason: BlockedReason
    ) : DiscoveryUiState
}

/**
 * Immutable UI state governing the receiver readiness route.
 */
sealed interface ReceiveReadinessUiState {
    /**
     * Active advertising state displaying local visible name and countdown timer.
     */
    data class Advertising(
        val localDeviceName: String,
        val secondsRemaining: Int = 120
    ) : ReceiveReadinessUiState

    /**
     * Incoming connection initiated by a sender peer.
     */
    data class IncomingConnection(
        val peer: DiscoveredPeer,
        val authDigits: String
    ) : ReceiveReadinessUiState

    /**
     * 120-second discoverability timeout reached.
     */
    data object TimedOut : ReceiveReadinessUiState

    /**
     * Blocked state due to radios or permissions.
     */
    data class Blocked(
        val reason: BlockedReason
    ) : ReceiveReadinessUiState
}
