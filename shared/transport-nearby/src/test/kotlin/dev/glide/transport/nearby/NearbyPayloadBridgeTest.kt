package dev.glide.transport.nearby

import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.PeerId
import dev.glide.core.ports.IncomingStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyPayloadBridgeTest {

    private lateinit var fakeClient: FakeNearbyClientWrapper
    private val connectedPeers = mutableSetOf<PeerId>()

    private val peer1 = PeerId("peer-phone-1")
    private val peer2 = PeerId("peer-phone-2")

    @Before
    fun setUp() {
        fakeClient = FakeNearbyClientWrapper()
        connectedPeers.clear()
        connectedPeers.add(peer1)
    }

    private fun TestScope.createBridge(): NearbyPayloadBridge {
        return NearbyPayloadBridge(
            clientWrapper = fakeClient,
            isPeerConnected = { peerId -> connectedPeers.contains(peerId) },
            scope = this
        )
    }

    @Test
    fun `controlMessage within 16KiB sends successfully via Nearby BYTES payload`() = runTest {
        val bridge = createBridge()
        val testBytes = "{\"type\":\"HELLO\",\"major\":1}".toByteArray(Charsets.UTF_8)
        bridge.sendControl(peer1, testBytes)

        assertEquals(1, fakeClient.sentPayloads.size)
        val (endpointId, payload) = fakeClient.sentPayloads[0]
        assertEquals(peer1.value, endpointId)
        assertEquals(Payload.Type.BYTES, payload.type)
        assertArrayEquals(testBytes, payload.asBytes())
    }

    @Test
    fun `controlMessage exceeding 16KiB is rejected with IllegalArgumentException`() = runTest {
        val bridge = createBridge()
        val oversizedBytes = ByteArray(16_385) { 0x41 }
        try {
            bridge.sendControl(peer1, oversizedBytes)
            fail("Expected IllegalArgumentException for oversized control payload")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("exceeds maximum 16384 bytes"))
        }
        assertEquals(0, fakeClient.sentPayloads.size)
    }

    @Test
    fun `empty controlMessage is rejected with IllegalArgumentException`() = runTest {
        val bridge = createBridge()
        try {
            bridge.sendControl(peer1, ByteArray(0))
            fail("Expected IllegalArgumentException for empty control payload")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("must not be empty"))
        }
    }

    @Test
    fun `controlMessage to disconnected peer throws IllegalStateException`() = runTest {
        val bridge = createBridge()
        try {
            bridge.sendControl(peer2, byteArrayOf(1, 2, 3))
            fail("Expected IllegalStateException for disconnected peer")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("is not connected"))
        }
    }

    @Test
    fun `incoming control BYTES payload is emitted to observeControl`() = runTest {
        val bridge = createBridge()
        val receivedControls = mutableListOf<ByteArray>()
        val job = launch {
            bridge.observeControl().collect { msg ->
                if (msg.peerId == peer1) {
                    receivedControls.add(msg.payload)
                }
            }
        }
        runCurrent()

        val controlBytes = "{\"type\":\"HELLO_ACK\"}".toByteArray(Charsets.UTF_8)
        val payload = Payload.fromBytes(controlBytes)
        bridge.payloadCallback.onPayloadReceived(peer1.value, payload)
        runCurrent()

        assertEquals(1, receivedControls.size)
        assertArrayEquals(controlBytes, receivedControls[0])
        job.cancel()
    }

    @Test
    fun `incoming control BYTES from disconnected peer is dropped immediately`() = runTest {
        val bridge = createBridge()
        val receivedControls = mutableListOf<ByteArray>()
        val job = launch {
            bridge.observeControl().collect { msg ->
                receivedControls.add(msg.payload)
            }
        }
        runCurrent()

        val controlBytes = "{\"type\":\"MALICIOUS\"}".toByteArray(Charsets.UTF_8)
        val payload = Payload.fromBytes(controlBytes)
        bridge.payloadCallback.onPayloadReceived(peer2.value, payload) // peer2 is NOT connected
        runCurrent()

        assertEquals(0, receivedControls.size)
        job.cancel()
    }

    @Test
    fun `payloadBeforeMetadata is held in pending map and admitted when ITEM_START metadata arrives`() = runTest {
        val bridge = createBridge()
        val receivedStreams = mutableListOf<IncomingStream>()
        val job = launch {
            bridge.observeIncomingStream().collect {
                receivedStreams.add(it)
            }
        }
        runCurrent()

        val sampleData = "Hello Glide Stream Bytes".toByteArray(Charsets.UTF_8)
        val nearbyPayload = Payload.fromStream(ByteArrayInputStream(sampleData))
        val bulkRef = BulkRef(nearbyPayload.id.toString())

        // 1. STREAM payload arrives before ITEM_START metadata
        bridge.payloadCallback.onPayloadReceived(peer1.value, nearbyPayload)
        runCurrent()

        // Not emitted yet because metadata is not registered
        assertEquals(0, receivedStreams.size)

        // 2. Metadata arrives via ITEM_START
        bridge.registerExpectedStream(bulkRef)
        runCurrent()

        // Now admitted!
        assertEquals(1, receivedStreams.size)
        val incoming = receivedStreams[0]
        assertEquals(peer1, incoming.peerId)
        assertEquals(bulkRef, incoming.bulkRef)

        val readBytes = incoming.stream.readBytes()
        assertArrayEquals(sampleData, readBytes)
        incoming.stream.close()

        job.cancel()
    }

    @Test
    fun `duplicate payload with same ID is rejected and closed`() = runTest {
        val bridge = createBridge()
        val receivedStreams = mutableListOf<IncomingStream>()
        val job = launch {
            bridge.observeIncomingStream().collect {
                receivedStreams.add(it)
            }
        }
        runCurrent()

        val sampleData = "First Stream".toByteArray(Charsets.UTF_8)
        val payload1 = Payload.fromStream(ByteArrayInputStream(sampleData))
        val bulkRef = BulkRef(payload1.id.toString())

        bridge.registerExpectedStream(bulkRef)
        bridge.payloadCallback.onPayloadReceived(peer1.value, payload1)
        runCurrent()

        assertEquals(1, receivedStreams.size)

        // Duplicate callback with same payload ID
        bridge.payloadCallback.onPayloadReceived(peer1.value, payload1)
        runCurrent()

        assertEquals(1, receivedStreams.size)
        job.cancel()
    }

    @Test
    fun `unmappedStream times out after 30s and is cancelled in Nearby`() = runTest {
        val timedBridge = createBridge()

        val sampleData = "Unmapped Stream Data".toByteArray(Charsets.UTF_8)
        val payload = Payload.fromStream(ByteArrayInputStream(sampleData))

        timedBridge.payloadCallback.onPayloadReceived(peer1.value, payload)
        runCurrent()

        assertEquals(0, fakeClient.cancelledPayloads.size)

        // Advance time past the 30s deadline
        advanceTimeBy(30_001L)
        runCurrent()

        // Verify that the unmapped payload was cancelled
        assertTrue(fakeClient.cancelledPayloads.contains(payload.id))
    }

    @Test
    fun `cancelPayload cancels active stream in Nearby and closes stream`() = runTest {
        val bridge = createBridge()
        val sampleData = ByteArray(1024) { 0x20 }
        val bulkRef = bridge.prepareStream { ByteArrayInputStream(sampleData) }

        bridge.cancelPayload(bulkRef)
        runCurrent()

        val payloadId = bulkRef.value.toLong()
        assertTrue(fakeClient.cancelledPayloads.contains(payloadId))
    }

    @Test
    fun `oneStreamMaximum rejects concurrent send streams`() = runTest {
        val bridge = createBridge()
        bridge.prepareStream { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }

        try {
            bridge.prepareStream { ByteArrayInputStream(byteArrayOf(4, 5, 6)) }
            fail("Expected IllegalStateException for concurrent send stream")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Concurrent streams not permitted"))
        }
    }

    @Test
    fun `oneStreamMaximum rejects concurrent incoming streams on receiver`() = runTest {
        val bridge = createBridge()
        val receivedStreams = mutableListOf<IncomingStream>()
        val job = launch {
            bridge.observeIncomingStream().collect {
                receivedStreams.add(it)
            }
        }
        runCurrent()

        val stream1 = Payload.fromStream(ByteArrayInputStream(byteArrayOf(1, 2)))
        val bulkRef1 = BulkRef(stream1.id.toString())
        bridge.registerExpectedStream(bulkRef1)
        bridge.payloadCallback.onPayloadReceived(peer1.value, stream1)
        runCurrent()

        assertEquals(1, receivedStreams.size)

        // Second stream arrives while first is active
        val stream2 = Payload.fromStream(ByteArrayInputStream(byteArrayOf(3, 4)))
        bridge.payloadCallback.onPayloadReceived(peer1.value, stream2)
        runCurrent()

        // Second stream was rejected and cancelled in Nearby
        assertEquals(1, receivedStreams.size)
        assertTrue(fakeClient.cancelledPayloads.contains(stream2.id))

        job.cancel()
    }

    @Test
    fun `readyHandshake delays sendStream until markItemReady is called`() = runTest {
        val bridge = createBridge()
        val testData = "Test Payload Stream Data".toByteArray(Charsets.UTF_8)
        val bulkRef = bridge.prepareStream { ByteArrayInputStream(testData) }

        var sendCompleted = false
        val sendJob = launch {
            bridge.sendStream(peer1, bulkRef) { ByteArrayInputStream(testData) }.collect { progress ->
                if (progress.isComplete) {
                    sendCompleted = true
                }
            }
        }
        runCurrent()

        // Not sent yet because READY handshake has not occurred
        assertEquals(0, fakeClient.sentPayloads.size)

        // Receiver confirms ITEM_READY
        bridge.markItemReady(bulkRef)
        runCurrent()

        // Now sent!
        assertEquals(1, fakeClient.sentPayloads.size)
        val (ep, p) = fakeClient.sentPayloads[0]
        assertEquals(peer1.value, ep)
        assertEquals(bulkRef.value.toLong(), p.id)

        // Simulate progress update from Nearby
        val update = PayloadTransferUpdate.Builder()
            .setPayloadId(p.id)
            .setBytesTransferred(testData.size.toLong())
            .setTotalBytes(testData.size.toLong())
            .setStatus(PayloadTransferUpdate.Status.SUCCESS)
            .build()
        bridge.payloadCallback.onPayloadTransferUpdate(peer1.value, update)
        runCurrent()

        assertTrue(sendCompleted)
        sendJob.cancel()
    }

    @Test
    fun `disconnect cleans all streams and cancels active payloads`() = runTest {
        val bridge = createBridge()
        val streamData = byteArrayOf(1, 2, 3)
        val bulkRef = bridge.prepareStream { ByteArrayInputStream(streamData) }

        bridge.onDisconnected(peer1)
        runCurrent()

        val payloadId = bulkRef.value.toLong()
        assertTrue(fakeClient.cancelledPayloads.contains(payloadId))
    }

    @Test
    fun `exactLength streams exact bytes and normalizes completion flags`() = runTest {
        val bridge = createBridge()
        val expectedLength = 512
        val streamData = ByteArray(expectedLength) { it.toByte() }
        val bulkRef = bridge.prepareStream { ByteArrayInputStream(streamData) }

        val progressList = mutableListOf<dev.glide.core.ports.BulkProgress>()
        val sendJob = launch {
            bridge.sendStream(peer1, bulkRef) { ByteArrayInputStream(streamData) }.collect {
                progressList.add(it)
            }
        }
        runCurrent()

        // 1. Enforce READY handshake
        bridge.markItemReady(bulkRef)
        runCurrent()

        val payloadId = bulkRef.value.toLong()

        // 2. In-progress update (256 of 512 bytes)
        val midUpdate = PayloadTransferUpdate.Builder()
            .setPayloadId(payloadId)
            .setBytesTransferred(256L)
            .setTotalBytes(512L)
            .setStatus(PayloadTransferUpdate.Status.IN_PROGRESS)
            .build()
        bridge.payloadCallback.onPayloadTransferUpdate(peer1.value, midUpdate)
        runCurrent()

        assertEquals(1, progressList.size)
        assertEquals(256L, progressList[0].bytesTransferred)
        assertFalse(progressList[0].isComplete)

        // 3. Completion update (512 of 512 bytes with SUCCESS)
        val finalUpdate = PayloadTransferUpdate.Builder()
            .setPayloadId(payloadId)
            .setBytesTransferred(512L)
            .setTotalBytes(512L)
            .setStatus(PayloadTransferUpdate.Status.SUCCESS)
            .build()
        bridge.payloadCallback.onPayloadTransferUpdate(peer1.value, finalUpdate)
        runCurrent()

        assertEquals(2, progressList.size)
        assertEquals(512L, progressList[1].bytesTransferred)
        assertTrue(progressList[1].isComplete)

        sendJob.cancel()
    }
}
