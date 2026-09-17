package dev.glide.app.ui.transfer

import dev.glide.core.coordinator.ItemPhase
import dev.glide.core.coordinator.SessionPhase
import dev.glide.core.coordinator.SessionState
import dev.glide.core.domain.DeviceRole

/**
 * UI presentation status of exporting a verified received file to public storage (GLIDE-023).
 */
sealed interface ExportUiStatus {
    data object Idle : ExportUiStatus
    data object Exporting : ExportUiStatus
    data class Success(val destinationName: String) : ExportUiStatus
    data class Failed(val message: String) : ExportUiStatus
}

/**
 * UI representation of an individual verified item within a completed transfer batch (LAN-023).
 */
data class VerifiedItemUiState(
    val itemId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val committedFileKey: String? = null,
    val digestHex: String? = null,
    val relativePath: String? = null
)

/**
 * Immutable UI state representing the active transfer session (GLIDE-015).
 */
sealed interface TransferUiState {
    val sessionId: String
    val role: DeviceRole

    data object Idle : TransferUiState {
        override val sessionId: String = ""
        override val role: DeviceRole = DeviceRole.Sender
    }

    data class Connecting(
        override val sessionId: String,
        override val role: DeviceRole,
        val peerName: String
    ) : TransferUiState

    data class AwaitingConsent(
        override val sessionId: String,
        override val role: DeviceRole,
        val peerName: String,
        val itemCount: Int,
        val totalBytes: Long,
        val firstItemName: String,
        val items: List<dev.glide.core.domain.TransferItem> = emptyList(),
        val secondsRemaining: Int = 60,
        val storageAvailableBytes: Long? = null,
        val storageRequiredBytes: Long = totalBytes,
        val isSameNamePeer: Boolean = false,
        val peerDeviceType: dev.glide.app.ui.discovery.DeviceType = dev.glide.app.ui.discovery.DeviceType.Phone
    ) : TransferUiState

    data class Transferring(
        override val sessionId: String,
        override val role: DeviceRole,
        val peerName: String,
        val itemName: String,
        val transferredBytes: Long,
        val totalBytes: Long,
        val progressFraction: Float,
        val speedBytesPerSec: Long = 0L,
        val itemIndex: Int = 1,
        val itemCount: Int = 1,
        val isStalled: Boolean = false,
        val peerDeviceType: dev.glide.app.ui.discovery.DeviceType = dev.glide.app.ui.discovery.DeviceType.Phone
    ) : TransferUiState

    data class Verifying(
        override val sessionId: String,
        override val role: DeviceRole,
        val peerName: String,
        val itemName: String,
        val itemIndex: Int = 1,
        val itemCount: Int = 1,
        val totalBytes: Long = 0L,
        val peerDeviceType: dev.glide.app.ui.discovery.DeviceType = dev.glide.app.ui.discovery.DeviceType.Phone
    ) : TransferUiState

    data class Completed(
        override val sessionId: String,
        override val role: DeviceRole,
        val peerName: String,
        val totalBytes: Long,
        val itemName: String,
        val committedFileKey: String? = null,
        val itemCount: Int = 1,
        val items: List<dev.glide.core.domain.TransferItem> = emptyList(),
        val verifiedItems: List<VerifiedItemUiState> = emptyList(),
        val peerDeviceType: dev.glide.app.ui.discovery.DeviceType = dev.glide.app.ui.discovery.DeviceType.Phone
    ) : TransferUiState

    data class Cancelled(
        override val sessionId: String,
        override val role: DeviceRole,
        val reason: String = "Transfer cancelled",
        val completedCount: Int = 0,
        val totalCount: Int = 1,
        val peerDeviceType: dev.glide.app.ui.discovery.DeviceType = dev.glide.app.ui.discovery.DeviceType.Phone
    ) : TransferUiState

