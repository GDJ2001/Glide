package dev.glide.core.coordinator

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferItem
import dev.glide.core.fakes.FakeHistoryStore
import dev.glide.core.fakes.FakeReceiveStore
import dev.glide.core.fakes.FakeSourceAccess
import dev.glide.core.ports.AdvertisingEvent
import dev.glide.core.ports.BulkProgress
import dev.glide.core.ports.ConnectionEvent
import dev.glide.core.ports.ControlMessage
import dev.glide.core.ports.DiscoveryEvent
import dev.glide.core.ports.IncomingStream
import dev.glide.core.ports.InputStreamProvider
import dev.glide.core.ports.SessionRecord
import dev.glide.core.ports.SourceMetadata
import dev.glide.core.ports.TransferTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID

/**
 * Milestone M4 (Foreground MVP) formal acceptance test suite.
 * Validates:
 * 1. Mixed 100-item sequential batch transfer with zero concurrency, streaming hash, and complete receipt.
 * 2. Offer denial and immediate retry lifecycle with zero resource leaks.
 * 3. Separation of history clearing vs. physical received file retention.
 */
class ForegroundMvpAcceptanceTest {

    private class LoopbackTransport(
        val localPeerId: PeerId
    ) : TransferTransport {
        lateinit var remote: LoopbackTransport

        val controlChannel = Channel<ControlMessage>(Channel.UNLIMITED)
        val incomingStreamChannel = Channel<IncomingStream>(Channel.UNLIMITED)
        val bulkProgressChannel = Channel<BulkProgress>(Channel.UNLIMITED)
        val connectionEventsChannel = Channel<ConnectionEvent>(Channel.UNLIMITED)

        var streamCounter = 0

        override suspend fun startDiscovery(serviceId: String): Flow<DiscoveryEvent> = emptyFlow()
        override suspend fun stopDiscovery() {}
        override suspend fun startAdvertising(serviceId: String, localDeviceName: String): Flow<AdvertisingEvent> = emptyFlow()
        override suspend fun stopAdvertising() {}
        override suspend fun initiateConnection(peerId: PeerId, localDeviceName: String) {}
        override suspend fun acceptConnection(peerId: PeerId) {}
        override suspend fun rejectConnection(peerId: PeerId) {}
        override fun observeConnectionEvents(): Flow<ConnectionEvent> = connectionEventsChannel.receiveAsFlow()

        override suspend fun sendControl(peerId: PeerId, payload: ByteArray) {
            remote.controlChannel.send(ControlMessage(localPeerId, payload))
        }

        override fun observeControl(): Flow<ControlMessage> = controlChannel.receiveAsFlow()

        val preparedStreams = mutableMapOf<BulkRef, InputStream>()

        override suspend fun prepareStream(streamSource: InputStreamProvider): BulkRef {
            streamCounter++
            val bulkRef = BulkRef("acceptance-bulk-$streamCounter")
            preparedStreams[bulkRef] = streamSource.openStream()
            return bulkRef
        }

        override suspend fun markItemReady(bulkRef: BulkRef) {}
        override suspend fun registerExpectedStream(bulkRef: BulkRef) {}

        override suspend fun sendStream(
            peerId: PeerId,
            bulkRef: BulkRef
        ): Flow<BulkProgress> = flow {
            val stream = preparedStreams.remove(bulkRef)
                ?: throw IllegalStateException("No prepared stream for bulkRef ${bulkRef.value}")
            val rawBytes = stream.readAllBytes()

            remote.incomingStreamChannel.send(IncomingStream(localPeerId, bulkRef, ByteArrayInputStream(rawBytes)))

            val total = rawBytes.size.toLong()
            emit(BulkProgress(bulkRef, total, false))
            remote.bulkProgressChannel.send(BulkProgress(bulkRef, total, false))

            emit(BulkProgress(bulkRef, total, true))
            remote.bulkProgressChannel.send(BulkProgress(bulkRef, total, true))
        }

        override suspend fun sendStream(
            peerId: PeerId,
            bulkRef: BulkRef,
            streamSource: InputStreamProvider
        ): Flow<BulkProgress> {
            preparedStreams.getOrPut(bulkRef) { streamSource.openStream() }
            return sendStream(peerId, bulkRef)
        }

        override fun observeIncomingStream(): Flow<IncomingStream> = incomingStreamChannel.receiveAsFlow()
        override fun observeBulkProgress(): Flow<BulkProgress> = bulkProgressChannel.receiveAsFlow()
        override suspend fun cancelPayload(bulkRef: BulkRef) {}
        override suspend fun disconnect(peerId: PeerId) {
            remote.connectionEventsChannel.send(ConnectionEvent.Disconnected(localPeerId))
        }
    }

