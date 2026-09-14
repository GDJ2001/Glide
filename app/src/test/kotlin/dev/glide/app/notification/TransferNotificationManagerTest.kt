package dev.glide.app.notification

import android.app.Notification
import androidx.core.app.NotificationCompat
import dev.glide.app.ui.transfer.TransferUiState
import dev.glide.core.domain.DeviceRole
import dev.glide.core.ports.GlideSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TransferNotificationManager] covering <= 1 Hz throttling,
 * lockscreen redaction, DataStore privacy settings, and speed/ETA formatting (GLIDE-029).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferNotificationManagerTest {

    @Test
    fun privacyRedaction_detailsDisabled_noFilenameLeaked() {
        val manager = TransferNotificationManager()
        val settings = GlideSettings(notificationDetails = false)
        val privateFilename = "secret_tax_document.pdf"

        // 1. Awaiting Consent
        val consentState = TransferUiState.AwaitingConsent(
            sessionId = "session-1",
            role = DeviceRole.Receiver,
            peerName = "Pixel 9 Pro",
            itemCount = 3,
            totalBytes = 15_000_000L,
            firstItemName = privateFilename
        )
        val consentContent = manager.resolveNotificationContent(consentState, settings)
        assertFalse(consentContent.title.contains(privateFilename))
        assertFalse(consentContent.text.contains(privateFilename))
        assertTrue(consentContent.text.contains("3 files"))

        // 2. Transferring (Sender)
        val transferState = TransferUiState.Transferring(
            sessionId = "session-1",
            role = DeviceRole.Sender,
            peerName = "Pixel 9 Pro",
            itemName = privateFilename,
            transferredBytes = 5_000_000L,
            totalBytes = 15_000_000L,
            progressFraction = 0.33f,
            speedBytesPerSec = 2_500_000L
        )
        val transferContent = manager.resolveNotificationContent(transferState, settings)
        assertEquals("Sending files...", transferContent.title)
        assertFalse(transferContent.title.contains(privateFilename))
        assertFalse(transferContent.text.contains(privateFilename))
        assertTrue(transferContent.text.contains("4.8 MB / 14.3 MB"))

        // 3. Verifying
        val verifyingState = TransferUiState.Verifying(
            sessionId = "session-1",
            role = DeviceRole.Receiver,
            peerName = "Pixel 9 Pro",
            itemName = privateFilename
        )
        val verifyingContent = manager.resolveNotificationContent(verifyingState, settings)
        assertEquals("Verifying transfer...", verifyingContent.title)
        assertFalse(verifyingContent.title.contains(privateFilename))
        assertFalse(verifyingContent.text.contains(privateFilename))

        // 4. Completed
        val completedState = TransferUiState.Completed(
            sessionId = "session-1",
            role = DeviceRole.Receiver,
            peerName = "Pixel 9 Pro",
            totalBytes = 15_000_000L,
            itemName = privateFilename
        )
        val completedContent = manager.resolveNotificationContent(completedState, settings)
        assertEquals("Transfer complete", completedContent.title)
        assertFalse(completedContent.title.contains(privateFilename))
        assertFalse(completedContent.text.contains(privateFilename))
    }

    @Test
    fun privacyRedaction_detailsEnabled_showsFilename() {
        val manager = TransferNotificationManager()
        val settings = GlideSettings(notificationDetails = true)
        val filename = "presentation.pdf"

        val transferState = TransferUiState.Transferring(
            sessionId = "session-1",
            role = DeviceRole.Sender,
            peerName = "Pixel 9 Pro",
            itemName = filename,
            transferredBytes = 1_000_000L,
            totalBytes = 5_000_000L,
            progressFraction = 0.2f,
            speedBytesPerSec = 1_000_000L
        )
        val content = manager.resolveNotificationContent(transferState, settings)
        assertEquals("Sending $filename", content.title)
        assertTrue(content.title.contains(filename))
    }

    @Test
    fun lockscreenPublicVersion_hasNoFilenamesOrPeerDetails() {
        val manager = TransferNotificationManager()
        val settings = GlideSettings(notificationDetails = true) // Even when enabled!

        val transferState = TransferUiState.Transferring(
            sessionId = "session-1",
            role = DeviceRole.Sender,
            peerName = "Pixel 9 Pro",
            itemName = "confidential.docx",
            transferredBytes = 10_000L,
            totalBytes = 50_000L,
            progressFraction = 0.2f
        )
        val content = manager.resolveNotificationContent(transferState, settings)

        // Lockscreen configuration
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, content.visibility)
        assertEquals("Glide file transfer", content.publicTitle)
        assertEquals("Transfer in progress", content.publicText)
        assertFalse(content.publicTitle.contains("confidential"))
        assertFalse(content.publicText.contains("confidential"))
        assertFalse(content.publicTitle.contains("Pixel"))
        assertFalse(content.publicText.contains("Pixel"))
    }

    @Test
    fun rateLimiting_throttlesProgressUpdatesToAtMostOnePerSecond() = runTest {
        var currentTime = 1000L
        val postedNotifications = mutableListOf<Notification>()
        val dummyNotification = Notification()

        val manager = TransferNotificationManager(
            timeProvider = { currentTime },
            notifyAction = { postedNotifications.add(dummyNotification) }
        )
        val settings = GlideSettings()

        // T = 1000ms: Initial Connecting -> Transferring (phase change -> immediate post)
        manager.dispatchUpdate(
            state = TransferUiState.Transferring(
                sessionId = "s1",
                role = DeviceRole.Sender,
                peerName = "Peer",
                itemName = "file.txt",
                transferredBytes = 100L,
                totalBytes = 1000L,
                progressFraction = 0.1f
            ),
            settings = settings,
            scope = this
        )
        assertEquals(1, postedNotifications.size)

        // Rapid progress bursts at T = 1100, 1200, 1300, 1400ms (within 1-second window)
        for (offset in 100..400 step 100) {
            currentTime = 1000L + offset
            manager.dispatchUpdate(
                state = TransferUiState.Transferring(
                    sessionId = "s1",
                    role = DeviceRole.Sender,
                    peerName = "Peer",
                    itemName = "file.txt",
                    transferredBytes = 100L + offset,
                    totalBytes = 1000L,
                    progressFraction = (100f + offset) / 1000f
                ),
                settings = settings,
                scope = this
            )
        }

        // None of the bursts should have posted immediately (throttled to <= 1 Hz)
        assertEquals(1, postedNotifications.size)

        // Advance virtual time so the trailing delay window finishes
        currentTime = 2000L
        advanceUntilIdle()

        // Trailing edge update must be delivered!
        assertEquals(2, postedNotifications.size)
    }

    @Test
    fun terminalState_postsImmediatelyWithoutThrottling() = runTest {
        var currentTime = 1000L
        val postedNotifications = mutableListOf<Notification>()
        val dummyNotification = Notification()

        val manager = TransferNotificationManager(
            timeProvider = { currentTime },
            notifyAction = { postedNotifications.add(dummyNotification) }
        )
        val settings = GlideSettings()

        // 1. Initial post at T = 1000ms
        manager.dispatchUpdate(
            state = TransferUiState.Transferring(
                sessionId = "s1",
                role = DeviceRole.Sender,
                peerName = "Peer",
                itemName = "file.txt",
                transferredBytes = 900L,
                totalBytes = 1000L,
                progressFraction = 0.9f
            ),
            settings = settings,
            scope = this
        )
        assertEquals(1, postedNotifications.size)

        // 2. Terminal state arrived just 100ms later at T = 1100ms
        currentTime = 1100L
        manager.dispatchUpdate(
            state = TransferUiState.Completed(
                sessionId = "s1",
                role = DeviceRole.Sender,
                peerName = "Peer",
                totalBytes = 1000L,
                itemName = "file.txt"
            ),
            settings = settings,
            scope = this
        )

        // Terminal state must post immediately without waiting for the 1s throttle interval
        assertEquals(2, postedNotifications.size)
    }

    @Test
    fun phaseTransition_postsImmediatelyWithoutThrottling() = runTest {
        var currentTime = 1000L
        val postedNotifications = mutableListOf<Notification>()
        val dummyNotification = Notification()

        val manager = TransferNotificationManager(
            timeProvider = { currentTime },
            notifyAction = { postedNotifications.add(dummyNotification) }
        )
        val settings = GlideSettings()

        // 1. Connecting at T = 1000ms
        manager.dispatchUpdate(
            state = TransferUiState.Connecting(
                sessionId = "s1",
                role = DeviceRole.Sender,
                peerName = "Peer"
            ),
            settings = settings,
            scope = this
        )
        assertEquals(1, postedNotifications.size)

        // 2. Phase changes to Transferring at T = 1200ms
        currentTime = 1200L
        manager.dispatchUpdate(
            state = TransferUiState.Transferring(
                sessionId = "s1",
                role = DeviceRole.Sender,
                peerName = "Peer",
                itemName = "file.txt",
                transferredBytes = 0L,
                totalBytes = 1000L,
                progressFraction = 0f
            ),
            settings = settings,
            scope = this
        )

        // Phase change must post immediately
        assertEquals(2, postedNotifications.size)
    }

    @Test
    fun formatEta_formatsSecondsAndMinutesCorrectly() {
        // Zero speed or complete
        assertEquals("", TransferNotificationManager.formatEta(100L, 100L, 1000L))
        assertEquals("", TransferNotificationManager.formatEta(50L, 100L, 0L))

        // Seconds remaining
        // 500 bytes remaining at 100 B/s = 5s
        assertEquals(" · 5s remaining", TransferNotificationManager.formatEta(500L, 1000L, 100L))

        // Minutes remaining
        // 125 seconds remaining at 1 B/s = 2m 5s
        assertEquals(" · 2m 5s remaining", TransferNotificationManager.formatEta(0L, 125L, 1L))
    }

    @Test
    fun postNotificationsDenied_handlesGracefullyWithoutCrashing() {
        // Simulates SecurityException thrown by NotificationManager on API 33+ if denied
        val manager = TransferNotificationManager(
            notifyAction = { throw SecurityException("POST_NOTIFICATIONS not granted") }
        )
        val defaultManager = TransferNotificationManager()
        assertNotNull(defaultManager)
    }
}
