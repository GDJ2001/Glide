package dev.glide.core

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.SourceHandle
import dev.glide.core.fakes.FakeHistoryStore
import dev.glide.core.fakes.FakeReceiveStore
import dev.glide.core.fakes.FakeSourceAccess
import dev.glide.core.fakes.FakeTransferTransport
import dev.glide.core.ports.ControlMessage
import dev.glide.core.ports.DiscoveryEvent
import dev.glide.core.ports.InputStreamProvider
import dev.glide.core.ports.ItemRecord
import dev.glide.core.ports.SessionRecord
import dev.glide.core.ports.SourceMetadata
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class PortContractTest {

    private val validSessionId = SessionId("123e4567-e89b-42d3-a456-426614174000")
    private val validBatchId = BatchId("123e4567-e89b-42d3-a456-426614174001")
    private val validItemId = ItemId("123e4567-e89b-42d3-a456-426614174002")
    private val validDigest = Sha256Digest("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

    @Test
    fun fakeTransport_connectionAndControlContract() = runBlocking {
        val transport = FakeTransferTransport()
        val peerId = PeerId("peer-alpha")

        transport.initiateConnection(peerId, "Local Device")
        assertEquals(listOf(peerId to "Local Device"), transport.initiatedPeers)

        transport.acceptConnection(peerId)
        assertTrue(transport.acceptedPeers.contains(peerId))

        val peerBeta = PeerId("peer-beta")
        transport.rejectConnection(peerBeta)
        assertTrue(transport.rejectedPeers.contains(peerBeta))

        val payload = "HELLO".toByteArray(Charsets.UTF_8)
        transport.sendControl(peerId, payload)
        assertEquals(1, transport.sentControlMessages.size)
        assertEquals(ControlMessage(peerId, payload), transport.sentControlMessages.first())

        // Test cancellation
        val bulkRef = BulkRef("bulk-99")
        transport.cancelPayload(bulkRef)
        assertTrue(transport.cancelledPayloads.contains(bulkRef))
    }

    @Test
    fun fakeTransport_sendStreamStreamsBytesWithProgress() = runBlocking {
        val transport = FakeTransferTransport()
        val peerId = PeerId("peer-beta")
        val bulkRef = BulkRef("bulk-01")
        val testData = "0123456789".repeat(1000).toByteArray(Charsets.UTF_8) // 10,000 bytes

        val progressList = transport.sendStream(
            peerId = peerId,
            bulkRef = bulkRef,
            streamSource = InputStreamProvider { ByteArrayInputStream(testData) }
        ).toList()

        assertTrue(progressList.isNotEmpty())
        val finalProgress = progressList.last()
        assertTrue(finalProgress.isComplete)
        assertEquals(10_000L, finalProgress.bytesTransferred)
    }

    @Test
    fun fakeSourceAccess_validatesAndOpensSourceStream() = runBlocking {
        val sourceAccess = FakeSourceAccess()
        val handle = SourceHandle("source-sample-1")
        val content = "File content bytes".toByteArray(Charsets.UTF_8)
        val metadata = SourceMetadata("sample.txt", "text/plain", content.size.toLong())

        sourceAccess.registerSource(handle, metadata, content)

        val retrievedMetadata = sourceAccess.validateSource(handle)
        assertEquals("sample.txt", retrievedMetadata.name)
        assertEquals(content.size.toLong(), retrievedMetadata.sizeBytes)

        val stream = sourceAccess.openSource(handle)
        val readBytes = stream.readBytes()
        assertArrayEquals(content, readBytes)
    }

    @Test
    fun fakeReceiveStore_stagesAndCommitsVerifiedFiles() = runBlocking {
        val receiveStore = FakeReceiveStore(availableSpaceBytes = 10_000L)

        assertTrue(receiveStore.hasAvailableSpace(5_000L))
        assertFalse(receiveStore.hasAvailableSpace(15_000L))

        val stagingHandle = receiveStore.prepareStaging(validSessionId, validItemId, 100L)
        assertNotNull(stagingHandle)

        val output = receiveStore.openStagingOutput(stagingHandle)
        val writtenData = "Received binary payload".toByteArray(Charsets.UTF_8)
        output.write(writtenData)
        output.flush()

        val committedHandle = receiveStore.commitVerified(stagingHandle, validDigest)
        assertNotNull(committedHandle)

        val committedData = receiveStore.committedFiles[committedHandle]
        assertNotNull(committedData)
        assertArrayEquals(writtenData, committedData!!.first)
        assertEquals(validDigest, committedData.second)
    }

    @Test
    fun fakeReceiveStore_discardsStagingProperly() = runBlocking {
        val receiveStore = FakeReceiveStore()
        val stagingHandle = receiveStore.prepareStaging(validSessionId, validItemId, 50L)

        receiveStore.discardStaging(stagingHandle)
        assertTrue(receiveStore.discardedHandles.contains(stagingHandle))
    }

    @Test
    fun fakeHistoryStore_recordsAndUpdatesSessionRecord() = runBlocking {
        val historyStore = FakeHistoryStore()
        val record = SessionRecord(
            sessionId = validSessionId,
            batchId = validBatchId,
            role = DeviceRole.Sender,
            peerLabel = "Peer Device",
            startedAtMs = 1_700_000_000L
        )

        historyStore.recordSession(record)
        assertEquals(record, historyStore.getSession(validSessionId))

        historyStore.updateSessionOutcome(validSessionId, "COMPLETED", 1_700_000_100L)
        val updated = historyStore.getSession(validSessionId)
        assertNotNull(updated)
        assertEquals("COMPLETED", updated!!.outcome)
        assertEquals(1_700_000_100L, updated.endedAtMs)
    }
}
