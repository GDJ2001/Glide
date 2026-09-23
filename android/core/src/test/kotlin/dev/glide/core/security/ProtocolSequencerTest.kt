package dev.glide.core.security

import dev.glide.core.domain.MessageId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.TransferFailure
import dev.glide.core.protocol.Envelope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ProtocolSequencerTest {

    private val sessionId = SessionId("550e8400-e29b-41d4-a716-446655440000")

    private fun createEnvelope(
        seq: Long,
        messageId: MessageId = MessageId(UUID.randomUUID().toString()),
        type: String = "ping",
        content: String = "test-nonce"
    ): Pair<Envelope, ByteArray> {
        val body = buildJsonObject { put("nonce", content) }
        val env = Envelope(
            v = 1,
            type = type,
            sessionId = sessionId,
            messageId = messageId,
            seq = seq,
            body = body
        )
        val payload = "payload-$seq-${messageId.value}-$content".toByteArray()
        return env to payload
    }

    @Test
    fun inOrderMessagesDeliveredSequentially() {
        val sequencer = ProtocolSequencer()

        val (env1, p1) = createEnvelope(seq = 1)
        val res1 = sequencer.processInbound(env1, p1)
        assertTrue(res1 is SequencerResult.Deliver)
        assertEquals(listOf(1L), (res1 as SequencerResult.Deliver).envelopes.map { it.seq })
        assertEquals(2L, sequencer.expectedSeq)

        val (env2, p2) = createEnvelope(seq = 2)
        val res2 = sequencer.processInbound(env2, p2)
        assertTrue(res2 is SequencerResult.Deliver)
        assertEquals(listOf(2L), (res2 as SequencerResult.Deliver).envelopes.map { it.seq })
        assertEquals(3L, sequencer.expectedSeq)
    }

    @Test
    fun outOfOrderBufferingAndContiguousDrain() {
        val sequencer = ProtocolSequencer()

        // 1 arrives
        val (env1, p1) = createEnvelope(seq = 1)
        val res1 = sequencer.processInbound(env1, p1)
        assertTrue(res1 is SequencerResult.Deliver)

        // 3 arrives before 2 -> buffered
        val (env3, p3) = createEnvelope(seq = 3)
        val res3 = sequencer.processInbound(env3, p3)
        assertTrue(res3 is SequencerResult.Buffered)
        assertEquals(1, sequencer.bufferedCount)

        // 4 arrives before 2 -> buffered
        val (env4, p4) = createEnvelope(seq = 4)
        val res4 = sequencer.processInbound(env4, p4)
        assertTrue(res4 is SequencerResult.Buffered)
        assertEquals(2, sequencer.bufferedCount)

        // Missing 2 arrives -> drains 2, 3, and 4 in order!
        val (env2, p2) = createEnvelope(seq = 2)
        val res2 = sequencer.processInbound(env2, p2)
        assertTrue(res2 is SequencerResult.Deliver)
        val delivered = (res2 as SequencerResult.Deliver).envelopes
        assertEquals(listOf(2L, 3L, 4L), delivered.map { it.seq })
        assertEquals(5L, sequencer.expectedSeq)
        assertEquals(0, sequencer.bufferedCount)
    }

    @Test
    fun outOfOrderBufferCapacityExceededFailsClosed() {
        val sequencer = ProtocolSequencer(maxBufferSize = 8)

        // 1 arrives
        val (env1, p1) = createEnvelope(seq = 1)
        sequencer.processInbound(env1, p1)

        // Fill buffer with 8 out-of-order messages (seq 3..10)
        for (s in 3L..10L) {
            val (env, p) = createEnvelope(seq = s)
            val res = sequencer.processInbound(env, p)
            assertTrue("Seq $s should be buffered", res is SequencerResult.Buffered)
        }
        assertEquals(8, sequencer.bufferedCount)

        // 9th out-of-order message (seq 11) must fail closed with ProtocolOrder
        val (env11, p11) = createEnvelope(seq = 11)
        val overflowRes = sequencer.processInbound(env11, p11)
        assertTrue("Exceeding 8 buffered controls must fail closed", overflowRes is SequencerResult.Violation.BufferExhausted)
        val failure = (overflowRes as SequencerResult.Violation.BufferExhausted).failure
        assertTrue(failure is TransferFailure.ProtocolOrder)
    }

    @Test
    fun gapTimeoutFailsClosedAfterTwoSeconds() {
        var currentTimeMs = 1_000L
        val sequencer = ProtocolSequencer(
            gapTimeoutMs = 2_000L,
            clock = { currentTimeMs }
        )

        // 1 arrives at t=1000
        val (env1, p1) = createEnvelope(seq = 1)
        sequencer.processInbound(env1, p1, nowMs = currentTimeMs)

        // 3 arrives at t=1000 (missing 2)
        val (env3, p3) = createEnvelope(seq = 3)
        sequencer.processInbound(env3, p3, nowMs = currentTimeMs)

        // At t=2500 (1.5s elapsed): no timeout yet
        currentTimeMs = 2_500L
        assertNull(sequencer.checkTimeout(nowMs = currentTimeMs))

        // At t=3001 (2001ms elapsed): gap timeout triggered
        currentTimeMs = 3_001L
        val timeoutViolation = sequencer.checkTimeout(nowMs = currentTimeMs)
        assertNotNull("Gap timeout should trigger after 2 seconds", timeoutViolation)
        assertTrue(timeoutViolation!!.failure is TransferFailure.ProtocolOrder)
        assertEquals(0, sequencer.bufferedCount)
    }

    @Test
    fun idempotentDuplicateIsIgnored() {
        val sequencer = ProtocolSequencer()
        val msgId = MessageId("550e8400-e29b-41d4-a716-446655440001")
        val (env1, p1) = createEnvelope(seq = 1, messageId = msgId, content = "same-payload")

        // First delivery
        val res1 = sequencer.processInbound(env1, p1)
        assertTrue(res1 is SequencerResult.Deliver)

        // Duplicate with exact same msgId, seq, and payload
        val resDuplicate = sequencer.processInbound(env1, p1)
        assertTrue(
            "Identical duplicate must be ignored idempotently (Threat T03)",
            resDuplicate is SequencerResult.DuplicateIgnored
        )
        assertEquals(2L, sequencer.expectedSeq)
    }

    @Test
    fun conflictingDuplicatePayloadTriggersMalformedMessage() {
        val sequencer = ProtocolSequencer()
        val msgId = MessageId("550e8400-e29b-41d4-a716-446655440002")
        val (env1, p1) = createEnvelope(seq = 1, messageId = msgId, content = "original-payload")

        // Deliver original
        sequencer.processInbound(env1, p1)

        // Malicious or corrupted retry with same messageId but altered payload
        val (envTampered, _) = createEnvelope(seq = 1, messageId = msgId, content = "tampered-payload")
        val tamperedPayload = "tampered-different-bytes".toByteArray()

        val tamperedRes = sequencer.processInbound(envTampered, tamperedPayload)
        assertTrue(
            "Reused messageId with conflicting content must trigger MalformedMessage",
            tamperedRes is SequencerResult.Violation.ConflictingMessageId
        )
        val failure = (tamperedRes as SequencerResult.Violation.ConflictingMessageId).failure
        assertTrue(failure is TransferFailure.MalformedMessage)
    }

    @Test
    fun staleSequenceReplayAfterCacheEvictionFailsClosed() {
        val sequencer = ProtocolSequencer(maxCacheEntries = 4)

        // Send 1..5 in order
        for (s in 1L..5L) {
            val (env, p) = createEnvelope(seq = s)
            sequencer.processInbound(env, p)
        }
        assertEquals(6L, sequencer.expectedSeq)

        // Sequence 1 was evicted from the 4-entry cache
        val (staleEnv, staleP) = createEnvelope(seq = 1)
        val staleRes = sequencer.processInbound(staleEnv, staleP)
        assertTrue(
            "Replay after LRU cache eviction must fail closed with ProtocolOrder",
            staleRes is SequencerResult.Violation.StaleSequence
        )
    }
}
