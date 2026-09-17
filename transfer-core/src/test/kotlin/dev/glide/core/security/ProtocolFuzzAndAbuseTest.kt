package dev.glide.core.security

import dev.glide.core.coordinator.ItemPhase
import dev.glide.core.coordinator.ReducerEvent
import dev.glide.core.coordinator.SessionPhase
import dev.glide.core.coordinator.TransferCoordinator
import dev.glide.core.domain.BatchId
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.CommittedFileHandle
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.MessageId
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.StagingHandle
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferFailure
import dev.glide.core.domain.TransferItem
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
import dev.glide.core.protocol.Envelope
import dev.glide.core.protocol.MessageTypes
import dev.glide.core.protocol.ProtocolCodec
import dev.glide.core.stream.StreamHasher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Random
import java.util.UUID

class ProtocolFuzzAndAbuseTest {

    private class MockTransport : TransferTransport {
        val controlChannel = Channel<ControlMessage>(Channel.UNLIMITED)
        val incomingStreamChannel = Channel<IncomingStream>(Channel.UNLIMITED)

        override suspend fun startDiscovery(serviceId: String): Flow<DiscoveryEvent> = emptyFlow()
        override suspend fun stopDiscovery() {}
        override suspend fun startAdvertising(serviceId: String, localDeviceName: String): Flow<AdvertisingEvent> = emptyFlow()
        override suspend fun stopAdvertising() {}
        override suspend fun initiateConnection(peerId: PeerId, localDeviceName: String) {}
        override suspend fun acceptConnection(peerId: PeerId) {}
        override suspend fun rejectConnection(peerId: PeerId) {}
        override fun observeConnectionEvents(): Flow<ConnectionEvent> = emptyFlow()
        override suspend fun sendControl(peerId: PeerId, payload: ByteArray) {}
        override fun observeControl(): Flow<ControlMessage> = controlChannel.receiveAsFlow()
        override suspend fun prepareStream(streamSource: InputStreamProvider): BulkRef = BulkRef("test-bulk")
        override suspend fun markItemReady(bulkRef: BulkRef) {}
        override suspend fun registerExpectedStream(bulkRef: BulkRef) {}
        override suspend fun cancelPayload(bulkRef: BulkRef) {}
        override suspend fun disconnect(peerId: PeerId) {}
        override suspend fun sendStream(peerId: PeerId, bulkRef: BulkRef, streamSource: InputStreamProvider): Flow<BulkProgress> = emptyFlow()
        override fun observeIncomingStream(): Flow<IncomingStream> = incomingStreamChannel.receiveAsFlow()
        override fun observeBulkProgress(): Flow<BulkProgress> = emptyFlow()
    }

    private class MockReceiveStore : ReceiveStore {
        val stagingBuffers = mutableMapOf<StagingHandle, ByteArrayOutputStream>()

        override suspend fun hasAvailableSpace(requiredBytes: Long): Boolean = true

        override suspend fun prepareStaging(
            sessionId: SessionId,
            itemId: ItemId,
            expectedBytes: Long,
            displayName: String,
            mimeType: String,
            relativePath: String?
        ): StagingHandle {
            val handle = StagingHandle("staging-${itemId.value}")
            stagingBuffers[handle] = ByteArrayOutputStream()
            return handle
        }

        override suspend fun openStagingOutput(handle: StagingHandle): java.io.OutputStream {
            return stagingBuffers[handle] ?: ByteArrayOutputStream().also { stagingBuffers[handle] = it }
        }

        override suspend fun commitVerified(handle: StagingHandle, digest: Sha256Digest): CommittedFileHandle {
            return CommittedFileHandle("committed-${handle.value}")
        }

        override suspend fun discardStaging(handle: StagingHandle) {
            stagingBuffers.remove(handle)
        }
    }

    private class MockSourceAccess : SourceAccess {
        override suspend fun validateSource(handle: SourceHandle): SourceMetadata =
            SourceMetadata("test", "application/octet-stream", 0L)
        override suspend fun openSource(handle: SourceHandle): InputStream = ByteArrayInputStream(ByteArray(0))
    }

