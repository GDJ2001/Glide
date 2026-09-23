package dev.glide.core.coordinator

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.MessageId
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferFailure
import dev.glide.core.domain.TransferItem
import dev.glide.core.coordinator.ItemPhase
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
import dev.glide.core.ports.ReceiveStoreException
import dev.glide.core.ports.SourceAccessException
import dev.glide.core.ports.SourceMetadata
import dev.glide.core.ports.StreamExpectation
import dev.glide.core.ports.TransferTransport
import dev.glide.core.protocol.Envelope
import dev.glide.core.protocol.ItemStartBody
import dev.glide.core.protocol.MessageTypes
import dev.glide.core.protocol.ProtocolCodec
import dev.glide.core.stream.StreamHasher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Rigorous test suite validating sequential multi-file batch execution, source preflight,
 * storage space reservation, zero-byte handling, provider revocation, and bounded memory (LAN-022).
 */
class SequentialBatchHardeningTest {

    private class TestLoopbackTransport(
        val localPeerId: PeerId
    ) : TransferTransport {
        lateinit var remote: TestLoopbackTransport

        val controlChannel = Channel<ControlMessage>(Channel.UNLIMITED)
        val incomingStreamChannel = Channel<IncomingStream>(Channel.UNLIMITED)
        val bulkProgressChannel = Channel<BulkProgress>(Channel.UNLIMITED)
        val connectionEventsChannel = Channel<ConnectionEvent>(Channel.UNLIMITED)

        val preparedStreams = mutableMapOf<BulkRef, InputStream>()
        var streamCounter: Int = 0

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

        override suspend fun prepareStream(streamSource: InputStreamProvider): BulkRef {
            streamCounter++
            val bulkRef = BulkRef("loopback-bulk-$streamCounter")
            preparedStreams[bulkRef] = streamSource.openStream()
            return bulkRef
        }

        override suspend fun markItemReady(bulkRef: BulkRef) {}

        override suspend fun registerExpectedStream(expectation: StreamExpectation) {}

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
    fun sequentialBatch_oneThousandItems_completesWithBoundedMemory() = runBlocking {
        withTimeout(120000) {
            val (sTransport, rTransport) = createPair()
            val sSourceAccess = FakeSourceAccess()
            val rReceiveStore = FakeReceiveStore(availableSpaceBytes = 10_000_000L)

            val itemCount = ProtocolLimits.MAX_ITEMS_PER_BATCH // 1,000 items!
            val items = ArrayList<TransferItem>(itemCount)
            val sourceMap = HashMap<ItemId, SourceHandle>(itemCount)
            val dummyBytes = "Item data payload bytes".toByteArray(Charsets.UTF_8)

            for (i in 1..itemCount) {
                val itemId = ItemId(UUID.randomUUID().toString())
                val handle = SourceHandle("content://media/file_$i.bin")
                items.add(TransferItem(itemId, "file_$i.bin", "application/octet-stream", dummyBytes.size.toLong()))
                sourceMap[itemId] = handle
                sSourceAccess.registerSource(handle, SourceMetadata("file_$i.bin", "application/octet-stream", dummyBytes.size.toLong()), dummyBytes)
            }

            val batchId = BatchId(UUID.randomUUID().toString())
            val batch = TransferBatch(id = batchId, items = items)
            val sessionId = SessionId(UUID.randomUUID().toString())

            val runtime = Runtime.getRuntime()
            System.gc()
            val memoryBefore = runtime.totalMemory() - runtime.freeMemory()

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
                sourceMap = sourceMap,
                rateLimiter = null
            )

            val receiverCoordinator = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this,
                rateLimiter = null
            )

            receiverCoordinator.start()
            senderCoordinator.start()

            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }

            receiverCoordinator.acceptOffer()

