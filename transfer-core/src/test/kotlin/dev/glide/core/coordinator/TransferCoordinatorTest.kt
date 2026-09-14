package dev.glide.core.coordinator

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferFailure
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
import dev.glide.core.ports.SourceMetadata
import dev.glide.core.ports.TransferTransport
import dev.glide.core.stream.StreamHasher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.UUID

class TransferCoordinatorTest {

    private class TestLoopbackTransport(
        val localPeerId: PeerId
    ) : TransferTransport {
        lateinit var remote: TestLoopbackTransport

        val controlChannel = Channel<ControlMessage>(Channel.UNLIMITED)
        val incomingStreamChannel = Channel<IncomingStream>(Channel.UNLIMITED)
        val bulkProgressChannel = Channel<BulkProgress>(Channel.UNLIMITED)
        val connectionEventsChannel = Channel<ConnectionEvent>(Channel.UNLIMITED)

        val cancelledPayloads = mutableListOf<BulkRef>()
        val registeredExpectedStreams = mutableSetOf<BulkRef>()
        val markedReadyStreams = mutableSetOf<BulkRef>()
        var injectCorruption: Boolean = false
        var corruptItemIndex: Int? = null
        var currentStreamSendCount: Int = 0
        var dropControlMessageType: String? = null
        var streamCounter: Int = 0
        var sendStreamDelayMs: Long = 0L

        override suspend fun startDiscovery(serviceId: String): Flow<DiscoveryEvent> = emptyFlow()
        override suspend fun stopDiscovery() {}
        override suspend fun startAdvertising(serviceId: String, localDeviceName: String): Flow<AdvertisingEvent> = emptyFlow()
        override suspend fun stopAdvertising() {}
        override suspend fun initiateConnection(peerId: PeerId, localDeviceName: String) {}
        override suspend fun acceptConnection(peerId: PeerId) {}
        override suspend fun rejectConnection(peerId: PeerId) {}
        override fun observeConnectionEvents(): Flow<ConnectionEvent> = connectionEventsChannel.receiveAsFlow()

        override suspend fun sendControl(peerId: PeerId, payload: ByteArray) {
            val env = try { dev.glide.core.protocol.ProtocolCodec.decode(payload) } catch (_: Exception) { null }
            if (dropControlMessageType != null && env?.type == dropControlMessageType) {
                return
            }
            remote.controlChannel.send(ControlMessage(localPeerId, payload))
        }

        override fun observeControl(): Flow<ControlMessage> = controlChannel.receiveAsFlow()

        override suspend fun prepareStream(streamSource: InputStreamProvider): BulkRef {
            streamCounter++
            return BulkRef("loopback-bulk-$streamCounter")
        }

        override suspend fun markItemReady(bulkRef: BulkRef) {
            markedReadyStreams.add(bulkRef)
        }

        override suspend fun registerExpectedStream(bulkRef: BulkRef) {
            registeredExpectedStreams.add(bulkRef)
        }

        override suspend fun sendStream(
            peerId: PeerId,
            bulkRef: BulkRef,
            streamSource: InputStreamProvider
        ): Flow<BulkProgress> = flow {
            if (sendStreamDelayMs > 0) {
                kotlinx.coroutines.delay(sendStreamDelayMs)
            }
            currentStreamSendCount++
            val shouldCorrupt = injectCorruption || (corruptItemIndex != null && currentStreamSendCount == corruptItemIndex)
            val stream = streamSource.openStream()
            val rawBytes = stream.readAllBytes()

            val bytesToSend = if (shouldCorrupt) {
                val corrupted = rawBytes.copyOf()
                if (corrupted.isNotEmpty()) {
                    corrupted[0] = (corrupted[0] + 1).toByte()
                } else {
                    byteArrayOf(0x01)
                }
                corrupted
            } else {
                rawBytes
            }

            // Emit to remote incoming stream
            remote.incomingStreamChannel.send(IncomingStream(localPeerId, bulkRef, ByteArrayInputStream(bytesToSend)))

            val total = rawBytes.size.toLong()
            emit(BulkProgress(bulkRef, total, false))
            remote.bulkProgressChannel.send(BulkProgress(bulkRef, total, false))

            emit(BulkProgress(bulkRef, total, true))
            remote.bulkProgressChannel.send(BulkProgress(bulkRef, total, true))
        }

        override fun observeIncomingStream(): Flow<IncomingStream> = incomingStreamChannel.receiveAsFlow()

        override fun observeBulkProgress(): Flow<BulkProgress> = bulkProgressChannel.receiveAsFlow()

        override suspend fun cancelPayload(bulkRef: BulkRef) {
            cancelledPayloads.add(bulkRef)
        }

        override suspend fun disconnect(peerId: PeerId) {
            remote.connectionEventsChannel.send(ConnectionEvent.Disconnected(localPeerId))
        }
    }

