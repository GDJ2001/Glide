package dev.glide.core.coordinator

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.MessageId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferFailure
import dev.glide.core.domain.TransferItem
import dev.glide.core.protocol.BatchEndBody
import dev.glide.core.protocol.BatchResultBody
import dev.glide.core.protocol.CancelBody
import dev.glide.core.protocol.HelloAckBody
import dev.glide.core.protocol.HelloBody
import dev.glide.core.protocol.ItemEndBody
import dev.glide.core.protocol.ItemReadyBody
import dev.glide.core.protocol.ItemResultBody
import dev.glide.core.protocol.ItemStartBody
import dev.glide.core.protocol.MessageTypes
import dev.glide.core.protocol.NegotiatedSession
import dev.glide.core.protocol.OfferBeginBody
import dev.glide.core.protocol.OfferDecisionBody
import dev.glide.core.protocol.OfferEndBody
import dev.glide.core.protocol.OfferPageBody
import dev.glide.core.protocol.ProtocolCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class TransferReducerTest {

    private val sessionId = SessionId("a0000000-0000-4000-8000-000000000001")
    private val batchId = BatchId("b0000000-0000-4000-8000-000000000001")
    private val itemId1 = ItemId("c0000000-0000-4000-8000-000000000001")
    private val itemId2 = ItemId("c0000000-0000-4000-8000-000000000002")

    private val digest1 = Sha256Digest("1".repeat(64))
    private val digest2 = Sha256Digest("2".repeat(64))

    private val item1 = TransferItem(
        id = itemId1,
        name = "file1.txt",
        mimeType = "text/plain",
        sizeBytes = 100L,
        digest = digest1
    )
    private val item2 = TransferItem(
        id = itemId2,
        name = "file2.txt",
        mimeType = "text/plain",
        sizeBytes = 200L,
        digest = digest2
    )
    private val batch = TransferBatch(id = batchId, items = listOf(item1, item2))

    @Test
    fun sender_and_receiver_full_successful_session_lifecycle() {
        var senderState = SessionState(sessionId = sessionId, role = DeviceRole.Sender)
        var receiverState = SessionState(sessionId = sessionId, role = DeviceRole.Receiver)

        assertEquals(SessionPhase.Idle, senderState.phase)
        assertEquals(SessionPhase.Idle, receiverState.phase)

        // 1. Sender starts negotiation
        val senderPeerId = UUID.randomUUID().toString()
        val res1 = TransferReducer.reduce(
            senderState,
            ReducerEvent.StartNegotiation(senderPeerId, "SenderDevice", 100L)
        )
        senderState = res1.state
        assertEquals(SessionPhase.Negotiating, senderState.phase)
        assertEquals(1, res1.effects.size)
        val helloControl = res1.effects[0] as ReducerEffect.SendControl
        assertEquals(MessageTypes.HELLO, helloControl.envelope.type)
        val helloBody = ProtocolCodec.decodeBody(helloControl.envelope) as HelloBody

        // 2. Receiver receives HELLO -> emits HELLO_ACK
        val res2 = TransferReducer.reduce(
            receiverState,
            ReducerEvent.HelloReceived(helloBody, helloControl.envelope.messageId, 1L, 105L)
        )
        receiverState = res2.state
        assertEquals(SessionPhase.Offering, receiverState.phase)
        assertEquals(1, res2.effects.size)
        val helloAckControl = res2.effects[0] as ReducerEffect.SendControl
        assertEquals(MessageTypes.HELLO_ACK, helloAckControl.envelope.type)
        val helloAckBody = ProtocolCodec.decodeBody(helloAckControl.envelope) as HelloAckBody

        // 3. Sender receives HELLO_ACK -> transitions to Offering
        val res3 = TransferReducer.reduce(
            senderState,
            ReducerEvent.HelloAckReceived(helloAckBody, helloAckControl.envelope.messageId, 1L, 110L)
        )
        senderState = res3.state
        assertEquals(SessionPhase.Offering, senderState.phase)
        assertNotNull(senderState.negotiated)

        // 4. Sender submits offer -> emits OFFER_BEGIN, OFFER_PAGE, OFFER_END
        val res4 = TransferReducer.reduce(
            senderState,
            ReducerEvent.SubmitOffer(batch, 120L)
        )
        senderState = res4.state
        assertEquals(SessionPhase.AwaitingConsent, senderState.phase)
        assertEquals(3, res4.effects.size) // BEGIN, 1 PAGE, END
        val beginCtrl = res4.effects[0] as ReducerEffect.SendControl
        val pageCtrl = res4.effects[1] as ReducerEffect.SendControl
        val endCtrl = res4.effects[2] as ReducerEffect.SendControl
        assertEquals(MessageTypes.OFFER_BEGIN, beginCtrl.envelope.type)
        assertEquals(MessageTypes.OFFER_PAGE, pageCtrl.envelope.type)
        assertEquals(MessageTypes.OFFER_END, endCtrl.envelope.type)

        // 5. Receiver receives OFFER_BEGIN, OFFER_PAGE, OFFER_END -> transitions to AwaitingConsent
        val beginBody = ProtocolCodec.decodeBody(beginCtrl.envelope) as OfferBeginBody
        val pageBody = ProtocolCodec.decodeBody(pageCtrl.envelope) as OfferPageBody
        val endBody = ProtocolCodec.decodeBody(endCtrl.envelope) as OfferEndBody

        val rBegin = TransferReducer.reduce(receiverState, ReducerEvent.OfferBeginReceived(beginBody, beginCtrl.envelope.messageId, 2L, 125L))
        receiverState = rBegin.state
        assertEquals(SessionPhase.Offering, receiverState.phase)

        val rPage = TransferReducer.reduce(receiverState, ReducerEvent.OfferPageReceived(pageBody, pageCtrl.envelope.messageId, 3L, 126L))
        receiverState = rPage.state

        val rEnd = TransferReducer.reduce(receiverState, ReducerEvent.OfferEndReceived(endBody, endCtrl.envelope.messageId, 4L, 127L))
        receiverState = rEnd.state
        assertEquals(SessionPhase.AwaitingConsent, receiverState.phase)
        assertEquals(2, receiverState.itemStates.size)

        // 6. Receiver accepts offer -> emits OFFER_DECISION(accepted = true)
        val resAccept = TransferReducer.reduce(receiverState, ReducerEvent.SubmitOfferDecision(accepted = true, rejectionCode = null, timestampMs = 130L))
        receiverState = resAccept.state
        assertEquals(SessionPhase.PreparingItem, receiverState.phase)
        val decisionCtrl = resAccept.effects[0] as ReducerEffect.SendControl
        val decisionBody = ProtocolCodec.decodeBody(decisionCtrl.envelope) as OfferDecisionBody
        assertTrue(decisionBody.accepted)

        // 7. Sender receives OFFER_DECISION -> emits ITEM_START for item 0
        val resDecision = TransferReducer.reduce(
            senderState,
            ReducerEvent.OfferDecisionReceived(decisionBody, decisionCtrl.envelope.messageId, 2L, 135L)
        )
        senderState = resDecision.state
        assertEquals(SessionPhase.PreparingItem, senderState.phase)
        val itemStartCtrl = resDecision.effects[0] as ReducerEffect.SendControl
        assertEquals(MessageTypes.ITEM_START, itemStartCtrl.envelope.type)
        val itemStartBody = ProtocolCodec.decodeBody(itemStartCtrl.envelope) as ItemStartBody
        assertEquals(itemId1, itemStartBody.itemId)

        // 8. Receiver receives ITEM_START -> emits ITEM_READY and PrepareDestinationStorage
        val resItemStart = TransferReducer.reduce(
            receiverState,
            ReducerEvent.ItemStartReceived(itemStartBody, itemStartCtrl.envelope.messageId, 5L, 140L)
        )
        receiverState = resItemStart.state
        assertEquals(SessionPhase.TransferringItem, receiverState.phase)
        assertEquals(2, resItemStart.effects.size)
        assertTrue(resItemStart.effects.any { it is ReducerEffect.PrepareDestinationStorage })
        val itemReadyCtrl = resItemStart.effects.first { it is ReducerEffect.SendControl } as ReducerEffect.SendControl
        assertEquals(MessageTypes.ITEM_READY, itemReadyCtrl.envelope.type)
        val itemReadyBody = ProtocolCodec.decodeBody(itemReadyCtrl.envelope) as ItemReadyBody

        // 9. Sender receives ITEM_READY -> emits StartBulkStream
        val resItemReady = TransferReducer.reduce(
            senderState,
            ReducerEvent.ItemReadyReceived(itemReadyBody, itemReadyCtrl.envelope.messageId, 3L, 145L)
        )
        senderState = resItemReady.state
        assertEquals(SessionPhase.TransferringItem, senderState.phase)
        val startStreamEff = resItemReady.effects[0] as ReducerEffect.StartBulkStream
        assertEquals(itemId1, startStreamEff.itemId)

        // 10. Byte progress updates
        val resProg = TransferReducer.reduce(senderState, ReducerEvent.StreamProgress(itemId1, 50L, 150L))
        senderState = resProg.state
        assertEquals(50L, senderState.itemStates[itemId1]!!.transferredBytes)
        val progEff = resProg.effects[0] as ReducerEffect.UpdateTransferProgress
        assertEquals(50L, progEff.progress.transferredBytes)
        assertEquals(300L, progEff.progress.totalBytes)

        // 11. Item 0 sender EOF & transport success -> sender emits ITEM_END
        val resSenderEof = TransferReducer.reduce(senderState, ReducerEvent.StreamEof(itemId1, 100L, digest1, 155L))
        senderState = resSenderEof.state
        assertTrue(resSenderEof.effects.isEmpty()) // Waiting for transport success

        val resSenderSuccess = TransferReducer.reduce(senderState, ReducerEvent.TransportPayloadSuccess(itemId1, startStreamEff.bulkRef, 160L))
        senderState = resSenderSuccess.state
        assertEquals(SessionPhase.VerifyingItem, senderState.phase)
        val itemEndCtrl = resSenderSuccess.effects[0] as ReducerEffect.SendControl
        assertEquals(MessageTypes.ITEM_END, itemEndCtrl.envelope.type)
        val itemEndBody = ProtocolCodec.decodeBody(itemEndCtrl.envelope) as ItemEndBody

        // 12. Receiver receives stream EOF, transport success, and ITEM_END -> triggers CommitDestinationFile
        val rEof = TransferReducer.reduce(receiverState, ReducerEvent.StreamEof(itemId1, 100L, digest1, 156L))
        receiverState = rEof.state
        assertTrue(rEof.effects.isEmpty())

        val rSucc = TransferReducer.reduce(receiverState, ReducerEvent.TransportPayloadSuccess(itemId1, startStreamEff.bulkRef, 158L))
        receiverState = rSucc.state
        assertTrue(rSucc.effects.isEmpty())

        val rEndItem = TransferReducer.reduce(receiverState, ReducerEvent.ItemEndReceived(itemEndBody, itemEndCtrl.envelope.messageId, 6L, 162L))
        receiverState = rEndItem.state
        assertEquals(SessionPhase.VerifyingItem, receiverState.phase)
        assertEquals(1, rEndItem.effects.size)
        val commitEff = rEndItem.effects[0] as ReducerEffect.CommitDestinationFile
        assertEquals(itemId1, commitEff.itemId)

        // 13. Receiver local commit succeeds -> emits ITEM_RESULT (verified) and advances to item 1
        val resCommitSuccess = TransferReducer.reduce(receiverState, ReducerEvent.LocalCommitSuccess(itemId1, 165L))
        receiverState = resCommitSuccess.state
        assertEquals(SessionPhase.PreparingItem, receiverState.phase)
        assertEquals(1, receiverState.currentItemIndex)
        assertTrue(receiverState.itemStates[itemId1]!!.isVerified)
        val itemResultCtrl = resCommitSuccess.effects[0] as ReducerEffect.SendControl
        assertEquals(MessageTypes.ITEM_RESULT, itemResultCtrl.envelope.type)
        val itemResultBody = ProtocolCodec.decodeBody(itemResultCtrl.envelope) as ItemResultBody
        assertEquals("verified", itemResultBody.status)

        // 14. Sender receives ITEM_RESULT (verified) -> advances to item 1 and emits ITEM_START
        val resResultRcv = TransferReducer.reduce(
            senderState,
            ReducerEvent.ItemResultReceived(itemResultBody, itemResultCtrl.envelope.messageId, 4L, 170L)
        )
        senderState = resResultRcv.state
        assertEquals(SessionPhase.PreparingItem, senderState.phase)
        assertEquals(1, senderState.currentItemIndex)
        assertTrue(senderState.itemStates[itemId1]!!.isVerified)
        val itemStartCtrl2 = resResultRcv.effects[0] as ReducerEffect.SendControl
        assertEquals(MessageTypes.ITEM_START, itemStartCtrl2.envelope.type)
        val itemStartBody2 = ProtocolCodec.decodeBody(itemStartCtrl2.envelope) as ItemStartBody
        assertEquals(itemId2, itemStartBody2.itemId)

        // 15. Item 2 transfer round-trip
        // Receiver receives ITEM_START(item2)
        val rStart2 = TransferReducer.reduce(receiverState, ReducerEvent.ItemStartReceived(itemStartBody2, itemStartCtrl2.envelope.messageId, 7L, 175L))
        receiverState = rStart2.state
        val itemReadyCtrl2 = rStart2.effects.first { it is ReducerEffect.SendControl } as ReducerEffect.SendControl
        val itemReadyBody2 = ProtocolCodec.decodeBody(itemReadyCtrl2.envelope) as ItemReadyBody

        // Sender receives ITEM_READY(item2)
        val sReady2 = TransferReducer.reduce(senderState, ReducerEvent.ItemReadyReceived(itemReadyBody2, itemReadyCtrl2.envelope.messageId, 5L, 180L))
        senderState = sReady2.state
        val startStreamEff2 = sReady2.effects[0] as ReducerEffect.StartBulkStream

        // Sender EOF & Success
        senderState = TransferReducer.reduce(senderState, ReducerEvent.StreamEof(itemId2, 200L, digest2, 185L)).state
        val sEnd2 = TransferReducer.reduce(senderState, ReducerEvent.TransportPayloadSuccess(itemId2, startStreamEff2.bulkRef, 186L))
        senderState = sEnd2.state
        val itemEndCtrl2 = sEnd2.effects[0] as ReducerEffect.SendControl
        val itemEndBody2 = ProtocolCodec.decodeBody(itemEndCtrl2.envelope) as ItemEndBody

        // Receiver EOF, Success, End
        receiverState = TransferReducer.reduce(receiverState, ReducerEvent.StreamEof(itemId2, 200L, digest2, 187L)).state
        receiverState = TransferReducer.reduce(receiverState, ReducerEvent.TransportPayloadSuccess(itemId2, startStreamEff2.bulkRef, 188L)).state
        val rEnd2 = TransferReducer.reduce(receiverState, ReducerEvent.ItemEndReceived(itemEndBody2, itemEndCtrl2.envelope.messageId, 8L, 189L))
        receiverState = rEnd2.state
        assertTrue(rEnd2.effects.any { it is ReducerEffect.CommitDestinationFile })

        // Receiver commit succeeds for item 2 (last item) -> AwaitingReceipt, sends ITEM_RESULT
        val rCommit2 = TransferReducer.reduce(receiverState, ReducerEvent.LocalCommitSuccess(itemId2, 190L))
        receiverState = rCommit2.state
        assertEquals(SessionPhase.AwaitingReceipt, receiverState.phase)
        assertTrue(receiverState.itemStates[itemId2]!!.isVerified)
        val itemResultCtrl2 = rCommit2.effects[0] as ReducerEffect.SendControl
        val itemResultBody2 = ProtocolCodec.decodeBody(itemResultCtrl2.envelope) as ItemResultBody

        // Sender receives ITEM_RESULT for last item -> AwaitingReceipt, sends BATCH_END
        val sResult2 = TransferReducer.reduce(senderState, ReducerEvent.ItemResultReceived(itemResultBody2, itemResultCtrl2.envelope.messageId, 6L, 195L))
        senderState = sResult2.state
        assertEquals(SessionPhase.AwaitingReceipt, senderState.phase)
        assertTrue(senderState.itemStates[itemId2]!!.isVerified)
        val batchEndCtrl = sResult2.effects[0] as ReducerEffect.SendControl
        assertEquals(MessageTypes.BATCH_END, batchEndCtrl.envelope.type)
        val batchEndBody = ProtocolCodec.decodeBody(batchEndCtrl.envelope) as BatchEndBody

        // Receiver receives BATCH_END -> sends BATCH_RESULT, transitions to Completed
        val rBatchEnd = TransferReducer.reduce(receiverState, ReducerEvent.BatchEndReceived(batchEndBody, batchEndCtrl.envelope.messageId, 9L, 200L))
        receiverState = rBatchEnd.state
        assertEquals(SessionPhase.Completed, receiverState.phase)
        assertTrue(receiverState.isTerminal)
        val batchResultCtrl = rBatchEnd.effects.first { it is ReducerEffect.SendControl } as ReducerEffect.SendControl
        assertEquals(MessageTypes.BATCH_RESULT, batchResultCtrl.envelope.type)
        val batchResultBody = ProtocolCodec.decodeBody(batchResultCtrl.envelope) as BatchResultBody
        assertEquals("completed", batchResultBody.status)
        assertEquals(2, batchResultBody.verifiedCount)
        assertTrue(rBatchEnd.effects.any { it is ReducerEffect.NotifyTerminalOutcome && it.phase == SessionPhase.Completed })

        // Sender receives BATCH_RESULT -> transitions to Completed
        val sBatchResult = TransferReducer.reduce(senderState, ReducerEvent.BatchResultReceived(batchResultBody, batchResultCtrl.envelope.messageId, 7L, 205L))
        senderState = sBatchResult.state
        assertEquals(SessionPhase.Completed, senderState.phase)
        assertTrue(senderState.isTerminal)
        assertTrue(sBatchResult.effects.any { it is ReducerEffect.NotifyTerminalOutcome && it.phase == SessionPhase.Completed })

        // Final verification of counts and state
        assertEquals(2, senderState.verifiedCount)
        assertEquals(2, receiverState.verifiedCount)
        assertEquals(300L, senderState.totalVerifiedBytes)
        assertEquals(300L, receiverState.totalVerifiedBytes)
    }

    @Test
    fun receiver_verification_race_permutations_all_require_all_three_events() {
        val baseItemState = ItemTransferState(item = item1, phase = ItemPhase.Streaming, bulkRef = BulkRef("bulk-1"))
        val baseSession = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Receiver,
            phase = SessionPhase.TransferringItem,
            batch = batch,
            currentItemIndex = 0,
            itemStates = mapOf(itemId1 to baseItemState)
        )

        val endBody = ItemEndBody(batchId = batchId, itemId = itemId1, actualBytes = "100", sha256 = digest1)
        val msgId = MessageId("f0000000-0000-4000-8000-000000000001")

        val eventEof = ReducerEvent.StreamEof(itemId1, 100L, digest1, 100L)
        val eventSucc = ReducerEvent.TransportPayloadSuccess(itemId1, BulkRef("bulk-1"), 101L)
        val eventEnd = ReducerEvent.ItemEndReceived(endBody, msgId, 1L, 102L)

        val permutations = listOf(
            listOf(eventEof, eventSucc, eventEnd),
            listOf(eventEnd, eventEof, eventSucc),
            listOf(eventSucc, eventEnd, eventEof),
            listOf(eventEof, eventEnd, eventSucc),
            listOf(eventEnd, eventSucc, eventEof),
            listOf(eventSucc, eventEof, eventEnd)
        )

        for ((idx, events) in permutations.withIndex()) {
            var state = baseSession

            // First event
            val r1 = TransferReducer.reduce(state, events[0])
            state = r1.state
            assertTrue("Permutation $idx: event 1 should not commit", r1.effects.none { it is ReducerEffect.CommitDestinationFile })
            assertFalse(state.itemStates[itemId1]!!.phase == ItemPhase.Committing)

            // Second event
            val r2 = TransferReducer.reduce(state, events[1])
            state = r2.state
            assertTrue("Permutation $idx: event 2 should not commit", r2.effects.none { it is ReducerEffect.CommitDestinationFile })
            assertFalse(state.itemStates[itemId1]!!.phase == ItemPhase.Committing)

            // Third event -> triggers CommitDestinationFile
            val r3 = TransferReducer.reduce(state, events[2])
            state = r3.state
            assertTrue("Permutation $idx: event 3 must trigger CommitDestinationFile", r3.effects.any { it is ReducerEffect.CommitDestinationFile })
            assertEquals(ItemPhase.Committing, state.itemStates[itemId1]!!.phase)
            assertEquals(SessionPhase.VerifyingItem, state.phase)

            // Duplicate event after entering Committing should be idempotent
            val dup = TransferReducer.reduce(state, eventSucc)
            assertTrue(dup.effects.isEmpty())
            assertEquals(state, dup.state)
        }
    }

    @Test
    fun sender_verification_race_permutations_both_orderings_emit_item_end() {
        val baseItemState = ItemTransferState(item = item1, phase = ItemPhase.Streaming, bulkRef = BulkRef("bulk-1"))
        val baseSession = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = SessionPhase.TransferringItem,
            batch = batch,
            currentItemIndex = 0,
            itemStates = mapOf(itemId1 to baseItemState)
        )

        val eventEof = ReducerEvent.StreamEof(itemId1, 100L, digest1, 100L)
        val eventSucc = ReducerEvent.TransportPayloadSuccess(itemId1, BulkRef("bulk-1"), 101L)

        // Ordering 1: EOF then Success
        var s1 = baseSession
        val r1 = TransferReducer.reduce(s1, eventEof)
        s1 = r1.state
        assertTrue(r1.effects.isEmpty())
        val r2 = TransferReducer.reduce(s1, eventSucc)
        s1 = r2.state
        assertEquals(SessionPhase.VerifyingItem, s1.phase)
        assertEquals(ItemPhase.Verifying, s1.itemStates[itemId1]!!.phase)
        assertTrue(r2.effects.any { it is ReducerEffect.SendControl && it.envelope.type == MessageTypes.ITEM_END })

        // Duplicate event
        val dup1 = TransferReducer.reduce(s1, eventSucc)
        assertTrue(dup1.effects.isEmpty())

        // Ordering 2: Success then EOF
        var s2 = baseSession
        val r3 = TransferReducer.reduce(s2, eventSucc)
        s2 = r3.state
        assertTrue(r3.effects.isEmpty())
        val r4 = TransferReducer.reduce(s2, eventEof)
        s2 = r4.state
        assertEquals(SessionPhase.VerifyingItem, s2.phase)
        assertEquals(ItemPhase.Verifying, s2.itemStates[itemId1]!!.phase)
        assertTrue(r4.effects.any { it is ReducerEffect.SendControl && it.envelope.type == MessageTypes.ITEM_END })
    }

    @Test
    fun receiver_detects_digest_integrity_mismatch_fails_session_and_cleans_up() {
        val badDigest = Sha256Digest("f".repeat(64))
        val itemState1 = ItemTransferState(item = item1, phase = ItemPhase.Streaming, bulkRef = BulkRef("bulk-1"))
        val itemState2 = ItemTransferState(item = item2, phase = ItemPhase.Queued)
        val session = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Receiver,
            phase = SessionPhase.TransferringItem,
            batch = batch,
            currentItemIndex = 0,
            itemStates = mapOf(itemId1 to itemState1, itemId2 to itemState2)
        )

        val endBody = ItemEndBody(batchId = batchId, itemId = itemId1, actualBytes = "100", sha256 = digest1)
        val msgId = MessageId("f0000000-0000-4000-8000-000000000001")

        // Receiver calculated badDigest, but sender sent digest1
        var state = TransferReducer.reduce(session, ReducerEvent.StreamEof(itemId1, 100L, badDigest, 100L)).state
        state = TransferReducer.reduce(state, ReducerEvent.TransportPayloadSuccess(itemId1, BulkRef("bulk-1"), 101L)).state
        val res = TransferReducer.reduce(state, ReducerEvent.ItemEndReceived(endBody, msgId, 1L, 102L))

        assertEquals(SessionPhase.Failed, res.state.phase)
        assertTrue(res.state.isTerminal)
        assertEquals("INTEGRITY_MISMATCH", res.state.failure?.code)

        // Item 1 failed, item 2 not attempted
        assertEquals(ItemPhase.Failed, res.state.itemStates[itemId1]!!.phase)
        assertEquals(ItemPhase.NotAttempted, res.state.itemStates[itemId2]!!.phase)

        // Check effects: partial file deleted, control failure sent, transport closed, terminal outcome notified
        assertTrue(res.effects.any { it is ReducerEffect.DeletePartialDestination && it.itemId == itemId1 })
        assertTrue(res.effects.any { it is ReducerEffect.CloseTransportChannel })
        assertTrue(res.effects.any { it is ReducerEffect.NotifyTerminalOutcome && it.phase == SessionPhase.Failed })
        val ctrl = res.effects.first { it is ReducerEffect.SendControl } as ReducerEffect.SendControl
        assertEquals(MessageTypes.ITEM_RESULT, ctrl.envelope.type)
        val body = ProtocolCodec.decodeBody(ctrl.envelope) as ItemResultBody
        assertEquals("failed", body.status)
        assertEquals("INTEGRITY_MISMATCH", body.code)
    }

    @Test
    fun receiver_detects_byte_count_mismatch_fails_session() {
        val itemState1 = ItemTransferState(item = item1, phase = ItemPhase.Streaming, bulkRef = BulkRef("bulk-1"))
        val session = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Receiver,
            phase = SessionPhase.TransferringItem,
            batch = batch,
            currentItemIndex = 0,
            itemStates = mapOf(itemId1 to itemState1)
        )

        val endBody = ItemEndBody(batchId = batchId, itemId = itemId1, actualBytes = "100", sha256 = digest1)
        val msgId = MessageId("f0000000-0000-4000-8000-000000000001")

        // Byte count was 90 instead of expected 100
        var state = TransferReducer.reduce(session, ReducerEvent.StreamEof(itemId1, 90L, digest1, 100L)).state
        state = TransferReducer.reduce(state, ReducerEvent.TransportPayloadSuccess(itemId1, BulkRef("bulk-1"), 101L)).state
        val res = TransferReducer.reduce(state, ReducerEvent.ItemEndReceived(endBody, msgId, 1L, 102L))

        assertEquals(SessionPhase.Failed, res.state.phase)
        assertEquals("INTEGRITY_MISMATCH", res.state.failure?.code)
    }

    @Test
    fun sender_receives_failed_item_result_transitions_to_failed() {
        val itemState1 = ItemTransferState(item = item1, phase = ItemPhase.Verifying, bulkRef = BulkRef("bulk-1"))
        val itemState2 = ItemTransferState(item = item2, phase = ItemPhase.Queued)
        val session = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = SessionPhase.VerifyingItem,
            batch = batch,
            currentItemIndex = 0,
            itemStates = mapOf(itemId1 to itemState1, itemId2 to itemState2)
        )

        val resultBody = ItemResultBody(batchId = batchId, itemId = itemId1, status = "failed", code = "INTEGRITY_MISMATCH")
        val res = TransferReducer.reduce(
            session,
            ReducerEvent.ItemResultReceived(resultBody, MessageId("f0000000-0000-4000-8000-000000000002"), 1L, 100L)
        )

        assertEquals(SessionPhase.Failed, res.state.phase)
        assertTrue(res.state.isTerminal)
        assertEquals(ItemPhase.Failed, res.state.itemStates[itemId1]!!.phase)
        assertEquals(ItemPhase.NotAttempted, res.state.itemStates[itemId2]!!.phase)
        assertTrue(res.effects.any { it is ReducerEffect.CloseTransportChannel })
        assertTrue(res.effects.any { it is ReducerEffect.NotifyTerminalOutcome && it.phase == SessionPhase.Failed })
    }

    @Test
    fun cancellation_before_commit_cleans_up_and_marks_queued_not_attempted() {
        val itemState1 = ItemTransferState(item = item1, phase = ItemPhase.Streaming, bulkRef = BulkRef("bulk-1"))
        val itemState2 = ItemTransferState(item = item2, phase = ItemPhase.Queued)
        val session = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Receiver,
            phase = SessionPhase.TransferringItem,
            batch = batch,
            currentItemIndex = 0,
            itemStates = mapOf(itemId1 to itemState1, itemId2 to itemState2)
        )

        val res = TransferReducer.reduce(session, ReducerEvent.RequestCancel(code = "USER_CANCELLED", timestampMs = 100L))

        assertEquals(SessionPhase.Cancelled, res.state.phase)
        assertTrue(res.state.isTerminal)
        assertEquals(ItemPhase.Cancelled, res.state.itemStates[itemId1]!!.phase)
        assertEquals(ItemPhase.NotAttempted, res.state.itemStates[itemId2]!!.phase)

        assertTrue(res.effects.any { it is ReducerEffect.CancelBulkStream && it.bulkRef == BulkRef("bulk-1") })
        assertTrue(res.effects.any { it is ReducerEffect.DeletePartialDestination && it.itemId == itemId1 })
        assertTrue(res.effects.any { it is ReducerEffect.SendControl && it.envelope.type == MessageTypes.CANCEL })
        assertTrue(res.effects.any { it is ReducerEffect.CloseTransportChannel })
        assertTrue(res.effects.any { it is ReducerEffect.NotifyTerminalOutcome && it.phase == SessionPhase.Cancelled })
    }

    @Test
    fun cancellation_after_first_item_committed_retains_verified_item() {
        val itemState1 = ItemTransferState(item = item1, phase = ItemPhase.Verified, bulkRef = BulkRef("bulk-1"))
        val itemState2 = ItemTransferState(item = item2, phase = ItemPhase.Streaming, bulkRef = BulkRef("bulk-2"))
        val session = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Receiver,
            phase = SessionPhase.TransferringItem,
            batch = batch,
            currentItemIndex = 1,
            itemStates = mapOf(itemId1 to itemState1, itemId2 to itemState2)
        )

        val res = TransferReducer.reduce(session, ReducerEvent.RequestCancel(code = "USER_CANCELLED", timestampMs = 100L))

        assertEquals(SessionPhase.Cancelled, res.state.phase)
        // Item 1 remains Verified! (Verified is absorbing)
        assertEquals(ItemPhase.Verified, res.state.itemStates[itemId1]!!.phase)
        assertTrue(res.state.itemStates[itemId1]!!.isVerified)
        assertEquals(1, res.state.verifiedCount)
        assertEquals(100L, res.state.totalVerifiedBytes)

        // Item 2 cancelled, partial destination deleted
        assertEquals(ItemPhase.Cancelled, res.state.itemStates[itemId2]!!.phase)
        assertTrue(res.effects.any { it is ReducerEffect.DeletePartialDestination && it.itemId == itemId2 })
        assertFalse(res.effects.any { it is ReducerEffect.DeletePartialDestination && it.itemId == itemId1 })
    }

    @Test
    fun cancel_received_from_peer_sends_cancel_ack_and_cleans_up() {
        val itemState1 = ItemTransferState(item = item1, phase = ItemPhase.Streaming, bulkRef = BulkRef("bulk-1"))
        val session = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Receiver,
            phase = SessionPhase.TransferringItem,
            batch = batch,
            currentItemIndex = 0,
            itemStates = mapOf(itemId1 to itemState1)
        )

        val cancelBody = CancelBody(batchId = batchId, code = "USER_CANCELLED")
        val msgId = MessageId("f0000000-0000-4000-8000-000000000003")
        val res = TransferReducer.reduce(session, ReducerEvent.CancelReceived(cancelBody, msgId, 5L, 100L))

        assertEquals(SessionPhase.Cancelled, res.state.phase)
        assertTrue(res.effects.any { it is ReducerEffect.SendControl && it.envelope.type == MessageTypes.CANCEL_ACK })
        assertTrue(res.effects.any { it is ReducerEffect.CloseTransportChannel })
        assertTrue(res.effects.any { it is ReducerEffect.NotifyTerminalOutcome && it.phase == SessionPhase.Cancelled })
    }

    @Test
    fun offer_rejected_by_receiver_transitions_both_sides_to_cancelled() {
        // Receiver rejects
        val rSession = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Receiver,
            phase = SessionPhase.AwaitingConsent,
            batch = batch,
            itemStates = batch.items.associate { it.id to ItemTransferState(it) }
        )
        val rRes = TransferReducer.reduce(rSession, ReducerEvent.SubmitOfferDecision(accepted = false, rejectionCode = "USER_DECLINED", timestampMs = 100L))
        assertEquals(SessionPhase.Cancelled, rRes.state.phase)
        assertTrue(rRes.effects.any { it is ReducerEffect.SendControl && it.envelope.type == MessageTypes.OFFER_DECISION })
        assertTrue(rRes.effects.any { it is ReducerEffect.CloseTransportChannel })
        val decBody = ProtocolCodec.decodeBody((rRes.effects.first { it is ReducerEffect.SendControl } as ReducerEffect.SendControl).envelope) as OfferDecisionBody
        assertFalse(decBody.accepted)
        assertEquals("USER_DECLINED", decBody.code)

        // Sender receives rejection
        val sSession = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = SessionPhase.AwaitingConsent,
            batch = batch,
            itemStates = batch.items.associate { it.id to ItemTransferState(it) }
        )
        val sRes = TransferReducer.reduce(sSession, ReducerEvent.OfferDecisionReceived(decBody, MessageId("f0000000-0000-4000-8000-000000000004"), 2L, 105L))
        assertEquals(SessionPhase.Cancelled, sRes.state.phase)
        assertTrue(sRes.effects.any { it is ReducerEffect.CloseTransportChannel })
        assertTrue(sRes.effects.any { it is ReducerEffect.NotifyTerminalOutcome && it.phase == SessionPhase.Cancelled })
    }

    @Test
    fun terminal_states_are_absorbing_and_ignore_late_events() {
        val terminalPhases = listOf(
            SessionPhase.Completed,
            SessionPhase.Cancelled,
            SessionPhase.Failed,
            SessionPhase.Interrupted,
            SessionPhase.DeliveryUnconfirmed
        )

        for (phase in terminalPhases) {
            val terminalState = SessionState(
                sessionId = sessionId,
                role = DeviceRole.Receiver,
                phase = phase,
                batch = batch,
                itemStates = mapOf(itemId1 to ItemTransferState(item1, phase = ItemPhase.Verified))
            )

            // Attempt late progress
            val r1 = TransferReducer.reduce(terminalState, ReducerEvent.StreamProgress(itemId1, 500L, 200L))
            assertEquals(terminalState, r1.state)
            assertTrue(r1.effects.isEmpty())

            // Attempt late EOF
            val r2 = TransferReducer.reduce(terminalState, ReducerEvent.StreamEof(itemId1, 100L, digest1, 201L))
            assertEquals(terminalState, r2.state)
            assertTrue(r2.effects.isEmpty())

            // Attempt late Cancel
            val r3 = TransferReducer.reduce(terminalState, ReducerEvent.RequestCancel("LATE_CANCEL", 202L))
            assertEquals(terminalState, r3.state)
            assertTrue(r3.effects.isEmpty())

            // Attempt late Timeout
            val r4 = TransferReducer.reduce(terminalState, ReducerEvent.Timeout("transfer", 203L))
            assertEquals(terminalState, r4.state)
            assertTrue(r4.effects.isEmpty())
        }
    }

    @Test
    fun receipt_ambiguity_timeout_when_awaiting_receipt_transitions_to_delivery_unconfirmed() {
        val sSession = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = SessionPhase.AwaitingReceipt,
            batch = batch,
            itemStates = mapOf(
                itemId1 to ItemTransferState(item1, phase = ItemPhase.Verified),
                itemId2 to ItemTransferState(item2, phase = ItemPhase.Verified)
            )
        )

        val res = TransferReducer.reduce(sSession, ReducerEvent.Timeout("AwaitingReceipt", 300L))

        assertEquals(SessionPhase.DeliveryUnconfirmed, res.state.phase)
        assertTrue(res.state.isTerminal)
        assertEquals("DELIVERY_UNCONFIRMED", res.state.failure?.code)
        assertTrue(res.effects.any { it is ReducerEffect.CloseTransportChannel })
        assertTrue(res.effects.any { it is ReducerEffect.NotifyTerminalOutcome && it.phase == SessionPhase.DeliveryUnconfirmed })
    }

    @Test
    fun transport_failure_with_peer_lost_transitions_to_interrupted() {
        val session = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Receiver,
            phase = SessionPhase.TransferringItem,
            batch = batch,
            currentItemIndex = 0,
            itemStates = mapOf(itemId1 to ItemTransferState(item1, phase = ItemPhase.Streaming, bulkRef = BulkRef("bulk-1")))
        )

        val failure = TransferFailure.PeerLost("Connection dropped unexpectedly")
        val res = TransferReducer.reduce(session, ReducerEvent.TransportFailure(failure, 100L))

        assertEquals(SessionPhase.Interrupted, res.state.phase)
        assertEquals(ItemPhase.Interrupted, res.state.itemStates[itemId1]!!.phase)
        assertTrue(res.effects.any { it is ReducerEffect.DeletePartialDestination && it.itemId == itemId1 })
        assertTrue(res.effects.any { it is ReducerEffect.CloseTransportChannel })
        assertTrue(res.effects.any { it is ReducerEffect.NotifyTerminalOutcome && it.phase == SessionPhase.Interrupted })
    }

    @Test
    fun transport_failure_with_transfer_interrupted_transitions_to_interrupted() {
        val session = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = SessionPhase.TransferringItem,
            batch = batch,
            currentItemIndex = 0,
            itemStates = mapOf(itemId1 to ItemTransferState(item1, phase = ItemPhase.Streaming, bulkRef = BulkRef("bulk-1")))
        )

        val failure = TransferFailure.TransferInterrupted("Socket closed")
        val res = TransferReducer.reduce(session, ReducerEvent.TransportFailure(failure, 100L))

        assertEquals(SessionPhase.Interrupted, res.state.phase)
        assertEquals(ItemPhase.Interrupted, res.state.itemStates[itemId1]!!.phase)
    }

    @Test
    fun timeout_in_active_phase_transitions_to_failed() {
        val session = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = SessionPhase.TransferringItem,
            batch = batch,
            currentItemIndex = 0,
            itemStates = mapOf(itemId1 to ItemTransferState(item1, phase = ItemPhase.Streaming))
        )

        val res = TransferReducer.reduce(session, ReducerEvent.Timeout("TransferringItem", 100L))

        assertEquals(SessionPhase.Failed, res.state.phase)
        assertEquals("TIMEOUT", res.state.failure?.code)
    }

    @Test
    fun partialBatchDisconnect_retainsVerifiedItemsAndMarksRemainingUnfinished() {
        val multiBatch = TransferBatch(
            id = batchId,
            items = listOf(item1, item2)
        )
        val session = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = SessionPhase.TransferringItem,
            batch = multiBatch,
            currentItemIndex = 1,
            itemStates = mapOf(
                itemId1 to ItemTransferState(item1, phase = ItemPhase.Verified),
                itemId2 to ItemTransferState(item2, phase = ItemPhase.Streaming, bulkRef = BulkRef("bulk-2"))
            )
        )

        val failure = TransferFailure.PeerLost("Connection reset")
        val res = TransferReducer.reduce(session, ReducerEvent.TransportFailure(failure, 100L))

        assertEquals(SessionPhase.Interrupted, res.state.phase)
        assertEquals(ItemPhase.Verified, res.state.itemStates[itemId1]!!.phase)
        assertEquals(ItemPhase.Interrupted, res.state.itemStates[itemId2]!!.phase)
        assertEquals(1, res.state.verifiedCount)
        assertEquals(1, res.state.unfinishedCount)
        assertEquals(listOf(item2), res.state.unfinishedItems)

        val retryBatch = res.state.createRetryBatch()
        assertNotNull(retryBatch)
        assertEquals(1, retryBatch!!.items.size)
        assertEquals(itemId2, retryBatch.items[0].id)
        assertFalse("Retry batch must have a new unique ID", retryBatch.id == multiBatch.id)
    }

    @Test
    fun createRetryBatch_whenAllItemsVerified_returnsNull() {
        val session = SessionState(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            phase = SessionPhase.Completed,
            batch = batch,
            itemStates = mapOf(
                itemId1 to ItemTransferState(item1, phase = ItemPhase.Verified),
                itemId2 to ItemTransferState(item2, phase = ItemPhase.Verified)
            )
        )

        assertEquals(0, session.unfinishedCount)
        assertTrue(session.unfinishedItems.isEmpty())
        assertNull(session.createRetryBatch())
    }

    @Test
    fun disconnectDuringEachPhase_mapsToInterruptedOrDeliveryUnconfirmed() {
        // Disconnect during Negotiating
        val negotiating = SessionState(sessionId = sessionId, role = DeviceRole.Receiver, phase = SessionPhase.Negotiating)
        val r1 = TransferReducer.reduce(negotiating, ReducerEvent.TransportFailure(TransferFailure.PeerLost("lost"), 100L))
        assertEquals(SessionPhase.Interrupted, r1.state.phase)

        // Disconnect during Offering
        val offering = SessionState(sessionId = sessionId, role = DeviceRole.Sender, phase = SessionPhase.Offering, batch = batch)
        val r2 = TransferReducer.reduce(offering, ReducerEvent.TransportFailure(TransferFailure.PeerLost("lost"), 100L))
        assertEquals(SessionPhase.Interrupted, r2.state.phase)

        // Disconnect during AwaitingConsent
        val awaitingConsent = SessionState(sessionId = sessionId, role = DeviceRole.Receiver, phase = SessionPhase.AwaitingConsent, batch = batch)
        val r3 = TransferReducer.reduce(awaitingConsent, ReducerEvent.TransportFailure(TransferFailure.PeerLost("lost"), 100L))
        assertEquals(SessionPhase.Interrupted, r3.state.phase)

        // Disconnect during TransferringItem
        val transferring = SessionState(sessionId = sessionId, role = DeviceRole.Sender, phase = SessionPhase.TransferringItem, batch = batch, itemStates = mapOf(itemId1 to ItemTransferState(item1, phase = ItemPhase.Streaming)))
        val r4 = TransferReducer.reduce(transferring, ReducerEvent.TransportFailure(TransferFailure.PeerLost("lost"), 100L))
        assertEquals(SessionPhase.Interrupted, r4.state.phase)

        // Disconnect during AwaitingReceipt (sender) -> DeliveryUnconfirmed
        val awaitingReceipt = SessionState(sessionId = sessionId, role = DeviceRole.Sender, phase = SessionPhase.AwaitingReceipt, batch = batch, itemStates = mapOf(itemId1 to ItemTransferState(item1, phase = ItemPhase.Verified)))
        val r5 = TransferReducer.reduce(awaitingReceipt, ReducerEvent.TransportFailure(TransferFailure.PeerLost("lost"), 100L))
        assertEquals(SessionPhase.DeliveryUnconfirmed, r5.state.phase)
        assertEquals("DELIVERY_UNCONFIRMED", r5.state.failure?.code)
    }
}
