package dev.glide.app.ui.transfer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import dev.glide.designsystem.glideHoverHand
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.glide.core.domain.DeviceRole
import dev.glide.designsystem.PrimaryButtonShape
import dev.glide.designsystem.onWarningContainer
import dev.glide.designsystem.warning
import dev.glide.designsystem.warningContainer
import dev.glide.designsystem.isolateBidi

/**
 * Polished composable for all terminal transfer result states:
 * Completed (receiver & sender), Interrupted (partial completion with retry), Failed, and Cancelled.
 * Conforms strictly to GLIDE_DESIGN_SYSTEM.md and GLIDE_USER_FLOWS.md.
 */
@Composable
fun TransferResultCard(
    state: TransferUiState,
    onBack: () -> Unit,
    onOpenFile: () -> Unit = {},
    openFileFeedback: String? = null,
    onExportFile: () -> Unit = {},
    exportStatus: ExportUiStatus = ExportUiStatus.Idle,
    onRetryTransfer: () -> Unit = {},
    onShareFile: () -> Unit = {},
    onOpenItem: (VerifiedItemUiState) -> Unit = { onOpenFile() },
    onShareItem: (VerifiedItemUiState) -> Unit = { onShareFile() },
    onExportItem: (VerifiedItemUiState) -> Unit = { onExportFile() },
    onExportAll: () -> Unit = { onExportFile() },
    onShareAll: () -> Unit = { onShareFile() },
    modifier: Modifier = Modifier
) {
    val haptics = dev.glide.designsystem.GlideTheme.haptics

    androidx.compose.runtime.LaunchedEffect(state) {
        when (state) {
            is TransferUiState.Completed -> haptics.performSuccess()
            is TransferUiState.Failed -> haptics.performError()
            else -> Unit
        }
    }

    when (state) {
        is TransferUiState.Completed -> {
            CompletedResultCard(
                state = state,
                onBack = onBack,
                onOpenFile = onOpenFile,
                openFileFeedback = openFileFeedback,
                onExportFile = onExportFile,
                exportStatus = exportStatus,
                onShareFile = onShareFile,
                onOpenItem = onOpenItem,
                onShareItem = onShareItem,
                onExportItem = onExportItem,
                onExportAll = onExportAll,
                onShareAll = onShareAll,
                modifier = modifier
            )
        }
        is TransferUiState.Failed -> {
            FailedResultCard(
                state = state,
                onBack = onBack,
                onRetryTransfer = onRetryTransfer,
                modifier = modifier
            )
        }
        is TransferUiState.Cancelled -> {
            CancelledResultCard(
                state = state,
                onBack = onBack,
                modifier = modifier
            )
        }
        else -> Unit
    }
}

