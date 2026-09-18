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
import dev.glide.core.protocol.IdentityChallengeDto
import dev.glide.core.protocol.IdentityResponseDto
import dev.glide.core.protocol.MessageTypes
import dev.glide.core.protocol.NegotiationConstants
import dev.glide.core.protocol.ProtocolCodec
import dev.glide.core.protocol.ResumeAcceptDto
import dev.glide.core.protocol.ResumeRejectCodes
import dev.glide.core.protocol.ResumeRejectDto
import dev.glide.core.protocol.ResumeRequestDto
import dev.glide.core.protocol.ResumeResultDto
import dev.glide.core.protocol.ResumeStreamReadyDto
import dev.glide.core.protocol.ResumeStreamStartDto
import dev.glide.core.resume.ResumeAuthorityLookup
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
    val resumeReconnectPlan:
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
    val enableTrueResumeCapability: Boolean = false
) {
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
                sessionId = { sessionId },
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
    private val terminalCleanupLock = Any()
    private var terminalCleanupBarrier: CompletableDeferred<Unit>? = null

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
        scope.launch {
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
                    return@launch
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
                        return@launch
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
                        return@launch
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
                    } catch (error: Exception) {
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
                        return@launch
                    }
                }
            }

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
        activeJobs.forEach { it.cancel() }
        activeJobs.clear()
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
        if (resumeReconnectPlan != null) {
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
                        body = body
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
            maybeSubmitSenderOffer()
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
            maybeStartReceiverResumeRequest()
        }
    }

    private suspend fun maybeStartReceiverResumeRequest() {
        val plan =
            resumeReconnectPlan
                as? ResumeReconnectPlan
                    .Receiver
                ?: return
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

        val envelope =
            sendHandshakeControl(
                MessageTypes.RESUME_REQUEST,
                ProtocolCodec.encodeBody(
                    request
                )
            )
        pendingResumeRequest =
            envelope
        pendingReceiverResumeJournal =
            journal
        _resumeReconnectState.value =
            ResumeReconnectState
                .AwaitingResponse(
                    requestMessageId =
                        envelope
                            .messageId
                            .value,
                    originalSessionId =
                        journal
                            .originalSessionId,
                    itemId =
                        journal.itemId,
                    requestedChunkIndex =
                        journal
                            .verifiedChunkCount,
                    requestedByteOffset =
                        journal
                            .contiguousByteOffset
                )

        resumeTimeoutJob?.cancel()
        resumeTimeoutJob =
            scope.launch {
                kotlinx.coroutines.delay(
                    30_000L
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
        scope.launch(ioDispatcher) {
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
                return@launch
            }

            val hashingStream = prepared.stream
            try {
                val sampler = StreamProgressSampler(
                    clock = clock,
                    onSample = { bytesRead ->
                        scope.launch {
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
        scope.launch(ioDispatcher) {
            val staging = stagingHandles[itemId] ?: currentStagingHandle ?: return@launch
            val itemState = _state.value.itemStates[itemId] ?: return@launch
            val digest = itemState.actualDigest ?: return@launch
            try {
                val committed = receiveStore.commitVerified(staging, digest)
                _committedFiles.add(committed)
                _committedItemFiles[itemId] = committed
                lastCommittedFileHandle = committed
                stagingHandles.remove(itemId)
                if (currentStagingHandle == staging) {
                    currentStagingHandle = null
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
                                now
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
                    val decision = ProtocolCodec.decodeBody<dev.glide.core.protocol.OfferDecisionBody>(envelope)
                    val canPrepare = role == DeviceRole.Sender &&
                        decision.accepted &&
                        _state.value.phase == SessionPhase.AwaitingConsent &&
                        _state.value.batch != null &&
                        _state.value.batch?.id == decision.batchId

                    val bulkRef = if (canPrepare) {
                        val firstItem = _state.value.batch?.items?.firstOrNull()
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
            if (incoming.peerId != peerId) {
                try { incoming.stream.close() } catch (_: Exception) {}
                return@collect
            }

            scope.launch(ioDispatcher) {
                // If coordinator is already terminal, close stream immediately (LAN-001)
                if (_state.value.isTerminal) {
                    try { incoming.stream.close() } catch (_: Exception) {}
                    return@launch
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
                    return@launch
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
                    return@launch
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
                    return@launch
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
