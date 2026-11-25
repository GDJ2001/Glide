package dev.glide.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.glide.app.ui.components.GlideIconAction
import dev.glide.app.util.prepareBluetoothShare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GlideBluetoothShare() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val share: () -> Unit = {
        preparing = true
        scope.launch {
            try {
                val intent = prepareBluetoothShare(context)
                if (intent == null) {
                    error = "No Bluetooth file sharing handler is available on this device."
                } else {
                    runCatching { context.startActivity(intent) }.onFailure {
                        error = "Could not open Bluetooth sharing on this device."
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = "Glide couldn’t prepare a standalone APK on this device. Install the standalone Glide APK build to share via Bluetooth."
            } finally {
                preparing = false
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (preparing) "Preparing Glide…" else "Share Glide via Bluetooth",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            GlideIconAction(
                Icons.Default.Bluetooth,
                "Share Glide APK via Bluetooth",
                onClick = share,
                enabled = !preparing
            )
        }
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Bluetooth sharing unavailable") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("Close") } }
        )
    }
}
