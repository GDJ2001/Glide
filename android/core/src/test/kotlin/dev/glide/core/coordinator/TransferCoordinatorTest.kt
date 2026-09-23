package dev.glide.core.coordinator

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.MessageId
import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferFailure
import dev.glide.core.domain.TransferItem
import dev.glide.core.ports.SourceAccess
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
import dev.glide.core.protocol.Envelope
import dev.glide.core.protocol.HelloBody
import dev.glide.core.protocol.MessageTypes
import dev.glide.core.protocol.NegotiationValidator
import dev.glide.core.protocol.ProtocolCodec
import dev.glide.core.stream.StreamHasher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
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
        var dropControlMessagePredicate: ((Envelope) -> Boolean)? = null
        var streamCounter: Int = 0
        var sendStreamDelayMs: Long = 0L
        var disconnectCalls: Int = 0

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
            if (env != null && dropControlMessagePredicate?.invoke(env) == true) {
                return
            }
            remote.controlChannel.send(ControlMessage(localPeerId, payload))
        }

        override fun observeControl(): Flow<ControlMessage> = controlChannel.receiveAsFlow()

        val preparedStreams = mutableMapOf<BulkRef, InputStream>()

        override suspend fun prepareStream(streamSource: InputStreamProvider): BulkRef {
            streamCounter++
            val bulkRef = BulkRef("loopback-bulk-$streamCounter")
            preparedStreams[bulkRef] = streamSource.openStream()
            return bulkRef
        }

        override suspend fun markItemReady(bulkRef: BulkRef) {
            markedReadyStreams.add(bulkRef)
        }

        override suspend fun registerExpectedStream(bulkRef: BulkRef) {
            registeredExpectedStreams.add(bulkRef)
        }

        override suspend fun sendStream(
            peerId: PeerId,
            bulkRef: BulkRef
        ): Flow<BulkProgress> = flow {
            if (sendStreamDelayMs > 0) {
                kotlinx.coroutines.delay(sendStreamDelayMs)
            }
            currentStreamSendCount++
            val shouldCorrupt = injectCorruption || (corruptItemIndex != null && currentStreamSendCount == corruptItemIndex)
            val stream = preparedStreams.remove(bulkRef)
                ?: throw IllegalStateException("No prepared stream for bulkRef ${bulkRef.value}")
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

        override suspend fun cancelPayload(bulkRef: BulkRef) {
            cancelledPayloads.add(bulkRef)
        }

        override suspend fun disconnect(peerId: PeerId) {
            disconnectCalls++
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

            senderCoordinator.awaitTerminalCleanup()
            receiverCoordinator.awaitTerminalCleanup()

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

            senderCoordinator.awaitTerminalCleanup()
            receiverCoordinator.awaitTerminalCleanup()

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
    fun cancelAndAwaitCleanup_returnsOnlyAfterTransportDisconnect() = runBlocking {
        withTimeout(5_000) {
            val (senderTransport, receiverTransport) = createPair()
            val coordinator = TransferCoordinator(
                sessionId = SessionId(UUID.randomUUID().toString()),
                role = DeviceRole.Sender,
                peerId = receiverTransport.localPeerId,
                localDisplayName = "Sender Device",
                transport = senderTransport,
                sourceAccess = FakeSourceAccess(),
                receiveStore = FakeReceiveStore(),
                scope = this
            )

            coordinator.cancelAndAwaitCleanup("USER_CANCELLED")

            assertEquals(SessionPhase.Cancelled, coordinator.state.value.phase)
            assertEquals(1, senderTransport.disconnectCalls)
            coordinator.stop()
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

            senderCoordinator.awaitTerminalCleanup()
            receiverCoordinator.awaitTerminalCleanup()

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

            senderCoordinator.awaitTerminalCleanup()
            receiverCoordinator.awaitTerminalCleanup()

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

            senderCoordinator.awaitTerminalCleanup()
            receiverCoordinator.awaitTerminalCleanup()

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

            senderCoordinator.awaitTerminalCleanup()
            receiverCoordinator.awaitTerminalCleanup()

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

            senderCoordinator.awaitTerminalCleanup()
            receiverCoordinator.awaitTerminalCleanup()

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

            receiverCoordinator.awaitTerminalCleanup()
            assertEquals(SessionPhase.Completed, receiverCoordinator.state.value.phase)
            assertEquals(2, receiverCoordinator.committedFiles.size)

            // Sender is now in AwaitingReceipt
            while (senderCoordinator.state.value.phase != SessionPhase.AwaitingReceipt) {
                kotlinx.coroutines.delay(10)
            }

            // Simulate disconnect / link failure while awaiting receipt
            rTransport.disconnect(sTransport.localPeerId)

            // Capture the exact terminal state emission before cleanup can close the
            // observation window and leave only the final snapshot.
            val terminalState = senderCoordinator.state.first {
                it.phase == SessionPhase.DeliveryUnconfirmed
            }

            // Sender transitions to DeliveryUnconfirmed
            assertEquals(SessionPhase.DeliveryUnconfirmed, terminalState.phase)
            assertTrue(terminalState.failure is TransferFailure.DeliveryUnconfirmed)

            // Terminal state publication precedes asynchronous history persistence.
            // The cleanup barrier is the deterministic point at which terminal bookkeeping is complete.
            senderCoordinator.awaitTerminalCleanup()

            // Both files on sender were verified
            assertEquals(2, terminalState.verifiedCount)
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

    private class TestTrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var isClosed: Boolean = false
            private set

        override fun close() {
            super.close()
            isClosed = true
        }
    }

    @Test
    fun inboundControl_wrongSessionId_rejectsAndFailsWithoutReducerDelivery() = runBlocking {
        withTimeout(5000) {
            val (sTransport, rTransport) = createPair()
            val rReceiveStore = FakeReceiveStore()
            val rHistory = FakeHistoryStore()
            val expectedSessionId = SessionId(UUID.randomUUID().toString())
            val wrongSessionId = SessionId(UUID.randomUUID().toString())

            val receiverCoordinator = TransferCoordinator(
                sessionId = expectedSessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver Device",
                transport = rTransport,
                sourceAccess = FakeSourceAccess(),
                receiveStore = rReceiveStore,
                historyStore = rHistory,
                scope = this
            )

            receiverCoordinator.start()

            // Send a HELLO envelope with wrong sessionId from valid peer
            val hello = NegotiationValidator.createHello(
                role = DeviceRole.Sender,
                ephemeralPeerId = UUID.randomUUID().toString(),
                displayName = "Rogue Sender"
            )
            val envelope = Envelope(
                v = 1,
                type = MessageTypes.HELLO,
                sessionId = wrongSessionId,
                messageId = dev.glide.core.domain.MessageId(UUID.randomUUID().toString()),
                seq = 1L,
                body = ProtocolCodec.encodeBody(hello)
            )
            sTransport.sendControl(rTransport.localPeerId, ProtocolCodec.encode(envelope))

            while (!receiverCoordinator.state.value.isTerminal) {
                kotlinx.coroutines.delay(10)
            }

            assertEquals(SessionPhase.Failed, receiverCoordinator.state.value.phase)
            assertTrue(receiverCoordinator.state.value.failure is TransferFailure.ProtocolOrder)
            assertTrue(receiverCoordinator.state.value.failure?.message?.contains("does not match") == true)

            receiverCoordinator.stop()
        }
    }

    @Test
    fun inboundStream_unknownBulkRef_closesStreamAndFailsWithoutWritingStaging() = runBlocking {
        withTimeout(5000) {
            val (sTransport, rTransport) = createPair()
            val sSourceAccess = FakeSourceAccess()
            val rReceiveStore = FakeReceiveStore()
            val sessionId = SessionId(UUID.randomUUID().toString())
            val itemId = ItemId(UUID.randomUUID().toString())
            val sourceHandle = SourceHandle("content://media/test.txt")
            val fileContent = "Test content".toByteArray(Charsets.UTF_8)

            sSourceAccess.registerSource(sourceHandle, SourceMetadata("test.txt", "text/plain", fileContent.size.toLong()), fileContent)
            val batch = TransferBatch(BatchId(UUID.randomUUID().toString()), listOf(TransferItem(itemId, "test.txt", "text/plain", fileContent.size.toLong())))

            // Drop ITEM_READY so sender never sends the legitimate stream
            rTransport.dropControlMessageType = MessageTypes.ITEM_READY

            val senderCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(itemId to sourceHandle)
            )
            val receiverCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this
            )

            receiverCoordinator.start()
            senderCoordinator.start()

            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }
            receiverCoordinator.acceptOffer()

            // Wait until receiver processes ITEM_START and enters TransferringItem
            while (receiverCoordinator.state.value.phase != SessionPhase.TransferringItem) {
                kotlinx.coroutines.delay(10)
            }

            // Inject incoming stream with UNKNOWN bulkRef
            val rogueTrackingStream = TestTrackingInputStream("Rogue content".toByteArray(Charsets.UTF_8))
            val rogueIncomingStream = IncomingStream(
                peerId = sTransport.localPeerId,
                bulkRef = BulkRef("rogue-unregistered-ref-999"),
                stream = rogueTrackingStream
            )
            rTransport.incomingStreamChannel.send(rogueIncomingStream)

            while (!receiverCoordinator.state.value.isTerminal) {
                kotlinx.coroutines.delay(10)
            }

            assertEquals(SessionPhase.Failed, receiverCoordinator.state.value.phase)
            assertTrue(receiverCoordinator.state.value.failure is TransferFailure.ProtocolOrder)
            assertTrue(receiverCoordinator.state.value.failure?.message?.contains("Unknown incoming stream") == true)
            assertTrue("Rejected stream must be closed", rogueTrackingStream.isClosed)
            assertTrue(rReceiveStore.committedFiles.isEmpty())

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }

    @Test
    fun inboundStream_staleBulkRef_closesStreamAndFails() = runBlocking {
        withTimeout(10000) {
            val (sTransport, rTransport) = createPair()
            val sSourceAccess = FakeSourceAccess()
            val rReceiveStore = FakeReceiveStore()
            val sessionId = SessionId(UUID.randomUUID().toString())
            val id1 = ItemId(UUID.randomUUID().toString())
            val id2 = ItemId(UUID.randomUUID().toString())
            val h1 = SourceHandle("content://media/f1.txt")
            val h2 = SourceHandle("content://media/f2.txt")
            val f1Bytes = "File 1".toByteArray(Charsets.UTF_8)
            val f2Bytes = "File 2".toByteArray(Charsets.UTF_8)

            sSourceAccess.registerSource(h1, SourceMetadata("f1.txt", "text/plain", f1Bytes.size.toLong()), f1Bytes)
            sSourceAccess.registerSource(h2, SourceMetadata("f2.txt", "text/plain", f2Bytes.size.toLong()), f2Bytes)

            val batch = TransferBatch(
                BatchId(UUID.randomUUID().toString()),
                listOf(
                    TransferItem(id1, "f1.txt", "text/plain", f1Bytes.size.toLong()),
                    TransferItem(id2, "f2.txt", "text/plain", f2Bytes.size.toLong())
                )
            )

            // Drop 2nd ITEM_READY so sender does not send stream 2
            var itemReadyCount = 0
            rTransport.dropControlMessagePredicate = { env ->
                if (env.type == MessageTypes.ITEM_READY) {
                    itemReadyCount++
                    itemReadyCount == 2
                } else false
            }

            val senderCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(id1 to h1, id2 to h2)
            )
            val receiverCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this
            )

            receiverCoordinator.start()
            senderCoordinator.start()

            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }
            receiverCoordinator.acceptOffer()

            // Wait until item 1 is verified on receiver and receiver enters TransferringItem for item 2
            while (receiverCoordinator.state.value.verifiedCount < 1 ||
                receiverCoordinator.state.value.currentItemIndex != 1 ||
                receiverCoordinator.state.value.phase != SessionPhase.TransferringItem) {
                kotlinx.coroutines.delay(10)
            }

            // Get bulkRef of item 1 (which is now completed/verified)
            val staleBulkRef = receiverCoordinator.state.value.itemStates[id1]?.bulkRef
            assertNotNull(staleBulkRef)

            // Inject late/stale stream with bulkRef belonging to item 1
            val staleTrackingStream = TestTrackingInputStream("Stale replay".toByteArray(Charsets.UTF_8))
            val staleStream = IncomingStream(
                peerId = sTransport.localPeerId,
                bulkRef = staleBulkRef!!,
                stream = staleTrackingStream
            )
            rTransport.incomingStreamChannel.send(staleStream)

            while (!receiverCoordinator.state.value.isTerminal) {
                kotlinx.coroutines.delay(10)
            }

            assertEquals(SessionPhase.Failed, receiverCoordinator.state.value.phase)
            assertTrue(receiverCoordinator.state.value.failure is TransferFailure.ProtocolOrder)
            assertTrue(receiverCoordinator.state.value.failure?.message?.contains("Stale or inactive") == true)
            assertTrue("Stale stream must be closed", staleTrackingStream.isClosed)

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }

    @Test
    fun inboundStream_preConsentData_closesStreamAndFailsImmediately() = runBlocking {
        withTimeout(5000) {
            val (sTransport, rTransport) = createPair()
            val rReceiveStore = FakeReceiveStore()
            val sessionId = SessionId(UUID.randomUUID().toString())

            val receiverCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport,
                sourceAccess = FakeSourceAccess(),
                receiveStore = rReceiveStore,
                scope = this
            )

            receiverCoordinator.start()

            // While receiver is in Idle (before any negotiation or consent), send an incoming stream
            val trackingStream = TestTrackingInputStream("Early data".toByteArray(Charsets.UTF_8))
            val earlyStream = IncomingStream(
                peerId = sTransport.localPeerId,
                bulkRef = BulkRef("early-stream-001"),
                stream = trackingStream
            )
            rTransport.incomingStreamChannel.send(earlyStream)

            while (!receiverCoordinator.state.value.isTerminal) {
                kotlinx.coroutines.delay(10)
            }

            assertEquals(SessionPhase.Failed, receiverCoordinator.state.value.phase)
            assertTrue(receiverCoordinator.state.value.failure is TransferFailure.ProtocolOrder)
            assertTrue(receiverCoordinator.state.value.failure?.message?.contains("before explicit offer acceptance") == true)
            assertTrue("Pre-consent stream must be closed", trackingStream.isClosed)
            assertTrue(rReceiveStore.committedFiles.isEmpty())

            receiverCoordinator.stop()
        }
    }

    @Test
    fun inboundStream_terminalStreamCleanup_closesStreamImmediately() = runBlocking {
        withTimeout(5000) {
            val (sTransport, rTransport) = createPair()
            val rReceiveStore = FakeReceiveStore()
            val sessionId = SessionId(UUID.randomUUID().toString())

            val receiverCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport,
                sourceAccess = FakeSourceAccess(),
                receiveStore = rReceiveStore,
                scope = this
            )

            receiverCoordinator.start()

            // Explicitly cancel to reach terminal Cancelled state
            receiverCoordinator.cancel("TEST_CANCEL")

            while (receiverCoordinator.state.value.phase != SessionPhase.Cancelled) {
                kotlinx.coroutines.delay(10)
            }

            receiverCoordinator.awaitTerminalCleanup()

            // Stream arrives after terminal cancellation
            val lateTrackingStream = TestTrackingInputStream("Late data".toByteArray(Charsets.UTF_8))
            val lateStream = IncomingStream(
                peerId = sTransport.localPeerId,
                bulkRef = BulkRef("late-stream-001"),
                stream = lateTrackingStream
            )
            rTransport.incomingStreamChannel.send(lateStream)

            // Allow the collector coroutine to run
            kotlinx.coroutines.delay(50)

            assertEquals(SessionPhase.Cancelled, receiverCoordinator.state.value.phase)
            assertTrue("Late stream arriving after terminal state must be closed", lateTrackingStream.isClosed)
            assertTrue(rReceiveStore.committedFiles.isEmpty())

            receiverCoordinator.stop()
        }
    }

    @Test
    fun lan002_changingSourceProvider_opensOnceAndSucceedsWithMatchingDigest() = runBlocking {
        withTimeout(10000) {
            val (sTransport, rTransport) = createPair()
            val rReceiveStore = FakeReceiveStore()
            val rHistory = FakeHistoryStore()
            val sHistory = FakeHistoryStore()

            val firstContent = "Authentic initial bytes from single open".toByteArray(Charsets.UTF_8)
            val secondContent = "Corrupted bytes if opened a second time".toByteArray(Charsets.UTF_8)
            val handle = SourceHandle("content://media/changing.txt")
            val itemId = ItemId(UUID.randomUUID().toString())
            val batchId = BatchId(UUID.randomUUID().toString())
            val sessionId = SessionId(UUID.randomUUID().toString())

            var openCount = 0
            val changingSourceAccess = object : SourceAccess {
                override suspend fun validateSource(h: SourceHandle) =
                    SourceMetadata("changing.txt", "text/plain", firstContent.size.toLong())
                override suspend fun openSource(h: SourceHandle): InputStream {
                    openCount++
                    return ByteArrayInputStream(if (openCount == 1) firstContent else secondContent)
                }
            }

            val batch = TransferBatch(
                id = batchId,
                items = listOf(TransferItem(itemId, "changing.txt", "text/plain", firstContent.size.toLong()))
            )

            val senderCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender Device",
                transport = sTransport,
                sourceAccess = changingSourceAccess,
                receiveStore = rReceiveStore,
                historyStore = sHistory,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(itemId to handle)
            )

            val receiverCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver Device",
                transport = rTransport,
                sourceAccess = changingSourceAccess,
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

            while (senderCoordinator.state.value.phase != SessionPhase.Completed ||
                receiverCoordinator.state.value.phase != SessionPhase.Completed) {
                kotlinx.coroutines.delay(20)
            }

            senderCoordinator.awaitTerminalCleanup()
            receiverCoordinator.awaitTerminalCleanup()

            assertEquals(SessionPhase.Completed, senderCoordinator.state.value.phase)
            assertEquals(SessionPhase.Completed, receiverCoordinator.state.value.phase)

            // CRITICAL LAN-002 assertion: source was opened EXACTLY ONCE
            assertEquals(1, openCount)

            // Receiver committed the exact first content
            assertEquals(1, receiverCoordinator.committedFiles.size)
            val committed = rReceiveStore.committedFiles[receiverCoordinator.committedFiles[0]]
            assertNotNull(committed)
            assertTrue(firstContent.contentEquals(committed!!.first))

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }

    @Test
    fun lan002_singleOpenAndIdempotentUnderlyingClose() = runBlocking {
        withTimeout(10000) {
            val (sTransport, rTransport) = createPair()
            val rReceiveStore = FakeReceiveStore()
            val fileContent = "Single open tracking stream".toByteArray(Charsets.UTF_8)
            val handle = SourceHandle("content://media/tracking.txt")
            val itemId = ItemId(UUID.randomUUID().toString())
            val batchId = BatchId(UUID.randomUUID().toString())
            val sessionId = SessionId(UUID.randomUUID().toString())

            var openCount = 0
            val trackingStream = TestTrackingInputStream(fileContent)
            val trackingSourceAccess = object : SourceAccess {
                override suspend fun validateSource(h: SourceHandle) =
                    SourceMetadata("tracking.txt", "text/plain", fileContent.size.toLong())
                override suspend fun openSource(h: SourceHandle): InputStream {
                    openCount++
                    return trackingStream
                }
            }

            val batch = TransferBatch(
                id = batchId,
                items = listOf(TransferItem(itemId, "tracking.txt", "text/plain", fileContent.size.toLong()))
            )

            val senderCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender Device",
                transport = sTransport,
                sourceAccess = trackingSourceAccess,
                receiveStore = rReceiveStore,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(itemId to handle)
            )

            val receiverCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver Device",
                transport = rTransport,
                sourceAccess = trackingSourceAccess,
                receiveStore = rReceiveStore,
                scope = this
            )

            receiverCoordinator.start()
            senderCoordinator.start()

            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }
            receiverCoordinator.acceptOffer()

            while (senderCoordinator.state.value.phase != SessionPhase.Completed ||
                receiverCoordinator.state.value.phase != SessionPhase.Completed) {
                kotlinx.coroutines.delay(20)
            }

            senderCoordinator.awaitTerminalCleanup()
            receiverCoordinator.awaitTerminalCleanup()

            // Exactly one open and underlying stream is closed
            assertEquals(1, openCount)
            assertTrue(trackingStream.isClosed)

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }

    @Test
    fun lan002_earlyEof_detectedAndFailsTransfer() = runBlocking {
        withTimeout(10000) {
            val (sTransport, rTransport) = createPair()
            val rReceiveStore = FakeReceiveStore()
            // Item promises 100 bytes, but source only has 20 bytes
            val shortContent = "Only 20 bytes here!".toByteArray(Charsets.UTF_8)
            val promisedSize = 100L
            val handle = SourceHandle("content://media/short.txt")
            val itemId = ItemId(UUID.randomUUID().toString())
            val batchId = BatchId(UUID.randomUUID().toString())
            val sessionId = SessionId(UUID.randomUUID().toString())

            val sSourceAccess = FakeSourceAccess()
            sSourceAccess.registerSource(handle, SourceMetadata("short.txt", "text/plain", promisedSize), shortContent)

            val batch = TransferBatch(
                id = batchId,
                items = listOf(TransferItem(itemId, "short.txt", "text/plain", promisedSize))
            )

            val senderCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(itemId to handle)
            )

            val receiverCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this
            )

            receiverCoordinator.start()
            senderCoordinator.start()

            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }
            receiverCoordinator.acceptOffer()

            while (!senderCoordinator.state.value.isTerminal) {
                kotlinx.coroutines.delay(20)
            }

            assertEquals(SessionPhase.Failed, senderCoordinator.state.value.phase)
            assertTrue(rReceiveStore.committedFiles.isEmpty())

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }

    @Test
    fun lan002_overlongSource_detectedAndFailsTransfer() = runBlocking {
        withTimeout(10000) {
            val (sTransport, rTransport) = createPair()
            val rReceiveStore = FakeReceiveStore()
            // Item promises 10 bytes, but source has 30 bytes
            val overlongContent = "This content has thirty bytes!".toByteArray(Charsets.UTF_8)
            val promisedSize = 10L
            val handle = SourceHandle("content://media/overlong.txt")
            val itemId = ItemId(UUID.randomUUID().toString())
            val batchId = BatchId(UUID.randomUUID().toString())
            val sessionId = SessionId(UUID.randomUUID().toString())

            val sSourceAccess = FakeSourceAccess()
            sSourceAccess.registerSource(handle, SourceMetadata("overlong.txt", "text/plain", promisedSize), overlongContent)

            val batch = TransferBatch(
                id = batchId,
                items = listOf(TransferItem(itemId, "overlong.txt", "text/plain", promisedSize))
            )

            val senderCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(itemId to handle)
            )

            val receiverCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this
            )

            receiverCoordinator.start()
            senderCoordinator.start()

            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }
            receiverCoordinator.acceptOffer()

            while (!senderCoordinator.state.value.isTerminal) {
                kotlinx.coroutines.delay(20)
            }

            assertEquals(SessionPhase.Failed, senderCoordinator.state.value.phase)
            assertTrue(rReceiveStore.committedFiles.isEmpty())

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }

    @Test
    fun lan002_zeroByteFile_provesEofAndTransfersSuccessfully() = runBlocking {
        withTimeout(10000) {
            val (sTransport, rTransport) = createPair()
            val rReceiveStore = FakeReceiveStore()
            val emptyContent = byteArrayOf()
            val handle = SourceHandle("content://media/empty.txt")
            val itemId = ItemId(UUID.randomUUID().toString())
            val batchId = BatchId(UUID.randomUUID().toString())
            val sessionId = SessionId(UUID.randomUUID().toString())

            val sSourceAccess = FakeSourceAccess()
            sSourceAccess.registerSource(handle, SourceMetadata("empty.txt", "text/plain", 0L), emptyContent)

            val batch = TransferBatch(
                id = batchId,
                items = listOf(TransferItem(itemId, "empty.txt", "text/plain", 0L))
            )

            val senderCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(itemId to handle)
            )

            val receiverCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this
            )

            receiverCoordinator.start()
            senderCoordinator.start()

            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }
            receiverCoordinator.acceptOffer()

            while (senderCoordinator.state.value.phase != SessionPhase.Completed ||
                receiverCoordinator.state.value.phase != SessionPhase.Completed) {
                kotlinx.coroutines.delay(20)
            }

            senderCoordinator.awaitTerminalCleanup()
            receiverCoordinator.awaitTerminalCleanup()

            assertEquals(SessionPhase.Completed, senderCoordinator.state.value.phase)
            assertEquals(SessionPhase.Completed, receiverCoordinator.state.value.phase)
            assertEquals(1, receiverCoordinator.committedFiles.size)
            val committed = rReceiveStore.committedFiles[receiverCoordinator.committedFiles[0]]
            assertNotNull(committed)
            assertEquals(0, committed!!.first.size)
            assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", committed.second.value)

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }

    @Test
    fun lan002_offeredZeroNonemptyMismatch_failsTransfer() = runBlocking {
        withTimeout(10000) {
            val (sTransport, rTransport) = createPair()
            val rReceiveStore = FakeReceiveStore()
            // Offered 0 bytes, but source has 15 bytes
            val nonEmptyContent = "Non-empty bytes".toByteArray(Charsets.UTF_8)
            val handle = SourceHandle("content://media/zero_mismatch.txt")
            val itemId = ItemId(UUID.randomUUID().toString())
            val batchId = BatchId(UUID.randomUUID().toString())
            val sessionId = SessionId(UUID.randomUUID().toString())

            val sSourceAccess = FakeSourceAccess()
            sSourceAccess.registerSource(handle, SourceMetadata("zero_mismatch.txt", "text/plain", 0L), nonEmptyContent)

            val batch = TransferBatch(
                id = batchId,
                items = listOf(TransferItem(itemId, "zero_mismatch.txt", "text/plain", 0L))
            )

            val senderCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(itemId to handle)
            )

            val receiverCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this
            )

            receiverCoordinator.start()
            senderCoordinator.start()

            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }
            receiverCoordinator.acceptOffer()

            while (!senderCoordinator.state.value.isTerminal) {
                kotlinx.coroutines.delay(20)
            }

            assertEquals(SessionPhase.Failed, senderCoordinator.state.value.phase)
            assertTrue(rReceiveStore.committedFiles.isEmpty())

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }

    @Test
    fun lan002_invalidOfferDecision_createsNoSourceOpenOrPrepareStream() = runBlocking {
        withTimeout(5000) {
            val (sTransport, rTransport) = createPair()
            val sSourceAccess = FakeSourceAccess()
            val handle = SourceHandle("content://media/test.txt")
            val itemId = ItemId(UUID.randomUUID().toString())
            val fileBytes = "test data".toByteArray(Charsets.UTF_8)
            sSourceAccess.registerSource(handle, SourceMetadata("test.txt", "text/plain", fileBytes.size.toLong()), fileBytes)

            val batch = TransferBatch(
                BatchId(UUID.randomUUID().toString()),
                listOf(TransferItem(itemId, "test.txt", "text/plain", fileBytes.size.toLong()))
            )
            val sessionId = SessionId(UUID.randomUUID().toString())

            val senderCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = FakeReceiveStore(),
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(itemId to handle)
            )

            senderCoordinator.start()

            // Send unsolicited/invalid OFFER_DECISION with wrong batchId while sender is not in AwaitingConsent
            val rogueDecision = dev.glide.core.protocol.OfferDecisionBody(
                batchId = BatchId(UUID.randomUUID().toString()),
                accepted = true
            )
            val envelope = Envelope(
                v = 1,
                type = MessageTypes.OFFER_DECISION,
                sessionId = sessionId,
                messageId = MessageId(UUID.randomUUID().toString()),
                seq = 1L,
                body = ProtocolCodec.encodeBody(rogueDecision)
            )
            rTransport.sendControl(sTransport.localPeerId, ProtocolCodec.encode(envelope))

            kotlinx.coroutines.delay(100)

            // LAN-002: Source must NOT be opened and prepareStream must NOT be called on invalid OFFER_DECISION
            assertTrue("Source must not be opened on invalid OFFER_DECISION", sSourceAccess.openCounts.isEmpty())
            assertTrue("No stream must be prepared in transport on invalid OFFER_DECISION", sTransport.preparedStreams.isEmpty())

            senderCoordinator.stop()
        }
    }

    @Test
    fun lan002_invalidItemStart_createsNoExpectationOrStaging() = runBlocking {
        withTimeout(5000) {
            val (sTransport, rTransport) = createPair()
            val rReceiveStore = FakeReceiveStore()
            val sessionId = SessionId(UUID.randomUUID().toString())

            val receiverCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport,
                sourceAccess = FakeSourceAccess(),
                receiveStore = rReceiveStore,
                scope = this
            )

            receiverCoordinator.start()

            // Send unsolicited ITEM_START while receiver is in Idle (before HELLO / OFFER)
            val rogueStart = dev.glide.core.protocol.ItemStartBody(
                batchId = BatchId(UUID.randomUUID().toString()),
                itemId = ItemId(UUID.randomUUID().toString()),
                bulkRef = BulkRef("rogue-unsolicited-ref"),
                sizeBytes = "100"
            )
            val envelope = Envelope(
                v = 1,
                type = MessageTypes.ITEM_START,
                sessionId = sessionId,
                messageId = MessageId(UUID.randomUUID().toString()),
                seq = 1L,
                body = ProtocolCodec.encodeBody(rogueStart)
            )
            sTransport.sendControl(rTransport.localPeerId, ProtocolCodec.encode(envelope))

            kotlinx.coroutines.delay(100)

            // LAN-002: No expectation registered and no staging prepared on unvalidated ITEM_START
            assertTrue("Expected stream must not be registered on invalid ITEM_START", rTransport.registeredExpectedStreams.isEmpty())
            assertTrue("Staging must not be prepared on invalid ITEM_START", rReceiveStore.preparedStaging.isEmpty())

            receiverCoordinator.stop()
        }
    }

    @Test
    fun lan003_receiverWithoutSessionId_adoptsFirstHelloSessionIdAndCompletes() = runBlocking {
        withTimeout(10000) {
            val (sTransport, rTransport) = createPair()
            val rReceiveStore = FakeReceiveStore()
            val content = "Hello receiver session binding!".toByteArray(Charsets.UTF_8)
            val handle = SourceHandle("content://media/binding.txt")
            val itemId = ItemId(UUID.randomUUID().toString())
            val batchId = BatchId(UUID.randomUUID().toString())
            val senderSessionId = SessionId(UUID.randomUUID().toString())

            val sSourceAccess = FakeSourceAccess()
            sSourceAccess.registerSource(handle, SourceMetadata("binding.txt", "text/plain", content.size.toLong()), content)

            val batch = TransferBatch(
                id = batchId,
                items = listOf(TransferItem(itemId, "binding.txt", "text/plain", content.size.toLong()))
            )

            val senderCoordinator = TransferCoordinator(
                sessionId = senderSessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(itemId to handle)
            )

            // Receiver created with sessionId = null (LAN-003 Nearby receiver pattern)
            val receiverCoordinator = TransferCoordinator(
                sessionId = null,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this
            )

            receiverCoordinator.start()
            senderCoordinator.start()

            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }
            // Verify receiver has adopted the sender's sessionId once
            assertEquals(senderSessionId, receiverCoordinator.sessionId)
            assertEquals(senderSessionId, receiverCoordinator.state.value.sessionId)

            receiverCoordinator.acceptOffer()

            while (senderCoordinator.state.value.phase != SessionPhase.Completed ||
                receiverCoordinator.state.value.phase != SessionPhase.Completed) {
                kotlinx.coroutines.delay(20)
            }

            senderCoordinator.awaitTerminalCleanup()
            receiverCoordinator.awaitTerminalCleanup()

            assertEquals(SessionPhase.Completed, senderCoordinator.state.value.phase)
            assertEquals(SessionPhase.Completed, receiverCoordinator.state.value.phase)
            assertEquals(1, receiverCoordinator.committedFiles.size)

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }

    @Test
    fun lan003_receiverWithoutSessionId_dropsNonHelloPriorToHello() = runBlocking {
        withTimeout(5000) {
            val (sTransport, rTransport) = createPair()
            val rReceiveStore = FakeReceiveStore()
            val sSourceAccess = FakeSourceAccess()

            val receiverCoordinator = TransferCoordinator(
                sessionId = null,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this
            )

            receiverCoordinator.start()

            // Send OFFER_BEGIN before HELLO
            val rogueBegin = dev.glide.core.protocol.OfferBeginBody(
                batchId = BatchId(UUID.randomUUID().toString()),
                totalBytes = "100",
                itemCount = 1,
                pageCount = 1
            )
            val envelope = Envelope(
                v = 1,
                type = MessageTypes.OFFER_BEGIN,
                sessionId = SessionId(UUID.randomUUID().toString()),
                messageId = MessageId(UUID.randomUUID().toString()),
                seq = 1L,
                body = ProtocolCodec.encodeBody(rogueBegin)
            )
            sTransport.sendControl(rTransport.localPeerId, ProtocolCodec.encode(envelope))

            kotlinx.coroutines.delay(100)

            // Receiver must remain in Idle phase and session ID must remain unbound
            assertEquals(SessionPhase.Idle, receiverCoordinator.state.value.phase)
            assertFalse(receiverCoordinator.state.value.isTerminal)

            receiverCoordinator.stop()
        }
    }

    @Test
    fun lan003_receiverWithoutSessionId_rejectsSubsequentMismatchedSessionId() = runBlocking {
        withTimeout(5000) {
            val (sTransport, rTransport) = createPair()
            val rReceiveStore = FakeReceiveStore()
            val sSourceAccess = FakeSourceAccess()
            val initialSessionId = SessionId(UUID.randomUUID().toString())

            val receiverCoordinator = TransferCoordinator(
                sessionId = null,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this
            )

            receiverCoordinator.start()

            // Send valid HELLO with initialSessionId
            val hello = NegotiationValidator.createHello(
                role = DeviceRole.Sender,
                ephemeralPeerId = UUID.randomUUID().toString(),
                displayName = "Sender"
            )
            val helloEnvelope = Envelope(
                v = 1,
                type = MessageTypes.HELLO,
                sessionId = initialSessionId,
                messageId = MessageId(UUID.randomUUID().toString()),
                seq = 1L,
                body = ProtocolCodec.encodeBody(hello)
            )
            sTransport.sendControl(rTransport.localPeerId, ProtocolCodec.encode(helloEnvelope))

            while (receiverCoordinator.state.value.phase != SessionPhase.Offering) {
                kotlinx.coroutines.delay(10)
            }
            assertEquals(initialSessionId, receiverCoordinator.sessionId)

            // Send subsequent OFFER_BEGIN with a DIFFERENT sessionId
            val wrongSessionId = SessionId(UUID.randomUUID().toString())
            val rogueBegin = dev.glide.core.protocol.OfferBeginBody(
                batchId = BatchId(UUID.randomUUID().toString()),
                totalBytes = "100",
                itemCount = 1,
                pageCount = 1
            )
            val rogueEnvelope = Envelope(
                v = 1,
                type = MessageTypes.OFFER_BEGIN,
                sessionId = wrongSessionId,
                messageId = MessageId(UUID.randomUUID().toString()),
                seq = 2L,
                body = ProtocolCodec.encodeBody(rogueBegin)
            )
            sTransport.sendControl(rTransport.localPeerId, ProtocolCodec.encode(rogueEnvelope))

            while (!receiverCoordinator.state.value.isTerminal) {
                kotlinx.coroutines.delay(10)
            }

            assertTrue(receiverCoordinator.state.value.isTerminal)
            assertEquals(SessionPhase.Failed, receiverCoordinator.state.value.phase)
            assertTrue(receiverCoordinator.state.value.failure is TransferFailure.ProtocolOrder)

            receiverCoordinator.stop()
        }
    }
}
