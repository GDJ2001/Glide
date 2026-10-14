package dev.glide.app.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.selected
import dev.glide.designsystem.isolateBidi
import dev.glide.core.domain.DeviceRole
import dev.glide.designsystem.AdaptiveLayoutTokens
import dev.glide.designsystem.LocalWindowSizeClass
import dev.glide.designsystem.WindowSizeClass
import dev.glide.designsystem.LocalDevicePosture
import dev.glide.designsystem.HingeOrientation
import dev.glide.designsystem.glideHoverHand
import dev.glide.designsystem.avoidHinge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onBack: () -> Unit,
    initialSessionId: String? = null,
    windowSizeClass: WindowSizeClass = LocalWindowSizeClass.current,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val postureInfo = LocalDevicePosture.current
    val isTwoPane = windowSizeClass.supportsTwoPaneLayout || postureInfo.isBook ||
            (postureInfo.hasActiveHinge && postureInfo.hinge?.orientation == HingeOrientation.Vertical)

    LaunchedEffect(initialSessionId) {
        if (initialSessionId != null) {
            viewModel.selectSession(initialSessionId, context)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                title = {
                    Text(
                        text = "Transfer history",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics {
                            role = Role.Button
                            contentDescription = "Navigate back to Home"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    if (uiState.sessions.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.requestClearHistory() },
                            modifier = Modifier.semantics {
                                role = Role.Button
                                contentDescription = "Clear transfer history"
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Filter Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                FilterChip(
                    selected = uiState.filter == HistoryFilter.ALL,
                    onClick = { viewModel.setFilter(HistoryFilter.ALL) },
                    label = { Text("All") },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .glideHoverHand()
                        .semantics {
                            contentDescription = "Filter all transfers"
                        }
                )
                FilterChip(
                    selected = uiState.filter == HistoryFilter.SENT,
                    onClick = { viewModel.setFilter(HistoryFilter.SENT) },
                    label = { Text("Sent") },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .glideHoverHand()
                        .semantics {
                            contentDescription = "Filter sent transfers"
                        }
                )
                FilterChip(
                    selected = uiState.filter == HistoryFilter.RECEIVED,
                    onClick = { viewModel.setFilter(HistoryFilter.RECEIVED) },
                    label = { Text("Received") },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .glideHoverHand()
                        .semantics {
                            contentDescription = "Filter received transfers"
                        }
                )
            }

            // Main Content Area
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (uiState.sessions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No transfers yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (isTwoPane) {
                // Two-pane layout for Expanded width (>= 840dp) or Book posture
                Row(modifier = Modifier.fillMaxSize()) {
                    // Left pane: List of sessions (width 360-420dp)
                    Column(
                        modifier = Modifier
                            .widthIn(
                                min = AdaptiveLayoutTokens.MinListPaneWidth,
                                max = AdaptiveLayoutTokens.MaxListPaneWidth
                            )
                            .fillMaxHeight()
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(
                                items = uiState.sessions,
                                key = { it.sessionId }
                            ) { session ->
                                val isSelected = session.sessionId == uiState.selectedSessionDetail?.session?.sessionId
                                HistorySessionCard(
                                    session = session,
                                    isSelected = isSelected,
                                    onClick = { viewModel.selectSession(session.sessionId, context) }
                                )
                            }
                        }
                    }

                    if (postureInfo.hasActiveHinge && postureInfo.hinge?.orientation == HingeOrientation.Vertical) {
                        Spacer(
                            modifier = Modifier
                                .width((postureInfo.hinge?.widthDp ?: 16.dp).coerceAtLeast(16.dp))
                                .fillMaxHeight()
                                .avoidHinge(postureInfo.hinge)
                        )
                    } else {
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }

                    // Detail pane: takes remaining space
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .semantics {
                                contentDescription = "Transfer detail pane"
                            }
                    ) {
                        val selectedDetail = uiState.selectedSessionDetail
                        if (selectedDetail != null) {
                            HistoryDetailContent(
                                detail = selectedDetail,
                                openFileFeedback = uiState.openFileFeedback,
                                exportStatus = uiState.exportStatus,
                                onDismiss = { viewModel.dismissSessionDetail() },
                                onOpenFile = { item -> viewModel.openItemFile(context, item) },
                                onExportFile = { item -> viewModel.exportItemFile(context, item) },
                                onShareFile = { item -> viewModel.shareItemFile(context, item) },
                                onDeleteSession = { viewModel.deleteSession(selectedDetail.session.sessionId) },
                                showCloseButton = false,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Select a transfer to view details",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else {
                // Single-pane list (Compact / Medium): dialog opened on tap
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = uiState.sessions,
                        key = { it.sessionId }
                    ) { session ->
                        HistorySessionCard(
                            session = session,
                            onClick = { viewModel.selectSession(session.sessionId, context) }
                        )
                    }
                }
            }
        }
    }

    // Clear History Confirmation Dialog
    if (uiState.isClearHistoryDialogOpen) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissClearHistoryDialog() },
            title = {
                Text(
                    text = "Clear transfer history?",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Text(
                    text = "This will remove all transfer history records. Files received and saved in Glide will not be deleted.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmClearHistory() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.semantics {
                        role = Role.Button
                        contentDescription = "Confirm clearing history"
                    }
                ) {
                    Text("Clear history")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.dismissClearHistoryDialog() },
                    modifier = Modifier.semantics {
                        role = Role.Button
                        contentDescription = "Cancel clearing history"
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Detail Dialog -- only shown in single-pane (Compact / Medium) mode
    if (!isTwoPane) {
        val selectedDetail = uiState.selectedSessionDetail
        if (selectedDetail != null) {
            HistoryDetailDialog(
                detail = selectedDetail,
                openFileFeedback = uiState.openFileFeedback,
                exportStatus = uiState.exportStatus,
                onDismiss = { viewModel.dismissSessionDetail() },
                onOpenFile = { item -> viewModel.openItemFile(context, item) },
                onExportFile = { item -> viewModel.exportItemFile(context, item) },
                onShareFile = { item -> viewModel.shareItemFile(context, item) },
                onDeleteSession = { viewModel.deleteSession(selectedDetail.session.sessionId) }
            )
        }
    }
}

@Composable
fun HistorySessionCard(
    session: HistorySessionItem,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            }
        ),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier
            .fillMaxWidth()
            .glideHoverHand()
            .clickable(
                onClick = onClick,
                onClickLabel = "View transfer details with ${session.peerLabel.isolateBidi()}"
            )
            .semantics {
                role = Role.Button
                selected = isSelected
                contentDescription = "Transfer with ${session.peerLabel.isolateBidi()}, ${if (session.role == DeviceRole.Receiver) "received" else "sent"}, ${session.outcome ?: "in progress"}${if (isSelected) ", selected" else ""}"
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Direction icon
            val (icon, tint) = if (session.role == DeviceRole.Receiver) {
                Icons.AutoMirrored.Filled.CallReceived to MaterialTheme.colorScheme.primary
            } else {
                Icons.AutoMirrored.Filled.CallMade to MaterialTheme.colorScheme.secondary
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = session.peerLabel.isolateBidi(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusBadge(outcome = session.outcome)
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = formatHistoryTimestamp(session.startedAtMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(2.dp))

                val fileText = if (session.itemCount == 1) "1 file" else "${session.itemCount} files"
                Text(
                    text = "$fileText · ${formatByteSize(session.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
