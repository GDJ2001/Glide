package dev.glide.app.ui

import android.content.Intent
import android.net.Uri
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import dev.glide.app.util.prepareBluetoothShare

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
                    if (intent == null) error = "This phone can’t send a Glide APK over Bluetooth."
                    else context.startActivity(intent)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = "Glide couldn’t prepare a standalone APK on this phone."
                } finally {
                    preparing = false
                }
            }
        }
    ) { Text(if (preparing) "Preparing Glide…" else "Share Glide via Bluetooth") }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Bluetooth sharing unavailable") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    error = null
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://github.com/GDJ2001/Glide/releases/latest"
                    )))
                }) { Text("Open download page") }
            },
            dismissButton = { TextButton(onClick = { error = null }) { Text("Close") } }
        )
    }
}
