package dev.glide.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.glide.app.service.TransferRuntime
import dev.glide.app.ui.components.ActionTone
import dev.glide.app.ui.components.GlideIconAction
import kotlinx.coroutines.launch

data class RetainedResumeEntryUi(
    val sessionId: String,
    val label: String
)

@Composable
internal fun RetainedTransfersPanel(runtime: TransferRuntime, onResume: (String) -> Unit) {
    var entries by remember { mutableStateOf(emptyList<TransferRuntime.RetainedTransfer>()) }
    val active by runtime.isSessionActive.collectAsStateWithLifecycle()
    val trust by runtime.trustState.collectAsStateWithLifecycle()
    val retainedVersion by runtime.retainedTransfersVersion.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(active, trust.trustedPeers, retainedVersion) {
        if (!active) entries = runtime.listRetainedTransfers()
    }
    if (entries.isEmpty()) return

    RetainedTransfersCard(
        entries = entries.map { RetainedResumeEntryUi(it.sessionId, it.label) },
        enabled = !active,
        onResume = onResume,
        onDiscard = { sessionId ->
            scope.launch {
                runtime.discardRetainedTransfer(sessionId)
                entries = runtime.listRetainedTransfers()
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
fun RetainedTransfersCard(
    entries: List<RetainedResumeEntryUi>,
    enabled: Boolean,
    onResume: (String) -> Unit,
    onDiscard: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) return
    var pendingDiscard by remember { mutableStateOf<RetainedResumeEntryUi?>(null) }

    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    )) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Resumable transfers", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.heightIn(max = 200.dp)) {
                items(entries, key = { it.sessionId }) { entry ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(entry.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        GlideIconAction(
                            Icons.Default.PlayArrow,
                            "Resume transfer",
                            { onResume(entry.sessionId) },
                            enabled = enabled,
                            tone = ActionTone.Primary
                        )
                        GlideIconAction(
                            Icons.Default.DeleteOutline,
                            "Discard resumable transfer",
                            { pendingDiscard = entry },
                            enabled = enabled,
                            tone = ActionTone.Destructive
                        )
                    }
                }
            }
        }
    }
    pendingDiscard?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDiscard = null },
            title = { Text("Discard interrupted transfer?") },
            text = { Text("Glide will remove the partial progress. Any files already received will stay on this device.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDiscard = null
                    onDiscard(entry.sessionId)
                }, enabled = enabled) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { pendingDiscard = null }) { Text("Cancel") } }
        )
    }
}
