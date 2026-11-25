package dev.glide.core.coordinator

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.CommittedFileHandle
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.MessageId
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.StagingHandle
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferFailure
import dev.glide.core.domain.TransferItem
import dev.glide.core.identity.PersistentTrustManager
import dev.glide.core.identity.VerifiedPeerIdentity
import dev.glide.core.ports.AuthenticatedChannelContextProvider
import dev.glide.core.ports.ConnectionEvent
import dev.glide.core.ports.HistoryStore
import dev.glide.core.ports.ItemRecord
import dev.glide.core.ports.ReceiveStore
import dev.glide.core.ports.SessionRecord
import dev.glide.core.ports.InputStreamProvider
import dev.glide.core.ports.StreamExpectation
import dev.glide.core.ports.SourceAccess
import dev.glide.core.ports.TransferTransport
import dev.glide.core.protocol.Envelope
import dev.glide.core.protocol.BatchEndBody
import dev.glide.core.protocol.BatchResultBody
import dev.glide.core.protocol.IdentityResumeTranscript
import dev.glide.core.protocol.OfferDecisionBody
import dev.glide.core.protocol.IdentityChallengeDto
import dev.glide.core.protocol.IdentityResponseDto
import dev.glide.core.protocol.MessageTypes
import dev.glide.core.protocol.NegotiationConstants
import dev.glide.core.protocol.ProtocolCodec
import dev.glide.core.protocol.ResumeAcceptDto
import dev.glide.core.protocol.ResumeOfferMetadata
import dev.glide.core.protocol.ResumeRejectCodes
import dev.glide.core.protocol.ResumeRejectDto
import dev.glide.core.protocol.ResumeRequestDto
import dev.glide.core.protocol.ResumeResultDto
import dev.glide.core.protocol.ResumeStreamReadyDto
import dev.glide.core.protocol.ResumeStreamStartDto
import dev.glide.core.protocol.WireErrorCodes
import dev.glide.core.resume.ResumeAuthorityLookup
import dev.glide.core.resume.ResumeBatchManifest
import dev.glide.core.resume.ResumeBatchReconnect
import dev.glide.core.resume.ResumeReconnectPlan
import dev.glide.core.resume.ResumeReconnectState
import dev.glide.core.resume.ResumeRequestAuthority
import dev.glide.core.resume.ResumeSenderReadySource
import dev.glide.core.resume.ResumeSourcePreflight
import dev.glide.core.resume.ResumeStreamSupport
import dev.glide.core.security.ProtocolRateLimiter
import dev.glide.core.security.ProtocolSequencer
import dev.glide.core.security.RateLimitResult
import dev.glide.core.security.SequencerResult
import dev.glide.core.stream.HashingInputStream
import dev.glide.core.stream.StreamHasher
import dev.glide.core.stream.StreamProgressSampler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/**
 * Coordinates deterministic transfer protocol execution between [TransferReducer], [TransferTransport],
 * [SourceAccess], and [ReceiveStore] for one-file (and sequential multi-file) transfers (GLIDE-015).
 *
 * Runs all state transitions sequentially using a Mutex, handles all transport and stream callbacks,
 * executes reducer effects, records history in [HistoryStore], and exposes immutable [SessionState] for the UI.
 */
