package dev.glide.core.coordinator

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.MessageId
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
import dev.glide.core.ports.StreamExpectation
import dev.glide.core.ports.TransferTransport
import dev.glide.core.protocol.Envelope
import dev.glide.core.protocol.MessageTypes
import dev.glide.core.protocol.PingBody
import dev.glide.core.protocol.ProtocolCodec
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
import java.security.MessageDigest
import java.util.UUID

/**
 * Adversarial protocol and coordinator security test suite enforcing Section 20 requirements:
 * - 4. Same peer with another session ID / stream: rejected before reducer delivery/staging, stream disposed.
 * - 5. Pre-consent payload or unsolicited second stream: no writes or publication; bounded reject/close.
 * - 8. Digest/length/receipt mismatch: no success until actual on-disk commit; receiver retains verified content while sender reports unconfirmed.
 */
class CoordinatorSecurityAdversarialTest {

    private class SecurityTestTransport(val localPeerId: PeerId) : TransferTransport {
        lateinit var remote: SecurityTestTransport

        val controlChannel = Channel<ControlMessage>(Channel.UNLIMITED)
        val incomingStreamChannel = Channel<IncomingStream>(Channel.UNLIMITED)
        val bulkProgressChannel = Channel<BulkProgress>(Channel.UNLIMITED)
        val connectionEventsChannel = Channel<ConnectionEvent>(Channel.UNLIMITED)

        val preparedStreams = mutableMapOf<BulkRef, InputStream>()
        var dropControlMessageType: String? = null
        var corruptDataStream: Boolean = false
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
            val env = try { ProtocolCodec.decode(payload) } catch (_: Exception) { null }
            if (dropControlMessageType != null && env?.type == dropControlMessageType) {
                return
            }
            remote.controlChannel.send(ControlMessage(localPeerId, payload))
        }

        override fun observeControl(): Flow<ControlMessage> = controlChannel.receiveAsFlow()

        override suspend fun prepareStream(streamSource: InputStreamProvider): BulkRef {
            streamCounter++
            val ref = BulkRef("loopback-bulk-$streamCounter")
            preparedStreams[ref] = streamSource.openStream()
            return ref
        }

        override suspend fun markItemReady(bulkRef: BulkRef) {}
        override suspend fun registerExpectedStream(bulkRef: BulkRef) {}
        override suspend fun registerExpectedStream(expectation: StreamExpectation) {}

        override suspend fun sendStream(
            peerId: PeerId,
            bulkRef: BulkRef
        ): Flow<BulkProgress> = flow {
            if (sendStreamDelayMs > 0) {
                delay(sendStreamDelayMs)
            }
            val stream = preparedStreams.remove(bulkRef)
                ?: throw IllegalStateException("No prepared stream for bulkRef ${bulkRef.value}")
            val rawBytes = stream.readAllBytes()

            val bytesToSend = if (corruptDataStream && rawBytes.isNotEmpty()) {
                val corrupted = rawBytes.copyOf()
                corrupted[0] = (corrupted[0] + 1).toByte()
                corrupted
            } else {
                rawBytes
            }

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
        override suspend fun cancelPayload(bulkRef: BulkRef) {}
        override suspend fun disconnect(peerId: PeerId) {
            remote.connectionEventsChannel.send(ConnectionEvent.Disconnected(localPeerId))
        }
    }

    private fun createPair(): Pair<SecurityTestTransport, SecurityTestTransport> {
        val sPeer = PeerId("sender-peer")
        val rPeer = PeerId("receiver-peer")
        val sTransport = SecurityTestTransport(sPeer)
        val rTransport = SecurityTestTransport(rPeer)
        sTransport.remote = rTransport
        rTransport.remote = sTransport
        return sTransport to rTransport
    }

    private fun createBatch(name: String, content: ByteArray): Pair<TransferBatch, Map<ItemId, SourceHandle>> {
        val itemId = ItemId(UUID.randomUUID().toString())
        val handle = SourceHandle("source-$name")
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(content).joinToString("") { "%02x".format(it) }
        val item = TransferItem(
            id = itemId,
            name = name,
            mimeType = "text/plain",
            sizeBytes = content.size.toLong(),
            digest = Sha256Digest(digest)
        )
        val batch = TransferBatch(
            id = BatchId(UUID.randomUUID().toString()),
            items = listOf(item)
        )
        return batch to mapOf(itemId to handle)
    }

