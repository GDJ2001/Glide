package dev.glide.core.security

import dev.glide.core.domain.MessageId
import dev.glide.core.domain.TransferFailure
import dev.glide.core.protocol.Envelope
import java.util.Arrays

/**
 * Result of sequencer evaluation on an incoming control envelope.
 */
sealed interface SequencerResult {
    /**
     * Envelope(s) ready for deterministic delivery to the protocol state machine.
     * May contain the received envelope plus any consecutively buffered out-of-order envelopes.
     */
    data class Deliver(val envelopes: List<Envelope>) : SequencerResult

    /**
     * Envelope arrived ahead of sequence and is safely buffered waiting for earlier sequences.
     */
    data class Buffered(val seq: Long, val bufferSize: Int) : SequencerResult

    /**
     * Envelope is an exact duplicate of an already processed message (identical messageId, seq, and content).
     * Safely ignored without re-executing state transitions (Threat T03).
     */
    data class DuplicateIgnored(val messageId: MessageId, val seq: Long) : SequencerResult

    /**
     * Sequence violation or security attack detected. Fails closed.
     */
    sealed class Violation(val failure: TransferFailure) : SequencerResult {
        data class ConflictingMessageId(val messageId: MessageId) :
            Violation(TransferFailure.MalformedMessage("Conflicting payload detected for duplicate messageId: ${messageId.value}"))

        data class BufferExhausted(val seq: Long, val capacity: Int) :
            Violation(TransferFailure.ProtocolOrder("Out-of-order control buffer capacity ($capacity) exceeded at seq $seq"))

        data class GapTimeout(val missingSeq: Long, val elapsedMs: Long) :
            Violation(TransferFailure.ProtocolOrder("Out-of-order sequence gap waiting for seq $missingSeq timed out after ${elapsedMs}ms"))

        data class StaleSequence(val seq: Long, val expectedSeq: Long) :
            Violation(TransferFailure.ProtocolOrder("Stale or replayed sequence number $seq received, expected $expectedSeq"))
    }
}

/**
 * Enforces inbound message ordering, out-of-order buffering, and replay deduplication conforming to:
 * - GLIDE_PROTOCOL_V1.md:
 *   - "seq: positive integer, starts at 1 independently per direction and increases by one."
 *   - "Core buffers at most 8 out-of-order controls for 2 seconds; otherwise fails PROTOCOL_ORDER."
 *   - "Duplicate messageId with identical bytes and original seq is ignored or gets its cached response;
 *      same ID with differing bytes is MALFORMED_MESSAGE."
 *   - "Keep at most 256 response-cache entries; older duplicates are rejected. Gap/replay after eviction fails closed."
 * - GLIDE_THREAT_MODEL.md (Threat T03: Replay / stale callbacks).
 */
class ProtocolSequencer(
    val maxBufferSize: Int = 8,
    val gapTimeoutMs: Long = 2_000L, // 2 seconds
    val maxCacheEntries: Int = 256,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()

    var expectedSeq: Long = 1L
        private set

    private data class BufferedItem(
        val envelope: Envelope,
        val payloadHash: Int,
        val arrivalMs: Long
    )

    private data class CacheEntry(
        val seq: Long,
        val payloadHash: Int
    )

    // Out-of-order buffer holding at most 8 envelopes keyed by seq
    private val outOfOrderBuffer = mutableMapOf<Long, BufferedItem>()

    // Bounded LRU cache of recently processed messageIds (max 256)
    private val seenMessageIds = object : LinkedHashMap<MessageId, CacheEntry>(maxCacheEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MessageId, CacheEntry>?): Boolean {
            return size > maxCacheEntries
        }
    }

    /**
     * Processes an incoming decoded envelope and raw payload bytes.
     *
     * @param envelope The parsed envelope.
     * @param rawPayload Raw payload bytes used to detect message tampering and replay conflicts.
     * @param nowMs Current monotonic or clock time in ms.
     * @return [SequencerResult] directing delivery, buffering, deduplication, or security failure.
     */
    fun processInbound(
        envelope: Envelope,
        rawPayload: ByteArray,
        nowMs: Long = clock()
    ): SequencerResult = synchronized(lock) {
        // 1. Check gap timeouts on existing buffered items
        checkTimeoutInternal(nowMs)?.let { return it }

        val payloadHash = Arrays.hashCode(rawPayload)
        val msgId = envelope.messageId

        // 2. Check LRU messageId cache for duplicate or replay attack (Threat T03)
        val cached = seenMessageIds[msgId]
        if (cached != null) {
            return if (cached.seq == envelope.seq && cached.payloadHash == payloadHash) {
                // Idempotent duplicate: identical ID, seq, and content -> ignore safely
                SequencerResult.DuplicateIgnored(msgId, envelope.seq)
            } else {
                // Conflicting duplicate: same ID but altered seq or content -> MALFORMED_MESSAGE
                SequencerResult.Violation.ConflictingMessageId(msgId)
            }
        }

        // 3. Evaluate sequence number
        val seq = envelope.seq
        return when {
            seq == expectedSeq -> {
                // In-order message received! Record in seen cache and advance
                seenMessageIds[msgId] = CacheEntry(seq, payloadHash)
                expectedSeq++

                val deliverable = mutableListOf(envelope)

                // Drain any contiguous out-of-order items that can now be delivered
                while (outOfOrderBuffer.containsKey(expectedSeq)) {
                    val next = outOfOrderBuffer.remove(expectedSeq)!!
                    seenMessageIds[next.envelope.messageId] = CacheEntry(expectedSeq, next.payloadHash)
                    deliverable.add(next.envelope)
                    expectedSeq++
                }

                SequencerResult.Deliver(deliverable)
            }

            seq > expectedSeq -> {
                // Ahead of sequence: check buffer limits
                if (outOfOrderBuffer.size >= maxBufferSize) {
                    return SequencerResult.Violation.BufferExhausted(seq, maxBufferSize)
                }

                outOfOrderBuffer[seq] = BufferedItem(
                    envelope = envelope,
                    payloadHash = payloadHash,
                    arrivalMs = nowMs
                )
                SequencerResult.Buffered(seq, outOfOrderBuffer.size)
            }

            else -> {
                // seq < expectedSeq and was not in the recent cache: stale or replay after eviction
                SequencerResult.Violation.StaleSequence(seq, expectedSeq)
            }
        }
    }

    /**
     * Checks if any currently buffered out-of-order messages have exceeded the 2-second gap timeout.
     */
    fun checkTimeout(nowMs: Long = clock()): SequencerResult.Violation.GapTimeout? = synchronized(lock) {
        checkTimeoutInternal(nowMs)
    }

    private fun checkTimeoutInternal(nowMs: Long): SequencerResult.Violation.GapTimeout? {
        if (outOfOrderBuffer.isEmpty()) return null

        val oldestArrival = outOfOrderBuffer.values.minOf { it.arrivalMs }
        val elapsed = nowMs - oldestArrival
        if (elapsed > gapTimeoutMs) {
            outOfOrderBuffer.clear()
            return SequencerResult.Violation.GapTimeout(expectedSeq, elapsed)
        }
        return null
    }

    /**
     * Returns the count of currently buffered out-of-order messages.
     */
    val bufferedCount: Int get() = synchronized(lock) { outOfOrderBuffer.size }

    /**
     * Clears all sequence state and caches (e.g. For new connection).
     */
    fun reset() = synchronized(lock) {
        expectedSeq = 1L
        outOfOrderBuffer.clear()
        seenMessageIds.clear()
    }
}
