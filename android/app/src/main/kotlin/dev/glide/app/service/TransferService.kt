package dev.glide.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.app.NotificationCompat
import dev.glide.app.ui.transfer.TransferUiState
import dev.glide.core.domain.BatchId
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.TransferFailure
import dev.glide.core.pairing.InvitationCodec
import dev.glide.core.pairing.LanInvitation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
    @Volatile private var foreground = false
    @Volatile private var idleReceiver = false
    @Volatile private var notificationSettings = dev.glide.core.ports.GlideSettings()
    private var releaseHotspotLease: (() -> Unit)? = null

    private val startRequestLock = Any()
    private var lastStartRequestKey: String? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        runtime().attachServiceScope(serviceScope, onTransferStarting = {
            if (foreground) true else startForegroundWithNotification().also { started ->
                if (started) startNotificationUpdates()
            }
        }, onVerifiedCompletion = ::postCompletion, onTerminal = ::handleTerminalTeardown)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        val startRequestKey = startRequestKey(action, intent)
        if (startRequestKey != null && !claimStartRequest(startRequestKey)) {
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_START -> {
                startForegroundWithNotification()
                startNotificationUpdates()
                // Active transfer execution is already owned by the runtime attached in onCreate().
            }

            ACTION_START_NEARBY_SENDER -> {
                startForegroundWithNotification()
                startNotificationUpdates()
                val peerId = intent?.getStringExtra(EXTRA_PEER_ID)
                val peerName = intent?.getStringExtra(EXTRA_PEER_NAME) ?: "Nearby device"
                val localDeviceName =
                    intent?.getStringExtra(EXTRA_LOCAL_DEVICE_NAME) ?: "Glide Device"
                val selectionId = intent?.getStringExtra(EXTRA_SELECTION_ID)?.let(::BatchId)
                val started = peerId != null && runtime().dispatch(
                    TransferRuntimeCommand.StartNearbySenderPairing(
                        selectionId = selectionId,
                        peerId = PeerId(peerId),
                        peerDisplayName = peerName,
                        localDeviceName = localDeviceName
                    )
                )
                if (!started) {
                    handleTerminalTeardown()
                }
            }

            ACTION_START_NEARBY_RECEIVER -> {
                idleReceiver = true
                val localDeviceName =
                    intent?.getStringExtra(EXTRA_LOCAL_DEVICE_NAME) ?: "Glide Device"
                if (!runtime().dispatch(
                        TransferRuntimeCommand.StartReceiveAvailability(localDeviceName)
                    )
                ) {
                    handleTerminalTeardown()
                }
            }

            ACTION_START_LAN_SENDER -> {
                releaseHotspotLease = dev.glide.transport.lan.network.AndroidHotspotConnections.claimCurrentLease()
                startForegroundWithNotification()
                startNotificationUpdates()
                val encodedInvitation = intent?.getStringExtra(EXTRA_LAN_INVITATION)
                val selectionId = intent?.getStringExtra(EXTRA_SELECTION_ID)?.let(::BatchId)
                val localDeviceName =
                    intent?.getStringExtra(EXTRA_LOCAL_DEVICE_NAME) ?: "Glide Device"
                val invitation = encodedInvitation?.let {
                    runCatching { InvitationCodec.decode(it) }.getOrNull()
                }
                val started = invitation != null && runtime().dispatch(
                    TransferRuntimeCommand.StartLanSenderPairing(
                        selectionId = selectionId,
                        invitation = invitation,
                        localDeviceName = localDeviceName
                    )
                )
                if (!started) {
                    handleTerminalTeardown()
                }
            }

            ACTION_START_TRUSTED_SENDER -> {
                startForegroundWithNotification()
                startNotificationUpdates()
                val selectionId = intent?.getStringExtra(EXTRA_SELECTION_ID)?.let(::BatchId)
                val fingerprint = intent?.getStringExtra(EXTRA_PEER_FINGERPRINT)
                val localDeviceName =
                    intent?.getStringExtra(EXTRA_LOCAL_DEVICE_NAME) ?: "Glide Device"
                serviceScope.launch {
                    val started = selectionId != null && fingerprint != null &&
                        runtime().refreshPersistentTrustState() &&
                        runtime().dispatch(
                            TransferRuntimeCommand.StartTrustedSender(
                                selectionId = selectionId,
                                peerFingerprintHex = fingerprint,
                                localDeviceName = localDeviceName
                            )
                        )
                    if (!started) handleTerminalTeardown()
                }
            }

            ACTION_START_RETAINED_RESUME -> {
                startForegroundWithNotification()
                startNotificationUpdates()
                val original = intent?.getStringExtra(EXTRA_SESSION_ID)
                val deviceName = intent?.getStringExtra(EXTRA_LOCAL_DEVICE_NAME) ?: "Glide Device"
                serviceScope.launch {
                    val started = original != null && runtime().refreshPersistentTrustState() &&
                        runtime().startRetainedResume(original, deviceName)
                    if (!started) handleTerminalTeardown()
                }
            }

            ACTION_START_LAN_RECEIVER_PAIRING -> {
                releaseHotspotLease = dev.glide.transport.lan.network.AndroidHotspotConnections.claimCurrentLease()
                startForegroundWithNotification()
                startNotificationUpdates()
                val encodedInvitation = intent?.getStringExtra(EXTRA_LAN_INVITATION)
                val localDeviceName =
                    intent?.getStringExtra(EXTRA_LOCAL_DEVICE_NAME) ?: "Glide Device"
                val invitation = encodedInvitation?.let {
                    runCatching { InvitationCodec.decode(it) }.getOrNull()
                }
                val started = invitation != null && runtime().dispatch(
                    TransferRuntimeCommand.StartLanReceiverPairing(
                        invitation = invitation,
                        localDeviceName = localDeviceName
                    )
                )
                if (!started) {
                    handleTerminalTeardown()
                }
            }

            ACTION_START_LAN_RECEIVER -> {
                idleReceiver = true
                releaseHotspotLease = dev.glide.transport.lan.network.AndroidHotspotConnections.claimCurrentLease()
                val localDeviceName =
                    intent?.getStringExtra(EXTRA_LOCAL_DEVICE_NAME) ?: "Glide Device"
                serviceScope.launch {
                    if (!runtime().refreshPersistentTrustState() ||
                        !runtime().dispatch(
                            TransferRuntimeCommand.StartLanReceiver(localDeviceName)
                        )) {
                        handleTerminalTeardown()
                    }
                }
            }

            ACTION_STOP_LAN_RECEIVER -> {
                runtime().dispatch(TransferRuntimeCommand.StopLanReceiver)
            }

            ACTION_STOP_IDLE_LAN_RECEIVER -> {
                if (idleReceiver && !foreground && !runtime().isSessionActive.value) {
                    runtime().dispatch(TransferRuntimeCommand.StopLanReceiver)
                    runtime().dispatch(TransferRuntimeCommand.StopReceiveAvailability)
                    handleTerminalTeardown()
                }
            }

            ACTION_CANCEL -> {
                val targetSessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
                handleCancelAction(targetSessionId)
            }

            ACTION_STOP -> {
                val cancellationStarted = runtime().stopActiveSession()
                if (!cancellationStarted) {
                    handleTerminalTeardown()
                }
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
            ) { state, settings -> state to settings }.collect { (state, settings) ->
                notificationSettings = settings
                when (state) {
                    is TransferUiState.Connecting, is TransferUiState.AwaitingConsent,
                    is TransferUiState.Transferring, is TransferUiState.Verifying ->
                        transferNotificationManager.dispatchUpdate(state, settings, serviceScope)
                    else -> Unit
                }
            }
        }
    }

    private fun startForegroundWithNotification(): Boolean {
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
            foreground = true
            return true
        } catch (e: SecurityException) {
            // API 34+ throws SecurityException if prerequisite connected device permissions are denied
            failForegroundService(TransferFailure.PermissionDenied(
                "Nearby device permission is required to maintain background transfer."
            ))
        } catch (e: IllegalStateException) {
            // API 31+ ForegroundServiceStartNotAllowedException is a subclass of IllegalStateException
            failForegroundService(TransferFailure.SystemCancelled(
                "Open Glide to continue receiving."
            ))
        } catch (e: Exception) {
            failForegroundService(TransferFailure.SystemCancelled(
                "Could not keep receiving in the background. Open Glide and try again."
            ))
        }
        return false
    }

    private fun failForegroundService(failure: TransferFailure) {
        serviceScope.launch {
            try {
                runtime().reportForegroundServiceFailure(failure)
            } finally {
                handleTerminalTeardown()
            }
        }
    }

    private fun buildInitialNotification(): Notification {
        val currentUiState = runtime().transferState.value
        val initialSettings = dev.glide.core.ports.GlideSettings()
        return if (runtime().isSessionActive.value) {
            transferNotificationManager.buildNotification(this, currentUiState, initialSettings)
        } else {
            buildStartingNotification()
        }
    }

    private fun buildStartingNotification(): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(dev.glide.app.R.drawable.ic_notification)
            .setContentTitle("Glide transfer")
            .setContentText("Connecting")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(transferNotificationManager.buildPublicNotification(this))
            .setOngoing(true)
            .build()

    private fun postCompletion(state: TransferUiState.Completed) {
        val appCtx = applicationContext
        val manager = appCtx.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        val fallbackSettings = notificationSettings
        val notifMgr = transferNotificationManager
        completionNotificationScope.launch {
            val settings = runCatching {
                dev.glide.app.di.DatabaseModule.provideSettingsStore(appCtx).settingsFlow.first()
            }.getOrDefault(fallbackSettings)
            val content = notifMgr.resolveNotificationContent(state, settings)
            val intent = Intent(appCtx, dev.glide.app.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                data = android.net.Uri.fromParts("glide-history", state.sessionId, null)
                putExtra(EXTRA_OPEN_HISTORY_SESSION_ID, state.sessionId)
            }
            val open = PendingIntent.getActivity(
                appCtx,
                state.sessionId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(appCtx, COMPLETION_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(dev.glide.app.R.drawable.ic_notification)
                .setContentTitle("Transfer complete")
                .setContentText(
                    if (settings.notificationDetails && content.text.isNotBlank()) {
                        "${content.text} · Transfer has been completed successfully"
                    } else "Transfer has been completed successfully"
                )
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(notifMgr.buildPublicCompletionNotification(appCtx))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build()
            runCatching { manager.notify(state.sessionId, 2000, notification) }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val activeChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Active File Transfers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active nearby file transfer status and progress"
                setShowBadge(false)
            }
            val completionChannel = NotificationChannel(
                COMPLETION_NOTIFICATION_CHANNEL_ID,
                "Transfer Completions",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows dismissible completion records after a transfer finishes"
                setShowBadge(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(activeChannel)
            manager?.createNotificationChannel(completionChannel)
        }
    }

    private fun handleTerminalTeardown() {
        notificationObserverJob?.cancel()
        notificationObserverJob = null
        try {
            transferNotificationManager.reset()
        } catch (_: Throwable) {}
        try {
            foreground = false
            idleReceiver = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            stopSelf()
        } catch (_: Throwable) {
            // Handled when running in unit test environment without attached Service context
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseHotspotLease?.invoke()
        releaseHotspotLease = null
        isRunning = false
        notificationObserverJob?.cancel()
        notificationObserverJob = null
        synchronized(startRequestLock) {
            lastStartRequestKey = null
        }
        val cleanupJob = runtime().detachServiceScope()
        if (cleanupJob == null) {
            serviceScope.cancel()
        } else {
            cleanupJob.invokeOnCompletion {
                serviceScope.cancel()
            }
        }
        transferNotificationManager.reset()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRequestKey(action: String, intent: Intent?): String? {
        return when (action) {
            ACTION_START -> "generic:${intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()}"
            ACTION_START_NEARBY_SENDER -> {
                val selection = intent?.getStringExtra(EXTRA_SELECTION_ID).orEmpty()
                val peer = intent?.getStringExtra(EXTRA_PEER_ID).orEmpty()
                "nearby-sender:$selection:$peer"
            }
            ACTION_START_NEARBY_RECEIVER -> null
            ACTION_START_LAN_SENDER -> {
                val selection = intent?.getStringExtra(EXTRA_SELECTION_ID).orEmpty()
                val encoded = intent?.getStringExtra(EXTRA_LAN_INVITATION)
                val invitationId = encoded?.let {
                    runCatching { InvitationCodec.decode(it).invitationId }.getOrNull()
                }.orEmpty()
                "lan-sender:$selection:$invitationId"
            }
            ACTION_START_TRUSTED_SENDER ->
                "trusted-sender:${intent?.getStringExtra(EXTRA_ATTEMPT_ID).orEmpty()}"
            ACTION_START_RETAINED_RESUME ->
                "resume:${intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()}"
            ACTION_START_LAN_RECEIVER -> null
            ACTION_START_LAN_RECEIVER_PAIRING -> {
                val encoded = intent?.getStringExtra(EXTRA_LAN_INVITATION)
                val invitationId = encoded?.let {
                    runCatching { InvitationCodec.decode(it).invitationId }.getOrNull()
                }.orEmpty()
                "lan-receiver:$invitationId"
            }
            else -> null
        }
    }

    private fun claimStartRequest(key: String): Boolean {
        synchronized(startRequestLock) {
            if (lastStartRequestKey == key) return false
            lastStartRequestKey = key
            return true
        }
    }

    internal fun claimStartRequestForTesting(key: String): Boolean = claimStartRequest(key)

    internal fun resetStartRequestForTesting() {
        synchronized(startRequestLock) {
            lastStartRequestKey = null
        }
    }

    companion object {
        private const val ACTION_START_RETAINED_RESUME = "dev.glide.app.action.START_RETAINED_RESUME"

        fun startRetainedResume(context: Context, sessionId: String, deviceName: String) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_START_RETAINED_RESUME
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_LOCAL_DEVICE_NAME, deviceName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
        @Volatile private var isRunning = false
        const val ACTION_START = "dev.glide.app.action.START_TRANSFER"
        const val ACTION_START_NEARBY_SENDER =
            "dev.glide.app.action.START_NEARBY_SENDER"
        const val ACTION_START_NEARBY_RECEIVER =
            "dev.glide.app.action.START_NEARBY_RECEIVER"
        const val ACTION_START_LAN_SENDER =
            "dev.glide.app.action.START_LAN_SENDER"
        const val ACTION_START_TRUSTED_SENDER =
            "dev.glide.app.action.START_TRUSTED_SENDER"
        const val ACTION_START_LAN_RECEIVER =
            "dev.glide.app.action.START_LAN_RECEIVER"
        const val ACTION_START_LAN_RECEIVER_PAIRING =
            "dev.glide.app.action.START_LAN_RECEIVER_PAIRING"
        const val ACTION_STOP_LAN_RECEIVER =
            "dev.glide.app.action.STOP_LAN_RECEIVER"
        const val ACTION_STOP_IDLE_LAN_RECEIVER =
            "dev.glide.app.action.STOP_IDLE_LAN_RECEIVER"
        const val ACTION_STOP = "dev.glide.app.action.STOP_TRANSFER"
        const val ACTION_CANCEL = "dev.glide.app.action.CANCEL_TRANSFER"
        const val ACTION_ACCEPT = "dev.glide.app.action.ACCEPT_OFFER"
        const val ACTION_REJECT = "dev.glide.app.action.REJECT_OFFER"

        const val EXTRA_SESSION_ID = "dev.glide.app.extra.SESSION_ID"
        const val EXTRA_OPEN_HISTORY_SESSION_ID = "dev.glide.app.extra.OPEN_HISTORY_SESSION_ID"
        const val EXTRA_PEER_ID = "dev.glide.app.extra.PEER_ID"
        const val EXTRA_PEER_NAME = "dev.glide.app.extra.PEER_NAME"
        const val EXTRA_SELECTION_ID = "dev.glide.app.extra.SELECTION_ID"
        const val EXTRA_LOCAL_DEVICE_NAME = "dev.glide.app.extra.LOCAL_DEVICE_NAME"
        const val EXTRA_LAN_INVITATION = "dev.glide.app.extra.LAN_INVITATION"
        const val EXTRA_PEER_FINGERPRINT = "dev.glide.app.extra.PEER_FINGERPRINT"
        const val EXTRA_ATTEMPT_ID = "dev.glide.app.extra.ATTEMPT_ID"
        const val EXTRA_IS_LAN = "dev.glide.app.extra.IS_LAN"

        const val NOTIFICATION_CHANNEL_ID = "glide_transfer_channel"
        const val COMPLETION_NOTIFICATION_CHANNEL_ID = "glide_transfer_complete"
        const val NOTIFICATION_ID = 1001
        private val completionNotificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

        fun startNearbySenderPairing(
            context: Context,
            peerId: PeerId,
            peerName: String,
            selectionId: BatchId? = null,
            localDeviceName: String = "Glide Device"
        ) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_START_NEARBY_SENDER
                putExtra(EXTRA_PEER_ID, peerId.value)
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_LOCAL_DEVICE_NAME, localDeviceName)
                selectionId?.let { putExtra(EXTRA_SELECTION_ID, it.value) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startNearbyReceiver(
            context: Context,
            localDeviceName: String = "Glide Device"
        ) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_START_NEARBY_RECEIVER
                putExtra(EXTRA_LOCAL_DEVICE_NAME, localDeviceName)
            }
            context.startService(intent)
        }

        fun startLanSenderPairing(
            context: Context,
            invitation: LanInvitation,
            selectionId: BatchId? = null,
            localDeviceName: String = "Glide Device"
        ) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_START_LAN_SENDER
                putExtra(EXTRA_LAN_INVITATION, InvitationCodec.encode(invitation))
                putExtra(EXTRA_LOCAL_DEVICE_NAME, localDeviceName)
                selectionId?.let { putExtra(EXTRA_SELECTION_ID, it.value) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startTrustedSender(
            context: Context,
            selectionId: BatchId,
            peerFingerprintHex: String,
            localDeviceName: String = "Glide Device"
        ) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_START_TRUSTED_SENDER
                putExtra(EXTRA_SELECTION_ID, selectionId.value)
                putExtra(EXTRA_PEER_FINGERPRINT, peerFingerprintHex)
                putExtra(EXTRA_LOCAL_DEVICE_NAME, localDeviceName)
                putExtra(EXTRA_ATTEMPT_ID, java.util.UUID.randomUUID().toString())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startLanReceiver(
            context: Context,
            localDeviceName: String = "Glide Device"
        ) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_START_LAN_RECEIVER
                putExtra(EXTRA_LOCAL_DEVICE_NAME, localDeviceName)
            }
            context.startService(intent)
        }

        fun startLanReceiverPairing(
            context: Context,
            invitation: LanInvitation,
            localDeviceName: String = "Glide Device"
        ) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_START_LAN_RECEIVER_PAIRING
                putExtra(EXTRA_LAN_INVITATION, InvitationCodec.encode(invitation))
                putExtra(EXTRA_LOCAL_DEVICE_NAME, localDeviceName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopLanReceiver(context: Context) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_STOP_LAN_RECEIVER
            }
            context.startService(intent)
        }

        fun stopIdleLanReceiver(context: Context) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_STOP_IDLE_LAN_RECEIVER
            }
            // Stop only the existing idle listener.
            if (isRunning) context.startService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun cancelTransfer(context: Context, sessionId: String) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            context.startService(intent)
        }
    }
}