    @Test
    fun fuzzCodecWithMutatedEnvelopes() {
        val random = Random(42) // Repeatable deterministic seed
        val validJson = """{"v":1,"type":"ping","sessionId":"550e8400-e29b-41d4-a716-446655440000","messageId":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d","seq":1,"body":{"test":true}}"""
        val validBytes = validJson.toByteArray(Charsets.UTF_8)

        var handledExceptions = 0

        // Run 500 deterministic fuzz mutations
        for (i in 0 until 500) {
            val mutated = validBytes.copyOf()
            val mutationType = random.nextInt(6)

            when (mutationType) {
                0 -> {
                    // Random bit flips
                    val pos = random.nextInt(mutated.size)
                    mutated[pos] = (mutated[pos].toInt() xor (1 shl random.nextInt(8))).toByte()
                }
                1 -> {
                    // Random truncation
                    val cutLen = random.nextInt(mutated.size)
                    val truncated = mutated.copyOf(cutLen)
                    try {
                        ProtocolCodec.decode(truncated)
                    } catch (_: Exception) {
                        handledExceptions++
                    }
                    continue
                }
                2 -> {
                    // Random byte insertion
                    val insertPos = random.nextInt(mutated.size)
                    val extra = ByteArray(mutated.size + 1)
                    System.arraycopy(mutated, 0, extra, 0, insertPos)
                    extra[insertPos] = random.nextInt(256).toByte()
                    System.arraycopy(mutated, insertPos, extra, insertPos + 1, mutated.size - insertPos)
                    try {
                        ProtocolCodec.decode(extra)
                    } catch (_: Exception) {
                        handledExceptions++
                    }
                    continue
                }
                3 -> {
                    // Inject null bytes
                    val pos = random.nextInt(mutated.size)
                    mutated[pos] = 0.toByte()
                }
                4 -> {
                    // Inject extreme length / garbage
                    val garbage = ByteArray(random.nextInt(1024) + 16_384) { random.nextInt(256).toByte() }
                    try {
                        ProtocolCodec.decode(garbage)
                    } catch (_: Exception) {
                        handledExceptions++
                    }
                    continue
                }
                5 -> {
                    // Mutate sequence number to negative in JSON string
                    val negativeSeqJson = validJson.replace("\"seq\":1", "\"seq\":-${random.nextInt(1000) + 1}")
                    try {
                        ProtocolCodec.decode(negativeSeqJson.toByteArray(Charsets.UTF_8))
                    } catch (_: Exception) {
                        handledExceptions++
                    }
                    continue
                }
            }

            try {
                ProtocolCodec.decode(mutated)
            } catch (_: Exception) {
                // Gracefully rejected by fail-closed codec
                handledExceptions++
            }
        }

        assertTrue("Fuzzer must exercise error paths safely", handledExceptions > 0)
    }

