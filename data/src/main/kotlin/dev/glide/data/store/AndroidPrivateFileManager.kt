package dev.glide.data.store

import dev.glide.core.ports.PrivateFileManager
import dev.glide.core.ports.ReceivedFileInfo
import dev.glide.core.ports.StorageSummary
import dev.glide.data.db.ReceiveJournalDao
import dev.glide.data.db.ReceivedFileDao
import dev.glide.data.db.SessionDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/**
 * Production implementation of [PrivateFileManager].
 *
 * Implements specifications in docs/android/STORAGE_AND_BACKGROUND.md and
 * docs/architecture/STATE_AND_DATA_MODEL.md:
 * - Manages the app-private received file library under internal storage.
 * - Enforces active file protection: files currently being received, staged, or
 *   belonging to non-terminal sessions are strictly protected against deletion.
 * - Deletes physical files from storage and records deletion in the catalog.
 * - Discloses separate storage count/bytes and active file preservation.
 */
class AndroidPrivateFileManager(
    private val receivedFilesDir: File,
    private val fileCatalogDao: ReceivedFileDao,
    private val receiveJournalDao: ReceiveJournalDao,
    private val sessionDao: SessionDao,
    private val activeItemIdsProvider: (() -> Set<String>)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PrivateFileManager {

    override suspend fun getStorageSummary(): StorageSummary = withContext(ioDispatcher) {
        val files = fileCatalogDao.getActiveFiles()
        val activeKeys = resolveActiveFileKeys()

        var totalBytes = 0L
        var activeCount = 0

        for (file in files) {
            val fileOnDisk = File(receivedFilesDir, file.fileKey)
            val size = if (fileOnDisk.exists()) fileOnDisk.length() else file.length
            totalBytes += size

            if (file.fileKey in activeKeys || (file.originatingItemId != null && file.originatingItemId in activeKeys)) {
                activeCount++
            }
        }

        StorageSummary(
            totalFiles = files.size,
            totalBytes = totalBytes,
            activeFiles = activeCount
        )
    }

    override suspend fun getReceivedFiles(): List<ReceivedFileInfo> = withContext(ioDispatcher) {
        val files = fileCatalogDao.getActiveFiles()
        val activeKeys = resolveActiveFileKeys()

        files.map { file ->
            val fileOnDisk = File(receivedFilesDir, file.fileKey)
            val size = if (fileOnDisk.exists()) fileOnDisk.length() else file.length
            val isActive = file.fileKey in activeKeys ||
                    (file.originatingItemId != null && file.originatingItemId in activeKeys)

            ReceivedFileInfo(
                fileKey = file.fileKey,
                displayName = file.displayName,
                sizeBytes = size,
                receivedAtMs = file.receivedAtMs,
                isActive = isActive
            )
        }
    }

    override suspend fun deleteReceivedFile(fileKey: String): Result<Unit> = withContext(ioDispatcher) {
        val fileEntity = fileCatalogDao.getByFileKey(fileKey)
            ?: return@withContext Result.failure(FileNotFoundException("File not found in catalog: $fileKey"))

        val activeKeys = resolveActiveFileKeys()
        val isActive = fileKey in activeKeys ||
                (fileEntity.originatingItemId != null && fileEntity.originatingItemId in activeKeys)

        if (isActive) {
            return@withContext Result.failure(
                IllegalStateException("Cannot delete active file in transfer: ${fileEntity.displayName}")
            )
        }

        // Delete physical file on disk
        val physicalFile = File(receivedFilesDir, fileKey)
        if (physicalFile.exists()) {
            physicalFile.delete()
        }

        // Mark deleted in Room catalog
        fileCatalogDao.markDeleted(fileKey, clock())
        Result.success(Unit)
    }

    override suspend fun deleteAllReceivedFiles(): Result<Int> = withContext(ioDispatcher) {
        val files = fileCatalogDao.getActiveFiles()
        val activeKeys = resolveActiveFileKeys()

        var deletedCount = 0
        for (file in files) {
            val isActive = file.fileKey in activeKeys ||
                    (file.originatingItemId != null && file.originatingItemId in activeKeys)

            if (!isActive) {
                val physicalFile = File(receivedFilesDir, file.fileKey)
                if (physicalFile.exists()) {
                    physicalFile.delete()
                }
                fileCatalogDao.markDeleted(file.fileKey, clock())
                deletedCount++
            }
        }

        Result.success(deletedCount)
    }

    private suspend fun resolveActiveFileKeys(): Set<String> {
        val activeKeys = mutableSetOf<String>()

        // 1. Keys and item IDs from pending journal entries (phase != COMPLETED)
        val pendingJournal = receiveJournalDao.getPendingEntries()
        for (entry in pendingJournal) {
            activeKeys.add(entry.finalKey)
            activeKeys.add(entry.stagingKey)
            activeKeys.add(entry.itemId)
        }

        // 2. Active non-terminal sessions in Room
        val activeSessions = sessionDao.getActiveSessions()
        for (session in activeSessions) {
            activeKeys.add(session.sessionId)
        }

        // 3. Runtime in-memory active item IDs provider
        activeItemIdsProvider?.invoke()?.let { runtimeActive ->
            activeKeys.addAll(runtimeActive)
        }

        return activeKeys
    }
}
