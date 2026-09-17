package dev.glide.app.ui.failure

import dev.glide.app.ui.discovery.BlockedReason
import dev.glide.app.ui.pairing.QrScanErrorReason
import dev.glide.core.domain.FailureCategory
import dev.glide.core.domain.TransferFailure

/**
 * Structured, user-actionable presentation model for transfer, discovery, and pairing errors (LAN-020).
 *
 * Invariants (Section 20):
 * - User wording strictly follows the Section 20 taxonomy.
 * - Zero raw exception exposure: stack traces, raw error messages, unredacted paths, and raw IP addresses
 *   must NEVER be displayed in [userWording] or [title].
 * - Honest troubleshooting: explains router/network AP isolation without claiming the app can fix it.
 */
data class ActionableError(
    val title: String,
    val userWording: String,
    val primaryActionLabel: String,
    val secondaryActionLabel: String? = null,
    val troubleshootingHint: String? = null,
    val category: FailureCategory,
    val isRecoverable: Boolean = true
)

object ActionableErrorMapper {

    private val IPV4_REGEX = Regex("""\b(?:[0-9]{1,3}\.){3}[0-9]{1,3}\b""")
    private val FILE_PATH_REGEX = Regex("""(/[a-zA-Z0-9_.\-]+)+|([a-zA-Z]:\\[a-zA-Z0-9_.\-\\]+)""")

    /**
     * Sanitizes any dynamic text by stripping out IP addresses, file paths, and raw exception class names.
     */
    fun sanitizeText(input: String): String {
        return input
            .replace(IPV4_REGEX, "[local network address]")
            .replace(FILE_PATH_REGEX, "[file location]")
            .replace("java.net.", "")
            .replace("java.io.", "")
            .replace("SocketTimeoutException", "Connection timed out")
            .replace("ConnectException", "Connection failed")
            .replace("SocketException", "Network connection lost")
    }

    /**
     * Maps a [BlockedReason] from discovery/readiness into an [ActionableError].
     */
    fun fromBlockedReason(reason: BlockedReason): ActionableError {
        return when (reason) {
            is BlockedReason.BluetoothDisabled -> ActionableError(
                title = "Bluetooth is turned off",
                userWording = "Turn on Bluetooth to discover and connect with nearby devices directly.",
                primaryActionLabel = "Open Bluetooth settings",
                secondaryActionLabel = "Check again",
                category = FailureCategory.Environment,
                isRecoverable = true
            )
            is BlockedReason.WifiDisabled -> ActionableError(
                title = "Wi-Fi is turned off",
                userWording = "Turn on Wi-Fi for local high-speed direct peer-to-peer file transfer.",
                primaryActionLabel = "Open Wi-Fi settings",
                secondaryActionLabel = "Check again",
                category = FailureCategory.Environment,
                isRecoverable = true
            )
            is BlockedReason.LanPermissionDenied -> ActionableError(
                title = "Local network access denied",
                userWording = "Allow local-network access to connect; Settings after permanent denial",
                primaryActionLabel = "Open App settings",
                secondaryActionLabel = "Check again",
                category = FailureCategory.Prerequisite,
                isRecoverable = true
            )
            is BlockedReason.NoLocalNetwork -> ActionableError(
                title = "No local network available",
                userWording = "Connect both devices to a reachable local network; Retry",
                primaryActionLabel = "Open Wi-Fi settings",
                secondaryActionLabel = "Retry",
                troubleshootingHint = "Ensure both devices are connected to the same Wi-Fi router or personal hotspot.",
                category = FailureCategory.Environment,
                isRecoverable = true
            )
            is BlockedReason.VpnActive -> ActionableError(
                title = "VPN is active",
                userWording = "An active VPN is routing local traffic. Disconnect or configure VPN local network bypass to use LAN sharing.",
                primaryActionLabel = "Open VPN settings",
                secondaryActionLabel = "Check again",
                troubleshootingHint = "Most VPN clients provide a 'Bypass local network' or 'Allow local LAN traffic' setting.",
                category = FailureCategory.Environment,
                isRecoverable = true
            )
            is BlockedReason.PermissionsRequired -> ActionableError(
                title = "Permissions required",
                userWording = "Glide requires Nearby devices and Location permissions to discover local peers at point of use.",
                primaryActionLabel = "Open App settings",
                secondaryActionLabel = "Check again",
                category = FailureCategory.Prerequisite,
                isRecoverable = true
            )
            is BlockedReason.TransportError -> ActionableError(
                title = "Discovery unavailable",
                userWording = "Could not start local discovery. Check device radios and try again.",
                primaryActionLabel = "Open Settings",
                secondaryActionLabel = "Retry",
                category = FailureCategory.Environment,
                isRecoverable = true
            )
        }
    }

