package dev.glide.data.store

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.ports.HistoryStore
import dev.glide.core.ports.ItemRecord
import dev.glide.core.ports.SessionRecord
import dev.glide.core.ports.SessionSummary
import dev.glide.data.db.ItemDao
import dev.glide.data.db.ItemEntity
import dev.glide.data.db.ReceivedFileDao
import dev.glide.data.db.SessionDao
import dev.glide.data.db.SessionEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Production implementation of [HistoryStore] port backed by Room database.
 * Conforms to persistence schema in docs/architecture/STATE_AND_DATA_MODEL.md.
 */
class RoomHistoryStore(
    private val sessionDao: SessionDao,
    private val itemDao: ItemDao,
    private val receivedFileDao: ReceivedFileDao? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : HistoryStore {

    override suspend fun recordSession(record: SessionRecord) = withContext(ioDispatcher) {
        val entity = SessionEntity(
            sessionId = record.sessionId.value,
            batchId = record.batchId.value,
            role = record.role.name,
            peerLabel = record.peerLabel,
            startedAtMs = record.startedAtMs,
            endedAtMs = record.endedAtMs,
            outcome = record.outcome,
            failureCode = null
        )
        sessionDao.insert(entity)
    }

    override suspend fun recordItem(record: ItemRecord) = withContext(ioDispatcher) {
        val entity = ItemEntity(
            itemId = record.itemId.value,
            sessionId = record.sessionId.value,
            ordinal = record.ordinal,
            displayName = record.displayName,
            mime = record.mimeType,
            offeredBytes = record.offeredBytes,
            actualBytes = record.actualBytes,
            digest = record.digest?.value,
            outcome = record.outcome,
            failureCode = null,
            relativePath = record.relativePath
        )
        itemDao.insert(entity)
    }

    override suspend fun updateSessionOutcome(
        sessionId: SessionId,
        outcome: String,
        endedAtMs: Long
    ) = withContext(ioDispatcher) {
        sessionDao.updateOutcome(
            sessionId = sessionId.value,
            outcome = outcome,
            endedAtMs = endedAtMs
        )
    }

    override suspend fun getSession(sessionId: SessionId): SessionRecord? = withContext(ioDispatcher) {
        val entity = sessionDao.getBySessionId(sessionId.value) ?: return@withContext null
        SessionRecord(
            sessionId = SessionId(entity.sessionId),
            batchId = BatchId(entity.batchId),
            role = parseRole(entity.role),
            peerLabel = entity.peerLabel,
            startedAtMs = entity.startedAtMs,
            endedAtMs = entity.endedAtMs,
            outcome = entity.outcome
        )
    }

    override suspend fun updateItemOutcome(
        itemId: ItemId,
        outcome: String,
        actualBytes: Long?,
        digest: Sha256Digest?
    ) = withContext(ioDispatcher) {
        itemDao.updateOutcome(
            itemId = itemId.value,
            outcome = outcome,
            actualBytes = actualBytes,
            digest = digest?.value
        )
    }

    override suspend fun getItemsForSession(sessionId: SessionId): List<ItemRecord> = withContext(ioDispatcher) {
        val entities = itemDao.getItemsForSession(sessionId.value)
        entities.map { entity ->
            ItemRecord(
                itemId = ItemId(entity.itemId),
                sessionId = SessionId(entity.sessionId),
                ordinal = entity.ordinal,
                displayName = entity.displayName,
                mimeType = entity.mime,
                offeredBytes = entity.offeredBytes,
                actualBytes = entity.actualBytes,
                digest = entity.digest?.let { Sha256Digest(it) },
                outcome = entity.outcome,
                relativePath = entity.relativePath
            )
        }
    }

    override suspend fun getSessionsPaged(
        role: DeviceRole?,
        limit: Int,
        offset: Int
    ): List<SessionSummary> = withContext(ioDispatcher) {
        val roleStr = role?.name
        val sessionsWithItems = sessionDao.getSessionsWithItemsPaged(roleStr, limit, offset)
        sessionsWithItems.map(::toSessionSummary)
    }

    override fun observeSessions(role: DeviceRole?, limit: Int): Flow<List<SessionSummary>> {
        require(limit > 0) { "limit must be positive" }
        return sessionDao.observeSessionsWithItems(role?.name, limit)
            .map { sessions -> sessions.map(::toSessionSummary) }
            .flowOn(ioDispatcher)
    }

    override suspend fun getSessionCount(role: DeviceRole?): Int = withContext(ioDispatcher) {
        sessionDao.getSessionCount(role?.name)
    }

    override suspend fun trimRetention(
        maxSessions: Int,
        maxAgeMs: Long,
        nowMs: Long
    ): Int = withContext(ioDispatcher) {
        var pruned = 0
        val cutoffMs = nowMs - maxAgeMs

        // 1. Detach and delete terminal sessions older than 90 days (maxAgeMs)
        receivedFileDao?.detachItemIdsOlderThan(cutoffMs)
        pruned += sessionDao.deleteTerminalOlderThan(cutoffMs)

        // 2. Detach and delete terminal sessions exceeding maxSessions
        val terminalCount = sessionDao.getTerminalSessionCount()
        if (terminalCount > maxSessions) {
            receivedFileDao?.detachItemIdsExceedingMax(maxSessions)
            pruned += sessionDao.deleteTerminalExceedingMax(maxSessions)
        }

        pruned
    }

    override suspend fun clearHistory(): Int = withContext(ioDispatcher) {
        receivedFileDao?.detachAllItemIds()
        sessionDao.deleteTerminalSessions()
    }

    override suspend fun deleteSession(sessionId: SessionId): Boolean = withContext(ioDispatcher) {
        val existing = sessionDao.getBySessionId(sessionId.value) ?: return@withContext false
        receivedFileDao?.detachItemIdsForSession(sessionId.value)
        sessionDao.delete(sessionId.value)
        true
    }

    suspend fun getReceivedFileKey(itemId: ItemId): String? = withContext(ioDispatcher) {
        receivedFileDao?.getByOriginatingItemId(itemId.value)?.fileKey
    }

    private fun parseRole(role: String): DeviceRole {
        return DeviceRole.entries.firstOrNull { it.name.equals(role, ignoreCase = true) } ?: DeviceRole.Receiver
    }

    private fun toSessionSummary(swi: dev.glide.data.db.SessionWithItems): SessionSummary =
        SessionSummary(
            sessionId = SessionId(swi.session.sessionId),
            batchId = BatchId(swi.session.batchId),
            role = parseRole(swi.session.role),
            peerLabel = swi.session.peerLabel,
            startedAtMs = swi.session.startedAtMs,
            endedAtMs = swi.session.endedAtMs,
            outcome = swi.session.outcome,
            failureCode = swi.session.failureCode,
            itemCount = swi.items.size,
            totalBytes = swi.items.sumOf { it.actualBytes ?: it.offeredBytes }
        )
}