    @Test
    fun testWrongSessionId_rejectedBeforeSequencerOrReducerDelivery(): Unit = runBlocking {
        withTimeout(10_000) {
            val (sTransport, rTransport) = createPair()
            val testSessionId = SessionId(UUID.randomUUID().toString())
            val wrongSessionId = SessionId(UUID.randomUUID().toString())

            val receiveStore = FakeReceiveStore()
            val historyStore = FakeHistoryStore()
            val sourceAccess = FakeSourceAccess()

            val fileContent = "valid payload".toByteArray()
            val (batch, sourceMap) = createBatch("test.txt", fileContent)
            val sourceHandle = sourceMap.values.first()
            sourceAccess.registerSource(sourceHandle, SourceMetadata("test.txt", "text/plain", fileContent.size.toLong()), fileContent)

            val senderCoordinator = TransferCoordinator(
                sessionId = testSessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender Device",
                transport = sTransport,
                sourceAccess = sourceAccess,
                receiveStore = FakeReceiveStore(),
                historyStore = FakeHistoryStore(),
                scope = this,
                senderBatch = batch,
                sourceMap = sourceMap
            )

            val receiverCoordinator = TransferCoordinator(
                sessionId = testSessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver Device",
                transport = rTransport,
                sourceAccess = sourceAccess,
                receiveStore = receiveStore,
                historyStore = historyStore,
                scope = this
            )

            receiverCoordinator.start()
            senderCoordinator.start()

            // Wait until session is established and receiver is awaiting consent
            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                delay(10)
            }

            // Now inject malicious frame with WRONG sessionId
            val pingBody = PingBody(UUID.randomUUID().toString())
            val maliciousEnv = Envelope(
                v = 1,
                type = MessageTypes.PING,
                messageId = MessageId(UUID.randomUUID().toString()),
                seq = 999,
                sessionId = wrongSessionId, // Mismatched sessionId
                body = ProtocolCodec.encodeBody(pingBody)
            )
            rTransport.controlChannel.send(ControlMessage(sTransport.localPeerId, ProtocolCodec.encode(maliciousEnv)))

            // Receiver state must fail closed with ProtocolOrder failure
            while (!receiverCoordinator.state.value.isTerminal) {
                delay(10)
            }

            val finalState = receiverCoordinator.state.value
            assertEquals(SessionPhase.Failed, finalState.phase)
            val failure = finalState.failure
            assertTrue("Failure must be ProtocolOrder: $failure", failure is TransferFailure.ProtocolOrder)
            assertTrue(
                "Failure message must identify session mismatch",
                failure?.message?.contains("does not match active sessionId") == true
            )
            assertEquals("Zero files committed", 0, receiverCoordinator.committedFiles.size)

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }

    @Test
    fun testPreConsentStream_rejectedAndZeroBytesStaged(): Unit = runBlocking {
        withTimeout(10_000) {
            val (sTransport, rTransport) = createPair()
            val testSessionId = SessionId(UUID.randomUUID().toString())

            val receiveStore = FakeReceiveStore()
            val historyStore = FakeHistoryStore()
            val sourceAccess = FakeSourceAccess()

            val fileContent = "valid payload".toByteArray()
            val (batch, sourceMap) = createBatch("test.txt", fileContent)
            val sourceHandle = sourceMap.values.first()
            sourceAccess.registerSource(sourceHandle, SourceMetadata("test.txt", "text/plain", fileContent.size.toLong()), fileContent)

            val senderCoordinator = TransferCoordinator(
                sessionId = testSessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender Device",
                transport = sTransport,
                sourceAccess = sourceAccess,
                receiveStore = FakeReceiveStore(),
                historyStore = FakeHistoryStore(),
                scope = this,
                senderBatch = batch,
                sourceMap = sourceMap
            )

            val receiverCoordinator = TransferCoordinator(
                sessionId = testSessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver Device",
                transport = rTransport,
                sourceAccess = sourceAccess,
                receiveStore = receiveStore,
                historyStore = historyStore,
                scope = this
            )

            receiverCoordinator.start()
            senderCoordinator.start()

            // Wait until receiver is awaiting consent (NO acceptOffer called)
            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                delay(10)
            }

            // Before user accepts consent, attacker sends an incoming bulk stream directly
            val unconsentedBulkRef = BulkRef("bulk-unconsented-123")
            val streamPayload = "Malicious unconsented file content".toByteArray()
            rTransport.incomingStreamChannel.send(
                IncomingStream(sTransport.localPeerId, unconsentedBulkRef, ByteArrayInputStream(streamPayload))
            )

            // Receiver must fail closed
            while (!receiverCoordinator.state.value.isTerminal) {
                delay(10)
            }

            val finalState = receiverCoordinator.state.value
            assertEquals(SessionPhase.Failed, finalState.phase)
            assertTrue(
                "Failure must be ProtocolOrder: ${finalState.failure}",
                finalState.failure is TransferFailure.ProtocolOrder
            )
            assertTrue(
                "Failure must mention before explicit offer acceptance",
                finalState.failure?.message?.contains("before explicit offer acceptance") == true
            )

            // Ensure ZERO files were staged or committed
            assertEquals("No files may be committed before consent", 0, receiverCoordinator.committedFiles.size)
            assertEquals("No staged files may be prepared", 0, receiveStore.preparedStaging.size)

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }

