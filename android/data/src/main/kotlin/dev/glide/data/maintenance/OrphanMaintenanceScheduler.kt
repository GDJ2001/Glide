package dev.glide.data.maintenance

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules periodic WorkManager background orphan maintenance (GLIDE-031).
 *
 * Constraints:
 * - Battery not low
 * - Storage not low
 * - Device idle
 *
 * Policy:
 * - ExistingPeriodicWorkPolicy.KEEP: preserves existing schedule without re-triggering.
 * - Interval: 24 hours.
 */
object OrphanMaintenanceScheduler {

    const val UNIQUE_WORK_NAME = OrphanMaintenanceWorker.WORK_NAME

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .setRequiresDeviceIdle(true)
            .build()

        val maintenanceWorkRequest = PeriodicWorkRequestBuilder<OrphanMaintenanceWorker>(
            repeatInterval = 24,
            repeatIntervalTimeUnit = TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .addTag(UNIQUE_WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            maintenanceWorkRequest
        )
    }

    /**
     * Enqueues an immediate one-time maintenance job (used for testing or explicit debug audits).
     */
    fun scheduleImmediateOneTime(context: Context) {
        val oneTimeRequest = OneTimeWorkRequestBuilder<OrphanMaintenanceWorker>()
            .addTag("${UNIQUE_WORK_NAME}_onetime")
            .build()

        WorkManager.getInstance(context).enqueue(oneTimeRequest)
    }
}
