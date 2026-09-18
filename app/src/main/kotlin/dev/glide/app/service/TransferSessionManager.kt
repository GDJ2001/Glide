package dev.glide.app.service

import dev.glide.app.transfer.ActiveSessionTracker
import dev.glide.app.ui.transfer.TransferUiState
import dev.glide.app.ui.transfer.toUiState
import dev.glide.app.util.BatchTransferRateTracker
import dev.glide.core.coordinator.SessionPhase
import dev.glide.core.coordinator.TransferCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Thread-safe singleton managing the active [TransferCoordinator] lifecycle and sharing
 * active session state between [TransferService] and [dev.glide.app.ui.transfer.TransferViewModel] (GLIDE-028).
 *
 * Guarantees:
 * - Single active session: Background and rotation share one continuous session without restarts.
 * - Duplicate start idempotence: Repeated start requests for the same session ID do not spawn duplicate jobs.
 * - Terminal teardown: Notifies [TransferService] via [onTerminalCallback] to stop the foreground service
 *   promptly when the session reaches a terminal phase (Completed, Failed, Cancelled).
 */
object TransferSessionManager {

    private var activeCoordinator: TransferCoordinator? = null
    private var activeJob: Job? = null

    private var _uiState = MutableStateFlow<TransferUiState>(TransferUiState.Idle)
    val uiState: StateFlow<TransferUiState> get() = _uiState.asStateFlow()

    var timeProvider: () -> Long = { System.currentTimeMillis() }
    private var rateTracker = BatchTransferRateTracker(timeProvider = { timeProvider() })

    /**
     * Callback invoked when the active session reaches a terminal state, allowing
     * the hosting Foreground Service to call stopForeground() and stopSelf().
     */
    var onTerminalCallback: (() -> Unit)? = null

    /**
     * Starts or attaches an active [TransferCoordinator] under the provided [CoroutineScope].
     *
     * @param coordinator The transfer coordinator instance.
     * @param peerDisplayName Display name of the remote peer.
     * @param scope The service coroutine scope owning this session's execution lifetime.
     * @return True if the session was started or already actively running; false if blocked by another session.
     */
    @Synchronized
    fun startSession(
        coordinator: TransferCoordinator,
        peerDisplayName: String = "Nearby device",
        scope: CoroutineScope
    ): Boolean {
        // 1. Check duplicate start for same session ID (idempotent)
        if (activeCoordinator?.sessionId == coordinator.sessionId) {
            return true
        }

        // 2. Reject if a different active session is already running
        val current = _uiState.value
        val isCurrentlyActive = current is TransferUiState.Connecting ||
            current is TransferUiState.AwaitingConsent ||
            current is TransferUiState.Transferring ||
            current is TransferUiState.Verifying

        if (activeCoordinator != null && isCurrentlyActive) {
            return false
        }

        // 3. Clean up any previous completed/cancelled jobs
        activeJob?.cancel()
        activeCoordinator = coordinator

        // 4. Mark session active in global tracker
        ActiveSessionTracker.setSessionActive(coordinator.sessionId.value, true)

        // 5. Observe coordinator state in the service scope
        rateTracker.reset()
        var lastPhase: SessionPhase? = null

        activeJob = scope.launch {
            coordinator.state.collect { sessionState ->
                val committedKeys = coordinator.committedItemFiles.mapKeys { it.key.value }.mapValues { it.value.value }
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
                    if (snapshot.shouldEmitUiUpdate || _uiState.value !is TransferUiState.Transferring) {
                        _uiState.value = baseUiState.copy(
                            speedBytesPerSec = snapshot.speedBytesPerSec,
                            isStalled = snapshot.isStalled
                        )
                    }
                } else {
                    rateTracker.reset()
                    _uiState.value = baseUiState
                }

                val isActive = !sessionState.phase.isTerminal
                ActiveSessionTracker.setSessionActive(coordinator.sessionId.value, isActive)

                if (sessionState.phase.isTerminal) {
                    // The reducer publishes its terminal state before terminal effects (history,
                    // quarantine cleanup and transport disconnect) have necessarily finished.
                    // Do not cancel coordinator jobs until that cleanup barrier is complete.
                    coordinator.awaitTerminalCleanup()
                    coordinator.stop()
                    onTerminalCallback?.invoke()
                }
            }
        }

        // 6. Begin coordinator execution
        coordinator.start()
        return true
    }

    /**
     * Accepts offer consent on behalf of the receiver.
     * Rejects action if [sessionId] is provided and does not match the active session.
     */
    fun acceptConsent(sessionId: String? = null): Boolean {
        val activeId = getActiveSessionId()
        if (sessionId != null && !TransferServicePolicy.shouldAcceptSessionAction(sessionId, activeId)) {
            return false
        }
        activeCoordinator?.acceptOffer()
        return true
    }

    /**
     * Rejects offer consent on behalf of the receiver.
     * Rejects action if [sessionId] is provided and does not match the active session.
     */
    fun rejectConsent(sessionId: String? = null, reason: String = "USER_REJECTED"): Boolean {
        val activeId = getActiveSessionId()
        if (sessionId != null && !TransferServicePolicy.shouldAcceptSessionAction(sessionId, activeId)) {
            return false
        }
        activeCoordinator?.rejectOffer(reason)
        return true
    }

    /**
     * Cancels the active transfer and cleans up unverified artifacts.
     * Rejects action if [sessionId] is provided and does not match the active session.
     */
    fun cancelTransfer(reason: String = "USER_CANCELLED", sessionId: String? = null): Boolean {
        val activeId = getActiveSessionId()
        if (sessionId != null && !TransferServicePolicy.shouldAcceptSessionAction(sessionId, activeId)) {
            return false
        }
        val coordinator = activeCoordinator
        if (coordinator != null) {
            coordinator.cancel(reason)
        } else {
            val current = _uiState.value
            _uiState.value = TransferUiState.Cancelled(
                sessionId = current.sessionId,
                role = current.role,
                reason = reason
            )
            ActiveSessionTracker.reset()
            onTerminalCallback?.invoke()
        }
        return true
    }

    /**
     * Stops the active session, cancels jobs, and signals terminal teardown.
     */
    @Synchronized
    fun stopSession() {
        rateTracker.reset()
        activeCoordinator?.stop()
        activeCoordinator = null
        activeJob?.cancel()
        activeJob = null
        ActiveSessionTracker.reset()
        onTerminalCallback?.invoke()
    }

    /**
     * Returns the currently active coordinator, if any.
     */
     fun getActiveCoordinator(): TransferCoordinator? = activeCoordinator

    /**
     * Returns the session ID of the currently active transfer session, or null if no session is active.
     */
    fun getActiveSessionId(): String? = activeCoordinator?.sessionId?.value ?: when (val state = _uiState.value) {
        is TransferUiState.Connecting -> state.sessionId
        is TransferUiState.AwaitingConsent -> state.sessionId
        is TransferUiState.Transferring -> state.sessionId
        is TransferUiState.Verifying -> state.sessionId
        else -> null
    }

    /**
     * Sets UI state directly for testing or debug screenshot purposes.
     */
    internal fun setUiStateForTesting(state: TransferUiState) {
        _uiState.value = state
    }

    /**
     * Resets internal state for clean testing isolation.
     */
    internal fun resetForTesting() {
        rateTracker.reset()
        timeProvider = { System.currentTimeMillis() }
        activeJob?.cancel()
        activeJob = null
        activeCoordinator = null
        _uiState = MutableStateFlow(TransferUiState.Idle)
        onTerminalCallback = null
        ActiveSessionTracker.reset()
    }
}