    @Test
    fun testUnregisteredBulkRef_rejectedWithoutStaging(): Unit = runBlocking {
        withTimeout(10_000) {
            val (sTransport, rTransport) = createPair()
            val testSessionId = SessionId(UUID.randomUUID().toString())

            val receiveStore = FakeReceiveStore()
            val historyStore = FakeHistoryStore()
            val sourceAccess = FakeSourceAccess()

            val fileContent = "valid payload".toByteArray()
            val (batch, sourceMap) = createBatch("test.txt", fileContent)
            val sourceHandle = sourceMap.values.first()
            sourceAccess.registerSource(sourceHandle, SourceMetadata("test.txt", "text/plain", fileContent.size.toLong()), fileContent)

            // Set delay on stream send so we can inject an unsolicited stream while active
            sTransport.sendStreamDelayMs = 200L

            val senderCoordinator = TransferCoordinator(
                sessionId = testSessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender Device",
                transport = sTransport,
                sourceAccess = sourceAccess,
                receiveStore = FakeReceiveStore(),
                historyStore = FakeHistoryStore(),
                scope = this,
                senderBatch = batch,
                sourceMap = sourceMap
            )

            val receiverCoordinator = TransferCoordinator(
                sessionId = testSessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver Device",
                transport = rTransport,
                sourceAccess = sourceAccess,
                receiveStore = receiveStore,
                historyStore = historyStore,
                scope = this
            )

            receiverCoordinator.start()
            senderCoordinator.start()

            // Wait until receiver is awaiting consent
            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                delay(10)
            }

            // Accept offer
            receiverCoordinator.acceptOffer()

            // Wait until receiver is in PreparingItem or TransferringItem
            while (receiverCoordinator.state.value.phase != SessionPhase.PreparingItem &&
                receiverCoordinator.state.value.phase != SessionPhase.TransferringItem) {
                delay(10)
            }

            // Attacker pushes stream with completely UNKNOWN bulkRef (not matching item's bulkRef)
            val randomBulkRef = BulkRef("unknown-bulk-ref-999")
            rTransport.incomingStreamChannel.send(
                IncomingStream(sTransport.localPeerId, randomBulkRef, ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)))
            )

            while (!receiverCoordinator.state.value.isTerminal) {
                delay(10)
            }

            val finalState = receiverCoordinator.state.value
            assertEquals(SessionPhase.Failed, finalState.phase)
            assertTrue(
                "Failure must be ProtocolOrder: ${finalState.failure}",
                finalState.failure is TransferFailure.ProtocolOrder
            )
            assertTrue(
                "Failure must mention unknown bulkRef",
                finalState.failure?.message?.contains("Unknown incoming stream bulkRef") == true
            )
            assertEquals("Zero files committed", 0, receiverCoordinator.committedFiles.size)

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }

    @Test
    fun testDigestMismatch_stagingDiscardedAndNeverCommitted(): Unit = runBlocking {
        withTimeout(10_000) {
            val (sTransport, rTransport) = createPair()
            val testSessionId = SessionId(UUID.randomUUID().toString())

            // Corrupt stream bytes in transit between sender and receiver
            sTransport.corruptDataStream = true

            val receiveStore = FakeReceiveStore()
            val historyStore = FakeHistoryStore()
            val sourceAccess = FakeSourceAccess()

            val fileContent = "Original authentic uncorrupted bytes".toByteArray()
            val (batch, sourceMap) = createBatch("corrupted.txt", fileContent)
            val handle = sourceMap.values.first()
            sourceAccess.registerSource(handle, SourceMetadata("corrupted.txt", "text/plain", fileContent.size.toLong()), fileContent)

            val senderCoordinator = TransferCoordinator(
                sessionId = testSessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender Device",
                transport = sTransport,
                sourceAccess = sourceAccess,
                receiveStore = FakeReceiveStore(),
                historyStore = FakeHistoryStore(),
                scope = this,
                senderBatch = batch,
                sourceMap = sourceMap
            )

            val receiverCoordinator = TransferCoordinator(
                sessionId = testSessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver Device",
                transport = rTransport,
                sourceAccess = sourceAccess,
                receiveStore = receiveStore,
                historyStore = historyStore,
                scope = this
            )

            receiverCoordinator.start()
            senderCoordinator.start()

            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                delay(10)
            }
            receiverCoordinator.acceptOffer()

            // Wait until both sides enter Failed terminal state
            while (receiverCoordinator.state.value.phase != SessionPhase.Failed ||
                senderCoordinator.state.value.phase != SessionPhase.Failed) {
                delay(20)
            }

            assertEquals(SessionPhase.Failed, receiverCoordinator.state.value.phase)
            assertEquals(SessionPhase.Failed, senderCoordinator.state.value.phase)

            // Staging files must be discarded on digest mismatch, never committed
            assertEquals("No files must be committed on digest mismatch", 0, receiverCoordinator.committedFiles.size)
            assertTrue("ReceiveStore committedFiles must be empty", receiveStore.committedFiles.isEmpty())
            assertTrue("Staging handle must have been discarded", receiveStore.discardedHandles.isNotEmpty())

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }

    @Test
    fun testLostFinalReceipt_receiverRetainsVerifiedWhileSenderReportsUnconfirmed(): Unit = runBlocking {
        withTimeout(10_000) {
            val (sTransport, rTransport) = createPair()
            val testSessionId = SessionId(UUID.randomUUID().toString())

            // Drop BATCH_RESULT from receiver to sender
            rTransport.dropControlMessageType = MessageTypes.BATCH_RESULT

            val receiveStore = FakeReceiveStore()
            val historyStore = FakeHistoryStore()
            val sourceAccess = FakeSourceAccess()

            val fileContent = "Important confidential documents".toByteArray()
            val (batch, sourceMap) = createBatch("receipt_test.txt", fileContent)
            val handle = sourceMap.values.first()
            sourceAccess.registerSource(handle, SourceMetadata("receipt_test.txt", "text/plain", fileContent.size.toLong()), fileContent)

            val senderCoordinator = TransferCoordinator(
                sessionId = testSessionId,
                role = DeviceRole.Sender,
                peerId = rTransport.localPeerId,
                localDisplayName = "Sender Device",
                transport = sTransport,
                sourceAccess = sourceAccess,
                receiveStore = FakeReceiveStore(),
                historyStore = historyStore,
                scope = this,
                senderBatch = batch,
                sourceMap = sourceMap
            )

            val receiverCoordinator = TransferCoordinator(
                sessionId = testSessionId,
                role = DeviceRole.Receiver,
                peerId = sTransport.localPeerId,
                localDisplayName = "Receiver Device",
                transport = rTransport,
                sourceAccess = sourceAccess,
                receiveStore = receiveStore,
                historyStore = historyStore,
                scope = this
            )

            receiverCoordinator.start()
            senderCoordinator.start()

            while (receiverCoordinator.state.value.phase != SessionPhase.AwaitingConsent) {
                delay(10)
            }
            receiverCoordinator.acceptOffer()

            // Receiver completes file verification and commits to storage
            while (receiverCoordinator.state.value.phase != SessionPhase.Completed) {
                delay(20)
            }

            // Receiver has successfully committed verified file
            assertEquals("Receiver must commit verified file", 1, receiverCoordinator.committedFiles.size)
            assertEquals("Receiver session must be Completed", SessionPhase.Completed, receiverCoordinator.state.value.phase)

            // Simulate disconnect / network drop before sender gets BATCH_RESULT receipt
            rTransport.disconnect(sTransport.localPeerId)

            while (!senderCoordinator.state.value.isTerminal) {
                delay(20)
            }

            val senderState = senderCoordinator.state.value
            assertTrue("Sender must be in a terminal state", senderState.isTerminal)
            assertEquals("Sender session must report unconfirmed delivery on lost receipt", SessionPhase.DeliveryUnconfirmed, senderState.phase)
            assertTrue("Sender failure must be DeliveryUnconfirmed", senderState.failure is TransferFailure.DeliveryUnconfirmed)

            // Receiver retained the verified committed file even though sender's confirmation was lost
            assertEquals(1, receiverCoordinator.committedFiles.size)
            assertTrue("Receiver committed file must match original content",
                receiveStore.committedFiles.values.any { String(it.first) == String(fileContent) }
            )

            senderCoordinator.stop()
            receiverCoordinator.stop()
        }
    }
}