@Composable
private fun CompletedResultCard(
    state: TransferUiState.Completed,
    onBack: () -> Unit,
    onOpenFile: () -> Unit,
    openFileFeedback: String?,
    onExportFile: () -> Unit,
    exportStatus: ExportUiStatus,
    onShareFile: () -> Unit = {},
    onOpenItem: (VerifiedItemUiState) -> Unit = { onOpenFile() },
    onShareItem: (VerifiedItemUiState) -> Unit = { onShareFile() },
    onExportItem: (VerifiedItemUiState) -> Unit = { onExportFile() },
    onExportAll: () -> Unit = { onExportFile() },
    onShareAll: () -> Unit = { onShareFile() },
    modifier: Modifier = Modifier
) {
    val isReceiver = state.role == DeviceRole.Receiver
    val totalFormatted = TransferFormatting.formatBytes(state.totalBytes)
    val filesSummary = if (state.itemCount > 1) {
        "${state.itemCount} files ($totalFormatted)"
    } else {
        "${state.itemName.isolateBidi()} ($totalFormatted)"
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Transfer complete: $filesSummary."
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Checkmark Success Badge
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(36.dp)
                )
            }

            // Headline
            Text(
                text = "Transfer complete",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            // Batch summary badge
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = filesSummary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isReceiver) "From ${state.peerName.isolateBidi()}" else "Delivered to ${state.peerName.isolateBidi()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Receiver Storage Guarantee Banner
            if (isReceiver) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(20.dp)
                                .padding(top = 2.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Saved in Glide • export to keep outside the app",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Received files are kept in private app storage. No external app can access them until you explicitly export.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "Delivery confirmed by recipient's device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            // Itemized Verified Items
            if (state.verifiedItems.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Verified items (${state.verifiedItems.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    state.verifiedItems.forEach { item ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.displayName.isolateBidi(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${TransferFormatting.formatBytes(item.sizeBytes)} • ${item.mimeType}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.padding(start = 8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Text(
                                                text = "Verified",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }

                                if (isReceiver && item.committedFileKey != null) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { onOpenItem(item) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 40.dp)
                                                .glideHoverHand()
                                                .semantics {
                                                    role = Role.Button
                                                    contentDescription = "Open ${item.displayName.isolateBidi()}"
                                                }
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Open", style = MaterialTheme.typography.labelSmall)
                                        }

                                        OutlinedButton(
                                            onClick = { onShareItem(item) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 40.dp)
                                                .glideHoverHand()
                                                .semantics {
                                                    role = Role.Button
                                                    contentDescription = "Share ${item.displayName.isolateBidi()}"
                                                }
                                        ) {
                                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Share", style = MaterialTheme.typography.labelSmall)
                                        }

                                        Button(
                                            onClick = { onExportItem(item) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 40.dp)
                                                .glideHoverHand()
                                                .semantics {
                                                    role = Role.Button
                                                    contentDescription = "Export ${item.displayName.isolateBidi()} to Downloads"
                                                }
                                        ) {
                                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Export", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Feedback messages
            if (openFileFeedback != null) {
                Text(
                    text = openFileFeedback,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = openFileFeedback }
                )
            }

            when (exportStatus) {
                is ExportUiStatus.Idle -> {}
                is ExportUiStatus.Exporting -> {
                    Text(
                        text = "Exporting to Downloads…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Exporting to Downloads" }
                    )
                }
                is ExportUiStatus.Success -> {
                    Text(
                        text = "Exported to Downloads as ${exportStatus.destinationName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Exported to Downloads as ${exportStatus.destinationName}" }
                    )
                }
                is ExportUiStatus.Failed -> {
                    Text(
                        text = exportStatus.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = exportStatus.message }
                    )
                }
            }

            // Action Buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isReceiver && (state.committedFileKey != null || state.verifiedItems.any { it.committedFileKey != null })) {
                    if (state.verifiedItems.size > 1) {
                        Button(
                            onClick = onExportAll,
                            enabled = exportStatus !is ExportUiStatus.Exporting,
                            shape = PrimaryButtonShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .semantics {
                                    role = Role.Button
                                    contentDescription = "Export all ${state.verifiedItems.size} files to Downloads"
                                }
                        ) {
                            Text(
                                text = if (exportStatus is ExportUiStatus.Exporting) "Exporting…" else "Export all to Downloads",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        OutlinedButton(
                            onClick = onShareAll,
                            shape = PrimaryButtonShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .semantics {
                                    role = Role.Button
                                    contentDescription = "Share all verified files"
                                }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Share all files", style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        Button(
                            onClick = onExportFile,
                            enabled = exportStatus !is ExportUiStatus.Exporting,
                            shape = PrimaryButtonShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .semantics {
                                    role = Role.Button
                                    contentDescription = "Export received file ${state.itemName.isolateBidi()} to Downloads"
                                }
                        ) {
                            Text(
                                text = if (exportStatus is ExportUiStatus.Exporting) "Exporting…" else "Export to Downloads",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        OutlinedButton(
                            onClick = onShareFile,
                            shape = PrimaryButtonShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .semantics {
                                    role = Role.Button
                                    contentDescription = "Share received file ${state.itemName.isolateBidi()}"
                                }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Share file", style = MaterialTheme.typography.labelLarge)
                        }

                        OutlinedButton(
                            onClick = onOpenFile,
                            shape = PrimaryButtonShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .semantics {
                                    role = Role.Button
                                    contentDescription = "Open received file ${state.itemName.isolateBidi()}"
                                }
                        ) {
                            Text(text = "Open file", style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    OutlinedButton(
                        onClick = onBack,
                        shape = PrimaryButtonShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Dismiss transfer and return home"
                            }
                    ) {
                        Text(text = "Done", style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    Button(
                        onClick = onBack,
                        shape = PrimaryButtonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Dismiss transfer and return home"
                            }
                    ) {
                        Text(text = "Done", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun FailedResultCard(
    state: TransferUiState.Failed,
    onBack: () -> Unit,
    onRetryTransfer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val headline = if (state.isInterrupted) "Transfer interrupted" else "Transfer failed"
    val containerColor = if (state.canRetry) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val iconColor = if (state.canRetry) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "$headline: ${state.message}."
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Warning/Error Icon
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(32.dp)
                )
            }

            // Headline
            Text(
                text = headline,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            // Message
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // Multi-file partial completion disclosure
            if (state.totalCount > 1) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "${state.completedCount} of ${state.totalCount} files completed and safely saved",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "${state.unfinishedCount} files remaining",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Unconfirmed receipt warning banner
            if (state.isReceiptUnconfirmed) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.warningContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.warning,
                            modifier = Modifier
                                .size(20.dp)
                                .padding(top = 2.dp)
                        )
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Delivery unconfirmed",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onWarningContainer
                            )
                            Text(
                                text = "The receiver may have already saved the last file before the connection dropped. Retrying may create a duplicate on their device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onWarningContainer
                            )
                        }
                    }
                }
            }

            // Monospace code
            Text(
                text = "Code: ${state.code}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Action Buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.canRetry) {
                    Button(
                        onClick = onRetryTransfer,
                        shape = PrimaryButtonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Retry unfinished items (${state.unfinishedCount} remaining)"
                            }
                    ) {
                        Text(
                            text = if (state.unfinishedCount > 0) {
                                "Retry unfinished items (${state.unfinishedCount} remaining)"
                            } else {
                                "Retry transfer"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    OutlinedButton(
                        onClick = onBack,
                        shape = PrimaryButtonShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Dismiss error and return home"
                            }
                    ) {
                        Text(text = "Back to home", style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    Button(
                        onClick = onBack,
                        shape = PrimaryButtonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Dismiss error and return home"
                            }
                    ) {
                        Text(text = "Back to home", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun CancelledResultCard(
    state: TransferUiState.Cancelled,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Transfer cancelled: ${state.reason}."
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Cancelled Icon
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
            }

            // Headline
            Text(
                text = "Transfer cancelled",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            // Reason
            Text(
                text = state.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // Reassurance note
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Transfer was stopped. Any unverified partial files were safely removed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(14.dp)
                )
            }

            // Return to Home button
            Button(
                onClick = onBack,
                shape = PrimaryButtonShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(top = 8.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Return to home"
                    }
            ) {
                Text(text = "Back to home", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
