package dev.glide.core.fakes

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.CommittedFileHandle
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.StagingHandle
import dev.glide.core.domain.TransferFailure
import dev.glide.core.ports.AdvertisingEvent
import dev.glide.core.ports.BulkProgress
import dev.glide.core.ports.ControlMessage
import dev.glide.core.ports.DiscoveryEvent
import dev.glide.core.ports.HistoryStore
import dev.glide.core.ports.IncomingStream
import dev.glide.core.ports.InputStreamProvider
import dev.glide.core.ports.ItemRecord
import dev.glide.core.ports.ReceiveStore
import dev.glide.core.ports.SessionRecord
import dev.glide.core.ports.SessionSummary
import dev.glide.core.ports.SourceAccess
import dev.glide.core.ports.SourceMetadata
import dev.glide.core.ports.StreamExpectation
import dev.glide.core.ports.TransferTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class FakeTransferTransport : TransferTransport {
    val discoveryChannel = Channel<DiscoveryEvent>(Channel.UNLIMITED)
    val advertisingChannel = Channel<AdvertisingEvent>(Channel.UNLIMITED)
    val connectionEventsChannel = Channel<dev.glide.core.ports.ConnectionEvent>(Channel.UNLIMITED)
    val incomingControlChannel = Channel<ControlMessage>(Channel.UNLIMITED)
    val incomingStreamChannel = Channel<IncomingStream>(Channel.UNLIMITED)
    val bulkProgressChannel = Channel<BulkProgress>(Channel.UNLIMITED)

    val sentControlMessages = mutableListOf<ControlMessage>()
    val initiatedPeers = mutableListOf<Pair<PeerId, String>>()
    val acceptedPeers = mutableListOf<PeerId>()
    val rejectedPeers = mutableListOf<PeerId>()
    val disconnectedPeers = mutableListOf<PeerId>()
    val cancelledPayloads = mutableListOf<BulkRef>()
    val preparedStreams = mutableMapOf<BulkRef, InputStreamProvider>()
    val markedReadyStreams = mutableSetOf<BulkRef>()
    val registeredExpectedStreams = mutableSetOf<BulkRef>()
    val registeredExpectations = mutableListOf<StreamExpectation>()

    var isDiscovering = false
    var isAdvertising = false

    override suspend fun startDiscovery(serviceId: String): Flow<DiscoveryEvent> {
        isDiscovering = true
        return discoveryChannel.receiveAsFlow()
    }

    override suspend fun stopDiscovery() {
        isDiscovering = false
    }

    override suspend fun startAdvertising(serviceId: String, localDeviceName: String): Flow<AdvertisingEvent> {
        isAdvertising = true
        return advertisingChannel.receiveAsFlow()
    }

    override suspend fun stopAdvertising() {
        isAdvertising = false
    }

    override suspend fun initiateConnection(peerId: PeerId, localDeviceName: String) {
        initiatedPeers.add(peerId to localDeviceName)
    }

    override suspend fun acceptConnection(peerId: PeerId) {
        acceptedPeers.add(peerId)
    }

    override suspend fun rejectConnection(peerId: PeerId) {
        rejectedPeers.add(peerId)
    }

    override fun observeConnectionEvents(): Flow<dev.glide.core.ports.ConnectionEvent> {
        return connectionEventsChannel.receiveAsFlow()
    }

    override suspend fun sendControl(peerId: PeerId, payload: ByteArray) {
        sentControlMessages.add(ControlMessage(peerId, payload))
    }

    override fun observeControl(): Flow<ControlMessage> {
        return incomingControlChannel.receiveAsFlow()
    }

    override suspend fun sendStream(
        peerId: PeerId,
        bulkRef: BulkRef
    ): Flow<BulkProgress> = flow {
        val provider = preparedStreams.remove(bulkRef)
            ?: throw IllegalStateException("No prepared stream for bulkRef ${bulkRef.value}")
        val stream = provider.openStream()
        val buffer = ByteArray(8192)
        var totalBytes = 0L
        var read: Int
        while (stream.read(buffer).also { read = it } != -1) {
            totalBytes += read
            emit(BulkProgress(bulkRef, totalBytes, false))
        }
        emit(BulkProgress(bulkRef, totalBytes, true))
    }

    override suspend fun sendStream(
        peerId: PeerId,
        bulkRef: BulkRef,
        streamSource: InputStreamProvider
    ): Flow<BulkProgress> {
        preparedStreams.getOrPut(bulkRef) { streamSource }
        return sendStream(peerId, bulkRef)
    }

    override fun observeIncomingStream(): Flow<IncomingStream> {
        return incomingStreamChannel.receiveAsFlow()
    }

    override fun observeBulkProgress(): Flow<BulkProgress> {
        return bulkProgressChannel.receiveAsFlow()
    }

    override suspend fun prepareStream(streamSource: InputStreamProvider): BulkRef {
        val ref = BulkRef("bulk-${java.util.UUID.randomUUID().toString().take(8)}")
        preparedStreams[ref] = streamSource
        return ref
    }

    override suspend fun markItemReady(bulkRef: BulkRef) {
        markedReadyStreams.add(bulkRef)
    }

    override suspend fun registerExpectedStream(expectation: StreamExpectation) {
        registeredExpectations.add(expectation)
        registeredExpectedStreams.add(expectation.bulkRef)
    }

    override suspend fun registerExpectedStream(bulkRef: BulkRef) {
        registeredExpectedStreams.add(bulkRef)
    }

    override suspend fun cancelPayload(bulkRef: BulkRef) {
        cancelledPayloads.add(bulkRef)
    }

    override suspend fun disconnect(peerId: PeerId) {
        disconnectedPeers.add(peerId)
    }
}