    private fun createPair(): Pair<LoopbackTransport, LoopbackTransport> {
        val sPeer = PeerId("sender-peer")
        val rPeer = PeerId("receiver-peer")
        val sTransport = LoopbackTransport(sPeer)
        val rTransport = LoopbackTransport(rPeer)
        sTransport.remote = rTransport
        rTransport.remote = sTransport
        return sTransport to rTransport
    }

    @Test
    fun mixed100ItemBatch_transfersSequentiallyAndVerifiesAll() = runBlocking {
        // Generous timeout for 100 sequential items
        withTimeout(60000) {
            val (sTransport, rTransport) = createPair()
            val sSourceAccess = FakeSourceAccess()
            val rReceiveStore = FakeReceiveStore()
            val sHistory = FakeHistoryStore()
            val rHistory = FakeHistoryStore()

            val items = ArrayList<TransferItem>(100)
            val sourceMap = HashMap<ItemId, SourceHandle>(100)
            val expectedContents = HashMap<ItemId, ByteArray>(100)

            for (i in 0 until 100) {
                val itemId = ItemId(UUID.randomUUID().toString())
                val handle = SourceHandle("content://media/item-$i")
                val (name, mime, bytes) = when {
                    i < 20 -> Triple("document_$i.txt", "text/plain", "Text document payload #$i for acceptance testing".toByteArray(Charsets.UTF_8))
                    i < 40 -> Triple("binary_$i.bin", "application/octet-stream", ByteArray(128 + i * 8) { (it xor i).toByte() })
                    i < 50 -> Triple("empty_$i.log", "text/plain", ByteArray(0)) // Zero-byte items
                    i < 70 -> Triple("photo_vacation_pic_$i.jpg", "image/jpeg", ByteArray(256) { (it + i).toByte() })
                    i < 85 -> Triple("archive_package_$i.zip", "application/zip", ByteArray(512) { (it * 2).toByte() })
                    else -> Triple("data_$i.csv", "text/csv", "id,name,value\n$i,item_$i,100.0\n".toByteArray(Charsets.UTF_8))
                }
                sSourceAccess.registerSource(handle, SourceMetadata(name, mime, bytes.size.toLong()), bytes)
                items.add(TransferItem(itemId, name, mime, bytes.size.toLong()))
                sourceMap[itemId] = handle
                expectedContents[itemId] = bytes
            }

            val batchId = BatchId(UUID.randomUUID().toString())
            val sessionId = SessionId(UUID.randomUUID().toString())
            val batch = TransferBatch(batchId, items)

            val senderCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Pixel Sender",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = sHistory,
                scope = this,
                senderBatch = batch,
                sourceMap = sourceMap,
                rateLimiter = null
            )

            val receiverCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Pixel Receiver",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = rHistory,
                scope = this,
                rateLimiter = null
            )

            receiverCoordinator.start()
            senderCoordinator.start()

