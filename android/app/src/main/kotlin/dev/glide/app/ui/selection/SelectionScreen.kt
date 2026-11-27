package dev.glide.app.ui.selection

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Work
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.glide.app.permission.PermissionPolicy
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
import dev.glide.data.source.QuickSelectionPrefsStore
import dev.glide.designsystem.PrimaryButtonShape
import dev.glide.designsystem.glideHoverHand
import dev.glide.designsystem.isolateBidi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val appIconCache = LruCache<String, ImageBitmap>(128)
private val mediaThumbCache = LruCache<String, ImageBitmap>(96)

private enum class SelectionCategory(val label: String) {
    Recent("Recent"),
    Photos("Photos"),
    Videos("Videos"),
    Audio("Audio"),
    Documents("Documents"),
    Work("Work"),
    Apps("Apps"),
    Folders("Folders")
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
    var category by rememberSaveable { mutableStateOf(SelectionCategory.Recent) }
    var showSystemApps by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var quickFilter by rememberSaveable { mutableStateOf("All") }
    var mediaRefreshTick by remember { mutableIntStateOf(0) }
    var isLoadingCategory by remember { mutableStateOf(false) }

    val repository = remember(context.applicationContext) { InstalledAppsRepository(context) }
    val mediaRepository = remember(context.applicationContext) { LocalMediaRepository(context) }
    val quickPrefs = remember(context.applicationContext) { QuickSelectionPrefsStore(context) }
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf<List<InstalledAppsRepository.App>?>(null) }
    var recentMedia by remember { mutableStateOf<List<LocalMediaEntry>>(emptyList()) }
    var pinnedFolders by remember { mutableStateOf<List<String>>(emptyList()) }
    var catalogError by remember { mutableStateOf(false) }

    BackHandler {
        viewModel.clear()
        onBack()
    }

    val isPermGranted: (String) -> Boolean = { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    val hasVisualPreviewAccess = PermissionPolicy.hasMediaPreviewAccess(
        includeAudio = false,
        isGranted = isPermGranted
    )
    val hasAudioPreviewAccess = PermissionPolicy.hasMediaPreviewAccess(
        includeAudio = true,
        isGranted = isPermGranted
    )

    LaunchedEffect(category) {
        quickFilter = "All"
    }

    LaunchedEffect(category, mediaRefreshTick) {
        pinnedFolders = quickPrefs.getPinnedFolders()
        when (category) {
            SelectionCategory.Apps -> {
                if (apps == null) {
                    catalogError = false
                    isLoadingCategory = true
                    try {
                        apps = repository.list()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        catalogError = true
                    } finally {
                        isLoadingCategory = false
                    }
                }
            }
            SelectionCategory.Recent -> {
                isLoadingCategory = true
                recentMedia = mediaRepository.listRecent(LocalMediaKind.RECENT)
                isLoadingCategory = false
            }
            SelectionCategory.Photos -> {
                isLoadingCategory = true
                recentMedia = mediaRepository.listRecent(LocalMediaKind.PHOTO)
                isLoadingCategory = false
            }
            SelectionCategory.Videos -> {
                isLoadingCategory = true
                recentMedia = mediaRepository.listRecent(LocalMediaKind.VIDEO)
                isLoadingCategory = false
            }
            SelectionCategory.Audio -> {
                isLoadingCategory = true
                recentMedia = mediaRepository.listRecent(LocalMediaKind.AUDIO)
                isLoadingCategory = false
            }
            SelectionCategory.Documents -> {
                isLoadingCategory = true
                recentMedia = mediaRepository.listRecent(LocalMediaKind.DOCUMENT)
                isLoadingCategory = false
            }
            SelectionCategory.Work -> {
                isLoadingCategory = true
                recentMedia = mediaRepository.listRecent(LocalMediaKind.WORK)
                isLoadingCategory = false
            }
            SelectionCategory.Folders -> {
                isLoadingCategory = true
                recentMedia = mediaRepository.listRecent(LocalMediaKind.DOCUMENT, limit = 120)
                isLoadingCategory = false
            }
        }
    }
    LaunchedEffect(initialState) {
        if (initialState !is SelectionUiState.Idle) viewModel.setUiStateForTesting(initialState)
    }
    var lastIngestedUrisKey by rememberSaveable { mutableStateOf("") }
    val initialUrisKey = remember(initialUris) { initialUris.joinToString("|") { it.toString() } }
    LaunchedEffect(initialUrisKey) {
        if (initialUris.isNotEmpty() && initialUrisKey != lastIngestedUrisKey) {
            lastIngestedUrisKey = initialUrisKey
            viewModel.onUrisSelected(initialUris, context)
            mediaRefreshTick++
        }
    }
    val visualPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) {
        if (it.isNotEmpty()) {
            viewModel.onUrisSelected(it, context)
            mediaRefreshTick++
        }
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) {
            viewModel.onUrisSelected(it, context)
            mediaRefreshTick++
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree?.let { viewModel.onTreeUriSelected(it, context) }
    }
    val pinFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            scope.launch {
                quickPrefs.addPinnedFolder(tree.toString())
                pinnedFolders = quickPrefs.getPinnedFolders()
                mediaRefreshTick++
            }
        }
    }
    val visualPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        mediaRefreshTick++
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        mediaRefreshTick++
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
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
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
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("Search apps") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    GlideIconAction(Icons.Default.Close, "Clear search", { searchQuery = "" })
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !showSystemApps,
                                onClick = { showSystemApps = false },
                                label = { Text("User apps") }
                            )
                            FilterChip(
                                selected = showSystemApps,
                                onClick = { showSystemApps = true },
                                label = { Text("System apps") }
                            )
                        }
                    }
                }

                val visibleApps = apps.orEmpty().filter { app ->
                    app.system == showSystemApps &&
                        (app.label.contains(searchQuery, ignoreCase = true) ||
                            app.packageName.contains(searchQuery, ignoreCase = true))
                }
                if (visibleApps.isEmpty()) {
                    item {
                        Text(
                            when {
                                catalogError -> "Couldn’t load apps. Reopen the Apps tab to retry."
                                apps == null -> "Loading apps…"
                                else -> "No matching apps."
                            }
                        )
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
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
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
                                        SelectionCategory.Documents, SelectionCategory.Work, SelectionCategory.Recent ->
                                            documentPicker.launch(arrayOf("*/*"))
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
                                        SelectionCategory.Work -> "Add work files"
                                        SelectionCategory.Recent -> "Browse files"
                                        SelectionCategory.Documents -> "Browse documents"
                                        SelectionCategory.Apps -> "Browse"
                                    }
                                )
                            }
                            if (category == SelectionCategory.Documents ||
                                category == SelectionCategory.Work ||
                                category == SelectionCategory.Folders
                            ) {
                                OutlinedButton(onClick = { pinFolderPicker.launch(null) }) {
                                    Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Pin folder")
                                }
                            }
                        }

                        if ((category == SelectionCategory.Photos ||
                                category == SelectionCategory.Videos ||
                                category == SelectionCategory.Recent) && !hasVisualPreviewAccess
                        ) {
                            QuickAccessBanner(
                                title = "Show instant photo & video previews",
                                subtitle = "Allow media access to pick recent camera shots and videos without opening the system browser.",
                                buttonLabel = "Allow access",
                                onAction = {
                                    visualPermissionLauncher.launch(
                                        PermissionPolicy.mediaPreviewPermissions(
                                            includeVisual = true,
                                            includeAudio = category == SelectionCategory.Recent
                                        ).toTypedArray()
                                    )
                                }
                            )
                        } else if (category == SelectionCategory.Audio && !hasAudioPreviewAccess) {
                            QuickAccessBanner(
                                title = "Show instant audio previews",
                                subtitle = "Allow audio access to browse recent music and recordings right here.",
                                buttonLabel = "Allow audio",
                                onAction = {
                                    audioPermissionLauncher.launch(
                                        PermissionPolicy.mediaPreviewPermissions(
                                            includeVisual = false,
                                            includeAudio = true
                                        ).toTypedArray()
                                    )
                                }
                            )
                        } else if ((category == SelectionCategory.Documents || category == SelectionCategory.Work) &&
                            pinnedFolders.isEmpty()
                        ) {
                            QuickAccessBanner(
                                title = "Quick-preview Download or Documents folders",
                                subtitle = "Pin your Work or Documents folder once for instant PDF & document previews.",
                                buttonLabel = "Pin folder",
                                onAction = { pinFolderPicker.launch(null) }
                            )
                        }

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("Search ${category.label.lowercase()}") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    GlideIconAction(Icons.Default.Close, "Clear search", { searchQuery = "" })
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        val chips = quickFiltersForCategory(category, recentMedia)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            chips.forEach { chip ->
                                FilterChip(
                                    selected = quickFilter == chip,
                                    onClick = { quickFilter = chip },
                                    label = { Text(chip) }
                                )
                            }
                        }
                    }
                }

                if (category == SelectionCategory.Folders && pinnedFolders.isNotEmpty()) {
                    item {
                        Text(
                            text = "Pinned quick folders",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    items(pinnedFolders, key = { "pin:$it" }) { folderUri ->
                        val folderLabel = remember(folderUri) {
                            Uri.parse(folderUri).lastPathSegment
                                ?.substringAfter(':', folderUri)
                                ?.substringAfterLast('/')
                                ?: "Pinned folder"
                        }
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(
                                    text = folderLabel.isolateBidi(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                GlideIconAction(
                                    Icons.Default.FolderOpen,
                                    "Send all files in $folderLabel",
                                    { viewModel.onTreeUriSelected(Uri.parse(folderUri), context) },
                                    tone = ActionTone.Primary,
                                    tooltip = "Select folder"
                                )
                                GlideIconAction(
                                    Icons.Default.Close,
                                    "Unpin $folderLabel",
                                    {
                                        scope.launch {
                                            quickPrefs.removePinnedFolder(folderUri)
                                            pinnedFolders = quickPrefs.getPinnedFolders()
                                            mediaRefreshTick++
                                        }
                                    },
                                    tone = ActionTone.Destructive,
                                    tooltip = "Unpin folder"
                                )
                            }
                        }
                    }
                }

                val filteredMedia = recentMedia.filter { entry ->
                    matchesQuickFilter(entry, category, quickFilter) &&
                        (searchQuery.isBlank() ||
                            entry.displayName.contains(searchQuery, ignoreCase = true) ||
                            (entry.bucketName?.contains(searchQuery, ignoreCase = true) == true))
                }

                if (isLoadingCategory && recentMedia.isEmpty()) {
                    item {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Loading ${category.label.lowercase()}…")
                        }
                    }
                } else if (filteredMedia.isEmpty()) {
                    item {
                        Text(
                            text = if (recentMedia.isEmpty()) {
                                "No ${category.label.lowercase()} previews yet. Allow access, pin a folder, or use Browse above."
                            } else {
                                "No files match \"$quickFilter\"."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val selectedUris = batch?.items.orEmpty().map { it.handle.value }.toSet()
                    when (category) {
                        SelectionCategory.Photos -> {
                            val rows = filteredMedia.chunked(3)
                            items(rows, key = { row -> "photo-row:" + row.joinToString("|") { it.uri.toString() } }) { rowEntries ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowEntries.forEach { media ->
                                        val isSelected = media.uri.toString() in selectedUris
                                        PhotoGridTile(
                                            entry = media,
                                            mediaRepository = mediaRepository,
                                            isSelected = isSelected,
                                            onClick = {
                                                viewModel.toggleMediaEntry(
                                                    media.uri,
                                                    media.displayName,
                                                    media.sizeBytes,
                                                    media.mimeType,
                                                    context
                                                )
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    repeat(3 - rowEntries.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        SelectionCategory.Videos -> {
                            val rows = filteredMedia.chunked(2)
                            items(rows, key = { row -> "video-row:" + row.joinToString("|") { it.uri.toString() } }) { rowEntries ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    rowEntries.forEach { media ->
                                        val isSelected = media.uri.toString() in selectedUris
                                        VideoGridTile(
                                            entry = media,
                                            mediaRepository = mediaRepository,
                                            isSelected = isSelected,
                                            onClick = {
                                                viewModel.toggleMediaEntry(
                                                    media.uri,
                                                    media.displayName,
                                                    media.sizeBytes,
                                                    media.mimeType,
                                                    context
                                                )
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (rowEntries.size == 1) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        else -> {
                            items(filteredMedia, key = { "media:${it.uri}" }) { media ->
                                val isSelected = media.uri.toString() in selectedUris
                                MediaListPreviewRow(
                                    entry = media,
                                    mediaRepository = mediaRepository,
                                    isSelected = isSelected,
                                    onToggle = {
                                        viewModel.toggleMediaEntry(
                                            media.uri,
                                            media.displayName,
                                            media.sizeBytes,
                                            media.mimeType,
                                            context
                                        )
                                    }
                                )
                            }
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
        SelectionCategory.Recent -> Icons.Default.History
        SelectionCategory.Photos -> Icons.Default.Image
        SelectionCategory.Videos -> Icons.Default.VideoFile
        SelectionCategory.Audio -> Icons.Default.AudioFile
        SelectionCategory.Documents -> Icons.Default.Description
        SelectionCategory.Work -> Icons.Default.Work
        SelectionCategory.Folders -> Icons.Default.FolderOpen
        SelectionCategory.Apps -> Icons.Default.Android
    }

private fun quickFiltersForCategory(
    category: SelectionCategory,
    entries: List<LocalMediaEntry>
): List<String> {
    val dynamicBuckets = entries.mapNotNull { it.bucketName?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()
        .take(3)
    return when (category) {
        SelectionCategory.Recent -> listOf("All", "Recently added", "Photos", "Videos", "PDFs", "Work files", "Audio")
        SelectionCategory.Photos -> (listOf("All", "Today", "Camera", "Screenshots") + dynamicBuckets).distinct()
        SelectionCategory.Videos -> (listOf("All", "Today", "Camera", "Screen recordings") + dynamicBuckets).distinct()
        SelectionCategory.Audio -> listOf("All", "Today", "Music", "Voice")
        SelectionCategory.Documents -> listOf("All", "PDFs", "Docs & Text", "Sheets & Slides", "Archives", "Today")
        SelectionCategory.Work -> listOf("All", "PDFs", "Docs", "Sheets", "Slides", "Archives & Code", "Today")
        SelectionCategory.Folders -> (listOf("All", "PDFs", "Images", "Work files") + dynamicBuckets).distinct()
        SelectionCategory.Apps -> listOf("User apps", "System apps")
    }
}

private fun matchesQuickFilter(
    entry: LocalMediaEntry,
    category: SelectionCategory,
    filter: String
): Boolean {
    if (filter == "All") return true
    val ext = entry.displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val mime = entry.mimeType.lowercase(Locale.ROOT)
    val bucket = entry.bucketName.orEmpty().lowercase(Locale.ROOT)
    val nowSec = System.currentTimeMillis() / 1000L
    val isToday = entry.dateModifiedSec > 0L && (nowSec - entry.dateModifiedSec) <= 86_400L
    val isRecentAdded = entry.sourceLabel == "Selected recently" || isToday

    return when (filter) {
        "Today" -> isToday
        "Recently added" -> isRecentAdded
        "Photos", "Images" -> mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "heic")
        "Videos" -> mime.startsWith("video/") || ext in setOf("mp4", "m4v", "mkv", "mov", "webm", "avi")
        "Audio", "Music" -> mime.startsWith("audio/") || ext in setOf("mp3", "m4a", "wav", "flac", "ogg", "opus", "aac")
        "Voice" -> (mime.startsWith("audio/") && (bucket.contains("record") || bucket.contains("voice") || bucket.contains("call")))
        "PDFs" -> ext == "pdf" || mime == "application/pdf"
        "Work files" -> ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "txt", "md", "rtf", "odt", "ods", "odp", "zip")
        "Docs & Text", "Docs" -> ext in setOf("doc", "docx", "txt", "md", "rtf", "odt", "srt", "vtt", "log") || mime.startsWith("text/")
        "Sheets & Slides" -> ext in setOf("xls", "xlsx", "csv", "ods", "ppt", "pptx", "odp")
        "Sheets" -> ext in setOf("xls", "xlsx", "csv", "ods")
        "Slides" -> ext in setOf("ppt", "pptx", "odp")
        "Archives", "Archives & Code" -> ext in setOf("zip", "7z", "rar", "tar", "gz", "json", "xml", "yaml", "yml")
        "Camera" -> bucket.contains("camera") || bucket.contains("dcim")
        "Screenshots" -> bucket.contains("screenshot")
        "Screen recordings" -> bucket.contains("screen") || bucket.contains("record")
        else -> {
            val f = filter.lowercase(Locale.ROOT)
            bucket.contains(f) || category == SelectionCategory.Apps
        }
    }
}

@Composable
private fun QuickAccessBanner(
    title: String,
    subtitle: String,
    buttonLabel: String,
    onAction: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            OutlinedButton(onClick = onAction) {
                Text(buttonLabel, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PhotoGridTile(
    entry: LocalMediaEntry,
    mediaRepository: LocalMediaRepository,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier
            .aspectRatio(1f)
            .glideHoverHand()
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics {
                contentDescription = "${entry.displayName}, ${if (isSelected) "selected" else "not selected"}"
            }
    ) {
        Box(Modifier.fillMaxSize()) {
            MediaThumbnail(
                entry = entry,
                mediaRepository = mediaRepository,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    modifier = Modifier
                        .size(22.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = if (isSelected) 0.9f else 0.35f),
                            CircleShape
                        )
                )
            }
        }
    }
}

@Composable
private fun VideoGridTile(
    entry: LocalMediaEntry,
    mediaRepository: LocalMediaRepository,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier
            .glideHoverHand()
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics {
                contentDescription = "${entry.displayName}, ${if (isSelected) "selected" else "not selected"}"
            }
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
            ) {
                MediaThumbnail(
                    entry = entry,
                    mediaRepository = mediaRepository,
                    modifier = Modifier.fillMaxSize()
                )
                if (entry.durationMs > 0L) {
                    Surface(
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                    ) {
                        Text(
                            text = formatDurationMs(entry.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = if (isSelected) 0.9f else 0.35f),
                            CircleShape
                        )
                )
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = entry.displayName.isolateBidi(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = formatFileSize(entry.sizeBytes),
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MediaListPreviewRow(
    entry: LocalMediaEntry,
    mediaRepository: LocalMediaRepository,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glideHoverHand()
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MediaThumbnail(
            entry = entry,
            mediaRepository = mediaRepository,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Column(Modifier.weight(1f)) {
            Text(entry.displayName.isolateBidi(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = buildString {
                append(formatFileSize(entry.sizeBytes))
                append(" · ")
                append(ShortFileTypeFormatter.format(entry.displayName, entry.mimeType))
                entry.bucketName?.takeIf { it.isNotBlank() }?.let {
                    append(" · ")
                    append(it)
                }
            }
            Text(
                text = detail,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() }
        )
    }
}

@Composable
private fun MediaThumbnail(
    entry: LocalMediaEntry,
    mediaRepository: LocalMediaRepository,
    modifier: Modifier = Modifier
) {
    val isVisual = entry.mimeType.startsWith("image/", ignoreCase = true) ||
        entry.mimeType.startsWith("video/", ignoreCase = true)
    if (isVisual) {
        val key = entry.uri.toString()
        val thumb by produceState<ImageBitmap?>(mediaThumbCache.get(key), key) {
            if (value != null) return@produceState
            val loaded = mediaRepository.loadThumbnail(entry)?.asImageBitmap()
            if (loaded != null) {
                mediaThumbCache.put(key, loaded)
                value = loaded
            }
        }
        val img = thumb
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier
            )
            return
        }
    }
    val fallbackIcon = when {
        entry.displayName.endsWith(".pdf", ignoreCase = true) || entry.mimeType == "application/pdf" ->
            Icons.Default.PictureAsPdf
        entry.mimeType.startsWith("image/", ignoreCase = true) -> Icons.Default.Image
        entry.mimeType.startsWith("video/", ignoreCase = true) -> Icons.Default.VideoFile
        entry.mimeType.startsWith("audio/", ignoreCase = true) -> Icons.Default.AudioFile
        else -> Icons.Default.Description
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = fallbackIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

private fun formatDurationMs(durationMs: Long): String {
    val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%d:%02d", mins, secs)
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