class FakeSourceAccess : SourceAccess {
    var throwOnOpen: Exception? = null
    var throwOnOpenForHandle: ((SourceHandle) -> Exception?)? = null
    var onOpenHook: (suspend (SourceHandle) -> Unit)? = null
    var throwOnValidate: Exception? = null
    private val sources = mutableMapOf<SourceHandle, Pair<SourceMetadata, ByteArray>>()
    val openCounts = mutableMapOf<SourceHandle, Int>()

    fun registerSource(handle: SourceHandle, metadata: SourceMetadata, content: ByteArray) {
        sources[handle] = metadata to content
    }

    override suspend fun validateSource(handle: SourceHandle): SourceMetadata {
        throwOnValidate?.let { throw it }
        return sources[handle]?.first ?: throw IllegalArgumentException("Source handle not found: $handle")
    }

    override suspend fun openSource(handle: SourceHandle): InputStream {
        onOpenHook?.invoke(handle)
        throwOnOpen?.let { throw it }
        throwOnOpenForHandle?.invoke(handle)?.let { throw it }
        openCounts[handle] = (openCounts[handle] ?: 0) + 1
        val content = sources[handle]?.second ?: throw IllegalArgumentException("Source handle not found: $handle")
        return ByteArrayInputStream(content)
    }
}

class FakeReceiveStore(
    var availableSpaceBytes: Long = 1_000_000_000L
) : ReceiveStore {
    var throwOnPrepareStaging: Exception? = null
    var throwOnOpenStagingOutput: Exception? = null

    private val stagingOutputs = mutableMapOf<StagingHandle, ByteArrayOutputStream>()
    val committedFiles = mutableMapOf<CommittedFileHandle, Pair<ByteArray, Sha256Digest>>()
    val discardedHandles = mutableListOf<StagingHandle>()
    val preparedStaging = mutableListOf<StagingHandle>()

    override suspend fun hasAvailableSpace(requiredBytes: Long): Boolean {
        return requiredBytes <= availableSpaceBytes
    }

    override suspend fun prepareStaging(
        sessionId: SessionId,
        itemId: ItemId,
        expectedBytes: Long,
        displayName: String,
        mimeType: String,
        relativePath: String?
    ): StagingHandle {
        throwOnPrepareStaging?.let { throw it }
        if (!hasAvailableSpace(expectedBytes)) {
            throw dev.glide.core.ports.ReceiveStoreException(
                TransferFailure.InsufficientStorage(
                    requiredBytes = expectedBytes,
                    availableBytes = availableSpaceBytes
                )
            )
        }
        val handle = StagingHandle("staging-${sessionId.value}-${itemId.value}")
        stagingOutputs[handle] = ByteArrayOutputStream()
        preparedStaging.add(handle)
        return handle
    }

    override suspend fun openStagingOutput(handle: StagingHandle): OutputStream {
        throwOnOpenStagingOutput?.let { throw it }
        return stagingOutputs[handle] ?: throw IllegalArgumentException("Staging handle not found: $handle")
    }

    override suspend fun commitVerified(
        handle: StagingHandle,
        digest: Sha256Digest
    ): CommittedFileHandle {
        val bytes = stagingOutputs[handle]?.toByteArray() ?: throw IllegalArgumentException("Staging handle not found: $handle")
        val committedHandle = CommittedFileHandle("file-${handle.value}")
        committedFiles[committedHandle] = bytes to digest
        stagingOutputs.remove(handle)
        return committedHandle
    }

    override suspend fun discardStaging(handle: StagingHandle) {
        stagingOutputs.remove(handle)
        discardedHandles.add(handle)
    }
}