class TransferCoordinator(
    sessionId: SessionId? = null,
    val role: DeviceRole,
    val peerId: PeerId,
    val localDisplayName: String = "Glide Device",
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
    val sequencer: ProtocolSequencer = ProtocolSequencer(clock = clock),
    val persistentTrustManager: PersistentTrustManager? = null,
    val expectedPeerFingerprintHex: String? = null,
    val onVerifiedPeerIdentity: ((VerifiedPeerIdentity) -> Unit)? = null,
    val resumeSourcePreflight:
        (suspend (SourceHandle) -> ResumeSourcePreflight)? = null,
    val persistSenderResumeAuthority:
        (suspend (
            SessionId,
            dev.glide.core.domain.TransferItem,
            SourceHandle,
            String,
            ResumeSourcePreflight
        ) -> Unit)? = null,
    val persistReceiverResumeAuthority:
        (suspend (
            SessionId,
            dev.glide.core.domain.TransferItem,
            String
        ) -> Unit)? = null,
    val recordReceiverResumeChunk:
        (suspend (
            SessionId,
            ItemId,
            Long,
            ByteArray
        ) -> Boolean)? = null,
    val purgeResumeItemAuthority:
        (suspend (
            SessionId,
            ItemId
        ) -> Unit)? = null,
    resumeReconnectPlan:
        ResumeReconnectPlan? = null,
    val loadReceiverResumeAuthority:
        (suspend (
            SessionId,
            ItemId,
            String
        ) -> ResumeAuthorityLookup<
            ResumeRequestAuthority
                .ReceiverJournal
        >)? = null,
    val loadSenderResumeAuthority:
        (suspend (
            SessionId,
            ItemId,
            String
        ) -> ResumeAuthorityLookup<
            ResumeSenderReadySource
        >)? = null,
    val finalizeReceiverResumeItem:
        (suspend (
            SessionId,
            SessionId,
            ItemId,
            String
        ) -> CommittedFileHandle)? = null,
    val enableTrueResumeCapability: Boolean = false,
    private val persistAcceptedResumeBatch: (suspend (ResumeBatchManifest, DeviceRole) -> Unit)? = null,
    private val recordResumeBatchCommit: (suspend (SessionId, ItemId, Long, dev.glide.core.domain.Sha256Digest) -> Unit)? = null,
    private val resumeBatch: ResumeBatchReconnect? = null,
    private val purgeAcceptedResumeBatch: (suspend (SessionId) -> Unit)? = null,
    private val recoverResumeCommitReceipts: (suspend (ResumeBatchManifest, Int) -> Int)? = null
) {
    private var resumeReconnectPlan = resumeReconnectPlan
    private var resumeBatchStarted = false
    private var resumeBatchIndex = 0
    private var resumeReceiverVerifiedCount = resumeBatch?.receiverVerifiedCount ?: 0
    private var emptyResumeRequestId: String? = null
    private var approvedResumeSourceHashes: List<String> = emptyList()
    private var lastResumeProgressNanos = 0L
    private class ResumeJournalException(
        message: String
    ) : IllegalStateException(message)

    @Volatile
    private var boundSessionId: SessionId? = sessionId

    val sessionId: SessionId
        get() = boundSessionId ?: _state.value.sessionId

    init {
        resumeReconnectPlan?.let { plan ->
            require(
                expectedPeerFingerprintHex ==
                    plan.expectedPeerFingerprintHex
            ) {
                "Resume reconnect requires the exact expected enrolled peer fingerprint"
            }
            require(
                when (plan) {
                    is ResumeReconnectPlan.Sender ->
                        role == DeviceRole.Sender
                    is ResumeReconnectPlan.Receiver ->
                        role == DeviceRole.Receiver
                }
            ) {
                "Resume reconnect plan role does not match coordinator role"
            }
        }

        if (role == DeviceRole.Sender) {
            requireNotNull(sessionId) { "Sender must specify an explicit non-null sessionId" }
            if (senderBatch != null) {
                val missing = senderBatch.items.filterNot { sourceMap.containsKey(it.id) }
                require(missing.isEmpty()) {
                    "sourceMap is missing handles for items: ${missing.map { it.name }}"
                }
            }
        }
    }

    private val mutex = Mutex()
    private val _state = MutableStateFlow(
        SessionState(
            sessionId = sessionId ?: SessionId(UUID.randomUUID().toString()),
            role = role
        )
    )
    val state: StateFlow<SessionState> = _state.asStateFlow()

    @Volatile
    var authenticatedPeerFingerprintHex: String? = null
        private set

    @Volatile
    private var remoteDisplayName: String = "Glide Device"

    private val trustedSessionHandshake: TrustedSessionHandshake? by lazy {
        persistentTrustManager?.let { manager ->
            TrustedSessionHandshake(
                role = role,
                sessionId = { this@TransferCoordinator.sessionId },
                trustManager = manager,
                expectedPeerFingerprintHex =
                    expectedPeerFingerprintHex,
                channelContext = {
                    (transport as?
                        AuthenticatedChannelContextProvider)
                        ?.authenticatedChannelContext(
                            peerId
                        )
                },
                negotiatedCapabilities = {
                    _state.value
                        .negotiated
                        ?.capabilities
                        ?: emptySet()
                },
                remoteDisplayName = {
                    remoteDisplayName
                },
                onVerifiedPeerIdentity =
                    onVerifiedPeerIdentity
            )
        }
    }

    private var identityTimeoutJob: Job? = null
    private var resumeTimeoutJob: Job? = null
    private var pendingResumeRequest:
        Envelope? = null
    private var pendingReceiverResumeJournal:
        ResumeRequestAuthority
            .ReceiverJournal? = null
    private var pendingSenderResumeStreamStart:
        ResumeStreamStartDto? = null
    private var pendingReceiverResumeStreamStart:
        ResumeStreamStartDto? = null
    private var pendingResumeStreamBulkRef:
        BulkRef? = null

    private val _resumeReconnectState =
        MutableStateFlow<
            ResumeReconnectState
        >(ResumeReconnectState.Idle)
    val resumeReconnectState:
        StateFlow<ResumeReconnectState> =
        _resumeReconnectState
            .asStateFlow()

    private val stagingHandles: MutableMap<ItemId, StagingHandle> = Collections.synchronizedMap(mutableMapOf())
    private var currentStagingHandle: StagingHandle? = null

    private val _committedFiles: MutableList<CommittedFileHandle> = Collections.synchronizedList(mutableListOf())
    val committedFiles: List<CommittedFileHandle> get() = _committedFiles.toList()
    private val _committedItemFiles = ConcurrentHashMap<ItemId, CommittedFileHandle>()
    val committedItemFiles: Map<ItemId, CommittedFileHandle> get() = _committedItemFiles.toMap()
    var lastCommittedFileHandle: CommittedFileHandle? = null
        private set

    private var sessionHistoryRecorded = false

    private suspend fun recordInitialHistory(batch: TransferBatch) {
        val shouldRecord = mutex.withLock {
            if (sessionHistoryRecorded) {
                false
            } else {
                sessionHistoryRecorded = true
                true
            }
        }
        if (!shouldRecord) return
        val store = historyStore ?: return
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
                    offeredBytes = item.sizeBytes,
                    relativePath = item.relativePath
                )
            )
        }
    }

    private suspend fun ensureSessionRecorded(outcome: String, failureCode: String? = null) {
        val shouldRecord = mutex.withLock {
            if (sessionHistoryRecorded) {
                false
            } else {
                sessionHistoryRecorded = true
                true
            }
        }
        if (!shouldRecord) return
        val store = historyStore ?: return
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
                outcome = outcome,
                failureCode = failureCode
            )
        )
    }

    private val activeJobs: MutableList<Job> = Collections.synchronizedList(mutableListOf<Job>())
    private val terminalCleanupLock = Any()
    private var terminalCleanupBarrier: CompletableDeferred<Unit>? = null

    private fun launchTracked(
        context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        val job = scope.launch(context, block = block)
        activeJobs += job
        job.invokeOnCompletion { activeJobs.remove(job) }
        return job
    }

    /**
     * Starts background observation of transport events (control, bulk, stream, connection)
     * and initiates protocol negotiation if this device is the Sender.
     */
    fun start() {
        launchTracked { observeControlMessages() }
        launchTracked { observeBulkProgress() }
        launchTracked { observeConnectionEvents() }
        if (resumeBatch != null) {
            launchTracked {
                _resumeReconnectState.collect { resume ->
                    val code = when (resume) {
                        is ResumeReconnectState.Failed -> resume.code
                        is ResumeReconnectState.Rejected -> resume.code
                        else -> return@collect
                    }
                    if (!_state.value.isTerminal) {
                        val failure = if (code == ResumeRejectCodes.FILE_CHANGED)
                            TransferFailure.SourceUnavailable("The original file changed or is unavailable")
                        else TransferFailure.TransferInterrupted("The approved transfer could not be resumed")
                        dispatch(ReducerEvent.TransportFailure(failure, clock()))
                    }
                }
            }
        }

        if (role == DeviceRole.Receiver) {
            launchTracked { observeIncomingStreams() }
        }

        if (role == DeviceRole.Sender) {
            launchTracked {
                dispatch(
                    ReducerEvent.StartNegotiation(
                        ephemeralPeerId = UUID.randomUUID().toString(),
                        displayName = localDisplayName,
                        timestampMs = clock(),
                        optionalCapabilities =
                            localOptionalCapabilities()
                    )
                )
            }
        }
    }

    /**
     * Receiver consent acceptance: verifies batch space reservation before dispatching positive offer decision (LAN-022).
     */
     fun acceptOffer() {
        launchTracked {
            if (resumeBatch != null) {
                respondToResumeConsent(true)
                return@launchTracked
            }
            var manifestDigest: dev.glide.core.domain.Sha256Digest? = null
            val batch =
                _state.value.batch
            if (batch != null) {
                val hasSpace =
                    withContext(
                        ioDispatcher
                    ) {
                        receiveStore
                            .hasAvailableSpace(
                                batch.totalBytes
                            )
                    }
                if (!hasSpace) {
                    dispatch(
                        ReducerEvent
                            .SubmitOfferDecision(
                                accepted = false,
                                rejectionCode =
                                    "INSUFFICIENT_STORAGE",
                                timestampMs =
                                    clock()
                            )
                    )
                    return@launchTracked
                }

                if (isTrueResumeNegotiated()) {
                    val persist =
                        persistReceiverResumeAuthority
                    if (persist == null ||
                        recordReceiverResumeChunk ==
                            null
                    ) {
                        dispatch(
                            ReducerEvent.TransportFailure(
                                TransferFailure
                                    .UnsupportedProtocol(
                                        "resume-v1 was negotiated without receiver journal authority"
                                    ),
                                clock()
                            )
                        )
                        return@launchTracked
                    }

                    val peerFingerprint =
                        authenticatedPeerFingerprintHex
                    if (peerFingerprint == null) {
                        dispatch(
                            ReducerEvent.TransportFailure(
                                TransferFailure
                                    .AuthenticationMismatch(
                                        "resume-v1 requires a mutually authenticated persistent peer"
                                    ),
                                clock()
                            )
                        )
                        return@launchTracked
                    }

                    try {
                        for (item in
                            batch.items
                        ) {
                            if (itemHasResumeMetadata(
                                    item
                                )
                            ) {
                                persist(
                                    sessionId,
                                    item,
                                    peerFingerprint
                                )
                            }
                        }
                        manifestDigest = saveAcceptedResumeBatch(batch)
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        dispatch(
                            ReducerEvent.TransportFailure(
                                TransferFailure
                                    .WriteFailure(
                                        "Failed to initialize durable resume journal: " +
                                            (
                                                error.message
                                                    ?: error
                                                        .javaClass
                                                        .simpleName
                                                )
                                    ),
                                clock()
                            )
                        )
                        return@launchTracked
                    }
                }
            }

            dispatch(
                ReducerEvent.SubmitOfferDecision(
                    accepted = true,
                    timestampMs = clock(),
                    resumeManifestDigest = manifestDigest
                )
            )
        }
    }

    /**
     * Receiver consent rejection: dispatches rejection offer decision.
     */
    fun rejectOffer(reason: String = "USER_REJECTED") {
        launchTracked {
            if (resumeBatch != null) {
                respondToResumeConsent(false)
                return@launchTracked
            }
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
        launchTracked {
            dispatch(
                ReducerEvent.RequestCancel(
                    code = reason,
                    timestampMs = clock()
                )
            )
        }
    }

    /**
     * Requests user cancellation and suspends until all terminal storage/transport effects finish.
     * Used by service lifecycle teardown so the foreground owner is not destroyed before cleanup.
     */
    suspend fun cancelAndAwaitCleanup(reason: String = "USER_CANCELLED") {
        if (!_state.value.isTerminal) {
            dispatch(
                ReducerEvent.RequestCancel(
                    code = reason,
                    timestampMs = clock()
                )
            )
        }
        awaitTerminalCleanup()
        resumeBatch?.let { purgeAcceptedResumeBatch?.invoke(it.manifest.sessionId) }
    }

    /**
     * Converts service/process-local ownership loss into an Interrupted terminal outcome and waits
     * for best-effort partial-file and transport cleanup. Sudden process death may prevent this from
     * finishing; startup reconciliation remains the durable recovery authority in that case.
     */
    suspend fun interruptAndAwaitCleanup(reason: String = "SERVICE_STOPPED") {
        if (!_state.value.isTerminal) {
            dispatch(
                ReducerEvent.TransportFailure(
                    TransferFailure.TransferInterrupted(reason),
                    clock()
                )
            )
        }
        awaitTerminalCleanup()
    }

    suspend fun awaitTerminalCleanup() {
        val barrier = synchronized(terminalCleanupLock) { terminalCleanupBarrier }
        barrier?.await()
    }

    private data class PreparedSource(
        val itemId: ItemId,
        val stream: HashingInputStream
    )

    private val preparedSources = mutableMapOf<BulkRef, PreparedSource>()
    private val preparedSourcesLock = Any()

    private suspend fun prepareSourceItem(item: dev.glide.core.domain.TransferItem): BulkRef? {
        val handle = sourceMap[item.id] ?: return null
        return withContext(ioDispatcher) {
            try {
                val rawStream = sourceAccess.openSource(handle)
                val hashingStream = HashingInputStream(rawStream, expectedBytes = item.sizeBytes)
                val bulkRef = transport.prepareStream { hashingStream }
                if (bulkRef != null) {
                    synchronized(preparedSourcesLock) {
                        preparedSources[bulkRef] = PreparedSource(item.id, hashingStream)
                    }
                } else {
                    try { hashingStream.close() } catch (_: Exception) {}
                }
                bulkRef
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun closePreparedSources() {
        val toClose = synchronized(preparedSourcesLock) {
            val list = preparedSources.values.map { it.stream }
            preparedSources.clear()
            list
        }
        toClose.forEach { s ->
            try { s.close() } catch (_: Exception) {}
        }
    }

    /**
     * Cleanly stops all background jobs and releases coordinator resources.
     */
    fun stop() {
        identityTimeoutJob?.cancel()
        identityTimeoutJob = null
        resumeTimeoutJob?.cancel()
        resumeTimeoutJob = null
        val jobsToCancel = synchronized(activeJobs) {
            activeJobs.toList().also { activeJobs.clear() }
        }
        jobsToCancel.forEach { it.cancel() }
        closePreparedSources()
    }

    suspend fun dispatch(event: ReducerEvent) {
        val effectsToRun: List<ReducerEffect>
        var terminalBarrierForThisDispatch: CompletableDeferred<Unit>? = null
        mutex.withLock {
            val current = _state.value
            val result = TransferReducer.reduce(current, event)
            if (!current.isTerminal && result.state.isTerminal) {
                terminalBarrierForThisDispatch = CompletableDeferred()
                synchronized(terminalCleanupLock) {
                    terminalCleanupBarrier = terminalBarrierForThisDispatch
                }
            }
            _state.value = result.state
            effectsToRun = result.effects
        }

        try {
            executeEffects(effectsToRun)

            if (_state.value.isTerminal) {
                closePreparedSources()
            }
        } finally {
            terminalBarrierForThisDispatch?.complete(Unit)
        }

        maybeSubmitSenderOffer()
    }

    private fun localOptionalCapabilities(): List<String> {
        val optional =
            (
                trustedSessionHandshake
                    ?.localOptionalCapabilities()
                    ?: NegotiationConstants
                        .OPTIONAL_CAPABILITIES
                        .toList()
                ).toMutableSet()

        val resumeRuntimeAvailable =
            when (
                val plan =
                    resumeReconnectPlan
            ) {
                is ResumeReconnectPlan.Sender ->
                    role ==
                        DeviceRole.Sender &&
                        loadSenderResumeAuthority !=
                            null
                is ResumeReconnectPlan.Receiver ->
                    role ==
                        DeviceRole.Receiver &&
                        loadReceiverResumeAuthority !=
                            null &&
                        recordReceiverResumeChunk !=
                            null &&
                        finalizeReceiverResumeItem !=
                            null
                null ->
                    when (role) {
                        DeviceRole.Sender ->
                            resumeSourcePreflight !=
                                null &&
                                persistSenderResumeAuthority !=
                                null
                        DeviceRole.Receiver ->
                            persistReceiverResumeAuthority !=
                                null &&
                                recordReceiverResumeChunk !=
                                null
                    }
            }

        if (enableTrueResumeCapability &&
            resumeRuntimeAvailable &&
            NegotiationConstants
                .CAPABILITY_PERSISTENT_IDENTITY_V1 in
                optional
        ) {
            optional +=
                NegotiationConstants
                    .CAPABILITY_TRUE_RESUME_V1
        }

        return optional.sorted()
    }

    private fun isTrueResumeNegotiated(): Boolean =
        NegotiationConstants
            .CAPABILITY_TRUE_RESUME_V1 in
            (
                _state.value.negotiated
                    ?.capabilities
                    ?: emptySet()
                )

    private fun itemHasResumeMetadata(
        item:
            dev.glide.core.domain.TransferItem
    ): Boolean =
        item.resumeChunkSize != null &&
            item.resumeChunkRootHashHex !=
                null

    private fun identityReadyForOffers(): Boolean {
        if (expectedPeerFingerprintHex != null &&
            trustedSessionHandshake == null
        ) {
            return false
        }

        return trustedSessionHandshake
            ?.isReadyForOffers()
            ?: true
    }

    private suspend fun maybeSubmitSenderOffer() {
        if (resumeReconnectPlan != null || resumeBatch != null) {
            return
        }
        val batch =
            senderBatch ?: return
        if (_state.value.role !=
                DeviceRole.Sender ||
            _state.value.phase !=
                SessionPhase.Offering ||
            _state.value.batch != null ||
            !identityReadyForOffers()
        ) {
            return
        }

        val negotiatedCapabilities =
            _state.value.negotiated
                ?.capabilities
                ?: emptySet()
        val offerBatch =
            if (NegotiationConstants
                    .CAPABILITY_TRUE_RESUME_V1 in
                negotiatedCapabilities
            ) {
                try {
                    prepareResumeCapableSenderBatch(
                        batch
                    )
                } catch (error: Exception) {
                    dispatch(
                        ReducerEvent.TransportFailure(
                            TransferFailure
                                .SourceUnavailable(
                                    "Resume source preflight failed: " +
                                        (
                                            error.message
                                                ?: error
                                                    .javaClass
                                                    .simpleName
                                            )
                                ),
                            clock()
                        )
                    )
                    return
                }
            } else {
                batch
            }

        recordInitialHistory(
            offerBatch
        )
        dispatch(
            ReducerEvent.SubmitOffer(
                offerBatch,
                clock()
            )
        )
    }

    private suspend fun prepareResumeCapableSenderBatch(
        batch: TransferBatch
    ): TransferBatch {
        if (!enableTrueResumeCapability) {
            throw IllegalStateException(
                "resume-v1 was negotiated while the local runtime gate is disabled"
            )
        }

        val preflight =
            resumeSourcePreflight
                ?: throw IllegalStateException(
                    "resume-v1 source preflight authority is unavailable"
                )
        val persist =
            persistSenderResumeAuthority
                ?: throw IllegalStateException(
                    "resume-v1 durable sender journal authority is unavailable"
                )
        val peerFingerprint =
            authenticatedPeerFingerprintHex
                ?: throw IllegalStateException(
                    "resume-v1 requires a mutually authenticated persistent peer"
                )

        val enriched =
            mutableListOf<
                dev.glide.core.domain.TransferItem
            >()

        for (item in batch.items) {
            val handle =
                sourceMap[item.id]
                    ?: throw IllegalStateException(
                        "Source handle missing for resumable item ${item.id.value}"
                    )
            if (item.sizeBytes == 0L) {
                check(sourceAccess.validateSource(handle).sizeBytes == 0L) {
                    "Empty source changed before resumable offer"
                }
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest()
                enriched += item.copy(digest = dev.glide.core.domain.Sha256Digest.fromByteArray(digest))
                continue
            }
            val snapshot =
                preflight(handle)
            if (snapshot.totalBytes !=
                item.sizeBytes
            ) {
                throw IllegalStateException(
                    "Source size changed before resumable offer for ${item.name}"
                )
            }

            val resumableItem =
                item.copy(
                    digest = dev.glide.core.domain.Sha256Digest(snapshot.wholeFileSha256Hex),
                    resumeChunkSize =
                        snapshot.chunkSize,
                    resumeChunkRootHashHex =
                        snapshot
                            .chunkRootHashHex
                )

            persist(
                sessionId,
                resumableItem,
                handle,
                peerFingerprint,
                snapshot
            )
            enriched +=
                resumableItem
        }

        return batch.copy(
            items = enriched
        )
    }

    private suspend fun saveAcceptedResumeBatch(
        batch: TransferBatch,
        expectedDigest: dev.glide.core.domain.Sha256Digest? = null
    ): dev.glide.core.domain.Sha256Digest? {
        if (!isTrueResumeNegotiated()) return null
        val manager = checkNotNull(persistentTrustManager)
        val peerFingerprint = checkNotNull(authenticatedPeerFingerprintHex)
        if (manager.trustedPeerByFingerprint(peerFingerprint) == null) {
            throw TrustedSessionAuthenticationException("Resume peer is no longer trusted")
        }
        val local = dev.glide.core.domain.Sha256Digest(manager.localFingerprintHex())
        val peer = dev.glide.core.domain.Sha256Digest(peerFingerprint)
        val sender = if (role == DeviceRole.Sender) local else peer
        val receiver = if (role == DeviceRole.Receiver) local else peer
        val manifest = ResumeBatchManifest.fromAcceptedOffer(sessionId, sender, receiver, batch)
        val digest = manifest?.digest()
        if (expectedDigest != null && digest != expectedDigest) {
            throw TrustedSessionAuthenticationException("Accepted resume manifest differs from the authenticated offer")
        }
        val persist = persistAcceptedResumeBatch ?: return null
        if (manifest != null) persist(manifest, role)
        return digest
    }

    private fun scheduleIdentityTimeoutIfNeeded() {
        val negotiated =
            _state.value.negotiated
                ?.capabilities
                ?: emptySet()
        if (NegotiationConstants
                .CAPABILITY_PERSISTENT_IDENTITY_V1 !in
            negotiated
        ) {
            return
        }

        identityTimeoutJob?.cancel()
        identityTimeoutJob =
            scope.launch {
                kotlinx.coroutines.delay(
                    30_000L
                )
                val handshake =
                    trustedSessionHandshake
                if (!_state.value.isTerminal &&
                    handshake != null &&
                    !handshake.isComplete
                ) {
                    dispatch(
                        ReducerEvent.TransportFailure(
                            TransferFailure.AuthenticationMismatch(
                                "Persistent identity authentication timed out."
                            ),
                            clock()
                        )
                    )
                }
            }
    }

    private fun cancelIdentityTimeoutIfComplete() {
        val handshake =
            trustedSessionHandshake
        if (handshake?.isComplete == true) {
            authenticatedPeerFingerprintHex =
                handshake
                    .authenticatedPeerFingerprintHex
            identityTimeoutJob?.cancel()
            identityTimeoutJob = null
        }
    }

    private fun requireIdentityReadyForOffer() {
        if (!identityReadyForOffers()) {
            throw TrustedSessionAuthenticationException(
                "File offer arrived before persistent identity authentication completed."
            )
        }
    }

    private suspend fun sendHandshakeControl(
        type: String,
        body: JsonObject
    ): Envelope = sendSessionControl(type, body, null)

    private suspend fun sendSessionControl(
        type: String,
        body: JsonObject,
        extensions: JsonObject?,
        onPrepared: (Envelope) -> Unit = {}
    ): Envelope {
        val envelope =
            mutex.withLock {
                val current =
                    _state.value
                val next =
                    Envelope(
                        v =
                            ProtocolCodec
                                .PROTOCOL_MAJOR_VERSION,
                        type = type,
                        sessionId =
                            current.sessionId,
                        messageId =
                            MessageId(
                                UUID.randomUUID()
                                    .toString()
                            ),
                        seq =
                            current.nextOutboundSeq,
                        body = body,
                        extensions = extensions
                    )

                _state.value =
                    current.copy(
                        nextOutboundSeq =
                            current.nextOutboundSeq + 1,
                        lastActivityMonotonicMs =
                            clock()
                    )
                next
            }

        onPrepared(envelope)
        try {
            transport.sendControl(
                peerId,
                ProtocolCodec.encode(
                    envelope
                )
            )
        } catch (error: Exception) {
            throw TrustedSessionAuthenticationException(
                "Failed to send persistent identity control message: " +
                    (error.message
                        ?: error::class.java.simpleName),
                error
            )
        }

        return envelope
    }

    private suspend fun beginTrustedSessionAfterNegotiation() {
        val handshake =
            trustedSessionHandshake

        if (handshake == null) {
            if (expectedPeerFingerprintHex != null) {
                throw TrustedSessionAuthenticationException(
                    "A trusted peer was required but no authenticated identity authority is available."
                )
            }
            return
        }

        handshake.onNegotiated(
            ::sendHandshakeControl
        )
        scheduleIdentityTimeoutIfNeeded()
        cancelIdentityTimeoutIfComplete()
    }

    private suspend fun handleIdentityChallenge(
        envelope: Envelope
    ) {
        val handshake =
            trustedSessionHandshake
                ?: throw TrustedSessionAuthenticationException(
                    "identity-v1 was received without a local persistent identity authority."
                )

        val challenge =
            ProtocolCodec
                .decodeBody<IdentityChallengeDto>(
                    envelope
                )

        handshake.onChallenge(
            envelope = envelope,
            challenge = challenge,
            sendControl =
                ::sendHandshakeControl
        )

        cancelIdentityTimeoutIfComplete()
        if (role == DeviceRole.Sender &&
            handshake.isComplete
        ) {
            if (resumeBatch != null) onResumeBatchAuthenticated() else maybeSubmitSenderOffer()
        }
    }

    private suspend fun handleIdentityResponse(
        envelope: Envelope
    ) {
        val handshake =
            trustedSessionHandshake
                ?: throw TrustedSessionAuthenticationException(
                    "identity-v1 was received without a local persistent identity authority."
                )

        val response =
            ProtocolCodec
                .decodeBody<IdentityResponseDto>(
                    envelope
                )

        handshake.onResponse(
            response
        )
        cancelIdentityTimeoutIfComplete()
        if (handshake.isComplete) {
            if (resumeBatch != null) onResumeBatchAuthenticated() else maybeStartReceiverResumeRequest()
        }
    }

    private suspend fun onResumeBatchAuthenticated() {
        val resume = checkNotNull(resumeBatch)
        if (resumeBatchStarted) return
        resumeBatchStarted = true
        if (!isTrueResumeNegotiated()) {
            dispatch(ReducerEvent.TransportFailure(
                TransferFailure.UnsupportedProtocol("The paired device cannot resume this batch"), clock()
            ))
            return
        }
        val manager = checkNotNull(persistentTrustManager)
        val peer = checkNotNull(authenticatedPeerFingerprintHex)
        val localExpected = if (role == DeviceRole.Sender) resume.manifest.senderFingerprint else resume.manifest.receiverFingerprint
        val peerExpected = if (role == DeviceRole.Sender) resume.manifest.receiverFingerprint else resume.manifest.senderFingerprint
        check(manager.localFingerprintHex() == localExpected.value && peer == peerExpected.value &&
            manager.trustedPeerByFingerprint(peer) != null) { "The approved batch belongs to another identity" }
        if (role == DeviceRole.Receiver) {
            resumeReceiverVerifiedCount = recoverResumeCommitReceipts?.invoke(resume.manifest, resumeReceiverVerifiedCount)
                ?: resumeReceiverVerifiedCount
            check(resumeReceiverVerifiedCount in resume.receiverVerifiedCount..resume.manifest.items.size)
        }
        val batch = resume.manifest.asTransferBatch()
        val offer = if (role == DeviceRole.Receiver && resumeReceiverVerifiedCount in 1 until batch.items.size)
            batch.copy(items = batch.items.drop(resumeReceiverVerifiedCount)) else batch
        _state.value = _state.value.copy(batch = offer, phase = SessionPhase.AwaitingConsent,
            itemStates = offer.items.associate { it.id to ItemTransferState(it) })
        if (role == DeviceRole.Receiver && (resume.localInitiated || resumeReceiverVerifiedCount == batch.items.size))
            decideResumeBatch(true)
    }

    private suspend fun decideResumeBatch(approved: Boolean) {
        val resume = checkNotNull(resumeBatch)
        mutex.withLock {
            if (role != DeviceRole.Receiver || !resumeBatchStarted || _state.value.phase != SessionPhase.AwaitingConsent) return
            _state.value = _state.value.copy(phase = SessionPhase.PreparingItem)
        }
        val peer = checkNotNull(authenticatedPeerFingerprintHex)
        val manager = checkNotNull(persistentTrustManager)
        check(manager.trustedPeerByFingerprint(peer) != null) { "The paired device was revoked" }
        val remaining = resume.manifest.items.drop(resumeReceiverVerifiedCount).sumOf { it.sizeBytes }
        val hasSpace = !approved || remaining == 0L || receiveStore.hasAvailableSpace(remaining)
        val accepted = approved && hasSpace
        val options = if (accepted) buildJsonObject {
            put("resumeManifestDigest", resume.manifest.digest().value)
            put("resumeVerifiedCount", resumeReceiverVerifiedCount)
        } else null
        val rejection = if (accepted) null else if (hasSpace) "USER_REJECTED" else "INSUFFICIENT_STORAGE"
        if (_state.value.isTerminal) return
        if (accepted) setResumePosition(resumeReceiverVerifiedCount)
        sendSessionControl(MessageTypes.OFFER_DECISION, ProtocolCodec.encodeBody(
            OfferDecisionBody(resume.manifest.batchId, accepted, rejection)), options)
        if (accepted) startCurrentResumeItem()
        else dispatch(ReducerEvent.TransportFailure(TransferFailure.UserRejected(rejection!!), clock()))
    }

    private suspend fun respondToResumeConsent(approved: Boolean) {
        try {
            decideResumeBatch(approved)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            dispatch(ReducerEvent.TransportFailure(TransferFailure.TransferInterrupted("Could not continue the retained transfer"), clock()))
        }
    }

    private suspend fun handleResumeBatchDecision(envelope: Envelope) {
        val resume = checkNotNull(resumeBatch)
        check(role == DeviceRole.Sender && resumeBatchStarted && _state.value.phase == SessionPhase.AwaitingConsent)
        val decision = ProtocolCodec.decodeBody<OfferDecisionBody>(envelope)
        check(decision.batchId == resume.manifest.batchId) { "Resume consent is for a different batch" }
        if (!decision.accepted) {
            dispatch(ReducerEvent.TransportFailure(TransferFailure.UserRejected(decision.code ?: "USER_REJECTED"), clock()))
            return
        }
        check(ResumeOfferMetadata.decodeAcceptance(envelope.extensions) == resume.manifest.digest())
        val count = (envelope.extensions?.get("resumeVerifiedCount") as? JsonPrimitive)
            ?.takeIf { !it.isString }?.content?.toIntOrNull()
        check(count in 0..resume.manifest.items.size) { "Invalid verified prefix in resume consent" }
        setResumePosition(checkNotNull(count))
        startCurrentResumeItem()
    }

    private fun setResumePosition(index: Int) {
        val resume = checkNotNull(resumeBatch)
        val peer = checkNotNull(authenticatedPeerFingerprintHex)
        resumeBatchIndex = index
        val item = resume.manifest.items.getOrNull(index)
        resumeReconnectPlan = item?.let {
            if (role == DeviceRole.Sender) ResumeReconnectPlan.Sender(resume.manifest.sessionId, it.id, peer)
            else ResumeReconnectPlan.Receiver(resume.manifest.sessionId, it.id, peer)
        }
        pendingResumeRequest = null
        pendingReceiverResumeJournal = null
        emptyResumeRequestId = null
        approvedResumeSourceHashes = emptyList()
        lastResumeProgressNanos = 0L
        _resumeReconnectState.value = ResumeReconnectState.Idle
        val batch = resume.manifest.asTransferBatch()
        _state.value = _state.value.copy(
            batch = batch,
            phase = if (item == null) SessionPhase.AwaitingReceipt else SessionPhase.TransferringItem,
            currentItemIndex = index.coerceAtMost(batch.items.lastIndex),
            itemStates = batch.items.mapIndexed { position, offered ->
                offered.id to ItemTransferState(
                    offered,
                    phase = if (position < index) ItemPhase.Verified else ItemPhase.Queued,
                    transferredBytes = if (position < index) offered.sizeBytes else 0,
                    actualDigest = if (position < index) offered.digest else null
                )
            }.toMap()
        )
    }

    private fun ResumeBatchManifest.asTransferBatch(): TransferBatch = TransferBatch(batchId, items.map {
        TransferItem(it.id, it.name, "application/octet-stream", it.sizeBytes,
            digest = it.digest, relativePath = it.relativePath)
    })

    private suspend fun startCurrentResumeItem() {
        val resume = checkNotNull(resumeBatch)
        if (_state.value.isTerminal) return
        if (resumeBatchIndex == resume.manifest.items.size) {
            if (role == DeviceRole.Sender)
                sendHandshakeControl(MessageTypes.BATCH_END, ProtocolCodec.encodeBody(BatchEndBody(resume.manifest.batchId)))
        } else if (role == DeviceRole.Receiver) {
            maybeStartReceiverResumeRequest()
            val failure = _resumeReconnectState.value as? ResumeReconnectState.Rejected
            if (failure != null)
                dispatch(ReducerEvent.TransportFailure(TransferFailure.TransferInterrupted("The retained file cannot be resumed"), clock()))
        }
    }

    private suspend fun advanceResumeBatchItem(startReceiver: Boolean = true) {
        val resume = checkNotNull(resumeBatch)
        val item = resume.manifest.items[resumeBatchIndex]
        historyStore?.updateItemOutcome(item.id, "Verified", item.sizeBytes, item.digest)
        setResumePosition(resumeBatchIndex + 1)
        if (role == DeviceRole.Sender || startReceiver) startCurrentResumeItem()
    }

    private suspend fun reportResumedBytes(itemId: ItemId, bytes: Long, force: Boolean = false) {
        if (resumeBatch == null) return
        val time = System.nanoTime()
        mutex.withLock {
            val current = _state.value
            if (current.isTerminal || current.currentItemState?.item?.id != itemId ||
                (!force && time - lastResumeProgressNanos < 200_000_000L)) return
            val item = current.itemStates.getValue(itemId)
            if (bytes !in item.transferredBytes..item.item.sizeBytes) return
            lastResumeProgressNanos = time
            _state.value = current.copy(itemStates = current.itemStates +
                (itemId to item.copy(transferredBytes = bytes, phase = ItemPhase.Streaming)))
        }
    }

    private suspend fun finishResumeBatch(envelope: Envelope, sender: Boolean) {
        val manifest = checkNotNull(resumeBatch).manifest
        val total = manifest.items.sumOf { it.sizeBytes }
        check((role == DeviceRole.Sender) == sender && resumeBatchIndex == manifest.items.size &&
            _state.value.phase == SessionPhase.AwaitingReceipt)
        if (sender) {
            val result = ProtocolCodec.decodeBody<BatchResultBody>(envelope)
            check(result.batchId == manifest.batchId && result.status == "completed" &&
                result.verifiedCount == manifest.items.size && result.totalVerifiedBytes.toLong() == total)
            purgeAcceptedResumeBatch?.invoke(manifest.sessionId)
        } else {
            check(ProtocolCodec.decodeBody<BatchEndBody>(envelope).batchId == manifest.batchId)
            sendHandshakeControl(MessageTypes.BATCH_RESULT, ProtocolCodec.encodeBody(
                BatchResultBody(manifest.batchId, "completed", manifest.items.size, total.toString())))
        }
        mutex.withLock {
            if (!_state.value.isTerminal) _state.value = _state.value.copy(phase = SessionPhase.Completed)
        }
    }

    private suspend fun sendEmptyResumeRequest() {
        val manifest = checkNotNull(resumeBatch).manifest
        val item = manifest.items[resumeBatchIndex]
        val request = ResumeRequestAuthority.createRequest(sessionId, checkNotNull(activeResumeChannelContext()),
            checkNotNull(_state.value.negotiated).capabilities, checkNotNull(persistentTrustManager),
            checkNotNull(authenticatedPeerFingerprintHex), ResumeRequestAuthority.ReceiverJournal(
                manifest.sessionId, item.id, 0, 0, 1, 0, "0".repeat(64)))
        sendSessionControl(MessageTypes.RESUME_REQUEST, ProtocolCodec.encodeBody(request), null) {
            emptyResumeRequestId = it.messageId.value
        }
    }

    private suspend fun handleEmptyResumeRequest(envelope: Envelope) {
        val manifest = checkNotNull(resumeBatch).manifest
        val item = manifest.items[resumeBatchIndex]
        check(role == DeviceRole.Sender && item.sizeBytes == 0L && emptyResumeRequestId == null)
        val request = ProtocolCodec.decodeBody<ResumeRequestDto>(envelope)
        ResumeRequestAuthority.verifyRequest(sessionId, checkNotNull(activeResumeChannelContext()),
            checkNotNull(_state.value.negotiated).capabilities, checkNotNull(persistentTrustManager),
            checkNotNull(authenticatedPeerFingerprintHex), request, ResumeRequestAuthority.SenderSourceAuthority(
                manifest.sessionId, item.id, 1, 0, "0".repeat(64)))
        emptyResumeRequestId = envelope.messageId.value
        sendHandshakeControl(MessageTypes.RESUME_ACCEPT, ProtocolCodec.encodeBody(
            ResumeAcceptDto(IdentityResumeTranscript.RESUME_PROFILE, envelope.messageId.value,
                manifest.sessionId.value, item.id.value, 0, "0")))
    }

    private suspend fun completeEmptyResumeReceiver(envelope: Envelope) {
        val manifest = checkNotNull(resumeBatch).manifest
        val item = manifest.items[resumeBatchIndex]
        val accept = ProtocolCodec.decodeBody<ResumeAcceptDto>(envelope)
        check(role == DeviceRole.Receiver && item.sizeBytes == 0L &&
            accept.requestMessageId == emptyResumeRequestId && accept.originalSessionId == manifest.sessionId.value &&
            accept.itemId == item.id.value && accept.startChunkIndex == 0L && accept.startByteOffset == "0")
        check(persistentTrustManager?.trustedPeerByFingerprint(checkNotNull(authenticatedPeerFingerprintHex)) != null)
        val staging = receiveStore.prepareStaging(sessionId, item.id, 0, item.name, relativePath = item.relativePath)
        try {
            receiveStore.openStagingOutput(staging).close()
            val committed = receiveStore.commitVerified(staging, item.digest)
            _committedFiles.add(committed)
            _committedItemFiles[item.id] = committed
            lastCommittedFileHandle = committed
            recordResumeBatchCommit?.invoke(manifest.sessionId, item.id, 0, item.digest)
            advanceResumeBatchItem(startReceiver = false)
            sendHandshakeControl(MessageTypes.RESUME_RESULT, ProtocolCodec.encodeBody(
                ResumeResultDto(IdentityResumeTranscript.RESUME_PROFILE, accept.requestMessageId,
                    manifest.sessionId.value, item.id.value, "verified", "0", item.digest.value)))
            startCurrentResumeItem()
        } finally {
            receiveStore.discardStaging(staging)
        }
    }

    private suspend fun verifyEmptyResumeResult(envelope: Envelope) {
        val manifest = checkNotNull(resumeBatch).manifest
        val item = manifest.items[resumeBatchIndex]
        val result = ProtocolCodec.decodeBody<ResumeResultDto>(envelope)
        check(role == DeviceRole.Sender && item.sizeBytes == 0L &&
            result.requestMessageId == emptyResumeRequestId && result.originalSessionId == manifest.sessionId.value &&
            result.itemId == item.id.value && result.status == "verified" && result.sizeBytes == "0" &&
            result.sha256 == item.digest.value)
        advanceResumeBatchItem()
    }

    private suspend fun maybeStartReceiverResumeRequest() {
        val plan =
            resumeReconnectPlan
                as? ResumeReconnectPlan
                    .Receiver
                ?: return
        if (resumeBatch?.manifest?.items?.getOrNull(resumeBatchIndex)?.sizeBytes == 0L) {
            sendEmptyResumeRequest()
            return
        }
        if (_resumeReconnectState.value !is
            ResumeReconnectState.Idle
        ) {
            return
        }

        if (!enableTrueResumeCapability ||
            !isTrueResumeNegotiated()
        ) {
            _resumeReconnectState.value =
                ResumeReconnectState.Rejected(
                    ResumeRejectCodes
                        .CAPABILITY_MISMATCH
                )
            return
        }

        val manager =
            persistentTrustManager
                ?: run {
                    _resumeReconnectState.value =
                        ResumeReconnectState
                            .Rejected(
                                ResumeRejectCodes
                                    .UNAUTHORIZED
                            )
                    return
                }
        val peerFingerprint =
            authenticatedPeerFingerprintHex
                ?: run {
                    _resumeReconnectState.value =
                        ResumeReconnectState
                            .Rejected(
                                ResumeRejectCodes
                                    .UNAUTHORIZED
                            )
                    return
                }
        if (peerFingerprint !=
            plan.expectedPeerFingerprintHex
        ) {
            _resumeReconnectState.value =
                ResumeReconnectState.Rejected(
                    ResumeRejectCodes
                        .UNAUTHORIZED
                )
            return
        }

        val loader =
            loadReceiverResumeAuthority
                ?: run {
                    _resumeReconnectState.value =
                        ResumeReconnectState
                            .Rejected(
                                ResumeRejectCodes
                                    .JOURNAL_MISMATCH
                            )
                    return
                }

        val lookup =
            loader(
                plan.originalSessionId,
                plan.itemId,
                peerFingerprint
            )
        val journal =
            when (lookup) {
                is ResumeAuthorityLookup.Ready ->
                    lookup.value
                is ResumeAuthorityLookup.Rejected -> {
                    _resumeReconnectState.value =
                        ResumeReconnectState
                            .Rejected(
                                lookup.code
                            )
                    return
                }
            }

        if (journal.originalSessionId !=
                plan.originalSessionId ||
            journal.itemId !=
                plan.itemId
        ) {
            _resumeReconnectState.value =
                ResumeReconnectState.Rejected(
                    ResumeRejectCodes
                        .JOURNAL_MISMATCH
                )
            return
        }

        val context =
            activeResumeChannelContext()
                ?: run {
                    _resumeReconnectState.value =
                        ResumeReconnectState
                            .Rejected(
                                ResumeRejectCodes
                                    .UNAUTHORIZED
                            )
                    return
                }

        val request =
            try {
                ResumeRequestAuthority
                    .createRequest(
                        activeSessionId =
                            sessionId,
                        channelContext =
                            context,
                        negotiatedCapabilities =
                            _state.value
                                .negotiated
                                ?.capabilities
                                ?: emptySet(),
                        trustManager =
                            manager,
                        responderFingerprintHex =
                            peerFingerprint,
                        journal =
                            journal
                    )
            } catch (
                rejected:
                    ResumeRequestAuthority
                        .Rejected
            ) {
                _resumeReconnectState.value =
                    ResumeReconnectState
                        .Rejected(
                            rejected.code
                        )
                return
            }

        sendSessionControl(MessageTypes.RESUME_REQUEST, ProtocolCodec.encodeBody(request), null) { envelope ->
            pendingResumeRequest = envelope
            pendingReceiverResumeJournal = journal
            _resumeReconnectState.value = ResumeReconnectState.AwaitingResponse(
                requestMessageId = envelope.messageId.value,
                originalSessionId = journal.originalSessionId,
                itemId = journal.itemId,
                requestedChunkIndex = journal.verifiedChunkCount,
                requestedByteOffset = journal.contiguousByteOffset
            )
        }

        resumeTimeoutJob?.cancel()
        resumeTimeoutJob =
            scope.launch {
                kotlinx.coroutines.delay(
                    (30_000L + journal.totalSizeBytes / 8192L).coerceAtMost(20 * 60_000L)
                )
                if (_resumeReconnectState
                        .value is
                    ResumeReconnectState
                        .AwaitingResponse
                ) {
                    _resumeReconnectState.value =
                        ResumeReconnectState
                            .Failed("TIMEOUT")
                    dispatch(
                        ReducerEvent
                            .TransportFailure(
                                TransferFailure
                                    .Timeout(
                                        "resume-v1"
                                    ),
                                clock()
                            )
                    )
                }
            }
    }

    private suspend fun handleResumeRequest(
        envelope: Envelope
    ) {
        if (resumeBatch?.manifest?.items?.getOrNull(resumeBatchIndex)?.sizeBytes == 0L) {
            handleEmptyResumeRequest(envelope)
            return
        }
        val plan =
            resumeReconnectPlan
                as? ResumeReconnectPlan
                    .Sender
        val request =
            ProtocolCodec
                .decodeBody<
                    ResumeRequestDto
                >(envelope)

        if (!enableTrueResumeCapability ||
            !isTrueResumeNegotiated() ||
            plan == null
        ) {
            sendResumeReject(
                envelope,
                request,
                ResumeRejectCodes
                    .CAPABILITY_MISMATCH
            )
            return
        }

        if (!identityReadyForOffers()) {
            sendResumeReject(
                envelope,
                request,
                ResumeRejectCodes
                    .UNAUTHORIZED
            )
            return
        }

        val manager =
            persistentTrustManager
        val requesterFingerprint =
            authenticatedPeerFingerprintHex
        if (manager == null ||
            requesterFingerprint == null ||
            requesterFingerprint !=
                plan.expectedPeerFingerprintHex
        ) {
            sendResumeReject(
                envelope,
                request,
                ResumeRejectCodes
                    .UNAUTHORIZED
            )
            return
        }

        if (request.originalSessionId !=
                plan.originalSessionId
                    .value ||
            request.itemId !=
                plan.itemId.value
        ) {
            sendResumeReject(
                envelope,
                request,
                ResumeRejectCodes
                    .JOURNAL_MISMATCH
            )
            return
        }

        val loader =
            loadSenderResumeAuthority
        if (loader == null) {
            sendResumeReject(
                envelope,
                request,
                ResumeRejectCodes
                    .JOURNAL_MISMATCH
            )
            return
        }

        val lookup =
            loader(
                plan.originalSessionId,
                plan.itemId,
                requesterFingerprint
            )
        val ready =
            when (lookup) {
                is ResumeAuthorityLookup.Ready ->
                    lookup.value
                is ResumeAuthorityLookup.Rejected -> {
                    sendResumeReject(
                        envelope,
                        request,
                        lookup.code
                    )
                    return
                }
            }

        val context =
            activeResumeChannelContext()
        if (context == null) {
            sendResumeReject(
                envelope,
                request,
                ResumeRejectCodes
                    .UNAUTHORIZED
            )
            return
        }

        if (resumeBatch != null) {
            val item = resumeBatch.manifest.items[resumeBatchIndex]
            if (ready.wholeFileSha256Hex != item.digest.value || ready.authority.totalSizeBytes != item.sizeBytes ||
                ready.authority.chunkSize != item.chunkSize || ready.authority.chunkRootHashHex != item.rootHash?.value) {
                sendResumeReject(envelope, request, ResumeRejectCodes.FILE_CHANGED)
                return
            }
        }

        val verified =
            try {
                ResumeRequestAuthority
                    .verifyRequest(
                        activeSessionId =
                            sessionId,
                        channelContext =
                            context,
                        negotiatedCapabilities =
                            _state.value
                                .negotiated
                                ?.capabilities
                                ?: emptySet(),
                        trustManager =
                            manager,
                        authenticatedRequesterFingerprintHex =
                            requesterFingerprint,
                        request =
                            request,
                        sourceAuthority =
                            ready.authority
                    )
            } catch (
                rejected:
                    ResumeRequestAuthority
                        .Rejected
            ) {
                sendResumeReject(
                    envelope,
                    request,
                    rejected.code
                )
                return
            }

        val accept =
            ResumeRequestAuthority
                .createAccept(
                    requestMessageId =
                        envelope
                            .messageId
                            .value,
                    verified =
                        verified
                )
        approvedResumeSourceHashes = ready.verifiedSourceChunks
        sendHandshakeControl(
            MessageTypes.RESUME_ACCEPT,
            ProtocolCodec.encodeBody(
                accept
            )
        )
        val acceptedState =
            ResumeReconnectState
                .SenderAccepted(
                    requestMessageId =
                        envelope.messageId.value,
                    originalSessionId =
                        verified
                            .originalSessionId,
                    itemId =
                        verified.itemId,
                    startChunkIndex =
                        verified
                            .startChunkIndex,
                    startByteOffset =
                        verified
                            .startByteOffset,
                    chunkSize =
                        ready.authority
                            .chunkSize,
                    totalSizeBytes =
                        ready.authority
                            .totalSizeBytes,
                    chunkRootHashHex =
                        ready.authority
                            .chunkRootHashHex,
                    sourceHandle =
                        ready.sourceHandle,
                    wholeFileSha256Hex =
                        ready
                            .wholeFileSha256Hex
                )
        _resumeReconnectState.value =
            acceptedState
        if (acceptedState.startByteOffset <
            acceptedState.totalSizeBytes
        ) {
            prepareAndAnnounceResumeStream(
                acceptedState
            )
        }
    }

    private suspend fun handleResumeAccept(
        envelope: Envelope
    ) {
        if (resumeBatch != null && emptyResumeRequestId != null) {
            completeEmptyResumeReceiver(envelope)
            return
        }
        val plan =
            resumeReconnectPlan
                as? ResumeReconnectPlan
                    .Receiver
                ?: return
        val pending =
            pendingResumeRequest
                ?: throw ResumeJournalException(
                    "RESUME_ACCEPT arrived without an active resume request"
                )
        val journal =
            pendingReceiverResumeJournal
                ?: throw ResumeJournalException(
                    "Receiver resume journal snapshot is unavailable"
                )
        val accept =
            ProtocolCodec
                .decodeBody<
                    ResumeAcceptDto
                >(envelope)

        val startOffset =
            try {
                dev.glide.core.protocol
                    .DecimalByteCount
                    .parse(
                        accept
                            .startByteOffset
                    )
            } catch (
                error:
                    IllegalArgumentException
            ) {
                throw ResumeJournalException(
                    error.message
                        ?: "Invalid resume accept offset"
                )
            }

        if (accept.requestMessageId !=
                pending.messageId.value ||
            accept.originalSessionId !=
                plan.originalSessionId
                    .value ||
            accept.itemId !=
                plan.itemId.value ||
            accept.startChunkIndex !=
                journal
                    .verifiedChunkCount ||
            startOffset !=
                journal
                    .contiguousByteOffset
        ) {
            _resumeReconnectState.value =
                ResumeReconnectState
                    .Failed(
                        ResumeRejectCodes
                            .JOURNAL_MISMATCH
                    )
            throw ResumeJournalException(
                "RESUME_ACCEPT did not match the exact requested verified prefix"
            )
        }

        resumeTimeoutJob?.cancel()
        resumeTimeoutJob = null
        pendingResumeRequest = null
        pendingReceiverResumeJournal =
            null
        _resumeReconnectState.value =
            ResumeReconnectState
                .ReceiverAccepted(
                    requestMessageId =
                        pending.messageId.value,
                    originalSessionId =
                        journal
                            .originalSessionId,
                    itemId =
                        journal.itemId,
                    startChunkIndex =
                        journal
                            .verifiedChunkCount,
                    startByteOffset =
                        journal
                            .contiguousByteOffset,
                    chunkSize =
                        journal.chunkSize,
                    totalSizeBytes =
                        journal.totalSizeBytes,
                    chunkRootHashHex =
                        journal.chunkRootHashHex
                )
    }

    private suspend fun handleResumeReject(
        envelope: Envelope
    ) {
        val plan =
            resumeReconnectPlan
                as? ResumeReconnectPlan
                    .Receiver
                ?: return
        val pending =
            pendingResumeRequest
                ?: throw ResumeJournalException(
                    "RESUME_REJECT arrived without an active resume request"
                )
        val reject =
            ProtocolCodec
                .decodeBody<
                    ResumeRejectDto
                >(envelope)

        if (reject.requestMessageId !=
                pending.messageId.value ||
            reject.originalSessionId !=
                plan.originalSessionId
                    .value ||
            reject.itemId !=
                plan.itemId.value
        ) {
            _resumeReconnectState.value =
                ResumeReconnectState
                    .Failed(
                        ResumeRejectCodes
                            .JOURNAL_MISMATCH
                    )
            throw ResumeJournalException(
                "RESUME_REJECT did not correlate to the active request"
            )
        }

        resumeTimeoutJob?.cancel()
        resumeTimeoutJob = null
        pendingResumeRequest = null
        pendingReceiverResumeJournal =
            null
        _resumeReconnectState.value =
            ResumeReconnectState
                .Rejected(
                    reject.code
                )
    }

    private suspend fun sendResumeReject(
        requestEnvelope: Envelope,
        request: ResumeRequestDto,
        code: String
    ) {
        val reject =
            ResumeRequestAuthority
                .createReject(
                    requestMessageId =
                        requestEnvelope
                            .messageId
                            .value,
                    originalSessionId =
                        request
                            .originalSessionId,
                    itemId =
                        request.itemId,
                    code = code
                )
        sendHandshakeControl(
            MessageTypes.RESUME_REJECT,
            ProtocolCodec.encodeBody(
                reject
            )
        )
        _resumeReconnectState.value =
            ResumeReconnectState
                .Rejected(code)
    }

    private fun activeResumeChannelContext():
        dev.glide.core.ports
            .AuthenticatedChannelContext? =
        (transport as?
            AuthenticatedChannelContextProvider)
            ?.authenticatedChannelContext(
                peerId
            )
            ?.takeIf {
                it.sessionId ==
                    sessionId
            }

    private suspend fun prepareAndAnnounceResumeStream(
        accepted:
            ResumeReconnectState
                .SenderAccepted
    ) {
        val remaining =
            accepted.totalSizeBytes -
                accepted.startByteOffset
        if (remaining <= 0L) {
            return
        }

        val prepared =
            try {
                withContext(
                    ioDispatcher
                ) {
                    ResumeStreamSupport
                        .openRemainingSource(
                            sourceAccess =
                                sourceAccess,
                            handle =
                                accepted
                                    .sourceHandle,
                            expectedTotalBytes =
                                accepted
                                    .totalSizeBytes,
                            startByteOffset =
                                accepted
                                    .startByteOffset,
                            verifiedChunkSize = accepted.chunkSize,
                            verifiedChunkDigests = approvedResumeSourceHashes
                        )
                }
            } catch (
                error: Exception
            ) {
                _resumeReconnectState.value =
                    ResumeReconnectState
                        .Failed(
                            WireErrorCodes
                                .SOURCE_UNAVAILABLE
                        )
                dispatch(
                    ReducerEvent
                        .TransportFailure(
                            TransferFailure
                                .SourceUnavailable(
                                    "Resume source could not be reopened at the accepted offset"
                                ),
                            clock()
                        )
                )
                return
            }

        val bulkRef =
            try {
                transport.prepareStream(
                    InputStreamProvider {
                        prepared.stream
                    }
                )
            } catch (
                _: Exception
            ) {
                null
            }

        if (bulkRef == null) {
            try {
                prepared.stream.close()
            } catch (_: Exception) {
            }
            _resumeReconnectState.value =
                ResumeReconnectState
                    .Failed(
                        WireErrorCodes
                            .TRANSFER_INTERRUPTED
                    )
            dispatch(
                ReducerEvent
                    .TransportFailure(
                        TransferFailure
                            .TransferInterrupted(
                                "Transport could not prepare resumed payload"
                            ),
                        clock()
                    )
            )
            return
        }

        synchronized(
            preparedSourcesLock
        ) {
            preparedSources[bulkRef] =
                PreparedSource(
                    accepted.itemId,
                    prepared.stream
                )
        }

        val start =
            ResumeStreamStartDto(
                profile =
                    dev.glide.core.protocol
                        .IdentityResumeTranscript
                        .RESUME_PROFILE,
                requestMessageId =
                    accepted
                        .requestMessageId,
                originalSessionId =
                    accepted
                        .originalSessionId
                        .value,
                itemId =
                    accepted.itemId.value,
                bulkRef =
                    bulkRef.value,
                startChunkIndex =
                    accepted
                        .startChunkIndex,
                startByteOffset =
                    dev.glide.core.protocol
                        .DecimalByteCount
                        .serialize(
                            accepted
                                .startByteOffset
                        ),
                remainingBytes =
                    dev.glide.core.protocol
                        .DecimalByteCount
                        .serialize(
                            remaining
                        ),
                chunkSize =
                    accepted.chunkSize,
                totalSizeBytes =
                    dev.glide.core.protocol
                        .DecimalByteCount
                        .serialize(
                            accepted
                                .totalSizeBytes
                        ),
                chunkRootHashHex =
                    accepted
                        .chunkRootHashHex,
                wholeFileSha256Hex =
                    accepted
                        .wholeFileSha256Hex
            )

        pendingSenderResumeStreamStart =
            start
        pendingResumeStreamBulkRef =
            bulkRef
        sendHandshakeControl(
            MessageTypes.RESUME_STREAM_START,
            ProtocolCodec.encodeBody(
                start
            )
        )
    }

    private suspend fun handleResumeStreamStart(
        envelope: Envelope
    ) {
        val plan =
            resumeReconnectPlan
                as? ResumeReconnectPlan
                    .Receiver
                ?: throw ResumeJournalException(
                    "RESUME_STREAM_START arrived outside receiver reconnect mode"
                )
        val accepted =
            _resumeReconnectState.value
                as? ResumeReconnectState
                    .ReceiverAccepted
                ?: throw ResumeJournalException(
                    "RESUME_STREAM_START arrived before RESUME_ACCEPT"
                )
        val start =
            ProtocolCodec
                .decodeBody<
                    ResumeStreamStartDto
                >(envelope)
        val startOffset =
            dev.glide.core.protocol
                .DecimalByteCount
                .parse(
                    start
                        .startByteOffset
                )
        val remaining =
            dev.glide.core.protocol
                .DecimalByteCount
                .parse(
                    start
                        .remainingBytes
                )
        val total =
            dev.glide.core.protocol
                .DecimalByteCount
                .parse(
                    start
                        .totalSizeBytes
                )

        if (start.requestMessageId !=
                accepted.requestMessageId ||
            start.originalSessionId !=
                plan.originalSessionId
                    .value ||
            start.itemId !=
                plan.itemId.value ||
            start.startChunkIndex !=
                accepted.startChunkIndex ||
            startOffset !=
                accepted.startByteOffset ||
            start.chunkSize !=
                accepted.chunkSize ||
            total !=
                accepted.totalSizeBytes ||
            start.chunkRootHashHex !=
                accepted.chunkRootHashHex ||
            remaining !=
                accepted.totalSizeBytes -
                    accepted.startByteOffset ||
            (resumeBatch != null && start.wholeFileSha256Hex != resumeBatch.manifest.items[resumeBatchIndex].digest.value)
        ) {
            throw ResumeJournalException(
                "RESUME_STREAM_START does not match the accepted prefix/source authority"
            )
        }

        val bulkRef =
            BulkRef(
                start.bulkRef
            )
        transport.registerExpectedStream(
            StreamExpectation(
                sessionId =
                    sessionId,
                itemId =
                    accepted.itemId,
                bulkRef =
                    bulkRef,
                sizeBytes =
                    remaining
            )
        )

        pendingReceiverResumeStreamStart =
            start
        pendingResumeStreamBulkRef =
            bulkRef

        val ready =
            ResumeStreamReadyDto(
                profile =
                    dev.glide.core.protocol
                        .IdentityResumeTranscript
                        .RESUME_PROFILE,
                requestMessageId =
                    accepted
                        .requestMessageId,
                originalSessionId =
                    accepted
                        .originalSessionId
                        .value,
                itemId =
                    accepted.itemId.value,
                bulkRef =
                    bulkRef.value
            )
        sendHandshakeControl(
            MessageTypes.RESUME_STREAM_READY,
            ProtocolCodec.encodeBody(
                ready
            )
        )
    }

    private suspend fun handleResumeStreamReady(
        envelope: Envelope
    ) {
        resumeReconnectPlan
            as? ResumeReconnectPlan
                .Sender
            ?: throw ResumeJournalException(
                "RESUME_STREAM_READY arrived outside sender reconnect mode"
            )
        val accepted =
            _resumeReconnectState.value
                as? ResumeReconnectState
                    .SenderAccepted
                ?: throw ResumeJournalException(
                    "RESUME_STREAM_READY arrived before resume acceptance"
                )
        val start =
            pendingSenderResumeStreamStart
                ?: throw ResumeJournalException(
                    "Sender has no pending resume stream"
                )
        val bulkRef =
            pendingResumeStreamBulkRef
                ?: throw ResumeJournalException(
                    "Sender has no prepared resume bulk reference"
                )
        val ready =
            ProtocolCodec
                .decodeBody<
                    ResumeStreamReadyDto
                >(envelope)

        if (ready.requestMessageId !=
                accepted.requestMessageId ||
            ready.originalSessionId !=
                accepted
                    .originalSessionId
                    .value ||
            ready.itemId !=
                accepted.itemId.value ||
            ready.bulkRef !=
                start.bulkRef ||
            ready.bulkRef !=
                bulkRef.value
        ) {
            throw ResumeJournalException(
                "RESUME_STREAM_READY did not correlate to the prepared stream"
            )
        }

        transport.markItemReady(
            bulkRef
        )
        startSendingResumeRemainder(
            accepted,
            bulkRef
        )
    }

    private fun startSendingResumeRemainder(
        accepted:
            ResumeReconnectState
                .SenderAccepted,
        bulkRef: BulkRef
    ) {
        launchTracked(
            ioDispatcher
        ) {
            val prepared =
                synchronized(
                    preparedSourcesLock
                ) {
                    preparedSources
                        .remove(
                            bulkRef
                        )
                }
            if (prepared == null) {
                _resumeReconnectState.value =
                    ResumeReconnectState
                        .Failed(
                            WireErrorCodes
                                .TRANSFER_INTERRUPTED
                        )
                return@launchTracked
            }

            val stream =
                prepared.stream
            try {
                reportResumedBytes(accepted.itemId, accepted.startByteOffset, force = true)
                transport
                    .sendStream(
                        peerId,
                        bulkRef
                    )
                    .collect { progress ->
                        // The transport owns authenticated DATA/STREAM_END framing.
                        reportResumedBytes(accepted.itemId, accepted.startByteOffset + progress.bytesTransferred)
                    }

                stream.proveEof()
                val expectedRemaining =
                    accepted.totalSizeBytes -
                        accepted.startByteOffset
                if (stream.bytesRead !=
                    expectedRemaining
                ) {
                    throw java.io.EOFException(
                        "Resumed payload byte count differed from accepted remaining size"
                    )
                }
            } catch (
                error: Exception
            ) {
                _resumeReconnectState.value =
                    ResumeReconnectState
                        .Failed(
                            WireErrorCodes
                                .READ_FAILURE
                        )
                if (!_state.value
                        .isTerminal
                ) {
                    dispatch(
                        ReducerEvent
                            .TransportFailure(
                                TransferFailure
                                    .ReadFailure(
                                        "Resumed source stream failed: " +
                                            (error.message
                                                ?: "unknown")
                                    ),
                                clock()
                            )
                    )
                }
            } finally {
                try {
                    stream.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun handleResumeResult(
        envelope: Envelope
    ) {
        if (resumeBatch != null && emptyResumeRequestId != null) {
            verifyEmptyResumeResult(envelope)
            return
        }
        val plan =
            resumeReconnectPlan
                as? ResumeReconnectPlan
                    .Sender
                ?: throw ResumeJournalException(
                    "RESUME_RESULT arrived outside sender reconnect mode"
                )
        val accepted =
            _resumeReconnectState.value
                as? ResumeReconnectState
                    .SenderAccepted
                ?: throw ResumeJournalException(
                    "RESUME_RESULT arrived before resume acceptance"
                )
        val result =
            ProtocolCodec
                .decodeBody<
                    ResumeResultDto
                >(envelope)

        if (result.requestMessageId !=
                accepted.requestMessageId ||
            result.originalSessionId !=
                plan.originalSessionId
                    .value ||
            result.itemId !=
                plan.itemId.value
        ) {
            throw ResumeJournalException(
                "RESUME_RESULT did not correlate to the active resumed item"
            )
        }

        if (result.status ==
            "failed"
        ) {
            _resumeReconnectState.value =
                ResumeReconnectState
                    .Failed(
                        result.code
                            ?: WireErrorCodes
                                .INTEGRITY_MISMATCH
                    )
            return
        }

        val size =
            dev.glide.core.protocol
                .DecimalByteCount
                .parse(
                    result.sizeBytes
                        ?: throw ResumeJournalException(
                            "Verified resume result is missing sizeBytes"
                        )
                )
        val sha =
            result.sha256
                ?: throw ResumeJournalException(
                    "Verified resume result is missing sha256"
                )

        if (size !=
                accepted.totalSizeBytes ||
            sha !=
                accepted
                    .wholeFileSha256Hex
        ) {
            throw ResumeJournalException(
                "Verified RESUME_RESULT does not match sender source authority"
            )
        }

        try {
            if (resumeBatch == null) purgeResumeItemAuthority
                ?.invoke(
                    accepted
                        .originalSessionId,
                    accepted.itemId
                )
        } catch (_: Exception) {
            // A verified receiver result is authoritative; stale private sender state is TTL-bounded.
        }

        pendingSenderResumeStreamStart =
            null
        pendingResumeStreamBulkRef =
            null
        _resumeReconnectState.value =
            ResumeReconnectState
                .Verified(
                    originalSessionId =
                        accepted
                            .originalSessionId,
                    itemId =
                        accepted.itemId,
                    sizeBytes = size,
                    sha256Hex = sha
                )
        if (resumeBatch != null) advanceResumeBatchItem()
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
                    val ok = prepareDestination(effect.itemId, effect.sizeBytes)
                    if (!ok) {
                        break
                    }
                }

                is ReducerEffect.CommitDestinationFile -> {
                    commitDestination(effect.itemId)
                }

                is ReducerEffect.DeletePartialDestination -> {
                    withContext(ioDispatcher) {
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
                    try { transport.cancelPayload(effect.bulkRef) } catch (_: Exception) {}
                }

                is ReducerEffect.CloseTransportChannel -> {
                    // Keep the foreground-service owner alive until the terminal control has had a
                    // short flush opportunity and the transport is actually disconnected.
                    kotlinx.coroutines.delay(100)
                    try { transport.disconnect(peerId) } catch (_: Exception) {}
                }

                is ReducerEffect.UpdateTransferProgress -> {
                    // Progress already reflected in state
                }

                is ReducerEffect.NotifyTerminalOutcome -> {
                    if (resumeBatch == null && role == DeviceRole.Sender &&
                        effect.phase == SessionPhase.Completed && isTrueResumeNegotiated()) {
                        try {
                            purgeAcceptedResumeBatch?.invoke(sessionId)
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            // The peer's final verified receipt stands. Retained data expires independently.
                        }
                    }
                    val terminalOutcome = effect.phase.name
                    val failureCode = effect.failure?.code ?: _state.value.failure?.code
                    ensureSessionRecorded(terminalOutcome, failureCode)
                    historyStore?.updateSessionOutcome(sessionId, terminalOutcome, clock(), failureCode)
                    _state.value.itemStates.forEach { (id, itState) ->
                        if (itState.phase != ItemPhase.Verified) {
                            val itemFailureCode = itState.failure?.code ?: failureCode
                            historyStore?.updateItemOutcome(id, itState.phase.name, itState.transferredBytes, itState.actualDigest, itemFailureCode)
                        }
                    }
                }
            }
        }
    }

    private fun startSendingStream(itemId: ItemId, bulkRef: BulkRef) {
        launchTracked(ioDispatcher) {
            val prepared = synchronized(preparedSourcesLock) {
                preparedSources.remove(bulkRef)
            }
            if (prepared == null) {
                if (!_state.value.isTerminal) {
                    dispatch(
                        ReducerEvent.TransportFailure(
                            TransferFailure.TransferInterrupted("No prepared stream found for bulkRef ${bulkRef.value}"),
                            clock()
                        )
                    )
                }
                return@launchTracked
            }

            val hashingStream = prepared.stream
            try {
                val sampler = StreamProgressSampler(
                    clock = clock,
                    onSample = { bytesRead ->
                        launchTracked {
                            dispatch(ReducerEvent.StreamProgress(itemId, bytesRead, clock()))
                        }
                    }
                )
                hashingStream.setProgressCallback { bytesRead ->
                    sampler.onBytesRead(bytesRead)
                }

                transport.sendStream(peerId, bulkRef).collect { progress ->
                    sampler.onBytesRead(progress.bytesTransferred)
                    if (progress.isComplete) {
                        dispatch(ReducerEvent.TransportPayloadSuccess(itemId, bulkRef, clock()))
                    }
                }

                hashingStream.proveEof()
                val actualBytes = hashingStream.bytesRead
                val digest = hashingStream.sha256Digest
                sampler.onComplete(actualBytes)
                dispatch(ReducerEvent.StreamEof(itemId, actualBytes, digest, clock()))
            } catch (e: Exception) {
                if (!_state.value.isTerminal) {
                    val failure = when (e) {
                        is java.io.EOFException, is java.io.IOException, is IllegalStateException ->
                            TransferFailure.ReadFailure("Stream read failed: ${e.message}")
                        else ->
                            TransferFailure.TransferInterrupted("Stream transfer failed: ${e.message}")
                    }
                    dispatch(ReducerEvent.TransportFailure(failure, clock()))
                }
            } finally {
                try { hashingStream.close() } catch (_: Exception) {}
            }
        }
    }

    private suspend fun prepareDestination(itemId: ItemId, sizeBytes: Long): Boolean {
        return withContext(ioDispatcher) {
            try {
                val item = _state.value.itemStates[itemId]?.item
                val displayName = item?.name ?: "received_file"
                val mimeType = item?.mimeType ?: "application/octet-stream"
                val stagingHandle = receiveStore.prepareStaging(
                    sessionId = sessionId,
                    itemId = itemId,
                    expectedBytes = sizeBytes,
                    displayName = displayName,
                    mimeType = mimeType,
                    relativePath = item?.relativePath
                )
                stagingHandles[itemId] = stagingHandle
                currentStagingHandle = stagingHandle

                // LAN-002, LAN-007: Only register typed stream expectation after staging preparation succeeds
                val bulkRef = _state.value.itemStates[itemId]?.bulkRef
                if (bulkRef != null) {
                    transport.registerExpectedStream(
                        StreamExpectation(
                            sessionId = sessionId,
                            itemId = itemId,
                            bulkRef = bulkRef,
                            sizeBytes = sizeBytes
                        )
                    )
                }
                true
            } catch (e: Exception) {
                val failure = (e as? dev.glide.core.ports.ReceiveStoreException)?.failure
                    ?: TransferFailure.InsufficientStorage(sizeBytes, 0)
                dispatch(ReducerEvent.TransportFailure(failure, clock()))
                false
            }
        }
    }

    private fun commitDestination(itemId: ItemId) {
        launchTracked(ioDispatcher) {
            val staging = stagingHandles[itemId] ?: currentStagingHandle ?: return@launchTracked
            val itemState = _state.value.itemStates[itemId] ?: return@launchTracked
            val digest = itemState.actualDigest ?: return@launchTracked
            try {
                val committed = receiveStore.commitVerified(staging, digest)
                _committedFiles.add(committed)
                _committedItemFiles[itemId] = committed
                lastCommittedFileHandle = committed
                stagingHandles.remove(itemId)
                if (currentStagingHandle == staging) {
                    currentStagingHandle = null
                }
                if (isTrueResumeNegotiated() && itemState.item.digest != null) {
                    try {
                        recordResumeBatchCommit?.invoke(sessionId, itemId, itemState.transferredBytes, digest)
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        // The receive store's durable verified commit is authoritative after a crash.
                    }
                }
                historyStore?.updateItemOutcome(
                    itemId,
                    "Verified",
                    itemState.transferredBytes,
                    digest
                )

                if (isTrueResumeNegotiated() &&
                    itemHasResumeMetadata(
                        itemState.item
                    )
                ) {
                    try {
                        purgeResumeItemAuthority
                            ?.invoke(
                                sessionId,
                                itemId
                            )
                    } catch (_: Exception) {
                        // The verified public commit is authoritative. Any stale private resume
                        // journal remains non-public and is bounded by the 24-hour maintenance TTL.
                    }
                }

                dispatch(
                    ReducerEvent.LocalCommitSuccess(
                        itemId,
                        clock()
                    )
                )
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

            // LAN-003: For receiver without predetermined session ID, bind first authenticated HELLO once
            val currentSessionId = boundSessionId
            if (currentSessionId == null) {
                if (role == DeviceRole.Receiver && envelope.type == MessageTypes.HELLO) {
                    boundSessionId = envelope.sessionId
                    _state.value = _state.value.copy(sessionId = envelope.sessionId)
                } else {
                    return@collect
                }
            } else if (envelope.sessionId != currentSessionId) {
                // LAN-001: Enforce exact session ownership before sequencer or dispatch
                dispatch(
                    ReducerEvent.TransportFailure(
                        TransferFailure.ProtocolOrder("Envelope sessionId ${envelope.sessionId.value} does not match active sessionId ${currentSessionId.value}"),
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
                    remoteDisplayName =
                        hello.displayName

                    if (expectedPeerFingerprintHex != null &&
                        NegotiationConstants
                            .CAPABILITY_PERSISTENT_IDENTITY_V1 !in
                        hello.optionalCapabilities
                    ) {
                        throw TrustedSessionAuthenticationException(
                            "Known trusted peer omitted identity-v1 from HELLO."
                        )
                    }

                    dispatch(
                        ReducerEvent.HelloReceived(
                            hello,
                            envelope.messageId,
                            envelope.seq,
                            now,
                            supportedOptionalCapabilities =
                                localOptionalCapabilities()
                                    .toSet()
                        )
                    )
                    beginTrustedSessionAfterNegotiation()
                }
                MessageTypes.HELLO_ACK -> {
                    val ack = ProtocolCodec.decodeBody<dev.glide.core.protocol.HelloAckBody>(envelope)
                    remoteDisplayName =
                        ack.displayName
                    dispatch(
                        ReducerEvent.HelloAckReceived(
                            ack,
                            envelope.messageId,
                            envelope.seq,
                            now
                        )
                    )
                    beginTrustedSessionAfterNegotiation()
                }
                MessageTypes.IDENTITY_CHALLENGE -> {
                    handleIdentityChallenge(
                        envelope
                    )
                }
                MessageTypes.IDENTITY_RESPONSE -> {
                    handleIdentityResponse(
                        envelope
                    )
                }
                MessageTypes.RESUME_REQUEST -> {
                    handleResumeRequest(
                        envelope
                    )
                }
                MessageTypes.RESUME_ACCEPT -> {
                    handleResumeAccept(
                        envelope
                    )
                }
                MessageTypes.RESUME_REJECT -> {
                    handleResumeReject(
                        envelope
                    )
                }
                MessageTypes.RESUME_STREAM_START -> {
                    handleResumeStreamStart(
                        envelope
                    )
                }
                MessageTypes.RESUME_STREAM_READY -> {
                    handleResumeStreamReady(
                        envelope
                    )
                }
                MessageTypes.RESUME_RESULT -> {
                    handleResumeResult(
                        envelope
                    )
                }
                MessageTypes.OFFER_BEGIN -> {
                    requireIdentityReadyForOffer()
                    val begin = ProtocolCodec.decodeBody<dev.glide.core.protocol.OfferBeginBody>(envelope)
                    dispatch(ReducerEvent.OfferBeginReceived(begin, envelope.messageId, envelope.seq, now))
                }
                MessageTypes.OFFER_PAGE -> {
                    requireIdentityReadyForOffer()
                    val page =
                        ProtocolCodec
                            .decodeBody<
                                dev.glide.core.protocol
                                    .OfferPageBody
                                >(envelope)
                    val carriesResumeMetadata =
                        page.items.any {
                            it.rootHash != null ||
                                it.chunkSize !=
                                    null
                        }
                    if (carriesResumeMetadata &&
                        !isTrueResumeNegotiated()
                    ) {
                        throw dev.glide.core.protocol
                            .ProtocolViolationException(
                                TransferFailure
                                    .UnsupportedProtocol(
                                        "OFFER_PAGE carried resume metadata without negotiated resume-v1"
                                    )
                            )
                    }
                    dispatch(
                        ReducerEvent
                            .OfferPageReceived(
                                page,
                                envelope.messageId,
                                envelope.seq,
                                now,
                                dev.glide.core.protocol.AppOfferMetadata.decode(envelope.extensions, page),
                                ResumeOfferMetadata.decode(envelope.extensions, page)
                            )
                    )
                }
                MessageTypes.OFFER_END -> {
                    requireIdentityReadyForOffer()
                    val end = ProtocolCodec.decodeBody<dev.glide.core.protocol.OfferEndBody>(envelope)
                    dispatch(ReducerEvent.OfferEndReceived(end, envelope.messageId, envelope.seq, now))
                    _state.value.batch?.let { recordInitialHistory(it) }
                }
                MessageTypes.OFFER_DECISION -> {
                    requireIdentityReadyForOffer()
                    if (resumeBatch != null) {
                        handleResumeBatchDecision(envelope)
                        return
                    }
                    val decision = ProtocolCodec.decodeBody<dev.glide.core.protocol.OfferDecisionBody>(envelope)
                    val acceptedDigest = ResumeOfferMetadata.decodeAcceptance(envelope.extensions)
                    if (acceptedDigest != null && (!isTrueResumeNegotiated() || !decision.accepted)) {
                        throw TrustedSessionAuthenticationException("Unexpected resume manifest acceptance")
                    }
                    val acceptedBatch = _state.value.batch
                    val canPrepare = role == DeviceRole.Sender &&
                        decision.accepted &&
                        _state.value.phase == SessionPhase.AwaitingConsent &&
                        acceptedBatch != null &&
                        acceptedBatch.id == decision.batchId

                    val bulkRef = if (canPrepare) {
                        try {
                            if (acceptedDigest != null) {
                                saveAcceptedResumeBatch(requireNotNull(acceptedBatch), acceptedDigest)
                            }
                        } catch (error: TrustedSessionAuthenticationException) {
                            throw error
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            dispatch(ReducerEvent.TransportFailure(
                                TransferFailure.WriteFailure("Could not save the accepted transfer for resume"), now
                            ))
                            return
                        }
                        val firstItem = acceptedBatch.items.firstOrNull()
                        if (firstItem != null) prepareSourceItem(firstItem) else null
                    } else null

                    if (canPrepare && bulkRef == null) {
                        val firstItemName = _state.value.batch?.items?.firstOrNull()?.name ?: "first item"
                        dispatch(
                            ReducerEvent.TransportFailure(
                                TransferFailure.SourceUnavailable("Failed to open source stream for item: $firstItemName"),
                                now
                            )
                        )
                    } else {
                        dispatch(ReducerEvent.OfferDecisionReceived(decision, envelope.messageId, envelope.seq, now, bulkRef))
                    }
                }
                MessageTypes.ITEM_START -> {
                    val start = ProtocolCodec.decodeBody<dev.glide.core.protocol.ItemStartBody>(envelope)
                    // LAN-002: Do NOT call transport.registerExpectedStream on unvalidated DTO receipt!
                    // Expected stream registration is deferred until after reducer validation and destination staging creation.
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

                    val nextIdx = _state.value.currentItemIndex + 1
                    val nextItem = _state.value.batch?.items?.getOrNull(nextIdx)
                    val canPrepareNext = role == DeviceRole.Sender &&
                        result.status == "verified" &&
                        (_state.value.phase == SessionPhase.VerifyingItem || _state.value.phase == SessionPhase.TransferringItem) &&
                        nextItem != null

                    val nextBulkRef = if (canPrepareNext) {
                        prepareSourceItem(nextItem)
                    } else null

                    if (canPrepareNext && nextBulkRef == null) {
                        dispatch(
                            ReducerEvent.TransportFailure(
                                TransferFailure.SourceUnavailable("Failed to open source stream for item: ${nextItem.name}"),
                                now
                            )
                        )
                    } else {
                        dispatch(ReducerEvent.ItemResultReceived(result, envelope.messageId, envelope.seq, now, nextBulkRef))
                    }
                }
                MessageTypes.BATCH_END -> {
                    if (resumeBatch != null) {
                        finishResumeBatch(envelope, sender = false)
                        return
                    }
                    val batchEnd = ProtocolCodec.decodeBody<dev.glide.core.protocol.BatchEndBody>(envelope)
                    dispatch(ReducerEvent.BatchEndReceived(batchEnd, envelope.messageId, envelope.seq, now))
                }
                MessageTypes.BATCH_RESULT -> {
                    if (resumeBatch != null) {
                        finishResumeBatch(envelope, sender = true)
                        return
                    }
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: TrustedSessionAuthenticationException) {
            dispatch(
                ReducerEvent.TransportFailure(
                    TransferFailure.AuthenticationMismatch(
                        e.message
                            ?: "Persistent identity authentication failed."
                    ),
                    now
                )
            )
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
                ?: return@collect
            if (curItem.bulkRef == progress.bulkRef && progress.isComplete) {
                dispatch(ReducerEvent.TransportPayloadSuccess(curItem.item.id, progress.bulkRef, clock()))
            }
        }
    }

    private suspend fun observeIncomingStreams() {
        transport.observeIncomingStream().collect { incoming ->
            if (incoming.peerId != peerId) {
                try { incoming.stream.close() } catch (_: Exception) {}
                return@collect
            }

            val resumeStart =
                pendingReceiverResumeStreamStart
            val resumeBulkRef =
                pendingResumeStreamBulkRef
            if (resumeStart != null &&
                resumeBulkRef != null &&
                incoming.bulkRef ==
                    resumeBulkRef &&
                resumeReconnectPlan is
                    ResumeReconnectPlan
                        .Receiver
            ) {
                launchTracked(
                    ioDispatcher
                ) {
                    receiveResumeRemainder(
                        incoming,
                        resumeStart
                    )
                }
                return@collect
            }

            launchTracked(ioDispatcher) {
                // If coordinator is already terminal, close stream immediately (LAN-001)
                if (_state.value.isTerminal) {
                    try { incoming.stream.close() } catch (_: Exception) {}
                    return@launchTracked
                }

                // Security check T04/T07/T17: Strict consent ordering
                val curPhase = _state.value.phase
                if (curPhase != SessionPhase.PreparingItem && curPhase != SessionPhase.TransferringItem) {
                    try { incoming.stream.close() } catch (_: Exception) {}
                    dispatch(
                        ReducerEvent.TransportFailure(
                            TransferFailure.ProtocolOrder("Stream packet received before explicit offer acceptance (phase: $curPhase)"),
                            clock()
                        )
                    )
                    return@launchTracked
                }

                // LAN-001: Exact bulkRef match required; never fall back to currentItemState
                val curItem = _state.value.itemStates.values.firstOrNull { it.bulkRef == incoming.bulkRef }
                if (curItem == null) {
                    try { incoming.stream.close() } catch (_: Exception) {}
                    dispatch(
                        ReducerEvent.TransportFailure(
                            TransferFailure.ProtocolOrder("Unknown incoming stream bulkRef ${incoming.bulkRef.value}"),
                            clock()
                        )
                    )
                    return@launchTracked
                }

                // LAN-001: Check for stale or inactive bulkRef
                val activeItem = _state.value.currentItemState
                if (curItem.phase.isTerminal || activeItem?.item?.id != curItem.item.id) {
                    try { incoming.stream.close() } catch (_: Exception) {}
                    dispatch(
                        ReducerEvent.TransportFailure(
                            TransferFailure.ProtocolOrder("Stale or inactive bulkRef ${incoming.bulkRef.value} for item ${curItem.item.id.value}"),
                            clock()
                        )
                    )
                    return@launchTracked
                }

                val staging = stagingHandles[curItem.item.id] ?: currentStagingHandle

                if (staging == null) {
                    try { incoming.stream.close() } catch (_: Exception) {}
                    dispatch(
                        ReducerEvent.TransportFailure(
                            TransferFailure.DestinationUnavailable("Staging storage unavailable for item ${curItem.item.id}"),
                            clock()
                        )
                    )
                    return@launchTracked
                }

                try {
                    val sampler = StreamProgressSampler(
                        clock = clock,
                        onSample = { bytesRead ->
                            launchTracked {
                                dispatch(ReducerEvent.StreamProgress(curItem.item.id, bytesRead, clock()))
                            }
                        }
                    )
                    val hashResult =
                        receiveStore
                            .openStagingOutput(
                                staging
                            )
                            .use { out ->
                                val resumeEnabled =
                                    isTrueResumeNegotiated() &&
                                        itemHasResumeMetadata(
                                            curItem.item
                                        )

                                if (resumeEnabled) {
                                    val recorder =
                                        recordReceiverResumeChunk
                                            ?: throw ResumeJournalException(
                                                "Receiver resume chunk authority is unavailable"
                                            )
                                    val chunkSize =
                                        curItem.item
                                            .resumeChunkSize
                                            ?: throw ResumeJournalException(
                                                "Resume chunk size is missing"
                                            )

                                    StreamHasher
                                        .copyAndHashWithChunks(
                                            inputStream =
                                                incoming.stream,
                                            outputStream =
                                                out,
                                            expectedBytes =
                                                curItem.item
                                                    .sizeBytes,
                                            chunkSize =
                                                chunkSize,
                                            onChunk = {
                                                chunkIndex,
                                                chunkData ->
                                                val recorded =
                                                    recorder(
                                                        sessionId,
                                                        curItem.item.id,
                                                        chunkIndex,
                                                        chunkData
                                                    )
                                                if (!recorded) {
                                                    throw ResumeJournalException(
                                                        "Durable resume journal rejected chunk " +
                                                            chunkIndex
                                                    )
                                                }
                                            },
                                            onProgress = {
                                                bytesRead ->
                                                sampler
                                                    .onBytesRead(
                                                        bytesRead
                                                    )
                                            }
                                        )
                                } else {
                                    StreamHasher
                                        .copyAndHash(
                                            inputStream =
                                                incoming.stream,
                                            outputStream =
                                                out,
                                            expectedBytes =
                                                curItem.item
                                                    .sizeBytes,
                                            onProgress = {
                                                bytesRead ->
                                                sampler
                                                    .onBytesRead(
                                                        bytesRead
                                                    )
                                            }
                                        )
                                }
                            }
                    sampler.onComplete(hashResult.sizeBytes)
                    dispatch(ReducerEvent.StreamEof(curItem.item.id, hashResult.sizeBytes, hashResult.digest, clock()))
                } catch (
                    e: ResumeJournalException
                ) {
                    dispatch(
                        ReducerEvent.TransportFailure(
                            TransferFailure
                                .WriteFailure(
                                    "Receive resume journal failed: ${e.message}"
                                ),
                            clock()
                        )
                    )
                } catch (e: IllegalStateException) {
                    dispatch(ReducerEvent.TransportFailure(TransferFailure.LimitExceeded("Receive stream limit exceeded: ${e.message}"), clock()))
                } catch (e: Exception) {
                    val isEnospc = e.message?.contains("ENOSPC", ignoreCase = true) == true ||
                        e.message?.contains("No space left on device", ignoreCase = true) == true
                    val failure = if (isEnospc) {
                        TransferFailure.InsufficientStorage(curItem.item.sizeBytes, 0L)
                    } else {
                        TransferFailure.WriteFailure("Receive stream failed: ${e.message}")
                    }
                    dispatch(ReducerEvent.TransportFailure(failure, clock()))
                } finally {
                    try { incoming.stream.close() } catch (_: Exception) {}
                }
            }
        }
    }

    private suspend fun receiveResumeRemainder(
        incoming: dev.glide.core.ports.IncomingStream,
        start: ResumeStreamStartDto
    ) {
        val accepted =
            _resumeReconnectState.value
                as? ResumeReconnectState
                    .ReceiverAccepted
                ?: run {
                    try {
                        incoming.stream.close()
                    } catch (_: Exception) {
                    }
                    return
                }
        val recorder =
            recordReceiverResumeChunk
                ?: run {
                    try {
                        incoming.stream.close()
                    } catch (_: Exception) {
                    }
                    _resumeReconnectState.value =
                        ResumeReconnectState
                            .Failed(
                                WireErrorCodes
                                    .WRITE_FAILURE
                            )
                    return
                }
        val finalizer =
            finalizeReceiverResumeItem
                ?: run {
                    try {
                        incoming.stream.close()
                    } catch (_: Exception) {
                    }
                    _resumeReconnectState.value =
                        ResumeReconnectState
                            .Failed(
                                WireErrorCodes
                                    .WRITE_FAILURE
                            )
                    return
                }

        val remaining =
            dev.glide.core.protocol
                .DecimalByteCount
                .parse(
                    start.remainingBytes
                )

        try {
            reportResumedBytes(accepted.itemId, accepted.startByteOffset, force = true)
            StreamHasher
                .copyAndHashWithChunks(
                    inputStream =
                        incoming.stream,
                    outputStream = null,
                    expectedBytes =
                        remaining,
                    chunkSize =
                        start.chunkSize,
                    startChunkIndex =
                        start.startChunkIndex,
                    onChunk = {
                        chunkIndex,
                        chunkData ->
                        val recorded =
                            recorder(
                                accepted
                                    .originalSessionId,
                                accepted.itemId,
                                chunkIndex,
                                chunkData
                            )
                        if (!recorded) {
                            throw ResumeJournalException(
                                "Resume chunk could not atomically advance the durable journal"
                            )
                        }
                        reportResumedBytes(accepted.itemId, chunkIndex * start.chunkSize + chunkData.size)
                    }
                )

            if (resumeBatch != null)
                _state.value = _state.value.copy(phase = SessionPhase.VerifyingItem)
            val committed =
                finalizer(
                    sessionId,
                    accepted
                        .originalSessionId,
                    accepted.itemId,
                    start
                        .wholeFileSha256Hex
                )
            _committedFiles.add(
                committed
            )
            _committedItemFiles[
                accepted.itemId
            ] = committed
            lastCommittedFileHandle =
                committed

            val result =
                ResumeResultDto(
                    profile =
                        dev.glide.core.protocol
                            .IdentityResumeTranscript
                            .RESUME_PROFILE,
                    requestMessageId =
                        accepted
                            .requestMessageId,
                    originalSessionId =
                        accepted
                            .originalSessionId
                            .value,
                    itemId =
                        accepted.itemId.value,
                    status = "verified",
                    sizeBytes =
                        dev.glide.core.protocol
                            .DecimalByteCount
                            .serialize(
                                accepted
                                    .totalSizeBytes
                            ),
                    sha256 =
                        start
                            .wholeFileSha256Hex
                )
            if (resumeBatch != null) {
                recordResumeBatchCommit?.invoke(accepted.originalSessionId, accepted.itemId,
                    accepted.totalSizeBytes, dev.glide.core.domain.Sha256Digest(start.wholeFileSha256Hex))
                advanceResumeBatchItem(startReceiver = false)
            }
            sendHandshakeControl(
                MessageTypes.RESUME_RESULT,
                ProtocolCodec.encodeBody(
                    result
                )
            )

            pendingReceiverResumeStreamStart =
                null
            pendingResumeStreamBulkRef =
                null
            if (resumeBatch != null) {
                startCurrentResumeItem()
                return
            }
            _resumeReconnectState.value =
                ResumeReconnectState
                    .Verified(
                        originalSessionId =
                            accepted
                                .originalSessionId,
                        itemId =
                            accepted.itemId,
                        sizeBytes =
                            accepted
                                .totalSizeBytes,
                        sha256Hex =
                            start
                                .wholeFileSha256Hex
                    )
        } catch (
            error: Exception
        ) {
            val result =
                ResumeResultDto(
                    profile =
                        dev.glide.core.protocol
                            .IdentityResumeTranscript
                            .RESUME_PROFILE,
                    requestMessageId =
                        accepted
                            .requestMessageId,
                    originalSessionId =
                        accepted
                            .originalSessionId
                            .value,
                    itemId =
                        accepted.itemId.value,
                    status = "failed",
                    code =
                        WireErrorCodes
                            .INTEGRITY_MISMATCH
                )
            try {
                sendHandshakeControl(
                    MessageTypes.RESUME_RESULT,
                    ProtocolCodec.encodeBody(
                        result
                    )
                )
            } catch (_: Exception) {
            }

            _resumeReconnectState.value =
                ResumeReconnectState
                    .Failed(
                        WireErrorCodes
                            .INTEGRITY_MISMATCH
                    )
        } finally {
            try {
                incoming.stream.close()
            } catch (_: Exception) {
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
