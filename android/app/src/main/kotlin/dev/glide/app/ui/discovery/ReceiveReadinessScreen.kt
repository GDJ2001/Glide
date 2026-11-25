package dev.glide.app.ui.discovery

import androidx.compose.foundation.layout.PaddingValues

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.glide.app.ui.components.ActionTone
import dev.glide.app.ui.components.GlideCompactTopBar
import dev.glide.app.ui.components.GlideIconAction
import dev.glide.designsystem.PrimaryButtonShape
import dev.glide.designsystem.isolateBidi

@Composable
fun ReceiveReadinessScreen(
    uiState: ReceiveReadinessUiState,
    onStopReceiving: () -> Unit,
    onRestartReceiving: () -> Unit,
    modifier: Modifier = Modifier,
    onShowQrCode: (() -> Unit)? = null,
    onScanPcQr: (() -> Unit)? = null,
    onStartHotspot: (() -> Unit)? = null,
    hotspotStatus: String? = null,
    showManualHotspotFallback: Boolean = false,
    onDismissManualHotspotFallback: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        GlideCompactTopBar("Receive files") {
            GlideIconAction(Icons.Default.Stop, "Stop receiving", onStopReceiving, tone = ActionTone.Destructive)
        }

        when (uiState) {
            is ReceiveReadinessUiState.Advertising -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ReceiveTonalBreathingBadge()
                        Text("Ready to receive", style = MaterialTheme.typography.titleLarge)
                        Text(
                            uiState.localDeviceName.isolateBidi(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text("Discoverable for ${uiState.secondsRemaining}s", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            is ReceiveReadinessUiState.TimedOut -> {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Receive discoverability timed out after 120 seconds." }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.HourglassBottom,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Receiving timed out",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = onStopReceiving,
                                shape = PrimaryButtonShape,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .semantics {
                                        role = Role.Button
                                        contentDescription = "Done, return to home"
                                    }
                            ) {
                                Text("Done")
                            }
                            Button(
                                onClick = onRestartReceiving,
                                shape = PrimaryButtonShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .semantics {
                                        role = Role.Button
                                        contentDescription = "Start receiving again"
                                    }
                            ) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Start again")
                            }
                        }
                    }
                }
            }

            is ReceiveReadinessUiState.Blocked -> {
                DiscoveryBlockedCard(
                    reason = uiState.reason,
                    onRetry = onRestartReceiving,
                    onOpenSettings = { openConnectivitySettings(context, uiState.reason) }
                )
            }

            is ReceiveReadinessUiState.IncomingConnection -> {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Incoming connection from ${uiState.peer.displayName.isolateBidi()}. Verification required."
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Incoming connection",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "From: ${uiState.peer.displayName.isolateBidi()}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            onShowQrCode?.let {
                ReceiveDockAction(Icons.Default.QrCode2, "Show QR", "Show QR code for receiving files", it)
            }
            onScanPcQr?.let {
                ReceiveDockAction(Icons.Default.QrCodeScanner, "Scan QR", "Scan PC QR code", it)
            }
            onShowQrCode?.let {
                ManualHotspotAction(
                    host = true,
                    onReady = it,
                    onStartHost = onStartHotspot,
                    status = hotspotStatus,
                    showCaption = true,
                    externalOpen = showManualHotspotFallback,
                    onExternalDismiss = onDismissManualHotspotFallback
                )
            }
        }
    }
}

@Composable
private fun ReceiveDockAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accessibleLabel: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = accessibleLabel }
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

/**
 * Calm 2.4s low-amplitude tonal breathing badge for receive readiness.
 */
@Composable
private fun ReceiveTonalBreathingBadge() {
    val alpha by dev.glide.app.ui.motion.rememberTonalBreathingAlpha(
        initialValue = 0.6f,
        targetValue = 1.0f,
        isActive = true
    )

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = alpha * 0.15f),
        modifier = Modifier.size(if (dev.glide.designsystem.LocalWindowSizeClass.current.isCompactHeight) 62.dp else 72.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Devices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}
