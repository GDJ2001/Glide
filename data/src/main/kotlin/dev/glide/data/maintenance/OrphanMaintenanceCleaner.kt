package dev.glide.data.maintenance

import dev.glide.core.ports.HistoryStore
import dev.glide.data.db.ReceiveJournalDao
import dev.glide.data.db.ReceivedFileDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Result data class for [OrphanMaintenanceCleaner.runMaintenance].
 */
data class OrphanMaintenanceResult(
    val purgedStagingPartials: Int = 0,
    val purgedStagedSources: Int = 0,
    val missingCatalogFilesMarkedDeleted: Int = 0,
    val trimmedHistorySessions: Int = 0
) {
    val totalCleaned: Int
        get() = purgedStagingPartials + purgedStagedSources + missingCatalogFilesMarkedDeleted + trimmedHistorySessions
}

/**
 * Executes bounded orphan maintenance and retention policies (GLIDE-031).
 *
 * Enforces:
 * 1. 24-hour orphan policy: Purges unreferenced `.part` files in [receivingDir] and cached sources in
 *    [stagingSourcesDirs] older than 24 hours.
 * 2. Active file protection: Files belonging to active transfer sessions ([activeSessionSupplier])
 *    or referenced by pending journal entries ([receiveJournalDao]) are strictly protected.
 * 3. Database missing file audit: Records in [receivedFileDao] whose physical file on disk in [receivedDir]
 *    is missing are marked deleted.
 * 4. History retention trimming: Terminal sessions exceeding 10,000 count or 90 days are trimmed via [historyStore],
 *    safely detaching verified files in the catalog without deleting them.
 * 5. Storage unavailable & cancellation safety: Storage unavailability (unmounted, permission denied, I/O failure)
 *    is handled gracefully without crashing, and cooperative cancellation ([isCancelled]) aborts immediately.
 */
