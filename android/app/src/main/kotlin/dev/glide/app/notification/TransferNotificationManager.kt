package dev.glide.app.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.glide.app.R
import dev.glide.app.MainActivity
import dev.glide.app.service.TransferService
import dev.glide.app.ui.selection.formatFileSize
import dev.glide.app.ui.transfer.TransferUiState
import dev.glide.core.domain.DeviceRole
import dev.glide.core.ports.GlideSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pure data model representing the resolved visual and privacy state of a transfer notification.
 */
data class NotificationContent(
    val title: String,
    val text: String,
    val subText: String?,
    val isOngoing: Boolean,
    val isIndeterminate: Boolean,
    val progressMax: Int,
    val currentProgress: Int,
    val smallIcon: Int,
    val visibility: Int,
    val hasCancelAction: Boolean,
    val publicTitle: String = "Glide file transfer",
    val publicText: String = "Transfer in progress"
)

/**
 * Manages foreground transfer notifications, privacy redaction, and update rate-limiting (GLIDE-029).
 *
 * Guarantees:
 * - Rate-limited updates: Notification updates are throttled to <= 1 Hz (1,000 ms) during active streaming
 *   to avoid IPC jank and platform notification throttling.
 * - Trailing edge delivery: The final progress update of an item is never dropped.
 * - Phase/terminal immediacy: Phase transitions (e.g. Connecting -> Transferring) and terminal states
 *   (Completed, Failed, Cancelled) post immediately without throttling delay.
 * - Privacy redaction: When [GlideSettings.notificationDetails] is false, file names and item metadata
 *   are strictly omitted from notification titles, texts, subtexts, and extras.
 * - Lockscreen protection: Notifications configure [NotificationCompat.VISIBILITY_PRIVATE] with a
 *   safe [NotificationCompat.VISIBILITY_PUBLIC] version that leaks zero filenames or metadata.
 * - Exact-session immutable PendingIntents: Both open and cancel intents bind exact session IDs and
 *   [PendingIntent.FLAG_IMMUTABLE].
 */
