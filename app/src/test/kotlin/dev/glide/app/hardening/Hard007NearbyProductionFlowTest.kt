package dev.glide.app.hardening

import dev.glide.app.discovery.ProductionDiscoveryController
import dev.glide.app.service.TransferRuntime
import dev.glide.app.service.TransferRuntimeCommand
import dev.glide.app.service.TransferSessionFactory
import dev.glide.app.ui.pairing.PairingUiState
import dev.glide.app.ui.selection.SelectedTransferStore
import dev.glide.app.ui.transfer.TransferUiState
import dev.glide.core.coordinator.SessionPhase
import dev.glide.core.domain.BatchId
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.CommittedFileHandle
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.MessageId
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
import dev.glide.core.protocol.Envelope
import dev.glide.core.protocol.HelloBody
import dev.glide.core.protocol.MessageTypes
import dev.glide.core.protocol.NegotiationValidator
import dev.glide.core.protocol.OfferBeginBody
import dev.glide.core.protocol.OfferEndBody
import dev.glide.core.protocol.OfferItemDto
import dev.glide.core.protocol.OfferPageBody
import dev.glide.core.protocol.ProtocolCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
import java.util.UUID

/**
 * HARD-007 production-flow proofs around the service-owned Nearby runtime boundary.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Hard007NearbyProductionFlowTest {

    private val dispatcher = StandardTestDispatcher()

    private class FlowTransport : TransferTransport {
        val connectionEvents = MutableSharedFlow<ConnectionEvent>(replay = 1, extraBufferCapacity = 16)
        val controls = MutableSharedFlow<ControlMessage>(extraBufferCapacity = 32)
        val advertisingEvents = MutableSharedFlow<AdvertisingEvent>(extraBufferCapacity = 8)

        val initiated = mutableListOf<Pair<PeerId, String>>()
        val accepted = mutableListOf<PeerId>()
        val rejected = mutableListOf<PeerId>()
        val disconnected = mutableListOf<PeerId>()
        val sentControls = mutableListOf<ByteArray>()

        var advertisingStarts = 0
        var streamStarts = 0
        var preparedStreams = 0

        override suspend fun startDiscovery(serviceId: String): Flow<DiscoveryEvent> = emptyFlow()
        override suspend fun stopDiscovery() {}

        override suspend fun startAdvertising(
            serviceId: String,
            localDeviceName: String
        ): Flow<AdvertisingEvent> {
            advertisingStarts++
            return advertisingEvents.asSharedFlow()
        }

        override suspend fun stopAdvertising() {}

        override suspend fun initiateConnection(peerId: PeerId, localDeviceName: String) {
            initiated += peerId to localDeviceName
        }

        override suspend fun acceptConnection(peerId: PeerId) {
            accepted += peerId
        }

        override suspend fun rejectConnection(peerId: PeerId) {
            rejected += peerId
        }

        override fun observeConnectionEvents(): Flow<ConnectionEvent> =
            connectionEvents.asSharedFlow()

        override suspend fun sendControl(peerId: PeerId, payload: ByteArray) {
            sentControls += payload
        }

        override fun observeControl(): Flow<ControlMessage> = controls.asSharedFlow()

        override suspend fun prepareStream(streamSource: InputStreamProvider): BulkRef {
            preparedStreams++
            return BulkRef("bulk-$preparedStreams")
        }

        override suspend fun sendStream(peerId: PeerId, bulkRef: BulkRef): Flow<BulkProgress> {
            streamStarts++
            return emptyFlow()
        }

        override fun observeIncomingStream(): Flow<IncomingStream> = emptyFlow()

        override suspend fun cancelPayload(bulkRef: BulkRef) {}

        override suspend fun disconnect(peerId: PeerId) {
            disconnected += peerId
        }
    }

    private class TestSourceAccess(
        private val failOpen: Boolean = false
    ) : SourceAccess {
        var openCount = 0

        override suspend fun validateSource(handle: SourceHandle): SourceMetadata =
            SourceMetadata("source.bin", "application/octet-stream", 16L)

        override suspend fun openSource(handle: SourceHandle): InputStream {
            openCount++
            if (failOpen) {
                throw java.io.FileNotFoundException("Source grant revoked")
            }
            return ByteArrayInputStream(ByteArray(16) { it.toByte() })
        }
    }

    private class TestReceiveStore : ReceiveStore {
        var prepareCount = 0

        override suspend fun hasAvailableSpace(requiredBytes: Long): Boolean = true

        override suspend fun prepareStaging(
            sessionId: SessionId,
            itemId: ItemId,
            expectedBytes: Long,
            displayName: String,
            mimeType: String,
            relativePath: String?
        ): StagingHandle {
            prepareCount++
            return StagingHandle("staging-${itemId.value}")
        }

        override suspend fun openStagingOutput(handle: StagingHandle): OutputStream =
            ByteArrayOutputStream()

        override suspend fun commitVerified(
            handle: StagingHandle,
            digest: Sha256Digest
        ): CommittedFileHandle = CommittedFileHandle("committed-${handle.value}")

        override suspend fun discardStaging(handle: StagingHandle) {}
    }

    private class TestHistoryStore : HistoryStore {
        override suspend fun recordSession(record: SessionRecord) {}
        override suspend fun recordItem(record: ItemRecord) {}
        override suspend fun updateSessionOutcome(
            sessionId: SessionId,
            outcome: String,
            endedAtMs: Long
        ) {}
        override suspend fun getSession(sessionId: SessionId): SessionRecord? = null
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        SelectedTransferStore.resetForTesting()
    }

    @After
    fun tearDown() {
        SelectedTransferStore.resetForTesting()
        Dispatchers.resetMain()
    }

    private fun newRuntime(
        transport: FlowTransport,
        sourceAccess: SourceAccess = TestSourceAccess(),
        receiveStore: TestReceiveStore = TestReceiveStore(),
        scope: kotlinx.coroutines.CoroutineScope
    ): TransferRuntime {
        val factory = TransferSessionFactory(
            sourceAccess = sourceAccess,
            receiveStore = receiveStore,
            historyStore = TestHistoryStore()
        )
        val controller = ProductionDiscoveryController.forTesting(
            runtimeScope = scope,
            transportFactory = { transport }
        )
        return TransferRuntime.forTesting(
            sessionFactory = factory,
            discoveryController = controller
        ).also { it.attachServiceScope(scope) }
    }

    private fun storeTwoFileBatch(): BatchId {
        val item1 = ItemId(UUID.randomUUID().toString())
        val item2 = ItemId(UUID.randomUUID().toString())
        val batchId = BatchId(UUID.randomUUID().toString())
        SelectedTransferStore.store(
            TransferBatch(
                id = batchId,
                items = listOf(
                    TransferItem(item1, "one.bin", "application/octet-stream", 16L),
                    TransferItem(item2, "two.bin", "application/octet-stream", 16L)
                )
            ),
            mapOf(
                item1 to SourceHandle("content://hard007/one"),
                item2 to SourceHandle("content://hard007/two")
            )
        )
        return batchId
    }

    @Test
    fun senderPairing_preservesMultiFileBatch_andStartsCoordinatorOnlyAfterAuthenticatedConnected() =
        runTest(dispatcher) {
            val transport = FlowTransport()
            val runtime = newRuntime(transport, scope = backgroundScope)
            val batchId = storeTwoFileBatch()
            val peer = PeerDevice(PeerId("peer-receiver"), "Pixel Receiver")

            assertTrue(
                runtime.dispatch(
                    TransferRuntimeCommand.StartNearbySenderPairing(
                        selectionId = batchId,
                        peerId = peer.id,
                        peerDisplayName = peer.displayName,
                        localDeviceName = "Glide Sender"
                    )
                )
            )
            runCurrent()

            assertEquals(listOf(peer.id to "Glide Sender"), transport.initiated)
            assertNull(runtime.activeCoordinatorForTesting())

            transport.connectionEvents.emit(ConnectionEvent.Initiated(peer, "482916"))
            runCurrent()
            val verifying = runtime.pairingState.value as PairingUiState.Verifying
            assertEquals("482916", verifying.authDigits)

            assertTrue(runtime.dispatch(TransferRuntimeCommand.ConfirmPairing(peer.id)))
            runCurrent()
            assertEquals(listOf(peer.id), transport.accepted)
            assertNull(runtime.activeCoordinatorForTesting())

            transport.connectionEvents.emit(ConnectionEvent.Connected(peer))
            runCurrent()

            val coordinator = runtime.activeCoordinatorForTesting()
            assertNotNull(coordinator)
            assertEquals(DeviceRole.Sender, coordinator!!.role)
            assertEquals(2, coordinator.senderBatch?.items?.size)
            assertEquals(2, coordinator.sourceMap.size)
            assertEquals(0, transport.streamStarts)
        }

    @Test
    fun senderPairing_rejectStaleConfirm_rejectAndPeerLoss_neverStartCoordinator() =
        runTest(dispatcher) {
            val transport = FlowTransport()
            val runtime = newRuntime(transport, scope = backgroundScope)
            val batchId = storeTwoFileBatch()
            val peer = PeerDevice(PeerId("peer-a"), "Galaxy Receiver")

            assertTrue(
                runtime.dispatch(
                    TransferRuntimeCommand.StartNearbySenderPairing(
                        selectionId = batchId,
                        peerId = peer.id,
                        peerDisplayName = peer.displayName
                    )
                )
            )
            runCurrent()

            transport.connectionEvents.emit(ConnectionEvent.Initiated(peer, "123456"))
            runCurrent()

            assertFalse(
                runtime.dispatch(
                    TransferRuntimeCommand.ConfirmPairing(PeerId("stale-peer"))
                )
            )
            assertTrue(transport.accepted.isEmpty())
            assertNull(runtime.activeCoordinatorForTesting())

            assertTrue(runtime.dispatch(TransferRuntimeCommand.RejectPairing(peer.id)))
            runCurrent()
            assertEquals(listOf(peer.id), transport.rejected)
            assertTrue(runtime.pairingState.value is PairingUiState.Failed)
            assertNull(runtime.activeCoordinatorForTesting())

            val transport2 = FlowTransport()
            val runtime2 = newRuntime(transport2, scope = backgroundScope)
            assertTrue(
                runtime2.dispatch(
                    TransferRuntimeCommand.StartNearbySenderPairing(
                        selectionId = batchId,
                        peerId = peer.id,
                        peerDisplayName = peer.displayName
                    )
                )
            )
            runCurrent()
            transport2.connectionEvents.emit(ConnectionEvent.Initiated(peer, "654321"))
            runCurrent()
            transport2.connectionEvents.emit(ConnectionEvent.Disconnected(peer.id))
            runCurrent()

            assertTrue(runtime2.pairingState.value is PairingUiState.Failed)
            assertNull(runtime2.activeCoordinatorForTesting())
        }

    @Test
    fun receiverPairing_startsCoordinatorOnlyAfterConnected_andOfferWaitsForConsent() =
        runTest(dispatcher) {
            val transport = FlowTransport()
            val receiveStore = TestReceiveStore()
            val runtime = newRuntime(
                transport = transport,
                receiveStore = receiveStore,
                scope = backgroundScope
            )
            val peer = PeerDevice(PeerId("peer-sender"), "Pixel Sender")

            assertTrue(
                runtime.dispatch(
                    TransferRuntimeCommand.StartReceiveAvailability("Glide Receiver")
                )
            )
            runCurrent()
            assertEquals(1, transport.advertisingStarts)
            assertNull(runtime.activeCoordinatorForTesting())

            transport.connectionEvents.emit(ConnectionEvent.Initiated(peer, "778899"))
            runCurrent()
            assertEquals("778899", (runtime.pairingState.value as PairingUiState.Verifying).authDigits)

            assertTrue(runtime.dispatch(TransferRuntimeCommand.ConfirmPairing(peer.id)))
            runCurrent()
            transport.connectionEvents.emit(ConnectionEvent.Connected(peer))
            runCurrent()

            val coordinator = runtime.activeCoordinatorForTesting()
            assertNotNull(coordinator)
            assertEquals(DeviceRole.Receiver, coordinator!!.role)
            assertEquals(0, transport.streamStarts)
            assertEquals(0, receiveStore.prepareCount)

            val sessionId = SessionId(UUID.randomUUID().toString())
            val hello = NegotiationValidator.createHello(
                role = DeviceRole.Sender,
                ephemeralPeerId = UUID.randomUUID().toString(),
                displayName = "Pixel Sender"
            )
            emitControl(
                transport,
                peer.id,
                Envelope(
                    v = 1,
                    type = MessageTypes.HELLO,
                    sessionId = sessionId,
                    messageId = MessageId(UUID.randomUUID().toString()),
                    seq = 1L,
                    body = ProtocolCodec.encodeBody(hello)
                )
            )
            runCurrent()

            val batchId = BatchId(UUID.randomUUID().toString())
            val itemId = ItemId(UUID.randomUUID().toString())
            emitControl(
                transport,
                peer.id,
                Envelope(
                    1,
                    MessageTypes.OFFER_BEGIN,
                    sessionId,
                    MessageId(UUID.randomUUID().toString()),
                    2L,
                    ProtocolCodec.encodeBody(
                        OfferBeginBody(batchId, 1, "16", 1)
                    )
                )
            )
            emitControl(
                transport,
                peer.id,
                Envelope(
                    1,
                    MessageTypes.OFFER_PAGE,
                    sessionId,
                    MessageId(UUID.randomUUID().toString()),
                    3L,
                    ProtocolCodec.encodeBody(
                        OfferPageBody(
                            batchId,
                            0,
                            listOf(
                                OfferItemDto(
                                    itemId = itemId,
                                    name = "offered.bin",
                                    mime = "application/octet-stream",
                                    sizeBytes = "16"
                                )
                            )
                        )
                    )
                )
            )
            emitControl(
                transport,
                peer.id,
                Envelope(
                    1,
                    MessageTypes.OFFER_END,
                    sessionId,
                    MessageId(UUID.randomUUID().toString()),
                    4L,
                    ProtocolCodec.encodeBody(OfferEndBody(batchId))
                )
            )
            runCurrent()

            assertTrue(runtime.transferState.value is TransferUiState.AwaitingConsent)
            assertEquals(0, transport.streamStarts)
            assertEquals(0, receiveStore.prepareCount)

            val activeSessionId = runtime.getActiveSessionId()
            assertEquals(sessionId.value, activeSessionId)
            assertFalse(
                runtime.dispatch(
                    TransferRuntimeCommand.AcceptOffer("stale-session")
                )
            )
            assertEquals(0, receiveStore.prepareCount)
        }

    @Test
    fun senderSourceRevocation_afterRemoteConsent_failsWithoutStartingBulkStream() =
        runTest(dispatcher) {
            val transport = FlowTransport()
            val sourceAccess = TestSourceAccess(failOpen = true)
            val runtime = newRuntime(
                transport = transport,
                sourceAccess = sourceAccess,
                scope = backgroundScope
            )

            val itemId = ItemId(UUID.randomUUID().toString())
            val batchId = BatchId(UUID.randomUUID().toString())
            SelectedTransferStore.store(
                TransferBatch(
                    batchId,
                    listOf(
                        TransferItem(
                            itemId,
                            "revoked.bin",
                            "application/octet-stream",
                            16L
                        )
                    )
                ),
                mapOf(itemId to SourceHandle("content://revoked/source"))
            )

            val peer = PeerDevice(PeerId("peer-receiver"), "Receiver")
            assertTrue(
                runtime.dispatch(
                    TransferRuntimeCommand.StartNearbySenderPairing(
                        selectionId = batchId,
                        peerId = peer.id,
                        peerDisplayName = peer.displayName
                    )
                )
            )
            runCurrent()
            transport.connectionEvents.emit(ConnectionEvent.Initiated(peer, "112233"))
            runCurrent()
            assertTrue(runtime.dispatch(TransferRuntimeCommand.ConfirmPairing(peer.id)))
            runCurrent()
            transport.connectionEvents.emit(ConnectionEvent.Connected(peer))
            runCurrent()

            withTimeout(2_000) {
                while (transport.sentControls.isEmpty()) {
                    delay(10)
                }
            }

            val helloEnvelope = ProtocolCodec.decode(transport.sentControls.first())
            assertEquals(MessageTypes.HELLO, helloEnvelope.type)
            val helloBody = ProtocolCodec.decodeBody<HelloBody>(helloEnvelope)
            val ack = NegotiationValidator.createHelloAck(
                hello = helloBody,
                ephemeralPeerId = UUID.randomUUID().toString(),
                displayName = "Receiver"
            )
            emitControl(
                transport,
                peer.id,
                Envelope(
                    1,
                    MessageTypes.HELLO_ACK,
                    helloEnvelope.sessionId,
                    MessageId(UUID.randomUUID().toString()),
                    1L,
                    ProtocolCodec.encodeBody(ack)
                )
            )

            withTimeout(2_000) {
                while (
                    transport.sentControls
                        .map { ProtocolCodec.decode(it).type }
                        .none { it == MessageTypes.OFFER_END }
                ) {
                    delay(10)
                }
            }

            emitControl(
                transport,
                peer.id,
                Envelope(
                    1,
                    MessageTypes.OFFER_DECISION,
                    helloEnvelope.sessionId,
                    MessageId(UUID.randomUUID().toString()),
                    2L,
                    ProtocolCodec.encodeBody(
                        dev.glide.core.protocol.OfferDecisionBody(
                            batchId = batchId,
                            accepted = true
                        )
                    )
                )
            )

            withTimeout(2_000) {
                while (runtime.activeCoordinatorForTesting()?.state?.value?.phase != SessionPhase.Failed) {
                    delay(10)
                }
            }

            assertTrue(sourceAccess.openCount >= 1)
            assertEquals(0, transport.preparedStreams)
            assertEquals(0, transport.streamStarts)
        }

    private suspend fun emitControl(
        transport: FlowTransport,
        peerId: PeerId,
        envelope: Envelope
    ) {
        transport.controls.emit(
            ControlMessage(
                peerId = peerId,
                payload = ProtocolCodec.encode(envelope)
            )
        )
    }
}
