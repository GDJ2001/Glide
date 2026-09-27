package dev.glide.app.ui.transfer

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.vector.ImageVector
import dev.glide.app.ui.discovery.DeviceType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glide.designsystem.AccessibilityProgressThrottler
import dev.glide.designsystem.isolateBidi
import dev.glide.core.domain.DeviceRole
import dev.glide.designsystem.PrimaryButtonShape

/**
 * Polished composable for active transfer progress and integrity verification phases.
 * Conforms strictly to GLIDE_DESIGN_SYSTEM.md, GLIDE_USER_FLOWS.md, and UI_QUALITY_CHECKLIST.md.
 * Strictly avoids fake 100% completion before cryptographic validation finishes.
 */
@Composable
fun TransferProgressCard(
    state: TransferUiState,
    onCancelTransfer: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (state) {
        is TransferUiState.Transferring -> {
            ActiveTransferringCard(
                state = state,
                onCancelTransfer = onCancelTransfer,
                modifier = modifier
            )
        }
        is TransferUiState.Verifying -> {
            IntegrityVerifyingCard(
                state = state,
                onCancelTransfer = onCancelTransfer,
                modifier = modifier
            )
        }
        else -> Unit
    }
}

@Composable
private fun ActiveTransferringCard(
    state: TransferUiState.Transferring,
    onCancelTransfer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isReducedMotion = dev.glide.designsystem.LocalReducedMotion.current
    val progressAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> = if (isReducedMotion) {
        androidx.compose.animation.core.snap()
    } else {
        tween(
            durationMillis = dev.glide.designsystem.GlideMotion.TransferProgressSampleMs,
            easing = dev.glide.designsystem.GlideMotion.StandardEasing
        )
    }

    val animatedProgress by animateFloatAsState(
        targetValue = state.progressFraction.coerceIn(0f, 1f),
        animationSpec = progressAnimationSpec,
        label = "transfer_progress_animation"
    )

    val roleLabel = if (state.role == DeviceRole.Sender) {
        "Sending to ${state.peerName.isolateBidi()}"
    } else {
        "Receiving from ${state.peerName.isolateBidi()}"
    }

    val remainingBytes = (state.totalBytes - state.transferredBytes).coerceAtLeast(0L)
    val speedText = TransferFormatting.formatSpeed(state.speedBytesPerSec, state.isStalled)
    val etaText = TransferFormatting.formatEta(remainingBytes, state.speedBytesPerSec, state.isStalled)
    val percentText = TransferFormatting.formatPercent(animatedProgress)

    val throttler = remember { AccessibilityProgressThrottler() }
    val intPercent = (animatedProgress * 100f).toInt().coerceIn(0, 100)
    var lastAnnouncement by remember {
        mutableStateOf("$roleLabel: ${state.itemName.isolateBidi()}: 0% complete.")
    }

    val milestoneAnnouncement = remember(intPercent, roleLabel, state.itemName) {
        throttler.checkAnnouncement(
            percent = intPercent,
            phase = roleLabel,
            itemName = state.itemName.isolateBidi()
        )
    }
    if (milestoneAnnouncement != null) {
        lastAnnouncement = milestoneAnnouncement
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "$roleLabel: ${state.itemName.isolateBidi()}. $percentText complete, ${TransferFormatting.formatBytes(state.transferredBytes)} of ${TransferFormatting.formatBytes(state.totalBytes)}."
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: Peer badge & Device Type
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = state.peerDeviceType.icon(),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = roleLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (state.itemCount > 1) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = "File ${state.itemIndex} of ${state.itemCount}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Item Name with 2-line safe wrapping
            Text(
                text = state.itemName.isolateBidi(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Large Tabular Percentage
            Text(
                text = percentText,
                fontSize = 48.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Default,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )

            // Linear Progress Track
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface
            )

            // Byte progress row
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${TransferFormatting.formatBytes(state.transferredBytes)} / ${TransferFormatting.formatBytes(state.totalBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Default,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Speed & ETA metrics pill
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp
                ) {
                    Text(
                        text = "$speedText • $etaText",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Cancel button
            Button(
                onClick = onCancelTransfer,
                shape = PrimaryButtonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(top = 8.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Cancel transfer and delete partial unverified files"
                    }
            ) {
                Text(
                    text = "Cancel transfer",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
private fun IntegrityVerifyingCard(
    state: TransferUiState.Verifying,
    onCancelTransfer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isSender = state.role == DeviceRole.Sender
    val headline = if (isSender) "Awaiting delivery receipt" else "Verifying file integrity"
    val description = if (isSender) {
        "Waiting for ${state.peerName.isolateBidi()} to confirm file integrity and commit to secure storage."
    } else {
        "Validating SHA-256 checksum for ${state.itemName.isolateBidi()} to guarantee bit-for-bit authenticity."
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "$headline: $description"
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Shield security icon badge
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp)
                )
            }

            // Headline
            Text(
                text = headline,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            // Peer context badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = state.peerDeviceType.icon(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = state.peerName.isolateBidi(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.itemCount > 1) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "File ${state.itemIndex} of ${state.itemCount}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Item Name
            Text(
                text = state.itemName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Restrained indeterminate progress bar (NO fake 100% complete!)
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer
            )

            // Cancel action
            OutlinedButton(
                onClick = onCancelTransfer,
                shape = PrimaryButtonShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(top = 8.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Cancel transfer verification"
                    }
            ) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

private fun DeviceType.icon(): ImageVector = when (this) {
    DeviceType.Phone -> Icons.Default.PhoneAndroid
    DeviceType.Tablet -> Icons.Default.TabletAndroid
    DeviceType.Computer -> Icons.Default.Computer
    DeviceType.Unknown -> Icons.Default.Devices
}
