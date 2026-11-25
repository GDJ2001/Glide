package dev.glide.app.ui.transfer

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.glide.app.installer.AppPackageInstaller
import dev.glide.core.domain.AppTransferMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun OfferedAppBadge(metadata: AppTransferMetadata) {
    val context = LocalContext.current
    val badge by produceState<String?>(null, metadata) {
        value = AppPackageInstaller.compareOffer(context, metadata)
    }
    badge?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
}

@Composable
internal fun AppInstallAction(key: String?, digest: String?, name: String, mime: String) {
    if (key == null || digest == null || !AppPackageInstaller.supports(mime, name)) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(key) { mutableStateOf(false) }
    var message by remember(key) { mutableStateOf<String?>(null) }
    var showPermission by remember(key) { mutableStateOf(false) }
    val inspection by produceState<AppPackageInstaller.Inspection?>(null, key, digest) {
        value = try {
            AppPackageInstaller.inspectVerified(context, key, name)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            AppPackageInstaller.Inspection("Android couldn’t read this app package.", null)
        }
    }

    inspection?.badge?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
    val installLabel = inspection?.installLabel
    if (installLabel != null) OutlinedButton(
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
    ) { Text(if (busy) "Preparing app…" else installLabel) }

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
