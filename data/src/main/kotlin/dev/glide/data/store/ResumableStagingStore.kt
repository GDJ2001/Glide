package dev.glide.data.store

import dev.glide.core.resume.ContentDigestTree
import dev.glide.core.resume.ResumeToken
import dev.glide.data.db.ResumableSessionDao
import dev.glide.data.db.ResumableSessionEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Sandboxed staging manager for resumable chunked file transfers (ADR-0015, GLIDE-070).
 *
 * Enforces:
 * 1. Sandboxed staging in cacheDir/staging/resumable/<session_id>/.
 * 2. Per-chunk SHA-256 validation via ContentDigestTree.
 * 3. 24-hour expiration TTL auto-purge.
 * 4. Storage pressure FIFO eviction when available space is low.
 * 5. Immediate deletion on explicit user cancel/decline.
 * 6. Zero unverified bytes exposed to public storage.
 */
class ResumableStagingStore(
    private val stagingBaseDir: File,
    private val resumableSessionDao: ResumableSessionDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    init {
        if (!stagingBaseDir.exists()) {
            stagingBaseDir.mkdirs()
        }
    }

    suspend fun initSession(
        sessionId: String,
        itemId: String,
        itemName: String,
        totalBytes: Long,
        chunkSize: Int,
        rootHashHex: String,
        nowMs: Long = System.currentTimeMillis()
    ): File = withContext(ioDispatcher) {
        val sessionDir = File(stagingBaseDir, sessionId)
        if (!sessionDir.exists()) {
            sessionDir.mkdirs()
        }

        val entity = ResumableSessionEntity(
            sessionId = sessionId,
            itemId = itemId,
            itemName = itemName,
            totalBytes = totalBytes,
            chunkSize = chunkSize,
            verifiedChunks = 0L,
            rootHashHex = rootHashHex,
            createdAtMs = nowMs,
            expiresAtMs = nowMs + ResumeToken.MAX_TTL_MILLIS,
            stagingPath = sessionDir.absolutePath
        )
        resumableSessionDao.insertOrUpdate(entity)
        sessionDir
    }

    suspend fun writeAndVerifyChunk(
        sessionId: String,
        chunkIndex: Long,
        chunkData: ByteArray,
        digestTree: ContentDigestTree
    ): Boolean = withContext(ioDispatcher) {
        if (!digestTree.verifyChunk(chunkIndex.toInt(), chunkData)) {
            return@withContext false
        }

        val sessionDir = File(stagingBaseDir, sessionId)
        if (!sessionDir.exists()) sessionDir.mkdirs()

        val chunkFile = File(sessionDir, "chunk_%06d.part".format(chunkIndex))
        FileOutputStream(chunkFile).use { it.write(chunkData) }

        val session = resumableSessionDao.getBySessionId(sessionId)
        if (session != null && chunkIndex >= session.verifiedChunks) {
            resumableSessionDao.updateVerifiedChunks(sessionId, chunkIndex + 1)
        }
        true
    }

    suspend fun getVerifiedChunkCount(sessionId: String): Long = withContext(ioDispatcher) {
        resumableSessionDao.getBySessionId(sessionId)?.verifiedChunks ?: 0L
    }

    suspend fun assembleVerifiedFile(
        sessionId: String,
        destinationFile: File
    ): Boolean = withContext(ioDispatcher) {
        val session = resumableSessionDao.getBySessionId(sessionId) ?: return@withContext false
        val sessionDir = File(stagingBaseDir, sessionId)
        if (!sessionDir.exists()) return@withContext false

        val verifiedCount = session.verifiedChunks
        if (verifiedCount <= 0) return@withContext false

        destinationFile.parentFile?.mkdirs()
        FileOutputStream(destinationFile).use { out ->
            for (i in 0 until verifiedCount) {
                val chunkFile = File(sessionDir, "chunk_%06d.part".format(i))
                if (!chunkFile.exists()) return@withContext false
                chunkFile.inputStream().use { it.copyTo(out) }
            }
        }
        true
    }

    suspend fun purgeSession(sessionId: String) = withContext(ioDispatcher) {
        val sessionDir = File(stagingBaseDir, sessionId)
        if (sessionDir.exists()) {
            sessionDir.deleteRecursively()
        }
        resumableSessionDao.deleteBySessionId(sessionId)
    }

    suspend fun purgeExpiredSessions(currentMs: Long = System.currentTimeMillis()): Int = withContext(ioDispatcher) {
        val expired = resumableSessionDao.getExpiredSessions(currentMs)
        for (session in expired) {
            val dir = File(session.stagingPath)
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
        resumableSessionDao.deleteExpired(currentMs)
    }

    suspend fun evictUnderStoragePressure(
        minFreeBytes: Long,
        checkFreeSpace: () -> Long = { stagingBaseDir.usableSpace }
    ): Int = withContext(ioDispatcher) {
        var evictedCount = 0
        var freeSpace = checkFreeSpace()
        if (freeSpace >= minFreeBytes) return@withContext 0

        val sessions = resumableSessionDao.getAllResumableSessions()
        // Oldest sessions first (last element in created_at DESC list, or sort by createdAtMs ASC)
        val oldestFirst = sessions.sortedBy { it.createdAtMs }
        for (session in oldestFirst) {
            purgeSession(session.sessionId)
            evictedCount++
            freeSpace = checkFreeSpace()
            if (freeSpace >= minFreeBytes) break
        }
        evictedCount
    }
}
