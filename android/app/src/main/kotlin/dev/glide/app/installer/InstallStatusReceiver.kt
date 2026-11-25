package dev.glide.app.installer

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal object InstallEvents {
    private val _completed = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 16)
    val completed = _completed.asSharedFlow()

    fun notifyCompleted(packageName: String) {
        _completed.tryEmit(packageName)
    }
}

class InstallStatusReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val prompt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                prompt?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { launchIntent ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val options = ActivityOptions.makeBasic()
                            .setPendingIntentBackgroundActivityStartMode(
                                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                            )
                            .toBundle()
                        val pending = PendingIntent.getActivity(
                            context,
                            intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, 0),
                            launchIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        runCatching { pending.send(context, 0, null, null, null, null, options) }
                            .onFailure { runCatching { context.startActivity(launchIntent, options) } }
                    } else {
                        runCatching { context.startActivity(launchIntent) }
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME).orEmpty()
                InstallEvents.notifyCompleted(pkg)
                Toast.makeText(context, "App installed", Toast.LENGTH_SHORT).show()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> Toast.makeText(context, "Android couldn’t install this app.", Toast.LENGTH_LONG).show()
        }
    }
}
