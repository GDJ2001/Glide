package dev.glide.core.ports

import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SessionId
import dev.glide.core.fakes.FakeTransferTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StreamExpectationTest {

    private val testSessionId = SessionId("00000000-0000-4000-8000-000000000001")
    private val testItemId = ItemId("00000000-0000-4000-8000-000000000002")
    private val testBulkRef = BulkRef("00000000-0000-4000-8000-000000000003")

    @Test
    fun testStreamExpectation_validConstruction() {
        val expectation = StreamExpectation(
            sessionId = testSessionId,
            itemId = testItemId,
            bulkRef = testBulkRef,
            sizeBytes = 1024L
        )

        assertEquals(testSessionId, expectation.sessionId)
        assertEquals(testItemId, expectation.itemId)
        assertEquals(testBulkRef, expectation.bulkRef)
        assertEquals(1024L, expectation.sizeBytes)
    }

    @Test
    fun testStreamExpectation_zeroSizeBytesValid() {
        val expectation = StreamExpectation(
            sessionId = testSessionId,
            itemId = testItemId,
            bulkRef = testBulkRef,
            sizeBytes = 0L
        )
        assertEquals(0L, expectation.sizeBytes)
    }

    @Test
    fun testStreamExpectation_negativeSizeBytesRejected() {
        try {
            StreamExpectation(
                sessionId = testSessionId,
                itemId = testItemId,
                bulkRef = testBulkRef,
                sizeBytes = -1L
            )
            fail("Expected rejection for negative sizeBytes")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("non-negative") == true)
        }
    }

    @Test
    fun testStreamExpectation_registeredInTransport() = runBlocking {
        val transport = FakeTransferTransport()
        val expectation = StreamExpectation(
            sessionId = testSessionId,
            itemId = testItemId,
            bulkRef = testBulkRef,
            sizeBytes = 2048L
        )

        transport.registerExpectedStream(expectation)

        assertEquals(1, transport.registeredExpectations.size)
        assertEquals(expectation, transport.registeredExpectations[0])
        assertTrue(transport.registeredExpectedStreams.contains(testBulkRef))
    }
}
