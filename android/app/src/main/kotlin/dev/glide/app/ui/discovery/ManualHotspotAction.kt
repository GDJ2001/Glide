package dev.glide.app.ui.discovery

import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun ManualHotspotAction(
    host: Boolean,
    onReady: () -> Unit,
    onStartHost: (() -> Unit)? = null,
    status: String? = null
) {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    var settingsUnavailable by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(if (host) "Hotspot Direct" else "Use a phone hotspot") }
    status?.let { Text(it) }
    if (!open) return

    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(if (host) "Set up a hotspot" else "Connect to a hotspot") },
        text = {
            Text(
                when {
                    settingsUnavailable -> "Open Android Settings to complete the connection, then return to Glide."
                    host -> "Start a hotspot in Glide, or turn on your phone’s hotspot in Android settings. For a system hotspot, connect the other phone first."
                    else -> "Connect to the receiving phone’s hotspot in Wi‑Fi settings. Ask for its hotspot password, then return here to scan its Glide QR code."
                }
            )
        },
        confirmButton = {
            TextButton(onClick = {
                open = false
                onReady()
            }) { Text(if (host) "Show QR code" else "Scan QR code") }
        },
        dismissButton = {
            onStartHost?.let { start ->
                TextButton(onClick = {
                    open = false
                    start()
                }) { Text("Start hotspot") }
            }
            TextButton(onClick = {
                val action = if (host) Settings.ACTION_WIRELESS_SETTINGS else Settings.ACTION_WIFI_SETTINGS
                runCatching { context.startActivity(Intent(action)) }.onFailure { settingsUnavailable = true }
            }) { Text(if (host) "Open network settings" else "Open Wi‑Fi settings") }
        }
    )
}
