package dev.glide.app.ui.discovery

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.glide.app.connectivity.isPhoneHotspotActive
import dev.glide.app.ui.components.GlideIconAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun ManualHotspotAction(
    host: Boolean,
    onReady: () -> Unit,
    onStartHost: (() -> Unit)? = null,
    status: String? = null,
    showCaption: Boolean = false,
    externalOpen: Boolean = false,
    onExternalDismiss: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
            val active = isPhoneHotspotActive(context)
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

    val openHostHotspotOrDialog: () -> Unit = {
        if (host) {
            scope.launch {
                if (isPhoneHotspotActive(context)) {
                    closeDialog()
                    onReady()
                } else {
                    internalOpen = true
                }
            }
        } else {
            internalOpen = true
        }
    }

    if (showCaption) {
        OutlinedButton(
            onClick = openHostHotspotOrDialog,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Icon(Icons.Default.WifiTethering, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Hotspot")
        }
    } else {
        GlideIconAction(Icons.Default.WifiTethering, "Use a phone hotspot", openHostHotspotOrDialog)
    }
    if (!open) return

    AlertDialog(
        onDismissRequest = closeDialog,
        title = {
            Text(
                if (host) "Phone hotspot"
                else "Join receiver hotspot"
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    when {
                        settingsUnavailable ->
                            "Open Android Settings manually to turn on Hotspot / Wi-Fi, then return to Glide."
                        host ->
                            "Turn on your phone hotspot, then return to Glide to show the receive QR code."
                        else ->
                            "Join the receiver’s hotspot in Wi-Fi settings, then scan their QR code."
                    }
                )
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (host && !hotspotDetected) {
                    Text("Waiting for hotspot…", style = MaterialTheme.typography.labelMedium)
                }
                if (host) {
                    TextButton(onClick = { closeDialog(); onReady() }) { Text("Show QR now") }
                    onStartHost?.let { start ->
                        TextButton(onClick = { closeDialog(); start() }) { Text("Start Glide hotspot") }
                    }
                } else {
                    TextButton(onClick = { closeDialog(); onReady() }) { Text("Scan receiver QR") }
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
                            context.startActivity(
                                Intent().setClassName(
                                    "com.android.settings",
                                    "com.android.settings.Settings\$WifiApSettingsActivity"
                                )
                            )
                        }.recoverCatching {
                            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                        }
                        if (opened.isFailure) settingsUnavailable = true
                    }
                ) {
                    Text("Hotspot settings")
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
                    Text("Wi-Fi settings")
                }
            }
        },
        dismissButton = { TextButton(onClick = closeDialog) { Text("Close") } }
    )
}
