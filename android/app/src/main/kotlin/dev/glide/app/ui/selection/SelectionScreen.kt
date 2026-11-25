package dev.glide.app.ui.selection

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.TransferBatch
import dev.glide.data.source.InstalledAppsRepository
import dev.glide.designsystem.PrimaryButtonShape
import dev.glide.designsystem.glideHoverHand
import dev.glide.designsystem.isolateBidi
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class SelectionCategory(val label: String) {
    Apps("Apps"), Photos("Photos"), Videos("Videos"), Audio("Audio"),
    Documents("Documents"), Folders("Folders")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionScreen(
    onBack: () -> Unit,
    onProceedToSend: (SourceHandle) -> Unit,
    modifier: Modifier = Modifier,
    onProceedToSendBatch: ((TransferBatch) -> Unit)? = null,
    initialState: SelectionUiState = SelectionUiState.Idle,
    initialUris: List<Uri> = emptyList(),
    viewModel: SelectionViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val batch = state as? SelectionUiState.BatchReady
    val busy = state is SelectionUiState.Validating ||
        state is SelectionUiState.StagingSource || state is SelectionUiState.Hashing
    var category by rememberSaveable { mutableStateOf(SelectionCategory.Documents) }
    var pendingApps by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var showSystemApps by rememberSaveable { mutableStateOf(false) }
    var appSearch by rememberSaveable { mutableStateOf("") }
    val repository = remember(context.applicationContext) { InstalledAppsRepository(context) }
    var apps by remember { mutableStateOf<List<InstalledAppsRepository.App>?>(null) }
    var catalogError by remember { mutableStateOf(false) }

    LaunchedEffect(category) {
        if (category == SelectionCategory.Apps) {
            catalogError = false
            try {
                apps = repository.list()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                catalogError = true
            }
        }
    }
    LaunchedEffect(initialState) {
        if (initialState !is SelectionUiState.Idle) viewModel.setUiStateForTesting(initialState)
    }
    LaunchedEffect(initialUris) {
        if (initialUris.isNotEmpty() && state == SelectionUiState.Idle) {
            viewModel.onUrisSelected(initialUris, context)
        }
    }
    val visualPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) {
        viewModel.onUrisSelected(it, context)
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        viewModel.onUrisSelected(it, context)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree?.let { viewModel.onTreeUriSelected(it, context) }
    }
    val stagedPackages = batch?.items.orEmpty().mapNotNull { it.installedPackageName }.toSet()

    Column(modifier.fillMaxSize().safeDrawingPadding()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { viewModel.clear(); onBack() }) { Text("Back") }
                    if (batch?.items?.isNotEmpty() == true) {
                        TextButton(onClick = viewModel::clear) { Text("Clear all") }
                    }
                }
                Text("Select files to send", style = MaterialTheme.typography.headlineLarge)
            }

            item {
                PrimaryScrollableTabRow(selectedTabIndex = category.ordinal, edgePadding = 0.dp) {
                    SelectionCategory.entries.forEach { tab ->
                        Tab(
                            selected = tab == category,
                            onClick = { category = tab },
                            text = { Text(tab.label) }
                        )
                    }
                }
            }

            if (!busy && category == SelectionCategory.Apps) {
                item {
                    OutlinedTextField(
                        value = appSearch,
                        onValueChange = { appSearch = it },
                        label = { Text("Search apps") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !showSystemApps, onClick = { showSystemApps = false },
                            label = { Text("User apps") })
                        FilterChip(selected = showSystemApps, onClick = { showSystemApps = true },
                            label = { Text("System apps") })
                    }
                    Text("Only app packages are shared.", style = MaterialTheme.typography.bodySmall)
                    val selected = apps.orEmpty().filter {
                        it.packageName in pendingApps && it.packageName !in stagedPackages
                    }
                    Button(
                        enabled = selected.isNotEmpty(),
                        onClick = {
                            viewModel.addInstalledApps(selected, repository)
                            pendingApps = arrayListOf()
                        }
                    ) { Text("Add selected apps") }
                }

                val visibleApps = apps.orEmpty().filter { app ->
                    app.system == showSystemApps &&
                        (app.label.contains(appSearch, ignoreCase = true) ||
                            app.packageName.contains(appSearch, ignoreCase = true))
                }
                if (visibleApps.isEmpty()) {
                    item {
                        Text(when {
                            catalogError -> "Couldn’t load apps. Reopen the Apps tab to retry."
                            apps == null -> "Loading apps…"
                            else -> "No matching apps."
                        })
                    }
                }
                items(visibleApps, key = { "app:${it.packageName}" }) { app ->
                    val alreadyAdded = app.packageName in stagedPackages
                    val checked = alreadyAdded || app.packageName in pendingApps
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = checked,
                            enabled = !alreadyAdded,
                            onCheckedChange = { selected ->
                                pendingApps = ArrayList(pendingApps.filterNot { it == app.packageName })
                                if (selected) pendingApps = ArrayList(pendingApps + app.packageName)
                            },
                            modifier = Modifier.semantics { contentDescription = app.label }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(app.label.isolateBidi(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (alreadyAdded) "Added" else if (app.split) "${app.versionName} · Split APKs" else app.versionName,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            } else if (!busy) {
                item {
                    OutlinedButton(
                        onClick = {
                            when (category) {
                                SelectionCategory.Photos -> visualPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                                SelectionCategory.Videos -> visualPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                )
                                SelectionCategory.Audio -> documentPicker.launch(MediaPickerHelper.getAudioMimeTypes())
                                SelectionCategory.Documents -> documentPicker.launch(MediaPickerHelper.getDocumentMimeTypes())
                                SelectionCategory.Folders -> folderPicker.launch(null)
                                SelectionCategory.Apps -> Unit
                            }
                        }
                    ) { Text(if (category == SelectionCategory.Folders) "Add folder" else "Add ${category.label.lowercase()}") }
                }
            }

            when (val progress = state) {
                is SelectionUiState.Validating -> item {
                    CircularProgressIndicator()
                    Text(if (progress.totalCount > 0) "Preparing ${progress.currentCount} of ${progress.totalCount}…" else "Checking folder…")
                    TextButton(onClick = viewModel::cancelStaging) { Text("Cancel") }
                }
                is SelectionUiState.StagingSource -> item {
                    CircularProgressIndicator()
                    Text("Preparing ${progress.displayName.isolateBidi()} · ${formatFileSize(progress.bytesStaged)}")
                    TextButton(onClick = viewModel::cancelStaging) { Text("Cancel") }
                }
                is SelectionUiState.Hashing -> item { Text("Checking ${progress.displayName.isolateBidi()}…") }
                is SelectionUiState.Error -> item { Text(progress.message, color = MaterialTheme.colorScheme.error) }
                is SelectionUiState.Ready -> item { Text(progress.displayName.isolateBidi()) }
                else -> Unit
            }
            batch?.notice?.let { notice -> item { Text(notice, color = MaterialTheme.colorScheme.error) } }
            if (batch?.items?.isNotEmpty() == true) {
                item { Text("Selected items", style = MaterialTheme.typography.titleMedium) }
                items(batch.items, key = { it.id.value }) { item ->
                    FileItemRow(item = item, onRemove = { viewModel.removeFile(item.id) })
                }
            }
        }

        val ready = state as? SelectionUiState.Ready
        if (batch?.items?.isNotEmpty() == true || ready != null) {
            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (batch != null) {
                        Text("${batch.itemCount} selected · ${formatFileSize(batch.totalBytes)}")
                        val limitMessage = when {
                            batch.hasUnavailableItems -> "Remove unavailable files to proceed."
                            batch.isLimitExceeded -> "A batch can contain up to 1,000 files."
                            batch.isTotalBytesExceeded -> "Remove files to reduce the batch size."
                            else -> null
                        }
                        limitMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    Button(
                        enabled = ready != null || batch?.canProceed == true,
                        onClick = {
                            val prepared = if (ready != null) viewModel.prepareReadyFileForTransfer()
                                else viewModel.prepareBatchForTransfer()
                            if (prepared != null) {
                                if (onProceedToSendBatch != null) onProceedToSendBatch(prepared.first)
                                else prepared.second.values.firstOrNull()?.let(onProceedToSend)
                            }
                        },
                        shape = PrimaryButtonShape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text("Proceed to send") }
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

                    val folderName = item.relativePath
                        ?.takeIf { it.contains('/') }
                        ?.substringBeforeLast('/')
                        ?.takeIf { it.isNotBlank() && !it.equals("Tree", ignoreCase = true) }

                    if (folderName != null) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = folderName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
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
