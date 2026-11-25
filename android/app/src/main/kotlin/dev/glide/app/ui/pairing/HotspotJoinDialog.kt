package dev.glide.app.ui.pairing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(invitation.ssid)
                if (manual) {
                    Text("Join this network in Wi‑Fi settings, then return to Glide.")
                    TextButton(onClick = {
                        val clip = ClipData.newPlainText("Hotspot password", invitation.passphrase)
                        clip.description.extras = PersistableBundle().apply {
                            putBoolean("android.content.extra.IS_SENSITIVE", true)
                        }
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                    }) { Text("Copy password") }
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                    }) { Text("Open Wi‑Fi settings") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !joining, onClick = {
                if (manual) {
                    onReady()
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    manual = true
                } else if (ContextCompat.checkSelfPermission(context, wifiPermission) == PackageManager.PERMISSION_GRANTED) {
                    tryJoin()
                } else {
                    permission.launch(wifiPermission)
                }
            }) { Text(if (joining) "Connecting…" else if (manual) "Continue" else "Join") }
        },
        dismissButton = {
            if (!manual && !joining) {
                TextButton(onClick = { manual = true }) { Text("Connect manually") }
            }
            TextButton(enabled = !joining, onClick = onDismiss) { Text("Cancel") }
        }
    )
}