    @Test
    fun pathTraversalPayloadRejectedByIdentifiers() {
        val maliciousPathStrings = listOf(
            "../../etc/passwd",
            "..\\..\\Windows\\System32\\calc.exe",
            "/etc/shadow",
            "C:\\boot.ini",
            "test\u0000file.bin",
            "normal/../../../root/.ssh/id_rsa",
            "../secret.key"
        )

        for (badPath in maliciousPathStrings) {
            try {
                ItemId(badPath)
                fail("ItemId creation with traversal '$badPath' should fail validation")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("canonical lowercase UUIDv4") == true)
            }

            try {
                SessionId(badPath)
                fail("SessionId creation with traversal '$badPath' should fail validation")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("canonical lowercase UUIDv4") == true)
            }
        }
    }

    @Test
    fun oversizedItemRejectedByLimits() {
        val hugeSizeBytes = ProtocolLimits.MAX_ITEM_BYTES + 1L

        try {
            TransferItem(
                id = ItemId("550e8400-e29b-41d4-a716-446655440000"),
                name = "huge.iso",
                mimeType = "application/octet-stream",
                sizeBytes = hugeSizeBytes
            )
            fail("TransferItem with size > 64 GiB must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("sizeBytes") == true)
        }
    }

    @Test
    fun streamExceedingOfferedLengthFailsClosed() = runBlocking {
        val expectedBytes = 10L
        val extraData = ByteArray(15) { 0x42.toByte() } // 5 extra bytes
        val input = ByteArrayInputStream(extraData)
        val output = ByteArrayOutputStream()

        try {
            StreamHasher.copyAndHash(
                inputStream = input,
                outputStream = output,
                expectedBytes = expectedBytes
            )
            fail("Stream exceeding expected bytes must throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Stream exceeded expected size") == true)
        }
    }

    @Test
    fun rateLimitingFloodThrowsTerminalLimitExceeded() = runBlocking {
        val transport = MockTransport()
        val receiveStore = MockReceiveStore()
        val coordinator = TransferCoordinator(
            sessionId = SessionId("550e8400-e29b-41d4-a716-446655440000"),
            role = DeviceRole.Receiver,
            peerId = PeerId("spammer-peer"),
            localDisplayName = "Receiver",
            transport = transport,
            sourceAccess = MockSourceAccess(),
            receiveStore = receiveStore,
            scope = this
        )

        coordinator.start()

        // Flood 100 control messages rapidly (burst is only 40)
        for (i in 1..100) {
            val env = Envelope(
                v = 1,
                type = MessageTypes.PING,
                sessionId = SessionId("550e8400-e29b-41d4-a716-446655440000"),
                messageId = MessageId("550e8400-e29b-41d4-a716-44665544${String.format(java.util.Locale.US, "%04d", i)}"),
                seq = i.toLong(),
                body = buildJsonObject { put("nonce", UUID.randomUUID().toString()) }
            )
            val bytes = ProtocolCodec.encode(env)
            transport.controlChannel.send(ControlMessage(PeerId("spammer-peer"), bytes))
        }

        // Allow collector coroutines to process
        var attempts = 0
        while (!coordinator.state.value.isTerminal && attempts < 50) {
            yield()
            kotlinx.coroutines.delay(20)
            attempts++
        }

        // State must transition to terminal Failed with LimitExceeded
        val finalState = coordinator.state.value
        assertTrue("Session must be terminal after flood", finalState.isTerminal)
        assertEquals(SessionPhase.Failed, finalState.phase)
        val failure = finalState.failure
        assertTrue("Failure must be LimitExceeded, but was: $failure", failure is TransferFailure.LimitExceeded)

        coordinator.stop()
    }

    @Test
    fun incomingStreamBeforeOfferConsentIsRejected() = runBlocking {
        val transport = MockTransport()
        val receiveStore = MockReceiveStore()
        val coordinator = TransferCoordinator(
            sessionId = SessionId("550e8400-e29b-41d4-a716-446655440000"),
            role = DeviceRole.Receiver,
            peerId = PeerId("attacker-peer"),
            localDisplayName = "Receiver",
            transport = transport,
            sourceAccess = MockSourceAccess(),
            receiveStore = receiveStore,
            scope = this
        )

        coordinator.start()

        // Attacker sends an incoming stream packet before any offer acceptance
        transport.incomingStreamChannel.send(
            IncomingStream(
                peerId = PeerId("attacker-peer"),
                bulkRef = BulkRef("unsolicited-bulk"),
                stream = ByteArrayInputStream(ByteArray(100))
            )
        )

        var attempts = 0
        while (!coordinator.state.value.isTerminal && attempts < 50) {
            yield()
            kotlinx.coroutines.delay(20)
            attempts++
        }

        // Receiver state must reject with ProtocolOrder failure
        val state = coordinator.state.value
        assertTrue("Session must terminate on unsolicited stream before consent", state.isTerminal)
        assertEquals(SessionPhase.Failed, state.phase)
        assertTrue("Failure must be ProtocolOrder", state.failure is TransferFailure.ProtocolOrder)

        coordinator.stop()
    }
}
