package dev.glide.app.service

import dev.glide.app.ui.discovery.BlockedReason
import dev.glide.app.ui.discovery.DeviceType
import dev.glide.app.ui.discovery.DiscoveryUiState
import dev.glide.app.ui.pairing.PairingUiState
import dev.glide.app.ui.selection.SelectedTransferStore
import dev.glide.app.ui.transfer.TransferUiState
import dev.glide.app.ui.transfer.toUiState
import dev.glide.app.util.BatchTransferRateTracker
import dev.glide.core.coordinator.ItemPhase
import dev.glide.core.coordinator.SessionPhase
import dev.glide.core.coordinator.SessionState
import dev.glide.core.coordinator.TransferCoordinator
import dev.glide.core.domain.BatchId
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.TransferBatch
import dev.glide.core.ports.ConnectionEvent
import dev.glide.core.ports.TransferTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service-owned Android transfer runtime (HARD-005).
 *
 * The object is process-scoped for observation/rebinding, but active transport/coordinator execution
 * is impossible until [TransferService] attaches its own [CoroutineScope]. ViewModels observe the
 * immutable state flows and issue typed [TransferRuntimeCommand] values; they never own the active
 * transfer or pairing coroutine scope.
 */
@Singleton
class TransferRuntime private constructor(
    private val sessionFactory: TransferSessionFactory?,
    @Suppress("unused") private val testInstance: Boolean
) {

    @Inject
    constructor(sessionFactory: TransferSessionFactory) : this(sessionFactory, false)

    private val lock = Any()

    private var serviceScope: CoroutineScope? = null
    private var terminalCallback: (() -> Unit)? = null

    private var activeCoordinator: TransferCoordinator? = null
    private var activeCoordinatorJob: Job? = null
    private var activePeerDisplayName: String = "Nearby device"

    private var pairingTransport: TransferTransport? = null
    private var pairingJob: Job? = null
    private var pairingSuccessCallback: ((PeerId) -> Unit)? = null

    private val _transferState = MutableStateFlow<TransferUiState>(TransferUiState.Idle)
    val transferState: StateFlow<TransferUiState> = _transferState.asStateFlow()

    private val _pairingState = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val pairingState: StateFlow<PairingUiState> = _pairingState.asStateFlow()

    private val _discoveryState = MutableStateFlow<DiscoveryUiState>(
        DiscoveryUiState.Blocked(
            BlockedReason.TransportError("Production discovery is not attached yet")
        )
    )
    val discoveryState: StateFlow<DiscoveryUiState> = _discoveryState.asStateFlow()

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    internal var timeProvider: () -> Long = { System.currentTimeMillis() }
    private var rateTracker = BatchTransferRateTracker(timeProvider = { timeProvider() })

    private var lastTerminalState: SessionState? = null
    private var lastSenderBatch: TransferBatch? = null
    private var lastSourceMap: Map<dev.glide.core.domain.ItemId, SourceHandle> = emptyMap()

    /**
     * Attaches the only execution scope allowed to own active transfer/pairing work.
     * This must be the foreground service scope in production.
     */
    fun attachServiceScope(
        scope: CoroutineScope,
        onTerminal: (() -> Unit)? = null
    ) {
        synchronized(lock) {
            if (serviceScope === scope) {
                terminalCallback = onTerminal
                return
            }

            check(activeCoordinator == null) {
                "Cannot replace TransferRuntime service scope while a coordinator is active"
            }

            pairingJob?.cancel()
            pairingJob = null
            pairingTransport = null
            pairingSuccessCallback = null

            serviceScope = scope
            terminalCallback = onTerminal
        }
    }

    /**
     * Releases service ownership. A non-terminal active coordinator is stopped and surfaced as an
     * interrupted failure instead of being allowed to continue in a ViewModel/application scope.
     * Terminal UI state is preserved for result/retry presentation.
     */
    fun detachServiceScope() {
        val coordinatorToStop: TransferCoordinator?
        synchronized(lock) {
            coordinatorToStop = activeCoordinator
            activeCoordinatorJob?.cancel()
            activeCoordinatorJob = null
            pairingJob?.cancel()
            pairingJob = null
            pairingTransport = null
            pairingSuccessCallback = null

            if (coordinatorToStop != null) {
                captureTerminalSnapshot(coordinatorToStop)
                if (!coordinatorToStop.state.value.phase.isTerminal) {
                    val current = _transferState.value
                    _transferState.value = TransferUiState.Failed(
                        sessionId = coordinatorToStop.sessionId.value,
                        role = coordinatorToStop.role,
                        message = "Transfer service stopped before the session completed.",
                        code = "SERVICE_STOPPED",
                        isRecoverable = true,
                        isInterrupted = true,
                        unfinishedCount = coordinatorToStop.state.value.unfinishedCount,
                        totalCount = coordinatorToStop.state.value.batch?.items?.size ?: 0,
                        canRetry = coordinatorToStop.role == dev.glide.core.domain.DeviceRole.Sender,
                        peerName = when (current) {
                            is TransferUiState.Connecting -> current.peerName
                            is TransferUiState.AwaitingConsent -> current.peerName
                            is TransferUiState.Transferring -> current.peerName
                            is TransferUiState.Verifying -> current.peerName
                            is TransferUiState.Completed -> current.peerName
                            is TransferUiState.Failed -> current.peerName
                            else -> activePeerDisplayName
                        }
                    )
                }
            }

            activeCoordinator = null
            _isSessionActive.value = false
            _activeSessionId.value = null
            serviceScope = null
            terminalCallback = null
            rateTracker.reset()
        }
        coordinatorToStop?.stop()
    }

    fun isServiceAttached(): Boolean = synchronized(lock) { serviceScope != null }

    /**
     * Single typed command entry point for production/user actions.
     */
    fun dispatch(command: TransferRuntimeCommand): Boolean {
        return when (command) {
            is TransferRuntimeCommand.StartSender -> startSender(command)
            is TransferRuntimeCommand.StartReceiver -> startReceiver(command)
            is TransferRuntimeCommand.BindPairingTransport -> bindPairingTransport(
                command.transport,
                command.onPairingSuccess
            )
            is TransferRuntimeCommand.ConfirmPairing -> confirmPairing(command.peerId)
            is TransferRuntimeCommand.RejectPairing -> rejectPairing(command.peerId)
            is TransferRuntimeCommand.AcceptOffer -> acceptOffer(command.sessionId)
            is TransferRuntimeCommand.RejectOffer -> rejectOffer(command.sessionId, command.reason)
            is TransferRuntimeCommand.Cancel -> cancel(command.sessionId, command.reason)
            is TransferRuntimeCommand.Resume -> resume(command.sessionId)
            is TransferRuntimeCommand.StartReceiveAvailability -> startReceiveAvailability(command.localDeviceName)
            TransferRuntimeCommand.StopReceiveAvailability -> stopReceiveAvailability()
        }
    }

    private fun startSender(command: TransferRuntimeCommand.StartSender): Boolean {
        val factory = sessionFactory ?: return false
        val scope = synchronized(lock) { serviceScope } ?: return false
        val prepared = SelectedTransferStore.get(command.selectionId) ?: return false
        val coordinator = factory.createSenderSession(
            sessionId = SessionId(UUID.randomUUID().toString()),
            peerId = command.peerId,
            peerDisplayName = command.peerDisplayName,
            senderBatch = prepared.batch,
            sourceMap = prepared.sourceMap,
            transport = command.transport,
            scope = scope
        )
        return startCoordinator(coordinator, command.peerDisplayName)
    }

    private fun startReceiver(command: TransferRuntimeCommand.StartReceiver): Boolean {
        val factory = sessionFactory ?: return false
        val scope = synchronized(lock) { serviceScope } ?: return false
        val coordinator = factory.createReceiverSession(
            peerId = command.peerId,
            peerDisplayName = command.peerDisplayName,
            sessionId = command.sessionId,
            transport = command.transport,
            scope = scope
        )
        return startCoordinator(coordinator, command.peerDisplayName)
    }

    /**
     * Internal bridge for existing coordinator-focused tests while production construction stays in
     * this runtime. The runtime still owns observation/execution through the attached service scope.
     */
    internal fun attachCoordinatorForTesting(
        coordinator: TransferCoordinator,
        peerDisplayName: String = "Nearby device"
    ): Boolean = startCoordinator(coordinator, peerDisplayName)

    private fun startCoordinator(
        coordinator: TransferCoordinator,
        peerDisplayName: String
    ): Boolean {
        val scope = synchronized(lock) {
            val owner = serviceScope ?: return false

            if (activeCoordinator?.sessionId == coordinator.sessionId) {
                return true
            }

            if (activeCoordinator != null && _isSessionActive.value) {
                return false
            }

            activeCoordinatorJob?.cancel()
            activeCoordinator?.let {
                captureTerminalSnapshot(it)
                it.stop()
            }

            activeCoordinator = coordinator
            activePeerDisplayName = peerDisplayName
            lastTerminalState = null
            lastSenderBatch = null
            lastSourceMap = emptyMap()
            _activeSessionId.value = coordinator.sessionId.value
            _isSessionActive.value = true
            rateTracker.reset()

            owner
        }

        observeCoordinator(coordinator, peerDisplayName, scope)
        coordinator.start()
        return true
    }

    private fun observeCoordinator(
        coordinator: TransferCoordinator,
        peerDisplayName: String,
        scope: CoroutineScope
    ) {
        synchronized(lock) {
            activeCoordinatorJob?.cancel()
            var lastPhase: SessionPhase? = null
            activeCoordinatorJob = scope.launch {
                coordinator.state.collect { sessionState ->
                    if (activeCoordinator !== coordinator) return@collect

                    val committedKeys = coordinator.committedItemFiles
                        .mapKeys { it.key.value }
                        .mapValues { it.value.value }
                    val baseUiState = sessionState.toUiState(
                        peerDisplayName = peerDisplayName,
                        committedFileKey = coordinator.lastCommittedFileHandle?.value,
                        committedItemKeys = committedKeys
                    )

                    val phaseChanged = sessionState.phase != lastPhase
                    lastPhase = sessionState.phase

                    if (baseUiState is TransferUiState.Transferring) {
                        val snapshot = rateTracker.updateProgress(
                            cumulativeBatchBytes = baseUiState.transferredBytes,
                            currentItemIndex = baseUiState.itemIndex,
                            forceImmediate = phaseChanged
                        )
                        if (snapshot.shouldEmitUiUpdate || _transferState.value !is TransferUiState.Transferring) {
                            _transferState.value = baseUiState.copy(
                                speedBytesPerSec = snapshot.speedBytesPerSec,
                                isStalled = snapshot.isStalled
                            )
                        }
                    } else {
                        rateTracker.reset()
                        _transferState.value = baseUiState
                    }

                    val active = !sessionState.phase.isTerminal
                    _isSessionActive.value = active
                    _activeSessionId.value = coordinator.sessionId.value.takeIf { active }

                    if (sessionState.phase.isTerminal) {
                        captureTerminalSnapshot(coordinator)
                        coordinator.stop()
                        terminalCallback?.invoke()
                    }
                }
            }
        }
    }

    private fun bindPairingTransport(
        transport: TransferTransport,
        onPairingSuccess: ((PeerId) -> Unit)?
    ): Boolean {
        val scope = synchronized(lock) { serviceScope } ?: return false

        synchronized(lock) {
            pairingJob?.cancel()
            pairingTransport = transport
            pairingSuccessCallback = onPairingSuccess
            pairingJob = scope.launch {
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
                            pairingSuccessCallback?.invoke(event.peer.id)
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
        return true
    }

    private fun confirmPairing(peerId: PeerId): Boolean {
        val transport: TransferTransport
        val scope: CoroutineScope
        synchronized(lock) {
            val current = _pairingState.value
            if (current !is PairingUiState.Verifying || current.localConfirmed || current.peerId != peerId) {
                return false
            }
            transport = pairingTransport ?: return false
            scope = serviceScope ?: return false
            _pairingState.value = current.copy(localConfirmed = true)
        }
        scope.launch { transport.acceptConnection(peerId) }
        return true
    }

    private fun rejectPairing(peerId: PeerId): Boolean {
        val transport: TransferTransport
        val scope: CoroutineScope
        synchronized(lock) {
            val current = _pairingState.value
            if (current !is PairingUiState.Verifying || current.peerId != peerId) {
                return false
            }
            transport = pairingTransport ?: return false
            scope = serviceScope ?: return false
            _pairingState.value = PairingUiState.Failed(
                title = "Verification Cancelled",
                message = "Pairing with ${current.peerName} was cancelled."
            )
        }
        scope.launch { transport.rejectConnection(peerId) }
        return true
    }

    private fun acceptOffer(sessionId: String): Boolean {
        val coordinator = synchronized(lock) {
            if (_activeSessionId.value != sessionId) return false
            activeCoordinator
        } ?: return false
        coordinator.acceptOffer()
        return true
    }

    private fun rejectOffer(sessionId: String, reason: String): Boolean {
        val coordinator = synchronized(lock) {
            if (_activeSessionId.value != sessionId) return false
            activeCoordinator
        } ?: return false
        coordinator.rejectOffer(reason)
        return true
    }

    private fun cancel(sessionId: String, reason: String): Boolean {
        val coordinator: TransferCoordinator?
        synchronized(lock) {
            if (_activeSessionId.value != sessionId) return false
            coordinator = activeCoordinator
            if (coordinator == null) {
                val current = _transferState.value
                _transferState.value = TransferUiState.Cancelled(
                    sessionId = current.sessionId,
                    role = current.role,
                    reason = reason
                )
                _isSessionActive.value = false
                _activeSessionId.value = null
                terminalCallback?.invoke()
                return true
            }
        }
        coordinator?.cancel(reason)
        return true
    }

    /**
     * Exact-session runtime resume command. This is process-lifetime reattachment only; durable
     * byte-level true resume remains owned by HARD-022/HARD-023.
     */
    private fun resume(sessionId: String): Boolean {
        val coordinator = synchronized(lock) {
            if (_activeSessionId.value != sessionId) return false
            if (serviceScope == null) return false
            activeCoordinator
        } ?: return false
        if (coordinator.state.value.phase.isTerminal) return false
        coordinator.start()
        return true
    }

    private fun startReceiveAvailability(localDeviceName: String): Boolean {
        _discoveryState.value = DiscoveryUiState.Blocked(
            BlockedReason.TransportError(
                "Receive availability for ${localDeviceName.ifBlank { "Glide Device" }} is not wired until HARD-006"
            )
        )
        return false
    }

    private fun stopReceiveAvailability(): Boolean {
        _discoveryState.value = DiscoveryUiState.Blocked(
            BlockedReason.TransportError("Receive availability is stopped")
        )
        return true
    }

    fun stopActiveSession(): Boolean {
        val coordinator: TransferCoordinator?
        synchronized(lock) {
            coordinator = activeCoordinator
            activeCoordinatorJob?.cancel()
            activeCoordinatorJob = null
            activeCoordinator = null
            _isSessionActive.value = false
            _activeSessionId.value = null
            rateTracker.reset()
        }
        coordinator?.let {
            captureTerminalSnapshot(it)
            it.stop()
        }
        return coordinator != null
    }

    fun getActiveSessionId(): String? = _activeSessionId.value

    fun getUnfinishedSourceUris(): List<String> {
        val coordinator = synchronized(lock) { activeCoordinator }
        val state = coordinator?.state?.value ?: lastTerminalState ?: return emptyList()
        val batch = state.batch ?: coordinator?.senderBatch ?: lastSenderBatch ?: return emptyList()
        val sourceMap = coordinator?.sourceMap ?: lastSourceMap

        return batch.items
            .filter { item ->
                val itemState = state.itemStates[item.id]
                itemState == null || itemState.phase != ItemPhase.Verified
            }
            .mapNotNull { item -> sourceMap[item.id]?.value }
    }

    internal fun activeCoordinatorForTesting(): TransferCoordinator? = synchronized(lock) { activeCoordinator }

    internal fun setTransferStateForTesting(state: TransferUiState) {
        _transferState.value = state
        val active = state is TransferUiState.Connecting ||
            state is TransferUiState.AwaitingConsent ||
            state is TransferUiState.Transferring ||
            state is TransferUiState.Verifying
        _isSessionActive.value = active
        _activeSessionId.value = state.sessionId.takeIf { active && it.isNotEmpty() }
    }

    internal fun setPairingStateForTesting(state: PairingUiState) {
        _pairingState.value = state
    }

    internal fun setDiscoveryStateForTesting(state: DiscoveryUiState) {
        _discoveryState.value = state
    }

    internal fun resetForTesting() {
        activeCoordinatorJob?.cancel()
        pairingJob?.cancel()
        activeCoordinator?.stop()
        activeCoordinator = null
        activeCoordinatorJob = null
        pairingTransport = null
        pairingJob = null
        pairingSuccessCallback = null
        terminalCallback = null
        serviceScope = null
        activePeerDisplayName = "Nearby device"
        lastTerminalState = null
        lastSenderBatch = null
        lastSourceMap = emptyMap()
        _transferState.value = TransferUiState.Idle
        _pairingState.value = PairingUiState.Idle
        _discoveryState.value = DiscoveryUiState.Blocked(
            BlockedReason.TransportError("Production discovery is not attached yet")
        )
        _isSessionActive.value = false
        _activeSessionId.value = null
        rateTracker.reset()
        timeProvider = { System.currentTimeMillis() }
    }

    private fun captureTerminalSnapshot(coordinator: TransferCoordinator) {
        lastTerminalState = coordinator.state.value
        lastSenderBatch = coordinator.senderBatch
        lastSourceMap = coordinator.sourceMap.toMap()
    }

    companion object {
        internal fun forTesting(sessionFactory: TransferSessionFactory? = null): TransferRuntime {
            return TransferRuntime(sessionFactory, true)
        }
    }
}

/**
 * Typed command surface consumed by presentation/service layers.
 */
sealed interface TransferRuntimeCommand {
    data class StartSender(
        val selectionId: BatchId? = null,
        val transport: TransferTransport,
        val peerId: PeerId,
        val peerDisplayName: String
    ) : TransferRuntimeCommand

    data class StartReceiver(
        val transport: TransferTransport,
        val peerId: PeerId,
        val peerDisplayName: String,
        val sessionId: SessionId? = null
    ) : TransferRuntimeCommand

    data class BindPairingTransport(
        val transport: TransferTransport,
        val onPairingSuccess: ((PeerId) -> Unit)? = null
    ) : TransferRuntimeCommand

    data class ConfirmPairing(val peerId: PeerId) : TransferRuntimeCommand
    data class RejectPairing(val peerId: PeerId) : TransferRuntimeCommand
    data class AcceptOffer(val sessionId: String) : TransferRuntimeCommand
    data class RejectOffer(
        val sessionId: String,
        val reason: String = "USER_REJECTED"
    ) : TransferRuntimeCommand
    data class Cancel(
        val sessionId: String,
        val reason: String = "USER_CANCELLED"
    ) : TransferRuntimeCommand
    data class Resume(val sessionId: String) : TransferRuntimeCommand
    data class StartReceiveAvailability(val localDeviceName: String) : TransferRuntimeCommand
    data object StopReceiveAvailability : TransferRuntimeCommand
}
