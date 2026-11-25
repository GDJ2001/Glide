package dev.glide.app.ui.discovery

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.glide.app.lan.HotspotInterfaceWatcher
import kotlinx.coroutines.delay

@Composable
internal fun ManualHotspotAction(
    host: Boolean,
    onReady: () -> Unit,
    onStartHost: (() -> Unit)? = null,
    status: String? = null,
    externalOpen: Boolean = false,
    onExternalDismiss: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var internalOpen by rememberSaveable { mutableStateOf(false) }
    val open = internalOpen || externalOpen
    var settingsUnavailable by rememberSaveable { mutableStateOf(false) }
    var hotspotDetected by rememberSaveable { mutableStateOf(false) }
    var autoTriggered by rememberSaveable { mutableStateOf(false) }

    val closeDialog: () -> Unit = {
        internalOpen = false
        onExternalDismiss?.invoke()
    }

    LaunchedEffect(open, host) {
        if (!open || !host) {
            hotspotDetected = false
            autoTriggered = false
            return@LaunchedEffect
        }
        while (true) {
            val active = HotspotInterfaceWatcher.hasActiveHotspotIpv4()
            hotspotDetected = active
            if (active && !autoTriggered) {
                autoTriggered = true
                closeDialog()
                onReady()
                break
            }
            delay(750)
        }
    }

    TextButton(onClick = { internalOpen = true }) {
        Text(if (host) "Hotspot Direct" else "Use a phone hotspot")
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (!open) return

    AlertDialog(
        onDismissRequest = closeDialog,
        title = {
            Text(
                if (host) "Manual system hotspot setup"
                else "Connect to receiver hotspot"
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        settingsUnavailable ->
                            "Open Android Settings manually to turn on Hotspot / Wi-Fi, then return to Glide."
                        host ->
                            "1. Open Android Hotspot Settings and turn on your Portable Hotspot.\n" +
                                "2. Return here — Glide will detect the hotspot automatically and open your Receive QR code."
                        else ->
                            "1. Open Wi-Fi Settings and connect to the receiving device's hotspot.\n" +
                                "2. Return here and tap Scan QR code to start the transfer."
                    }
                )
                if (host) {
                    Text(
                        text = if (hotspotDetected) {
                            "Hotspot detected. Opening QR code…"
                        } else {
                            "Waiting for portable hotspot to turn on…"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (hotspotDetected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (host) {
                Button(
                    onClick = {
                        val opened = runCatching {
                            context.startActivity(Intent("android.settings.TETHER_SETTINGS"))
                        }.recoverCatching {
                            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                        }
                        if (opened.isFailure) settingsUnavailable = true
                    }
                ) {
                    Text("Open Hotspot Settings")
                }
            } else {
                Button(
                    onClick = {
                        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            Settings.Panel.ACTION_WIFI
                        } else {
                            Settings.ACTION_WIFI_SETTINGS
                        }
                        val opened = runCatching {
                            context.startActivity(Intent(action))
                        }.recoverCatching {
                            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                        }
                        if (opened.isFailure) settingsUnavailable = true
                    }
                ) {
                    Text("Open Wi-Fi Settings")
                }
            }
        },
        dismissButton = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (host) {
                    onStartHost?.let { start ->
                        TextButton(onClick = {
                            closeDialog()
                            start()
                        }) { Text("Retry Glide hotspot") }
                    }
                    OutlinedButton(
                        enabled = hotspotDetected,
                        onClick = {
                            closeDialog()
                            onReady()
                        }
                    ) { Text("Show Receive QR code") }
                } else {
                    OutlinedButton(
                        onClick = {
                            closeDialog()
                            onReady()
                        }
                    ) { Text("Scan receiver QR") }
                }
            }
        }
    )
}
