package dev.glide.app

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import dev.glide.app.transfer.ActiveSessionTracker
import dev.glide.data.maintenance.OrphanMaintenanceActiveSessionProvider
import dev.glide.data.maintenance.OrphanMaintenanceCleaner
import dev.glide.data.maintenance.OrphanMaintenanceCleanerProvider
import dev.glide.data.maintenance.OrphanMaintenanceScheduler
import dev.glide.data.store.ProcessDeathReconciler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class GlideApplication : Application(), OrphanMaintenanceCleanerProvider, OrphanMaintenanceActiveSessionProvider {

    @Inject
    lateinit var reconciler: ProcessDeathReconciler

    @Inject
    lateinit var cleaner: OrphanMaintenanceCleaner

    override fun getOrphanMaintenanceCleaner(): OrphanMaintenanceCleaner = cleaner

    override fun getActiveSessionId(): String? = ActiveSessionTracker.activeSessionId.value

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Enable StrictMode in debug builds to guarantee zero main-thread I/O, database, or network stalls
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build()
            )
            Log.i("GlideApplication", "StrictMode ThreadPolicy & VmPolicy active for debug build")
        }

        appScope.launch {
            try {
                val summary = reconciler.reconcile()
                if (summary.totalActions > 0) {
                    Log.i("GlideReconciler", "Startup reconciliation complete: $summary")
                }
            } catch (e: Exception) {
                Log.e("GlideReconciler", "Startup reconciliation error", e)
            }
        }

        // Register periodic background orphan maintenance (GLIDE-031)
        try {
            OrphanMaintenanceScheduler.schedule(this)
        } catch (e: Exception) {
            Log.e("GlideOrphanMaintenance", "Failed to schedule periodic orphan maintenance", e)
        }
    }
}
