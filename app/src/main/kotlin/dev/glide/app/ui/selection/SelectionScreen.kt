package dev.glide.app.ui.selection

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.glide.core.domain.BatchId
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.TransferBatch
import dev.glide.core.ports.SourceAccessException
import dev.glide.data.source.AndroidContentResolverWrapper
import dev.glide.data.source.AndroidSourceAccess
import dev.glide.data.source.BoundedSourceStager
import dev.glide.designsystem.PrimaryButtonShape
import dev.glide.designsystem.glideHoverHand
import dev.glide.designsystem.isolateBidi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable
fun SelectionScreen(
    onBack: () -> Unit,
    onProceedToSend: (SourceHandle) -> Unit,
    modifier: Modifier = Modifier,
    onProceedToSendBatch: ((TransferBatch) -> Unit)? = null,
    initialState: SelectionUiState = SelectionUiState.Idle,
    initialUris: List<Uri> = emptyList()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var uiState by remember { mutableStateOf<SelectionUiState>(initialState) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    val sourceAccess = remember {
        val stagingDir = File(context.cacheDir, "staging_sources")
        val resolverWrapper = AndroidContentResolverWrapper(context.contentResolver)
        val stager = BoundedSourceStager(
            stagingDir = stagingDir,
            resolverWrapper = resolverWrapper
        )
        AndroidSourceAccess(
            resolverWrapper = resolverWrapper,
            ioDispatcher = Dispatchers.IO,
            stager = stager
        )
    }

    val onUrisSelected: (List<Uri>) -> Unit = { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            activeJob?.cancel()
            activeJob = scope.launch {
                val currentItems = (uiState as? SelectionUiState.BatchReady)?.items ?: emptyList()
                val existingUris = currentItems.map { it.handle.value }.toSet()

                // Deduplicate incoming URIs against already selected files
                val distinctNewUris = uris
                    .map { it.toString() }
                    .distinct()
                    .filterNot { existingUris.contains(it) }

                if (distinctNewUris.isEmpty()) {
                    // All selected files were duplicates; keep current items
                    return@launch
                }

                val totalPlannedCount = currentItems.size + distinctNewUris.size
                if (totalPlannedCount > ProtocolLimits.MAX_ITEMS_PER_BATCH) {
                    uiState = SelectionUiState.Error(
                        message = "Batch limit exceeded: Maximum ${ProtocolLimits.MAX_ITEMS_PER_BATCH} files allowed (selected: $totalPlannedCount)",
                        isRecoverable = true
                    )
                    return@launch
                }

                uiState = SelectionUiState.Validating(
                    currentCount = 0,
                    totalCount = distinctNewUris.size
                )

                val validatedNewItems = mutableListOf<SelectedFileItem>()

                for ((index, uriStr) in distinctNewUris.withIndex()) {
                    uiState = SelectionUiState.Validating(
                        currentCount = index + 1,
                        totalCount = distinctNewUris.size,
                        currentUriString = uriStr
                    )

                    val handle = SourceHandle(uriStr)
                    try {
                        val isCloud = uriStr.contains("com.google.android.apps.docs") ||
                            uriStr.contains("cloud") ||
                            uriStr.contains("nextcloud") ||
                            uriStr.contains("dropbox")
                        val metadata = sourceAccess.validateSource(handle) { bytesStaged ->
                            val displayName = uriStr.substringAfterLast('/').substringBefore('?').ifBlank { "Cloud file" }
                            uiState = SelectionUiState.StagingSource(
                                displayName = displayName,
                                bytesStaged = bytesStaged,
                                isCloudProvider = isCloud
                            )
                        }
                        validatedNewItems.add(
                            SelectedFileItem(
                                id = ItemId(UUID.randomUUID().toString()),
                                handle = handle,
                                displayName = metadata.name,
                                sizeBytes = metadata.sizeBytes,
                                mimeType = metadata.mimeType,
                                isAvailable = true
                            )
                        )
                    } catch (e: SourceAccessException) {
                        validatedNewItems.add(
                            SelectedFileItem(
                                id = ItemId(UUID.randomUUID().toString()),
                                handle = handle,
                                displayName = uriStr.substringAfterLast('/').substringBefore('?').ifBlank { "Unavailable file" },
                                sizeBytes = 0L,
                                mimeType = "application/octet-stream",
                                isAvailable = false,
                                errorMessage = e.failure.message
                            )
                        )
                    } catch (e: SecurityException) {
                        validatedNewItems.add(
                            SelectedFileItem(
                                id = ItemId(UUID.randomUUID().toString()),
                                handle = handle,
                                displayName = uriStr.substringAfterLast('/').substringBefore('?').ifBlank { "Unavailable file" },
                                sizeBytes = 0L,
                                mimeType = "application/octet-stream",
                                isAvailable = false,
                                errorMessage = "Access denied or permission revoked: ${e.message}"
                            )
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        validatedNewItems.add(
                            SelectedFileItem(
                                id = ItemId(UUID.randomUUID().toString()),
                                handle = handle,
                                displayName = uriStr.substringAfterLast('/').substringBefore('?').ifBlank { "Unavailable file" },
                                sizeBytes = 0L,
                                mimeType = "application/octet-stream",
                                isAvailable = false,
                                errorMessage = e.message ?: "Failed to read file"
                            )
                        )
                    }
                }

                val combinedList = currentItems + validatedNewItems
                uiState = SelectionUiState.BatchReady(items = combinedList)
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(initialUris) {
        if (initialUris.isNotEmpty() && uiState is SelectionUiState.Idle) {
            onUrisSelected(initialUris)
        }
    }

    // Photo picker contract (PickMultipleVisualMedia)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        onUrisSelected(uris)
    }

    // Multi-document picker contract (OpenMultipleDocuments)
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        onUrisSelected(uris)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Navigation Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Selection screen navigation header" },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    activeJob?.cancel()
                    sourceAccess.cleanupAllStaged()
                    onBack()
                },
                shape = PrimaryButtonShape,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Navigate back"
                    }
            ) {
                Text(text = "Back", style = MaterialTheme.typography.labelLarge)
            }

            if (uiState is SelectionUiState.BatchReady) {
                TextButton(
                    onClick = {
                        activeJob?.cancel()
                        sourceAccess.cleanupAllStaged()
                        uiState = SelectionUiState.Idle
                    },
                    modifier = Modifier.semantics {
                        role = Role.Button
                        contentDescription = "Clear all selected files"
                    }
                ) {
                    Text("Clear all", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // Section Title
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Select files to send",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Private peer-to-peer transfer without broad storage access",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // State Content Presentation
        when (val state = uiState) {
            is SelectionUiState.Idle, is SelectionUiState.Cancelled -> {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "File picker prompt card" }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (state is SelectionUiState.Cancelled) {
                                "Selection cancelled"
                            } else {
                                "Choose photos, videos, documents, or any files to transfer privately without broad storage access"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            onClick = {
                                photoPickerLauncher.launch(
                                    MediaPickerHelper.createVisualMediaRequest()
                                )
                            },
                            shape = PrimaryButtonShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .semantics {
                                    role = Role.Button
                                    contentDescription = "Choose photos and videos from system photo picker"
                                }
                        ) {
                            Text(
                                text = "Choose photos & videos",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                documentPickerLauncher.launch(MediaPickerHelper.getDocumentMimeTypes())
                            },
                            shape = PrimaryButtonShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .semantics {
                                    role = Role.Button
                                    contentDescription = "Choose files and documents from system picker"
                                }
                        ) {
                            Text(
                                text = "Choose files & documents",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            is SelectionUiState.Validating -> {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Validating selected files metadata" }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Column {
                            Text(
                                text = "Checking files (${state.currentCount}/${state.totalCount})...",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Verifying readability, size, and sanitizing names",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            is SelectionUiState.StagingSource -> {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Staging ${state.displayName}: ${formatFileSize(state.bytesStaged)} prepared"
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Column {
                                Text(
                                    text = "Preparing file: ${state.displayName}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Staged: ${formatFileSize(state.bytesStaged)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (state.isCloudProvider) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Downloading from cloud provider before transfer. This ensures your peer receives an exact, verified file offer.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                activeJob?.cancel()
                                sourceAccess.cleanupAllStaged()
                                uiState = SelectionUiState.Cancelled
                            },
                            shape = PrimaryButtonShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .semantics {
                                    role = Role.Button
                                    contentDescription = "Cancel file staging"
                                }
                        ) {
                            Text(
                                text = "Cancel staging",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            is SelectionUiState.BatchReady -> {
                // Batch Summary Card
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Batch summary: ${state.itemCount} files selected, total size ${formatFileSize(state.totalBytes)}"
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Batch Summary",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "${state.itemCount} / ${ProtocolLimits.MAX_ITEMS_PER_BATCH} max",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        Text(
                            text = "${state.itemCount} ${if (state.itemCount == 1) "file" else "files"} · ${formatFileSize(state.totalBytes)}",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )

                        Text(
                            text = "All-or-nothing transfer: the receiver inspects and accepts the complete batch.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )

                        if (state.hasUnavailableItems) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Some files are unavailable or unreadable. Please remove them before proceeding.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }

                        if (state.isLimitExceeded) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Batch limit of ${ProtocolLimits.MAX_ITEMS_PER_BATCH} files exceeded. Please remove files to continue.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }

                // Virtualized Item List with LazyColumn (efficient with 1000 items)
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(
                        items = state.items,
                        key = { it.handle.value }
                    ) { item ->
                        FileItemRow(
                            item = item,
                            onRemove = {
                                val remaining = state.items.filterNot { it.id == item.id }
                                uiState = if (remaining.isEmpty()) {
                                    SelectionUiState.Idle
                                } else {
                                    SelectionUiState.BatchReady(items = remaining)
                                }
                            }
                        )
                    }
                }

                // Action Bar
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (state.canProceed) {
                                val batch = state.toTransferBatch()
                                val sourceMap = state.validItems.associate { it.id to it.handle }
                                SelectedTransferStore.store(batch, sourceMap)
                                if (onProceedToSendBatch != null) {
                                    onProceedToSendBatch(batch)
                                } else {
                                    // Fallback to legacy single handle callback
                                    val firstHandle = state.validItems.firstOrNull()?.handle
                                        ?: SourceHandle("glide://batch/${batch.id.value}")
                                    onProceedToSend(firstHandle)
                                }
                            }
                        },
                        enabled = state.canProceed,
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
                                contentDescription = "Proceed to send ${state.validItemCount} files"
                            }
                    ) {
                        Text(
                            text = if (state.canProceed) {
                                "Proceed to send (${state.validItemCount})"
                            } else if (state.hasUnavailableItems) {
                                "Remove unavailable files to proceed"
                            } else if (state.isLimitExceeded) {
                                "Batch exceeds 1,000 file limit"
                            } else {
                                "Proceed to send"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    MediaPickerHelper.createVisualMediaRequest()
                                )
                            },
                            shape = PrimaryButtonShape,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .semantics {
                                    role = Role.Button
                                    contentDescription = "Add photos and videos to batch"
                                }
                        ) {
                            Text(
                                text = "Add photos",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                documentPickerLauncher.launch(MediaPickerHelper.getDocumentMimeTypes())
                            },
                            shape = PrimaryButtonShape,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .semantics {
                                    role = Role.Button
                                    contentDescription = "Add files and documents to batch"
                                }
                        ) {
                            Text(
                                text = "Add files",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            is SelectionUiState.Ready -> {
                // Backward-compatible single file presentation
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = state.displayName.isolateBidi(),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Size: ${formatFileSize(state.sizeBytes)} (${state.sizeBytes} bytes)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "SHA-256: ${state.digest.value}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { onProceedToSend(state.handle) },
                    shape = PrimaryButtonShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                ) {
                    Text("Proceed to send", style = MaterialTheme.typography.titleMedium)
                }
            }

            is SelectionUiState.Error -> {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Selection error: ${state.message}" }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Selection Error",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )

                        Button(
                            onClick = { documentPickerLauncher.launch(MediaPickerHelper.getDocumentMimeTypes()) },
                            shape = PrimaryButtonShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Text(text = "Choose files again")
                        }
                    }
                }
            }

            is SelectionUiState.Hashing -> {
                // Backward-compatible hashing progress presentation
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Checking: ${state.displayName}",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileItemRow(
    item: SelectedFileItem,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = if (item.isAvailable) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            }
        ),
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = if (item.isAvailable) {
                    "File: ${item.displayName.isolateBidi()}, size: ${formatFileSize(item.sizeBytes)}"
                } else {
                    "Unavailable file: ${item.displayName.isolateBidi()}, error: ${item.errorMessage}"
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.displayName.isolateBidi(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (item.isAvailable) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = formatFileSize(item.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = item.mimeType,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                if (!item.isAvailable && item.errorMessage != null) {
                    Text(
                        text = item.errorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Accessible Remove button (touch target >= 48dp)
            TextButton(
                onClick = onRemove,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .glideHoverHand()
                    .semantics {
                        role = Role.Button
                        contentDescription = "Remove ${item.displayName.isolateBidi()} from batch"
                    }
            ) {
                Text(
                    text = "Remove",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
