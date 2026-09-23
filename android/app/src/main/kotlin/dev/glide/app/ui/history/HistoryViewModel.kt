package dev.glide.app.ui.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.glide.app.ui.transfer.ExportUiStatus
import dev.glide.app.util.FileOpenResult
import dev.glide.app.util.FileOpener
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SessionId
import dev.glide.core.ports.HistoryStore
import dev.glide.data.export.AndroidFileExporter
import dev.glide.data.export.ExportResult
import dev.glide.data.export.FileExporter
import dev.glide.data.store.RoomHistoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * ViewModel managing transfer history queries, filtering, detail presentation, and retention clearing.
 * Conforms to GLIDE-024 specification.
 */
class HistoryViewModel(
    private val historyStore: HistoryStore,
    private val fileResolver: (Context?, String) -> File? = { context, key ->
        context?.let { FileOpener.resolveReceivedFile(it, key) }
    },
    private val fileOpener: (Context?, File, String, String) -> FileOpenResult = { context, file, name, mime ->
        context?.let { FileOpener.openFile(it, file, name, mime) } ?: FileOpenResult.FileNotFound
    },
    private val fileSharer: (Context?, File, String, String) -> FileOpenResult = { context, file, name, mime ->
        context?.let { dev.glide.app.util.FileSharing.shareFile(it, file, name, mime) } ?: FileOpenResult.FileNotFound
    },
    private val fileExporterFactory: (Context) -> FileExporter = { context ->
        AndroidFileExporter(context)
    }
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()
    private var isTestingOverride = false

    init {
        loadHistory()
    }

    fun setFilter(filter: HistoryFilter) {
        if (_uiState.value.filter == filter) return
        _uiState.value = _uiState.value.copy(filter = filter)
        loadHistory()
    }

    fun loadHistory() {
        if (isTestingOverride) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val roleFilter = when (_uiState.value.filter) {
                HistoryFilter.ALL -> null
                HistoryFilter.SENT -> DeviceRole.Sender
                HistoryFilter.RECEIVED -> DeviceRole.Receiver
            }
            val summaries = historyStore.getSessionsPaged(role = roleFilter, limit = 100, offset = 0)
            val sessionItems = summaries.map { summary ->
                HistorySessionItem(
                    sessionId = summary.sessionId.value,
                    role = summary.role,
                    peerLabel = summary.peerLabel,
                    startedAtMs = summary.startedAtMs,
                    endedAtMs = summary.endedAtMs,
                    outcome = summary.outcome,
                    failureCode = summary.failureCode,
                    itemCount = summary.itemCount,
                    totalBytes = summary.totalBytes
                )
            }
            if (!isTestingOverride) {
                _uiState.value = _uiState.value.copy(
                    sessions = sessionItems,
                    isLoading = false
                )
            }
        }
    }

    fun selectSession(sessionId: String, context: Context? = null) {
        viewModelScope.launch {
            val session = historyStore.getSession(SessionId(sessionId)) ?: return@launch
            val items = historyStore.getItemsForSession(SessionId(sessionId))

            val sessionItem = HistorySessionItem(
                sessionId = session.sessionId.value,
                role = session.role,
                peerLabel = session.peerLabel,
                startedAtMs = session.startedAtMs,
                endedAtMs = session.endedAtMs,
                outcome = session.outcome,
                failureCode = null,
                itemCount = items.size,
                totalBytes = items.sumOf { it.actualBytes ?: it.offeredBytes }
            )

            val itemDetails = items.map { item ->
                val receivedFileKey = if (session.role == DeviceRole.Receiver) {
                    (historyStore as? RoomHistoryStore)?.getReceivedFileKey(item.itemId)
                } else null

                val isFileAvailable = if (receivedFileKey != null) {
                    val file = fileResolver(context, receivedFileKey)
                    file != null && file.exists()
                } else false

                HistoryItemDetail(
                    itemId = item.itemId.value,
                    ordinal = item.ordinal,
                    displayName = item.displayName,
                    mimeType = item.mimeType,
                    offeredBytes = item.offeredBytes,
                    actualBytes = item.actualBytes,
                    outcome = item.outcome,
                    failureCode = null,
                    receivedFileKey = receivedFileKey,
                    isFileAvailable = isFileAvailable,
                    relativePath = item.relativePath
                )
            }

            _uiState.value = _uiState.value.copy(
                selectedSessionDetail = HistorySessionDetail(
                    session = sessionItem,
                    items = itemDetails
                ),
                openFileFeedback = null,
                exportStatus = ExportUiStatus.Idle
            )
        }
    }

    fun dismissSessionDetail() {
        _uiState.value = _uiState.value.copy(
            selectedSessionDetail = null,
            openFileFeedback = null,
            exportStatus = ExportUiStatus.Idle
        )
    }

    fun requestClearHistory() {
        _uiState.value = _uiState.value.copy(isClearHistoryDialogOpen = true)
    }

    fun dismissClearHistoryDialog() {
        _uiState.value = _uiState.value.copy(isClearHistoryDialogOpen = false)
    }

    fun confirmClearHistory() {
        viewModelScope.launch {
            historyStore.clearHistory()
            _uiState.value = _uiState.value.copy(
                isClearHistoryDialogOpen = false,
                selectedSessionDetail = null
            )
            loadHistory()
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            historyStore.deleteSession(SessionId(sessionId))
            _uiState.value = _uiState.value.copy(
                selectedSessionDetail = null
            )
            loadHistory()
        }
    }

    fun shareItemFile(context: Context?, item: HistoryItemDetail) {
        val key = item.receivedFileKey
        if (key == null) {
            _uiState.value = _uiState.value.copy(openFileFeedback = "File not found")
            return
        }

        val file = fileResolver(context, key)
        if (file == null || !file.exists()) {
            _uiState.value = _uiState.value.copy(openFileFeedback = "File not found on device")
            return
        }

        val result = fileSharer(context, file, item.displayName, item.mimeType)
        when (result) {
            is FileOpenResult.Success -> {
                _uiState.value = _uiState.value.copy(openFileFeedback = null)
            }
            is FileOpenResult.NoViewerApp -> {
                _uiState.value = _uiState.value.copy(openFileFeedback = "No app available to share this file")
            }
            is FileOpenResult.FileNotFound -> {
                _uiState.value = _uiState.value.copy(openFileFeedback = "File not found on device")
            }
            is FileOpenResult.Error -> {
                _uiState.value = _uiState.value.copy(openFileFeedback = result.message)
            }
        }
    }

    fun openItemFile(context: Context?, item: HistoryItemDetail) {
        val key = item.receivedFileKey
        if (key == null) {
            _uiState.value = _uiState.value.copy(openFileFeedback = "File not found")
            return
        }

        val file = fileResolver(context, key)
        if (file == null || !file.exists()) {
            _uiState.value = _uiState.value.copy(openFileFeedback = "File not found on device")
            return
        }

        val result = fileOpener(context, file, item.displayName, item.mimeType)
        when (result) {
            is FileOpenResult.Success -> {
                _uiState.value = _uiState.value.copy(openFileFeedback = null)
            }
            is FileOpenResult.NoViewerApp -> {
                _uiState.value = _uiState.value.copy(openFileFeedback = "No app available to open this file")
            }
            is FileOpenResult.FileNotFound -> {
                _uiState.value = _uiState.value.copy(openFileFeedback = "File not found on device")
            }
            is FileOpenResult.Error -> {
                _uiState.value = _uiState.value.copy(openFileFeedback = result.message)
            }
        }
    }

    fun exportItemFile(context: Context?, item: HistoryItemDetail) {
        val key = item.receivedFileKey
        if (key == null) {
            _uiState.value = _uiState.value.copy(exportStatus = ExportUiStatus.Failed("File not found"))
            return
        }

        val file = fileResolver(context, key)
        if (file == null || !file.exists()) {
            _uiState.value = _uiState.value.copy(exportStatus = ExportUiStatus.Failed("File not found on device"))
            return
        }

        if (context == null) {
            _uiState.value = _uiState.value.copy(exportStatus = ExportUiStatus.Failed("Context unavailable for export"))
            return
        }

        _uiState.value = _uiState.value.copy(exportStatus = ExportUiStatus.Exporting)

        viewModelScope.launch {
            val exporter = fileExporterFactory(context)
            val result = exporter.exportToDownloads(
                sourceFile = file,
                displayName = item.displayName,
                mimeType = item.mimeType,
                relativePath = item.relativePath
            )
            when (result) {
                is ExportResult.Success -> {
                    _uiState.value = _uiState.value.copy(exportStatus = ExportUiStatus.Success(result.destinationName))
                }
                is ExportResult.Failure -> {
                    _uiState.value = _uiState.value.copy(exportStatus = ExportUiStatus.Failed(result.message))
                }
            }
        }
    }

    fun clearOpenFileFeedback() {
        _uiState.value = _uiState.value.copy(openFileFeedback = null)
    }

    fun resetExportStatus() {
        _uiState.value = _uiState.value.copy(exportStatus = ExportUiStatus.Idle)
    }

    internal fun setUiStateForTesting(state: HistoryUiState) {
        isTestingOverride = true
        _uiState.value = state
    }

    companion object {
        fun provideFactory(
            historyStore: HistoryStore,
            fileResolver: (Context?, String) -> File? = { context, key ->
                context?.let { FileOpener.resolveReceivedFile(it, key) }
            },
            fileOpener: (Context?, File, String, String) -> FileOpenResult = { context, file, name, mime ->
                context?.let { FileOpener.openFile(it, file, name, mime) } ?: FileOpenResult.FileNotFound
            },
            fileSharer: (Context?, File, String, String) -> FileOpenResult = { context, file, name, mime ->
                context?.let { dev.glide.app.util.FileSharing.shareFile(it, file, name, mime) } ?: FileOpenResult.FileNotFound
            },
            fileExporterFactory: (Context) -> FileExporter = { context ->
                AndroidFileExporter(context)
            }
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HistoryViewModel(historyStore, fileResolver, fileOpener, fileSharer, fileExporterFactory) as T
            }
        }
    }
}
