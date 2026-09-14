package dev.glide.core.coordinator

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.CommittedFileHandle
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.MessageId
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.StagingHandle
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferFailure
import dev.glide.core.ports.ConnectionEvent
import dev.glide.core.ports.HistoryStore
import dev.glide.core.ports.ItemRecord
import dev.glide.core.ports.ReceiveStore
import dev.glide.core.ports.SessionRecord
import dev.glide.core.ports.SourceAccess
import dev.glide.core.ports.TransferTransport
import dev.glide.core.protocol.Envelope
import dev.glide.core.protocol.MessageTypes
import dev.glide.core.protocol.ProtocolCodec
import dev.glide.core.security.ProtocolRateLimiter
import dev.glide.core.security.ProtocolSequencer
import dev.glide.core.security.RateLimitResult
import dev.glide.core.security.SequencerResult
import dev.glide.core.stream.HashingInputStream
import dev.glide.core.stream.StreamHasher
import dev.glide.core.stream.StreamProgressSampler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.UUID

/**
 * Coordinates deterministic transfer protocol execution between [TransferReducer], [TransferTransport],
 * [SourceAccess], and [ReceiveStore] for one-file (and sequential multi-file) transfers (GLIDE-015).
 *
 * Runs all state transitions sequentially using a Mutex, handles all transport and stream callbacks,
 * executes reducer effects, records history in [HistoryStore], and exposes immutable [SessionState] for the UI.
 */