class TransferNotificationManager(
    private val context: Context? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val notifyAction: (Notification) -> Unit = { notification ->
        context?.let { ctx ->
            val manager = NotificationManagerCompat.from(ctx)
            try {
                manager.notify(TransferService.NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
                // Handled when POST_NOTIFICATIONS runtime permission is denied on API 33+
            }
        }
    }
) {

    private var lastPostTimestampMs: Long = 0L
    private var lastPostedPhase: String? = null
    private var pendingTrailingJob: Job? = null
    private var latestPendingState: TransferUiState? = null
    private var latestPendingSettings: GlideSettings? = null

    /**
     * Resolves the visual and privacy content purely without Android platform dependencies.
     */
    fun resolveNotificationContent(
        state: TransferUiState,
        settings: GlideSettings
    ): NotificationContent {
        val title: String
        val text: String
        val subText: String?
        val isIndeterminate: Boolean
        val maxProgress: Int
        val currentProgress: Int
        val smallIcon: Int

        val isActive = state is TransferUiState.Connecting ||
            state is TransferUiState.AwaitingConsent ||
            state is TransferUiState.Transferring ||
            state is TransferUiState.Verifying

        val isOngoing = isActive

        when (state) {
            is TransferUiState.Idle -> {
                title = "Glide transfer"
                text = "Preparing transfer..."
                subText = null
                isIndeterminate = true
                maxProgress = 0
                currentProgress = 0
                smallIcon = R.drawable.ic_notification
            }

            is TransferUiState.Connecting -> {
                title = "Connecting to peer"
                text = "Establishing secure connection..."
                subText = state.peerName
                isIndeterminate = true
                maxProgress = 0
                currentProgress = 0
                smallIcon = R.drawable.ic_notification
            }

            is TransferUiState.AwaitingConsent -> {
                title = "Incoming file offer"
                text = if (settings.notificationDetails) {
                    "${state.firstItemName} (${formatFileSize(state.totalBytes)})"
                } else {
                    val countText = if (state.itemCount == 1) "1 file" else "${state.itemCount} files"
                    "$countText (${formatFileSize(state.totalBytes)})"
                }
                subText = state.peerName
                isIndeterminate = true
                maxProgress = 0
                currentProgress = 0
                smallIcon = R.drawable.ic_notification
            }

            is TransferUiState.Transferring -> {
                val isSending = state.role == DeviceRole.Sender
                val actionVerb = if (isSending) "Sending" else "Receiving"

                title = if (settings.notificationDetails) {
                    if (state.itemCount > 1) {
                        "$actionVerb ${state.itemName} (${state.itemIndex}/${state.itemCount})"
                    } else {
                        "$actionVerb ${state.itemName}"
                    }
                } else {
                    "$actionVerb files..."
                }

                val transferredStr = formatFileSize(state.transferredBytes)
                val totalStr = formatFileSize(state.totalBytes)
                val rateAndEtaStr = if (state.isStalled) {
                    " · Stalled"
                } else {
                    val rateStr = if (state.speedBytesPerSec > 0L) {
                        " · ${formatFileSize(state.speedBytesPerSec)}/s"
                    } else {
                        ""
                    }
                    val etaStr = formatEta(state.transferredBytes, state.totalBytes, state.speedBytesPerSec)
                    "$rateStr$etaStr"
                }

                text = "$transferredStr / $totalStr$rateAndEtaStr"
                subText = state.peerName
                isIndeterminate = false
                maxProgress = PROGRESS_MAX
                currentProgress = (state.progressFraction * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX)
                smallIcon = R.drawable.ic_notification
            }

            is TransferUiState.Verifying -> {
                title = if (settings.notificationDetails) {
                    if (state.itemCount > 1) {
                        "Verifying ${state.itemName} (${state.itemIndex}/${state.itemCount})..."
                    } else {
                        "Verifying ${state.itemName}..."
                    }
                } else {
                    "Verifying transfer..."
                }
                text = "Checking file integrity..."
                subText = state.peerName
                isIndeterminate = true
                maxProgress = 0
                currentProgress = 0
                smallIcon = R.drawable.ic_notification
            }

            is TransferUiState.Completed -> {
                title = "Transfer complete"
                text = if (settings.notificationDetails) {
                    if (state.itemCount > 1) {
                        "${state.itemCount} files (${formatFileSize(state.totalBytes)}) transferred"
                    } else {
                        "${state.itemName} (${formatFileSize(state.totalBytes)}) transferred"
                    }
                } else {
                    "${formatFileSize(state.totalBytes)} transferred successfully"
                }
                subText = state.peerName
                isIndeterminate = false
                maxProgress = 0
                currentProgress = 0
                smallIcon = R.drawable.ic_notification
            }

            is TransferUiState.Cancelled -> {
                title = "Transfer cancelled"
                text = state.reason
                subText = null
                isIndeterminate = false
                maxProgress = 0
                currentProgress = 0
                smallIcon = R.drawable.ic_notification
            }

            is TransferUiState.Failed -> {
                title = "Transfer failed"
                text = state.message
                subText = null
                isIndeterminate = false
                maxProgress = 0
                currentProgress = 0
                smallIcon = R.drawable.ic_notification
            }
        }

        return NotificationContent(
            title = title,
            text = text,
            subText = subText,
            isOngoing = isOngoing,
            isIndeterminate = isIndeterminate,
            progressMax = maxProgress,
            currentProgress = currentProgress,
            smallIcon = smallIcon,
            visibility = NotificationCompat.VISIBILITY_PRIVATE,
            hasCancelAction = isActive
        )
    }

    /**
     * Builds the public-safe notification displayed on secure lockscreens.
     * Guaranteed to leak zero filenames, file sizes, or peer identifiers.
     */
    fun buildPublicNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, TransferService.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Glide file transfer")
            .setContentText("Transfer in progress")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /**
     * Builds the private notification honoring [GlideSettings.notificationDetails].
     */
    fun buildNotification(
        context: Context,
        state: TransferUiState,
        settings: GlideSettings
    ): Notification {
        val sessionId = state.sessionId
        val content = resolveNotificationContent(state, settings)

        // 1. Content Intent (Tap to restore active transfer screen)
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_VIEW_TRANSFER
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(TransferService.EXTRA_SESSION_ID, sessionId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 2. Cancel Action Intent (Targets TransferService with exact session ID)
        val cancelIntent = Intent(context, TransferService::class.java).apply {
            action = TransferService.ACTION_CANCEL
            putExtra(TransferService.EXTRA_SESSION_ID, sessionId)
        }
        val cancelPendingIntent = PendingIntent.getService(
            context,
            sessionId.hashCode() + 1,
            cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Cancel",
            cancelPendingIntent
        ).build()

        val publicNotification = buildPublicNotification(context)

        val builder = NotificationCompat.Builder(context, TransferService.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(content.smallIcon)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.subText)
            .setOngoing(content.isOngoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(content.visibility)
            .setPublicVersion(publicNotification)
            .setContentIntent(contentPendingIntent)
            .setProgress(content.progressMax, content.currentProgress, content.isIndeterminate)

        if (content.hasCancelAction) {
            builder.addAction(cancelAction)
        }

        return builder.build()
    }

    /**
     * Dispatches a notification update with rate-limiting (<= 1 Hz).
     *
     * Rules:
     * - Terminal states post immediately and cancel pending jobs.
     * - Phase transitions post immediately and reset throttle timer.
     * - Progress ticks within the same phase are throttled to at most once per 1,000 ms.
     * - Trailing progress updates are guaranteed via a scheduled coroutine delay.
     */
    fun dispatchUpdate(
        state: TransferUiState,
        settings: GlideSettings,
        scope: CoroutineScope
    ) {
        val now = timeProvider()
        val currentPhase = state::class.simpleName

        val isTerminal = state is TransferUiState.Completed ||
            state is TransferUiState.Failed ||
            state is TransferUiState.Cancelled

        val isPhaseChange = currentPhase != lastPostedPhase

        latestPendingState = state
        latestPendingSettings = settings

        if (isTerminal || isPhaseChange || (now - lastPostTimestampMs) >= THROTTLE_INTERVAL_MS) {
            // Immediate dispatch
            pendingTrailingJob?.cancel()
            pendingTrailingJob = null

            val notification = context?.let { buildNotification(it, state, settings) } ?: Notification()
            notifyAction(notification)

            lastPostTimestampMs = now
            lastPostedPhase = currentPhase
        } else {
            // Schedule trailing dispatch if not already scheduled
            if (pendingTrailingJob == null || pendingTrailingJob?.isCompleted == true) {
                val delayRemaining = (THROTTLE_INTERVAL_MS - (now - lastPostTimestampMs)).coerceAtLeast(0L)
                pendingTrailingJob = scope.launch {
                    delay(delayRemaining)
                    val pendingState = latestPendingState
                    val pendingSettings = latestPendingSettings
                    if (pendingState != null && pendingSettings != null) {
                        val notification = context?.let { buildNotification(it, pendingState, pendingSettings) } ?: Notification()
                        notifyAction(notification)
                        lastPostTimestampMs = timeProvider()
                        lastPostedPhase = pendingState::class.simpleName
                    }
                    pendingTrailingJob = null
                }
            }
        }
    }

    /**
     * Resets throttling state when service stops.
     */
    fun reset() {
        pendingTrailingJob?.cancel()
        pendingTrailingJob = null
        latestPendingState = null
        latestPendingSettings = null
        lastPostTimestampMs = 0L
        lastPostedPhase = null
    }

    companion object {
        const val ACTION_VIEW_TRANSFER = "dev.glide.app.action.VIEW_TRANSFER"
        const val THROTTLE_INTERVAL_MS = 1000L
        const val PROGRESS_MAX = 1000

        /**
         * Formats estimated time remaining into human-readable text.
         */
        fun formatEta(transferredBytes: Long, totalBytes: Long, speedBytesPerSec: Long): String {
            if (speedBytesPerSec <= 0L || totalBytes <= transferredBytes) return ""
            val remainingBytes = totalBytes - transferredBytes
            val seconds = remainingBytes / speedBytesPerSec
            return when {
                seconds < 60 -> " · ${seconds}s remaining"
                else -> {
                    val minutes = seconds / 60
                    val remSecs = seconds % 60
                    " · ${minutes}m ${remSecs}s remaining"
                }
            }
        }
    }
}
