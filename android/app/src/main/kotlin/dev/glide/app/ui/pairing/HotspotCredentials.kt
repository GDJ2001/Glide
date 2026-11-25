package dev.glide.app.ui.pairing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import dev.glide.app.ui.components.GlideIconAction
import dev.glide.designsystem.isolateBidi

@Composable
internal fun HotspotCredentials(
    network: String,
    password: String?,
    onCopied: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var revealPassword by remember(network, password) { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CredentialValue("Network", network.isolateBidi(), Modifier.weight(1f))
            GlideIconAction(Icons.Default.ContentCopy, "Copy network name", {
                if (copyHotspotText(context, "Hotspot network", network)) onCopied("Network name copied")
            })
        }
        if (!password.isNullOrEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CredentialValue("Password", if (revealPassword) password.isolateBidi() else "••••••••", Modifier.weight(1f))
                GlideIconAction(
                    if (revealPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    if (revealPassword) "Hide password" else "Show password",
                    { revealPassword = !revealPassword }
                )
                GlideIconAction(Icons.Default.ContentCopy, "Copy password", {
                    if (copyHotspotText(context, "Hotspot password", password)) onCopied("Password copied")
                })
            }
        }
    }
}

@Composable
private fun CredentialValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun copyHotspotText(context: Context, label: String, value: String): Boolean {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    val clip = ClipData.newPlainText(label, value)
    clip.description.extras = PersistableBundle().apply {
        putBoolean("android.content.extra.IS_SENSITIVE", true)
    }
    clipboard.setPrimaryClip(clip)
    return true
}
