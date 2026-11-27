package dev.glide.app.service

import android.content.Context
import dev.glide.app.discovery.ProductionDiscoveryController
import dev.glide.app.diagnostics.AndroidTransferDiagnosticsObserver
import dev.glide.app.lan.LanReceiverRuntimeState
import dev.glide.app.lan.ProductionLanController
import dev.glide.app.ui.discovery.BlockedReason
import dev.glide.app.ui.discovery.DeviceType
import dev.glide.app.ui.discovery.DiscoveredPeer
import dev.glide.app.ui.discovery.DiscoveryUiState
import dev.glide.app.ui.discovery.ReceiveReadinessUiState
import dev.glide.app.ui.pairing.PairingUiState
import dev.glide.app.ui.selection.SelectedTransferStore
import dev.glide.app.ui.transfer.TransferUiState
import dev.glide.app.ui.transfer.ExportUiStatus
import dev.glide.app.util.FileOpener
import dev.glide.app.util.ReceivedFileSaver
import dev.glide.data.export.AndroidFileExporter
import dev.glide.app.ui.transfer.toUiState
import dev.glide.core.coordinator.ItemPhase
import dev.glide.core.coordinator.ReducerEvent
import dev.glide.core.coordinator.SessionPhase
import dev.glide.core.coordinator.SessionState
import dev.glide.core.coordinator.TransferCoordinator
import dev.glide.core.domain.BatchId
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferFailure
import dev.glide.core.identity.LocalIdentityInitialization
import dev.glide.core.identity.PersistentTrustManager
import dev.glide.core.identity.VerifiedPeerIdentity
import dev.glide.core.pairing.LanInvitation
import dev.glide.core.ports.AuthenticatedChannelContextProvider
import dev.glide.core.ports.ConnectionEvent
import dev.glide.core.ports.TransferTransport
import dev.glide.core.resume.ResumeBatchReconnect
import dev.glide.data.store.ResumableStagingStore
import dev.glide.transport.lan.LanTransferTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

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
    private val discoveryController: ProductionDiscoveryController?,
    private val lanController: ProductionLanController?,
    private val persistentTrustManager: PersistentTrustManager?,
    private val resumableStagingStore: ResumableStagingStore?,
    private val applicationContext: Context?,
    @Suppress("unused") private val testInstance: Boolean
) {

    @Inject
    constructor(
        sessionFactory: TransferSessionFactory,
        discoveryController: ProductionDiscoveryController,
        lanController: ProductionLanController,
        persistentTrustManager: PersistentTrustManager,
        resumableStagingStore: ResumableStagingStore,
        @ApplicationContext applicationContext: Context
    ) : this(
        sessionFactory,
        discoveryController,
        lanController,
        persistentTrustManager,
        resumableStagingStore,
        applicationContext,
        false
    )

    private val lock = Any()

    private var serviceScope: CoroutineScope? = null
    private var onTransferStarting: (() -> Boolean)? = null
    private var onVerifiedCompletion: ((TransferUiState.Completed) -> Unit)? = null
    private var terminalCallback: (() -> Unit)? = null
    private var terminalCallbackSessionId: String? = null
    private var autoSavedSessionId: String? = null

    private var activeCoordinator: TransferCoordinator? = null
    private var activeCoordinatorJob: Job? = null
    private var activePeerDisplayName: String = "Nearby device"
    private val pausedSessionIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private var pairingTransport: TransferTransport? = null
    private var pairingJob: Job? = null
    private var pairingSuccessCallback: ((PeerId) -> Unit)? = null

    private var trustObservationJob: Job? = null
    private var pendingVerifiedPeer: VerifiedPeerIdentity? = null
    private val _trustState = MutableStateFlow(TrustRuntimeState())
    val trustState: StateFlow<TrustRuntimeState> = _trustState.asStateFlow()

    private val _transferState = MutableStateFlow<TransferUiState>(TransferUiState.Idle)
    val transferState: StateFlow<TransferUiState> = _transferState.asStateFlow()
    private val _autoSaveStatus = MutableStateFlow<ExportUiStatus>(ExportUiStatus.Idle)
    val autoSaveStatus: StateFlow<ExportUiStatus> = _autoSaveStatus.asStateFlow()
    private val _autoSavedItemIds = MutableStateFlow<Set<String>>(emptySet())
    val autoSavedItemIds: StateFlow<Set<String>> = _autoSavedItemIds.asStateFlow()

    private val _pairingState = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val pairingState: StateFlow<PairingUiState> = _pairingState.asStateFlow()

    private val _fallbackDiscoveryState = MutableStateFlow<DiscoveryUiState>(
        DiscoveryUiState.Blocked(
            BlockedReason.TransportError("Production discovery is not attached")
        )
    )
    val discoveryState: StateFlow<DiscoveryUiState> =
        discoveryController?.discoveryState ?: _fallbackDiscoveryState.asStateFlow()

    private val _fallbackReceiveReadinessState = MutableStateFlow<ReceiveReadinessUiState>(
        ReceiveReadinessUiState.Blocked(
            BlockedReason.TransportError("Production advertising is not attached")
        )
    )
    val receiveReadinessState: StateFlow<ReceiveReadinessUiState> =
        discoveryController?.receiveReadinessState ?: _fallbackReceiveReadinessState.asStateFlow()

    private val _fallbackLanReceiverState =
        MutableStateFlow<LanReceiverRuntimeState>(LanReceiverRuntimeState.Idle)
    val lanReceiverState: StateFlow<LanReceiverRuntimeState> =
        lanController?.receiverState ?: _fallbackLanReceiverState.asStateFlow()

    private val _onlineLanPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val onlineLanPeers: StateFlow<List<DiscoveredPeer>> = _onlineLanPeers.asStateFlow()
    private val lanPeerDiscoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lanPeerDiscoveryJob: Job? = null

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    private val _retainedTransfersVersion = MutableStateFlow(0L)
    val retainedTransfersVersion: StateFlow<Long> = _retainedTransfersVersion.asStateFlow()

    private fun bumpRetainedTransfersVersion() {
        _retainedTransfersVersion.value = _retainedTransfersVersion.value + 1L
    }

    internal var timeProvider: () -> Long = { System.currentTimeMillis() }
    private val statePresenter = TransferStatePresenter(timeProvider = { timeProvider() })
    private val diagnosticsObserver: AndroidTransferDiagnosticsObserver? =
        applicationContext?.let { AndroidTransferDiagnosticsObserver(it) }

    private var lastTerminalState: SessionState? = null
    private var lastSenderBatch: TransferBatch? = null
    private var lastSourceMap: Map<dev.glide.core.domain.ItemId, SourceHandle> = emptyMap()

    /**
     * Attaches the only execution scope allowed to own active transfer/pairing work.
     * This must be the foreground service scope in production.
     */
    fun attachServiceScope(
        scope: CoroutineScope,
        onTransferStarting: (() -> Boolean)? = null,
        onVerifiedCompletion: ((TransferUiState.Completed) -> Unit)? = null,
        onTerminal: (() -> Unit)? = null
    ) {
        synchronized(lock) {
            this.onTransferStarting = onTransferStarting
            this.onVerifiedCompletion = onVerifiedCompletion
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
            terminalCallbackSessionId = null
            autoSavedSessionId = null
        }

        startTrustObservation(scope)
    }

    /**
     * Releases service ownership. A non-terminal active coordinator is stopped and surfaced as an
     * interrupted failure instead of being allowed to continue in a ViewModel/application scope.
     * Terminal UI state is preserved for result/retry presentation.
     */
    fun detachServiceScope(): Job? {
        val coordinatorToStop: TransferCoordinator?
        val ownerScope: CoroutineScope?
        synchronized(lock) {
            coordinatorToStop = activeCoordinator
            ownerScope = serviceScope
            activeCoordinatorJob?.cancel()
            activeCoordinatorJob = null
            pairingJob?.cancel()
            pairingJob = null
            pairingTransport = null
            pairingSuccessCallback = null
            trustObservationJob?.cancel()
            trustObservationJob = null
            pendingVerifiedPeer = null
            _trustState.value = _trustState.value.copy(pendingEnrollment = null)

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
            if (_pairingState.value is PairingUiState.Connected ||
                _pairingState.value is PairingUiState.Connecting ||
                _pairingState.value is PairingUiState.Verifying
            ) {
                _pairingState.value = PairingUiState.Idle
            }
            serviceScope = null
            onTransferStarting = null
            onVerifiedCompletion = null
            terminalCallback = null
            terminalCallbackSessionId = null
            statePresenter.reset()
        }

        discoveryController?.stopAdvertising()
        lanController?.stopAll()

        if (coordinatorToStop == null) return null
        if (ownerScope == null) {
            coordinatorToStop.stop()
            return null
        }

        return ownerScope.launch {
            try {
                if (coordinatorToStop.state.value.phase.isTerminal) {
                    coordinatorToStop.awaitTerminalCleanup()
                } else {
                    coordinatorToStop.interruptAndAwaitCleanup("SERVICE_STOPPED")
                }
            } finally {
                coordinatorToStop.stop()
            }
        }
    }

    /** Records a foreground-service startup failure through the owned session reducer. */
    suspend fun reportForegroundServiceFailure(failure: TransferFailure) {
        val coordinator = synchronized(lock) { activeCoordinator }
        if (coordinator != null && !coordinator.state.value.phase.isTerminal) {
            try {
                coordinator.dispatch(
                    ReducerEvent.TransportFailure(failure, System.currentTimeMillis())
                )
                coordinator.awaitTerminalCleanup()
            } finally {
                finalizeTerminalCoordinator(coordinator)
            }
            return
        }

        synchronized(lock) {
            val current = _transferState.value
            if (current is TransferUiState.Completed || current is TransferUiState.Failed) return
            _transferState.value = TransferUiState.Failed(
                sessionId = current.sessionId,
                role = current.role,
                message = failure.message,
                code = failure.code,
                isRecoverable = failure.isRecoverable
            )
            _isSessionActive.value = false
            _activeSessionId.value = null
        }
    }

    fun isServiceAttached(): Boolean = synchronized(lock) { serviceScope != null }

    data class RetainedTransfer(val sessionId: String, val label: String)

    suspend fun listRetainedTransfers(): List<RetainedTransfer> {
        val manager = persistentTrustManager ?: return emptyList()
        refreshPersistentTrustState()
        return sessionFactory?.listRetainedBatches().orEmpty().mapNotNull { batch ->
            val sender = batch.role == DeviceRole.Sender
            val fingerprint = if (sender) batch.manifest.receiverFingerprint else batch.manifest.senderFingerprint
            val peer = manager.trustedPeerByFingerprint(fingerprint.value) ?: return@mapNotNull null
            val direction = if (sender) "Send to" else "Receive from"
            RetainedTransfer(batch.manifest.sessionId.value, "$direction ${peer.displayName} · ${batch.manifest.items.size} files")
        }
    }

    suspend fun discardRetainedTransfer(sessionId: String) {
        if (_isSessionActive.value) return
        val original = runCatching { SessionId(sessionId) }.getOrNull() ?: return
        resumableStagingStore?.purgeOriginalSession(original.value)
        bumpRetainedTransfersVersion()
    }

    suspend fun startRetainedResume(sessionId: String, localDeviceName: String): Boolean {
        val manager = persistentTrustManager ?: return false
        val controller = lanController ?: return false
        val scope = synchronized(lock) { if (_isSessionActive.value) null else serviceScope } ?: return false
        val batch = sessionFactory?.listRetainedBatches()?.firstOrNull { it.manifest.sessionId.value == sessionId }
            ?: return failPairing("Transfer unavailable", "The retained transfer expired or was discarded.")
        val fingerprint = if (batch.role == DeviceRole.Sender)
            batch.manifest.receiverFingerprint else batch.manifest.senderFingerprint
        val peer = manager.trustedPeerByFingerprint(fingerprint.value)
            ?: return failPairing("Device no longer paired", "The original device is no longer trusted.")
        _transferState.value = TransferUiState.Idle
        _pairingState.value = PairingUiState.Connecting(
            peerId = PeerId("resume-${fingerprint.value.take(16)}"), peerName = peer.displayName,
            deviceType = DeviceType.fromDeviceName(peer.displayName)
        )
        val opening = LanTransferTransport.TrustedResumeOpening(
            sessionId, if (batch.role == DeviceRole.Sender) "sender" else "receiver", batch.manifest.digest().value)
        return controller.startTrustedSender(
            ownerScope = scope, trustedPeer = peer, trustManager = manager, localDeviceName = localDeviceName,
            onConnected = { transport, remote ->
                scope.launch {
                    if (!openResumeCoordinator(transport, remote.id, remote.displayName, localInitiated = true, localDisplayName = localDeviceName)) {
                        transport.disconnect(remote.id)
                        failPairing("Resume failed", "The approved transfer could not be reopened.")
                        terminalCallback?.invoke()
                    } else {
                        _pairingState.value = PairingUiState.Connected(remote)
                    }
                }
            },
            onFailure = { failure ->
                failPairing("Could not resume transfer", failure.message)
                _transferState.value = TransferUiState.Failed(
                    sessionId, batch.role, "Could not resume. Keep Glide open on the paired device and try again.",
                    "RESUME_CONNECTION_FAILED", isRecoverable = true
                )
                terminalCallback?.invoke()
            },
            resume = opening
        )
    }

    private suspend fun openResumeCoordinator(
        transport: LanTransferTransport,
        peerId: PeerId,
        peerName: String,
        localInitiated: Boolean,
        localDisplayName: String = "Glide Device"
    ): Boolean = try {
        startResumeCoordinator(transport, peerId, peerName, localInitiated, localDisplayName)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private suspend fun startResumeCoordinator(
        transport: LanTransferTransport,
        peerId: PeerId,
        peerName: String,
        localInitiated: Boolean,
        localDisplayName: String = "Glide Device"
    ): Boolean {
        val factory = sessionFactory ?: return false
        val manager = persistentTrustManager ?: return false
        val scope = synchronized(lock) { serviceScope } ?: return false
        val open = transport.trustedResumeOpening(peerId) ?: return false
        val fingerprint = transport.trustedInboundPeerFingerprint(peerId)?.let(::Sha256Digest) ?: return false
        val actualSession = transport.authenticatedChannelContext(peerId)?.sessionId ?: return false
        val original = SessionId(open.originalSessionId)
        val sender = (open.initiatorRole == "sender") == localInitiated
        val role = if (sender) DeviceRole.Sender else DeviceRole.Receiver
        val batch = factory.findRetainedBatch(original, role, fingerprint, Sha256Digest(open.manifestDigest)) ?: return false
        val context = ResumeBatchReconnect(batch.manifest, batch.verifiedCount, localInitiated)
        val first = batch.manifest.items.first().id
        val coordinator = if (sender) factory.createResumeSenderSession(
            actualSession, original, first, peerId, peerName, fingerprint.value, transport, scope, manager, context, localDisplayName = localDisplayName
        ) else factory.createResumeReceiverSession(
            actualSession, original, first, peerId, peerName, fingerprint.value, transport, scope, manager, context, localDisplayName = localDisplayName
        )
        return startCoordinator(coordinator, peerName)
    }

    /**
     * Single typed command entry point for production/user actions.
     */
    fun dispatch(command: TransferRuntimeCommand): Boolean {
        return when (command) {
            is TransferRuntimeCommand.StartSender -> startSender(command)
            is TransferRuntimeCommand.StartReceiver -> startReceiver(command)
            is TransferRuntimeCommand.StartNearbySenderPairing ->
                startNearbySenderPairing(command)
            is TransferRuntimeCommand.StartLanSenderPairing ->
                startLanSenderPairing(command)
            is TransferRuntimeCommand.StartTrustedSender ->
                startTrustedSender(command)
            is TransferRuntimeCommand.StartLanReceiverPairing ->
                startLanReceiverPairing(command)
            is TransferRuntimeCommand.StartLanReceiver ->
                startLanReceiver(command)
            TransferRuntimeCommand.StopLanReceiver ->
                stopLanReceiver()
            TransferRuntimeCommand.RevokeLanReceiverInvitation -> {
                lanController?.revokeReceiverInvitation(closeListener = false)
                true
            }
            is TransferRuntimeCommand.BindPairingTransport -> bindPairingTransport(
                command.transport,
                command.onPairingSuccess
            )
            is TransferRuntimeCommand.ConfirmPairing -> confirmPairing(command.peerId)
            is TransferRuntimeCommand.RejectPairing -> rejectPairing(command.peerId)
            is TransferRuntimeCommand.AcceptOffer -> acceptOffer(command.sessionId)
            is TransferRuntimeCommand.RejectOffer -> rejectOffer(command.sessionId, command.reason)
            is TransferRuntimeCommand.Pause -> pause(command.sessionId, command.reason)
            is TransferRuntimeCommand.Cancel -> cancel(command.sessionId, command.reason)
            is TransferRuntimeCommand.Resume -> resume(command.sessionId)
            is TransferRuntimeCommand.ApprovePeerEnrollment ->
                approvePeerEnrollment(command.fingerprintHex)
            is TransferRuntimeCommand.RejectPeerEnrollment ->
                rejectPeerEnrollment(command.fingerprintHex)
            is TransferRuntimeCommand.RevokeTrustedPeer ->
                revokeTrustedPeer(command.fingerprintHex)
            TransferRuntimeCommand.RotateLocalIdentity ->
                rotateLocalIdentity()
            TransferRuntimeCommand.StartDiscovery -> startDiscovery()
            TransferRuntimeCommand.StopDiscovery -> stopDiscovery()
            TransferRuntimeCommand.StartLanPeerDiscovery -> startLanPeerDiscovery()
            TransferRuntimeCommand.StopLanPeerDiscovery -> stopLanPeerDiscovery()
            is TransferRuntimeCommand.NearbyPermissionDenied ->
                nearbyPermissionDenied(command.receiveMode)
            is TransferRuntimeCommand.StartReceiveAvailability ->
                startReceiveAvailability(command.localDeviceName)
            TransferRuntimeCommand.StopReceiveAvailability -> stopReceiveAvailability()
        }
    }

    private fun startLanSenderPairing(
        command: TransferRuntimeCommand.StartLanSenderPairing
    ): Boolean {
        val controller = lanController ?: return failPairing(
            "LAN unavailable",
            "Production LAN transport is unavailable."
        )
        val scope = synchronized(lock) { serviceScope } ?: return failPairing(
            "Transfer service unavailable",
            "Foreground transfer service is not attached."
        )
        val prepared = SelectedTransferStore.get(command.selectionId) ?: return failPairing(
            "Selection unavailable",
            "The selected files are no longer available. Choose them again."
        )
        if (prepared.batch.items.isEmpty() || prepared.sourceMap.isEmpty()) {
            return failPairing(
                "Selection unavailable",
                "No selected files are available for transfer."
            )
        }

        discoveryController?.stopDiscovery()
        discoveryController?.stopAdvertising()
        clearPairingBinding()

        val peerId = PeerId("lan-${command.invitation.invitationId}")
        _pairingState.value = PairingUiState.Connecting(
            peerId = peerId,
            peerName = command.invitation.name,
            deviceType = DeviceType.fromDeviceName(command.invitation.name)
        )

        return controller.startSender(
            ownerScope = scope,
            invitation = command.invitation,
            localDeviceName = command.localDeviceName,
            onConnected = { transport, peer ->
                _pairingState.value = PairingUiState.Connected(peer)
                val started = startSender(
                    TransferRuntimeCommand.StartSender(
                        selectionId = command.selectionId,
                        transport = transport,
                        peerId = peer.id,
                        peerDisplayName = peer.displayName,
                        localDisplayName = command.localDeviceName
                    )
                )
                if (!started) {
                    failPairing(
                        "Transfer could not start",
                        "The selected files or transfer runtime became unavailable after LAN pairing."
                    )
                    terminalCallback?.invoke()
                }
            },
            onFailureTyped = { failure ->
                val actionable = dev.glide.app.ui.failure.ActionableErrorMapper.fromLanConnectionFailure(failure)
                failPairing(
                    title = actionable.title,
                    message = actionable.userWording,
                    primaryActionLabel = actionable.primaryActionLabel,
                    secondaryActionLabel = actionable.secondaryActionLabel,
                    troubleshootingHint = actionable.troubleshootingHint
                )
                terminalCallback?.invoke()
            }
        )
    }

    private fun startTrustedSender(command: TransferRuntimeCommand.StartTrustedSender): Boolean {
        if (_isSessionActive.value) {
            return failPairing("Device is busy", "Finish the current transfer, then try again.")
        }
        val controller = lanController ?: return failPairing(
            "LAN unavailable", "Local network transfer is unavailable."
        )
        val manager = persistentTrustManager ?: return failPairing(
            "Trust unavailable", "Saved device identity is unavailable."
        )
        val scope = synchronized(lock) { serviceScope } ?: return failPairing(
            "Transfer service unavailable", "The transfer service is unavailable."
        )
        val prepared = SelectedTransferStore.get(command.selectionId) ?: return failPairing(
            "Selection unavailable", "Choose files to send again."
        )
        if (prepared.batch.items.isEmpty() || prepared.sourceMap.isEmpty()) {
            return failPairing("Selection unavailable", "Choose files to send again.")
        }
        val trustedPeer = _trustState.value.trustedPeers.firstOrNull {
            it.peerFingerprintHex == command.peerFingerprintHex
        } ?: return failPairing("Device no longer paired", "This device is no longer saved. Pair it again.")

        discoveryController?.stopDiscovery()
        discoveryController?.stopAdvertising()
        clearPairingBinding()
        _pairingState.value = PairingUiState.Connecting(
            peerId = PeerId("saved-${trustedPeer.peerFingerprintHex.take(16)}"),
            peerName = trustedPeer.displayName,
            deviceType = DeviceType.fromDeviceName(trustedPeer.displayName)
        )
        return controller.startTrustedSender(
            ownerScope = scope,
            trustedPeer = trustedPeer,
            trustManager = manager,
            localDeviceName = command.localDeviceName,
            onConnected = { transport, peer ->
                _pairingState.value = PairingUiState.Connected(peer)
                if (!startSender(
                        TransferRuntimeCommand.StartSender(
                            selectionId = command.selectionId,
                            transport = transport,
                            peerId = peer.id,
                            peerDisplayName = trustedPeer.displayName,
                            expectedPeerFingerprintHex = trustedPeer.peerFingerprintHex,
                            localDisplayName = command.localDeviceName
                        )
                    )) {
                    failPairing("Transfer could not start", "Choose the files again and retry.")
                    terminalCallback?.invoke()
                }
            },
            onFailure = { failure ->
                val identityFailed = failure is dev.glide.transport.lan.LanConnectionFailure.AuthenticationFailed ||
                    failure.message.contains("Identity changed", ignoreCase = true)
                failPairing(
                    title = if (identityFailed) "Device identity changed" else "Could not connect",
                    message = if (identityFailed) "This saved device could not be verified. Pair it again."
                        else "Keep Glide open on the receiving device and check the local network, then try again.",
                    primaryActionLabel = "Try again"
                )
                terminalCallback?.invoke()
            }
        )
    }

    private fun startLanReceiver(
        command: TransferRuntimeCommand.StartLanReceiver
    ): Boolean {
        val controller = lanController ?: return false
        controller.configureTrustedReceive(persistentTrustManager)
        val scope = synchronized(lock) { serviceScope } ?: return false

        discoveryController?.stopAdvertising()
        clearPairingBinding()
        _pairingState.value = PairingUiState.Idle

        return controller.startReceiver(
            ownerScope = scope,
            deviceName = command.localDeviceName
        ) { transport, peer ->
            _pairingState.value = PairingUiState.Connected(peer)
            if (transport.trustedResumeOpening(peer.id) != null) {
                scope.launch {
                    if (!openResumeCoordinator(transport, peer.id, peer.displayName, localInitiated = false, localDisplayName = command.localDeviceName)) {
                        transport.disconnect(peer.id)
                        failPairing("Resume failed", "The approved transfer is unavailable.")
                    }
                }
                return@startReceiver
            }
            val started = startReceiver(
                TransferRuntimeCommand.StartReceiver(
                    transport = transport,
                    peerId = peer.id,
                    peerDisplayName = peer.displayName,
                    expectedPeerFingerprintHex = transport.trustedInboundPeerFingerprint(peer.id),
                    localDisplayName = command.localDeviceName
                )
            )
            if (!started) {
                failPairing(
                    "Transfer could not start",
                    "Receiver session could not start after authenticated LAN bootstrap."
                )
                terminalCallback?.invoke()
            }
        }
    }

    private fun startLanReceiverPairing(
        command: TransferRuntimeCommand.StartLanReceiverPairing
    ): Boolean {
        val controller = lanController ?: return failPairing(
            "LAN unavailable",
            "Production LAN transport is unavailable."
        )
        val scope = synchronized(lock) { serviceScope } ?: return failPairing(
            "Transfer service unavailable",
            "Foreground transfer service is not attached."
        )

        discoveryController?.stopDiscovery()
        discoveryController?.stopAdvertising()
        clearPairingBinding()

        val peerId = PeerId("lan-${command.invitation.invitationId}")
        _pairingState.value = PairingUiState.Connecting(
            peerId = peerId,
            peerName = command.invitation.name,
            deviceType = DeviceType.fromDeviceName(command.invitation.name)
        )

        return controller.startReceiverViaInvitation(
            ownerScope = scope,
            invitation = command.invitation,
            localDeviceName = command.localDeviceName,
            onConnected = { transport, peer ->
                _pairingState.value = PairingUiState.Connected(peer)
                val started = startReceiver(
                    TransferRuntimeCommand.StartReceiver(
                        transport = transport,
                        peerId = peer.id,
                        peerDisplayName = peer.displayName,
                        localDisplayName = command.localDeviceName
                    )
                )
                if (!started) {
                    failPairing(
                        "Transfer could not start",
                        "The receiver session could not start after LAN pairing."
                    )
                    terminalCallback?.invoke()
                }
            },
            onFailureTyped = { failure ->
                val actionable = dev.glide.app.ui.failure.ActionableErrorMapper.fromLanConnectionFailure(failure)
                failPairing(
                    title = actionable.title,
                    message = actionable.userWording,
                    primaryActionLabel = actionable.primaryActionLabel,
                    secondaryActionLabel = actionable.secondaryActionLabel,
                    troubleshootingHint = actionable.troubleshootingHint
                )
                terminalCallback?.invoke()
            }
        )
    }

    private fun stopLanReceiver(): Boolean {
        val controller = lanController ?: return false
        controller.stopAll()
        return true
    }

    private fun startNearbySenderPairing(
        command: TransferRuntimeCommand.StartNearbySenderPairing
    ): Boolean {
        lanController?.stopAll()
        val controller = discoveryController ?: return failPairing(
            "Nearby unavailable",
            "Production Nearby transport is unavailable."
        )
        val scope = synchronized(lock) { serviceScope } ?: return failPairing(
            "Transfer service unavailable",
            "Foreground transfer service is not attached."
        )
        val prepared = SelectedTransferStore.get(command.selectionId) ?: return failPairing(
            "Selection unavailable",
            "The selected files are no longer available. Choose them again."
        )
        if (prepared.batch.items.isEmpty() || prepared.sourceMap.isEmpty()) {
            return failPairing(
                "Selection unavailable",
                "No selected files are available for transfer."
            )
        }

        val transport = controller.acquireSessionTransport(scope) ?: return failPairing(
            "Nearby unavailable",
            "Nearby permissions or required radios are unavailable."
        )

        controller.stopDiscovery()
        controller.stopAdvertising()
        _pairingState.value = PairingUiState.Connecting(
            peerId = command.peerId,
            peerName = command.peerDisplayName,
            deviceType = DeviceType.fromDeviceName(command.peerDisplayName)
        )

        val bound = bindPairingTransport(transport) { connectedPeerId ->
            val connectedPeer = (_pairingState.value as? PairingUiState.Connected)?.peer
            val started = startSender(
                TransferRuntimeCommand.StartSender(
                    selectionId = command.selectionId,
                    transport = transport,
                    peerId = connectedPeerId,
                    peerDisplayName = connectedPeer?.displayName ?: command.peerDisplayName,
                    localDisplayName = command.localDeviceName
                )
            )
            if (!started) {
                failPairing(
                    "Transfer could not start",
                    "The selected files or transfer runtime became unavailable after pairing."
                )
                terminalCallback?.invoke()
            }
        }
        if (!bound) {
            return failPairing(
                "Pairing unavailable",
                "Unable to bind the Nearby authentication lifecycle."
            )
        }

        scope.launch {
            try {
                transport.initiateConnection(command.peerId, command.localDeviceName)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                failPairing(
                    "Connection failed",
                    error.message ?: "Unable to request a Nearby connection."
                )
                terminalCallback?.invoke()
            }
        }
        return true
    }

    private fun clearPairingBinding() {
        synchronized(lock) {
            pairingJob?.cancel()
            pairingJob = null
            pairingTransport = null
            pairingSuccessCallback = null
        }
    }

    private fun failPairing(
        title: String,
        message: String,
        primaryActionLabel: String? = null,
        secondaryActionLabel: String? = null,
        troubleshootingHint: String? = null,
        onPrimaryAction: (() -> Unit)? = null,
        onSecondaryAction: (() -> Unit)? = null
    ): Boolean {
        _pairingState.value = PairingUiState.Failed(
            title = dev.glide.app.ui.failure.ActionableErrorMapper.sanitizeText(title),
            message = dev.glide.app.ui.failure.ActionableErrorMapper.sanitizeText(message),
            primaryActionLabel = primaryActionLabel,
            secondaryActionLabel = secondaryActionLabel,
            troubleshootingHint = troubleshootingHint?.let {
                dev.glide.app.ui.failure.ActionableErrorMapper.sanitizeText(it)
            },
            onPrimaryAction = onPrimaryAction,
            onSecondaryAction = onSecondaryAction
        )
        return false
    }

    private fun startSender(command: TransferRuntimeCommand.StartSender): Boolean {
        val factory = sessionFactory ?: return false
        val scope = synchronized(lock) { serviceScope } ?: return false
        val prepared = SelectedTransferStore.get(command.selectionId) ?: return false
        val authenticatedSessionId =
            (command.transport as? AuthenticatedChannelContextProvider)
                ?.authenticatedChannelContext(command.peerId)
                ?.sessionId
        val sessionTrustManager =
            persistentTrustManager
                ?.takeIf {
                    _trustState.value
                        .isInitialized
                }
        if (command.expectedPeerFingerprintHex != null &&
            sessionTrustManager == null
        ) {
            return false
        }

        val coordinator = factory.createSenderSession(
            sessionId =
                authenticatedSessionId
                    ?: SessionId(UUID.randomUUID().toString()),
            peerId = command.peerId,
            peerDisplayName = command.peerDisplayName,
            localDisplayName = command.localDisplayName,
            senderBatch = prepared.batch,
            sourceMap = prepared.sourceMap,
            transport = command.transport,
            scope = scope,
            persistentTrustManager =
                sessionTrustManager,
            expectedPeerFingerprintHex =
                command.expectedPeerFingerprintHex,
            onVerifiedPeerIdentity = { peer ->
                stageVerifiedPeerForEnrollment(
                    peer
                )
            }
        )
        return startCoordinator(coordinator, command.peerDisplayName)
    }

    private fun startReceiver(command: TransferRuntimeCommand.StartReceiver): Boolean {
        val factory = sessionFactory ?: return false
        val scope = synchronized(lock) { serviceScope } ?: return false
        val authenticatedSessionId =
            (command.transport as? AuthenticatedChannelContextProvider)
                ?.authenticatedChannelContext(command.peerId)
                ?.sessionId
        val sessionTrustManager =
            persistentTrustManager
                ?.takeIf {
                    _trustState.value
                        .isInitialized
                }
        if (command.expectedPeerFingerprintHex != null &&
            sessionTrustManager == null
        ) {
            return false
        }

        val coordinator = factory.createReceiverSession(
            peerId = command.peerId,
            peerDisplayName = command.peerDisplayName,
            localDisplayName = command.localDisplayName,
            sessionId =
                command.sessionId
                    ?: authenticatedSessionId,
            transport = command.transport,
            scope = scope,
            persistentTrustManager =
                sessionTrustManager,
            expectedPeerFingerprintHex =
                command.expectedPeerFingerprintHex,
            onVerifiedPeerIdentity = { peer ->
                stageVerifiedPeerForEnrollment(
                    peer
                )
            }
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
            if (onTransferStarting?.invoke() == false) return false

            activeCoordinatorJob?.cancel()
            activeCoordinator?.let {
                captureTerminalSnapshot(it)
                it.stop()
            }

            activeCoordinator = coordinator
            activePeerDisplayName = peerDisplayName
            terminalCallbackSessionId = null
            autoSavedSessionId = null
            lastTerminalState = null
            lastSenderBatch = null
            lastSourceMap = emptyMap()
            _autoSaveStatus.value = ExportUiStatus.Idle
            _autoSavedItemIds.value = emptySet()
            _activeSessionId.value = coordinator.sessionId.value
            _isSessionActive.value = true
            statePresenter.reset()

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
            diagnosticsObserver?.onSessionStarted(
                scope = scope,
                sessionId = coordinator.sessionId.value,
                role = coordinator.role
            )
            activeCoordinatorJob = scope.launch {
                coordinator.state.collect { sessionState ->
                    if (activeCoordinator !== coordinator) return@collect
                    diagnosticsObserver?.onSessionState(sessionState)

                    val committedKeys = coordinator.committedItemFiles
                        .mapKeys { it.key.value }
                        .mapValues { it.value.value }
                    val nextUiState = statePresenter.present(
                        sessionState = sessionState,
                        peerDisplayName = peerDisplayName,
                        committedFileKey = coordinator.lastCommittedFileHandle?.value,
                        committedItemKeys = committedKeys,
                        current = _transferState.value
                    )
                    val resolvedUiState = if (nextUiState is TransferUiState.Transferring &&
                        coordinator.originalResumableSessionId != null
                    ) {
                        nextUiState.copy(isResumable = true)
                    } else {
                        nextUiState
                    }
                    if (resolvedUiState != null && sessionState.phase != SessionPhase.Interrupted) {
                        _transferState.value = resolvedUiState
                    }

                    // Keep the service and its single-operation gate active while verified
                    // received files are being published to the selected public folder.
                    val pendingPublicSave = resolvedUiState is TransferUiState.Completed &&
                        resolvedUiState.role == dev.glide.core.domain.DeviceRole.Receiver &&
                        applicationContext != null
                    val active = !sessionState.phase.isTerminal || pendingPublicSave
                    _isSessionActive.value = active
                    _activeSessionId.value = coordinator.sessionId.value.takeIf { active }

                    if (sessionState.phase.isTerminal) {
                        coordinator.awaitTerminalCleanup()
                        val stillOwned = synchronized(lock) {
                            activeCoordinator === coordinator
                        }
                        if (!stillOwned) return@collect
                        if (sessionState.phase == SessionPhase.Interrupted) {
                            val pausedUi = buildPausedStateIfRetained(coordinator, sessionState, peerDisplayName)
                            if (pausedUi != null) {
                                _transferState.value = pausedUi
                            } else if (resolvedUiState != null) {
                                _transferState.value = resolvedUiState
                            }
                        }
                        val shouldSave = synchronized(lock) {
                            if (autoSavedSessionId == coordinator.sessionId.value) false
                            else {
                                autoSavedSessionId = coordinator.sessionId.value
                                true
                            }
                        }
                        if (shouldSave && resolvedUiState is TransferUiState.Completed &&
                            resolvedUiState.role == dev.glide.core.domain.DeviceRole.Receiver
                        ) {
                            try {
                                autoSaveVerifiedFiles(resolvedUiState)
                            } catch (e: Exception) {
                                _autoSaveStatus.value = ExportUiStatus.Failed(
                                    "Could not save the verified files: ${e.message ?: "Unknown error"}"
                                )
                            }
                        }
                        finalizeTerminalCoordinator(coordinator)
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
                            if (activeCoordinator == null) {
                                terminalCallback?.invoke()
                            }
                        }
                        is ConnectionEvent.Failed -> {
                            _pairingState.value = PairingUiState.Failed(
                                title = "Connection Failed",
                                message = event.reason
                            )
                            if (activeCoordinator == null) {
                                terminalCallback?.invoke()
                            }
                        }
                        is ConnectionEvent.Disconnected -> {
                            if (_pairingState.value is PairingUiState.Verifying ||
                                _pairingState.value is PairingUiState.Connecting
                            ) {
                                _pairingState.value = PairingUiState.Failed(
                                    title = "Disconnected",
                                    message = "Remote peer disconnected."
                                )
                                if (activeCoordinator == null) {
                                    terminalCallback?.invoke()
                                }
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    private suspend fun autoSaveVerifiedFiles(state: TransferUiState.Completed) {
        val context = applicationContext ?: return
        diagnosticsObserver?.onSavingStarted()
        _autoSaveStatus.value = ExportUiStatus.Exporting
        val exporter = AndroidFileExporter(context)
        val result = publishVerifiedReceive(
            state = state,
            resolve = { FileOpener.resolveReceivedFile(context, it) },
            save = { file, item ->
                ReceivedFileSaver.save(context, exporter, file, item.displayName,
                    item.mimeType, item.relativePath)
            },
            onSaved = { _autoSavedItemIds.value = _autoSavedItemIds.value + it }
        )
        _autoSaveStatus.value = result
        diagnosticsObserver?.onSavingFinished(result)
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

    private fun pause(sessionId: String, reason: String): Boolean {
        val coordinator: TransferCoordinator
        val ownerScope: CoroutineScope?
        synchronized(lock) {
            if (_activeSessionId.value != sessionId) return false
            coordinator = activeCoordinator ?: return false
            if (coordinator.state.value.phase.isTerminal) return false
            if (coordinator.originalResumableSessionId == null) return false
            ownerScope = serviceScope
            pausedSessionIds.add(coordinator.sessionId.value)
        }
        if (ownerScope == null) {
            coordinator.scope.launch {
                coordinator.interruptAndAwaitCleanup(reason)
            }
            return true
        }
        coordinator.scope.launch {
            coordinator.interruptAndAwaitCleanup(reason)
            finalizeTerminalCoordinator(coordinator)
        }
        return true
    }

    private suspend fun buildPausedStateIfRetained(
        coordinator: TransferCoordinator,
        sessionState: SessionState,
        peerDisplayName: String
    ): TransferUiState.Paused? {
        val origId = coordinator.originalResumableSessionId ?: return null
        val userPaused = pausedSessionIds.contains(coordinator.sessionId.value)
        val hasRetained = userPaused ||
            sessionFactory?.listRetainedBatches()?.any { it.manifest.sessionId.value == origId } == true
        if (!hasRetained) return null
        return buildPausedUiState(sessionState, peerDisplayName, origId, userPaused)
    }

    private fun buildPausedUiState(
        sessionState: SessionState,
        peerDisplayName: String,
        origId: String,
        userPaused: Boolean
    ): TransferUiState.Paused {
        val batch = sessionState.batch
        val items = batch?.items ?: emptyList()
        val currentItem = sessionState.currentItemState?.item ?: items.firstOrNull()
        val itemCount = items.size.coerceAtLeast(1)
        val itemIndex = if (currentItem != null && items.isNotEmpty()) {
            (items.indexOfFirst { it.id == currentItem.id } + 1).coerceAtLeast(1)
        } else 1
        val totalBytes = batch?.totalBytes ?: (currentItem?.sizeBytes ?: 0L)
        val transferredBytes = sessionState.itemStates.values.sumOf { it.transferredBytes }.coerceIn(0L, totalBytes.coerceAtLeast(0L))
        val fraction = if (totalBytes > 0L) (transferredBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
        val verifiedCount = sessionState.itemStates.values.count { it.phase == ItemPhase.Verified }
        val statusMsg = if (itemCount > 1 && verifiedCount > 0) {
            if (userPaused) "Paused · $verifiedCount of $itemCount files verified"
            else "Paused — connection interrupted · $verifiedCount of $itemCount files verified"
        } else if (userPaused) {
            "Transfer paused"
        } else {
            "Transfer paused — connection interrupted"
        }
        return TransferUiState.Paused(
            sessionId = sessionState.sessionId.value,
            role = sessionState.role,
            peerName = peerDisplayName,
            itemName = currentItem?.name ?: "Transfer batch",
            transferredBytes = transferredBytes,
            totalBytes = totalBytes,
            progressFraction = fraction,
            itemIndex = itemIndex,
            itemCount = itemCount,
            resumableSessionId = origId,
            statusMessage = statusMsg,
            peerDeviceType = DeviceType.fromDeviceName(peerDisplayName)
        )
    }

    private fun cancel(sessionId: String, reason: String): Boolean {
        val coordinator: TransferCoordinator?
        val ownerScope: CoroutineScope?
        var pausedToPurge: TransferUiState.Paused? = null
        synchronized(lock) {
            val current = _transferState.value
            if (activeCoordinator == null && _activeSessionId.value == null &&
                current is TransferUiState.Paused &&
                (current.sessionId == sessionId || current.resumableSessionId == sessionId)
            ) {
                pausedToPurge = current
                ownerScope = serviceScope
                _transferState.value = TransferUiState.Cancelled(
                    sessionId = current.sessionId,
                    role = current.role,
                    reason = reason,
                    peerDeviceType = current.peerDeviceType
                )
                diagnosticsObserver?.onCancellationRequested(sessionId)
                diagnosticsObserver?.onCancellationUiUpdated(sessionId)
                _isSessionActive.value = false
                _activeSessionId.value = null
                terminalCallback?.invoke()
                coordinator = null
            } else {
                if (_activeSessionId.value != sessionId) return false
                coordinator = activeCoordinator
                ownerScope = serviceScope
                pausedSessionIds.remove(sessionId)
                diagnosticsObserver?.onCancellationRequested(sessionId)
                if (coordinator == null) {
                    _transferState.value = TransferUiState.Cancelled(
                        sessionId = current.sessionId,
                        role = current.role,
                        reason = reason
                    )
                    diagnosticsObserver?.onCancellationUiUpdated(sessionId)
                    _isSessionActive.value = false
                    _activeSessionId.value = null
                    terminalCallback?.invoke()
                }
            }
        }

        pausedToPurge?.let { paused ->
            val store = resumableStagingStore
            if (store != null) {
                val purgeScope = ownerScope ?: kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
                purgeScope.launch {
                    store.purgeOriginalSession(paused.resumableSessionId)
                    bumpRetainedTransfersVersion()
                }
            }
            return true
        }

        // Explicit user cancellation invalidates retained resume authority for this transfer.
        // Service/process interruption deliberately does not call this path.
        val originalResumableId = coordinator?.originalResumableSessionId
        if (ownerScope != null &&
            resumableStagingStore != null
        ) {
            ownerScope.launch {
                resumableStagingStore
                    .purgeOriginalSession(
                        sessionId
                    )
                if (originalResumableId != null && originalResumableId != sessionId) {
                    resumableStagingStore.purgeOriginalSession(originalResumableId)
                }
                bumpRetainedTransfersVersion()
            }
        }

        if (ownerScope == null) {
            coordinator?.cancel(reason)
            return true
        }

        val active = coordinator ?: return true
        active.scope.launch {
            active.cancelAndAwaitCleanup(reason)
            finalizeTerminalCoordinator(active)
        }
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

    /**
     * Initializes/refreshes persistent trust without requiring a transfer service scope.
     * Settings uses this in its own lifecycle; no network or coordinator work is started.
     */
    suspend fun refreshPersistentTrustState(): Boolean {
        val manager = persistentTrustManager ?: run {
            _trustState.value = TrustRuntimeState(
                errorMessage = "Persistent trust service is unavailable"
            )
            return false
        }

        return try {
            val initialization = manager.initialize()
            val notice = when (initialization) {
                is LocalIdentityInitialization.Ready -> null
                is LocalIdentityInitialization.ResetAfterKeyLoss ->
                    "Local identity key was unavailable. " +
                        "${initialization.revokedPeerCount} trusted peer record(s) were revoked and re-enrollment is required."
            }
            val peers = manager.trustedPeerSnapshot()
            _trustState.value = _trustState.value.copy(
                isInitialized = true,
                localFingerprintHex = initialization.localFingerprintHex,
                trustedPeers = peers,
                securityNotice = notice,
                errorMessage = null
            )
            true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _trustState.value = _trustState.value.copy(
                errorMessage =
                    "Persistent identity initialization failed: " +
                        (t.message ?: t::class.java.simpleName)
            )
            false
        }
    }

    suspend fun revokeTrustedPeerNow(
        fingerprintHex: String
    ): Boolean {
        val manager = persistentTrustManager ?: return false
        return try {
            manager.revokePeer(fingerprintHex)
            resumableStagingStore
                ?.purgeForPeer(
                    fingerprintHex
                )
            _trustState.value = _trustState.value.copy(
                trustedPeers = manager.trustedPeerSnapshot(),
                securityNotice = "Device trust revoked.",
                errorMessage = null
            )
            true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _trustState.value = _trustState.value.copy(
                errorMessage =
                    "Could not revoke trusted device: " +
                        (t.message ?: t::class.java.simpleName)
            )
            false
        }
    }

    suspend fun rotateLocalIdentityNow(): Boolean {
        val manager = persistentTrustManager ?: return false
        synchronized(lock) {
            pendingVerifiedPeer = null
            _trustState.value = _trustState.value.copy(
                pendingEnrollment = null
            )
        }

        return try {
            val result = manager.rotateLocalIdentity()
            resumableStagingStore
                ?.purgeAll()
            _trustState.value = _trustState.value.copy(
                isInitialized = true,
                localFingerprintHex = result.localFingerprintHex,
                trustedPeers = emptyList(),
                securityNotice =
                    "Local identity rotated. " +
                        "${result.revokedPeerCount} trusted peer record(s) were revoked.",
                errorMessage = null
            )
            true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _trustState.value = _trustState.value.copy(
                errorMessage =
                    "Could not rotate local identity: " +
                        (t.message ?: t::class.java.simpleName)
            )
            false
        }
    }

    private fun startTrustObservation(scope: CoroutineScope) {
        val manager = persistentTrustManager ?: run {
            _trustState.value = TrustRuntimeState(
                errorMessage = "Persistent trust service is unavailable"
            )
            return
        }

        synchronized(lock) {
            trustObservationJob?.cancel()
            trustObservationJob = scope.launch {
                if (!refreshPersistentTrustState()) return@launch
                try {
                    manager.trustedPeers.collect { peers ->
                        _trustState.value = _trustState.value.copy(
                            isInitialized = true,
                            trustedPeers = peers,
                            errorMessage = null
                        )
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    _trustState.value = _trustState.value.copy(
                        errorMessage =
                            "Persistent trust observation failed: " +
                                (t.message ?: t::class.java.simpleName)
                    )
                }
            }
        }
    }
    /**
     * HARD-021 calls this only after IdentityProofVerifier has validated the live channel-bound
     * proof. UI code may approve or reject the pending identity but cannot create it.
     */
    internal fun stageVerifiedPeerForEnrollment(
        peer: VerifiedPeerIdentity
    ): Boolean {
        if (persistentTrustManager == null) return false

        synchronized(lock) {
            pendingVerifiedPeer = peer
            _trustState.value = _trustState.value.copy(
                pendingEnrollment = PendingTrustEnrollment(
                    fingerprintHex = peer.fingerprintHex,
                    displayName = peer.displayName
                ),
                securityNotice = null,
                errorMessage = null
            )
        }
        return true
    }

    private fun approvePeerEnrollment(
        fingerprintHex: String
    ): Boolean {
        val manager = persistentTrustManager ?: return false
        val scope = synchronized(lock) { serviceScope } ?: return false
        val peer = synchronized(lock) {
            val pending = pendingVerifiedPeer ?: return false
            if (pending.fingerprintHex != fingerprintHex) return false

            pendingVerifiedPeer = null
            _trustState.value = _trustState.value.copy(
                pendingEnrollment = null
            )
            pending
        }

        scope.launch {
            try {
                manager.enrollVerifiedPeer(
                    verifiedPeer = peer,
                    userApproved = true
                )
                _trustState.value = _trustState.value.copy(
                    securityNotice = "Trusted device enrolled: ${peer.displayName}",
                    errorMessage = null
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _trustState.value = _trustState.value.copy(
                    errorMessage =
                        "Could not enroll trusted device: " +
                            (t.message ?: t::class.java.simpleName)
                )
            }
        }
        return true
    }

    private fun rejectPeerEnrollment(
        fingerprintHex: String
    ): Boolean = synchronized(lock) {
        val pending = pendingVerifiedPeer ?: return false
        if (pending.fingerprintHex != fingerprintHex) return false

        pendingVerifiedPeer = null
        _trustState.value = _trustState.value.copy(
            pendingEnrollment = null,
            securityNotice = "Device trust enrollment declined."
        )
        true
    }

    private fun revokeTrustedPeer(
        fingerprintHex: String
    ): Boolean {
        if (persistentTrustManager == null) return false
        val scope = synchronized(lock) { serviceScope } ?: return false
        scope.launch {
            revokeTrustedPeerNow(fingerprintHex)
        }
        return true
    }

    private fun rotateLocalIdentity(): Boolean {
        if (persistentTrustManager == null) return false
        val scope = synchronized(lock) { serviceScope } ?: return false
        scope.launch {
            rotateLocalIdentityNow()
        }
        return true
    }
    private fun startDiscovery(): Boolean {
        val controller = discoveryController ?: run {
            _fallbackDiscoveryState.value = DiscoveryUiState.Blocked(
                BlockedReason.TransportError("Production discovery dependency is unavailable")
            )
            return false
        }
        return controller.startDiscovery()
    }

    private fun stopDiscovery(): Boolean {
        val controller = discoveryController ?: return false
        controller.stopDiscovery()
        return true
    }

    private fun startLanPeerDiscovery(): Boolean {
        val controller = lanController ?: return false
        synchronized(lock) {
            if (lanPeerDiscoveryJob?.isActive == true) return true
            lanPeerDiscoveryJob = lanPeerDiscoveryScope.launch {
                if (!_trustState.value.isInitialized) {
                    refreshPersistentTrustState()
                }
                while (true) {
                    val trusted = _trustState.value.trustedPeers
                    if (trusted.isNotEmpty()) {
                        val candidates = runCatching {
                            controller.probeTrustedReconnectCandidates(timeoutMs = 1_100L)
                        }.getOrDefault(emptyList())
                        val matched = trusted.filter { peer ->
                            candidates.any { it.displayName.equals(peer.displayName, ignoreCase = true) } ||
                                (candidates.size == 1 && trusted.size == 1)
                        }.map { peer ->
                            DiscoveredPeer(
                                id = lanTrustedPeerId(peer.peerFingerprintHex),
                                displayName = peer.displayName,
                                deviceType = DeviceType.fromDeviceName(peer.displayName),
                                routeSubtitle = "Hotspot / Local Wi-Fi"
                            )
                        }
                        _onlineLanPeers.value = matched
                    } else {
                        _onlineLanPeers.value = emptyList()
                    }
                    delay(1_800L)
                }
            }
        }
        return true
    }

    private fun stopLanPeerDiscovery(): Boolean {
        synchronized(lock) {
            lanPeerDiscoveryJob?.cancel()
            lanPeerDiscoveryJob = null
        }
        _onlineLanPeers.value = emptyList()
        return true
    }

    fun resolveTrustedLanPeerFingerprint(peerId: PeerId): String? {
        val raw = peerId.value
        if (!raw.startsWith(LAN_TRUSTED_PEER_ID_PREFIX)) return null
        val prefix = raw.removePrefix(LAN_TRUSTED_PEER_ID_PREFIX)
        if (prefix.isEmpty()) return null
        return _trustState.value.trustedPeers
            .firstOrNull { it.peerFingerprintHex.startsWith(prefix, ignoreCase = true) }
            ?.peerFingerprintHex
    }

    private fun nearbyPermissionDenied(receiveMode: Boolean): Boolean {
        val controller = discoveryController ?: run {
            if (receiveMode) {
                _fallbackReceiveReadinessState.value = ReceiveReadinessUiState.Blocked(
                    BlockedReason.PermissionsRequired
                )
            } else {
                _fallbackDiscoveryState.value = DiscoveryUiState.Blocked(
                    BlockedReason.PermissionsRequired
                )
            }
            return false
        }
        controller.onPermissionDenied(receiveMode)
        return true
    }

    private fun startReceiveAvailability(localDeviceName: String): Boolean {
        // Nearby and trusted LAN availability have independent transports. Opening
        // Receive must not withdraw the phone's authenticated LAN endpoint.
        val controller = discoveryController ?: run {
            _fallbackReceiveReadinessState.value = ReceiveReadinessUiState.Blocked(
                BlockedReason.TransportError("Production advertising dependency is unavailable")
            )
            return false
        }

        val scope = synchronized(lock) { serviceScope }
        if (scope == null) {
            // Test/preview compatibility. Production Navigation3 starts receive availability through
            // TransferService so authenticated connection work is service-owned.
            return controller.startAdvertising(localDeviceName)
        }

        val transport = controller.acquireSessionTransport(scope) ?: return false
        _pairingState.value = PairingUiState.Idle

        val bound = bindPairingTransport(transport) { connectedPeerId ->
            controller.stopAdvertising()
            val connectedPeer = (_pairingState.value as? PairingUiState.Connected)?.peer
            val started = startReceiver(
                TransferRuntimeCommand.StartReceiver(
                    transport = transport,
                    peerId = connectedPeerId,
                    peerDisplayName = connectedPeer?.displayName ?: "Nearby device",
                    localDisplayName = localDeviceName
                )
            )
            if (!started) {
                failPairing(
                    "Transfer could not start",
                    "Receiver session could not start after authenticated pairing."
                )
                terminalCallback?.invoke()
            }
        }
        if (!bound) return false

        return controller.startAdvertising(
            localDeviceName = localDeviceName,
            transportOverride = transport
        )
    }

    private fun stopReceiveAvailability(): Boolean {
        val controller = discoveryController ?: return false
        controller.stopAdvertising()
        return true
    }

    fun stopActiveSession(reason: String = "USER_STOPPED"): Boolean {
        val coordinator: TransferCoordinator
        val serviceAttached: Boolean
        synchronized(lock) {
            coordinator = activeCoordinator ?: return false
            serviceAttached = serviceScope != null
        }

        if (coordinator.state.value.phase.isTerminal) {
            synchronized(lock) {
                if (activeCoordinator === coordinator) {
                    captureTerminalSnapshot(coordinator)
                    activeCoordinatorJob?.cancel()
                    activeCoordinatorJob = null
                    activeCoordinator = null
                    _isSessionActive.value = false
                    _activeSessionId.value = null
                    statePresenter.reset()
                }
            }
            coordinator.stop()
            return false
        }

        if (!serviceAttached) {
            coordinator.cancel(reason)
            return true
        }

        coordinator.scope.launch {
            coordinator.cancelAndAwaitCleanup(reason)
            finalizeTerminalCoordinator(coordinator)
        }
        return true
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

    internal fun setAutoSaveStatusForTesting(status: ExportUiStatus) {
        _autoSaveStatus.value = status
    }

    internal fun setPairingStateForTesting(state: PairingUiState) {
        _pairingState.value = state
    }

    internal fun setDiscoveryStateForTesting(state: DiscoveryUiState) {
        _fallbackDiscoveryState.value = state
    }

    internal fun setReceiveReadinessStateForTesting(state: ReceiveReadinessUiState) {
        _fallbackReceiveReadinessState.value = state
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
        trustObservationJob?.cancel()
        trustObservationJob = null
        pendingVerifiedPeer = null
        _trustState.value = TrustRuntimeState()
        terminalCallback = null
        terminalCallbackSessionId = null
        serviceScope = null
        activePeerDisplayName = "Nearby device"
        lastTerminalState = null
        lastSenderBatch = null
        lastSourceMap = emptyMap()
        pausedSessionIds.clear()
        _transferState.value = TransferUiState.Idle
        _autoSaveStatus.value = ExportUiStatus.Idle
        _autoSavedItemIds.value = emptySet()
        _pairingState.value = PairingUiState.Idle
        discoveryController?.resetForTesting()
        stopLanPeerDiscovery()
        lanController?.stopAll()
        _fallbackLanReceiverState.value = LanReceiverRuntimeState.Idle
        _fallbackDiscoveryState.value = DiscoveryUiState.Blocked(
            BlockedReason.TransportError("Production discovery is not attached")
        )
        _fallbackReceiveReadinessState.value = ReceiveReadinessUiState.Blocked(
            BlockedReason.TransportError("Production advertising is not attached")
        )
        _isSessionActive.value = false
        _activeSessionId.value = null
        statePresenter.reset()
        timeProvider = { System.currentTimeMillis() }
    }

    private fun captureTerminalSnapshot(coordinator: TransferCoordinator) {
        lastTerminalState = coordinator.state.value
        lastSenderBatch = coordinator.senderBatch
        lastSourceMap = coordinator.sourceMap.toMap()
    }

    private fun finalizeTerminalCoordinator(coordinator: TransferCoordinator) {
        val peerDisplayName: String
        val callback: (() -> Unit)?
        val publishCompletion: (() -> Unit)?
        val terminalSessionState: SessionState
        val terminalScope: CoroutineScope?
        val exportStatusSnapshot: ExportUiStatus
        synchronized(lock) {
            if (activeCoordinator !== coordinator || !coordinator.state.value.phase.isTerminal) {
                return
            }

            peerDisplayName = activePeerDisplayName
            terminalScope = serviceScope

            val sessionState = coordinator.state.value
            terminalSessionState = sessionState
            val committedKeys = coordinator.committedItemFiles
                .mapKeys { it.key.value }
                .mapValues { it.value.value }
            val userPaused = pausedSessionIds.remove(coordinator.sessionId.value)
            val origId = coordinator.originalResumableSessionId
            val currentUi = _transferState.value
            if (currentUi is TransferUiState.Paused && currentUi.sessionId == coordinator.sessionId.value) {
                // Keep Paused UI produced by observeCoordinator.
            } else if (sessionState.phase == SessionPhase.Interrupted && userPaused && origId != null) {
                _transferState.value = buildPausedUiState(sessionState, peerDisplayName, origId, userPaused = true)
            } else {
                _transferState.value = sessionState.toUiState(
                    peerDisplayName = peerDisplayName,
                    committedFileKey = coordinator.lastCommittedFileHandle?.value,
                    committedItemKeys = committedKeys
                )
            }
            if (sessionState.phase == dev.glide.core.coordinator.SessionPhase.Cancelled) {
                diagnosticsObserver?.onCancellationUiUpdated(coordinator.sessionId.value)
            }
            exportStatusSnapshot = _autoSaveStatus.value
            _isSessionActive.value = false
            _activeSessionId.value = null
            if (_pairingState.value is PairingUiState.Connected ||
                _pairingState.value is PairingUiState.Connecting ||
                _pairingState.value is PairingUiState.Verifying
            ) {
                _pairingState.value = PairingUiState.Idle
            }
            statePresenter.reset()
            captureTerminalSnapshot(coordinator)

            callback = if (terminalCallbackSessionId == coordinator.sessionId.value) {
                publishCompletion = null
                null
            } else {
                terminalCallbackSessionId = coordinator.sessionId.value
                val state = _transferState.value as? TransferUiState.Completed
                val publisher = onVerifiedCompletion
                publishCompletion = if (state != null && publisher != null) {
                    { publisher(state) }
                } else null
                terminalCallback
            }
        }

        coordinator.stop()
        bumpRetainedTransfersVersion()
        diagnosticsObserver?.onTerminalState(
            scope = terminalScope ?: coordinator.scope,
            sessionState = terminalSessionState,
            exportStatus = exportStatusSnapshot
        )
        publishCompletion?.invoke()
        callback?.invoke()
    }

    companion object {
        internal const val LAN_TRUSTED_PEER_ID_PREFIX = "lan-t-"

        internal fun lanTrustedPeerId(fingerprintHex: String): PeerId =
            PeerId("$LAN_TRUSTED_PEER_ID_PREFIX${fingerprintHex.take(58)}")

        internal fun forTesting(
            sessionFactory: TransferSessionFactory? = null,
            discoveryController: ProductionDiscoveryController? = null,
            lanController: ProductionLanController? = null,
            persistentTrustManager: PersistentTrustManager? = null,
            resumableStagingStore: ResumableStagingStore? = null
        ): TransferRuntime {
            return TransferRuntime(
                sessionFactory,
                discoveryController,
                lanController,
                persistentTrustManager,
                resumableStagingStore,
                null,
                true
            )
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
        val peerDisplayName: String,
        val expectedPeerFingerprintHex: String? = null,
        val localDisplayName: String = "Glide Device"
    ) : TransferRuntimeCommand

    data class StartReceiver(
        val transport: TransferTransport,
        val peerId: PeerId,
        val peerDisplayName: String,
        val sessionId: SessionId? = null,
        val expectedPeerFingerprintHex: String? = null,
        val localDisplayName: String = "Glide Device"
    ) : TransferRuntimeCommand

    data class StartNearbySenderPairing(
        val selectionId: BatchId? = null,
        val peerId: PeerId,
        val peerDisplayName: String,
        val localDeviceName: String = "Glide Device"
    ) : TransferRuntimeCommand

    data class StartLanSenderPairing(
        val selectionId: BatchId? = null,
        val invitation: LanInvitation,
        val localDeviceName: String = "Glide Device"
    ) : TransferRuntimeCommand

    data class StartTrustedSender(
        val selectionId: BatchId,
        val peerFingerprintHex: String,
        val localDeviceName: String = "Glide Device"
    ) : TransferRuntimeCommand

    data class StartLanReceiverPairing(
        val invitation: LanInvitation,
        val localDeviceName: String = "Glide Device"
    ) : TransferRuntimeCommand

    data class StartLanReceiver(
        val localDeviceName: String = "Glide Device"
    ) : TransferRuntimeCommand

    data object StopLanReceiver : TransferRuntimeCommand
    data object RevokeLanReceiverInvitation : TransferRuntimeCommand

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
    data class Pause(
        val sessionId: String,
        val reason: String = "USER_PAUSED"
    ) : TransferRuntimeCommand
    data class Cancel(
        val sessionId: String,
        val reason: String = "USER_CANCELLED"
    ) : TransferRuntimeCommand
    data class Resume(val sessionId: String) : TransferRuntimeCommand

    data class ApprovePeerEnrollment(
        val fingerprintHex: String
    ) : TransferRuntimeCommand

    data class RejectPeerEnrollment(
        val fingerprintHex: String
    ) : TransferRuntimeCommand

    data class RevokeTrustedPeer(
        val fingerprintHex: String
    ) : TransferRuntimeCommand

    data object RotateLocalIdentity : TransferRuntimeCommand

    data object StartDiscovery : TransferRuntimeCommand
    data object StopDiscovery : TransferRuntimeCommand
    data object StartLanPeerDiscovery : TransferRuntimeCommand
    data object StopLanPeerDiscovery : TransferRuntimeCommand
    data class NearbyPermissionDenied(val receiveMode: Boolean) : TransferRuntimeCommand
    data class StartReceiveAvailability(val localDeviceName: String) : TransferRuntimeCommand
    data object StopReceiveAvailability : TransferRuntimeCommand
}
