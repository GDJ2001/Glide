package dev.glide.app.ingress

import android.net.Uri
import dev.glide.app.navigation.GlideRoute
import dev.glide.core.domain.ProtocolLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test suite for [ShareIngressHandler] (LAN-021).
 *
 * Verifies:
 * 1. Cold start with ACTION_SEND and ACTION_SEND_MULTIPLE routes to Selection retaining all URIs.
 * 2. Active transfer session guard: incoming shares while active bring the active session to front and return busy dialog.
 * 3. Task recreation (isRecreating = true) returns NoOp, suppressing re-dispatch and avoiding duplicate transfers.
 * 4. onNewIntent deduplicates repeat intents and handles incoming shares safely.
 * 5. Notification session extras take immediate precedence.
 * 6. Malicious schemes, text shares, and oversized batches produce informative dialog outcomes without throwing exceptions.
 */
class ShareIngressHandlerTest {

    @Test
    fun handleLaunchIntent_nullIntent_routesToHome() {
        val outcome = ShareIngressHandler.handleLaunchIntent(
            intent = null,
            isRecreating = false,
            isSessionActive = false,
            activeSessionId = null
        )

        assertTrue(outcome is IngressOutcome.Route)
        assertEquals(GlideRoute.Home, (outcome as IngressOutcome.Route).route)
    }

    @Test
    fun handleLaunchOutcome_notificationExtra_routesToTransferSession() {
        val outcome = ShareIngressHandler.handleLaunchOutcome(
            hasIntent = true,
            sessionIdExtra = "session-fgs-42",
            isRecreating = false,
            isSessionActive = false,
            activeSessionId = null,
            shareResult = ShareIngressResult.Ignored
        )

        assertTrue(outcome is IngressOutcome.Route)
        val route = (outcome as IngressOutcome.Route).route
        assertTrue(route is GlideRoute.Transfer)
        assertEquals("session-fgs-42", (route as GlideRoute.Transfer).sessionId)
    }

    @Test
    fun handleLaunchOutcome_recreatingFromSavedState_returnsNoOp() {
        val shareResult = ShareIngressParser.parseFromData(
            action = "android.intent.action.SEND",
            mimeType = "image/png",
            textExtra = null,
            rawUriStrings = listOf("content://media/external/images/1")
        )

        val outcome = ShareIngressHandler.handleLaunchOutcome(
            hasIntent = true,
            sessionIdExtra = null,
            isRecreating = true,
            isSessionActive = false,
            activeSessionId = null,
            shareResult = shareResult
        )

        assertEquals(IngressOutcome.NoOp, outcome)
    }

    @Test
    fun handleLaunchOutcome_activeSessionRunning_returnsActiveSessionBusy() {
        val shareResult = ShareIngressParser.parseFromData(
            action = "android.intent.action.SEND",
            mimeType = "image/png",
            textExtra = null,
            rawUriStrings = listOf("content://media/external/images/1")
        )

        val outcome = ShareIngressHandler.handleLaunchOutcome(
            hasIntent = true,
            sessionIdExtra = null,
            isRecreating = false,
            isSessionActive = true,
            activeSessionId = "running-session-99",
            shareResult = shareResult
        )

        assertTrue(outcome is IngressOutcome.ActiveSessionBusy)
        val busy = outcome as IngressOutcome.ActiveSessionBusy
        assertEquals("running-session-99", busy.activeSessionId)
        assertTrue(busy.dialogTitle.contains("Transfer in progress"))
    }

    @Test
    fun handleLaunchOutcome_validShare_routesToSelectionRetainingFullBatch() {
        val uris = listOf(
            "content://media/external/images/1",
            "content://media/external/images/2",
            "content://media/external/images/3"
        )
        val shareResult = ShareIngressParser.parseFromData(
            action = "android.intent.action.SEND_MULTIPLE",
            mimeType = "image/*",
            textExtra = null,
            rawUriStrings = uris
        )

        val outcome = ShareIngressHandler.handleLaunchOutcome(
            hasIntent = true,
            sessionIdExtra = null,
            isRecreating = false,
            isSessionActive = false,
            activeSessionId = null,
            shareResult = shareResult
        )

        assertTrue(outcome is IngressOutcome.Route)
        val route = (outcome as IngressOutcome.Route).route
        assertTrue(route is GlideRoute.Selection)
        val selection = route as GlideRoute.Selection
        assertEquals(3, selection.initialUris.size)
        assertEquals(uris, selection.initialUris)
    }

    @Test
    fun handleNewIntent_nullIntent_returnsNoOp() {
        val (outcome, fingerprint) = ShareIngressHandler.handleNewIntent(
            intent = null,
            isSessionActive = false,
            activeSessionId = null,
            lastHandledFingerprint = "prev-token"
        )
        assertEquals(IngressOutcome.NoOp, outcome)
        assertEquals("prev-token", fingerprint)
    }