    /**
     * Maps a [QrScanErrorReason] into an [ActionableError].
     */
    fun fromQrScanErrorReason(reason: QrScanErrorReason): ActionableError {
        return when (reason) {
            is QrScanErrorReason.InvalidFormat -> ActionableError(
                title = "Invalid QR code",
                userWording = "This code is not a valid Glide transfer invitation.",
                primaryActionLabel = "Try again",
                secondaryActionLabel = "Paste invitation",
                category = FailureCategory.Protocol,
                isRecoverable = true
            )
            is QrScanErrorReason.Expired -> ActionableError(
                title = "Invitation expired",
                userWording = "This code has expired or was used. Ask the receiver to refresh it",
                primaryActionLabel = "Try again",
                secondaryActionLabel = "Paste invitation",
                category = FailureCategory.Security,
                isRecoverable = true
            )
            is QrScanErrorReason.IncompatibleVersion -> ActionableError(
                title = "Incompatible version",
                userWording = "Devices use incompatible Glide versions; no insecure fallback",
                primaryActionLabel = "Try again",
                category = FailureCategory.Compatibility,
                isRecoverable = false
            )
            is QrScanErrorReason.NoValidEndpoints -> ActionableError(
                title = "No reachable network address",
                userWording = "Connect both devices to a reachable local network; Retry",
                primaryActionLabel = "Try again",
                secondaryActionLabel = "Paste invitation",
                troubleshootingHint = "Both devices must be connected to the same local Wi-Fi subnet without client isolation.",
                category = FailureCategory.Link,
                isRecoverable = true
            )
            is QrScanErrorReason.CameraPermissionDenied -> ActionableError(
                title = "Camera permission denied",
                userWording = "Camera access is needed to scan QR codes. You can paste the invitation instead.",
                primaryActionLabel = "Grant permission",
                secondaryActionLabel = "Paste invitation",
                category = FailureCategory.Prerequisite,
                isRecoverable = true
            )
            is QrScanErrorReason.CameraUnavailable -> ActionableError(
                title = "Camera unavailable",
                userWording = "The camera could not be accessed. You can paste the invitation instead.",
                primaryActionLabel = "Paste invitation",
                secondaryActionLabel = "Try again",
                category = FailureCategory.Environment,
                isRecoverable = true
            )
            is QrScanErrorReason.AuthenticationMismatch -> ActionableError(
                title = "Verification failed",
                userWording = "Could not verify this device. Scan a new code from its screen",
                primaryActionLabel = "Scan new code",
                category = FailureCategory.Security,
                isRecoverable = true
            )
            is QrScanErrorReason.ConnectionTimeout -> ActionableError(
                title = "Connection timed out",
                userWording = "Connection timed out; check receiver and network isolation",
                primaryActionLabel = "Retry",
                secondaryActionLabel = "Paste invitation",
                troubleshootingHint = "Many guest Wi-Fi networks and office routers enable 'AP Isolation' or 'Client Isolation', which blocks direct device-to-device communication. Ensure both devices are on a private Wi-Fi network.",
                category = FailureCategory.Lifecycle,
                isRecoverable = true
            )
            is QrScanErrorReason.PeerBusy -> ActionableError(
                title = "Device is busy",
                userWording = "Device is already transferring; try again when it finishes",
                primaryActionLabel = "Retry later",
                category = FailureCategory.Capacity,
                isRecoverable = true
            )
            is QrScanErrorReason.NoLocalNetwork -> ActionableError(
                title = "No local network",
                userWording = "Connect both devices to a reachable local network; Retry",
                primaryActionLabel = "Retry",
                category = FailureCategory.Environment,
                isRecoverable = true
            )
            is QrScanErrorReason.Custom -> ActionableError(
                title = "Pairing error",
                userWording = sanitizeText(reason.message).ifBlank { "Could not complete pairing. Please try again." },
                primaryActionLabel = "Try again",
                secondaryActionLabel = "Paste invitation",
                category = FailureCategory.System,
                isRecoverable = true
            )
        }
    }

