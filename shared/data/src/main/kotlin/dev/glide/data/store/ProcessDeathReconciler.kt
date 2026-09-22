package dev.glide.data.store

import dev.glide.data.db.ItemDao
import dev.glide.data.db.ReceivedFileDao
import dev.glide.data.db.SessionDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Summary of actions taken by [ProcessDeathReconciler.reconcile].
 */
data class ReconciliationSummary(
    val reconciledCommits: Int,
    val interruptedSessions: Int,
    val interruptedItems: Int,
    val purgedPartials: Int,
    val missingFilesMarkedDeleted: Int
) {
    val totalActions: Int
        get() = reconciledCommits + interruptedSessions + interruptedItems + purgedPartials + missingFilesMarkedDeleted
}

/**
 * Coordinates recovery across storage, journal, and transfer history on process restart.
 *
 * Implements recovery specifications from docs/android/STORAGE_AND_BACKGROUND.md and
 * docs/architecture/STATE_AND_DATA_MODEL.md:
 * - Recovers every commit boundary (pre-commit, pre-rename, post-rename, post-catalog).
 * - Distinguishes live session ownership from abandoned sessions.
 * - Removes unverified leftovers while preserving verified file catalog entries and history.
 * - Resolves missing-file discrepancies (durable record cannot claim missing file).
 * - Guarantees 100% idempotent execution on app launch.
 */
class ProcessDeathReconciler(
    private val receiveStore: AndroidReceiveStore,
    private val sessionDao: SessionDao,
    private val itemDao: ItemDao,
    private val receivedFileDao: ReceivedFileDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Executes process-death reconciliation across storage, journals, and history.
     *
     * @param activeSessionId Session ID currently owned and running in this process (if any).
     *                        Active sessions and their staged files are preserved from interruption.
     */
    suspend fun reconcile(activeSessionId: String? = null): ReconciliationSummary = withContext(ioDispatcher) {
        var interruptedSessions = 0
        var interruptedItems = 0

        // Step 1: Identify active staging keys for the activeSessionId (if any)
        val activeStagingKeys = mutableSetOf<String>()
        if (activeSessionId != null) {
            val activeItems = itemDao.getItemsForSession(activeSessionId)
            activeItems.forEach { item ->
                activeStagingKeys.add("${item.itemId}.part")
            }
        }

        // Step 2: Reconcile filesystem & receive journal (commits, partials, missing files)
        val storageResult = receiveStore.reconcileDetailed(activeStagingKeys)

        // Step 3: Reconcile abandoned transfer sessions and items in history
        val activeDbSessions = sessionDao.getActiveSessions()
        for (session in activeDbSessions) {
            if (session.sessionId == activeSessionId) {
                // Active process ownership: currently running in this process! Do not interrupt.
                continue
            }

            // Session was abandoned due to process death!
            val items = itemDao.getItemsForSession(session.sessionId)
            for (item in items) {
                if (item.outcome != null) {
                    // Item was already completed/failed before crash, preserve its outcome
                    continue
                }

                // Check if this item was verified and committed into received_files:
                val catalogRecord = receivedFileDao.getByOriginatingItemId(item.itemId)
                val finalFile = if (catalogRecord != null) File(receiveStore.receivedDir, catalogRecord.fileKey) else null

                if (catalogRecord != null && catalogRecord.deletedAtMs == null && finalFile?.exists() == true) {
                    // Item was successfully committed and exists on disk! Mark completed in history
                    itemDao.updateOutcome(
                        itemId = item.itemId,
                        outcome = "Completed",
                        actualBytes = catalogRecord.length,
                        digest = catalogRecord.digest,
                        failureCode = null
                    )
                } else {
                    // Item was interrupted mid-stream or unattempted
                    itemDao.updateOutcome(
                        itemId = item.itemId,
                        outcome = "Interrupted",
                        actualBytes = item.actualBytes,
                        digest = item.digest,
                        failureCode = "INTERRUPTED"
                    )
                    interruptedItems++
                }
            }

            // Mark session as Interrupted with terminal timestamp and failure code
            sessionDao.updateOutcome(
                sessionId = session.sessionId,
                outcome = "Interrupted",
                endedAtMs = clock(),
                failureCode = "INTERRUPTED"
            )
            interruptedSessions++
        }

        ReconciliationSummary(
            reconciledCommits = storageResult.reconciledCommits,
            interruptedSessions = interruptedSessions,
            interruptedItems = interruptedItems,
            purgedPartials = storageResult.purgedPartials,
            missingFilesMarkedDeleted = storageResult.missingFilesMarkedDeleted
        )
    }
}
