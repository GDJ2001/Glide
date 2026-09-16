package dev.glide.app.ui.selection

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.TransferBatch
import java.util.concurrent.ConcurrentHashMap

/**
 * Data holder representing a prepared multi-file transfer batch and its complete
 * mapping of [ItemId] to [SourceHandle] (LAN-004).
 */
data class PreparedBatch(
    val batch: TransferBatch,
    val sourceMap: Map<ItemId, SourceHandle>
)

/**
 * Thread-safe singleton store preserving selected multi-file batches and their
 * [ItemId] -> [SourceHandle] maps across selection, discovery, and transfer session startup (LAN-004).
 */
object SelectedTransferStore {

    private val batches = ConcurrentHashMap<String, PreparedBatch>()

    @Volatile
    private var activeBatchId: String? = null

    /**
     * Stores a prepared batch and its source handle mapping, marking it as the active batch.
     */
    fun store(batch: TransferBatch, sourceMap: Map<ItemId, SourceHandle>) {
        val prepared = PreparedBatch(batch = batch, sourceMap = sourceMap.toMap())
        batches[batch.id.value] = prepared
        activeBatchId = batch.id.value
    }

    /**
     * Retrieves the prepared batch for the specified [batchId], or the active batch if null.
     */
    fun get(batchId: BatchId? = null): PreparedBatch? {
        val id = batchId?.value ?: activeBatchId ?: return null
        return batches[id]
    }

    /**
     * Retrieves the active prepared batch, if any.
     */
    fun getActive(): PreparedBatch? = activeBatchId?.let { batches[it] }

    /**
     * Convenience method to retrieve the source map for the specified [batchId] or active batch.
     */
    fun getSourceMap(batchId: BatchId? = null): Map<ItemId, SourceHandle> {
        return get(batchId)?.sourceMap ?: emptyMap()
    }

    /**
     * Clears the specified batch or the active batch if none specified.
     */
    fun clear(batchId: BatchId? = null) {
        val id = batchId?.value ?: activeBatchId
        if (id != null) {
            batches.remove(id)
            if (activeBatchId == id) {
                activeBatchId = null
            }
        }
    }

    /**
     * Resets the store for testing isolation.
     */
    fun resetForTesting() {
        batches.clear()
        activeBatchId = null
    }
}
