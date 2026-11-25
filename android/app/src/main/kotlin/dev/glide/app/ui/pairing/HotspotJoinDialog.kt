package dev.glide.app.ui.pairing

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.glide.app.ui.components.GlideIconAction
import dev.glide.core.pairing.HotspotInvitation
import dev.glide.transport.lan.network.AndroidHotspotConnections
import kotlinx.coroutines.launch

@Composable
internal fun HotspotJoinDialog(
    invitation: HotspotInvitation,
    onReady: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copyNotice by remember(invitation) { mutableStateOf<String?>(null) }
    var joining by remember(invitation) { mutableStateOf(false) }
    var manual by remember(invitation) { mutableStateOf(false) }
    val wifiPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        android.Manifest.permission.NEARBY_WIFI_DEVICES
    } else android.Manifest.permission.ACCESS_FINE_LOCATION
    val tryJoin: () -> Unit = {
        joining = true
        scope.launch {
            try {
                if (AndroidHotspotConnections.join(context, invitation.ssid, invitation.passphrase)) {
                    onReady()
                } else {
                    manual = true
                }
            } finally {
                joining = false
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) tryJoin() else manual = true
    }
    AlertDialog(
        onDismissRequest = { if (!joining) onDismiss() },
        title = { Text("Join hotspot") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HotspotCredentials(invitation.ssid, invitation.passphrase) { copyNotice = it }
                copyNotice?.let { message ->
                    Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (manual) {
                    Text("Join this network in Wi-Fi settings, then return to Glide.")
                    GlideIconAction(Icons.Default.Settings, "Open Wi-Fi settings", {
                        runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                    })
                } else if (!joining) {
                    TextButton(onClick = { manual = true }) { Text("Connect manually") }
                }
            }
        },
        confirmButton = {
            Button(enabled = !joining, onClick = {
                when {
                    manual -> onReady()
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> manual = true
                    ContextCompat.checkSelfPermission(context, wifiPermission) == PackageManager.PERMISSION_GRANTED -> tryJoin()
                    else -> permission.launch(wifiPermission)
                }
            }) { Text(if (joining) "Connecting…" else if (manual) "Continue" else "Join") }
        },
        dismissButton = {
            TextButton(enabled = !joining, onClick = onDismiss) { Text("Cancel") }
        }
    )
}