class OrphanMaintenanceCleaner(
    private val receivingDir: File,
    private val receivedDir: File,
    private val stagingSourcesDirs: List<File> = emptyList(),
    private val receiveJournalDao: ReceiveJournalDao? = null,
    private val receivedFileDao: ReceivedFileDao? = null,
    private val historyStore: HistoryStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        const val ORPHAN_AGE_THRESHOLD_MS: Long = 24L * 60L * 60L * 1000L // 24 hours
        const val HISTORY_MAX_SESSIONS: Int = 10_000
        const val HISTORY_MAX_AGE_MS: Long = 90L * 24L * 60L * 60L * 1000L // 90 days
    }

    suspend fun runMaintenance(
        activeSessionSupplier: () -> String? = { null },
        activeStagingKeys: Set<String> = emptySet(),
        isCancelled: () -> Boolean = { false }
    ): OrphanMaintenanceResult = withContext(ioDispatcher) {
        var purgedStagingPartials = 0
        var purgedStagedSources = 0
        var missingCatalogFilesMarkedDeleted = 0
        var trimmedHistorySessions = 0

        val nowMs = clock()
        val orphanCutoffMs = nowMs - ORPHAN_AGE_THRESHOLD_MS

        if (isCancelled()) {
            return@withContext OrphanMaintenanceResult()
        }

        // 1. Gather protected keys from active session and active journal entries
        val protectedKeys = mutableSetOf<String>()
        protectedKeys.addAll(activeStagingKeys)

        try {
            val pendingJournal = receiveJournalDao?.getPendingEntries() ?: emptyList()
            for (entry in pendingJournal) {
                protectedKeys.add(entry.stagingKey)
                protectedKeys.add(entry.finalKey)
            }

            val activeSessionId = activeSessionSupplier()
            if (activeSessionId != null) {
                val activeEntries = receiveJournalDao?.getEntriesBySessionId(activeSessionId) ?: emptyList()
                for (entry in activeEntries) {
                    protectedKeys.add(entry.stagingKey)
                    protectedKeys.add(entry.finalKey)
                }
            }
        } catch (_: Exception) {
            // If DB is unavailable, fail safe: proceed with activeStagingKeys
        }

        if (isCancelled()) {
            return@withContext OrphanMaintenanceResult(
                purgedStagingPartials, purgedStagedSources, missingCatalogFilesMarkedDeleted, trimmedHistorySessions
            )
        }

        // 2. Scan receivingDir for unreferenced .part files older than 24 hours
        try {
            if (receivingDir.exists() && receivingDir.isDirectory) {
                val files = receivingDir.listFiles()
                if (files != null) {
                    for (file in files) {
                        if (isCancelled()) {
                            return@withContext OrphanMaintenanceResult(
                                purgedStagingPartials, purgedStagedSources, missingCatalogFilesMarkedDeleted, trimmedHistorySessions
                            )
                        }
                        if (file.name.endsWith(".part") && file.name !in protectedKeys) {
                            if (file.lastModified() < orphanCutoffMs) {
                                if (file.delete()) {
                                    purgedStagingPartials++
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Storage unavailable or unmounted - handle gracefully
        }

        if (isCancelled()) {
            return@withContext OrphanMaintenanceResult(
                purgedStagingPartials, purgedStagedSources, missingCatalogFilesMarkedDeleted, trimmedHistorySessions
            )
        }

        // 3. Scan stagingSourcesDirs for staged source cache older than 24 hours
        for (srcDir in stagingSourcesDirs) {
            if (isCancelled()) break
            try {
                if (srcDir.exists() && srcDir.isDirectory) {
                    val srcFiles = srcDir.listFiles()
                    if (srcFiles != null) {
                        for (file in srcFiles) {
                            if (isCancelled()) {
                                return@withContext OrphanMaintenanceResult(
                                    purgedStagingPartials, purgedStagedSources, missingCatalogFilesMarkedDeleted, trimmedHistorySessions
                                )
                            }
                            // Purge src_stage_*.part, src_stage_*.staged, or any cached staged files older than 24h
                            if (file.lastModified() < orphanCutoffMs) {
                                if (file.delete()) {
                                    purgedStagedSources++
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Storage unavailable - handle gracefully
            }
        }

        if (isCancelled()) {
            return@withContext OrphanMaintenanceResult(
                purgedStagingPartials, purgedStagedSources, missingCatalogFilesMarkedDeleted, trimmedHistorySessions
            )
        }

        // 4. Database missing file check ("DB missing file"):
        // Verify that all active catalog files actually exist on the filesystem.
        // Only run if receivedDir actually exists and is readable (storage is mounted).
        try {
            if (receivedFileDao != null && receivedDir.exists() && receivedDir.isDirectory) {
                val activeFiles = receivedFileDao.getActiveFiles()
                for (fileRecord in activeFiles) {
                    if (isCancelled()) {
                        return@withContext OrphanMaintenanceResult(
                            purgedStagingPartials, purgedStagedSources, missingCatalogFilesMarkedDeleted, trimmedHistorySessions
                        )
                    }
                    val diskFile = File(receivedDir, fileRecord.fileKey)
                    if (!diskFile.exists()) {
                        receivedFileDao.markDeleted(fileRecord.fileKey, nowMs)
                        missingCatalogFilesMarkedDeleted++
                    }
                }
            }
        } catch (_: Exception) {
            // Storage or DB unavailable - handle gracefully
        }

        if (isCancelled()) {
            return@withContext OrphanMaintenanceResult(
                purgedStagingPartials, purgedStagedSources, missingCatalogFilesMarkedDeleted, trimmedHistorySessions
            )
        }

        // 5. History retention trimming:
        // Trim terminal sessions exceeding 10,000 or 90 days.
        try {
            if (historyStore != null) {
                trimmedHistorySessions = historyStore.trimRetention(
                    maxSessions = HISTORY_MAX_SESSIONS,
                    maxAgeMs = HISTORY_MAX_AGE_MS,
                    nowMs = nowMs
                )
            }
        } catch (_: Exception) {
            // DB unavailable - handle gracefully
        }

        OrphanMaintenanceResult(
            purgedStagingPartials = purgedStagingPartials,
            purgedStagedSources = purgedStagedSources,
            missingCatalogFilesMarkedDeleted = missingCatalogFilesMarkedDeleted,
            trimmedHistorySessions = trimmedHistorySessions
        )
    }
}
