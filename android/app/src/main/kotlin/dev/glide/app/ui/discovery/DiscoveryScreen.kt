package dev.glide.app.ui.discovery

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.glide.core.domain.PeerId
import dev.glide.designsystem.PrimaryButtonShape
import dev.glide.designsystem.brandCyanAccent
import dev.glide.designsystem.glideHoverHand
import dev.glide.designsystem.isolateBidi

@Composable
fun DiscoveryScreen(
    uiState: DiscoveryUiState,
    onBack: () -> Unit,
    onSelectPeer: (PeerId) -> Unit,
    onRetry: () -> Unit,
    onScanQrClicked: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptics = dev.glide.designsystem.GlideTheme.haptics
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Navigation Bar
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Discovery navigation bar" },
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Back to file selection"
                        }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Select recipient",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() }
                )
            }
        }

        // Header with subtle ambient wash (<= 8% opacity) and 2.4s low-amplitude tonal breathing indicator
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.brandCyanAccent.copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(vertical = 12.dp, horizontal = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Searching for nearby devices...",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )

                    if (uiState is DiscoveryUiState.Searching) {
                        DiscoveryBreathingIndicator()
                    }
                }
            }
        }

        // Main Discovery Content State Presentation
        when (uiState) {
            is DiscoveryUiState.Searching -> {
                if (uiState.peers.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp, horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.semantics {
                                    contentDescription = "Looking for nearby devices. Waiting for receiving devices to appear."
                                }
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(36.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 3.dp
                                )
                                OutlinedButton(
                                    onClick = onScanQrClicked,
                                    shape = PrimaryButtonShape,
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .semantics {
                                            role = Role.Button
                                            contentDescription = "Scan QR code or paste invitation"
                                        }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QrCode2,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Scan QR or paste invite")
                                }
                            }
                        }
                    }
                } else {
                    items(
                        items = uiState.peers,
                        key = { it.id.value }
                    ) { peer ->
                        DiscoveredPeerCard(
                            peer = peer,
                            hasActiveSelection = uiState.selectedPeerId != null,
                            onClick = {
                                if (!peer.isConnecting && peer.isAvailable && !peer.isBusy && uiState.selectedPeerId == null) {
                                    haptics.performSelection()
                                    onSelectPeer(peer.id)
                                }
                            }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        ScanQrActionCard(
                            onClick = onScanQrClicked
                        )
                    }
                }
            }

            is DiscoveryUiState.TimedOut -> {
                item {
                    Card(
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Discovery timed out. No devices found." }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.SearchOff,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "No devices found",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Ensure the receiving device has Glide open and 'Receive files' active on the same local network or nearby.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onBack,
                                    shape = PrimaryButtonShape,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .semantics {
                                            role = Role.Button
                                            contentDescription = "Cancel discovery"
                                        }
                                ) {
                                    Text("Cancel")
                                }
                                Button(
                                    onClick = onRetry,
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
                                            contentDescription = "Retry discovering nearby devices"
                                        }
                                ) {
                                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }
            }

            is DiscoveryUiState.Blocked -> {
                item {
                    DiscoveryBlockedCard(
                        reason = uiState.reason,
                        onRetry = onRetry,
                        onOpenSettings = {
                            val intent = when (uiState.reason) {
                                is BlockedReason.BluetoothDisabled -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                                is BlockedReason.WifiDisabled, is BlockedReason.NoLocalNetwork -> Intent(Settings.ACTION_WIFI_SETTINGS)
                                is BlockedReason.VpnActive -> Intent(Settings.ACTION_VPN_SETTINGS)
                                else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.fromParts("package", context.packageName, null)
                                }
                            }
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                } catch (_: Exception) {
                                    // Safe fallback
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * Restrained 2.4s low-amplitude tonal breathing indicator for active discovery.
 * Strictly adheres to GLIDE_DESIGN_SYSTEM.md:
 * "Slow 2.4s low-amplitude tonal breathing only while active; no radar implying measured distance."
 */
@Composable
fun DiscoveryBreathingIndicator(modifier: Modifier = Modifier) {
    val alpha by dev.glide.app.ui.motion.rememberTonalBreathingAlpha(
        initialValue = 0.55f,
        targetValue = 1.0f,
        isActive = true
    )

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = alpha),
        modifier = modifier
            .semantics { contentDescription = "Discovery scanning active" }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Text(
                text = "Scanning",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/**
 * Accessible discovered peer card with stable keys, device icon, availability status,
 * and prominent selected styling:
 * "Selected peer uses primary container +2dp elevation +outline and selected semantics"
 */
@Composable
fun DiscoveredPeerCard(
    peer: DiscoveredPeer,
    hasActiveSelection: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isInteractive = peer.isAvailable && !peer.isBusy && (!hasActiveSelection || peer.isSelected)
    val containerColor = when {
        peer.isSelected -> MaterialTheme.colorScheme.primaryContainer
        peer.isBusy -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val contentColor = when {
        peer.isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
        peer.isBusy -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.onSurface
    }

    val borderStroke = if (peer.isSelected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else null

    val elevation = if (peer.isSelected) 2.dp else 0.dp

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = borderStroke,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .glideHoverHand()
            .clickable(
                enabled = isInteractive,
                onClick = onClick,
                onClickLabel = if (isInteractive) "Connect to ${peer.displayName.isolateBidi()}" else null,
                role = Role.Button
            )
            .semantics {
                role = Role.Button
                selected = peer.isSelected
                contentDescription = buildString {
                    append("Device: ")
                    append(peer.displayName.isolateBidi())
                    append(", type: ")
                    append(peer.deviceType.name)
                    append(", route: ")
                    append(peer.routeSubtitle)
                    append(", status: ")
                    when {
                        peer.isConnecting -> append("Connecting")
                        peer.isSelected -> append("Selected")
                        peer.isBusy -> append("Busy with another transfer")
                        peer.isAvailable -> append("Available for sharing")
                        else -> append("Unavailable")
                    }
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Device Type Icon Container
            Surface(
                shape = CircleShape,
                color = if (peer.isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val icon = when (peer.deviceType) {
                        DeviceType.Phone -> Icons.Default.PhoneAndroid
                        DeviceType.Tablet -> Icons.Default.TabletAndroid
                        DeviceType.Computer -> Icons.Default.Computer
                        DeviceType.Unknown -> Icons.Default.Devices
                    }
                    val tint = if (peer.isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Device Name & Status
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = peer.displayName.isolateBidi(),
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        peer.isConnecting -> "Connecting..."
                        peer.isSelected -> "Connecting..."
                        peer.isBusy -> "${peer.routeSubtitle} • Busy with another transfer"
                        peer.isAvailable -> "${peer.routeSubtitle} • Tap to connect"
                        else -> "${peer.routeSubtitle} • Unavailable"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (peer.isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Trailing Action / State Indicator
            when {
                peer.isConnecting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                peer.isSelected -> {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

/**
 * Action card displayed in the peer list providing quick access to offline QR scan or paste pairing (LAN-019).
 */
@Composable
fun ScanQrActionCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .glideHoverHand()
            .clickable(
                onClick = onClick,
                onClickLabel = "Scan QR code or paste invitation",
                role = Role.Button
            )
            .semantics {
                role = Role.Button
                contentDescription = "Scan QR code or paste invitation directly from receiver"
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.QrCode2,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Text(
                text = "Scan QR code or paste invite",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Explanatory card for blocked hardware radio and permission states with explicit action buttons.
 */
@Composable
fun DiscoveryBlockedCard(
    reason: BlockedReason,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val error = dev.glide.app.ui.failure.ActionableErrorMapper.fromBlockedReason(reason)
    val icon = when (reason) {
        is BlockedReason.BluetoothDisabled -> Icons.Default.BluetoothDisabled
        is BlockedReason.WifiDisabled, is BlockedReason.NoLocalNetwork -> Icons.Default.WifiOff
        is BlockedReason.PermissionsRequired, is BlockedReason.LanPermissionDenied -> Icons.Default.Security
        is BlockedReason.VpnActive, is BlockedReason.TransportError -> Icons.Default.Warning
    }

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${error.title}: ${error.userWording}" }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                text = error.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = error.userWording,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (error.troubleshootingHint != null) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = error.troubleshootingHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onOpenSettings,
                    shape = PrimaryButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = error.primaryActionLabel
                        }
                ) {
                    Text(error.primaryActionLabel)
                }

                if (error.secondaryActionLabel != null) {
                    OutlinedButton(
                        onClick = onRetry,
                        shape = PrimaryButtonShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = error.secondaryActionLabel
                            }
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(error.secondaryActionLabel)
                    }
                }
            }
        }
    }
}
