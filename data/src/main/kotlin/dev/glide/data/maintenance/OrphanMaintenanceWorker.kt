package dev.glide.data.maintenance

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Interface implemented by Application to provide [OrphanMaintenanceCleaner] to [OrphanMaintenanceWorker].
 */
interface OrphanMaintenanceCleanerProvider {
    fun getOrphanMaintenanceCleaner(): OrphanMaintenanceCleaner
}

/**
 * Interface implemented by Application to provide the active session ID to [OrphanMaintenanceWorker].
 */
interface OrphanMaintenanceActiveSessionProvider {
    fun getActiveSessionId(): String?
}

/**
 * Periodic WorkManager worker executing bounded orphan maintenance and retention cleanup (GLIDE-031).
 */
class OrphanMaintenanceWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "GlideOrphanWorker"
        const val WORK_NAME = "glide_orphan_maintenance"

        /**
         * Testing hook to inject a cleaner directly in unit / instrumentation tests.
         */
        @VisibleForTesting
        var testCleaner: OrphanMaintenanceCleaner? = null

        /**
         * Testing hook to inject an active session supplier directly in unit / instrumentation tests.
         */
        @VisibleForTesting
        var testActiveSessionSupplier: (() -> String?)? = null
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting orphan maintenance job (runAttemptCount: $runAttemptCount)")

        if (isStopped) {
            Log.w(TAG, "Orphan maintenance worker cancelled before execution started.")
            return Result.failure()
        }

        val cleaner = testCleaner ?: (applicationContext as? OrphanMaintenanceCleanerProvider)?.getOrphanMaintenanceCleaner()
        if (cleaner == null) {
            Log.e(TAG, "OrphanMaintenanceCleaner not available from application context. Skipping.")
            return Result.failure()
        }

        val activeSessionSupplier = testActiveSessionSupplier
            ?: (applicationContext as? OrphanMaintenanceActiveSessionProvider)?.let { { it.getActiveSessionId() } }
            ?: { null }

        return try {
            val result = cleaner.runMaintenance(
                activeSessionSupplier = activeSessionSupplier,
                isCancelled = { isStopped }
            )

            if (isStopped) {
                Log.w(TAG, "Orphan maintenance cancelled during execution: $result")
                Result.failure()
            } else {
                Log.i(TAG, "Orphan maintenance completed successfully: $result")
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Orphan maintenance failed with error", e)
            Result.retry()
        }
    }
}