class FakeHistoryStore : HistoryStore {
    val sessions = mutableMapOf<SessionId, SessionRecord>()
    val items = mutableListOf<ItemRecord>()

    override suspend fun recordSession(record: SessionRecord) {
        sessions[record.sessionId] = record
    }

    override suspend fun recordItem(record: ItemRecord) {
        items.add(record)
    }

    override suspend fun updateSessionOutcome(sessionId: SessionId, outcome: String, endedAtMs: Long) {
        val current = sessions[sessionId] ?: return
        sessions[sessionId] = current.copy(outcome = outcome, endedAtMs = endedAtMs)
    }

    override suspend fun getSession(sessionId: SessionId): SessionRecord? {
        return sessions[sessionId]
    }

    override suspend fun updateItemOutcome(
        itemId: ItemId,
        outcome: String,
        actualBytes: Long?,
        digest: Sha256Digest?
    ) {
        val index = items.indexOfFirst { it.itemId == itemId }
        if (index != -1) {
            val cur = items[index]
            items[index] = cur.copy(
                outcome = outcome,
                actualBytes = actualBytes ?: cur.actualBytes,
                digest = digest ?: cur.digest
            )
        }
    }

    override suspend fun getItemsForSession(sessionId: SessionId): List<ItemRecord> {
        return items.filter { it.sessionId == sessionId }
    }

    override suspend fun getSessionsPaged(role: DeviceRole?, limit: Int, offset: Int): List<SessionSummary> {
        val filtered = sessions.values
            .filter { role == null || it.role == role }
            .sortedByDescending { it.startedAtMs }
            .drop(offset)
            .take(limit)
        return filtered.map { session ->
            val sessionItems = items.filter { it.sessionId == session.sessionId }
            SessionSummary(
                sessionId = session.sessionId,
                batchId = session.batchId,
                role = session.role,
                peerLabel = session.peerLabel,
                startedAtMs = session.startedAtMs,
                endedAtMs = session.endedAtMs,
                outcome = session.outcome,
                itemCount = sessionItems.size,
                totalBytes = sessionItems.sumOf { it.actualBytes ?: it.offeredBytes }
            )
        }
    }

    override suspend fun getSessionCount(role: DeviceRole?): Int {
        return sessions.values.count { role == null || it.role == role }
    }

    override suspend fun trimRetention(maxSessions: Int, maxAgeMs: Long, nowMs: Long): Int {
        var pruned = 0
        val cutoffMs = nowMs - maxAgeMs
        val oldTerminal = sessions.values.filter { it.outcome != null && it.startedAtMs < cutoffMs }
        oldTerminal.forEach {
            sessions.remove(it.sessionId)
            items.removeAll { item -> item.sessionId == it.sessionId }
            pruned++
        }
        val terminalSessions = sessions.values
            .filter { it.outcome != null }
            .sortedByDescending { it.startedAtMs }
        if (terminalSessions.size > maxSessions) {
            val excess = terminalSessions.drop(maxSessions)
            excess.forEach {
                sessions.remove(it.sessionId)
                items.removeAll { item -> item.sessionId == it.sessionId }
                pruned++
            }
        }
        return pruned
    }

    override suspend fun clearHistory(): Int {
        val terminal = sessions.values.filter { it.outcome != null }
        val count = terminal.size
        terminal.forEach {
            sessions.remove(it.sessionId)
            items.removeAll { item -> item.sessionId == it.sessionId }
        }
        return count
    }

