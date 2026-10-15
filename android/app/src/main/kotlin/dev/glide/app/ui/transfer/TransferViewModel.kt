package dev.glide.app.ui.transfer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.glide.app.service.TransferRuntime
import dev.glide.app.service.TransferRuntimeCommand
import dev.glide.app.service.TrustRuntimeState
import dev.glide.app.ui.pairing.PairingUiState
import dev.glide.app.util.FileOpenResult
import dev.glide.app.util.FileOpener
import dev.glide.app.util.ReceivedFileSaver
import dev.glide.core.coordinator.TransferCoordinator
import dev.glide.core.domain.BatchId
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.SessionId
import dev.glide.core.ports.TransferTransport
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
 * Observes [TransferRuntime] state and forwards typed commands. Active transport/coordinator
 * execution is owned by the foreground service runtime rather than this ViewModel's scope.
 */
class TransferViewModel(
    private val runtime: TransferRuntime
) : ViewModel() {

    /**
     * Test-only fallback. Production navigation supplies the injected process runtime.
     */
    constructor() : this(TransferRuntime.forTesting())

    val uiState: StateFlow<TransferUiState> = runtime.transferState
    val pairingState: StateFlow<PairingUiState> = runtime.pairingState
    val trustState: StateFlow<TrustRuntimeState> = runtime.trustState

    private val _openFileFeedback = MutableStateFlow<String?>(null)
    val openFileFeedback: StateFlow<String?> = _openFileFeedback.asStateFlow()

    private val _exportStatus = MutableStateFlow<ExportUiStatus>(ExportUiStatus.Idle)
    val exportStatus: StateFlow<ExportUiStatus> = _exportStatus.asStateFlow()

    init {
        viewModelScope.launch {
            runtime.autoSaveStatus.collect { _exportStatus.value = it }
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
        val state = runtime.transferState.value
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
            val result = ReceivedFileSaver.save(
                context = context,
                exporter = exporter,
                sourceFile = file,
                displayName = state.itemName,
                useSavedDestination = fileExporter == null
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
        val state = runtime.transferState.value
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

    fun openVerifiedItem(
        context: Context?,
        item: VerifiedItemUiState,
        fileResolver: (String) -> File? = { key -> context?.let { FileOpener.resolveReceivedFile(it, key) } },
        fileOpener: (File, String, String) -> FileOpenResult = { f, dn, mt ->
            context?.let { FileOpener.openFile(it, f, dn, mt) } ?: FileOpenResult.FileNotFound
        }
    ) {
        val key = item.committedFileKey
        if (key == null) {
            _openFileFeedback.value = "File not found"
            return
        }

        val file = fileResolver(key)
        if (file == null || !file.exists()) {
            _openFileFeedback.value = "File not found on device"
            return
        }

        val result = fileOpener(file, item.displayName, item.mimeType)
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

    fun shareVerifiedItem(
        context: Context?,
        item: VerifiedItemUiState,
        fileResolver: (String) -> File? = { key -> context?.let { FileOpener.resolveReceivedFile(it, key) } },
        fileSharer: (File, String, String) -> FileOpenResult = { f, dn, mt ->
            context?.let { dev.glide.app.util.FileSharing.shareFile(it, f, dn, mt) } ?: FileOpenResult.FileNotFound
        }
    ) {
        val key = item.committedFileKey
        if (key == null) {
            _openFileFeedback.value = "File not found"
            return
        }

        val file = fileResolver(key)
        if (file == null || !file.exists()) {
            _openFileFeedback.value = "File not found on device"
            return
        }

        val result = fileSharer(file, item.displayName, item.mimeType)
        when (result) {
            is FileOpenResult.Success -> {
                _openFileFeedback.value = null
            }
            is FileOpenResult.NoViewerApp -> {
                _openFileFeedback.value = "No app available to share this file"
            }
            is FileOpenResult.FileNotFound -> {
                _openFileFeedback.value = "File not found on device"
            }
            is FileOpenResult.Error -> {
                _openFileFeedback.value = result.message
            }
        }
    }

    fun exportVerifiedItem(
        context: Context?,
        item: VerifiedItemUiState,
        fileResolver: (String) -> File? = { key -> context?.let { FileOpener.resolveReceivedFile(it, key) } },
        fileExporter: FileExporter? = null
    ) {
        val key = item.committedFileKey
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
            val result = ReceivedFileSaver.save(
                context = context,
                exporter = exporter,
                sourceFile = file,
                displayName = item.displayName,
                mimeType = item.mimeType,
                relativePath = item.relativePath,
                useSavedDestination = fileExporter == null
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

    fun shareAllVerifiedItems(
        context: Context?,
        fileResolver: (String) -> File? = { key -> context?.let { FileOpener.resolveReceivedFile(it, key) } },
        fileSharer: (List<File>, String) -> FileOpenResult = { files, mime ->
            context?.let { dev.glide.app.util.FileSharing.shareFiles(it, files, mime) } ?: FileOpenResult.FileNotFound
        }
    ) {
        val state = runtime.transferState.value
        if (state !is TransferUiState.Completed) {
            _openFileFeedback.value = "No verified files available to share"
            return
        }

        val itemsWithFiles = state.verifiedItems.mapNotNull { item ->
            val key = item.committedFileKey ?: return@mapNotNull null
            val file = fileResolver(key)?.takeIf { it.exists() } ?: return@mapNotNull null
            file to item.mimeType
        }

        if (itemsWithFiles.isEmpty()) {
            _openFileFeedback.value = "No files available on device to share"
            return
        }

        val files = itemsWithFiles.map { it.first }
        val mimeTypes = itemsWithFiles.map { it.second }.distinct()
        val commonMime = if (mimeTypes.size == 1) mimeTypes.first() else "*/*"

        val result = fileSharer(files, commonMime)
        when (result) {
            is FileOpenResult.Success -> _openFileFeedback.value = null
            is FileOpenResult.NoViewerApp -> _openFileFeedback.value = "No app available to share these files"
            is FileOpenResult.FileNotFound -> _openFileFeedback.value = "Files not found on device"
            is FileOpenResult.Error -> _openFileFeedback.value = result.message
        }
    }

    fun exportAllVerifiedItems(
        context: Context?,
        fileResolver: (String) -> File? = { key -> context?.let { FileOpener.resolveReceivedFile(it, key) } },
        fileExporter: FileExporter? = null
    ) {
        val state = runtime.transferState.value
        if (state !is TransferUiState.Completed || state.role != DeviceRole.Receiver) {
            _exportStatus.value = ExportUiStatus.Failed("No verified files available to export")
            return
        }

        val exporter = fileExporter ?: context?.let { AndroidFileExporter(it) }
        if (exporter == null) {
            _exportStatus.value = ExportUiStatus.Failed("Exporter unavailable")
            return
        }

        _exportStatus.value = ExportUiStatus.Exporting

        viewModelScope.launch {
            var exportedCount = 0
            var lastDestination = ""
            for (item in state.verifiedItems) {
                if (item.itemId in runtime.autoSavedItemIds.value) continue
                val key = item.committedFileKey ?: continue
                val file = fileResolver(key)?.takeIf { it.exists() } ?: continue
                val result = ReceivedFileSaver.save(
                    context = context,
                    exporter = exporter,
                    sourceFile = file,
                    displayName = item.displayName,
                    mimeType = item.mimeType,
                    relativePath = item.relativePath,
                    useSavedDestination = fileExporter == null
                )
                when (result) {
                    is ExportResult.Success -> {
                        exportedCount++
                        lastDestination = result.destinationName
                    }
                    is ExportResult.Failure -> {
                        _exportStatus.value = ExportUiStatus.Failed("Export failed for ${item.displayName}: ${result.message}")
                        return@launch
                    }
                }
            }

            if (exportedCount == 0) {
                _exportStatus.value = ExportUiStatus.Failed("No files found on device to export")
            } else if (exportedCount == 1) {
                _exportStatus.value = ExportUiStatus.Success(lastDestination)
            } else {
                _exportStatus.value = ExportUiStatus.Success("$exportedCount files")
            }
        }
    }

    internal fun setUiStateForTesting(state: TransferUiState) {
        runtime.setTransferStateForTesting(state)
    }

    /**
     * Test bridge for coordinator-focused tests. Runtime observation/execution still uses the
     * attached service/test scope rather than viewModelScope.
     */
    fun bindCoordinator(
        coordinator: TransferCoordinator,
        peerDisplayName: String = "Nearby device"
    ): Boolean = runtime.attachCoordinatorForTesting(coordinator, peerDisplayName)

    fun acceptConsent(): Boolean {
        val sid = uiState.value.sessionId.takeIf { it.isNotEmpty() } ?: return false
        return runtime.dispatch(TransferRuntimeCommand.AcceptOffer(sid))
    }

    fun rejectConsent(reason: String = "USER_REJECTED"): Boolean {
        val sid = uiState.value.sessionId.takeIf { it.isNotEmpty() } ?: return false
        return runtime.dispatch(TransferRuntimeCommand.RejectOffer(sid, reason))
    }

    fun cancelTransfer(reason: String = "USER_CANCELLED"): Boolean {
        val sid = uiState.value.sessionId.takeIf { it.isNotEmpty() } ?: return false
        return runtime.dispatch(TransferRuntimeCommand.Cancel(sid, reason))
    }

    fun resumeTransfer(): Boolean {
        val sid = uiState.value.sessionId.takeIf { it.isNotEmpty() } ?: return false
        return runtime.dispatch(TransferRuntimeCommand.Resume(sid))
    }

    /**
     * Retrieves source URI strings for unfinished sender items from runtime-owned retry metadata.
     */
    fun getUnfinishedSourceUris(): List<String> = runtime.getUnfinishedSourceUris()

    /**
     * Registers a transport with the service-owned runtime for pairing observation.
     */
    fun bindTransport(
        transport: TransferTransport,
        onPairingSuccess: ((PeerId) -> Unit)? = null
    ): Boolean = runtime.dispatch(
        TransferRuntimeCommand.BindPairingTransport(transport, onPairingSuccess)
    )

    fun approvePendingTrust(): Boolean {
        val pending = trustState.value.pendingEnrollment ?: return false
        return runtime.dispatch(
            TransferRuntimeCommand.ApprovePeerEnrollment(
                pending.fingerprintHex
            )
        )
    }

    fun rejectPendingTrust(): Boolean {
        val pending = trustState.value.pendingEnrollment ?: return false
        return runtime.dispatch(
            TransferRuntimeCommand.RejectPeerEnrollment(
                pending.fingerprintHex
            )
        )
    }
    fun confirmPairing(peerId: PeerId): Boolean =
        runtime.dispatch(TransferRuntimeCommand.ConfirmPairing(peerId))

    fun rejectPairing(peerId: PeerId): Boolean =
        runtime.dispatch(TransferRuntimeCommand.RejectPairing(peerId))

    internal fun setPairingStateForTesting(state: PairingUiState) {
        runtime.setPairingStateForTesting(state)
    }

    /**
     * Requests a sender session. Coordinator construction and execution ownership remain in
     * [TransferRuntime], never in this ViewModel.
     */
    fun startSenderSession(
        transport: TransferTransport,
        peerId: PeerId,
        peerDisplayName: String,
        batchId: BatchId? = null
    ): Boolean = runtime.dispatch(
        TransferRuntimeCommand.StartSender(
            selectionId = batchId,
            transport = transport,
            peerId = peerId,
            peerDisplayName = peerDisplayName
        )
    )

    /**
     * Requests a receiver session through the service-owned runtime.
     */
    fun startReceiverSession(
        transport: TransferTransport,
        peerId: PeerId,
        peerDisplayName: String,
        sessionId: SessionId? = null
    ): Boolean = runtime.dispatch(
        TransferRuntimeCommand.StartReceiver(
            transport = transport,
            peerId = peerId,
            peerDisplayName = peerDisplayName,
            sessionId = sessionId
        )
    )

}
