package dev.glide.app.ui.discovery

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.glide.app.connectivity.ConnectionKind
import dev.glide.app.connectivity.automaticActionFor
import dev.glide.app.connectivity.connectionBlocker
import dev.glide.app.connectivity.hasPermission
import dev.glide.app.connectivity.requiredPermissions
import dev.glide.app.ui.failure.ActionableErrorMapper
import kotlinx.coroutines.launch

internal data class ConnectionRequest(
    val kind: ConnectionKind,
    val ready: () -> Unit,
    val failed: (BlockedReason) -> Unit,
    val launchedPanels: Set<String> = emptySet()
)

internal class ConnectionSetup {
    internal var pending by mutableStateOf<ConnectionRequest?>(null)
    internal var advance: (ConnectionRequest, BlockedReason?) -> Unit = { _, _ -> }
    var blocker by mutableStateOf<BlockedReason?>(null)
        internal set
    internal var blockedRequest: ConnectionRequest? = null

    fun start(kind: ConnectionKind, onBlocked: (BlockedReason) -> Unit = {}, onReady: () -> Unit) {
        if (pending != null) return
        blocker = null
        advance(ConnectionRequest(kind, onReady, onBlocked), null)
    }

    fun openSettings(
        kind: ConnectionKind,
        reason: BlockedReason,
        onBlocked: (BlockedReason) -> Unit,
        onReady: () -> Unit
    ) {
        if (pending != null) return
        blocker = null
        advance(ConnectionRequest(kind, onReady, onBlocked), reason)
    }

    fun cancel() {
        pending = null
        blocker = null
        blockedRequest = null
    }

    fun openSettings() {
        val request = blockedRequest ?: return
        val reason = blocker ?: return
        openSettings(request.kind, reason, request.failed, request.ready)
    }
}

private fun deniedPermissions(kind: ConnectionKind): BlockedReason =
    if (kind == ConnectionKind.Lan) BlockedReason.LanPermissionDenied else BlockedReason.PermissionsRequired

/** Only a visible sharing action may launch a permission dialog or a system panel. */
@Composable
internal fun rememberConnectionSetup(): ConnectionSetup {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setup = remember { ConnectionSetup() }

    fun fail(request: ConnectionRequest, reason: BlockedReason) {
        setup.pending = null
        setup.blocker = reason
        setup.blockedRequest = request
        request.failed(reason)
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        setup.pending?.let { request ->
            if (requiredPermissions(request.kind).all { context.hasPermission(it) }) {
                setup.advance(request, null)
            } else {
                fail(request, deniedPermissions(request.kind))
            }
        }
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        setup.pending?.let { setup.advance(it, null) }
    }

    setup.advance = { request, manualReason ->
        setup.pending = request
        scope.launch {
            if (setup.pending !== request) return@launch
            val required = requiredPermissions(request.kind)
            if (manualReason == null && "permission" !in request.launchedPanels &&
                required.any { !context.hasPermission(it) }) {
                setup.pending = request.copy(launchedPanels = request.launchedPanels + "permission")
                if (runCatching { permissions.launch(required.toTypedArray()) }.isFailure) {
                    fail(request, deniedPermissions(request.kind))
                }
                return@launch
            }

            val reason = manualReason ?: connectionBlocker(context, request.kind)
            if (setup.pending !== request) return@launch
            val panel = manualReason?.let { connectivitySettingsIntent(context, it) }
                ?: automaticActionFor(reason)?.let(::Intent)
            if (panel != null && panel.action !in request.launchedPanels) {
                val panelActions = request.launchedPanels + listOfNotNull(panel.action, automaticActionFor(reason))
                val actions = if (manualReason != null) panelActions + "permission" else panelActions
                setup.pending = request.copy(launchedPanels = actions)
                val launched = runCatching { settings.launch(panel) }.isSuccess
                if (!launched) fail(request, requireNotNull(reason))
            } else if (reason != null) {
                fail(request, reason)
            } else {
                setup.pending = null
                setup.blocker = null
                setup.blockedRequest = null
                request.ready()
            }
        }
    }
    return setup
}

@Composable
internal fun ConnectionSetupRecovery(
    setup: ConnectionSetup,
    kind: ConnectionKind? = null,
    onCancel: () -> Unit = {}
) {
    if (kind != null && setup.blockedRequest?.kind != kind) return
    val reason = setup.blocker ?: return
    val error = ActionableErrorMapper.fromBlockedReason(reason)
    val cancel = {
        setup.cancel()
        onCancel()
    }
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text(error.title) },
        text = { Text(error.userWording) },
        confirmButton = { TextButton(onClick = setup::openSettings) { Text(error.primaryActionLabel) } },
        dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } }
    )
}

