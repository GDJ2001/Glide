package dev.glide.core.ports

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.CommittedFileHandle
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.StagingHandle
import dev.glide.core.domain.TransferFailure
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class SourceMetadata(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val relativePath: String? = null
)

open class StorageException(
    val failure: TransferFailure,
    message: String = failure.message,
    cause: Throwable? = null
) : IOException(message, cause)

class SourceAccessException(
    failure: TransferFailure,
    message: String = failure.message,
    cause: Throwable? = null
) : StorageException(failure, message, cause)

class ReceiveStoreException(
    failure: TransferFailure,
    message: String = failure.message,
    cause: Throwable? = null
) : StorageException(failure, message, cause)

interface SourceAccess {
    suspend fun validateSource(handle: SourceHandle): SourceMetadata
    suspend fun openSource(handle: SourceHandle): InputStream
}

interface ReceiveStore {
    suspend fun hasAvailableSpace(requiredBytes: Long): Boolean
    suspend fun prepareStaging(
        sessionId: SessionId,
        itemId: ItemId,
        expectedBytes: Long,
        displayName: String = "Received file",
        mimeType: String = "application/octet-stream",
        relativePath: String? = null
    ): StagingHandle
    suspend fun openStagingOutput(handle: StagingHandle): OutputStream
    suspend fun commitVerified(handle: StagingHandle, digest: Sha256Digest): CommittedFileHandle
    suspend fun discardStaging(handle: StagingHandle)
}

data class SessionRecord(
    val sessionId: SessionId,
    val batchId: BatchId,
    val role: DeviceRole,
    val peerLabel: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val outcome: String? = null
)

data class ItemRecord(
    val itemId: ItemId,
    val sessionId: SessionId,
    val ordinal: Int,
    val displayName: String,
    val mimeType: String,
    val offeredBytes: Long,
    val actualBytes: Long? = null,
    val digest: Sha256Digest? = null,
    val outcome: String? = null,
    val relativePath: String? = null
)

data class SessionSummary(
    val sessionId: SessionId,
    val batchId: BatchId,
    val role: DeviceRole,
    val peerLabel: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val outcome: String? = null,
    val failureCode: String? = null,
    val itemCount: Int = 0,
    val totalBytes: Long = 0L
)

interface HistoryStore {
    suspend fun recordSession(record: SessionRecord)
    suspend fun recordItem(record: ItemRecord)
    suspend fun updateSessionOutcome(sessionId: SessionId, outcome: String, endedAtMs: Long)
    suspend fun getSession(sessionId: SessionId): SessionRecord?
    suspend fun updateItemOutcome(itemId: ItemId, outcome: String, actualBytes: Long? = null, digest: Sha256Digest? = null) {}
    suspend fun getItemsForSession(sessionId: SessionId): List<ItemRecord> = emptyList()
    suspend fun getSessionsPaged(role: DeviceRole? = null, limit: Int = 50, offset: Int = 0): List<SessionSummary> = emptyList()
    suspend fun getSessionCount(role: DeviceRole? = null): Int = 0
    suspend fun trimRetention(maxSessions: Int = 10_000, maxAgeMs: Long = 90L * 24 * 60 * 60 * 1000L, nowMs: Long = System.currentTimeMillis()): Int = 0
    suspend fun clearHistory(): Int = 0
    suspend fun deleteSession(sessionId: SessionId): Boolean = false
}
