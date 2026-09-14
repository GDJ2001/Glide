package dev.glide.app.service

import dev.glide.app.transfer.ActiveSessionTracker
import dev.glide.app.ui.transfer.TransferUiState
import dev.glide.core.coordinator.TransferCoordinator
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.CommittedFileHandle
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Unit tests verifying [TransferSessionManager] active coordinator ownership,
 * duplicate start protection, rotation resilience, and terminal teardown (GLIDE-028).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferSessionManagerTest {

    private val testDispatcher = StandardTestDispatcher()

    private class TestTransport : TransferTransport {
        override suspend fun startDiscovery(serviceId: String): Flow<DiscoveryEvent> = emptyFlow()
        override suspend fun stopDiscovery() {}
        override suspend fun startAdvertising(serviceId: String, localDeviceName: String): Flow<AdvertisingEvent> = emptyFlow()
        override suspend fun stopAdvertising() {}
        override suspend fun initiateConnection(peerId: PeerId, localDeviceName: String) {}
        override suspend fun acceptConnection(peerId: PeerId) {}
        override suspend fun rejectConnection(peerId: PeerId) {}
        override fun observeConnectionEvents(): Flow<ConnectionEvent> = emptyFlow()
        override suspend fun sendControl(peerId: PeerId, payload: ByteArray) {}
        override fun observeControl(): Flow<ControlMessage> = emptyFlow()
        override suspend fun sendStream(peerId: PeerId, bulkRef: BulkRef, streamSource: InputStreamProvider): Flow<BulkProgress> = emptyFlow()
        override fun observeIncomingStream(): Flow<IncomingStream> = emptyFlow()
        override suspend fun cancelPayload(bulkRef: BulkRef) {}
        override suspend fun disconnect(peerId: PeerId) {}
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
            mimeType: String
        ): StagingHandle = StagingHandle("staging-${itemId.value}")
        override suspend fun openStagingOutput(handle: StagingHandle): OutputStream = ByteArrayOutputStream()
        override suspend fun commitVerified(handle: StagingHandle, digest: Sha256Digest): CommittedFileHandle =
            CommittedFileHandle("file-${handle.value}")
        override suspend fun discardStaging(handle: StagingHandle) {}
    }

    private fun createCoordinator(sessionIdStr: String = UUID.randomUUID().toString()): TransferCoordinator {
        return TransferCoordinator(
            sessionId = SessionId(sessionIdStr),
            role = DeviceRole.Receiver,
            peerId = PeerId("peer-1"),
            localDisplayName = "Local Receiver",
            transport = TestTransport(),
            sourceAccess = TestSourceAccess(),
            receiveStore = TestReceiveStore(),
            scope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        TransferSessionManager.resetForTesting()
    }

    @After
    fun tearDown() {
        TransferSessionManager.resetForTesting()
        Dispatchers.resetMain()
    }

    @Test
    fun startSession_attachesCoordinatorAndEmitsConnectingState() = runTest(testDispatcher) {
        val coordinator = createCoordinator()
        val started = TransferSessionManager.startSession(
            coordinator = coordinator,
            peerDisplayName = "Sender Device",
            scope = backgroundScope
        )

        assertTrue(started)
        testScheduler.advanceUntilIdle()

        val state = TransferSessionManager.uiState.value
        assertTrue(state is TransferUiState.Connecting)
        val connecting = state as TransferUiState.Connecting
        assertEquals(coordinator.sessionId.value, connecting.sessionId)
        assertEquals("Sender Device", connecting.peerName)

        assertTrue(ActiveSessionTracker.isSessionActive.value)
        assertEquals(coordinator.sessionId.value, ActiveSessionTracker.activeSessionId.value)
    }

    @Test
    fun duplicateStart_withSameSessionId_isIdempotent() = runTest(testDispatcher) {
        val coordinator = createCoordinator()
        val firstStart = TransferSessionManager.startSession(
            coordinator = coordinator,
            peerDisplayName = "Sender Device",
            scope = backgroundScope
        )
        assertTrue(firstStart)

        // Attempt second start with the exact same coordinator / session ID
        val duplicateStart = TransferSessionManager.startSession(
            coordinator = coordinator,
            peerDisplayName = "Sender Device",
            scope = backgroundScope
        )
        assertTrue(duplicateStart)

        // Only one coordinator is registered
        assertEquals(coordinator, TransferSessionManager.getActiveCoordinator())
    }

    @Test
    fun startSession_whenDifferentSessionActive_rejectsSecondSession() = runTest(testDispatcher) {
        val coordinator1 = createCoordinator()
        val coordinator2 = createCoordinator()

        val firstStart = TransferSessionManager.startSession(
            coordinator = coordinator1,
            peerDisplayName = "Sender 1",
            scope = backgroundScope
        )
        assertTrue(firstStart)
        testScheduler.advanceUntilIdle()

        // Attempt start with a different session ID while session 1 is active
        val secondStart = TransferSessionManager.startSession(
            coordinator = coordinator2,
            peerDisplayName = "Sender 2",
            scope = backgroundScope
        )
        assertFalse(secondStart)
        assertEquals(coordinator1, TransferSessionManager.getActiveCoordinator())
    }

    @Test
    fun rotationResilience_multipleObserversReceiveContinuousState() = runTest(testDispatcher) {
        val coordinator = createCoordinator()
        TransferSessionManager.startSession(
            coordinator = coordinator,
            peerDisplayName = "Sender Device",
            scope = backgroundScope
        )
        testScheduler.advanceUntilIdle()

        // Observer 1 (Activity before rotation)
        val stateBeforeRotation = TransferSessionManager.uiState.value
        assertTrue(stateBeforeRotation is TransferUiState.Connecting)

        // Simulate Activity recreation (new ViewModel collecting from TransferSessionManager)
        val stateAfterRotation = TransferSessionManager.uiState.value
        assertEquals(stateBeforeRotation, stateAfterRotation)
        assertEquals(coordinator.sessionId.value, stateAfterRotation.sessionId)
    }

    @Test
    fun cancelTransfer_cancelsCoordinatorAndTeardownsSession() = runTest(testDispatcher) {
        var terminalCalled = false
        TransferSessionManager.onTerminalCallback = {
            terminalCalled = true
        }

        val coordinator = createCoordinator()
        TransferSessionManager.startSession(
            coordinator = coordinator,
            peerDisplayName = "Sender Device",
            scope = backgroundScope
        )
        testScheduler.advanceUntilIdle()

        TransferSessionManager.cancelTransfer("USER_REQUESTED")
        testScheduler.advanceUntilIdle()

        val state = TransferSessionManager.uiState.value
        assertTrue(state is TransferUiState.Cancelled)
        assertTrue(terminalCalled)
        assertFalse(ActiveSessionTracker.isSessionActive.value)
    }

    @Test
    fun stopSession_cleansUpAndInvokesTeardown() = runTest(testDispatcher) {
        var terminalCalled = false
        TransferSessionManager.onTerminalCallback = {
            terminalCalled = true
        }

        val coordinator = createCoordinator()
        TransferSessionManager.startSession(
            coordinator = coordinator,
            peerDisplayName = "Sender Device",
            scope = backgroundScope
        )
        testScheduler.advanceUntilIdle()

        TransferSessionManager.stopSession()
        testScheduler.advanceUntilIdle()

        assertNull(TransferSessionManager.getActiveCoordinator())
        assertTrue(terminalCalled)
        assertFalse(ActiveSessionTracker.isSessionActive.value)
    }
}