            while (senderCoordinator.state.value.phase != SessionPhase.Completed ||
                receiverCoordinator.state.value.phase != SessionPhase.Completed) {
                if (senderCoordinator.state.value.isTerminal && receiverCoordinator.state.value.isTerminal) {
                    break
                }
                kotlinx.coroutines.delay(10)
            }

            assertEquals(SessionPhase.Completed, senderCoordinator.state.value.phase)
            assertEquals(SessionPhase.Completed, receiverCoordinator.state.value.phase)

            assertEquals(itemCount, receiverCoordinator.committedFiles.size)

            System.gc()
            val memoryAfter = runtime.totalMemory() - runtime.freeMemory()
            val memoryDeltaMb = (memoryAfter - memoryBefore) / (1024 * 1024)

            // Bounded memory report: heap growth for 1,000 items should remain well bounded (< 32 MiB)
            assertTrue("Heap growth ($memoryDeltaMb MB) should be bounded under 32 MB", memoryDeltaMb < 32)

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }

    @Test
    fun sequentialBatch_zeroByteFiles_transfersAndVerifiesSuccessfully() = runBlocking {
        withTimeout(10000) {
            val (sTransport, rTransport) = createPair()
            val sSourceAccess = FakeSourceAccess()
            val rReceiveStore = FakeReceiveStore()

            val emptyBytes = ByteArray(0)
            val normalBytes = "Non-empty content".toByteArray(Charsets.UTF_8)

            val id1 = ItemId(UUID.randomUUID().toString())
            val id2 = ItemId(UUID.randomUUID().toString())
            val id3 = ItemId(UUID.randomUUID().toString())

            val h1 = SourceHandle("content://media/empty1.txt")
            val h2 = SourceHandle("content://media/normal2.txt")
            val h3 = SourceHandle("content://media/empty3.txt")

            sSourceAccess.registerSource(h1, SourceMetadata("empty1.txt", "text/plain", 0L), emptyBytes)
            sSourceAccess.registerSource(h2, SourceMetadata("normal2.txt", "text/plain", normalBytes.size.toLong()), normalBytes)
            sSourceAccess.registerSource(h3, SourceMetadata("empty3.txt", "text/plain", 0L), emptyBytes)

            val batch = TransferBatch(
                id = BatchId(UUID.randomUUID().toString()),
                items = listOf(
                    TransferItem(id1, "empty1.txt", "text/plain", 0L),
                    TransferItem(id2, "normal2.txt", "text/plain", normalBytes.size.toLong()),
                    TransferItem(id3, "empty3.txt", "text/plain", 0L)
                )
            )

            val sessionId = SessionId(UUID.randomUUID().toString())
            val sender = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(id1 to h1, id2 to h2, id3 to h3)
            )

            val receiver = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this
            )

            receiver.start()
            sender.start()

            while (receiver.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }

            receiver.acceptOffer()

            while (sender.state.value.phase != SessionPhase.Completed ||
                receiver.state.value.phase != SessionPhase.Completed) {
                kotlinx.coroutines.delay(20)
            }

            assertEquals(SessionPhase.Completed, sender.state.value.phase)
            assertEquals(SessionPhase.Completed, receiver.state.value.phase)

            assertEquals(3, receiver.committedFiles.size)

            // Verify empty file SHA-256 matches standard empty string hash e3b0c442...
            val emptyHashExpected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            assertEquals(emptyHashExpected, receiver.state.value.itemStates[id1]?.actualDigest?.value)
            assertEquals(emptyHashExpected, receiver.state.value.itemStates[id3]?.actualDigest?.value)
            assertEquals(0L, receiver.state.value.itemStates[id1]?.transferredBytes)
            assertEquals(0L, receiver.state.value.itemStates[id3]?.transferredBytes)

            sender.stop()
            receiver.stop()
        }
    }

    @Test
    fun sequentialBatch_receiverInsufficientStorageAtOffer_rejectsWithInsufficientStorage() = runBlocking {
        withTimeout(5000) {
            val (sTransport, rTransport) = createPair()
            val sSourceAccess = FakeSourceAccess()
            // Receiver only has 50 bytes available
            val rReceiveStore = FakeReceiveStore(availableSpaceBytes = 50L)

            val fileContent = ByteArray(500) { 0x42 }
            val h1 = SourceHandle("content://media/large.bin")
            val id1 = ItemId(UUID.randomUUID().toString())

            sSourceAccess.registerSource(h1, SourceMetadata("large.bin", "application/octet-stream", 500L), fileContent)

            val batch = TransferBatch(
                id = BatchId(UUID.randomUUID().toString()),
                items = listOf(TransferItem(id1, "large.bin", "application/octet-stream", 500L))
            )

            val sessionId = SessionId(UUID.randomUUID().toString())
            val sender = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(id1 to h1)
            )

            val receiver = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this
            )

            receiver.start()
            sender.start()

            while (receiver.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }

            // User taps accept, but storage preflight detects insufficient space
            receiver.acceptOffer()

            while (!receiver.state.value.isTerminal || !sender.state.value.isTerminal) {
                kotlinx.coroutines.delay(20)
            }

            assertEquals(SessionPhase.Cancelled, receiver.state.value.phase)
            assertEquals(SessionPhase.Cancelled, sender.state.value.phase)

            val sFailure = sender.state.value.failure
            assertTrue(sFailure is TransferFailure.UserRejected)
            assertEquals("INSUFFICIENT_STORAGE", (sFailure as TransferFailure.UserRejected).detail)

            sender.stop()
            receiver.stop()
        }
    }

    @Test
    fun sequentialBatch_itemLevelInsufficientStorage_failsItemGracefully() = runBlocking {
        withTimeout(5000) {
            val (sTransport, rTransport) = createPair()
            val sSourceAccess = FakeSourceAccess()
            val rReceiveStore = FakeReceiveStore(availableSpaceBytes = 1000L).apply {
                throwOnPrepareStaging = ReceiveStoreException(
                    TransferFailure.InsufficientStorage(requiredBytes = 200L, availableBytes = 50L)
                )
            }

            val fileContent = ByteArray(200) { 0x42 }
            val h1 = SourceHandle("content://media/file.bin")
            val id1 = ItemId(UUID.randomUUID().toString())

            sSourceAccess.registerSource(h1, SourceMetadata("file.bin", "application/octet-stream", 200L), fileContent)

            val batch = TransferBatch(
                id = BatchId(UUID.randomUUID().toString()),
                items = listOf(TransferItem(id1, "file.bin", "application/octet-stream", 200L))
            )

            val sessionId = SessionId(UUID.randomUUID().toString())
            val sender = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(id1 to h1)
            )

            val receiver = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this
            )

            receiver.start()
            sender.start()

            while (receiver.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }

            receiver.acceptOffer()

            while (!receiver.state.value.isTerminal) {
                kotlinx.coroutines.delay(20)
            }

            assertEquals(SessionPhase.Failed, receiver.state.value.phase)
            assertTrue(receiver.state.value.failure is TransferFailure.InsufficientStorage)

            sender.stop()
            receiver.stop()
        }
    }

    @Test
    fun sequentialBatch_inFlightDiskExhaustionEnospc_mapsToInsufficientStorage() = runBlocking {
        withTimeout(5000) {
            val (sTransport, rTransport) = createPair()
            val sSourceAccess = FakeSourceAccess()
            val rReceiveStore = FakeReceiveStore().apply {
                throwOnOpenStagingOutput = IOException("No space left on device (ENOSPC)")
            }

            val fileContent = ByteArray(200) { 0x42 }
            val h1 = SourceHandle("content://media/file.bin")
            val id1 = ItemId(UUID.randomUUID().toString())

            sSourceAccess.registerSource(h1, SourceMetadata("file.bin", "application/octet-stream", 200L), fileContent)

            val batch = TransferBatch(
                id = BatchId(UUID.randomUUID().toString()),
                items = listOf(TransferItem(id1, "file.bin", "application/octet-stream", 200L))
            )

            val sessionId = SessionId(UUID.randomUUID().toString())
            val sender = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(id1 to h1)
            )

            val receiver = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this
            )

            receiver.start()
            sender.start()

            while (receiver.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }

            receiver.acceptOffer()

            while (!receiver.state.value.isTerminal) {
                kotlinx.coroutines.delay(20)
            }

            assertEquals(SessionPhase.Failed, receiver.state.value.phase)
            assertTrue(
                "Expected InsufficientStorage on ENOSPC, got: ${receiver.state.value.failure}",
                receiver.state.value.failure is TransferFailure.InsufficientStorage
            )

            sender.stop()
            receiver.stop()
        }
    }

    @Test
    fun sequentialBatch_providerFailsOnFirstItem_failsSessionWithSourceUnavailable() = runBlocking {
        withTimeout(5000) {
            val (sTransport, rTransport) = createPair()
            val sSourceAccess = FakeSourceAccess().apply {
                throwOnOpen = SecurityException("Permission revoked for source file")
            }
            val rReceiveStore = FakeReceiveStore()

            val h1 = SourceHandle("content://media/revoked.bin")
            val id1 = ItemId(UUID.randomUUID().toString())

            val batch = TransferBatch(
                id = BatchId(UUID.randomUUID().toString()),
                items = listOf(TransferItem(id1, "revoked.bin", "application/octet-stream", 100L))
            )

            val sessionId = SessionId(UUID.randomUUID().toString())
            val sender = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(id1 to h1)
            )

            val receiver = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this
            )

            receiver.start()
            sender.start()

            while (receiver.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }

            receiver.acceptOffer()

            while (!sender.state.value.isTerminal) {
                kotlinx.coroutines.delay(20)
            }

            assertEquals(SessionPhase.Failed, sender.state.value.phase)
            assertTrue(
                "Expected SourceUnavailable on provider permission failure, got: ${sender.state.value.failure}",
                sender.state.value.failure is TransferFailure.SourceUnavailable
            )

            sender.stop()
            receiver.stop()
        }
    }

    @Test
    fun sequentialBatch_providerFailsOnMiddleItem_preservesEarlierCommittedFiles() = runBlocking {
        withTimeout(10000) {
            val (sTransport, rTransport) = createPair()
            val sSourceAccess = FakeSourceAccess()
            val rReceiveStore = FakeReceiveStore()

            val f1Bytes = "File 1 valid content".toByteArray(Charsets.UTF_8)
            val f2Bytes = "File 2 will be deleted".toByteArray(Charsets.UTF_8)
            val f3Bytes = "File 3 unreached".toByteArray(Charsets.UTF_8)

            val id1 = ItemId(UUID.randomUUID().toString())
            val id2 = ItemId(UUID.randomUUID().toString())
            val id3 = ItemId(UUID.randomUUID().toString())

            val h1 = SourceHandle("content://media/file1.txt")
            val h2 = SourceHandle("content://media/file2.txt")
            val h3 = SourceHandle("content://media/file3.txt")

            sSourceAccess.registerSource(h1, SourceMetadata("file1.txt", "text/plain", f1Bytes.size.toLong()), f1Bytes)
            sSourceAccess.registerSource(h2, SourceMetadata("file2.txt", "text/plain", f2Bytes.size.toLong()), f2Bytes)
            sSourceAccess.registerSource(h3, SourceMetadata("file3.txt", "text/plain", f3Bytes.size.toLong()), f3Bytes)

            val batch = TransferBatch(
                id = BatchId(UUID.randomUUID().toString()),
                items = listOf(
                    TransferItem(id1, "file1.txt", "text/plain", f1Bytes.size.toLong()),
                    TransferItem(id2, "file2.txt", "text/plain", f2Bytes.size.toLong()),
                    TransferItem(id3, "file3.txt", "text/plain", f3Bytes.size.toLong())
                )
            )

            val sessionId = SessionId(UUID.randomUUID().toString())
            val sender = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender",
                transport = sTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this,
                senderBatch = batch,
                sourceMap = mapOf(id1 to h1, id2 to h2, id3 to h3)
            )

            val receiver = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this
            )

            // Configure item 2's source provider to fail on open (simulating provider revocation/deletion)
            sSourceAccess.throwOnOpenForHandle = { handle ->
                if (handle == h2) java.io.FileNotFoundException("File 2 deleted by user") else null
            }

            receiver.start()
            sender.start()

            while (receiver.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }

            receiver.acceptOffer()

            while (!sender.state.value.isTerminal) {
                kotlinx.coroutines.delay(10)
            }

            assertEquals(SessionPhase.Failed, sender.state.value.phase)
            assertTrue(sender.state.value.failure is TransferFailure.SourceUnavailable)

            // Invariant: Item 1 MUST remain verified and committed on receiver!
            assertEquals(1, receiver.committedFiles.size)
            assertEquals(ItemPhase.Verified, receiver.state.value.itemStates[id1]?.phase)
            assertEquals(ItemPhase.NotAttempted, sender.state.value.itemStates[id3]?.phase)

            sender.stop()
            receiver.stop()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun sequentialBatch_missingSourceHandleInSourceMap_failsFastOnInit() {
        val (sTransport, _) = createPair()
        val sSourceAccess = FakeSourceAccess()
        val rReceiveStore = FakeReceiveStore()

        val id1 = ItemId(UUID.randomUUID().toString())
        val id2 = ItemId(UUID.randomUUID().toString())

        val batch = TransferBatch(
            id = BatchId(UUID.randomUUID().toString()),
            items = listOf(
                TransferItem(id1, "file1.txt", "text/plain", 100L),
                TransferItem(id2, "file2.txt", "text/plain", 100L)
            )
        )

        // Only id1 is in sourceMap, id2 is missing! Must fail fast with IllegalArgumentException
        TransferCoordinator(
            sessionId = SessionId(UUID.randomUUID().toString()),
            role = DeviceRole.Sender,
            peerId = PeerId("remote-peer"),
            localDisplayName = "Sender",
            transport = sTransport,
            sourceAccess = sSourceAccess,
            receiveStore = rReceiveStore,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            senderBatch = batch,
            sourceMap = mapOf(id1 to SourceHandle("content://media/file1.txt"))
        )
    }

    @Test
    fun sequentialBatch_mismatchedItemStartSize_failsClosedWithProtocolOrder() = runBlocking {
        withTimeout(5000) {
            val (sTransport, rTransport) = createPair()
            val sSourceAccess = FakeSourceAccess()
            val rReceiveStore = FakeReceiveStore()

            val f1Bytes = "Offered 100 bytes".toByteArray(Charsets.UTF_8)
            val id1 = ItemId(UUID.randomUUID().toString())
            val h1 = SourceHandle("content://media/file1.txt")

            sSourceAccess.registerSource(h1, SourceMetadata("file1.txt", "text/plain", 100L), f1Bytes)

            val batch = TransferBatch(
                id = BatchId(UUID.randomUUID().toString()),
                items = listOf(TransferItem(id1, "file1.txt", "text/plain", 100L))
            )

            val sessionId = SessionId(UUID.randomUUID().toString())

            val receiver = TransferCoordinator(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this
            )

            receiver.start()

            // Simulate sender HELLO
            val hello = dev.glide.core.protocol.NegotiationValidator.createHello(
                role = DeviceRole.Sender,
                ephemeralPeerId = UUID.randomUUID().toString(),
                displayName = "Sender"
            )
            val helloEnv = Envelope(1, MessageTypes.HELLO, sessionId, MessageId(UUID.randomUUID().toString()), 1L, ProtocolCodec.encodeBody(hello))
            sTransport.sendControl(rTransport.localPeerId, ProtocolCodec.encode(helloEnv))

            while (receiver.state.value.phase != SessionPhase.Offering) {
                kotlinx.coroutines.delay(10)
            }

            // Simulate OFFER_BEGIN, OFFER_PAGE, OFFER_END for 100-byte item
            val begin = dev.glide.core.protocol.OfferBeginBody(batch.id, 1, "100", 1)
            val page = dev.glide.core.protocol.OfferPageBody(batch.id, 0, listOf(
                dev.glide.core.protocol.OfferItemDto(id1, "file1.txt", "text/plain", "100")
            ))
            val end = dev.glide.core.protocol.OfferEndBody(batch.id)

            sTransport.sendControl(rTransport.localPeerId, ProtocolCodec.encode(Envelope(1, MessageTypes.OFFER_BEGIN, sessionId, MessageId(UUID.randomUUID().toString()), 2L, ProtocolCodec.encodeBody(begin))))
            sTransport.sendControl(rTransport.localPeerId, ProtocolCodec.encode(Envelope(1, MessageTypes.OFFER_PAGE, sessionId, MessageId(UUID.randomUUID().toString()), 3L, ProtocolCodec.encodeBody(page))))
            sTransport.sendControl(rTransport.localPeerId, ProtocolCodec.encode(Envelope(1, MessageTypes.OFFER_END, sessionId, MessageId(UUID.randomUUID().toString()), 4L, ProtocolCodec.encodeBody(end))))

            while (receiver.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }

            receiver.acceptOffer()

            while (receiver.state.value.phase != SessionPhase.PreparingItem) {
                kotlinx.coroutines.delay(10)
            }

            // Rogue sender sends ITEM_START claiming 999 bytes instead of 100 bytes!
            val rogueStart = ItemStartBody(
                batchId = batch.id,
                itemId = id1,
                bulkRef = BulkRef("rogue-bulk-1"),
                sizeBytes = "999"
            )
            sTransport.sendControl(rTransport.localPeerId, ProtocolCodec.encode(Envelope(1, MessageTypes.ITEM_START, sessionId, MessageId(UUID.randomUUID().toString()), 5L, ProtocolCodec.encodeBody(rogueStart))))

            while (!receiver.state.value.isTerminal) {
                kotlinx.coroutines.delay(10)
            }

            assertEquals(SessionPhase.Failed, receiver.state.value.phase)
            assertTrue(
                "Expected ProtocolOrder on size mismatch, got: ${receiver.state.value.failure}",
                receiver.state.value.failure is TransferFailure.ProtocolOrder
            )

            receiver.stop()
        }
    }

    @Test
    fun sequentialBatch_safeUnfinishedRetry_preservesEarlierCommittedFiles() = runBlocking {
        withTimeout(15000) {
            val (sTransport1, rTransport1) = createPair()
            val sSourceAccess = FakeSourceAccess()
            val rReceiveStore = FakeReceiveStore()

            val f1Bytes = "File 1 content".toByteArray(Charsets.UTF_8)
            val f2Bytes = "File 2 content".toByteArray(Charsets.UTF_8)

            val id1 = ItemId(UUID.randomUUID().toString())
            val id2 = ItemId(UUID.randomUUID().toString())

            val h1 = SourceHandle("content://media/file1.txt")
            val h2 = SourceHandle("content://media/file2.txt")

            sSourceAccess.registerSource(h1, SourceMetadata("file1.txt", "text/plain", f1Bytes.size.toLong()), f1Bytes)
            sSourceAccess.registerSource(h2, SourceMetadata("file2.txt", "text/plain", f2Bytes.size.toLong()), f2Bytes)

            val fullBatch = TransferBatch(
                id = BatchId(UUID.randomUUID().toString()),
                items = listOf(
                    TransferItem(id1, "file1.txt", "text/plain", f1Bytes.size.toLong()),
                    TransferItem(id2, "file2.txt", "text/plain", f2Bytes.size.toLong())
                )
            )

            val session1Id = SessionId(UUID.randomUUID().toString())
            val sender1 = TransferCoordinator(
                sessionId = session1Id,
                role = DeviceRole.Sender,
                peerId = rTransport1.localPeerId,
                localDisplayName = "Sender",
                transport = sTransport1,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this,
                senderBatch = fullBatch,
                sourceMap = mapOf(id1 to h1, id2 to h2)
            )

            val receiver1 = TransferCoordinator(
                sessionId = session1Id,
                role = DeviceRole.Receiver,
                peerId = sTransport1.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport1,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this
            )

            val item2Started = kotlinx.coroutines.CompletableDeferred<Unit>()
            val pauseItem2 = kotlinx.coroutines.CompletableDeferred<Unit>()
            sSourceAccess.onOpenHook = { handle ->
                if (handle == h2) {
                    item2Started.complete(Unit)
                    pauseItem2.await()
                }
            }

            receiver1.start()
            sender1.start()

            while (receiver1.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }
            receiver1.acceptOffer()

            // Wait until item 2 begins opening (guarantees item 1 is committed and preserved)
            item2Started.await()
            assertEquals(1, rReceiveStore.committedFiles.size)
            assertEquals(ItemPhase.Verified, receiver1.state.value.itemStates[id1]?.phase)

            // Interrupt session 1 before item 2 completes
            sender1.cancel("NETWORK_LOST")
            pauseItem2.complete(Unit)

            while (!receiver1.state.value.isTerminal || !sender1.state.value.isTerminal) {
                kotlinx.coroutines.delay(10)
            }

            // Item 1 is committed and preserved in rReceiveStore!
            assertEquals(1, rReceiveStore.committedFiles.size)
            sender1.stop()
            receiver1.stop()

            // Clear hook for session 2
            sSourceAccess.onOpenHook = null

            // Session 2: Retry only remaining item 2
            val (sTransport2, rTransport2) = createPair()
            val retryBatch = TransferBatch(
                id = BatchId(UUID.randomUUID().toString()),
                items = listOf(TransferItem(id2, "file2.txt", "text/plain", f2Bytes.size.toLong()))
            )
            val session2Id = SessionId(UUID.randomUUID().toString())

            val sender2 = TransferCoordinator(
                sessionId = session2Id,
                role = DeviceRole.Sender,
                peerId = rTransport2.localPeerId,
                localDisplayName = "Sender",
                transport = sTransport2,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this,
                senderBatch = retryBatch,
                sourceMap = mapOf(id2 to h2)
            )

            val receiver2 = TransferCoordinator(
                sessionId = session2Id,
                role = DeviceRole.Receiver,
                peerId = sTransport2.localPeerId,
                localDisplayName = "Receiver",
                transport = rTransport2,
                sourceAccess = sSourceAccess,
                receiveStore = rReceiveStore,
                scope = this
            )

            receiver2.start()
            sender2.start()

            while (receiver2.state.value.phase != SessionPhase.AwaitingConsent) {
                kotlinx.coroutines.delay(10)
            }
            receiver2.acceptOffer()

            while (sender2.state.value.phase != SessionPhase.Completed ||
                receiver2.state.value.phase != SessionPhase.Completed) {
                kotlinx.coroutines.delay(20)
            }

            assertEquals(SessionPhase.Completed, sender2.state.value.phase)
            assertEquals(SessionPhase.Completed, receiver2.state.value.phase)

            // Both item 1 from session 1 and item 2 from retry session are verified and intact!
            assertEquals(2, rReceiveStore.committedFiles.size)

            sender2.stop()
            receiver2.stop()
        }
    }
}
