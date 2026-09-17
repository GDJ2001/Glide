package dev.glide.app.ingress

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import dev.glide.app.navigation.GlideRoute
import dev.glide.app.service.TransferService

/**
 * Result of evaluating incoming intents at launch or onNewIntent (LAN-021).
 */
sealed interface IngressOutcome {

    /**
     * Navigate to the specified route.
     * Optionally carries URIs for which persistable permission grants should be attempted.
     */
    data class Route(
        val route: GlideRoute,
        val urisToPersist: List<Uri> = emptyList()
    ) : IngressOutcome

    /**
     * An incoming share was received while another transfer session was active.
     * Navigates to bring the active transfer session to foreground and displays the busy dialog.
     */
    data class ActiveSessionBusy(
        val activeSessionId: String?,
        val dialogTitle: String = "Transfer in progress",
        val dialogMessage: String = "Glide cannot start a new share while a transfer is active. Complete or cancel the current transfer before sharing new files."
    ) : IngressOutcome

    /**
     * Display an informative dialog to the user without changing route (e.g. invalid scheme, oversized batch).
     */
    data class ShowDialog(
        val title: String,
        val message: String
    ) : IngressOutcome

    /**
     * Intent was ignored or already handled (e.g. task recreation, duplicate intent).
     */
    data object NoOp : IngressOutcome
}

/**
 * Coordinates and validates Android Share Sheet ingress for MainActivity (LAN-021).
 *
 * Implements specifications from:
 * - docs/planning/LAN_QR_QUICK_SHARE_MASTER_PLAN.md (LAN-021, L5)
 * - docs/design/GLIDE_USER_FLOWS.md
 *
 * Enforces:
 * 1. Single-entry reuse of [ShareIngressParser].
 * 2. Every content URI and permission grant validated; full batch retained without single-file truncation.
 * 3. Ongoing active transfer sessions are brought to foreground and guarded against replacement/interruption.
 * 4. Task recreation / screen rotation (savedInstanceState != null) suppresses re-dispatch to avoid duplicate transfers.
 * 5. onNewIntent deduplicates repeat intents and handles incoming shares safely.
 */
object ShareIngressHandler {

    /**
     * Evaluates the initial launch intent during Activity.onCreate.
     *
     * @param intent Launch intent provided to the Activity.
     * @param isRecreating True when savedInstanceState != null (screen rotation, posture change, process restart).
     * @param isSessionActive True if a transfer session is currently running in the service.
     * @param activeSessionId Identifier of the currently running session, if any.
     */
    fun handleLaunchIntent(
        intent: Intent?,
        isRecreating: Boolean,
        isSessionActive: Boolean,
        activeSessionId: String?
    ): IngressOutcome {
        val sessionIdExtra = intent?.getStringExtra(TransferService.EXTRA_SESSION_ID)
        val shareResult = if (intent != null) ShareIngressParser.parse(intent) else ShareIngressResult.Ignored
        return handleLaunchOutcome(
            hasIntent = intent != null,
            sessionIdExtra = sessionIdExtra,
            isRecreating = isRecreating,
            isSessionActive = isSessionActive,
            activeSessionId = activeSessionId,
            shareResult = shareResult
        )
    }

    fun handleLaunchOutcome(
        hasIntent: Boolean,
        sessionIdExtra: String?,
        isRecreating: Boolean,
        isSessionActive: Boolean,
        activeSessionId: String?,
        shareResult: ShareIngressResult
    ): IngressOutcome {
        if (!hasIntent) return IngressOutcome.Route(GlideRoute.Home)
        if (sessionIdExtra != null) return IngressOutcome.Route(GlideRoute.Transfer(sessionIdExtra))
        if (isRecreating) return IngressOutcome.NoOp
        if (shareResult is ShareIngressResult.Ignored) return IngressOutcome.Route(GlideRoute.Home)
        if (isSessionActive) return IngressOutcome.ActiveSessionBusy(activeSessionId = activeSessionId)
        return mapShareResult(shareResult)
    }