    override suspend fun deleteSession(sessionId: SessionId): Boolean {
        val removed = sessions.remove(sessionId) != null
        items.removeAll { it.sessionId == sessionId }
        return removed
    }
}

class FakeSettingsStore(
    initialSettings: dev.glide.core.ports.GlideSettings = dev.glide.core.ports.GlideSettings()
) : dev.glide.core.ports.SettingsStore {
    private val _settingsState = kotlinx.coroutines.flow.MutableStateFlow(initialSettings)
    override val settingsFlow: kotlinx.coroutines.flow.Flow<dev.glide.core.ports.GlideSettings> = _settingsState

    var shouldFailWrites: Boolean = false

    override suspend fun getSettings(): dev.glide.core.ports.GlideSettings = _settingsState.value

    override suspend fun updateDeviceName(name: String): Result<Unit> {
        if (shouldFailWrites) return Result.failure(java.io.IOException("Disk write failure"))
        val validation = dev.glide.core.ports.DeviceNameValidator.validate(name)
        return validation.map { validatedName ->
            _settingsState.value = _settingsState.value.copy(deviceName = validatedName)
        }
    }

    override suspend fun updateTheme(theme: dev.glide.core.ports.ThemePreference): Result<Unit> {
        if (shouldFailWrites) return Result.failure(java.io.IOException("Disk write failure"))
        _settingsState.value = _settingsState.value.copy(theme = theme)
        return Result.success(Unit)
    }

    override suspend fun updateDynamicColor(enabled: Boolean): Result<Unit> {
        if (shouldFailWrites) return Result.failure(java.io.IOException("Disk write failure"))
        _settingsState.value = _settingsState.value.copy(dynamicColor = enabled)
        return Result.success(Unit)
    }

    override suspend fun updateReducedMotion(motion: dev.glide.core.ports.ReducedMotionPreference): Result<Unit> {
        if (shouldFailWrites) return Result.failure(java.io.IOException("Disk write failure"))
        _settingsState.value = _settingsState.value.copy(reducedMotion = motion)
        return Result.success(Unit)
    }

    override suspend fun updateNotificationDetails(enabled: Boolean): Result<Unit> {
        if (shouldFailWrites) return Result.failure(java.io.IOException("Disk write failure"))
        _settingsState.value = _settingsState.value.copy(notificationDetails = enabled)
        return Result.success(Unit)
    }

    override suspend fun updateKeepScreenAwake(enabled: Boolean): Result<Unit> {
        if (shouldFailWrites) return Result.failure(java.io.IOException("Disk write failure"))
        _settingsState.value = _settingsState.value.copy(keepScreenAwake = enabled)
        return Result.success(Unit)
    }
}

class FakePrivateFileManager(
    initialFiles: List<dev.glide.core.ports.ReceivedFileInfo> = emptyList()
) : dev.glide.core.ports.PrivateFileManager {
    private val files = initialFiles.toMutableList()

    fun setFiles(newFiles: List<dev.glide.core.ports.ReceivedFileInfo>) {
        files.clear()
        files.addAll(newFiles)
    }

    override suspend fun getStorageSummary(): dev.glide.core.ports.StorageSummary {
        val totalFiles = files.size
        val totalBytes = files.sumOf { it.sizeBytes }
        val activeFiles = files.count { it.isActive }
        return dev.glide.core.ports.StorageSummary(
            totalFiles = totalFiles,
            totalBytes = totalBytes,
            activeFiles = activeFiles
        )
    }

    override suspend fun getReceivedFiles(): List<dev.glide.core.ports.ReceivedFileInfo> {
        return files.toList()
    }

    override suspend fun deleteReceivedFile(fileKey: String): Result<Unit> {
        val file = files.find { it.fileKey == fileKey }
            ?: return Result.failure(java.io.FileNotFoundException("File not found: $fileKey"))
        if (file.isActive) {
            return Result.failure(IllegalStateException("Cannot delete active file: ${file.displayName}"))
        }
        files.remove(file)
        return Result.success(Unit)
    }

    override suspend fun deleteAllReceivedFiles(): Result<Int> {
        val inactive = files.filter { !it.isActive }
        val count = inactive.size
        files.removeAll(inactive)
        return Result.success(count)
    }
}

