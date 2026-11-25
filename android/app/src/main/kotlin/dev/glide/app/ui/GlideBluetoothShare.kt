package dev.glide.app.ui

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
import dev.glide.app.util.prepareBluetoothShare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GlideBluetoothShare() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    TextButton(
        enabled = !preparing,
        onClick = {
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
    ) { Text(if (preparing) "Preparing Glide…" else "Share Glide APK via Bluetooth") }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Bluetooth sharing unavailable") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("Close") } }
        )
    }
}
