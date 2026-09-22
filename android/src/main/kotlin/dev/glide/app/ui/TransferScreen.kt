package dev.glide.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import dev.glide.app.navigation.GlideRoute
import dev.glide.app.service.TrustRuntimeState
import dev.glide.app.ui.pairing.PairingUiState
import dev.glide.app.ui.pairing.PairingVerificationCard
import dev.glide.app.ui.transfer.ExportUiStatus
import dev.glide.app.ui.transfer.OfferConsentCard
import dev.glide.app.ui.transfer.VerifiedItemUiState
import dev.glide.app.ui.transfer.TransferProgressCard
import dev.glide.app.ui.transfer.TransferResultCard
import dev.glide.app.ui.transfer.TransferUiState
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.PeerId
import dev.glide.designsystem.glideHoverHand
import dev.glide.designsystem.PrimaryButtonShape
import java.util.Locale

@Composable
fun TransferScreen(
    route: GlideRoute.Transfer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    uiState: TransferUiState = TransferUiState.Idle,
    onAcceptConsent: () -> Unit = {},
    onRejectConsent: () -> Unit = {},
    onCancelTransfer: () -> Unit = {},
    onOpenFile: () -> Unit = {},
    openFileFeedback: String? = null,
    onExportFile: () -> Unit = {},
    exportStatus: ExportUiStatus = ExportUiStatus.Idle,
    pairingState: PairingUiState = PairingUiState.Idle,
    onConfirmMatch: (PeerId) -> Unit = {},
    onRejectMatch: (PeerId) -> Unit = {},
    onRetryTransfer: () -> Unit = {},
    onShareFile: () -> Unit = {},
    onOpenItem: (VerifiedItemUiState) -> Unit = { onOpenFile() },
    onShareItem: (VerifiedItemUiState) -> Unit = { onShareFile() },
    onExportItem: (VerifiedItemUiState) -> Unit = { onExportFile() },
    onExportAll: () -> Unit = { onExportFile() },
    onShareAll: () -> Unit = { onShareFile() },
    trustState: TrustRuntimeState = TrustRuntimeState(),
    onApproveTrust: () -> Unit = {},
    onRejectTrust: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    val isActivelyVerifying = pairingState !is PairingUiState.Idle && pairingState !is PairingUiState.Connected

    BackHandler {
        if (isActivelyVerifying) {
            if (pairingState is PairingUiState.Verifying) {
                onRejectMatch(pairingState.peerId)
            } else {
                onBack()
            }
        } else if (uiState is TransferUiState.AwaitingConsent) {
            if (uiState.role == DeviceRole.Receiver) {
                onRejectConsent()
            } else {
                onCancelTransfer()
            }
        } else {
            onBack()
        }
    }

    val windowSizeClass = dev.glide.designsystem.LocalWindowSizeClass.current
    val postureInfo = dev.glide.designsystem.LocalDevicePosture.current
    val verticalSpacing = if (windowSizeClass.isCompactHeight) 12.dp else 24.dp

    Box(
        modifier = modifier.fillMaxSize().safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = dev.glide.designsystem.AdaptiveLayoutTokens.MaxDetailPaneWidth)
                .fillMaxSize()
                .padding(
                    horizontal = windowSizeClass.pageHorizontalPadding,
                    vertical = if (windowSizeClass.isCompactHeight) 8.dp else 16.dp
                )
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing)
        ) {
        // Navigation Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Transfer route navigation header"
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onBack,
                shape = PrimaryButtonShape,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .glideHoverHand()
                    .semantics {
                        role = Role.Button
                        contentDescription = "Navigate back to home"
                    }
            ) {
                Text(
                    text = "Back",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Text(
                text = "Session: ${route.sessionId.take(12)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 1. Connection & Pairing Verification Surface (Strictly isolated during verification)
        if (isActivelyVerifying) {
            PairingVerificationCard(
                state = pairingState,
                onConfirmMatch = onConfirmMatch,
                onRejectMatch = onRejectMatch
            )
        } else {
            // Main Transfer Phase State Presentation
            when (uiState) {
            is TransferUiState.Idle -> {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Preparing transfer session" }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "Initializing transfer...",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            is TransferUiState.Connecting -> {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Connecting to ${uiState.peerName}" }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "Connecting to ${uiState.peerName}...",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Negotiating transfer protocol capabilities",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f, fill = false))

                Button(
                    onClick = onCancelTransfer,
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
                            contentDescription = "Cancel transfer connection"
                        }
                ) {
                    Text(text = "Cancel", style = MaterialTheme.typography.titleMedium)
                }
            }

            is TransferUiState.AwaitingConsent -> {
                OfferConsentCard(
                    state = uiState,
                    onAcceptConsent = onAcceptConsent,
                    onRejectConsent = onRejectConsent,
                    onCancelTransfer = onCancelTransfer
                )
            }

            is TransferUiState.Transferring, is TransferUiState.Verifying -> {
                TransferProgressCard(
                    state = uiState,
                    onCancelTransfer = onCancelTransfer
                )
            }

            is TransferUiState.Completed, is TransferUiState.Failed, is TransferUiState.Cancelled -> {
                TransferResultCard(
                    state = uiState,
                    onBack = onBack,
                    onOpenFile = onOpenFile,
                    openFileFeedback = openFileFeedback,
                    onExportFile = onExportFile,
                    exportStatus = exportStatus,
                    onRetryTransfer = onRetryTransfer,
                    onShareFile = onShareFile,
                    onOpenItem = onOpenItem,
                    onShareItem = onShareItem,
                    onExportItem = onExportItem,
                    onExportAll = onExportAll,
                    onShareAll = onShareAll
                )
            }
        }
    }
}
}

    val pendingTrust = trustState.pendingEnrollment
    if (pendingTrust != null) {
        val fingerprint = pendingTrust.fingerprintHex
        val shortFingerprint = if (fingerprint.length > 16) {
            "${fingerprint.take(8)}...${fingerprint.takeLast(8)}"
        } else {
            fingerprint
        }

        AlertDialog(
            onDismissRequest = onRejectTrust,
            title = { Text("Trust this device?") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text =
                            "${pendingTrust.displayName} has proven possession of its persistent identity key on this secure connection."
                    )
                    Text(
                        text = "Identity: SHA-256 $shortFingerprint",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text =
                            "Trusting remembers this cryptographic identity for future authenticated sessions. It does not automatically accept future file offers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onApproveTrust
                ) {
                    Text("Trust device")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = onRejectTrust
                ) {
                    Text("Not now")
                }
            }
        )
    }
}
