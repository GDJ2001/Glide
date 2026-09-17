package dev.glide.app.ui.transfer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.glide.app.service.TransferSessionFactory
import dev.glide.app.service.TransferSessionManager
import dev.glide.app.transfer.ActiveSessionTracker
import dev.glide.app.ui.discovery.DeviceType
import dev.glide.app.ui.pairing.PairingUiState
import dev.glide.app.ui.selection.SelectedTransferStore
import dev.glide.app.util.FileOpenResult
import dev.glide.app.util.FileOpener
import dev.glide.core.coordinator.ItemPhase
import dev.glide.core.coordinator.TransferCoordinator
import dev.glide.core.domain.BatchId
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.TransferBatch
import dev.glide.core.ports.ConnectionEvent
import dev.glide.core.ports.TransferTransport
import dev.glide.data.export.AndroidFileExporter
import dev.glide.data.export.ExportResult
import dev.glide.data.export.FileExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

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

    private val _pairingState = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val pairingState: StateFlow<PairingUiState> = _pairingState.asStateFlow()

    private var activeTransport: TransferTransport? = null
    private var pairingJob: Job? = null

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
            val result = exporter.exportToDownloads(
                sourceFile = file,
                displayName = item.displayName
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
        val state = _uiState.value
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
        val state = _uiState.value
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
                val key = item.committedFileKey ?: continue
                val file = fileResolver(key)?.takeIf { it.exists() } ?: continue
                val result = exporter.exportToDownloads(
                    sourceFile = file,
                    displayName = item.displayName
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
                val committedKeys = coordinator.committedItemFiles.mapKeys { it.key.value }.mapValues { it.value.value }
                _uiState.value = sessionState.toUiState(
                    peerDisplayName = peerDisplayName,
                    committedFileKey = coordinator.lastCommittedFileHandle?.value,
                    committedItemKeys = committedKeys
                )
            }
        }
    }

    fun acceptConsent() {
        val sid = _uiState.value.sessionId.takeIf { it.isNotEmpty() }
        directCoordinator?.acceptOffer() ?: TransferSessionManager.acceptConsent(sessionId = sid)
    }

    fun rejectConsent(reason: String = "USER_REJECTED") {
        val sid = _uiState.value.sessionId.takeIf { it.isNotEmpty() }
        directCoordinator?.rejectOffer(reason) ?: TransferSessionManager.rejectConsent(sessionId = sid, reason = reason)
    }

    fun cancelTransfer(reason: String = "USER_CANCELLED") {
        val sid = _uiState.value.sessionId.takeIf { it.isNotEmpty() }
        if (directCoordinator != null) {
            directCoordinator?.cancel(reason)
        } else {
            TransferSessionManager.cancelTransfer(reason = reason, sessionId = sid)
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

    /**
     * Binds a [TransferTransport] to observe live link verification and pairing events (LAN-004).
     */
    fun bindTransport(
        transport: TransferTransport,
        onPairingSuccess: ((PeerId) -> Unit)? = null
    ) {
        activeTransport = transport
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            transport.observeConnectionEvents().collect { event ->
                when (event) {
                    is ConnectionEvent.Initiated -> {
                        _pairingState.value = PairingUiState.Verifying(
                            peerId = event.peer.id,
                            peerName = event.peer.displayName,
                            authDigits = event.authDigits,
                            localConfirmed = false,
                            isSameNamePeer = false,
                            deviceType = DeviceType.fromDeviceName(event.peer.displayName)
                        )
                    }
                    is ConnectionEvent.Connected -> {
                        _pairingState.value = PairingUiState.Connected(event.peer)
                        onPairingSuccess?.invoke(event.peer.id)
                    }
                    is ConnectionEvent.Rejected -> {
                        _pairingState.value = PairingUiState.Failed(
                            title = "Connection Rejected",
                            message = "The connection was rejected by the peer."
                        )
                    }
                    is ConnectionEvent.Failed -> {
                        _pairingState.value = PairingUiState.Failed(
                            title = "Connection Failed",
                            message = event.reason
                        )
                    }
                    is ConnectionEvent.Disconnected -> {
                        if (_pairingState.value is PairingUiState.Verifying) {
                            _pairingState.value = PairingUiState.Failed(
                                title = "Disconnected",
                                message = "Remote peer disconnected."
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Confirms that SAS / PIN digits match the peer.
     * Strictly enforces single tap by checking [PairingUiState.Verifying.localConfirmed].
     */
    fun confirmPairing(peerId: PeerId) {
        val current = _pairingState.value
        if (current !is PairingUiState.Verifying) return
        if (current.localConfirmed) return // Enforce single confirmation; reject duplicate taps

        _pairingState.value = current.copy(localConfirmed = true)
        viewModelScope.launch {
            activeTransport?.acceptConnection(peerId)
        }
    }

    /**
     * Rejects SAS / PIN digit match, terminating the pairing attempt.
     */
    fun rejectPairing(peerId: PeerId) {
        val current = _pairingState.value
        if (current !is PairingUiState.Verifying) return

        _pairingState.value = PairingUiState.Failed(
            title = "Verification Cancelled",
            message = "Pairing with ${current.peerName} was cancelled."
        )
        viewModelScope.launch {
            activeTransport?.rejectConnection(peerId)
        }
    }

    fun setPairingStateForTesting(state: PairingUiState) {
        _pairingState.value = state
    }

    /**
     * Starts a service-owned sender transfer session using [TransferSessionFactory], preserving
     * the complete multi-file batch and source map from [SelectedTransferStore] (LAN-003, LAN-004).
     */
    fun startSenderSession(
        factory: TransferSessionFactory,
        transport: TransferTransport,
        peerId: PeerId,
        peerDisplayName: String,
        batchId: BatchId? = null,
        scope: CoroutineScope = viewModelScope
    ): Boolean {
        val prepared = SelectedTransferStore.get(batchId) ?: return false
        val sessionId = SessionId(UUID.randomUUID().toString())
        val coordinator = factory.createSenderSession(
            sessionId = sessionId,
            peerId = peerId,
            peerDisplayName = peerDisplayName,
            senderBatch = prepared.batch,
            sourceMap = prepared.sourceMap,
            transport = transport,
            scope = scope
        )
        return TransferSessionManager.startSession(coordinator, peerDisplayName, scope)
    }

    /**
     * Starts a service-owned receiver transfer session using [TransferSessionFactory],
     * bootstrapping without a session ID to bind the first authenticated HELLO once (LAN-003, LAN-004).
     */
    fun startReceiverSession(
        factory: TransferSessionFactory,
        transport: TransferTransport,
        peerId: PeerId,
        peerDisplayName: String,
        sessionId: SessionId? = null,
        scope: CoroutineScope = viewModelScope
    ): Boolean {
        val coordinator = factory.createReceiverSession(
            peerId = peerId,
            peerDisplayName = peerDisplayName,
            sessionId = sessionId,
            transport = transport,
            scope = scope
        )
        return TransferSessionManager.startSession(coordinator, peerDisplayName, scope)
    }

    override fun onCleared() {
        super.onCleared()
        pairingJob?.cancel()
        pairingJob = null
        // If direct coordinator was bound without foreground service, stop it
        if (directCoordinator != null && TransferSessionManager.getActiveCoordinator() != directCoordinator) {
            directCoordinator?.stop()
        }
    }
}
