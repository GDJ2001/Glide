package dev.glide.app.ui.selection

import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.glide.app.ui.components.ActionTone
import dev.glide.app.ui.components.GlideCompactTopBar
import dev.glide.app.ui.components.GlideIconAction
import dev.glide.app.ui.components.ShortFileTypeFormatter
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.TransferBatch
import dev.glide.data.source.InstalledAppsRepository
import dev.glide.data.source.LocalMediaEntry
import dev.glide.data.source.LocalMediaKind
import dev.glide.data.source.LocalMediaRepository
import dev.glide.designsystem.PrimaryButtonShape
import dev.glide.designsystem.glideHoverHand
import dev.glide.designsystem.isolateBidi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val appIconCache = LruCache<String, ImageBitmap>(128)

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
    var category by rememberSaveable { mutableStateOf(SelectionCategory.Apps) }
    var showSystemApps by rememberSaveable { mutableStateOf(false) }
    var appSearch by rememberSaveable { mutableStateOf("") }
    val repository = remember(context.applicationContext) { InstalledAppsRepository(context) }
    val mediaRepository = remember(context.applicationContext) { LocalMediaRepository(context) }
    var apps by remember { mutableStateOf<List<InstalledAppsRepository.App>?>(null) }
    var recentMedia by remember { mutableStateOf<List<LocalMediaEntry>>(emptyList()) }
    var catalogError by remember { mutableStateOf(false) }

    LaunchedEffect(category) {
        when (category) {
            SelectionCategory.Apps -> {
                if (apps == null) {
                    catalogError = false
                    try {
                        apps = repository.list()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        catalogError = true
                    }
                }
            }
            SelectionCategory.Photos -> recentMedia = mediaRepository.listRecent(LocalMediaKind.PHOTO)
            SelectionCategory.Videos -> recentMedia = mediaRepository.listRecent(LocalMediaKind.VIDEO)
            SelectionCategory.Audio -> recentMedia = mediaRepository.listRecent(LocalMediaKind.AUDIO)
            SelectionCategory.Documents, SelectionCategory.Folders -> recentMedia = emptyList()
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

    Column(modifier.fillMaxSize()) {
        GlideCompactTopBar(
            "Select files to send",
            Modifier.padding(horizontal = 16.dp),
            onBack = { viewModel.clear(); onBack() }
        ) {
            if (batch?.items?.isNotEmpty() == true) {
                GlideIconAction(Icons.Default.DeleteSweep, "Clear all selected files", viewModel::clear, enabled = !busy)
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                PrimaryScrollableTabRow(selectedTabIndex = category.ordinal, edgePadding = 0.dp) {
                    SelectionCategory.entries.forEach { tab ->
                        Tab(
                            selected = tab == category,
                            onClick = { category = tab },
                            text = { Text(tab.label) },
                            icon = { Icon(tab.icon, contentDescription = null) }
                        )
                    }
                }
            }

            when (val progress = state) {
                is SelectionUiState.Validating -> item {
                    SelectionProgress(
                        if (progress.totalCount > 0) "Preparing ${progress.currentCount} of ${progress.totalCount}…" else "Checking folder…",
                        viewModel::cancelStaging
                    )
                }
                is SelectionUiState.StagingSource -> item {
                    SelectionProgress(
                        "Preparing ${progress.displayName.isolateBidi()} · ${formatFileSize(progress.bytesStaged)}",
                        viewModel::cancelStaging
                    )
                }
                is SelectionUiState.Hashing -> item { Text("Checking ${progress.displayName.isolateBidi()}…") }
                is SelectionUiState.Error -> item { Text(progress.message, color = MaterialTheme.colorScheme.error) }
                is SelectionUiState.Ready -> item {
                    Text(progress.displayName.isolateBidi(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                else -> Unit
            }
            batch?.notice?.let { notice -> item { Text(notice, color = MaterialTheme.colorScheme.error) } }
            if (batch?.items?.isNotEmpty() == true) {
                item { Text("Selected items", style = MaterialTheme.typography.titleMedium) }
                items(batch.items, key = { it.id.value }) { item ->
                    FileItemRow(item = item, onRemove = { viewModel.removeFile(item.id) })
                }
            }

            if (!busy && category == SelectionCategory.Apps) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = appSearch,
                            onValueChange = { appSearch = it },
                            label = { Text("Search apps") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (appSearch.isNotEmpty()) {
                                    GlideIconAction(Icons.Default.Close, "Clear app search", { appSearch = "" })
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !showSystemApps, onClick = { showSystemApps = false },
                                label = { Text("User apps") })
                            FilterChip(selected = showSystemApps, onClick = { showSystemApps = true },
                                label = { Text("System apps") })
                        }
                    }
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
                    val checked = app.packageName in stagedPackages
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glideHoverHand()
                            .clickable(role = Role.Checkbox) {
                                viewModel.toggleInstalledApp(app, repository)
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AppIconThumb(packageName = app.packageName)
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = app.label.isolateBidi(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            val sizeLabel = if (app.sizeBytes > 0L) " · ${formatFileSize(app.sizeBytes)}" else ""
                            val kindLabel = if (app.split) " · Split APKs" else ""
                            Text(
                                text = "v${app.versionName}$sizeLabel$kindLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { viewModel.toggleInstalledApp(app, repository) },
                            modifier = Modifier.semantics { contentDescription = app.label }
                        )
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
                    ) {
                        Icon(category.icon, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (category) {
                                SelectionCategory.Folders -> "Browse folder"
                                SelectionCategory.Photos, SelectionCategory.Videos, SelectionCategory.Audio ->
                                    "Browse ${category.label.lowercase()}"
                                else -> "Add ${category.label.lowercase()}"
                            }
                        )
                    }
                }
                if (recentMedia.isNotEmpty()) {
                    val selectedUris = batch?.items.orEmpty().map { it.handle.value }.toSet()
                    items(recentMedia, key = { "media:${it.uri}" }) { media ->
                        val isSelected = media.uri.toString() in selectedUris
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .glideHoverHand()
                                .clickable(role = Role.Checkbox) {
                                    viewModel.toggleMediaEntry(media.uri, media.displayName, media.sizeBytes, media.mimeType, context)
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(category.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(media.displayName.isolateBidi(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${formatFileSize(media.sizeBytes)} · ${ShortFileTypeFormatter.format(media.displayName, media.mimeType)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    viewModel.toggleMediaEntry(media.uri, media.displayName, media.sizeBytes, media.mimeType, context)
                                }
                            )
                        }
                    }
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
                        val limitMessage = when {
                            batch.hasUnavailableItems -> "Remove unavailable files to proceed."
                            batch.isLimitExceeded -> "A batch can contain up to 1,000 files."
                            batch.isTotalBytesExceeded -> "Remove files to reduce the batch size."
                            else -> null
                        }
                        limitMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    Button(
                        enabled = !busy && (ready != null || batch?.canProceed == true),
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
                    ) { Text("Continue (${if (ready != null) 1 else batch?.itemCount ?: 0})") }
                }
            }
        }
    }
}

private val SelectionCategory.icon
    get() = when (this) {
        SelectionCategory.Photos -> Icons.Default.Image
        SelectionCategory.Videos -> Icons.Default.VideoFile
        SelectionCategory.Audio -> Icons.Default.AudioFile
        SelectionCategory.Documents -> Icons.Default.Description
        SelectionCategory.Folders -> Icons.Default.FolderOpen
        SelectionCategory.Apps -> Icons.Default.Android
    }

@Composable
private fun SelectionProgress(message: String, onCancel: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(message, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            GlideIconAction(Icons.Default.Close, "Cancel staging", onCancel, tone = ActionTone.Destructive)
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

            GlideIconAction(
                Icons.Default.Close,
                "Remove ${item.displayName.isolateBidi()} from batch",
                onRemove,
                tone = ActionTone.Destructive,
                tooltip = "Remove file"
            )
        }
    }
}

@Composable
private fun AppIconThumb(packageName: String) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(appIconCache.get(packageName), packageName) {
        if (value != null) return@produceState
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                drawable.toBitmap(width = 72, height = 72).asImageBitmap()
            }.getOrNull()
        }
        if (loaded != null) {
            appIconCache.put(packageName, loaded)
            value = loaded
        }
    }
    val img = bitmap
    if (img != null) {
        Image(bitmap = img, contentDescription = null, modifier = Modifier.size(36.dp))
    } else {
        Icon(
            imageVector = Icons.Default.Android,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp)
        )
    }
}
