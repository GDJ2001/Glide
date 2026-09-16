package dev.glide.app.service

import dev.glide.core.coordinator.SessionPhase
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Unit tests verifying [TransferSessionFactory] composition and receiver session ID adoption (LAN-003).
 */
class TransferSessionFactoryTest {

    private class TestSourceAccess : SourceAccess {
        val sources = mutableMapOf<SourceHandle, ByteArray>()
        override suspend fun validateSource(handle: SourceHandle): SourceMetadata {
            val bytes = sources[handle] ?: byteArrayOf()
            return SourceMetadata("file.txt", "text/plain", bytes.size.toLong())
        }
        override suspend fun openSource(handle: SourceHandle): InputStream {
            val bytes = sources[handle] ?: byteArrayOf()
            return ByteArrayInputStream(bytes)
        }
    }

    private class TestReceiveStore : ReceiveStore {
        val staged = mutableMapOf<StagingHandle, ByteArrayOutputStream>()
        val committed = mutableMapOf<CommittedFileHandle, Pair<ByteArray, Sha256Digest>>()

        override suspend fun hasAvailableSpace(requiredBytes: Long): Boolean = true
        override suspend fun prepareStaging(
            sessionId: SessionId,
            itemId: ItemId,
            expectedBytes: Long,
            displayName: String,
            mimeType: String
        ): StagingHandle {
            val handle = StagingHandle("staging-${itemId.value}")
            staged[handle] = ByteArrayOutputStream()
            return handle
        }
        override suspend fun openStagingOutput(handle: StagingHandle): OutputStream {
            return staged[handle] ?: ByteArrayOutputStream()
        }
        override suspend fun commitVerified(handle: StagingHandle, digest: Sha256Digest): CommittedFileHandle {
            val bytes = staged[handle]?.toByteArray() ?: byteArrayOf()
            val fileHandle = CommittedFileHandle("file-${handle.value}")
            committed[fileHandle] = bytes to digest
            return fileHandle
        }
        override suspend fun discardStaging(handle: StagingHandle) {
            staged.remove(handle)
        }
    }

    private class TestHistoryStore : HistoryStore {
        val recordedSessions = mutableListOf<SessionRecord>()
        override suspend fun recordSession(record: SessionRecord) {
            recordedSessions.add(record)
        }
        override suspend fun recordItem(record: ItemRecord) {}
        override suspend fun updateSessionOutcome(sessionId: SessionId, outcome: String, endedAtMs: Long) {}
        override suspend fun getSession(sessionId: SessionId): SessionRecord? = null
    }

    private class LoopbackPairTransport(
        val localPeerId: PeerId,
        var remotePeerId: PeerId? = null,
        var remoteTransport: LoopbackPairTransport? = null
    ) : TransferTransport {
        val controlFlow = MutableSharedFlow<ControlMessage>(extraBufferCapacity = 64)
        val incomingStreamFlow = MutableSharedFlow<IncomingStream>(extraBufferCapacity = 16)
        val bulkProgressFlow = MutableSharedFlow<BulkProgress>(extraBufferCapacity = 64)
        val preparedStreams = mutableMapOf<BulkRef, InputStreamProvider>()

        override suspend fun startDiscovery(serviceId: String): Flow<DiscoveryEvent> = emptyFlow()
        override suspend fun stopDiscovery() {}
        override suspend fun startAdvertising(serviceId: String, localDeviceName: String): Flow<AdvertisingEvent> = emptyFlow()
        override suspend fun stopAdvertising() {}
        override suspend fun initiateConnection(peerId: PeerId, localDeviceName: String) {}
        override suspend fun acceptConnection(peerId: PeerId) {}
        override suspend fun rejectConnection(peerId: PeerId) {}
        override fun observeConnectionEvents(): Flow<ConnectionEvent> = emptyFlow()
        override suspend fun sendControl(peerId: PeerId, payload: ByteArray) {
            remoteTransport?.controlFlow?.emit(ControlMessage(localPeerId, payload))
        }
        override fun observeControl(): Flow<ControlMessage> = controlFlow.asSharedFlow()
        override suspend fun prepareStream(streamSource: InputStreamProvider): BulkRef {
            val ref = BulkRef("bulk-${UUID.randomUUID()}")
            preparedStreams[ref] = streamSource
            return ref
        }
        override suspend fun sendStream(peerId: PeerId, bulkRef: BulkRef): Flow<BulkProgress> = flow {
            val provider = preparedStreams.remove(bulkRef) ?: error("No prepared stream for $bulkRef")
            val stream = provider.openStream()
            val rawBytes = stream.readAllBytes()
            remoteTransport?.incomingStreamFlow?.emit(IncomingStream(localPeerId, bulkRef, ByteArrayInputStream(rawBytes)))
            val total = rawBytes.size.toLong()
            emit(BulkProgress(bulkRef, total, false))
            remoteTransport?.bulkProgressFlow?.emit(BulkProgress(bulkRef, total, false))
            emit(BulkProgress(bulkRef, total, true))
            remoteTransport?.bulkProgressFlow?.emit(BulkProgress(bulkRef, total, true))
        }
        override fun observeIncomingStream(): Flow<IncomingStream> = incomingStreamFlow.asSharedFlow()
        override fun observeBulkProgress(): Flow<BulkProgress> = bulkProgressFlow.asSharedFlow()
        override suspend fun cancelPayload(bulkRef: BulkRef) {}
        override suspend fun disconnect(peerId: PeerId) {}
    }