    private fun createPair(): Pair<TestLoopbackTransport, TestLoopbackTransport> {
        val sPeer = PeerId("sender-peer")
        val rPeer = PeerId("receiver-peer")
        val sTransport = TestLoopbackTransport(sPeer)
        val rTransport = TestLoopbackTransport(rPeer)
        sTransport.remote = rTransport
        rTransport.remote = sTransport
        return sTransport to rTransport
    }

    @Test
    fun twoDeviceHappyPath_oneFileTransfersAndCommitsSuccessfully() = runBlocking {
        withTimeout(10000) {
            val (sTransport, rTransport) = createPair()
            val sSourceAccess = FakeSourceAccess()
            val rReceiveStore = FakeReceiveStore()

            val fileContent = "Hello Glide P2P Transfer World!".toByteArray(Charsets.UTF_8)
            val sourceHandle = SourceHandle("content://media/test.txt")
            val itemId = ItemId(UUID.randomUUID().toString())
            val batchId = BatchId(UUID.randomUUID().toString())
            val sessionId = SessionId(UUID.randomUUID().toString())

            sSourceAccess.registerSource(
                handle = sourceHandle,
                metadata = SourceMetadata("test.txt", "text/plain", fileContent.size.toLong()),
                content = fileContent
            )

            val testItem = TransferItem(
                id = itemId,
                name = "test.txt",
                mimeType = "text/plain",
                sizeBytes = fileContent.size.toLong()
            )
            val batch = TransferBatch(id = batchId, items = listOf(testItem))

            val sHistory = FakeHistoryStore()
            val rHistory = FakeHistoryStore()

            val senderCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender Device",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = sHistory,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(itemId to sourceHandle)
            )

            val receiverCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver Device",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = rHistory,
                scope = this
            )

            // Start both
            receiverCoordinator.start()
            senderCoordinator.start()

