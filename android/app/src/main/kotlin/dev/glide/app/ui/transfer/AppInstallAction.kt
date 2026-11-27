package dev.glide.app.ui.transfer

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.glide.app.installer.AppPackageInstaller
import dev.glide.app.installer.InstallEvents
import dev.glide.app.ui.components.GlideIconAction
import dev.glide.core.domain.AppTransferMetadata
import dev.glide.designsystem.isolateBidi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun OfferedAppBadge(metadata: AppTransferMetadata) {
    val context = LocalContext.current
    val comparison by produceState<AppPackageInstaller.OfferComparison?>(null, metadata) {
        value = AppPackageInstaller.compareOffer(context, metadata)
    }
    comparison?.let { result ->
        Text(
            text = result.badge,
            style = MaterialTheme.typography.labelSmall,
            color = if (result.isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
internal fun ReceivedFileActions(
    key: String?,
    digest: String?,
    name: String,
    mime: String,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSave: (() -> Unit)?,
    onOpenFolder: (() -> Unit)? = null,
    saving: Boolean = false
) {
    val filename = name.isolateBidi()
    val actions: @Composable (@Composable () -> Unit) -> Unit = { install ->
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!AppPackageInstaller.isSplitBundle(mime, name)) {
                GlideIconAction(Icons.AutoMirrored.Filled.OpenInNew, "Open $filename", onOpen, tooltip = "Open file")
            }
            onOpenFolder?.let {
                GlideIconAction(Icons.Default.FolderOpen, "Open $filename in file manager", it, tooltip = "Open in file manager")
            }
            GlideIconAction(Icons.Default.Share, "Share $filename", onShare, tooltip = "Share file")
            onSave?.let {
                GlideIconAction(Icons.Default.Download, "Save $filename to selected folder", it,
                    enabled = !saving, tooltip = "Save file")
            }
            install()
        }
    }
    if (key != null && digest != null && AppPackageInstaller.supports(mime, name)) {
        AppInstallAction(key, digest, name, actions)
    } else {
        actions {}
    }
}

@Composable
private fun AppInstallAction(
    key: String,
    digest: String,
    name: String,
    actions: @Composable (@Composable () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(key) { mutableStateOf(false) }
    var message by remember(key) { mutableStateOf<String?>(null) }
    var showPermission by remember(key) { mutableStateOf(false) }
    var refreshTick by remember(key) { mutableIntStateOf(0) }

    LaunchedEffect(key) {
        InstallEvents.completed.collect {
            refreshTick++
        }
    }

    val inspection by produceState<AppPackageInstaller.Inspection?>(null, key, digest, refreshTick) {
        value = try {
            AppPackageInstaller.inspectVerified(context, key, name)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            AppPackageInstaller.Inspection("Android couldn’t read this app package.", null, isWarning = true)
        }
    }

    inspection?.let { current ->
        Text(
            text = current.badge,
            style = MaterialTheme.typography.labelSmall,
            color = if (current.isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
    }
    val installLabel = inspection?.installLabel
    actions {
        if (installLabel != null) GlideIconAction(
            icon = if (installLabel == "Update") Icons.Default.SystemUpdateAlt else Icons.Default.InstallMobile,
            label = if (busy) "Preparing app" else "$installLabel ${name.isolateBidi()}",
            tooltip = if (busy) "Preparing app…" else installLabel,
            enabled = !busy,
            onClick = {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    showPermission = true
                } else {
                    busy = true
                    scope.launch {
                        try {
                            message = AppPackageInstaller.install(context, key, digest, name)
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            message = "This app package couldn’t be installed. Check its version and compatibility."
                        } finally {
                            busy = false
                        }
                    }
                }
            }
        )
    }

    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (showPermission) {
        AlertDialog(
            onDismissRequest = { showPermission = false },
            title = { Text("Allow app installation") },
            text = { Text("Allow Glide in Android settings, then return to install the app.") },
            confirmButton = {
                TextButton(onClick = {
                    showPermission = false
                    val settings = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.fromParts("package", context.packageName, null)
                    )
                    runCatching { context.startActivity(settings) }.onFailure {
                        message = "Android couldn’t open app installation settings."
                    }
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { showPermission = false }) { Text("Cancel") } }
        )
    }
}