    private fun createPair(): Pair<LoopbackPairTransport, LoopbackPairTransport> {
        val peerA = PeerId("peer-sender")
        val peerB = PeerId("peer-receiver")
        val tA = LoopbackPairTransport(peerA, peerB)
        val tB = LoopbackPairTransport(peerB, peerA)
        tA.remoteTransport = tB
        tB.remoteTransport = tA
        return tA to tB
    }

    @Test
    fun createSenderSession_createsConfiguredSenderCoordinator() = runBlocking {
        val sourceAccess = TestSourceAccess()
        val receiveStore = TestReceiveStore()
        val historyStore = TestHistoryStore()
        val factory = TransferSessionFactory(sourceAccess, receiveStore, historyStore)

        val (tA, _) = createPair()
        val sessionId = SessionId(UUID.randomUUID().toString())
        val itemId = ItemId(UUID.randomUUID().toString())
        val batch = TransferBatch(BatchId(UUID.randomUUID().toString()), listOf(TransferItem(itemId, "test.txt", "text/plain", 100L)))
        val handle = SourceHandle("content://test/1")

        val coordinator = factory.createSenderSession(
            sessionId = sessionId,
            peerId = PeerId("peer-receiver"),
            peerDisplayName = "Test Sender",
            senderBatch = batch,
            sourceMap = mapOf(itemId to handle),
            transport = tA,
            scope = this
        )

        assertEquals(sessionId, coordinator.sessionId)
        assertEquals(DeviceRole.Sender, coordinator.role)
        assertEquals(PeerId("peer-receiver"), coordinator.peerId)
        assertEquals("Test Sender", coordinator.localDisplayName)
    }

    @Test
    fun createReceiverSession_withoutSessionId_adoptsHelloSessionIdAndTransfersSuccessfully() = runBlocking {
        withTimeout(10000) {
            val sourceAccess = TestSourceAccess()
            val receiveStore = TestReceiveStore()
            val historyStore = TestHistoryStore()
            val factory = TransferSessionFactory(sourceAccess, receiveStore, historyStore)

            val (sTransport, rTransport) = createPair()
            val content = "Transferred via TransferSessionFactory!".toByteArray(Charsets.UTF_8)
            val handle = SourceHandle("content://media/factory_test.txt")
            val itemId = ItemId(UUID.randomUUID().toString())
            val batchId = BatchId(UUID.randomUUID().toString())
            val senderSessionId = SessionId(UUID.randomUUID().toString())

            sourceAccess.sources[handle] = content

            val batch = TransferBatch(
                id = batchId,
                items = listOf(TransferItem(itemId, "factory_test.txt", "text/plain", content.size.toLong()))
            )

            val sender = factory.createSenderSession(
                sessionId = senderSessionId,
                peerId = rTransport.localPeerId,
                peerDisplayName = "Sender Device",
                senderBatch = batch,
                sourceMap = mapOf(itemId to handle),
                transport = sTransport,
                scope = this
            )

            // Receiver created with sessionId = null
            val receiver = factory.createReceiverSession(
                peerId = sTransport.localPeerId,
                peerDisplayName = "Receiver Device",
                sessionId = null,
                transport = rTransport,
                scope = this
            )

            receiver.start()
            sender.start()

            while (receiver.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }

            // Receiver should have bound the sender's session ID
            assertEquals(senderSessionId, receiver.sessionId)
            assertEquals(senderSessionId, receiver.state.value.sessionId)

            receiver.acceptOffer()

            while (sender.state.value.phase != SessionPhase.Completed ||
                receiver.state.value.phase != SessionPhase.Completed) {
                kotlinx.coroutines.delay(20)
            }

            assertEquals(SessionPhase.Completed, sender.state.value.phase)
            assertEquals(SessionPhase.Completed, receiver.state.value.phase)
            assertEquals(1, receiver.committedFiles.size)

            val committed = receiveStore.committed[receiver.committedFiles[0]]
            assertNotNull(committed)
            assertEquals(String(content), String(committed!!.first))

            sender.stop()
            receiver.stop()
        }
    }
}
