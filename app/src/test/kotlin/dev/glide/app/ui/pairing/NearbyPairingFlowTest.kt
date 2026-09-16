package dev.glide.app.ui.pairing

import dev.glide.app.service.TransferSessionFactory
import dev.glide.app.service.TransferSessionManager
import dev.glide.app.ui.discovery.DeviceType
import dev.glide.app.ui.selection.SelectedTransferStore
import dev.glide.app.ui.transfer.TransferViewModel
import dev.glide.core.domain.BatchId
import dev.glide.core.domain.CommittedFileHandle
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.StagingHandle
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferItem
import dev.glide.core.ports.AdvertisingEvent
import dev.glide.core.ports.BulkProgress
import dev.glide.core.ports.ConnectionEvent
import dev.glide.core.ports.ControlMessage
import dev.glide.core.ports.DiscoveryEvent
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyPairingFlowTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakePairingTransport(val localPeerId: PeerId) : TransferTransport {
        val connectionEventsFlow = MutableSharedFlow<ConnectionEvent>(replay = 1, extraBufferCapacity = 16)
        val acceptedPeers = mutableListOf<PeerId>()
        val rejectedPeers = mutableListOf<PeerId>()

        override suspend fun startDiscovery(serviceId: String): Flow<DiscoveryEvent> = emptyFlow()
        override suspend fun stopDiscovery() {}
        override suspend fun startAdvertising(serviceId: String, localDeviceName: String): Flow<AdvertisingEvent> = emptyFlow()
        override suspend fun stopAdvertising() {}
        override suspend fun initiateConnection(peerId: PeerId, localDeviceName: String) {}
        override suspend fun acceptConnection(peerId: PeerId) {
            acceptedPeers.add(peerId)
        }
        override suspend fun rejectConnection(peerId: PeerId) {
            rejectedPeers.add(peerId)
        }
        override fun observeConnectionEvents(): Flow<ConnectionEvent> = connectionEventsFlow.asSharedFlow()
        override suspend fun sendControl(peerId: PeerId, payload: ByteArray) {}
        override fun observeControl(): Flow<ControlMessage> = emptyFlow()
        override suspend fun prepareStream(streamSource: InputStreamProvider): dev.glide.core.domain.BulkRef =
            dev.glide.core.domain.BulkRef("bulk-dummy")
        override suspend fun sendStream(peerId: PeerId, bulkRef: dev.glide.core.domain.BulkRef): Flow<BulkProgress> = emptyFlow()
        override fun observeIncomingStream(): Flow<IncomingStream> = emptyFlow()
        override fun observeBulkProgress(): Flow<BulkProgress> = emptyFlow()
        override suspend fun cancelPayload(bulkRef: dev.glide.core.domain.BulkRef) {}
        override suspend fun disconnect(peerId: PeerId) {}
    }

    private class LocalTestSourceAccess : SourceAccess {
        override suspend fun validateSource(handle: SourceHandle): SourceMetadata =
            SourceMetadata("test.txt", "text/plain", 100L)
        override suspend fun openSource(handle: SourceHandle): InputStream =
            ByteArrayInputStream(ByteArray(100))
    }

    private class LocalTestReceiveStore : ReceiveStore {
        override suspend fun hasAvailableSpace(requiredBytes: Long): Boolean = true
        override suspend fun prepareStaging(sessionId: SessionId, itemId: ItemId, expectedBytes: Long, displayName: String, mimeType: String): StagingHandle =
            StagingHandle("staging-${itemId.value}")
        override suspend fun openStagingOutput(handle: StagingHandle): OutputStream = ByteArrayOutputStream()
        override suspend fun commitVerified(handle: StagingHandle, digest: Sha256Digest): CommittedFileHandle =
            CommittedFileHandle("file-${handle.value}")
        override suspend fun discardStaging(handle: StagingHandle) {}
    }

    private class LocalTestHistoryStore : HistoryStore {
        override suspend fun recordSession(record: SessionRecord) {}
        override suspend fun recordItem(record: ItemRecord) {}
        override suspend fun updateSessionOutcome(sessionId: SessionId, outcome: String, endedAtMs: Long) {}
        override suspend fun getSession(sessionId: SessionId): SessionRecord? = null
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        SelectedTransferStore.resetForTesting()
        TransferSessionManager.resetForTesting()
    }

    @After
    fun tearDown() {
        SelectedTransferStore.resetForTesting()
        TransferSessionManager.resetForTesting()
        Dispatchers.resetMain()
    }

    @Test
    fun pairingFlow_surfacesRealDigits_enforcesSingleConfirm_andStartsSenderSessionWithFullBatch() = runTest(testDispatcher) {
        val viewModel = TransferViewModel()
        val transport = FakePairingTransport(PeerId("local-sender"))
        var pairingSucceededPeer: PeerId? = null

        viewModel.bindTransport(transport) { peerId ->
            pairingSucceededPeer = peerId
        }
        testScheduler.runCurrent()

        val remotePeer = PeerDevice(PeerId("remote-receiver"), "Pixel 9 Pro")
        val realSdkDigits = "592817"

        // 1. Connection is initiated with real SDK digits
        transport.connectionEventsFlow.emit(ConnectionEvent.Initiated(remotePeer, realSdkDigits))
        testScheduler.runCurrent()

        val verifying = viewModel.pairingState.value
        assertTrue(verifying is PairingUiState.Verifying)
        val verState = verifying as PairingUiState.Verifying
        assertEquals("remote-receiver", verState.peerId.value)
        assertEquals("Pixel 9 Pro", verState.peerName)
        assertEquals(realSdkDigits, verState.authDigits)
        assertFalse(verState.localConfirmed)

        // 2. Confirm pairing (first tap) accepts connection
        viewModel.confirmPairing(remotePeer.id)
        testScheduler.runCurrent()
        assertEquals(1, transport.acceptedPeers.size)
        assertEquals(remotePeer.id, transport.acceptedPeers[0])
        assertTrue((viewModel.pairingState.value as PairingUiState.Verifying).localConfirmed)

        // 3. Duplicate confirmation tap is strictly rejected (no second accept call)
        viewModel.confirmPairing(remotePeer.id)
        testScheduler.runCurrent()
        assertEquals(1, transport.acceptedPeers.size)

        // 4. Remote peer also accepts -> Connected
        transport.connectionEventsFlow.emit(ConnectionEvent.Connected(remotePeer))
        testScheduler.runCurrent()

        val connected = viewModel.pairingState.value
        assertTrue(connected is PairingUiState.Connected)
        assertEquals(remotePeer.id, (connected as PairingUiState.Connected).peer.id)
        assertEquals(remotePeer.id, pairingSucceededPeer)

        // 5. Store 2-file batch into SelectedTransferStore and launch sender session
        val item1Id = ItemId(UUID.randomUUID().toString())
        val item2Id = ItemId(UUID.randomUUID().toString())
        val handle1 = SourceHandle("content://files/1")
        val handle2 = SourceHandle("content://files/2")
        val batchId = BatchId(UUID.randomUUID().toString())
        val batch = TransferBatch(
            id = batchId,
            items = listOf(
                TransferItem(item1Id, "doc.pdf", "application/pdf", 100L),
                TransferItem(item2Id, "image.png", "image/png", 200L)
            )
        )
        val sourceMap = mapOf(item1Id to handle1, item2Id to handle2)
        SelectedTransferStore.store(batch, sourceMap)

        val factory = TransferSessionFactory(
            sourceAccess = LocalTestSourceAccess(),
            receiveStore = LocalTestReceiveStore(),
            historyStore = LocalTestHistoryStore()
        )

        val sessionStarted = viewModel.startSenderSession(
            factory = factory,
            transport = transport,
            peerId = remotePeer.id,
            peerDisplayName = remotePeer.displayName,
            batchId = batchId,
            scope = this
        )
        assertTrue(sessionStarted)

        val activeCoordinator = TransferSessionManager.getActiveCoordinator()
        assertNotNull(activeCoordinator)
        assertEquals(DeviceRole.Sender, activeCoordinator!!.role)
        assertEquals(2, activeCoordinator.senderBatch?.items?.size)
        assertEquals(handle1, activeCoordinator.sourceMap[item1Id])
        assertEquals(handle2, activeCoordinator.sourceMap[item2Id])

        TransferSessionManager.stopSession()
    }

    @Test
    fun pairingFlow_rejectPairing_terminatesAttemptWithFailedState() = runTest(testDispatcher) {
        val viewModel = TransferViewModel()
        val transport = FakePairingTransport(PeerId("local-sender"))
        viewModel.bindTransport(transport)
        testScheduler.runCurrent()

        val remotePeer = PeerDevice(PeerId("remote-receiver"), "Galaxy Tab")
        transport.connectionEventsFlow.emit(ConnectionEvent.Initiated(remotePeer, "123456"))
        testScheduler.runCurrent()

        viewModel.rejectPairing(remotePeer.id)
        testScheduler.runCurrent()

        assertEquals(1, transport.rejectedPeers.size)
        assertEquals(remotePeer.id, transport.rejectedPeers[0])

        val state = viewModel.pairingState.value
        assertTrue(state is PairingUiState.Failed)
    }
}