    /**
     * Maps a core [TransferFailure] into an [ActionableError] adhering to the Section 20 taxonomy.
     */
    fun fromTransferFailure(failure: TransferFailure): ActionableError {
        return when (failure) {
            is TransferFailure.PermissionDenied -> ActionableError(
                title = "Permission required",
                userWording = "Allow local-network access to connect; Settings after permanent denial",
                primaryActionLabel = "Open Settings",
                category = failure.category,
                isRecoverable = failure.isRecoverable
            )
            is TransferFailure.DiscoveryUnavailable -> ActionableError(
                title = "Discovery unavailable",
                userWording = "Connect both devices to a reachable local network; Retry",
                primaryActionLabel = "Retry",
                category = failure.category,
                isRecoverable = failure.isRecoverable
            )
            is TransferFailure.PeerLost -> ActionableError(
                title = "Peer disconnected",
                userWording = "Connect both devices to a reachable local network; Retry",
                primaryActionLabel = "Retry",
                category = failure.category,
                isRecoverable = failure.isRecoverable
            )
            is TransferFailure.ConnectionRejected -> ActionableError(
                title = "Connection declined",
                userWording = "The recipient declined the connection request.",
                primaryActionLabel = "Back",
                category = failure.category,
                isRecoverable = false
            )
            is TransferFailure.UserRejected -> ActionableError(
                title = "Transfer declined",
                userWording = "The transfer was declined on the recipient device.",
                primaryActionLabel = "Back",
                category = failure.category,
                isRecoverable = false
            )
            is TransferFailure.AuthenticationMismatch -> ActionableError(
                title = "Verification failed",
                userWording = "Could not verify this device. Scan a new code from its screen",
                primaryActionLabel = "Scan new code",
                category = failure.category,
                isRecoverable = false
            )
            is TransferFailure.Timeout -> ActionableError(
                title = "Connection timed out",
                userWording = "Connection timed out; check receiver and network isolation",
                primaryActionLabel = "Retry",
                troubleshootingHint = "Many guest Wi-Fi networks and office routers enable 'AP Isolation' or 'Client Isolation', which blocks direct communication between devices.",
                category = failure.category,
                isRecoverable = failure.isRecoverable
            )
            is TransferFailure.Busy -> ActionableError(
                title = "Device is busy",
                userWording = "Device is already transferring; try again when it finishes",
                primaryActionLabel = "Retry later",
                category = failure.category,
                isRecoverable = failure.isRecoverable
            )
            is TransferFailure.MalformedMessage, is TransferFailure.ProtocolOrder, is TransferFailure.LimitExceeded -> ActionableError(
                title = "Invalid data received",
                userWording = "Connection stopped because the data was invalid",
                primaryActionLabel = "New transfer",
                category = failure.category,
                isRecoverable = false
            )
            is TransferFailure.SourceUnavailable -> ActionableError(
                title = "File unavailable",
                userWording = "This file is no longer accessible",
                primaryActionLabel = "Choose files again",
                category = failure.category,
                isRecoverable = true
            )
            is TransferFailure.DestinationUnavailable, is TransferFailure.InsufficientStorage -> ActionableError(
                title = "Storage full",
                userWording = "Free space or choose an available destination",
                primaryActionLabel = "Check storage",
                category = failure.category,
                isRecoverable = true
            )
            is TransferFailure.TransferInterrupted, is TransferFailure.SystemCancelled -> ActionableError(
                title = "Transfer interrupted",
                userWording = "Transfer interrupted. Verified files are kept",
                primaryActionLabel = "Retry remaining",
                category = failure.category,
                isRecoverable = true
            )
            is TransferFailure.IntegrityMismatch -> ActionableError(
                title = "Verification failed",
                userWording = "This file could not be verified and was not saved as complete",
                primaryActionLabel = "Resend file",
                category = failure.category,
                isRecoverable = false
            )
            is TransferFailure.DeliveryUnconfirmed -> ActionableError(
                title = "Delivery unconfirmed",
                userWording = "Delivery could not be confirmed. Check the receiving device before resending",
                primaryActionLabel = "Check receiver",
                category = failure.category,
                isRecoverable = true
            )
            is TransferFailure.UnsupportedProtocol -> ActionableError(
                title = "Incompatible version",
                userWording = "Devices use incompatible Glide versions; no insecure fallback",
                primaryActionLabel = "Back",
                category = failure.category,
                isRecoverable = false
            )
            is TransferFailure.UserCancelled -> ActionableError(
                title = "Transfer cancelled",
                userWording = "The transfer was cancelled.",
                primaryActionLabel = "Back",
                category = failure.category,
                isRecoverable = false
            )
            is TransferFailure.ReadFailure -> ActionableError(
                title = "Read error",
                userWording = "This file is no longer accessible",
                primaryActionLabel = "Choose files again",
                category = failure.category,
                isRecoverable = true
            )
            is TransferFailure.WriteFailure, is TransferFailure.ExportFailure -> ActionableError(
                title = "Save error",
                userWording = "Free space or choose an available destination",
                primaryActionLabel = "Check storage",
                category = failure.category,
                isRecoverable = true
            )
            is TransferFailure.Unknown -> ActionableError(
                title = "Transfer error",
                userWording = "Connection stopped because the data was invalid",
                primaryActionLabel = "Try again",
                category = failure.category,
                isRecoverable = false
            )
        }
    }
}