    data class Failed(
        override val sessionId: String,
        override val role: DeviceRole,
        val message: String,
        val code: String,
        val isRecoverable: Boolean = false,
        val isInterrupted: Boolean = false,
        val isReceiptUnconfirmed: Boolean = false,
        val unfinishedCount: Int = 0,
        val totalCount: Int = 0,
        val canRetry: Boolean = false,
        val peerName: String = "Peer device",
        val peerDeviceType: dev.glide.app.ui.discovery.DeviceType = dev.glide.app.ui.discovery.DeviceType.Phone
    ) : TransferUiState {
        val completedCount: Int get() = (totalCount - unfinishedCount).coerceAtLeast(0)
    }
}

/**
 * Pure mapping from domain [SessionState] to [TransferUiState].
 */
fun SessionState.toUiState(
    peerDisplayName: String = "Peer device",
    committedFileKey: String? = null,
    committedItemKeys: Map<String, String>? = null
): TransferUiState {
    val sid = sessionId.value
    val currentBatch = batch
    val items = currentBatch?.items ?: emptyList()
    val currentItem = currentItemState?.item ?: items.firstOrNull()
    val itemName = currentItem?.name ?: "Unknown file"
    val totalBytes = currentBatch?.totalBytes ?: (currentItem?.sizeBytes ?: 0L)
    val batchTransferredBytes = run {
        var completedBytes = 0L
        if (currentBatch != null && items.isNotEmpty()) {
            val currentIdx = if (currentItem != null) items.indexOfFirst { it.id == currentItem.id } else -1
            for (i in 0 until items.size) {
                val item = items[i]
                if (currentIdx >= 0 && i < currentIdx) {
                    val state = itemStates[item.id]
                    completedBytes += state?.transferredBytes ?: item.sizeBytes
                } else if (currentIdx >= 0 && i == currentIdx) {
                    completedBytes += (currentItemState?.transferredBytes ?: 0L)
                }
            }
            completedBytes
        } else {
            currentItemState?.transferredBytes ?: 0L
        }
    }
    val fraction = if (totalBytes > 0L) (batchTransferredBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    val itemCount = items.size.coerceAtLeast(1)
    val itemIndex = if (currentItem != null && items.isNotEmpty()) {
        val idx = items.indexOfFirst { it.id == currentItem.id }
        if (idx >= 0) idx + 1 else 1
    } else 1
    val peerType = dev.glide.app.ui.discovery.DeviceType.fromDeviceName(peerDisplayName)

    val verifiedItemList = itemStates.values
        .filter { it.phase == ItemPhase.Verified }
        .map { state ->
            val key = committedItemKeys?.get(state.item.id.value)
                ?: if (state.item.id.value == currentItem?.id?.value) committedFileKey else null
            VerifiedItemUiState(
                itemId = state.item.id.value,
                displayName = state.item.name,
                mimeType = dev.glide.app.util.MimeSanitizer.sanitize(state.item.mimeType),
                sizeBytes = state.item.sizeBytes,
                committedFileKey = key,
                digestHex = state.actualDigest?.value ?: state.expectedDigest?.value,
                relativePath = state.item.relativePath
            )
        }
        .ifEmpty {
            if (items.isNotEmpty()) {
                items.map { it ->
                    val key = committedItemKeys?.get(it.id.value)
                        ?: if (it.id.value == currentItem?.id?.value) committedFileKey else null
                    VerifiedItemUiState(
                        itemId = it.id.value,
                        displayName = it.name,
                        mimeType = dev.glide.app.util.MimeSanitizer.sanitize(it.mimeType),
                        sizeBytes = it.sizeBytes,
                        committedFileKey = key,
                        relativePath = it.relativePath
                    )
                }
            } else emptyList()
        }

    return when (phase) {
        SessionPhase.Idle, SessionPhase.Negotiating -> {
            TransferUiState.Connecting(
                sessionId = sid,
                role = role,
                peerName = peerDisplayName
            )
        }

        SessionPhase.Offering, SessionPhase.AwaitingConsent -> {
            if (role == DeviceRole.Receiver && currentBatch != null) {
                TransferUiState.AwaitingConsent(
                    sessionId = sid,
                    role = role,
                    peerName = peerDisplayName,
                    itemCount = items.size,
                    totalBytes = totalBytes,
                    firstItemName = itemName,
                    items = items,
                    storageRequiredBytes = totalBytes,
                    peerDeviceType = peerType
                )
            } else if (role == DeviceRole.Sender) {
                TransferUiState.Transferring(
                    sessionId = sid,
                    role = role,
                    peerName = peerDisplayName,
                    itemName = itemName,
                    transferredBytes = 0L,
                    totalBytes = totalBytes,
                    progressFraction = 0f,
                    itemIndex = itemIndex,
                    itemCount = itemCount,
                    peerDeviceType = peerType
                )
            } else {
                TransferUiState.Connecting(
                    sessionId = sid,
                    role = role,
                    peerName = peerDisplayName
                )
            }
        }

        SessionPhase.PreparingItem, SessionPhase.TransferringItem -> {
            TransferUiState.Transferring(
                sessionId = sid,
                role = role,
                peerName = peerDisplayName,
                itemName = itemName,
                transferredBytes = batchTransferredBytes,
                totalBytes = totalBytes,
                progressFraction = fraction,
                itemIndex = itemIndex,
                itemCount = itemCount,
                peerDeviceType = peerType
            )
        }

        SessionPhase.VerifyingItem -> {
            TransferUiState.Verifying(
                sessionId = sid,
                role = role,
                peerName = peerDisplayName,
                itemName = itemName,
                itemIndex = itemIndex,
                itemCount = itemCount,
                totalBytes = totalBytes,
                peerDeviceType = peerType
            )
        }

        SessionPhase.AwaitingReceipt -> {
            if (role == DeviceRole.Receiver) {
                TransferUiState.Completed(
                    sessionId = sid,
                    role = role,
                    peerName = peerDisplayName,
                    totalBytes = totalBytes,
                    itemName = itemName,
                    committedFileKey = committedFileKey,
                    itemCount = itemCount,
                    items = items,
                    verifiedItems = verifiedItemList,
                    peerDeviceType = peerType
                )
            } else {
                TransferUiState.Verifying(
                    sessionId = sid,
                    role = role,
                    peerName = peerDisplayName,
                    itemName = itemName,
                    itemIndex = itemIndex,
                    itemCount = itemCount,
                    totalBytes = totalBytes,
                    peerDeviceType = peerType
                )
            }
        }

        SessionPhase.Completed -> {
            TransferUiState.Completed(
                sessionId = sid,
                role = role,
                peerName = peerDisplayName,
                totalBytes = totalBytes,
                itemName = itemName,
                committedFileKey = committedFileKey,
                itemCount = itemCount,
                items = items,
                verifiedItems = verifiedItemList,
                peerDeviceType = peerType
            )
        }

        SessionPhase.Cancelled -> {
            TransferUiState.Cancelled(
                sessionId = sid,
                role = role,
                reason = failure?.message ?: "Transfer cancelled",
                peerDeviceType = peerType
            )
        }

        SessionPhase.Failed, SessionPhase.Interrupted, SessionPhase.DeliveryUnconfirmed -> {
            val isUnconfirmed = phase == SessionPhase.DeliveryUnconfirmed || failure?.code == "DELIVERY_UNCONFIRMED"
            val isInterrupted = phase == SessionPhase.Interrupted || failure?.code in setOf("PEER_LOST", "TRANSFER_INTERRUPTED")
            val unfinished = unfinishedCount.coerceAtLeast(1)
            val total = items.size.coerceAtLeast(1)
            val canRetry = (failure?.isRecoverable == true || isInterrupted || isUnconfirmed) && role == DeviceRole.Sender && unfinished > 0

            TransferUiState.Failed(
                sessionId = sid,
                role = role,
                message = failure?.message ?: if (isInterrupted) "Transfer interrupted" else "Transfer failed",
                code = failure?.code ?: if (isInterrupted) "TRANSFER_INTERRUPTED" else "UNKNOWN_FAILURE",
                isRecoverable = failure?.isRecoverable ?: isInterrupted,
                isInterrupted = isInterrupted,
                isReceiptUnconfirmed = isUnconfirmed,
                unfinishedCount = unfinished,
                totalCount = total,
                canRetry = canRetry,
                peerName = peerDisplayName,
                peerDeviceType = peerType
            )
        }
    }
}