class TransferCoordinator(
    val sessionId: SessionId,
    val role: DeviceRole,
    val peerId: PeerId,
    val localDisplayName: String,
    val transport: TransferTransport,
    val sourceAccess: SourceAccess,
    val receiveStore: ReceiveStore,
    val scope: CoroutineScope,
    val senderBatch: TransferBatch? = null,
    val sourceMap: Map<ItemId, SourceHandle> = emptyMap(),
    val historyStore: HistoryStore? = null,
    val clock: () -> Long = System::currentTimeMillis,
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val rateLimiter: ProtocolRateLimiter? = ProtocolRateLimiter(clock = clock),
    val sequencer: ProtocolSequencer = ProtocolSequencer(clock = clock)
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SessionState(sessionId = sessionId, role = role))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val stagingHandles: MutableMap<ItemId, StagingHandle> = Collections.synchronizedMap(mutableMapOf())
    private var currentStagingHandle: StagingHandle? = null

    private val _committedFiles: MutableList<CommittedFileHandle> = Collections.synchronizedList(mutableListOf())
    val committedFiles: List<CommittedFileHandle> get() = _committedFiles.toList()
    var lastCommittedFileHandle: CommittedFileHandle? = null
        private set

    private var sessionHistoryRecorded = false

    private suspend fun recordInitialHistory(batch: TransferBatch) {
        if (sessionHistoryRecorded) return
        val store = historyStore ?: return
        sessionHistoryRecorded = true
        store.recordSession(
            SessionRecord(
                sessionId = sessionId,
                batchId = batch.id,
                role = role,
                peerLabel = peerId.value,
                startedAtMs = clock()
            )
        )
        batch.items.forEachIndexed { index, item ->
            store.recordItem(
                ItemRecord(
                    itemId = item.id,
                    sessionId = sessionId,
                    ordinal = index,
                    displayName = item.name,
                    mimeType = item.mimeType,
                    offeredBytes = item.sizeBytes
                )
            )
        }
    }

    private suspend fun ensureSessionRecorded(outcome: String) {
        if (sessionHistoryRecorded) return
        val store = historyStore ?: return
        sessionHistoryRecorded = true
        val batch = _state.value.batch ?: senderBatch
        val batchId = batch?.id ?: BatchId(sessionId.value)
        store.recordSession(
            SessionRecord(
                sessionId = sessionId,
                batchId = batchId,
                role = role,
                peerLabel = peerId.value,
                startedAtMs = clock(),
                endedAtMs = clock(),
                outcome = outcome
            )
        )
    }

    private val activeJobs = mutableListOf<Job>()

    /**
     * Starts background observation of transport events (control, bulk, stream, connection)
     * and initiates protocol negotiation if this device is the Sender.
     */
    fun start() {
        activeJobs += scope.launch { observeControlMessages() }
        activeJobs += scope.launch { observeBulkProgress() }
        activeJobs += scope.launch { observeConnectionEvents() }

        if (role == DeviceRole.Receiver) {
            activeJobs += scope.launch { observeIncomingStreams() }
        }

        if (role == DeviceRole.Sender) {
            scope.launch {
                dispatch(
                    ReducerEvent.StartNegotiation(
                        ephemeralPeerId = UUID.randomUUID().toString(),
                        displayName = localDisplayName,
                        timestampMs = clock()
                    )
                )
            }
        }
    }

    /**
     * Receiver consent acceptance: dispatches positive offer decision.
     */
    fun acceptOffer() {
        scope.launch {
            dispatch(
                ReducerEvent.SubmitOfferDecision(
                    accepted = true,
                    timestampMs = clock()
                )
            )
        }
    }

    /**
     * Receiver consent rejection: dispatches rejection offer decision.
     */
    fun rejectOffer(reason: String = "USER_REJECTED") {
        scope.launch {
            dispatch(
                ReducerEvent.SubmitOfferDecision(
                    accepted = false,
                    rejectionCode = reason,
                    timestampMs = clock()
                )
            )
        }
    }

    /**
     * User cancellation: terminates ongoing transfer and cleans up unverified artifacts.
     */
    fun cancel(reason: String = "USER_CANCELLED") {
        scope.launch {
            dispatch(
                ReducerEvent.RequestCancel(
                    code = reason,
                    timestampMs = clock()
                )
            )
        }
    }

    /**
     * Cleanly stops all background jobs and releases coordinator resources.
     */
    fun stop() {
        activeJobs.forEach { it.cancel() }
        activeJobs.clear()
    }

    suspend fun dispatch(event: ReducerEvent) {
        val effectsToRun: List<ReducerEffect>
        mutex.withLock {
            val current = _state.value
            val result = TransferReducer.reduce(current, event)
            _state.value = result.state
            effectsToRun = result.effects
        }
        executeEffects(effectsToRun)

        // Automatic offer submission when sender reaches Offering phase
        if (_state.value.role == DeviceRole.Sender &&
            _state.value.phase == SessionPhase.Offering &&
            _state.value.batch == null &&
            senderBatch != null
        ) {
            recordInitialHistory(senderBatch)
            dispatch(ReducerEvent.SubmitOffer(senderBatch, clock()))
        }
    }

    private suspend fun executeEffects(effects: List<ReducerEffect>) {
        for (effect in effects) {
            when (effect) {
                is ReducerEffect.SendControl -> {
                    val bytes = ProtocolCodec.encode(effect.envelope)
                    try {
                        transport.sendControl(peerId, bytes)
                    } catch (e: Exception) {
                        dispatch(ReducerEvent.TransportFailure(TransferFailure.TransferInterrupted("Failed to send control: ${e.message}"), clock()))
                    }
                }

                is ReducerEffect.StartBulkStream -> {
                    startSendingStream(effect.itemId, effect.bulkRef)
                }

                is ReducerEffect.PrepareDestinationStorage -> {
                    prepareDestination(effect.itemId, effect.sizeBytes)
                }

                is ReducerEffect.CommitDestinationFile -> {
                    commitDestination(effect.itemId)
                }

                is ReducerEffect.DeletePartialDestination -> {
                    scope.launch(ioDispatcher) {
                        val staging = stagingHandles.remove(effect.itemId) ?: currentStagingHandle
                        staging?.let { handle ->
                            try { receiveStore.discardStaging(handle) } catch (_: Exception) {}
                        }
                        if (currentStagingHandle == staging) {
                            currentStagingHandle = null
                        }
                    }
                }

                is ReducerEffect.CancelBulkStream -> {
                    scope.launch {
                        try { transport.cancelPayload(effect.bulkRef) } catch (_: Exception) {}
                    }
                }

                is ReducerEffect.CloseTransportChannel -> {
                    scope.launch {
                        // Allow in-flight terminal control messages to flush across the transport
                        kotlinx.coroutines.delay(100)
                        try { transport.disconnect(peerId) } catch (_: Exception) {}
                    }
                }

                is ReducerEffect.UpdateTransferProgress -> {
                    // Progress already reflected in state
                }

                is ReducerEffect.NotifyTerminalOutcome -> {
                    val terminalOutcome = effect.phase.name
                    ensureSessionRecorded(terminalOutcome)
                    historyStore?.updateSessionOutcome(sessionId, terminalOutcome, clock())
                    _state.value.itemStates.forEach { (id, itState) ->
                        if (itState.phase != ItemPhase.Verified) {
                            historyStore?.updateItemOutcome(id, itState.phase.name, itState.transferredBytes, itState.actualDigest)
                        }
                    }
                }
            }
        }
    }

    private fun startSendingStream(itemId: ItemId, bulkRef: BulkRef) {
        val sourceHandle = sourceMap[itemId] ?: return
        scope.launch(ioDispatcher) {
            var streamToClose: HashingInputStream? = null
            try {
                val rawStream = sourceAccess.openSource(sourceHandle)
                val sampler = StreamProgressSampler(
                    clock = clock,
                    onSample = { bytesRead ->
                        scope.launch {
                            dispatch(ReducerEvent.StreamProgress(itemId, bytesRead, clock()))
                        }
                    }
                )
                val hashingStream = HashingInputStream(rawStream) { bytesRead ->
                    sampler.onBytesRead(bytesRead)
                }
                streamToClose = hashingStream

                transport.sendStream(peerId, bulkRef) { hashingStream }.collect { progress ->
                    sampler.onBytesRead(progress.bytesTransferred)
                    if (progress.isComplete) {
                        dispatch(ReducerEvent.TransportPayloadSuccess(itemId, bulkRef, clock()))
                    }
                }

                val actualBytes = hashingStream.bytesRead
                val digest = hashingStream.sha256Digest
                sampler.onComplete(actualBytes)
                dispatch(ReducerEvent.StreamEof(itemId, actualBytes, digest, clock()))
            } catch (e: Exception) {
                if (!_state.value.isTerminal) {
                    dispatch(ReducerEvent.TransportFailure(TransferFailure.TransferInterrupted("Stream transfer failed: ${e.message}"), clock()))
                }
            } finally {
                try { streamToClose?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun prepareDestination(itemId: ItemId, sizeBytes: Long) {
        scope.launch(ioDispatcher) {
            try {
                val item = _state.value.itemStates[itemId]?.item
                val displayName = item?.name ?: "received_file"
                val mimeType = item?.mimeType ?: "application/octet-stream"
                val stagingHandle = receiveStore.prepareStaging(
                    sessionId = sessionId,
                    itemId = itemId,
                    expectedBytes = sizeBytes,
                    displayName = displayName,
                    mimeType = mimeType
                )
                stagingHandles[itemId] = stagingHandle
                currentStagingHandle = stagingHandle
            } catch (e: Exception) {
                dispatch(ReducerEvent.TransportFailure(TransferFailure.InsufficientStorage(sizeBytes, 0), clock()))
            }
        }
    }

    private fun commitDestination(itemId: ItemId) {
        scope.launch(ioDispatcher) {
            val staging = stagingHandles[itemId] ?: currentStagingHandle ?: return@launch
            val itemState = _state.value.itemStates[itemId] ?: return@launch
            val digest = itemState.actualDigest ?: return@launch
            try {
                val committed = receiveStore.commitVerified(staging, digest)
                _committedFiles.add(committed)
                lastCommittedFileHandle = committed
                stagingHandles.remove(itemId)
                if (currentStagingHandle == staging) {
                    currentStagingHandle = null
                }
                historyStore?.updateItemOutcome(itemId, "Verified", itemState.transferredBytes, digest)
                dispatch(ReducerEvent.LocalCommitSuccess(itemId, clock()))
            } catch (e: Exception) {
                dispatch(ReducerEvent.TransportFailure(TransferFailure.WriteFailure("Commit failed: ${e.message}"), clock()))
            }
        }
    }

    private suspend fun observeControlMessages() {
        transport.observeControl().collect { msg ->
            if (msg.peerId != peerId) return@collect

            val now = clock()

            // 1. Rate limiting check (Threat T08: Token bucket <=20 msg/s sustained, burst 40)
            val rateResult = rateLimiter?.tryAcquire(nowMs = now)
            if (rateResult is RateLimitResult.Throttled) {
                dispatch(
                    ReducerEvent.TransportFailure(
                        TransferFailure.LimitExceeded(rateResult.reason),
                        now
                    )
                )
                return@collect
            }

            // 2. Decode envelope (Fail closed on malformed message)
            val envelope = try {
                ProtocolCodec.decode(msg.payload)
            } catch (e: Exception) {
                dispatch(
                    ReducerEvent.TransportFailure(
                        TransferFailure.MalformedMessage("Failed to decode envelope: ${e.message}"),
                        now
                    )
                )
                return@collect
            }

            // 3. Sequencer & replay protection (Threat T03: Monotonic seq, bounded buffer 8, 2s gap timeout, LRU 256)
            val seqResult = sequencer.processInbound(envelope, msg.payload, now)
            when (seqResult) {
                is SequencerResult.Deliver -> {
                    for (env in seqResult.envelopes) {
                        dispatchEnvelope(env, now)
                    }
                }
                is SequencerResult.Buffered -> {
                    // Out-of-order envelope buffered waiting for missing sequence numbers
                    activeJobs += scope.launch {
                        kotlinx.coroutines.delay(sequencer.gapTimeoutMs + 50)
                        if (!_state.value.isTerminal) {
                            val violation = sequencer.checkTimeout(clock())
                            if (violation != null) {
                                dispatch(ReducerEvent.TransportFailure(violation.failure, clock()))
                            }
                        }
                    }
                }
                is SequencerResult.DuplicateIgnored -> {
                    // Exact duplicate safely ignored (T03 idempotent replay protection)
                }
                is SequencerResult.Violation -> {
                    dispatch(ReducerEvent.TransportFailure(seqResult.failure, now))
                }
            }
        }
    }

    private suspend fun dispatchEnvelope(envelope: Envelope, now: Long) {
        try {
            when (envelope.type) {
                MessageTypes.HELLO -> {
                    val hello = ProtocolCodec.decodeBody<dev.glide.core.protocol.HelloBody>(envelope)
                    dispatch(ReducerEvent.HelloReceived(hello, envelope.messageId, envelope.seq, now))
                }
                MessageTypes.HELLO_ACK -> {
                    val ack = ProtocolCodec.decodeBody<dev.glide.core.protocol.HelloAckBody>(envelope)
                    dispatch(ReducerEvent.HelloAckReceived(ack, envelope.messageId, envelope.seq, now))
                }
                MessageTypes.OFFER_BEGIN -> {
                    val begin = ProtocolCodec.decodeBody<dev.glide.core.protocol.OfferBeginBody>(envelope)
                    dispatch(ReducerEvent.OfferBeginReceived(begin, envelope.messageId, envelope.seq, now))
                }
                MessageTypes.OFFER_PAGE -> {
                    val page = ProtocolCodec.decodeBody<dev.glide.core.protocol.OfferPageBody>(envelope)
                    dispatch(ReducerEvent.OfferPageReceived(page, envelope.messageId, envelope.seq, now))
                }
                MessageTypes.OFFER_END -> {
                    val end = ProtocolCodec.decodeBody<dev.glide.core.protocol.OfferEndBody>(envelope)
                    dispatch(ReducerEvent.OfferEndReceived(end, envelope.messageId, envelope.seq, now))
                    _state.value.batch?.let { recordInitialHistory(it) }
                }
                MessageTypes.OFFER_DECISION -> {
                    val decision = ProtocolCodec.decodeBody<dev.glide.core.protocol.OfferDecisionBody>(envelope)
                    val bulkRef = if (role == DeviceRole.Sender && decision.accepted) {
                        val firstItem = _state.value.batch?.items?.firstOrNull()
                        val handle = firstItem?.let { sourceMap[it.id] }
                        if (handle != null) {
                            val stream = sourceAccess.openSource(handle)
                            transport.prepareStream { stream }
                        } else null
                    } else null
                    dispatch(ReducerEvent.OfferDecisionReceived(decision, envelope.messageId, envelope.seq, now, bulkRef))
                }
                MessageTypes.ITEM_START -> {
                    val start = ProtocolCodec.decodeBody<dev.glide.core.protocol.ItemStartBody>(envelope)
                    transport.registerExpectedStream(start.bulkRef)
                    dispatch(ReducerEvent.ItemStartReceived(start, envelope.messageId, envelope.seq, now))
                }
                MessageTypes.ITEM_READY -> {
                    val ready = ProtocolCodec.decodeBody<dev.glide.core.protocol.ItemReadyBody>(envelope)
                    val curItem = _state.value.currentItemState
                    curItem?.bulkRef?.let { transport.markItemReady(it) }
                    dispatch(ReducerEvent.ItemReadyReceived(ready, envelope.messageId, envelope.seq, now))
                }
                MessageTypes.ITEM_END -> {
                    val end = ProtocolCodec.decodeBody<dev.glide.core.protocol.ItemEndBody>(envelope)
                    dispatch(ReducerEvent.ItemEndReceived(end, envelope.messageId, envelope.seq, now))
                }
                MessageTypes.ITEM_RESULT -> {
                    val result = ProtocolCodec.decodeBody<dev.glide.core.protocol.ItemResultBody>(envelope)
                    val curItem = _state.value.itemStates[result.itemId]
                    val outcome = if (result.status == "verified") "Verified" else "Failed"
                    historyStore?.updateItemOutcome(result.itemId, outcome, curItem?.transferredBytes, curItem?.actualDigest)

                    val nextBulkRef = if (role == DeviceRole.Sender && result.status == "verified") {
                        val nextIdx = _state.value.currentItemIndex + 1
                        val nextItem = _state.value.batch?.items?.getOrNull(nextIdx)
                        val handle = nextItem?.let { sourceMap[it.id] }
                        if (handle != null) {
                            val stream = sourceAccess.openSource(handle)
                            transport.prepareStream { stream }
                        } else null
                    } else null

                    dispatch(ReducerEvent.ItemResultReceived(result, envelope.messageId, envelope.seq, now, nextBulkRef))
                }
                MessageTypes.BATCH_END -> {
                    val batchEnd = ProtocolCodec.decodeBody<dev.glide.core.protocol.BatchEndBody>(envelope)
                    dispatch(ReducerEvent.BatchEndReceived(batchEnd, envelope.messageId, envelope.seq, now))
                }
                MessageTypes.BATCH_RESULT -> {
                    val batchResult = ProtocolCodec.decodeBody<dev.glide.core.protocol.BatchResultBody>(envelope)
                    dispatch(ReducerEvent.BatchResultReceived(batchResult, envelope.messageId, envelope.seq, now))
                }
                MessageTypes.CANCEL -> {
                    val cancel = ProtocolCodec.decodeBody<dev.glide.core.protocol.CancelBody>(envelope)
                    dispatch(ReducerEvent.CancelReceived(cancel, envelope.messageId, envelope.seq, now))
                }
                else -> {
                    // Unknown envelope types safely ignored
                }
            }
        } catch (e: Exception) {
            dispatch(
                ReducerEvent.TransportFailure(
                    TransferFailure.MalformedMessage("Malformed envelope body: ${e.message}"),
                    now
                )
            )
        }
    }

    private suspend fun observeBulkProgress() {
        transport.observeBulkProgress().collect { progress ->
            val curItem = _state.value.itemStates.values.firstOrNull { it.bulkRef == progress.bulkRef }
                ?: _state.value.currentItemState
                ?: return@collect
            if (curItem.bulkRef == progress.bulkRef) {
                dispatch(ReducerEvent.StreamProgress(curItem.item.id, progress.bytesTransferred, clock()))
                if (progress.isComplete) {
                    dispatch(ReducerEvent.TransportPayloadSuccess(curItem.item.id, progress.bulkRef, clock()))
                }
            }
        }
    }

    private suspend fun observeIncomingStreams() {
        transport.observeIncomingStream().collect { incoming ->
            if (incoming.peerId != peerId) return@collect

            scope.launch(ioDispatcher) {
                // Security check T04/T07/T17: Strict consent ordering
                val curPhase = _state.value.phase
                if (curPhase != SessionPhase.PreparingItem && curPhase != SessionPhase.TransferringItem) {
                    dispatch(
                        ReducerEvent.TransportFailure(
                            TransferFailure.ProtocolOrder("Stream packet received before explicit offer acceptance (phase: $curPhase)"),
                            clock()
                        )
                    )
                    return@launch
                }

                val curItem = _state.value.itemStates.values.firstOrNull { it.bulkRef == incoming.bulkRef }
                    ?: _state.value.currentItemState
                    ?: return@launch

                var staging = stagingHandles[curItem.item.id] ?: currentStagingHandle
                var attempts = 0
                while (staging == null && attempts < 50) {
                    kotlinx.coroutines.delay(10)
                    staging = stagingHandles[curItem.item.id] ?: currentStagingHandle
                    attempts++
                }

                if (staging == null) {
                    dispatch(
                        ReducerEvent.TransportFailure(
                            TransferFailure.DestinationUnavailable("Staging storage unavailable for item ${curItem.item.id}"),
                            clock()
                        )
                    )
                    return@launch
                }

                try {
                    val sampler = StreamProgressSampler(
                        clock = clock,
                        onSample = { bytesRead ->
                            scope.launch {
                                dispatch(ReducerEvent.StreamProgress(curItem.item.id, bytesRead, clock()))
                            }
                        }
                    )
                    val hashResult = receiveStore.openStagingOutput(staging).use { out ->
                        StreamHasher.copyAndHash(
                            inputStream = incoming.stream,
                            outputStream = out,
                            expectedBytes = curItem.item.sizeBytes,
                            onProgress = { bytesRead ->
                                sampler.onBytesRead(bytesRead)
                            }
                        )
                    }
                    sampler.onComplete(hashResult.sizeBytes)
                    dispatch(ReducerEvent.StreamEof(curItem.item.id, hashResult.sizeBytes, hashResult.digest, clock()))
                } catch (e: IllegalStateException) {
                    dispatch(ReducerEvent.TransportFailure(TransferFailure.LimitExceeded("Receive stream limit exceeded: ${e.message}"), clock()))
                } catch (e: Exception) {
                    dispatch(ReducerEvent.TransportFailure(TransferFailure.WriteFailure("Receive stream failed: ${e.message}"), clock()))
                }
            }
        }
    }

    private suspend fun observeConnectionEvents() {
        transport.observeConnectionEvents().collect { event ->
            val now = clock()
            when (event) {
                is ConnectionEvent.Disconnected -> {
                    if (event.peerId == peerId && !_state.value.isTerminal) {
                        dispatch(ReducerEvent.TransportFailure(TransferFailure.PeerLost("Peer disconnected"), now))
                    }
                }
                is ConnectionEvent.Failed -> {
                    if (event.peerId == peerId && !_state.value.isTerminal) {
                        dispatch(ReducerEvent.TransportFailure(TransferFailure.TransferInterrupted("Connection failed: ${event.reason}"), now))
                    }
                }
                else -> {}
            }
        }
    }
}
