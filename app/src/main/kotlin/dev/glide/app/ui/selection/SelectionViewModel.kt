package dev.glide.app.ui.selection

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.TransferBatch
import dev.glide.core.ports.SourceAccess
import dev.glide.core.ports.SourceAccessException
import dev.glide.data.source.AndroidContentResolverWrapper
import dev.glide.data.source.AndroidSourceAccess
import dev.glide.data.source.BoundedSourceStager
import dev.glide.data.source.SafTreeCrawler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * ViewModel governing source selection, multi-file batch aggregation, URI grant validation,
 * and preparation store handoff across configuration changes and Share Sheet ingress (LAN-021).
 */
class SelectionViewModel(
    private var customSourceAccess: SourceAccess? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow<SelectionUiState>(SelectionUiState.Idle)
    val uiState: StateFlow<SelectionUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null
    private var cachedSourceAccess: SourceAccess? = customSourceAccess

    /**
     * Obtains or creates the [SourceAccess] instance using the application context.
     */
    fun getOrCreateSourceAccess(context: Context): SourceAccess {
        return cachedSourceAccess ?: synchronized(this) {
            cachedSourceAccess ?: run {
                val appContext = context.applicationContext
                val stagingDir = File(appContext.cacheDir, "staging_sources")
                val resolverWrapper = AndroidContentResolverWrapper(appContext.contentResolver)
                val stager = BoundedSourceStager(
                    stagingDir = stagingDir,
                    resolverWrapper = resolverWrapper
                )
                val access = AndroidSourceAccess(
                    resolverWrapper = resolverWrapper,
                    ioDispatcher = Dispatchers.IO,
                    stager = stager
                )
                cachedSourceAccess = access
                access
            }
        }
    }

    /**
     * Ingests and validates a list of content URIs (from the system picker or Share Sheet).
     * Validates readability, metadata, size limits, and retains the full batch.
     */
    fun onUrisSelected(uris: List<Uri>, context: Context) {
        if (uris.isEmpty()) return
        val sourceAccess = getOrCreateSourceAccess(context)
        onUriStringsSelected(uris.map { it.toString() }, sourceAccess)
    }

    /**
     * Ingests a directory tree URI selected via ActivityResultContracts.OpenDocumentTree().
     * Traverses the tree recursively using [SafTreeCrawler] with loop detection and complexity limits.
     */
    fun onTreeUriSelected(treeUri: Uri, context: Context) {
        val appContext = context.applicationContext
        try {
            appContext.contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Persistable permission may not be supported by all providers
        }
        val crawler = SafTreeCrawler(appContext.contentResolver)
        onTreeUriStringSelected(treeUri.toString(), crawler)
    }

    /**
     * Ingests a folder tree using the provided [SafTreeCrawler].
     */
    fun onTreeUriStringSelected(treeUriString: String, crawler: SafTreeCrawler) {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            val currentItems = (_uiState.value as? SelectionUiState.BatchReady)?.items ?: emptyList()
            val existingHandles = currentItems.map { it.handle.value }.toSet()

            _uiState.value = SelectionUiState.Validating(
                currentCount = 0,
                totalCount = 0,
                currentUriString = treeUriString
            )

            val crawledFiles = mutableListOf<SelectedFileItem>()
            try {
                crawler.crawl(treeUriString).collect { item ->
                    if (!existingHandles.contains(item.documentUriString)) {
                        crawledFiles.add(
                            SelectedFileItem(
                                id = ItemId(UUID.randomUUID().toString()),
                                handle = SourceHandle(item.documentUriString),
                                displayName = item.displayName,
                                sizeBytes = item.sizeBytes,
                                mimeType = item.mimeType,
                                isAvailable = true,
                                relativePath = item.relativePath
                            )
                        )
                        _uiState.value = SelectionUiState.Validating(
                            currentCount = crawledFiles.size,
                            totalCount = crawledFiles.size,
                            currentUriString = item.displayName
                        )
                    }
                }

                if (crawledFiles.isEmpty()) {
                    if (currentItems.isEmpty()) {
                        _uiState.value = SelectionUiState.Idle
                    } else {
                        _uiState.value = SelectionUiState.BatchReady(items = currentItems)
                    }
                    return@launch
                }

                val totalPlanned = currentItems.size + crawledFiles.size
                if (totalPlanned > ProtocolLimits.MAX_ITEMS_PER_BATCH) {
                    _uiState.value = SelectionUiState.Error(
                        message = "Batch limit exceeded: Maximum ${ProtocolLimits.MAX_ITEMS_PER_BATCH} files allowed (folder contained ${crawledFiles.size} files)",
                        isRecoverable = true
                    )
                    return@launch
                }

                _uiState.value = SelectionUiState.BatchReady(items = currentItems + crawledFiles)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.value = SelectionUiState.Error(
                    message = e.message ?: "Failed to traverse selected folder",
                    isRecoverable = true
                )
            }
        }
    }

    /**
     * Pure URI string batch validation using the supplied [SourceAccess] port.
     */
    fun onUriStringsSelected(uriStrings: List<String>, sourceAccess: SourceAccess) {
        if (uriStrings.isEmpty()) return

        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            val currentItems = (_uiState.value as? SelectionUiState.BatchReady)?.items ?: emptyList()
            val existingUris = currentItems.map { it.handle.value }.toSet()

            // Deduplicate incoming URIs against already selected files
            val distinctNewUris = uriStrings
                .distinct()
                .filterNot { existingUris.contains(it) }

            if (distinctNewUris.isEmpty()) {
                // All incoming files were already present; retain existing batch
                return@launch
            }

            val totalPlannedCount = currentItems.size + distinctNewUris.size
            if (totalPlannedCount > ProtocolLimits.MAX_ITEMS_PER_BATCH) {
                _uiState.value = SelectionUiState.Error(
                    message = "Batch limit exceeded: Maximum ${ProtocolLimits.MAX_ITEMS_PER_BATCH} files allowed (selected: $totalPlannedCount)",
                    isRecoverable = true
                )
                return@launch
            }

            _uiState.value = SelectionUiState.Validating(
                currentCount = 0,
                totalCount = distinctNewUris.size
            )

            val validatedNewItems = mutableListOf<SelectedFileItem>()

            for ((index, uriStr) in distinctNewUris.withIndex()) {
                _uiState.value = SelectionUiState.Validating(
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

                    val metadata = if (sourceAccess is AndroidSourceAccess) {
                        sourceAccess.validateSource(handle) { bytesStaged ->
                            val displayName = uriStr.substringAfterLast('/').substringBefore('?').ifBlank { "Cloud file" }
                            _uiState.value = SelectionUiState.StagingSource(
                                displayName = displayName,
                                bytesStaged = bytesStaged,
                                isCloudProvider = isCloud
                            )
                        }
                    } else {
                        sourceAccess.validateSource(handle)
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
                            errorMessage = "Access denied or permission revoked: ${e.message ?: "permission missing"}"
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
            _uiState.value = SelectionUiState.BatchReady(items = combinedList)
        }
    }

    /**
     * Removes an individual item from the active batch.
     */
    fun removeFile(itemId: ItemId) {
        val currentBatch = _uiState.value as? SelectionUiState.BatchReady ?: return
        val remaining = currentBatch.items.filterNot { it.id == itemId }
        _uiState.value = if (remaining.isEmpty()) {
            SelectionUiState.Idle
        } else {
            SelectionUiState.BatchReady(items = remaining)
        }
    }

    /**
     * Validates the active batch, stores it in [SelectedTransferStore], and prepares it for transmission.
     *
     * @return Domain [TransferBatch] and complete [ItemId] -> [SourceHandle] mapping, or null if cannot proceed.
     */
    fun prepareBatchForTransfer(): Pair<TransferBatch, Map<ItemId, SourceHandle>>? {
        val batchReady = _uiState.value as? SelectionUiState.BatchReady ?: return null
        if (!batchReady.canProceed) return null

        val batch = batchReady.toTransferBatch()
        val sourceMap = batchReady.validItems.associate { it.id to it.handle }
        SelectedTransferStore.store(batch, sourceMap)
        return batch to sourceMap
    }

    /**
     * Resets the selection state to Idle.
     */
    fun clear() {
        activeJob?.cancel()
        (cachedSourceAccess as? AndroidSourceAccess)?.cleanupAllStaged()
        _uiState.value = SelectionUiState.Idle
    }

    /**
     * Cancels active staging or validation and transitions to Cancelled state.
     */
    fun cancelStaging() {
        activeJob?.cancel()
        (cachedSourceAccess as? AndroidSourceAccess)?.cleanupAllStaged()
        _uiState.value = SelectionUiState.Cancelled
    }

    /**
     * Test hook to inject UI state directly.
     */
    fun setUiStateForTesting(state: SelectionUiState) {
        _uiState.value = state
    }

    /**
     * Test hook to set custom source access.
     */
    fun setSourceAccessForTesting(sourceAccess: SourceAccess) {
        this.cachedSourceAccess = sourceAccess
        this.customSourceAccess = sourceAccess
    }
}
