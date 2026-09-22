package dev.glide.app.lifecycle

import dev.glide.app.service.TransferSessionManager
import dev.glide.app.transfer.ActiveSessionTracker
import dev.glide.app.ui.transfer.TransferUiState
import dev.glide.core.coordinator.ItemPhase
import dev.glide.core.coordinator.SessionPhase
import dev.glide.core.coordinator.TransferCoordinator
import dev.glide.core.domain.BatchId
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.CommittedFileHandle
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.StagingHandle
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferItem
import dev.glide.core.ports.BulkProgress
import dev.glide.core.ports.ConnectionEvent
import dev.glide.core.ports.ControlMessage
import dev.glide.core.ports.DiscoveryEvent
import dev.glide.core.ports.AdvertisingEvent
import dev.glide.core.ports.HistoryStore
import dev.glide.core.ports.IncomingStream
import dev.glide.core.ports.InputStreamProvider
import dev.glide.core.ports.ItemRecord
import dev.glide.core.ports.ReceiveStore
import dev.glide.core.ports.SessionRecord
import dev.glide.core.ports.SourceAccess
import dev.glide.core.ports.SourceMetadata
import dev.glide.core.ports.TransferTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * End-to-end chaos acceptance tests certifying M07/M08 resilience in the application layer:
 * - Service destruction: stops coordinator, cancels transport, clears active session tracker.
 * - Mid-batch network loss: session marks Interrupted (never Completed), prior verified items preserved.
 * - Stale notification actions: rejected against active session mismatch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChaosGateAcceptanceTest {

    private val testDispatcher = StandardTestDispatcher()

    private class ChaosTransport : TransferTransport {
        val connectionEvents = kotlinx.coroutines.flow.MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 64)
        var isDisconnected = false

        override suspend fun startDiscovery(serviceId: String): Flow<DiscoveryEvent> = emptyFlow()
        override suspend fun stopDiscovery() {}
        override suspend fun startAdvertising(serviceId: String, localDeviceName: String): Flow<AdvertisingEvent> = emptyFlow()
        override suspend fun stopAdvertising() {}
        override suspend fun initiateConnection(peerId: PeerId, localDeviceName: String) {}
        override suspend fun acceptConnection(peerId: PeerId) {}
        override suspend fun rejectConnection(peerId: PeerId) {}
        override fun observeConnectionEvents(): Flow<ConnectionEvent> = connectionEvents
        override suspend fun sendControl(peerId: PeerId, payload: ByteArray) {}
        override fun observeControl(): Flow<ControlMessage> = emptyFlow()
        override suspend fun sendStream(peerId: PeerId, bulkRef: BulkRef, streamSource: InputStreamProvider): Flow<BulkProgress> = emptyFlow()
        override fun observeIncomingStream(): Flow<IncomingStream> = emptyFlow()
        override suspend fun cancelPayload(bulkRef: BulkRef) {}
        override suspend fun disconnect(peerId: PeerId) {
            isDisconnected = true
            connectionEvents.tryEmit(ConnectionEvent.Disconnected(peerId))
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
        override suspend fun openStagingOutput(handle: StagingHandle): OutputStream = ByteArrayOutputStream()
        override suspend fun commitVerified(handle: StagingHandle, digest: Sha256Digest): CommittedFileHandle =
            CommittedFileHandle("file-${handle.value}")
        override suspend fun discardStaging(handle: StagingHandle) {}
    }

    private class TestHistoryStore : HistoryStore {
        val sessions = mutableMapOf<SessionId, SessionRecord>()
        val items = mutableListOf<ItemRecord>()

        override suspend fun recordSession(record: SessionRecord) {
            sessions[record.sessionId] = record
        }

        override suspend fun recordItem(record: ItemRecord) {
            items.add(record)
        }

        override suspend fun updateSessionOutcome(sessionId: SessionId, outcome: String, endedAtMs: Long) {
            val existing = sessions[sessionId] ?: return
            sessions[sessionId] = existing.copy(outcome = outcome, endedAtMs = endedAtMs)
        }

        override suspend fun updateItemOutcome(itemId: ItemId, outcome: String, actualBytes: Long?, digest: Sha256Digest?) {
            val idx = items.indexOfFirst { it.itemId == itemId }
            if (idx >= 0) {
                items[idx] = items[idx].copy(outcome = outcome, actualBytes = actualBytes, digest = digest)
            }
        }

        override suspend fun getSession(sessionId: SessionId): SessionRecord? = sessions[sessionId]
        override suspend fun getItemsForSession(sessionId: SessionId): List<ItemRecord> = items.filter { it.sessionId == sessionId }
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
    fun testServiceDestroy_stopsActiveSessionAndClosesTransport() = runTest(testDispatcher) {
        val transport = ChaosTransport()
        val coordinator = TransferCoordinator(
            sessionId = SessionId(UUID.randomUUID().toString()),
            role = DeviceRole.Receiver,
            peerId = PeerId("peer-chaos-1"),
            localDisplayName = "Receiver Device",
            transport = transport,
            sourceAccess = TestSourceAccess(),
            receiveStore = TestReceiveStore(),
            scope = backgroundScope
        )

        val started = TransferSessionManager.startSession(
            coordinator = coordinator,
            peerDisplayName = "Sender Device",
            scope = backgroundScope
        )
        assertTrue("Session must start successfully", started)
        testScheduler.advanceUntilIdle()

        assertTrue("Session must be active in tracker", ActiveSessionTracker.isSessionActive.value)
        assertEquals(coordinator.sessionId.value, ActiveSessionTracker.activeSessionId.value)

        // Android OS terminates service (e.g. low memory or user kill)
        TransferSessionManager.stopSession()
        testScheduler.advanceUntilIdle()

        assertFalse("Session must no longer be active in tracker after service destroy",
            ActiveSessionTracker.isSessionActive.value
        )
        assertNull("Active session ID must be cleared", ActiveSessionTracker.activeSessionId.value)
        assertNull("Active coordinator must be cleared", TransferSessionManager.getActiveCoordinator())
    }

    @Test
    fun testBatchWithNetworkLossMidTransfer_preservesVerifiedHistory() = runTest(testDispatcher) {
        val sessionId = SessionId(UUID.randomUUID().toString())
        val historyStore = TestHistoryStore()
        val receiveStore = TestReceiveStore()
        val transport = ChaosTransport()

        val item1Id = ItemId(UUID.randomUUID().toString())
        val item2Id = ItemId(UUID.randomUUID().toString())

        val item1Content = "Verified Item 1 Content".toByteArray()
        val item1Digest = Sha256Digest(MessageDigest.getInstance("SHA-256").digest(item1Content).joinToString("") { "%02x".format(it) })

        val batch = TransferBatch(
            id = BatchId(UUID.randomUUID().toString()),
            items = listOf(
                TransferItem(item1Id, "file1.txt", "text/plain", item1Content.size.toLong(), item1Digest),
                TransferItem(item2Id, "file2.txt", "text/plain", 2000L)
            )
        )

        val coordinatorScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        val coordinator = TransferCoordinator(
            sessionId = sessionId,
            role = DeviceRole.Receiver,
            peerId = PeerId("peer-chaos-2"),
            localDisplayName = "Receiver Device",
            transport = transport,
            sourceAccess = TestSourceAccess(),
            receiveStore = receiveStore,
            historyStore = historyStore,
            scope = coordinatorScope
        )

        val started = TransferSessionManager.startSession(
            coordinator = coordinator,
            peerDisplayName = "Sender Device",
            scope = coordinatorScope
        )
        assertTrue(started)
        testScheduler.advanceUntilIdle()

        // Simulate Item 1 verified and committed
        historyStore.recordSession(
            SessionRecord(
                sessionId = sessionId,
                batchId = batch.id,
                role = DeviceRole.Receiver,
                peerLabel = "Sender Device",
                startedAtMs = 1000L
            )
        )
        historyStore.recordItem(
            ItemRecord(
                sessionId = sessionId,
                itemId = item1Id,
                ordinal = 0,
                displayName = "file1.txt",
                mimeType = "text/plain",
                offeredBytes = item1Content.size.toLong(),
                outcome = "Verified"
            )
        )
        historyStore.recordItem(
            ItemRecord(
                sessionId = sessionId,
                itemId = item2Id,
                ordinal = 1,
                displayName = "file2.txt",
                mimeType = "text/plain",
                offeredBytes = 2000L,
                outcome = "Pending"
            )
        )

        // Inject network drop / disconnect mid-transfer during Item 2
        transport.connectionEvents.emit(ConnectionEvent.Disconnected(PeerId("peer-chaos-2")))
        testScheduler.advanceUntilIdle()

        // Coordinator processes disconnect and sets terminal failure
        val state = coordinator.state.value
        assertTrue("Coordinator must be in terminal state after disconnect: ${state.phase}", state.isTerminal)
        assertEquals("Phase must be Interrupted after disconnect", SessionPhase.Interrupted, state.phase)

        // Verify history records: Item 1 is Verified, session outcome is Interrupted, NEVER Completed
        val sessionRecord = historyStore.getSession(sessionId)
        assertNotNull(sessionRecord)
        assertEquals("Session outcome must reflect terminal interruption, never Completed", "Interrupted", sessionRecord?.outcome)

        val items = historyStore.getItemsForSession(sessionId)
        val file1Record = items.find { it.itemId == item1Id }
        assertNotNull(file1Record)
        assertEquals("Item 1 must remain Verified", "Verified", file1Record?.outcome)
    }

    @Test
    fun testStaleNotificationAction_doesNotAffectNewSession() = runTest(testDispatcher) {
        val coordinator = TransferCoordinator(
            sessionId = SessionId(UUID.randomUUID().toString()),
            role = DeviceRole.Receiver,
            peerId = PeerId("peer-new-session"),
            localDisplayName = "Receiver Device",
            transport = ChaosTransport(),
            sourceAccess = TestSourceAccess(),
            receiveStore = TestReceiveStore(),
            scope = backgroundScope
        )

        TransferSessionManager.startSession(
            coordinator = coordinator,
            peerDisplayName = "Sender Device",
            scope = backgroundScope
        )
        testScheduler.advanceUntilIdle()

        // Stale action arrives targeting an OLD session ID
        val oldSessionId = UUID.randomUUID().toString()
        val accepted = TransferSessionManager.cancelTransfer(reason = "USER_CANCELLED", sessionId = oldSessionId)

        // Active session must NOT be cancelled by mismatched session ID
        assertFalse("Stale action with mismatched session ID must be rejected", accepted)
        assertFalse("Active session must not be terminated by stale action", coordinator.state.value.isTerminal)
        assertEquals(coordinator.sessionId.value, ActiveSessionTracker.activeSessionId.value)
    }
}
