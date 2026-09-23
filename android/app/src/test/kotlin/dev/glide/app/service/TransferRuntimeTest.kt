package dev.glide.app.service

import dev.glide.app.ui.transfer.TransferUiState
import dev.glide.app.ui.transfer.TransferViewModel
import dev.glide.core.coordinator.TransferCoordinator
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
import dev.glide.core.ports.AdvertisingEvent
import dev.glide.core.ports.BulkProgress
import dev.glide.core.ports.ConnectionEvent
import dev.glide.core.ports.ControlMessage
import dev.glide.core.ports.DiscoveryEvent
import dev.glide.core.ports.IncomingStream
import dev.glide.core.ports.InputStreamProvider
import dev.glide.core.ports.ReceiveStore
import dev.glide.core.ports.SourceAccess
import dev.glide.core.ports.SourceMetadata
import dev.glide.core.ports.TransferTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * HARD-005 behavioral ownership tests for the Android process runtime.
 *
 * These tests intentionally exercise the runtime boundary rather than source-text pattern matching:
 * active coordinator and pairing jobs cannot exist before a service scope is attached; recreation
 * only creates new observers; exact-session actions are enforced; and service detachment terminates
 * process-local ownership without silently moving the transfer into a ViewModel scope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferRuntimeTest {

    private val dispatcher = StandardTestDispatcher()

    private class TestTransport : TransferTransport {
        val connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 8)
        val acceptedPeers = mutableListOf<PeerId>()
        val rejectedPeers = mutableListOf<PeerId>()
        var disconnectCalls: Int = 0

        override suspend fun startDiscovery(serviceId: String): Flow<DiscoveryEvent> = emptyFlow()
        override suspend fun stopDiscovery() {}
        override suspend fun startAdvertising(
            serviceId: String,
            localDeviceName: String
        ): Flow<AdvertisingEvent> = emptyFlow()
        override suspend fun stopAdvertising() {}
        override suspend fun initiateConnection(peerId: PeerId, localDeviceName: String) {}
        override suspend fun acceptConnection(peerId: PeerId) {
            acceptedPeers += peerId
        }
        override suspend fun rejectConnection(peerId: PeerId) {
            rejectedPeers += peerId
        }
        override fun observeConnectionEvents(): Flow<ConnectionEvent> = connectionEvents.asSharedFlow()
        override suspend fun sendControl(peerId: PeerId, payload: ByteArray) {}
        override fun observeControl(): Flow<ControlMessage> = emptyFlow()
        override suspend fun sendStream(
            peerId: PeerId,
            bulkRef: BulkRef,
            streamSource: InputStreamProvider
        ): Flow<BulkProgress> = emptyFlow()
        override fun observeIncomingStream(): Flow<IncomingStream> = emptyFlow()
        override suspend fun cancelPayload(bulkRef: BulkRef) {}
        override suspend fun disconnect(peerId: PeerId) {
            disconnectCalls++
        }
    }

    private class TestSourceAccess : SourceAccess {
        override suspend fun validateSource(handle: SourceHandle): SourceMetadata =
            SourceMetadata("test.txt", "text/plain", 100L)

        override suspend fun openSource(handle: SourceHandle): InputStream =
            ByteArrayInputStream(ByteArray(100))
    }

    private class TestReceiveStore : ReceiveStore {
        override suspend fun hasAvailableSpace(requiredBytes: Long): Boolean = true

        override suspend fun prepareStaging(
            sessionId: SessionId,
            itemId: ItemId,
            expectedBytes: Long,
            displayName: String,
            mimeType: String,
            relativePath: String?
        ): StagingHandle = StagingHandle("staging-${itemId.value}")

        override suspend fun openStagingOutput(handle: StagingHandle): OutputStream =
            ByteArrayOutputStream()

        override suspend fun commitVerified(
            handle: StagingHandle,
            digest: Sha256Digest
        ): CommittedFileHandle = CommittedFileHandle("file-${handle.value}")

        override suspend fun discardStaging(handle: StagingHandle) {}
    }

    private fun coordinator(
        sessionId: SessionId = SessionId(UUID.randomUUID().toString()),
        transport: TransferTransport = TestTransport()
    ): TransferCoordinator = TransferCoordinator(
        sessionId = sessionId,
        role = DeviceRole.Receiver,
        peerId = PeerId("peer-runtime"),
        localDisplayName = "Runtime Receiver",
        transport = transport,
        sourceAccess = TestSourceAccess(),
        receiveStore = TestReceiveStore(),
        scope = kotlinx.coroutines.CoroutineScope(dispatcher),
        ioDispatcher = dispatcher
    )

    @Test
    fun activeWork_withoutServiceScope_failsClosed() = runTest(dispatcher) {
        val runtime = TransferRuntime.forTesting()
        val coordinator = coordinator()
        val transport = TestTransport()

        assertFalse(runtime.attachCoordinatorForTesting(coordinator))
        assertFalse(
            runtime.dispatch(
                TransferRuntimeCommand.BindPairingTransport(transport)
            )
        )
        assertFalse(runtime.isSessionActive.value)
        assertNull(runtime.activeSessionId.value)
    }

    @Test
    fun serviceOwnedCoordinator_duplicateSameSessionIsIdempotent_differentSessionRejected() =
        runTest(dispatcher) {
            val runtime = TransferRuntime.forTesting()
            runtime.attachServiceScope(backgroundScope)

            val first = coordinator()
            val second = coordinator()

            assertTrue(runtime.attachCoordinatorForTesting(first, "Sender"))
            testScheduler.runCurrent()
            assertSame(first, runtime.activeCoordinatorForTesting())
            assertTrue(runtime.isSessionActive.value)
            assertEquals(first.sessionId.value, runtime.activeSessionId.value)

            assertTrue(runtime.attachCoordinatorForTesting(first, "Sender"))
            assertSame(first, runtime.activeCoordinatorForTesting())

            assertFalse(runtime.attachCoordinatorForTesting(second, "Other sender"))
            assertSame(first, runtime.activeCoordinatorForTesting())

            runtime.detachServiceScope()
        }

    @Test
    fun stopActiveSession_requestsCancellationAndWaitsForTerminalCleanup() = runTest(dispatcher) {
        var terminalCallbacks = 0
        val runtime = TransferRuntime.forTesting()
        runtime.attachServiceScope(backgroundScope) { terminalCallbacks++ }

        val transport = TestTransport()
        val coordinator = coordinator(transport = transport)
        assertTrue(runtime.attachCoordinatorForTesting(coordinator, "Sender"))
        testScheduler.runCurrent()

        assertTrue(runtime.stopActiveSession("USER_STOPPED"))
        testScheduler.advanceUntilIdle()

        assertTrue(runtime.transferState.value is TransferUiState.Cancelled)
        assertFalse(runtime.isSessionActive.value)
        assertNull(runtime.activeSessionId.value)
        assertTrue(transport.disconnectCalls >= 1)
        assertEquals(1, terminalCallbacks)

        runtime.detachServiceScope()?.join()
    }

    @Test
    fun exactSessionCancel_rejectsStaleId_andTerminatesMatchingSession() = runTest(dispatcher) {
        var terminalCallbacks = 0
        val runtime = TransferRuntime.forTesting()
        runtime.attachServiceScope(backgroundScope) { terminalCallbacks++ }

        val coordinator = coordinator()
        assertTrue(runtime.attachCoordinatorForTesting(coordinator, "Sender"))
        testScheduler.runCurrent()

        assertFalse(
            runtime.dispatch(
                TransferRuntimeCommand.Cancel(
                    sessionId = "stale-${coordinator.sessionId.value}",
                    reason = "USER_CANCELLED"
                )
            )
        )
        assertFalse(coordinator.state.value.phase.isTerminal)

        assertTrue(
            runtime.dispatch(
                TransferRuntimeCommand.Cancel(
                    sessionId = coordinator.sessionId.value,
                    reason = "USER_CANCELLED"
                )
            )
        )
        testScheduler.advanceUntilIdle()

        assertTrue(runtime.transferState.value is TransferUiState.Cancelled)
        assertFalse(runtime.isSessionActive.value)
        assertNull(runtime.activeSessionId.value)
        assertEquals(1, terminalCallbacks)

        runtime.detachServiceScope()
    }

    @Test
    fun viewModelRecreation_observesSameRuntimeState_withoutCreatingSecondOwner() =
        runTest(dispatcher) {
            val runtime = TransferRuntime.forTesting()
            runtime.attachServiceScope(backgroundScope)

            val coordinator = coordinator()
            assertTrue(runtime.attachCoordinatorForTesting(coordinator, "Pixel 9 Pro"))
            testScheduler.runCurrent()

            val firstOwner = runtime.activeCoordinatorForTesting()
            val vm1 = TransferViewModel(runtime)
            val vm2 = TransferViewModel(runtime)

            assertSame(firstOwner, runtime.activeCoordinatorForTesting())
            assertEquals(vm1.uiState.value, vm2.uiState.value)
            assertEquals(coordinator.sessionId.value, vm1.uiState.value.sessionId)
            assertTrue(runtime.isSessionActive.value)

            runtime.detachServiceScope()
        }

    @Test
    fun pairingObservationAndConfirmation_runOnlyUnderServiceOwner() = runTest(dispatcher) {
        val runtime = TransferRuntime.forTesting()
        runtime.attachServiceScope(backgroundScope)
        val transport = TestTransport()
        val peer = PeerDevice(PeerId("paired-peer"), "Pixel 9 Pro")

        assertTrue(
            runtime.dispatch(
                TransferRuntimeCommand.BindPairingTransport(transport)
            )
        )
        testScheduler.runCurrent()

        transport.connectionEvents.emit(ConnectionEvent.Initiated(peer, "482916"))
        testScheduler.runCurrent()

        assertTrue(runtime.pairingState.value is dev.glide.app.ui.pairing.PairingUiState.Verifying)
        assertTrue(runtime.dispatch(TransferRuntimeCommand.ConfirmPairing(peer.id)))
        testScheduler.runCurrent()
        assertEquals(listOf(peer.id), transport.acceptedPeers)

        assertFalse(runtime.dispatch(TransferRuntimeCommand.ConfirmPairing(peer.id)))
        testScheduler.runCurrent()
        assertEquals(1, transport.acceptedPeers.size)

        runtime.detachServiceScope()
    }

    @Test
    fun detachServiceScope_interruptsNonTerminalOwnership_andPreservesRetryState() =
        runTest(dispatcher) {
            val runtime = TransferRuntime.forTesting()
            runtime.attachServiceScope(backgroundScope)
            val coordinator = coordinator()

            assertTrue(runtime.attachCoordinatorForTesting(coordinator, "Sender"))
            testScheduler.runCurrent()

            val cleanupJob = runtime.detachServiceScope()
            testScheduler.advanceUntilIdle()
            cleanupJob?.join()

            val state = runtime.transferState.value
            assertTrue(state is TransferUiState.Failed)
            state as TransferUiState.Failed
            assertEquals("SERVICE_STOPPED", state.code)
            assertTrue(state.isInterrupted)
            assertFalse(runtime.isSessionActive.value)
            assertNull(runtime.activeSessionId.value)
            assertNull(runtime.activeCoordinatorForTesting())
            assertFalse(runtime.isServiceAttached())
            assertTrue(coordinator.state.value.phase.isTerminal)
            assertTrue((coordinator.transport as TestTransport).disconnectCalls >= 1)
        }

    @Test
    fun receiveAvailability_beforeHard006_remainsFailClosed() = runTest(dispatcher) {
        val runtime = TransferRuntime.forTesting()
        runtime.attachServiceScope(backgroundScope)

        assertFalse(
            runtime.dispatch(
                TransferRuntimeCommand.StartReceiveAvailability("Glide Device")
            )
        )
        assertTrue(runtime.discoveryState.value is dev.glide.app.ui.discovery.DiscoveryUiState.Blocked)

        runtime.detachServiceScope()
    }
}