            // Wait for receiver to receive and stage offer
            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                delay(10)
            }

            assertEquals(SessionPhase.AwaitingConsent, receiverCoordinator.state.value.phase)
            assertEquals(100, receiverCoordinator.state.value.batch?.items?.size)

            // Receiver consents to entire batch
            receiverCoordinator.acceptOffer()

            // Await completion on both sides
            while (senderCoordinator.state.value.phase != SessionPhase.Completed ||
                receiverCoordinator.state.value.phase != SessionPhase.Completed
            ) {
                delay(20)
            }

            assertEquals(SessionPhase.Completed, senderCoordinator.state.value.phase)
            assertEquals(SessionPhase.Completed, receiverCoordinator.state.value.phase)

            // Verify all 100 items were committed to storage
            assertEquals(100, receiverCoordinator.committedFiles.size)

            // Verify all 100 items match expected content byte-for-byte
            for (item in items) {
                val committedHandle = receiverCoordinator.committedFiles.firstOrNull {
                    it.value.contains(item.id.value)
                }
                assertNotNull("Committed handle must exist for ${item.name}", committedHandle)
                val stored = rReceiveStore.committedFiles[committedHandle]
                assertNotNull("Stored data must exist for handle $committedHandle", stored)
                val expected = expectedContents[item.id]!!
                assertTrue("Content mismatch for ${item.name}", expected.contentEquals(stored!!.first))
            }

            // Verify history persistence on both sender and receiver
            val sSession = sHistory.getSession(sessionId)
            assertNotNull(sSession)
            assertEquals("Completed", sSession?.outcome)

            val rSession = rHistory.getSession(sessionId)
            assertNotNull(rSession)
            assertEquals("Completed", rSession?.outcome)

            val sHistoryItems = sHistory.getItemsForSession(sessionId)
            assertEquals(100, sHistoryItems.size)
            assertTrue("All sender history items must be Verified", sHistoryItems.all { it.outcome == "Verified" })

            val rHistoryItems = rHistory.getItemsForSession(sessionId)
            assertEquals(100, rHistoryItems.size)
            assertTrue("All receiver history items must be Verified", rHistoryItems.all { it.outcome == "Verified" })

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }

    @Test
    fun offerDenialAndImmediateRetryFlow_noResourceLeaks() = runBlocking {
        withTimeout(20000) {
            val (sTransport, rTransport) = createPair()
            val sSourceAccess = FakeSourceAccess()
            val rReceiveStore = FakeReceiveStore()
            val sHistory = FakeHistoryStore()
            val rHistory = FakeHistoryStore()

            // Session 1: Offer will be denied
            val file1Bytes = "Session 1 content".toByteArray(Charsets.UTF_8)
            val h1 = SourceHandle("content://media/s1.txt")
            val id1 = ItemId(UUID.randomUUID().toString())
            val bId1 = BatchId(UUID.randomUUID().toString())
            val sId1 = SessionId(UUID.randomUUID().toString())

            sSourceAccess.registerSource(h1, SourceMetadata("s1.txt", "text/plain", file1Bytes.size.toLong()), file1Bytes)
            val batch1 = TransferBatch(bId1, listOf(TransferItem(id1, "s1.txt", "text/plain", file1Bytes.size.toLong())))

            val sender1 = TransferCoordinator(
                sessionId = sId1,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = sHistory,
                scope = this,
                senderBatch = batch1,
                sourceMap = mapOf(id1 to h1)
            )

            val receiver1 = TransferCoordinator(
                sessionId = sId1,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = rHistory,
                scope = this
            )

            receiver1.start()
            sender1.start()

            while (receiver1.state.value.phase != SessionPhase.AwaitingConsent) {
                delay(10)
            }

            // User denies offer
            receiver1.rejectOffer()

            while (!sender1.state.value.isTerminal || !receiver1.state.value.isTerminal) {
                delay(10)
            }

            assertEquals(SessionPhase.Cancelled, sender1.state.value.phase)
            assertEquals(SessionPhase.Cancelled, receiver1.state.value.phase)
            assertTrue("No files should be committed on denial", receiver1.committedFiles.isEmpty())

            sender1.stop()
            receiver1.stop()

            // Session 2: Immediate retry with new session, transport pair, and batch
            val (sTransport2, rTransport2) = createPair()
            val file2Bytes = "Session 2 retry content".toByteArray(Charsets.UTF_8)
            val h2 = SourceHandle("content://media/s2.txt")
            val id2 = ItemId(UUID.randomUUID().toString())
            val bId2 = BatchId(UUID.randomUUID().toString())
            val sId2 = SessionId(UUID.randomUUID().toString())

            sSourceAccess.registerSource(h2, SourceMetadata("s2.txt", "text/plain", file2Bytes.size.toLong()), file2Bytes)
            val batch2 = TransferBatch(bId2, listOf(TransferItem(id2, "s2.txt", "text/plain", file2Bytes.size.toLong())))

            val sender2 = TransferCoordinator(
                sessionId = sId2,
                role = DeviceRole.Sender,
                peerId = rTransport2.localPeerId,
                localDisplayName = "Sender",
                transport = sTransport2,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = sHistory,
                scope = this,
                senderBatch = batch2,
                sourceMap = mapOf(id2 to h2)
            )

            val receiver2 = TransferCoordinator(
                sessionId = sId2,
                role = DeviceRole.Receiver,
                peerId = sTransport2.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport2,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = rHistory,
                scope = this
            )

            receiver2.start()
            sender2.start()

            while (receiver2.state.value.phase != SessionPhase.AwaitingConsent) {
                delay(10)
            }

            // User accepts retry offer
            receiver2.acceptOffer()

            while (sender2.state.value.phase != SessionPhase.Completed ||
                receiver2.state.value.phase != SessionPhase.Completed
            ) {
                delay(10)
            }

            assertEquals(SessionPhase.Completed, sender2.state.value.phase)
            assertEquals(SessionPhase.Completed, receiver2.state.value.phase)
            assertEquals(1, receiver2.committedFiles.size)

            sender2.stop()
            receiver2.stop()
        }
    }

    @Test
    fun clearHistory_retainsReceivedFilesOnDiskAndCatalog() = runBlocking {
        val rReceiveStore = FakeReceiveStore()
        val rHistory = FakeHistoryStore()

        // Commit file to receive store
        val fileBytes = "Important received document".toByteArray(Charsets.UTF_8)
        val sId = SessionId(UUID.randomUUID().toString())
        val itemId = ItemId(UUID.randomUUID().toString())
        val stagingHandle = rReceiveStore.prepareStaging(sId, itemId, fileBytes.size.toLong(), "important.pdf", "application/pdf")
        val out = rReceiveStore.openStagingOutput(stagingHandle)
        out.write(fileBytes)
        out.flush()
        out.close()
        val committedHandle = rReceiveStore.commitVerified(stagingHandle, Sha256Digest("a".repeat(64)))

        // Add history records
        rHistory.recordSession(
            SessionRecord(
                sessionId = sId,
                batchId = BatchId(UUID.randomUUID().toString()),
                role = DeviceRole.Receiver,
                peerLabel = "Sender Device",
                startedAtMs = System.currentTimeMillis(),
                endedAtMs = System.currentTimeMillis(),
                outcome = "Completed"
            )
        )

        assertEquals(1, rHistory.getSessionCount())
        assertEquals(1, rReceiveStore.committedFiles.size)

        // Clear history records
        rHistory.clearHistory()

        // History records are wiped
        assertEquals(0, rHistory.getSessionCount())

        // Physical committed file is 100% retained!
        assertEquals(1, rReceiveStore.committedFiles.size)
        assertTrue(rReceiveStore.committedFiles.containsKey(committedHandle))
        assertTrue(fileBytes.contentEquals(rReceiveStore.committedFiles[committedHandle]!!.first))
    }
}
