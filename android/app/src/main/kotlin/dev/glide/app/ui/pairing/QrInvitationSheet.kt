package dev.glide.app.ui.pairing

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.glide.app.ui.components.ActionTone
import dev.glide.app.ui.components.GlideIconAction
import dev.glide.core.pairing.InvitationCodec
import dev.glide.designsystem.PrimaryButtonShape
import dev.glide.designsystem.isolateBidi
import kotlinx.coroutines.delay

/**
 * Material 3 Bottom Sheet displaying the offline Receive QR code, 120-second countdown,
 * quiet zone, copy invitation action with sensitive clipboard flags, and refresh CTA (LAN-018).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrInvitationSheet(
    uiState: QrReceiverUiState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var copyNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copyNotice) {
        if (copyNotice != null) {
            delay(2_500L)
            copyNotice = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier.semantics {
            contentDescription = "Receive QR code bottom sheet"
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header Row: Title and Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Receive via QR code",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).semantics { heading() }
                )
                GlideIconAction(Icons.Default.Close, "Close QR code", onDismiss)
            }

            when (uiState) {
                is QrReceiverUiState.Idle, is QrReceiverUiState.Starting -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Preparing secure local QR code...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                is QrReceiverUiState.Ready -> {
                    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                    val hasHotspot = !uiState.hotspotSsid.isNullOrBlank()
                    val isCompactHeight = configuration.screenHeightDp < 480
                    val isTightHotspot = hasHotspot && configuration.screenHeightDp < 760
                    val qrCardSize = when {
                        isCompactHeight -> 184.dp
                        isTightHotspot -> 228.dp
                        else -> 264.dp
                    }
                    val qrImageSize = when {
                        isCompactHeight -> 168.dp
                        isTightHotspot -> 212.dp
                        else -> 244.dp
                    }
                    val cardInnerPadding = when {
                        isCompactHeight -> 8.dp
                        isTightHotspot -> 8.dp
                        else -> 10.dp
                    }

                    // High-contrast quiet zone container (always white background for optimal optical scan)
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                        modifier = Modifier.semantics {
                            contentDescription = "QR code for pairing with ${uiState.localDeviceName.isolateBidi()}. Discoverable for ${uiState.secondsRemaining} seconds."
                        }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(qrCardSize)
                                .padding(cardInnerPadding),
                            contentAlignment = Alignment.Center
                        ) {
                            if (uiState.qrBitmap != null) {
                                Image(
                                    bitmap = uiState.qrBitmap.asImageBitmap(),
                                    contentDescription = "Pairing QR code",
                                    filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    modifier = Modifier.size(qrImageSize)
                                )
                            } else {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    // 120-second countdown indicator
                    val isExpiringSoon = uiState.secondsRemaining <= 30
                    val timerColor = if (isExpiringSoon) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.semantics {
                            contentDescription = "Valid for ${uiState.secondsRemaining} seconds"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.HourglassBottom,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = timerColor
                        )
                        Text(
                            text = "Valid for ${uiState.secondsRemaining}s",
                            style = MaterialTheme.typography.titleMedium,
                            color = timerColor
                        )
                    }

                    // Device & Endpoint Summary
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = uiState.localDeviceName.isolateBidi(),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                    }

                    if (!uiState.hotspotSsid.isNullOrBlank()) {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                HotspotCredentials(uiState.hotspotSsid, uiState.hotspotPassphrase) {
                                    copyNotice = it
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                    ) {
                        GlideIconAction(Icons.Default.Refresh, "Refresh QR code", onRefresh)
                        GlideIconAction(Icons.Default.ContentCopy, "Copy receive link", {
                            copyInvitationToClipboard(
                                context,
                                uiState.hotspotQr ?: InvitationCodec.encode(uiState.invitation)
                            )
                            copyNotice = "Invitation copied"
                        }, tone = ActionTone.Primary)
                    }

                    if (copyNotice != null) {
                        Text(
                            text = copyNotice ?: "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                is QrReceiverUiState.Expired -> {
                    // Expired state: Dimmed QR card with warning badge
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier
                            .size(240.dp)
                            .alpha(0.6f)
                    ) {
                        Box(
                            modifier = Modifier.size(240.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(48.dp)
                                )
                                Text(
                                    text = "QR Code Expired",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onRefresh,
                        shape = PrimaryButtonShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Generate new QR code"
                            }
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Generate new QR code", style = MaterialTheme.typography.titleSmall)
                    }
                }

                is QrReceiverUiState.Error -> {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = uiState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    if (uiState.canRetry) {
                        Button(
                            onClick = onRefresh,
                            shape = PrimaryButtonShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }
}

private fun copyInvitationToClipboard(context: Context, invitationUri: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText("Glide LAN Invitation", invitationUri)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        clip.description.extras = extras
    }

    clipboard.setPrimaryClip(clip)
}
