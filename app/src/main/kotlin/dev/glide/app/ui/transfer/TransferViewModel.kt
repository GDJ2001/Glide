package dev.glide.app.ui.transfer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.glide.app.service.TransferSessionManager
import dev.glide.app.transfer.ActiveSessionTracker
import dev.glide.app.util.FileOpenResult
import dev.glide.app.util.FileOpener
import dev.glide.core.coordinator.ItemPhase
import dev.glide.core.coordinator.TransferCoordinator
import dev.glide.core.domain.DeviceRole
import dev.glide.data.export.AndroidFileExporter
import dev.glide.data.export.ExportResult
import dev.glide.data.export.FileExporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * ViewModel governing transfer screen UI state and user intents (GLIDE-015, GLIDE-016, GLIDE-023, GLIDE-028).
 *
 * Integrates with [TransferSessionManager] so background execution and screen rotation share
 * the exact same active transfer session without duplicate transfers or state loss.
 */
class TransferViewModel : ViewModel() {

    private var directCoordinator: TransferCoordinator? = null
    private val _uiState = MutableStateFlow<TransferUiState>(TransferUiState.Idle)
    val uiState: StateFlow<TransferUiState> = _uiState.asStateFlow()

    private val _openFileFeedback = MutableStateFlow<String?>(null)
    val openFileFeedback: StateFlow<String?> = _openFileFeedback.asStateFlow()

    private val _exportStatus = MutableStateFlow<ExportUiStatus>(ExportUiStatus.Idle)
    val exportStatus: StateFlow<ExportUiStatus> = _exportStatus.asStateFlow()

    init {
        // Collect shared session state from TransferSessionManager
        viewModelScope.launch {
            TransferSessionManager.uiState.collect { state ->
                if (directCoordinator == null && state !is TransferUiState.Idle) {
                    _uiState.value = state
                }
            }
        }
    }

    fun clearOpenFileFeedback() {
        _openFileFeedback.value = null
    }

    fun resetExportStatus() {
        _exportStatus.value = ExportUiStatus.Idle
    }

    fun exportReceivedFile(
        context: Context?,
        fileResolver: (String) -> File? = { key -> context?.let { FileOpener.resolveReceivedFile(it, key) } },
        fileExporter: FileExporter? = null
    ) {
        val state = _uiState.value
        if (state !is TransferUiState.Completed || state.role != DeviceRole.Receiver) {
            _exportStatus.value = ExportUiStatus.Failed("No verified file available to export")
            return
        }

        val key = state.committedFileKey
        if (key == null) {
            _exportStatus.value = ExportUiStatus.Failed("File not found")
            return
        }

        val file = fileResolver(key)
        if (file == null || !file.exists()) {
            _exportStatus.value = ExportUiStatus.Failed("File not found on device")
            return
        }

        val exporter = fileExporter ?: context?.let { AndroidFileExporter(it) }
        if (exporter == null) {
            _exportStatus.value = ExportUiStatus.Failed("Exporter unavailable")
            return
        }

        _exportStatus.value = ExportUiStatus.Exporting

        viewModelScope.launch {
            val result = exporter.exportToDownloads(
                sourceFile = file,
                displayName = state.itemName
            )
            when (result) {
                is ExportResult.Success -> {
                    _exportStatus.value = ExportUiStatus.Success(result.destinationName)
                }
                is ExportResult.Failure -> {
                    _exportStatus.value = ExportUiStatus.Failed(result.message)
                }
            }
        }
    }

    fun openReceivedFile(
        context: Context?,
        fileResolver: (String) -> File? = { key -> context?.let { FileOpener.resolveReceivedFile(it, key) } },
        fileOpener: (File, String, String) -> FileOpenResult = { f, dn, mt ->
            context?.let { FileOpener.openFile(it, f, dn, mt) } ?: FileOpenResult.FileNotFound
        }
    ) {
        val state = _uiState.value
        if (state !is TransferUiState.Completed || state.role != DeviceRole.Receiver) {
            _openFileFeedback.value = "No verified file available to open"
            return
        }

        val key = state.committedFileKey
        if (key == null) {
            _openFileFeedback.value = "File not found"
            return
        }

        val file = fileResolver(key)
        if (file == null || !file.exists()) {
            _openFileFeedback.value = "File not found on device"
            return
        }

        val result = fileOpener(file, state.itemName, "*/*")
        when (result) {
            is FileOpenResult.Success -> {
                _openFileFeedback.value = null
            }
            is FileOpenResult.NoViewerApp -> {
                _openFileFeedback.value = "No app available to open this file"
            }
            is FileOpenResult.FileNotFound -> {
                _openFileFeedback.value = "File not found on device"
            }
            is FileOpenResult.Error -> {
                _openFileFeedback.value = result.message
            }
        }
    }

    internal fun setUiStateForTesting(state: TransferUiState) {
        _uiState.value = state
        val isActive = state is TransferUiState.Connecting ||
            state is TransferUiState.AwaitingConsent ||
            state is TransferUiState.Transferring ||
            state is TransferUiState.Verifying
        ActiveSessionTracker.setSessionActive(state.sessionId, isActive)
    }

    fun bindCoordinator(
        coordinator: TransferCoordinator,
        peerDisplayName: String = "Nearby device"
    ) {
        directCoordinator = coordinator
        TransferSessionManager.startSession(coordinator, peerDisplayName, viewModelScope)

        viewModelScope.launch {
            coordinator.state.collect { sessionState ->
                _uiState.value = sessionState.toUiState(
                    peerDisplayName = peerDisplayName,
                    committedFileKey = coordinator.lastCommittedFileHandle?.value
                )
            }
        }
    }

    fun acceptConsent() {
        directCoordinator?.acceptOffer() ?: TransferSessionManager.acceptConsent()
    }

    fun rejectConsent(reason: String = "USER_REJECTED") {
        directCoordinator?.rejectOffer(reason) ?: TransferSessionManager.rejectConsent(reason)
    }

    fun cancelTransfer(reason: String = "USER_CANCELLED") {
        if (directCoordinator != null) {
            directCoordinator?.cancel(reason)
        } else {
            TransferSessionManager.cancelTransfer(reason)
            val current = _uiState.value
            if (current !is TransferUiState.Cancelled) {
                _uiState.value = TransferUiState.Cancelled(
                    sessionId = current.sessionId,
                    role = current.role,
                    reason = reason
                )
            }
        }
    }

    /**
     * Retrieves the source URI strings of items in the current transfer that were not yet completed,
     * allowing the user to initiate an explicit retry session containing only unfinished items (GLIDE-032).
     */
    fun getUnfinishedSourceUris(): List<String> {
        val coordinator = directCoordinator ?: TransferSessionManager.getActiveCoordinator()
        val sessionState = coordinator?.state?.value ?: return emptyList()
        val items = sessionState.batch?.items ?: coordinator.senderBatch?.items ?: emptyList()
        val unfinished = items.filter { item ->
            val itState = sessionState.itemStates[item.id]
            itState == null || itState.phase != ItemPhase.Verified
        }
        return unfinished.mapNotNull { item ->
            coordinator.sourceMap[item.id]?.value
        }
    }

    override fun onCleared() {
        super.onCleared()
        // If direct coordinator was bound without foreground service, stop it
        if (directCoordinator != null && TransferSessionManager.getActiveCoordinator() != directCoordinator) {
            directCoordinator?.stop()
        }
    }
}
