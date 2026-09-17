package dev.glide.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.glide.app.ui.transfer.TransferUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Android Foreground Service holding active connected-device transfer execution lifetime (GLIDE-028).
 *
 * Requirements & Constraints:
 * - Manifest declaration: android:exported="false", android:foregroundServiceType="connectedDevice".
 * - Platform deadline: Posts an immediate foreground notification upon start to satisfy OS deadlines.
 * - Non-sticky: Returns START_NOT_STICKY; never automatically reconnects with stale state after process death.
 * - Terminal teardown: Automatically stops foreground and calls stopSelf() when the active session completes, fails, or cancels.
 */
@AndroidEntryPoint
class TransferService : Service() {

    @Inject
    lateinit var transferRuntime: TransferRuntime

    internal var runtimeOverride: TransferRuntime? = null

    private fun runtime(): TransferRuntime = runtimeOverride ?: transferRuntime

    private val serviceJob = SupervisorJob()
    val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)

    internal var customNotificationManager: dev.glide.app.notification.TransferNotificationManager? = null
    val transferNotificationManager: dev.glide.app.notification.TransferNotificationManager by lazy {
        customNotificationManager ?: dev.glide.app.notification.TransferNotificationManager(this)
    }

    private var notificationObserverJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        runtime().attachServiceScope(serviceScope) {
            handleTerminalTeardown()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_START -> {
                startForegroundWithNotification()
                startNotificationUpdates()
                // Active transfer execution is already owned by the runtime attached in onCreate().
            }

            ACTION_CANCEL -> {
                val targetSessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
                handleCancelAction(targetSessionId)
            }

            ACTION_STOP -> {
                runtime().stopActiveSession()
                handleTerminalTeardown()
            }

            ACTION_ACCEPT -> {
                val targetSessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
                handleAcceptAction(targetSessionId)
            }

            ACTION_REJECT -> {
                val targetSessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
                handleRejectAction(targetSessionId)
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Processes an accept action request with exact-session validation.
     */
    internal fun handleAcceptAction(targetSessionId: String?): Boolean {
        val activeSessionId = runtime().getActiveSessionId()
        val shouldAccept = TransferServicePolicy.shouldAcceptSessionAction(targetSessionId, activeSessionId)
        if (shouldAccept) {
            runtime().dispatch(TransferRuntimeCommand.AcceptOffer(targetSessionId!!))
        }
        return shouldAccept
    }

    /**
     * Processes a reject action request with exact-session validation.
     */
    internal fun handleRejectAction(targetSessionId: String?, reason: String = "USER_REJECTED"): Boolean {
        val activeSessionId = runtime().getActiveSessionId()
        val shouldReject = TransferServicePolicy.shouldAcceptSessionAction(targetSessionId, activeSessionId)
        if (shouldReject) {
            runtime().dispatch(TransferRuntimeCommand.RejectOffer(targetSessionId!!, reason))
        }
        return shouldReject
    }

    /**
     * Processes a cancel action request with exact-session validation.
     * Rejects stale, null, or mismatched session IDs to safeguard active transfers.
     */
    internal fun handleCancelAction(targetSessionId: String?): Boolean {
        val activeSessionId = runtime().getActiveSessionId()
        val shouldCancel = TransferServicePolicy.shouldAcceptCancelAction(targetSessionId, activeSessionId)
        if (shouldCancel) {
            runtime().dispatch(TransferRuntimeCommand.Cancel(targetSessionId!!))
            handleTerminalTeardown()
        }
        return shouldCancel
    }

    private fun startNotificationUpdates() {
        val settingsStore = dev.glide.app.di.DatabaseModule.provideSettingsStore(applicationContext)
        notificationObserverJob?.cancel()
        notificationObserverJob = serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                runtime().transferState,
                settingsStore.settingsFlow
            ) { state, settings ->
                state to settings
            }.collect { (state, settings) ->
                if (state !is TransferUiState.Idle) {
                    transferNotificationManager.dispatchUpdate(state, settings, serviceScope)
                }
            }
        }
    }

    private fun startForegroundWithNotification() {
        val notification = buildInitialNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            // API 34+ throws SecurityException if prerequisite connected device permissions are denied
            runtime().setTransferStateForTesting(
                TransferUiState.Failed(
                    sessionId = runtime().transferState.value.sessionId,
                    role = runtime().transferState.value.role,
                    message = "Missing nearby device permission to maintain background transfer.",
                    code = "MISSING_PERMISSION",
                    isRecoverable = false
                )
            )
            handleTerminalTeardown()
        } catch (e: IllegalStateException) {
            // API 31+ ForegroundServiceStartNotAllowedException is a subclass of IllegalStateException
            runtime().setTransferStateForTesting(
                TransferUiState.Failed(
                    sessionId = runtime().transferState.value.sessionId,
                    role = runtime().transferState.value.role,
                    message = "Cannot maintain background transfer while Glide is in the background.",
                    code = "FOREGROUND_START_NOT_ALLOWED",
                    isRecoverable = false
                )
            )
            handleTerminalTeardown()
        } catch (e: Exception) {
            runtime().setTransferStateForTesting(
                TransferUiState.Failed(
                    sessionId = runtime().transferState.value.sessionId,
                    role = runtime().transferState.value.role,
                    message = "Failed to start transfer foreground service: ${e.message}",
                    code = "SERVICE_START_FAILED",
                    isRecoverable = false
                )
            )
            handleTerminalTeardown()
        }
    }

    private fun buildInitialNotification(): Notification {
        val currentUiState = runtime().transferState.value
        val initialSettings = dev.glide.core.ports.GlideSettings()
        return if (currentUiState !is TransferUiState.Idle) {
            transferNotificationManager.buildNotification(this, currentUiState, initialSettings)
        } else {
            transferNotificationManager.buildNotification(
                this,
                TransferUiState.Connecting(
                    sessionId = runtime().getActiveSessionId().orEmpty(),
                    role = currentUiState.role,
                    peerName = "Nearby device"
                ),
                initialSettings
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "File Transfer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active nearby file transfer status and progress"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun handleTerminalTeardown() {
        notificationObserverJob?.cancel()
        notificationObserverJob = null
        try {
            customNotificationManager?.reset()
        } catch (_: Throwable) {}
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (_: Throwable) {
            // Handled when running in unit test environment without attached Service context
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        notificationObserverJob?.cancel()
        notificationObserverJob = null
        transferNotificationManager.reset()
        runtime().detachServiceScope()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "dev.glide.app.action.START_TRANSFER"
        const val ACTION_STOP = "dev.glide.app.action.STOP_TRANSFER"
        const val ACTION_CANCEL = "dev.glide.app.action.CANCEL_TRANSFER"
        const val ACTION_ACCEPT = "dev.glide.app.action.ACCEPT_OFFER"
        const val ACTION_REJECT = "dev.glide.app.action.REJECT_OFFER"

        const val EXTRA_SESSION_ID = "dev.glide.app.extra.SESSION_ID"
        const val EXTRA_PEER_NAME = "dev.glide.app.extra.PEER_NAME"
        const val EXTRA_IS_LAN = "dev.glide.app.extra.IS_LAN"

        const val NOTIFICATION_CHANNEL_ID = "glide_transfer_channel"
        const val NOTIFICATION_ID = 1001

        fun startService(
            context: Context,
            sessionId: String,
            peerName: String = "Nearby device",
            isLan: Boolean = false
        ) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_IS_LAN, isLan)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun cancelTransfer(context: Context, sessionId: String? = null) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_CANCEL
                val sid = sessionId ?: runtime().getActiveSessionId()
                if (sid != null) {
                    putExtra(EXTRA_SESSION_ID, sid)
                }
            }
            context.startService(intent)
        }
    }
}
