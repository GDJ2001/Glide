package dev.glide.app.ui.selection

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferItem
import java.util.Locale
import java.util.UUID

/**
 * Represents a single file selected for transfer within a batch.
 */
data class SelectedFileItem(
    val id: ItemId,
    val handle: SourceHandle,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val isAvailable: Boolean = true,
    val errorMessage: String? = null
)

/**
 * Immutable UI states for source selection, multi-file batch aggregation, and verification.
 */
sealed interface SelectionUiState {

    /**
     * Initial idle state before user launches the system picker.
     */
    data object Idle : SelectionUiState

    /**
     * Resolving metadata and verifying source accessibility for selected URIs.
     */
    data class Validating(
        val currentCount: Int = 0,
        val totalCount: Int = 0,
        val currentUriString: String = ""
    ) : SelectionUiState

    /**
     * Staging an unknown-length or cloud-backed source into local app-private cache before offer.
     */
    data class StagingSource(
        val displayName: String,
        val bytesStaged: Long,
        val isCloudProvider: Boolean = false
    ) : SelectionUiState

    /**
     * Streaming progress state for single-file preview hashing.
     */
    data class Hashing(
        val displayName: String,
        val sizeBytes: Long,
        val bytesHashed: Long
    ) : SelectionUiState {
        val progressFraction: Float = if (sizeBytes > 0) {
            (bytesHashed.toFloat() / sizeBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    /**
     * Single-file ready state (retained for backward-compatibility with single-file slice).
     */
    data class Ready(
        val displayName: String,
        val sizeBytes: Long,
        val mimeType: String,
        val digest: Sha256Digest,
        val handle: SourceHandle
    ) : SelectionUiState

    /**
     * Active batch of selected files with summary, count, and ceiling enforcement.
     */
    data class BatchReady(
        val items: List<SelectedFileItem>
    ) : SelectionUiState {
        val itemCount: Int = items.size
        val validItems: List<SelectedFileItem> = items.filter { it.isAvailable }
        val validItemCount: Int = validItems.size
        val totalBytes: Long = validItems.sumOf { it.sizeBytes }
        val hasUnavailableItems: Boolean = items.any { !it.isAvailable }
        val isLimitExceeded: Boolean = items.size > ProtocolLimits.MAX_ITEMS_PER_BATCH
        val isTotalBytesExceeded: Boolean = totalBytes > ProtocolLimits.MAX_BATCH_BYTES
        val canProceed: Boolean = items.isNotEmpty() && !hasUnavailableItems && !isLimitExceeded && !isTotalBytesExceeded

        /**
         * Converts valid items into a domain [TransferBatch] for protocol offer pagination.
         */
        fun toTransferBatch(batchId: BatchId = BatchId(UUID.randomUUID().toString())): TransferBatch {
            check(canProceed) { "Cannot construct TransferBatch when batch cannot proceed" }
            return TransferBatch(
                id = batchId,
                items = validItems.map { item ->
                    TransferItem(
                        id = item.id,
                        name = item.displayName,
                        mimeType = item.mimeType,
                        sizeBytes = item.sizeBytes
                    )
                }
            )
        }
    }

    /**
     * Validation or streaming failed (e.g. deleted file, unknown size, oversized).
     */
    data class Error(
        val message: String,
        val isRecoverable: Boolean = true
    ) : SelectionUiState

    /**
     * Selection or hashing cancelled by the user.
     */
    data object Cancelled : SelectionUiState
}

/**
 * Formats byte counts into human-readable strings (e.g., "10.0 MB", "500 B").
 */
fun formatFileSize(bytes: Long): String {
    if (bytes < 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt().coerceAtMost(4)
    val pre = "KMGT"[exp - 1]
    val value = bytes / Math.pow(1024.0, exp.toDouble())
    return String.format(Locale.US, "%.1f %sB", value, pre)
}
