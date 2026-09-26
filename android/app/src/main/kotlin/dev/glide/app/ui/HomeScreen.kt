package dev.glide.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.glide.app.R
import dev.glide.designsystem.PrimaryButtonShape
import dev.glide.designsystem.brandCyanAccent
import dev.glide.designsystem.avoidHinge
import dev.glide.designsystem.glideHoverHand
import androidx.compose.foundation.layout.widthIn

@Composable
fun HomeScreen(
    onNavigateToSend: () -> Unit,
    onNavigateToReceive: () -> Unit,
    onNavigateToHistory: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    activeSessionId: String? = null,
    onNavigateToTransfer: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val isDark = isSystemInDarkTheme()
    val logoRes = if (isDark) R.drawable.ic_glide_logo_inverse else R.drawable.ic_glide_logo_color
    val windowSizeClass = dev.glide.designsystem.LocalWindowSizeClass.current
    val postureInfo = dev.glide.designsystem.LocalDevicePosture.current
    val verticalSpacing = if (windowSizeClass.isCompactHeight) 12.dp else 24.dp

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = dev.glide.designsystem.AdaptiveLayoutTokens.MaxContentWidth)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    horizontal = windowSizeClass.pageHorizontalPadding,
                    vertical = if (windowSizeClass.isCompactHeight) 8.dp else 16.dp
                ),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing)
        ) {
        // App Identity Header with Settings Action
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
                .padding(vertical = 8.dp, horizontal = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Glide" },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = logoRes),
                    contentDescription = "Glide",
                    modifier = Modifier
                        .height(40.dp)
                        .padding(vertical = 4.dp)
                )

                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier
                        .size(48.dp)
                        .glideHoverHand()
                        .semantics {
                            role = Role.Button
                            contentDescription = "Open settings"
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Active Transfer Banner (if a transfer is currently active)
        if (activeSessionId != null) {
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Active transfer in progress. Tap to resume." }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Transfer in progress",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Button(
                        onClick = { onNavigateToTransfer(activeSessionId) },
                        shape = PrimaryButtonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Resume active transfer"
                            }
                    ) {
                        Text("Resume")
                    }
                }
            }
        }

        // Tabletop posture fold avoidance: ensure hinge is not occluding action buttons
        if (postureInfo.isTabletop && postureInfo.hasActiveHinge) {
            Spacer(modifier = Modifier.avoidHinge(postureInfo.hinge, additionalPadding = 8.dp))
        }

        // Action Buttons (Send & Receive >= 56dp height)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = onNavigateToSend,
                shape = PrimaryButtonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .glideHoverHand()
                    .semantics {
                        role = Role.Button
                        contentDescription = "Send files to a nearby device"
                    }
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Send files",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            OutlinedButton(
                onClick = onNavigateToReceive,
                shape = PrimaryButtonShape,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .glideHoverHand()
                    .semantics {
                        role = Role.Button
                        contentDescription = "Receive files from a nearby device"
                    }
            ) {
                Icon(imageVector = Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Receive files",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        // Recent Transfers Section
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent activity",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(
                    onClick = onNavigateToHistory,
                    modifier = Modifier.semantics {
                        role = Role.Button
                        contentDescription = "View all transfer history"
                    }
                ) {
                    Text("View all")
                }
            }

            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Recent activity card: No recent transfers"
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No recent transfers",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
}