    /**
     * Evaluates incoming intents delivered via Activity.onNewIntent.
     *
     * @param intent Newly delivered intent.
     * @param isSessionActive True if a transfer session is currently running.
     * @param activeSessionId Identifier of the currently running session, if any.
     * @param lastHandledFingerprint Fingerprint of the most recently handled intent for deduplication.
     * @return Pair of [IngressOutcome] and the updated fingerprint string.
     */
    fun handleNewIntent(
        intent: Intent?,
        isSessionActive: Boolean,
        activeSessionId: String?,
        lastHandledFingerprint: String? = null
    ): Pair<IngressOutcome, String?> {
        if (intent == null) return IngressOutcome.NoOp to lastHandledFingerprint
        val fingerprint = computeIntentFingerprint(intent)
        val sessionIdExtra = intent.getStringExtra(TransferService.EXTRA_SESSION_ID)
        val shareResult = ShareIngressParser.parse(intent)
        return handleNewOutcome(
            hasIntent = true,
            fingerprint = fingerprint,
            sessionIdExtra = sessionIdExtra,
            isSessionActive = isSessionActive,
            activeSessionId = activeSessionId,
            shareResult = shareResult,
            lastHandledFingerprint = lastHandledFingerprint
        )
    }

    fun handleNewOutcome(
        hasIntent: Boolean,
        fingerprint: String?,
        sessionIdExtra: String?,
        isSessionActive: Boolean,
        activeSessionId: String?,
        shareResult: ShareIngressResult,
        lastHandledFingerprint: String? = null
    ): Pair<IngressOutcome, String?> {
        if (!hasIntent) return IngressOutcome.NoOp to lastHandledFingerprint
        if (fingerprint != null && fingerprint == lastHandledFingerprint) {
            return IngressOutcome.NoOp to lastHandledFingerprint
        }
        if (sessionIdExtra != null) {
            return IngressOutcome.Route(GlideRoute.Transfer(sessionIdExtra)) to fingerprint
        }
        if (shareResult is ShareIngressResult.Ignored) {
            return IngressOutcome.NoOp to fingerprint
        }
        if (isSessionActive) {
            return IngressOutcome.ActiveSessionBusy(activeSessionId = activeSessionId) to fingerprint
        }
        return mapShareResult(shareResult) to fingerprint
    }

    /**
     * Safely attempts to acquire persistable read URI permissions for content URIs.
     */
    fun tryTakePersistableUriPermissions(
        contentResolver: ContentResolver?,
        uris: List<Uri>,
        flags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
    ) {
        if (contentResolver == null || uris.isEmpty()) return
        for (uri in uris) {
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) {
                // Expected for transient Share Sheet content grants from other apps
            } catch (_: Exception) {
                // Defensive catch against vendor-specific ContentResolver anomalies
            }
        }
    }

    private fun mapShareResult(result: ShareIngressResult): IngressOutcome {
        return when (result) {
            is ShareIngressResult.Success -> {
                IngressOutcome.Route(
                    route = GlideRoute.Selection(initialUris = result.uriStrings),
                    urisToPersist = result.uris
                )
            }
            is ShareIngressResult.UnsupportedType -> {
                IngressOutcome.ShowDialog(
                    title = "Unsupported share",
                    message = result.message
                )
            }
            is ShareIngressResult.InvalidScheme -> {
                IngressOutcome.ShowDialog(
                    title = "Invalid share",
                    message = "Direct file and web URL sharing is not permitted for security reasons. Only content URIs are accepted."
                )
            }
            is ShareIngressResult.BatchLimitExceeded -> {
                IngressOutcome.ShowDialog(
                    title = "Batch limit exceeded",
                    message = "Cannot share more than ${result.max} files in a single batch (selected: ${result.count})."
                )
            }
            is ShareIngressResult.Error -> {
                IngressOutcome.ShowDialog(
                    title = "Cannot share files",
                    message = result.message
                )
            }
            is ShareIngressResult.Ignored -> IngressOutcome.NoOp
        }
    }

    private fun computeIntentFingerprint(intent: Intent): String? {
        val action = intent.action ?: return null
        val data = intent.dataString ?: ""
        val type = intent.type ?: ""
        val clipCount = intent.clipData?.itemCount ?: 0
        return "$action:$type:$data:$clipCount"
    }
}