            // Wait until receiver is awaiting consent
            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }

            assertEquals(SessionPhase.AwaitingConsent, receiverCoordinator.state.value.phase)
            assertEquals("test.txt", receiverCoordinator.state.value.batch?.items?.first()?.name)

            // Receiver user accepts offer
            receiverCoordinator.acceptOffer()

            // Wait until both complete
            while (senderCoordinator.state.value.phase != SessionPhase.Completed ||
                receiverCoordinator.state.value.phase != SessionPhase.Completed) {
                kotlinx.coroutines.delay(20)
            }

            assertEquals(SessionPhase.Completed, senderCoordinator.state.value.phase)
            assertEquals(SessionPhase.Completed, receiverCoordinator.state.value.phase)

            // Verify receiver committed content matches
            val committed = receiverCoordinator.lastCommittedFileHandle
            assertNotNull(committed)
            val committedData = rReceiveStore.committedFiles[committed]
            assertNotNull(committedData)
            assertEquals(String(fileContent), String(committedData!!.first))

            // Verify history was recorded with terminal outcomes
            val sSessionRecord = sHistory.getSession(sessionId)
            assertNotNull(sSessionRecord)
            assertEquals("Completed", sSessionRecord?.outcome)

            val rSessionRecord = rHistory.getSession(sessionId)
            assertNotNull(rSessionRecord)
            assertEquals("Completed", rSessionRecord?.outcome)

            val sItems = sHistory.getItemsForSession(sessionId)
            assertEquals(1, sItems.size)
            assertEquals("Verified", sItems.first().outcome)

            val rItems = rHistory.getItemsForSession(sessionId)
            assertEquals(1, rItems.size)
            assertEquals("Verified", rItems.first().outcome)

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }

    @Test
    fun offerRejection_terminatesBothSidesCleanlyWithoutDataTransfer() = runBlocking {
        withTimeout(10000) {
            val (sTransport, rTransport) = createPair()
            val sSourceAccess = FakeSourceAccess()
            val rReceiveStore = FakeReceiveStore()

            val fileContent = "Secret data".toByteArray(Charsets.UTF_8)
            val sourceHandle = SourceHandle("content://media/secret.txt")
            val itemId = ItemId(UUID.randomUUID().toString())
            val batchId = BatchId(UUID.randomUUID().toString())
            val sessionId = SessionId(UUID.randomUUID().toString())

            sSourceAccess.registerSource(
                handle = sourceHandle,
                metadata = SourceMetadata("secret.txt", "text/plain", fileContent.size.toLong()),
                content = fileContent
            )

            val batch = TransferBatch(
                id = batchId,
                items = listOf(TransferItem(itemId, "secret.txt", "text/plain", fileContent.size.toLong()))
            )

            val sHistory = FakeHistoryStore()
            val rHistory = FakeHistoryStore()

            val senderCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender Device",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = sHistory,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(itemId to sourceHandle)
            )

            val receiverCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver Device",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = rHistory,
                scope = this
            )

            receiverCoordinator.start()
            senderCoordinator.start()

            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }

            // User rejects offer
            receiverCoordinator.rejectOffer()

            while (senderCoordinator.state.value.phase != SessionPhase.Cancelled ||
                receiverCoordinator.state.value.phase != SessionPhase.Cancelled) {
                kotlinx.coroutines.delay(20)
            }

            assertEquals(SessionPhase.Cancelled, receiverCoordinator.state.value.phase)
            assertEquals(SessionPhase.Cancelled, senderCoordinator.state.value.phase)
            assertTrue(rReceiveStore.committedFiles.isEmpty())

            val sSessionRecord = sHistory.getSession(sessionId)
            assertNotNull(sSessionRecord)
            assertEquals("Cancelled", sSessionRecord?.outcome)

            val rSessionRecord = rHistory.getSession(sessionId)
            assertNotNull(rSessionRecord)
            assertEquals("Cancelled", rSessionRecord?.outcome)

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }

    @Test
    fun userCancel_terminatesTransferAndDiscardsStaging() = runBlocking {
        withTimeout(10000) {
            val (sTransport, rTransport) = createPair()
            val sSourceAccess = FakeSourceAccess()
            val rReceiveStore = FakeReceiveStore()

            val fileContent = "Cancel me".toByteArray(Charsets.UTF_8)
            val sourceHandle = SourceHandle("content://media/cancel.txt")
            val itemId = ItemId(UUID.randomUUID().toString())
            val batchId = BatchId(UUID.randomUUID().toString())
            val sessionId = SessionId(UUID.randomUUID().toString())

            sSourceAccess.registerSource(
                handle = sourceHandle,
                metadata = SourceMetadata("cancel.txt", "text/plain", fileContent.size.toLong()),
                content = fileContent
            )

            val batch = TransferBatch(
                id = batchId,
                items = listOf(TransferItem(itemId, "cancel.txt", "text/plain", fileContent.size.toLong()))
            )

            val sHistory = FakeHistoryStore()
            val rHistory = FakeHistoryStore()

            val senderCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender Device",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = sHistory,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(itemId to sourceHandle)
            )

            val receiverCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver Device",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = rHistory,
                scope = this
            )

            receiverCoordinator.start()
            senderCoordinator.start()

            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }

            // Sender cancels before accepting
            senderCoordinator.cancel()

            while (senderCoordinator.state.value.phase != SessionPhase.Cancelled ||
                receiverCoordinator.state.value.phase != SessionPhase.Cancelled) {
                kotlinx.coroutines.delay(20)
            }

            assertEquals(SessionPhase.Cancelled, senderCoordinator.state.value.phase)
            assertEquals(SessionPhase.Cancelled, receiverCoordinator.state.value.phase)
            assertTrue(rReceiveStore.committedFiles.isEmpty())

            val sSessionRecord = sHistory.getSession(sessionId)
            assertNotNull(sSessionRecord)
            assertEquals("Cancelled", sSessionRecord?.outcome)

            val rSessionRecord = rHistory.getSession(sessionId)
            assertNotNull(rSessionRecord)
            assertEquals("Cancelled", rSessionRecord?.outcome)

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }

    @Test
    fun integrityMismatch_rejectsAndDiscardsUnverifiedContent() = runBlocking {
        withTimeout(10000) {
            val (sTransport, rTransport) = createPair()
            sTransport.injectCorruption = true // Corrupt stream bytes!

            val sSourceAccess = FakeSourceAccess()
            val rReceiveStore = FakeReceiveStore()

            val fileContent = "Original authentic data".toByteArray(Charsets.UTF_8)
            val sourceHandle = SourceHandle("content://media/tampered.txt")
            val itemId = ItemId(UUID.randomUUID().toString())
            val batchId = BatchId(UUID.randomUUID().toString())
            val sessionId = SessionId(UUID.randomUUID().toString())

            sSourceAccess.registerSource(
                handle = sourceHandle,
                metadata = SourceMetadata("tampered.txt", "text/plain", fileContent.size.toLong()),
                content = fileContent
            )

            val batch = TransferBatch(
                id = batchId,
                items = listOf(TransferItem(itemId, "tampered.txt", "text/plain", fileContent.size.toLong()))
            )

            val sHistory = FakeHistoryStore()
            val rHistory = FakeHistoryStore()

            val senderCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender Device",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = sHistory,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(itemId to sourceHandle)
            )

            val receiverCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver Device",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = rHistory,
                scope = this
            )

            receiverCoordinator.start()
            senderCoordinator.start()

            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }

            receiverCoordinator.acceptOffer()

            // Corrupted bytes will cause receiver to reject with INTEGRITY_MISMATCH
            while (receiverCoordinator.state.value.phase != SessionPhase.Failed ||
                senderCoordinator.state.value.phase != SessionPhase.Failed) {
                kotlinx.coroutines.delay(20)
            }

            assertEquals(SessionPhase.Failed, receiverCoordinator.state.value.phase)
            assertEquals(SessionPhase.Failed, senderCoordinator.state.value.phase)
            assertTrue(rReceiveStore.committedFiles.isEmpty())
            assertTrue(rReceiveStore.discardedHandles.isNotEmpty())

            val sSessionRecord = sHistory.getSession(sessionId)
            assertNotNull(sSessionRecord)
            assertEquals("Failed", sSessionRecord?.outcome)

            val rSessionRecord = rHistory.getSession(sessionId)
            assertNotNull(rSessionRecord)
            assertEquals("Failed", rSessionRecord?.outcome)

            val rItems = rHistory.getItemsForSession(sessionId)
            assertEquals(1, rItems.size)
            assertEquals("Failed", rItems.first().outcome)

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }

    @Test
    fun sequentialBatch_happyPath_mixedFiles_transfersAllPreservingOrderAndOutcomes() = runBlocking {
        withTimeout(15000) {
            val (sTransport, rTransport) = createPair()
            val sSourceAccess = FakeSourceAccess()
            val rReceiveStore = FakeReceiveStore()

            val f1Bytes = "Item 1: Plain text payload".toByteArray(Charsets.UTF_8)
            val f2Bytes = ByteArray(1024) { (it % 128).toByte() }
            val f3Bytes = ByteArray(0) // Zero-byte file

            val h1 = SourceHandle("content://media/1.txt")
            val h2 = SourceHandle("content://media/2.bin")
            val h3 = SourceHandle("content://media/3.empty")

            val id1 = ItemId(UUID.randomUUID().toString())
            val id2 = ItemId(UUID.randomUUID().toString())
            val id3 = ItemId(UUID.randomUUID().toString())

            sSourceAccess.registerSource(h1, SourceMetadata("file1.txt", "text/plain", f1Bytes.size.toLong()), f1Bytes)
            sSourceAccess.registerSource(h2, SourceMetadata("file2.bin", "application/octet-stream", f2Bytes.size.toLong()), f2Bytes)
            sSourceAccess.registerSource(h3, SourceMetadata("file3.empty", "application/octet-stream", 0L), f3Bytes)

            val batchId = BatchId(UUID.randomUUID().toString())
            val sessionId = SessionId(UUID.randomUUID().toString())

            val batch = TransferBatch(
                id = batchId,
                items = listOf(
                    TransferItem(id1, "file1.txt", "text/plain", f1Bytes.size.toLong()),
                    TransferItem(id2, "file2.bin", "application/octet-stream", f2Bytes.size.toLong()),
                    TransferItem(id3, "file3.empty", "application/octet-stream", 0L)
                )
            )

            val sHistory = FakeHistoryStore()
            val rHistory = FakeHistoryStore()

            val senderCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender Device",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = sHistory,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(id1 to h1, id2 to h2, id3 to h3)
            )

            val receiverCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver Device",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = rHistory,
                scope = this
            )

            receiverCoordinator.start()
            senderCoordinator.start()

            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }

            assertEquals(3, receiverCoordinator.state.value.batch?.items?.size)
            receiverCoordinator.acceptOffer()

            while (senderCoordinator.state.value.phase != SessionPhase.Completed ||
                receiverCoordinator.state.value.phase != SessionPhase.Completed) {
                kotlinx.coroutines.delay(20)
            }

            assertEquals(SessionPhase.Completed, senderCoordinator.state.value.phase)
            assertEquals(SessionPhase.Completed, receiverCoordinator.state.value.phase)

            // Verify receiver committed all 3 files
            assertEquals(3, receiverCoordinator.committedFiles.size)
            val c1 = rReceiveStore.committedFiles[receiverCoordinator.committedFiles[0]]
            val c2 = rReceiveStore.committedFiles[receiverCoordinator.committedFiles[1]]
            val c3 = rReceiveStore.committedFiles[receiverCoordinator.committedFiles[2]]

            assertNotNull(c1)
            assertNotNull(c2)
            assertNotNull(c3)

            assertTrue(f1Bytes.contentEquals(c1!!.first))
            assertTrue(f2Bytes.contentEquals(c2!!.first))
            assertTrue(f3Bytes.contentEquals(c3!!.first))

            // History verification
            val sItems = sHistory.getItemsForSession(sessionId)
            val rItems = rHistory.getItemsForSession(sessionId)
            assertEquals(3, sItems.size)
            assertEquals(3, rItems.size)
            assertTrue(sItems.all { it.outcome == "Verified" })
            assertTrue(rItems.all { it.outcome == "Verified" })

            assertEquals("Completed", sHistory.getSession(sessionId)?.outcome)
            assertEquals("Completed", rHistory.getSession(sessionId)?.outcome)

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }

    @Test
    fun sequentialBatch_failMiddleItem_retainsEarlierCommittedFilesAndMarksLaterNotAttempted() = runBlocking {
        withTimeout(15000) {
            val (sTransport, rTransport) = createPair()
            sTransport.corruptItemIndex = 2 // Corrupt item 2!

            val sSourceAccess = FakeSourceAccess()
            val rReceiveStore = FakeReceiveStore()

            val f1Bytes = "File 1 authentic".toByteArray(Charsets.UTF_8)
            val f2Bytes = "File 2 corrupt me".toByteArray(Charsets.UTF_8)
            val f3Bytes = "File 3 unreached".toByteArray(Charsets.UTF_8)

            val h1 = SourceHandle("content://media/1.txt")
            val h2 = SourceHandle("content://media/2.txt")
            val h3 = SourceHandle("content://media/3.txt")

            val id1 = ItemId(UUID.randomUUID().toString())
            val id2 = ItemId(UUID.randomUUID().toString())
            val id3 = ItemId(UUID.randomUUID().toString())

            sSourceAccess.registerSource(h1, SourceMetadata("file1.txt", "text/plain", f1Bytes.size.toLong()), f1Bytes)
            sSourceAccess.registerSource(h2, SourceMetadata("file2.txt", "text/plain", f2Bytes.size.toLong()), f2Bytes)
            sSourceAccess.registerSource(h3, SourceMetadata("file3.txt", "text/plain", f3Bytes.size.toLong()), f3Bytes)

            val batchId = BatchId(UUID.randomUUID().toString())
            val sessionId = SessionId(UUID.randomUUID().toString())

            val batch = TransferBatch(
                id = batchId,
                items = listOf(
                    TransferItem(id1, "file1.txt", "text/plain", f1Bytes.size.toLong()),
                    TransferItem(id2, "file2.txt", "text/plain", f2Bytes.size.toLong()),
                    TransferItem(id3, "file3.txt", "text/plain", f3Bytes.size.toLong())
                )
            )

            val sHistory = FakeHistoryStore()
            val rHistory = FakeHistoryStore()

            val senderCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender Device",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = sHistory,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(id1 to h1, id2 to h2, id3 to h3)
            )

            val receiverCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver Device",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = rHistory,
                scope = this
            )

            receiverCoordinator.start()
            senderCoordinator.start()

            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }
            receiverCoordinator.acceptOffer()

            // Wait until both coordinators fail due to item 2 mismatch
            while (senderCoordinator.state.value.phase != SessionPhase.Failed ||
                receiverCoordinator.state.value.phase != SessionPhase.Failed) {
                kotlinx.coroutines.delay(20)
            }

            assertEquals(SessionPhase.Failed, senderCoordinator.state.value.phase)
            assertEquals(SessionPhase.Failed, receiverCoordinator.state.value.phase)

            // Acceptance criterion: File 1 was verified and remains committed!
            assertEquals(1, receiverCoordinator.committedFiles.size)
            val c1 = rReceiveStore.committedFiles[receiverCoordinator.committedFiles[0]]
            assertNotNull(c1)
            assertTrue(f1Bytes.contentEquals(c1!!.first))

            // Item 2 staging was discarded
            assertTrue(rReceiveStore.discardedHandles.isNotEmpty())

            // State check
            assertEquals(ItemPhase.Verified, receiverCoordinator.state.value.itemStates[id1]?.phase)
            assertEquals(ItemPhase.Failed, receiverCoordinator.state.value.itemStates[id2]?.phase)
            assertEquals(ItemPhase.NotAttempted, receiverCoordinator.state.value.itemStates[id3]?.phase)

            assertEquals(ItemPhase.Verified, senderCoordinator.state.value.itemStates[id1]?.phase)
            assertEquals(ItemPhase.Failed, senderCoordinator.state.value.itemStates[id2]?.phase)
            assertEquals(ItemPhase.NotAttempted, senderCoordinator.state.value.itemStates[id3]?.phase)

            // History check
            val rItems = rHistory.getItemsForSession(sessionId).associateBy { it.itemId }
            assertEquals("Verified", rItems[id1]?.outcome)
            assertEquals("Failed", rItems[id2]?.outcome)
            assertEquals("NotAttempted", rItems[id3]?.outcome)

            val sItems = sHistory.getItemsForSession(sessionId).associateBy { it.itemId }
            assertEquals("Verified", sItems[id1]?.outcome)
            assertEquals("Failed", sItems[id2]?.outcome)
            assertEquals("NotAttempted", sItems[id3]?.outcome)

            assertEquals("Failed", rHistory.getSession(sessionId)?.outcome)
            assertEquals("Failed", sHistory.getSession(sessionId)?.outcome)

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }

    @Test
    fun sequentialBatch_cancelBeforeNextItem_preservesEarlierCommittedFiles() = runBlocking {
        withTimeout(15000) {
            val (sTransport, rTransport) = createPair()
            sTransport.sendStreamDelayMs = 200L
            val sSourceAccess = FakeSourceAccess()
            val rReceiveStore = FakeReceiveStore()

            val f1Bytes = "File 1 before cancel".toByteArray(Charsets.UTF_8)
            val f2Bytes = "File 2 target of cancel".toByteArray(Charsets.UTF_8)
            val f3Bytes = "File 3 unreached".toByteArray(Charsets.UTF_8)

            val h1 = SourceHandle("content://media/1.txt")
            val h2 = SourceHandle("content://media/2.txt")
            val h3 = SourceHandle("content://media/3.txt")

            val id1 = ItemId(UUID.randomUUID().toString())
            val id2 = ItemId(UUID.randomUUID().toString())
            val id3 = ItemId(UUID.randomUUID().toString())

            sSourceAccess.registerSource(h1, SourceMetadata("file1.txt", "text/plain", f1Bytes.size.toLong()), f1Bytes)
            sSourceAccess.registerSource(h2, SourceMetadata("file2.txt", "text/plain", f2Bytes.size.toLong()), f2Bytes)
            sSourceAccess.registerSource(h3, SourceMetadata("file3.txt", "text/plain", f3Bytes.size.toLong()), f3Bytes)

            val batchId = BatchId(UUID.randomUUID().toString())
            val sessionId = SessionId(UUID.randomUUID().toString())

            val batch = TransferBatch(
                id = batchId,
                items = listOf(
                    TransferItem(id1, "file1.txt", "text/plain", f1Bytes.size.toLong()),
                    TransferItem(id2, "file2.txt", "text/plain", f2Bytes.size.toLong()),
                    TransferItem(id3, "file3.txt", "text/plain", f3Bytes.size.toLong())
                )
            )

            val sHistory = FakeHistoryStore()
            val rHistory = FakeHistoryStore()

            val senderCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender Device",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = sHistory,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(id1 to h1, id2 to h2, id3 to h3)
            )

            val receiverCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver Device",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = rHistory,
                scope = this
            )

            receiverCoordinator.start()
            senderCoordinator.start()

            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }
            receiverCoordinator.acceptOffer()

            // Wait until item 1 is verified
            while (receiverCoordinator.state.value.verifiedCount < 1) {
                kotlinx.coroutines.delay(10)
            }

            // Immediately cancel from sender
            senderCoordinator.cancel("USER_CANCELLED")

            while (senderCoordinator.state.value.phase != SessionPhase.Cancelled ||
                receiverCoordinator.state.value.phase != SessionPhase.Cancelled) {
                kotlinx.coroutines.delay(20)
            }

            assertEquals(SessionPhase.Cancelled, senderCoordinator.state.value.phase)
            assertEquals(SessionPhase.Cancelled, receiverCoordinator.state.value.phase)

            // Acceptance criterion: File 1 was verified and committed on disk before cancellation, must be retained!
            assertEquals(1, receiverCoordinator.committedFiles.size)
            val c1 = rReceiveStore.committedFiles[receiverCoordinator.committedFiles[0]]
            assertNotNull(c1)
            assertTrue(f1Bytes.contentEquals(c1!!.first))

            // History verification
            val rItems = rHistory.getItemsForSession(sessionId).associateBy { it.itemId }
            assertEquals("Verified", rItems[id1]?.outcome)
            assertEquals("Cancelled", rItems[id2]?.outcome)
            assertEquals("NotAttempted", rItems[id3]?.outcome)

            assertEquals("Cancelled", rHistory.getSession(sessionId)?.outcome)
            assertEquals("Cancelled", sHistory.getSession(sessionId)?.outcome)

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }

    @Test
    fun sequentialBatch_lostFinalReceipt_terminatesInDeliveryUnconfirmedWithVerifiedFilesRetained() = runBlocking {
        withTimeout(15000) {
            val (sTransport, rTransport) = createPair()
            rTransport.dropControlMessageType = dev.glide.core.protocol.MessageTypes.BATCH_RESULT

            val sSourceAccess = FakeSourceAccess()
            val rReceiveStore = FakeReceiveStore()

            val f1Bytes = "Receipt loss file 1".toByteArray(Charsets.UTF_8)
            val f2Bytes = "Receipt loss file 2".toByteArray(Charsets.UTF_8)

            val h1 = SourceHandle("content://media/1.txt")
            val h2 = SourceHandle("content://media/2.txt")

            val id1 = ItemId(UUID.randomUUID().toString())
            val id2 = ItemId(UUID.randomUUID().toString())

            sSourceAccess.registerSource(h1, SourceMetadata("file1.txt", "text/plain", f1Bytes.size.toLong()), f1Bytes)
            sSourceAccess.registerSource(h2, SourceMetadata("file2.txt", "text/plain", f2Bytes.size.toLong()), f2Bytes)

            val batchId = BatchId(UUID.randomUUID().toString())
            val sessionId = SessionId(UUID.randomUUID().toString())

            val batch = TransferBatch(
                id = batchId,
                items = listOf(
                    TransferItem(id1, "file1.txt", "text/plain", f1Bytes.size.toLong()),
                    TransferItem(id2, "file2.txt", "text/plain", f2Bytes.size.toLong())
                )
            )

            val sHistory = FakeHistoryStore()
            val rHistory = FakeHistoryStore()

            val senderCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender Device",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = sHistory,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(id1 to h1, id2 to h2)
            )

            val receiverCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver Device",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = rHistory,
                scope = this
            )

            receiverCoordinator.start()
            senderCoordinator.start()

            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }
            receiverCoordinator.acceptOffer()

            // Receiver finishes both items and sends BATCH_RESULT (which is dropped)
            while (receiverCoordinator.state.value.phase != SessionPhase.Completed) {
                kotlinx.coroutines.delay(20)
            }
            assertEquals(SessionPhase.Completed, receiverCoordinator.state.value.phase)
            assertEquals(2, receiverCoordinator.committedFiles.size)

            // Sender is now in AwaitingReceipt
            while (senderCoordinator.state.value.phase != SessionPhase.AwaitingReceipt) {
                kotlinx.coroutines.delay(10)
            }

            // Simulate disconnect / link failure while awaiting receipt
            rTransport.disconnect(sTransport.localPeerId)

            while (!senderCoordinator.state.value.isTerminal) {
                kotlinx.coroutines.delay(20)
            }

            // Sender transitions to DeliveryUnconfirmed
            assertEquals(SessionPhase.DeliveryUnconfirmed, senderCoordinator.state.value.phase)
            assertTrue(senderCoordinator.state.value.failure is TransferFailure.DeliveryUnconfirmed)

            // Both files on sender were verified
            assertEquals(2, senderCoordinator.state.value.verifiedCount)
            val sItems = sHistory.getItemsForSession(sessionId)
            assertEquals(2, sItems.size)
            assertTrue(sItems.all { it.outcome == "Verified" })
            assertEquals("DeliveryUnconfirmed", sHistory.getSession(sessionId)?.outcome)

            // Receiver has both files committed and recorded as Completed
            assertEquals(2, receiverCoordinator.committedFiles.size)
            val rItems = rHistory.getItemsForSession(sessionId)
            assertEquals(2, rItems.size)
            assertTrue(rItems.all { it.outcome == "Verified" })
            assertEquals("Completed", rHistory.getSession(sessionId)?.outcome)

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }
}