    @Test
    fun handleNewOutcome_validShare_routesToSelectionRetainingFullBatch() {
        val uris = listOf(
            "content://media/external/images/1",
            "content://media/external/images/2"
        )
        val shareResult = ShareIngressParser.parseFromData(
            action = "android.intent.action.SEND_MULTIPLE",
            mimeType = "image/*",
            textExtra = null,
            rawUriStrings = uris
        )

        val (outcome, fingerprint) = ShareIngressHandler.handleNewOutcome(
            hasIntent = true,
            fingerprint = "token-1",
            sessionIdExtra = null,
            isSessionActive = false,
            activeSessionId = null,
            shareResult = shareResult,
            lastHandledFingerprint = null
        )

        assertEquals("token-1", fingerprint)
        assertTrue(outcome is IngressOutcome.Route)
        val route = (outcome as IngressOutcome.Route).route
        assertTrue(route is GlideRoute.Selection)
        assertEquals(2, (route as GlideRoute.Selection).initialUris.size)
    }

    @Test
    fun handleNewOutcome_duplicateIntent_returnsNoOp() {
        val shareResult = ShareIngressParser.parseFromData(
            action = "android.intent.action.SEND",
            mimeType = "application/pdf",
            textExtra = null,
            rawUriStrings = listOf("content://doc/report.pdf")
        )

        val (outcome, fingerprint) = ShareIngressHandler.handleNewOutcome(
            hasIntent = true,
            fingerprint = "same-fingerprint",
            sessionIdExtra = null,
            isSessionActive = false,
            activeSessionId = null,
            shareResult = shareResult,
            lastHandledFingerprint = "same-fingerprint"
        )

        assertEquals(IngressOutcome.NoOp, outcome)
        assertEquals("same-fingerprint", fingerprint)
    }

    @Test
    fun handleNewOutcome_activeSessionRunning_returnsActiveSessionBusyWithoutOverwriting() {
        val shareResult = ShareIngressParser.parseFromData(
            action = "android.intent.action.SEND",
            mimeType = "video/mp4",
            textExtra = null,
            rawUriStrings = listOf("content://media/video/42")
        )

        val (outcome, _) = ShareIngressHandler.handleNewOutcome(
            hasIntent = true,
            fingerprint = "token-video",
            sessionIdExtra = null,
            isSessionActive = true,
            activeSessionId = "active-video-transfer",
            shareResult = shareResult,
            lastHandledFingerprint = null
        )

        assertTrue(outcome is IngressOutcome.ActiveSessionBusy)
        val busy = outcome as IngressOutcome.ActiveSessionBusy
        assertEquals("active-video-transfer", busy.activeSessionId)
        assertTrue(busy.dialogMessage.contains("cannot start a new share while a transfer is active"))
    }

    @Test
    fun handleNewOutcome_invalidScheme_returnsShowDialog() {
        val shareResult = ShareIngressParser.parseFromData(
            action = "android.intent.action.SEND",
            mimeType = "application/zip",
            textExtra = null,
            rawUriStrings = listOf("file:///sdcard/Download/archive.zip")
        )

        val (outcome, _) = ShareIngressHandler.handleNewOutcome(
            hasIntent = true,
            fingerprint = "token-file",
            sessionIdExtra = null,
            isSessionActive = false,
            activeSessionId = null,
            shareResult = shareResult
        )

        assertTrue(outcome is IngressOutcome.ShowDialog)
        val dialog = outcome as IngressOutcome.ShowDialog
        assertEquals("Invalid share", dialog.title)
        assertTrue(dialog.message.contains("Only content URIs are accepted"))
    }

    @Test
    fun handleNewOutcome_textShare_returnsUnsupportedTypeDialog() {
        val shareResult = ShareIngressParser.parseFromData(
            action = "android.intent.action.SEND",
            mimeType = "text/plain",
            textExtra = "https://example.com/invitation",
            rawUriStrings = emptyList()
        )

        val (outcome, _) = ShareIngressHandler.handleNewOutcome(
            hasIntent = true,
            fingerprint = "token-text",
            sessionIdExtra = null,
            isSessionActive = false,
            activeSessionId = null,
            shareResult = shareResult
        )

        assertTrue(outcome is IngressOutcome.ShowDialog)
        val dialog = outcome as IngressOutcome.ShowDialog
        assertEquals("Unsupported share", dialog.title)
        assertTrue(dialog.message.contains("Text and link sharing is not supported in Glide V1"))
    }

    @Test
    fun handleNewOutcome_batchLimitExceeded_returnsBatchLimitDialog() {
        val hugeList = (1..ProtocolLimits.MAX_ITEMS_PER_BATCH + 10).map {
            "content://media/photos/$it"
        }
        val shareResult = ShareIngressParser.parseFromData(
            action = "android.intent.action.SEND_MULTIPLE",
            mimeType = "image/*",
            textExtra = null,
            rawUriStrings = hugeList
        )

        val (outcome, _) = ShareIngressHandler.handleNewOutcome(
            hasIntent = true,
            fingerprint = "token-huge",
            sessionIdExtra = null,
            isSessionActive = false,
            activeSessionId = null,
            shareResult = shareResult
        )

        assertTrue(outcome is IngressOutcome.ShowDialog)
        val dialog = outcome as IngressOutcome.ShowDialog
        assertEquals("Batch limit exceeded", dialog.title)
        assertTrue(dialog.message.contains("Cannot share more than 1000 files"))
    }

    @Test
    fun tryTakePersistableUriPermissions_nullResolver_doesNotThrow() {
        ShareIngressHandler.tryTakePersistableUriPermissions(
            contentResolver = null,
            uris = listOf(Uri.parse("content://test/1"))
        )
    }
}
