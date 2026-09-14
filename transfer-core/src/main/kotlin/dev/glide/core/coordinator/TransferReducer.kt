package dev.glide.core.coordinator

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.EffectiveLimits
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.MessageId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferFailure
import dev.glide.core.domain.TransferProgress
import dev.glide.core.protocol.BatchEndBody
import dev.glide.core.protocol.BatchResultBody
import dev.glide.core.protocol.CancelAckBody
import dev.glide.core.protocol.CancelBody
import dev.glide.core.protocol.DecimalByteCount
import dev.glide.core.protocol.Envelope
import dev.glide.core.protocol.ItemEndBody
import dev.glide.core.protocol.ItemReadyBody
import dev.glide.core.protocol.ItemResultBody
import dev.glide.core.protocol.ItemStartBody
import dev.glide.core.protocol.MessageTypes
import dev.glide.core.protocol.NegotiationValidator
import dev.glide.core.protocol.OfferAssembler
import dev.glide.core.protocol.OfferDecisionBody
import dev.glide.core.protocol.OfferPaginator
import dev.glide.core.protocol.ProtocolCodec
import java.util.UUID

data class ReducerResult(
    val state: SessionState,
    val effects: List<ReducerEffect> = emptyList()
)

object TransferReducer {

    fun reduce(state: SessionState, event: ReducerEvent): ReducerResult {
        // Invariant: Terminal states are absorbing. Late events never regress state.
        if (state.isTerminal) {
            return ReducerResult(state, emptyList())
        }

        return when (event) {
            is ReducerEvent.RequestCancel -> handleRequestCancel(state, event)
            is ReducerEvent.CancelReceived -> handleCancelReceived(state, event)
            is ReducerEvent.TransportFailure -> handleTransportFailure(state, event)
            is ReducerEvent.Timeout -> handleTimeout(state, event)

            is ReducerEvent.StartNegotiation -> handleStartNegotiation(state, event)
            is ReducerEvent.HelloReceived -> handleHelloReceived(state, event)
            is ReducerEvent.HelloAckReceived -> handleHelloAckReceived(state, event)

            is ReducerEvent.SubmitOffer -> handleSubmitOffer(state, event)
            is ReducerEvent.OfferBeginReceived -> handleOfferBeginReceived(state, event)
            is ReducerEvent.OfferPageReceived -> handleOfferPageReceived(state, event)
            is ReducerEvent.OfferEndReceived -> handleOfferEndReceived(state, event)
            is ReducerEvent.SubmitOfferDecision -> handleSubmitOfferDecision(state, event)
            is ReducerEvent.OfferDecisionReceived -> handleOfferDecisionReceived(state, event)

            is ReducerEvent.ItemStartReceived -> handleItemStartReceived(state, event)
            is ReducerEvent.ItemReadyReceived -> handleItemReadyReceived(state, event)
            is ReducerEvent.StreamProgress -> handleStreamProgress(state, event)
            is ReducerEvent.StreamEof -> handleStreamEof(state, event)
            is ReducerEvent.TransportPayloadSuccess -> handleTransportPayloadSuccess(state, event)
            is ReducerEvent.ItemEndReceived -> handleItemEndReceived(state, event)
            is ReducerEvent.LocalCommitSuccess -> handleLocalCommitSuccess(state, event)
            is ReducerEvent.ItemResultReceived -> handleItemResultReceived(state, event)

            is ReducerEvent.BatchEndReceived -> handleBatchEndReceived(state, event)
            is ReducerEvent.BatchResultReceived -> handleBatchResultReceived(state, event)
        }
    }

    private fun handleStartNegotiation(state: SessionState, event: ReducerEvent.StartNegotiation): ReducerResult {
        if (state.role != DeviceRole.Sender || state.phase != SessionPhase.Idle) {
            return ReducerResult(state, emptyList())
        }
        val hello = NegotiationValidator.createHello(
            role = state.role,
            ephemeralPeerId = event.ephemeralPeerId,
            displayName = event.displayName
        )
        val (env, nextSeq) = makeEnvelope(state, MessageTypes.HELLO, ProtocolCodec.encodeBody(hello))
        val nextState = state.copy(
            phase = SessionPhase.Negotiating,
            outboundHello = hello,
            nextOutboundSeq = nextSeq,
            lastActivityMonotonicMs = event.timestampMs
        )
        return ReducerResult(nextState, listOf(ReducerEffect.SendControl(env)))
    }

    private fun handleHelloReceived(state: SessionState, event: ReducerEvent.HelloReceived): ReducerResult {
        if (state.role != DeviceRole.Receiver || state.phase != SessionPhase.Idle) {
            return ReducerResult(state, emptyList())
        }
        val limits = state.negotiated?.effectiveLimits ?: EffectiveLimits()
        val peerId = UUID.randomUUID().toString()
        val ack = NegotiationValidator.createHelloAck(
            hello = event.hello,
            ephemeralPeerId = peerId,
            displayName = "Glide Device",
            requestedLimits = limits
        )
        val session = NegotiationValidator.validateHelloAck(event.hello, ack)
        val (env, nextSeq) = makeEnvelope(state, MessageTypes.HELLO_ACK, ProtocolCodec.encodeBody(ack))
        val nextState = state.copy(
            phase = SessionPhase.Offering,
            negotiated = session,
            nextOutboundSeq = nextSeq,
            lastActivityMonotonicMs = event.timestampMs
        )
        return ReducerResult(nextState, listOf(ReducerEffect.SendControl(env)))
    }

    private fun handleHelloAckReceived(state: SessionState, event: ReducerEvent.HelloAckReceived): ReducerResult {
        if (state.role != DeviceRole.Sender || state.phase != SessionPhase.Negotiating) {
            return ReducerResult(state, emptyList())
        }
        val hello = state.outboundHello ?: return ReducerResult(state, emptyList())
        val session = NegotiationValidator.validateHelloAck(hello, event.ack)
        val nextState = state.copy(
            phase = SessionPhase.Offering,
            negotiated = session,
            lastActivityMonotonicMs = event.timestampMs
        )
        return ReducerResult(nextState, emptyList())
    }

    private fun handleSubmitOffer(state: SessionState, event: ReducerEvent.SubmitOffer): ReducerResult {
        if (state.role != DeviceRole.Sender || state.phase != SessionPhase.Offering) {
            return ReducerResult(state, emptyList())
        }
        val limits = state.negotiated?.effectiveLimits ?: EffectiveLimits()
        val paged = OfferPaginator.paginate(event.batch, limits)

        val effects = mutableListOf<ReducerEffect>()
        var curState = state

        val (beginEnv, seq1) = makeEnvelope(curState, MessageTypes.OFFER_BEGIN, ProtocolCodec.encodeBody(paged.begin))
        effects.add(ReducerEffect.SendControl(beginEnv))
        curState = curState.copy(nextOutboundSeq = seq1)

        for (page in paged.pages) {
            val (pageEnv, seqN) = makeEnvelope(curState, MessageTypes.OFFER_PAGE, ProtocolCodec.encodeBody(page))
            effects.add(ReducerEffect.SendControl(pageEnv))
            curState = curState.copy(nextOutboundSeq = seqN)
        }

        val (endEnv, seqEnd) = makeEnvelope(curState, MessageTypes.OFFER_END, ProtocolCodec.encodeBody(paged.end))
        effects.add(ReducerEffect.SendControl(endEnv))
        curState = curState.copy(nextOutboundSeq = seqEnd)

        val itemStates = event.batch.items.associate { it.id to ItemTransferState(item = it, phase = ItemPhase.Queued) }
        val nextState = curState.copy(
            phase = SessionPhase.AwaitingConsent,
            batch = event.batch,
            itemStates = itemStates,
            lastActivityMonotonicMs = event.timestampMs
        )
        return ReducerResult(nextState, effects)
    }

    private fun handleOfferBeginReceived(state: SessionState, event: ReducerEvent.OfferBeginReceived): ReducerResult {
        if (state.role != DeviceRole.Receiver || state.phase != SessionPhase.Offering) {
            return ReducerResult(state, emptyList())
        }
        val limits = state.negotiated?.effectiveLimits ?: EffectiveLimits()
        val assembler = OfferAssembler(event.begin, limits)
        return ReducerResult(
            state.copy(offerAssembler = assembler, lastActivityMonotonicMs = event.timestampMs),
            emptyList()
        )
    }

    private fun handleOfferPageReceived(state: SessionState, event: ReducerEvent.OfferPageReceived): ReducerResult {
        if (state.role != DeviceRole.Receiver || state.phase != SessionPhase.Offering) {
            return ReducerResult(state, emptyList())
        }
        val assembler = state.offerAssembler ?: return ReducerResult(state, emptyList())
        assembler.addPage(event.page)
        return ReducerResult(state.copy(lastActivityMonotonicMs = event.timestampMs), emptyList())
    }

    private fun handleOfferEndReceived(state: SessionState, event: ReducerEvent.OfferEndReceived): ReducerResult {
        if (state.role != DeviceRole.Receiver || state.phase != SessionPhase.Offering) {
            return ReducerResult(state, emptyList())
        }
        val assembler = state.offerAssembler ?: return ReducerResult(state, emptyList())
        val offer = assembler.finish(event.end)
        val itemStates = offer.batch.items.associate { it.id to ItemTransferState(item = it, phase = ItemPhase.Queued) }
        val nextState = state.copy(
            phase = SessionPhase.AwaitingConsent,
            batch = offer.batch,
            itemStates = itemStates,
            lastActivityMonotonicMs = event.timestampMs
        )
        return ReducerResult(nextState, emptyList())
    }

    private fun handleSubmitOfferDecision(state: SessionState, event: ReducerEvent.SubmitOfferDecision): ReducerResult {
        if (state.role != DeviceRole.Receiver || state.phase != SessionPhase.AwaitingConsent) {
            return ReducerResult(state, emptyList())
        }
        val batch = state.batch ?: return ReducerResult(state, emptyList())
        val decision = OfferDecisionBody(
            batchId = batch.id,
            accepted = event.accepted,
            code = if (event.accepted) null else (event.rejectionCode ?: "USER_REJECTED")
        )
        val (env, nextSeq) = makeEnvelope(state, MessageTypes.OFFER_DECISION, ProtocolCodec.encodeBody(decision))

        return if (event.accepted) {
            val nextState = state.copy(
                phase = SessionPhase.PreparingItem,
                currentItemIndex = 0,
                nextOutboundSeq = nextSeq,
                lastActivityMonotonicMs = event.timestampMs
            )
            ReducerResult(nextState, listOf(ReducerEffect.SendControl(env)))
        } else {
            val failure = TransferFailure.ConnectionRejected(decision.code ?: "USER_REJECTED")
            val nextItemStates = state.itemStates.mapValues { (_, itemState) ->
                itemState.copy(phase = ItemPhase.Cancelled)
            }
            val nextState = state.copy(
                phase = SessionPhase.Cancelled,
                itemStates = nextItemStates,
                failure = failure,
                nextOutboundSeq = nextSeq,
                lastActivityMonotonicMs = event.timestampMs
            )
            ReducerResult(
                nextState,
                listOf(
                    ReducerEffect.SendControl(env),
                    ReducerEffect.CloseTransportChannel,
                    ReducerEffect.NotifyTerminalOutcome(SessionPhase.Cancelled, failure)
                )
            )
        }
    }

    private fun handleOfferDecisionReceived(state: SessionState, event: ReducerEvent.OfferDecisionReceived): ReducerResult {
        if (state.role != DeviceRole.Sender || state.phase != SessionPhase.AwaitingConsent) {
            return ReducerResult(state, emptyList())
        }
        val batch = state.batch ?: return ReducerResult(state, emptyList())

        return if (event.decision.accepted) {
            val firstItem = batch.items[0]
            val bulkRef = event.bulkRef ?: BulkRef("bulk-${state.sessionId.value.take(8)}-0")
            val startBody = ItemStartBody(
                batchId = batch.id,
                itemId = firstItem.id,
                bulkRef = bulkRef,
                sizeBytes = DecimalByteCount.serialize(firstItem.sizeBytes)
            )
            val (env, nextSeq) = makeEnvelope(state, MessageTypes.ITEM_START, ProtocolCodec.encodeBody(startBody))
            val updatedItem = state.itemStates[firstItem.id]!!.copy(
                phase = ItemPhase.Preparing,
                bulkRef = bulkRef
            )
            val nextState = state.copy(
                phase = SessionPhase.PreparingItem,
                currentItemIndex = 0,
                itemStates = state.itemStates + (firstItem.id to updatedItem),
                nextOutboundSeq = nextSeq,
                lastActivityMonotonicMs = event.timestampMs
            )
            ReducerResult(nextState, listOf(ReducerEffect.SendControl(env)))
        } else {
            val failure = TransferFailure.UserRejected(event.decision.code ?: "USER_REJECTED")
            val nextItemStates = state.itemStates.mapValues { (_, itemState) ->
                itemState.copy(phase = ItemPhase.Cancelled)
            }
            val nextState = state.copy(
                phase = SessionPhase.Cancelled,
                itemStates = nextItemStates,
                failure = failure,
                lastActivityMonotonicMs = event.timestampMs
            )
            ReducerResult(
                nextState,
                listOf(
                    ReducerEffect.CloseTransportChannel,
                    ReducerEffect.NotifyTerminalOutcome(SessionPhase.Cancelled, failure)
                )
            )
        }
    }

    private fun handleItemStartReceived(state: SessionState, event: ReducerEvent.ItemStartReceived): ReducerResult {
        if (state.role != DeviceRole.Receiver || (state.phase != SessionPhase.PreparingItem && state.phase != SessionPhase.AwaitingConsent)) {
            return ReducerResult(state, emptyList())
        }
        val currentItem = state.currentItemState?.item ?: return ReducerResult(state, emptyList())
        if (event.start.itemId != currentItem.id) {
            return ReducerResult(state, emptyList())
        }

        val readyBody = ItemReadyBody(batchId = event.start.batchId, itemId = currentItem.id)
        val (env, nextSeq) = makeEnvelope(state, MessageTypes.ITEM_READY, ProtocolCodec.encodeBody(readyBody))

        val updatedItem = state.itemStates[currentItem.id]!!.copy(
            phase = ItemPhase.Streaming,
            bulkRef = event.start.bulkRef
        )
        val nextState = state.copy(
            phase = SessionPhase.TransferringItem,
            itemStates = state.itemStates + (currentItem.id to updatedItem),
            nextOutboundSeq = nextSeq,
            lastActivityMonotonicMs = event.timestampMs
        )
        return ReducerResult(
            nextState,
            listOf(
                ReducerEffect.PrepareDestinationStorage(currentItem.id, currentItem.sizeBytes),
                ReducerEffect.SendControl(env)
            )
        )
    }

    private fun handleItemReadyReceived(state: SessionState, event: ReducerEvent.ItemReadyReceived): ReducerResult {
        if (state.role != DeviceRole.Sender || state.phase != SessionPhase.PreparingItem) {
            return ReducerResult(state, emptyList())
        }
        val currentItem = state.currentItemState ?: return ReducerResult(state, emptyList())
        if (event.ready.itemId != currentItem.item.id) {
            return ReducerResult(state, emptyList())
        }

        val bulkRef = currentItem.bulkRef ?: BulkRef("bulk-${state.sessionId.value.take(8)}-${state.currentItemIndex}")
        val updatedItem = currentItem.copy(phase = ItemPhase.Streaming, bulkRef = bulkRef)
        val nextState = state.copy(
            phase = SessionPhase.TransferringItem,
            itemStates = state.itemStates + (currentItem.item.id to updatedItem),
            lastActivityMonotonicMs = event.timestampMs
        )
        return ReducerResult(
            nextState,
            listOf(ReducerEffect.StartBulkStream(currentItem.item.id, bulkRef))
        )
    }

    private fun handleStreamProgress(state: SessionState, event: ReducerEvent.StreamProgress): ReducerResult {
        val curItem = state.itemStates[event.itemId] ?: return ReducerResult(state, emptyList())
        if (curItem.phase != ItemPhase.Streaming) {
            return ReducerResult(state, emptyList())
        }
        val updatedItem = curItem.copy(transferredBytes = event.bytesTransferred)
        val updatedMap = state.itemStates + (event.itemId to updatedItem)

        val totalTransferred = updatedMap.values.sumOf { it.transferredBytes }
        val totalBytes = state.batch?.totalBytes ?: 0L
        val progress = TransferProgress(
            transferredBytes = totalTransferred,
            totalBytes = totalBytes,
            currentItemIndex = state.currentItemIndex,
            totalItems = state.batch?.items?.size ?: 0
        )
        val nextState = state.copy(
            itemStates = updatedMap,
            lastActivityMonotonicMs = maxOf(state.lastActivityMonotonicMs, event.timestampMs)
        )
        return ReducerResult(nextState, listOf(ReducerEffect.UpdateTransferProgress(progress)))
    }

    private fun handleStreamEof(state: SessionState, event: ReducerEvent.StreamEof): ReducerResult {
        val curItem = state.itemStates[event.itemId] ?: return ReducerResult(state, emptyList())
        if (curItem.eofReceived || curItem.phase == ItemPhase.Committing || curItem.phase.isTerminal) {
            return ReducerResult(state, emptyList())
        }
        val updatedItem = curItem.copy(
            eofReceived = true,
            actualBytesAtEof = event.actualBytes,
            actualDigest = event.digest,
            transferredBytes = event.actualBytes
        )
        val nextState = state.copy(
            itemStates = state.itemStates + (event.itemId to updatedItem),
            lastActivityMonotonicMs = maxOf(state.lastActivityMonotonicMs, event.timestampMs)
        )

        return if (state.role == DeviceRole.Receiver) {
            evaluateReceiverItemVerification(nextState, event.itemId)
        } else {
            evaluateSenderItemVerification(nextState, event.itemId)
        }
    }

    private fun handleTransportPayloadSuccess(state: SessionState, event: ReducerEvent.TransportPayloadSuccess): ReducerResult {
        val curItem = state.itemStates[event.itemId] ?: return ReducerResult(state, emptyList())
        if (curItem.transportSuccessReceived || curItem.phase == ItemPhase.Committing || curItem.phase.isTerminal) {
            return ReducerResult(state, emptyList())
        }
        val updatedItem = curItem.copy(transportSuccessReceived = true)
        val nextState = state.copy(
            itemStates = state.itemStates + (event.itemId to updatedItem),
            lastActivityMonotonicMs = maxOf(state.lastActivityMonotonicMs, event.timestampMs)
        )

        return if (state.role == DeviceRole.Receiver) {
            evaluateReceiverItemVerification(nextState, event.itemId)
        } else {
            evaluateSenderItemVerification(nextState, event.itemId)
        }
    }

    private fun handleItemEndReceived(state: SessionState, event: ReducerEvent.ItemEndReceived): ReducerResult {
        if (state.role != DeviceRole.Receiver) return ReducerResult(state, emptyList())
        val curItem = state.itemStates[event.end.itemId] ?: return ReducerResult(state, emptyList())
        if (curItem.expectedDigest != null || curItem.phase == ItemPhase.Committing || curItem.phase.isTerminal) {
            return ReducerResult(state, emptyList())
        }
        val updatedItem = curItem.copy(expectedDigest = event.end.sha256)
        val nextState = state.copy(
            itemStates = state.itemStates + (event.end.itemId to updatedItem),
            lastActivityMonotonicMs = maxOf(state.lastActivityMonotonicMs, event.timestampMs)
        )
        return evaluateReceiverItemVerification(nextState, event.end.itemId)
    }

    private fun evaluateReceiverItemVerification(state: SessionState, itemId: ItemId): ReducerResult {
        val itemState = state.itemStates[itemId] ?: return ReducerResult(state, emptyList())
        if (itemState.phase == ItemPhase.Committing || itemState.phase == ItemPhase.Verified || itemState.phase.isTerminal) {
            return ReducerResult(state, emptyList())
        }

        // Predicate: stream EOF + transport success + item end digest received
        if (itemState.eofReceived && itemState.transportSuccessReceived && itemState.expectedDigest != null && itemState.actualDigest != null) {
            // Check integrity
            val expectedBytes = itemState.item.sizeBytes
            val actualBytes = itemState.actualBytesAtEof ?: -1L
            val hashMatch = itemState.actualDigest == itemState.expectedDigest && actualBytes == expectedBytes

            return if (hashMatch) {
                val committingItem = itemState.copy(phase = ItemPhase.Committing)
                val nextState = state.copy(
                    phase = SessionPhase.VerifyingItem,
                    itemStates = state.itemStates + (itemId to committingItem)
                )
                ReducerResult(nextState, listOf(ReducerEffect.CommitDestinationFile(itemId)))
            } else {
                val failure = TransferFailure.IntegrityMismatch(
                    expected = itemState.expectedDigest,
                    actual = itemState.actualDigest
                )
                val failedItem = itemState.copy(phase = ItemPhase.Failed, failure = failure)
                val remainingItems = state.itemStates.mapValues { (id, itState) ->
                    if (id == itemId) failedItem else if (itState.phase == ItemPhase.Queued) itState.copy(phase = ItemPhase.NotAttempted) else itState
                }
                val resultBody = ItemResultBody(
                    batchId = state.batch!!.id,
                    itemId = itemId,
                    status = "failed",
                    code = failure.code
                )
                val (env, nextSeq) = makeEnvelope(state, MessageTypes.ITEM_RESULT, ProtocolCodec.encodeBody(resultBody))
                val nextState = state.copy(
                    phase = SessionPhase.Failed,
                    itemStates = remainingItems,
                    failure = failure,
                    nextOutboundSeq = nextSeq
                )
                ReducerResult(
                    nextState,
                    listOf(
                        ReducerEffect.DeletePartialDestination(itemId),
                        ReducerEffect.SendControl(env),
                        ReducerEffect.CloseTransportChannel,
                        ReducerEffect.NotifyTerminalOutcome(SessionPhase.Failed, failure)
                    )
                )
            }
        }
        return ReducerResult(state, emptyList())
    }

    private fun handleLocalCommitSuccess(state: SessionState, event: ReducerEvent.LocalCommitSuccess): ReducerResult {
        val curItem = state.itemStates[event.itemId] ?: return ReducerResult(state, emptyList())
        val verifiedItem = curItem.copy(phase = ItemPhase.Verified)
        val updatedMap = state.itemStates + (event.itemId to verifiedItem)

        val resultBody = ItemResultBody(
            batchId = state.batch!!.id,
            itemId = event.itemId,
            status = "verified",
            sizeBytes = DecimalByteCount.serialize(curItem.item.sizeBytes),
            sha256 = curItem.actualDigest
        )
        val (env, nextSeq) = makeEnvelope(state, MessageTypes.ITEM_RESULT, ProtocolCodec.encodeBody(resultBody))

        val isLastItem = state.currentItemIndex + 1 >= state.batch.items.size
        val nextState = if (isLastItem) {
            state.copy(
                phase = SessionPhase.AwaitingReceipt,
                itemStates = updatedMap,
                nextOutboundSeq = nextSeq,
                lastActivityMonotonicMs = event.timestampMs
            )
        } else {
            state.copy(
                phase = SessionPhase.PreparingItem,
                currentItemIndex = state.currentItemIndex + 1,
                itemStates = updatedMap,
                nextOutboundSeq = nextSeq,
                lastActivityMonotonicMs = event.timestampMs
            )
        }
        return ReducerResult(nextState, listOf(ReducerEffect.SendControl(env)))
    }

    private fun evaluateSenderItemVerification(state: SessionState, itemId: ItemId): ReducerResult {
        val itemState = state.itemStates[itemId] ?: return ReducerResult(state, emptyList())
        if (itemState.phase != ItemPhase.Streaming && itemState.phase != ItemPhase.Preparing) {
            return ReducerResult(state, emptyList())
        }
        if (itemState.eofReceived && itemState.transportSuccessReceived && itemState.actualDigest != null) {
            val itemEnd = ItemEndBody(
                batchId = state.batch!!.id,
                itemId = itemId,
                actualBytes = DecimalByteCount.serialize(itemState.actualBytesAtEof ?: itemState.item.sizeBytes),
                sha256 = itemState.actualDigest
            )
            val (env, nextSeq) = makeEnvelope(state, MessageTypes.ITEM_END, ProtocolCodec.encodeBody(itemEnd))
            val updatedItem = itemState.copy(phase = ItemPhase.Verifying)
            val nextState = state.copy(
                phase = SessionPhase.VerifyingItem,
                itemStates = state.itemStates + (itemId to updatedItem),
                nextOutboundSeq = nextSeq
            )
            return ReducerResult(nextState, listOf(ReducerEffect.SendControl(env)))
        }
        return ReducerResult(state, emptyList())
    }

    private fun handleItemResultReceived(state: SessionState, event: ReducerEvent.ItemResultReceived): ReducerResult {
        if (state.role != DeviceRole.Sender) return ReducerResult(state, emptyList())
        val curItem = state.itemStates[event.result.itemId] ?: return ReducerResult(state, emptyList())

        return if (event.result.status == "verified") {
            val verifiedItem = curItem.copy(phase = ItemPhase.Verified)
            val updatedMap = state.itemStates + (event.result.itemId to verifiedItem)
            val batch = state.batch!!
            val isLastItem = state.currentItemIndex + 1 >= batch.items.size

            if (isLastItem) {
                val batchEnd = BatchEndBody(batchId = batch.id)
                val (env, nextSeq) = makeEnvelope(state, MessageTypes.BATCH_END, ProtocolCodec.encodeBody(batchEnd))
                val nextState = state.copy(
                    phase = SessionPhase.AwaitingReceipt,
                    itemStates = updatedMap,
                    nextOutboundSeq = nextSeq,
                    lastActivityMonotonicMs = event.timestampMs
                )
                ReducerResult(nextState, listOf(ReducerEffect.SendControl(env)))
            } else {
                val nextIdx = state.currentItemIndex + 1
                val nextItem = batch.items[nextIdx]
                val bulkRef = event.bulkRef ?: BulkRef("bulk-${state.sessionId.value.take(8)}-$nextIdx")
                val startBody = ItemStartBody(
                    batchId = batch.id,
                    itemId = nextItem.id,
                    bulkRef = bulkRef,
                    sizeBytes = DecimalByteCount.serialize(nextItem.sizeBytes)
                )
                val (env, nextSeq) = makeEnvelope(state, MessageTypes.ITEM_START, ProtocolCodec.encodeBody(startBody))
                val preparingItem = updatedMap[nextItem.id]!!.copy(
                    phase = ItemPhase.Preparing,
                    bulkRef = bulkRef
                )
                val nextState = state.copy(
                    phase = SessionPhase.PreparingItem,
                    currentItemIndex = nextIdx,
                    itemStates = updatedMap + (nextItem.id to preparingItem),
                    nextOutboundSeq = nextSeq,
                    lastActivityMonotonicMs = event.timestampMs
                )
                ReducerResult(nextState, listOf(ReducerEffect.SendControl(env)))
            }
        } else {
            val failure = when (event.result.code) {
                "INTEGRITY_MISMATCH" -> TransferFailure.IntegrityMismatch(
                    expected = curItem.actualDigest ?: Sha256Digest("0".repeat(64)),
                    actual = Sha256Digest("0".repeat(64))
                )
                "INSUFFICIENT_STORAGE" -> TransferFailure.InsufficientStorage(curItem.item.sizeBytes, 0)
                "DESTINATION_UNAVAILABLE" -> TransferFailure.DestinationUnavailable()
                else -> TransferFailure.WriteFailure(event.result.code ?: "Item transfer failed on receiver")
            }
            val failedItem = curItem.copy(phase = ItemPhase.Failed, failure = failure)
            val remaining = state.itemStates.mapValues { (id, itState) ->
                if (id == event.result.itemId) failedItem else if (itState.phase == ItemPhase.Queued) itState.copy(phase = ItemPhase.NotAttempted) else itState
            }
            val nextState = state.copy(
                phase = SessionPhase.Failed,
                itemStates = remaining,
                failure = failure,
                lastActivityMonotonicMs = event.timestampMs
            )
            ReducerResult(
                nextState,
                listOf(
                    ReducerEffect.CloseTransportChannel,
                    ReducerEffect.NotifyTerminalOutcome(SessionPhase.Failed, failure)
                )
            )
        }
    }

    private fun handleBatchEndReceived(state: SessionState, event: ReducerEvent.BatchEndReceived): ReducerResult {
        if (state.role != DeviceRole.Receiver || state.phase != SessionPhase.AwaitingReceipt) {
            return ReducerResult(state, emptyList())
        }
        val batch = state.batch ?: return ReducerResult(state, emptyList())
        val result = BatchResultBody(
            batchId = batch.id,
            status = "completed",
            verifiedCount = state.verifiedCount,
            totalVerifiedBytes = DecimalByteCount.serialize(state.totalVerifiedBytes)
        )
        val (env, nextSeq) = makeEnvelope(state, MessageTypes.BATCH_RESULT, ProtocolCodec.encodeBody(result))
        val nextState = state.copy(
            phase = SessionPhase.Completed,
            nextOutboundSeq = nextSeq,
            lastActivityMonotonicMs = event.timestampMs
        )
        return ReducerResult(
            nextState,
            listOf(
                ReducerEffect.SendControl(env),
                ReducerEffect.NotifyTerminalOutcome(SessionPhase.Completed, null)
            )
        )
    }

    private fun handleBatchResultReceived(state: SessionState, event: ReducerEvent.BatchResultReceived): ReducerResult {
        if (state.role != DeviceRole.Sender || state.phase != SessionPhase.AwaitingReceipt) {
            return ReducerResult(state, emptyList())
        }
        val nextState = state.copy(
            phase = SessionPhase.Completed,
            lastActivityMonotonicMs = event.timestampMs
        )
        return ReducerResult(
            nextState,
            listOf(ReducerEffect.NotifyTerminalOutcome(SessionPhase.Completed, null))
        )
    }

    private fun handleRequestCancel(state: SessionState, event: ReducerEvent.RequestCancel): ReducerResult {
        val failure = TransferFailure.UserCancelled()
        val effects = mutableListOf<ReducerEffect>()

        // Retain verified items! Clean up only unfinished items
        val currentItem = state.currentItemState
        if (currentItem != null && !currentItem.isVerified) {
            if (currentItem.bulkRef != null) {
                effects.add(ReducerEffect.CancelBulkStream(currentItem.bulkRef))
            }
            if (state.role == DeviceRole.Receiver) {
                effects.add(ReducerEffect.DeletePartialDestination(currentItem.item.id))
            }
        }

        val updatedMap = state.itemStates.mapValues { (id, itState) ->
            when {
                itState.isVerified -> itState
                id == currentItem?.item?.id -> itState.copy(phase = ItemPhase.Cancelled)
                itState.phase == ItemPhase.Queued -> itState.copy(phase = ItemPhase.NotAttempted)
                else -> itState.copy(phase = ItemPhase.Cancelled)
            }
        }

        val cancelBody = CancelBody(batchId = state.batch?.id, code = event.code)
        val (env, nextSeq) = makeEnvelope(state, MessageTypes.CANCEL, ProtocolCodec.encodeBody(cancelBody))
        effects.add(ReducerEffect.SendControl(env))
        effects.add(ReducerEffect.CloseTransportChannel)
        effects.add(ReducerEffect.NotifyTerminalOutcome(SessionPhase.Cancelled, failure))

        val nextState = state.copy(
            phase = SessionPhase.Cancelled,
            itemStates = updatedMap,
            failure = failure,
            nextOutboundSeq = nextSeq,
            lastActivityMonotonicMs = event.timestampMs
        )
        return ReducerResult(nextState, effects)
    }

    private fun handleCancelReceived(state: SessionState, event: ReducerEvent.CancelReceived): ReducerResult {
        val failure = TransferFailure.UserCancelled()
        val effects = mutableListOf<ReducerEffect>()

        val currentItem = state.currentItemState
        if (currentItem != null && !currentItem.isVerified) {
            if (currentItem.bulkRef != null) {
                effects.add(ReducerEffect.CancelBulkStream(currentItem.bulkRef))
            }
            if (state.role == DeviceRole.Receiver) {
                effects.add(ReducerEffect.DeletePartialDestination(currentItem.item.id))
            }
        }

        val updatedMap = state.itemStates.mapValues { (id, itState) ->
            when {
                itState.isVerified -> itState
                id == currentItem?.item?.id -> itState.copy(phase = ItemPhase.Cancelled)
                itState.phase == ItemPhase.Queued -> itState.copy(phase = ItemPhase.NotAttempted)
                else -> itState.copy(phase = ItemPhase.Cancelled)
            }
        }

        val cancelAckBody = CancelAckBody(replyTo = event.messageId)
        val (env, nextSeq) = makeEnvelope(state, MessageTypes.CANCEL_ACK, ProtocolCodec.encodeBody(cancelAckBody))
        effects.add(ReducerEffect.SendControl(env))
        effects.add(ReducerEffect.CloseTransportChannel)
        effects.add(ReducerEffect.NotifyTerminalOutcome(SessionPhase.Cancelled, failure))

        val nextState = state.copy(
            phase = SessionPhase.Cancelled,
            itemStates = updatedMap,
            failure = failure,
            nextOutboundSeq = nextSeq,
            lastActivityMonotonicMs = event.timestampMs
        )
        return ReducerResult(nextState, effects)
    }

    private fun handleTransportFailure(state: SessionState, event: ReducerEvent.TransportFailure): ReducerResult {
        val effects = mutableListOf<ReducerEffect>()
        val currentItem = state.currentItemState
        if (currentItem != null && !currentItem.isVerified) {
            if (currentItem.bulkRef != null) {
                effects.add(ReducerEffect.CancelBulkStream(currentItem.bulkRef))
            }
            if (state.role == DeviceRole.Receiver) {
                effects.add(ReducerEffect.DeletePartialDestination(currentItem.item.id))
            }
        }

        val isReceiptLost = state.role == DeviceRole.Sender && state.phase == SessionPhase.AwaitingReceipt
        val terminalPhase = when {
            isReceiptLost -> SessionPhase.DeliveryUnconfirmed
            event.failure.code == "PEER_LOST" || event.failure.code == "TRANSFER_INTERRUPTED" -> SessionPhase.Interrupted
            else -> SessionPhase.Failed
        }
        val effectiveFailure = if (isReceiptLost) TransferFailure.DeliveryUnconfirmed() else event.failure

        val updatedMap = state.itemStates.mapValues { (id, itState) ->
            when {
                itState.isVerified -> itState
                id == currentItem?.item?.id -> itState.copy(phase = if (terminalPhase == SessionPhase.Interrupted) ItemPhase.Interrupted else ItemPhase.Failed)
                itState.phase == ItemPhase.Queued -> itState.copy(phase = ItemPhase.NotAttempted)
                else -> itState.copy(phase = if (terminalPhase == SessionPhase.Interrupted) ItemPhase.Interrupted else ItemPhase.Failed)
            }
        }

        effects.add(ReducerEffect.CloseTransportChannel)
        effects.add(ReducerEffect.NotifyTerminalOutcome(terminalPhase, effectiveFailure))

        val nextState = state.copy(
            phase = terminalPhase,
            itemStates = updatedMap,
            failure = effectiveFailure,
            lastActivityMonotonicMs = event.timestampMs
        )
        return ReducerResult(nextState, effects)
    }

    private fun handleTimeout(state: SessionState, event: ReducerEvent.Timeout): ReducerResult {
        // Receipt ambiguity: if sender is awaiting receipt after all items verified, outcome is DeliveryUnconfirmed
        if (state.role == DeviceRole.Sender && state.phase == SessionPhase.AwaitingReceipt) {
            val failure = TransferFailure.DeliveryUnconfirmed()
            val nextState = state.copy(
                phase = SessionPhase.DeliveryUnconfirmed,
                failure = failure,
                lastActivityMonotonicMs = event.timestampMs
            )
            return ReducerResult(
                nextState,
                listOf(
                    ReducerEffect.CloseTransportChannel,
                    ReducerEffect.NotifyTerminalOutcome(SessionPhase.DeliveryUnconfirmed, failure)
                )
            )
        }

        val failure = TransferFailure.Timeout(phase = event.phase)
        return handleTransportFailure(state, ReducerEvent.TransportFailure(failure, event.timestampMs))
    }

    private fun makeEnvelope(
        state: SessionState,
        type: String,
        body: kotlinx.serialization.json.JsonObject
    ): Pair<Envelope, Long> {
        val seq = state.nextOutboundSeq
        val envelope = Envelope(
            v = ProtocolCodec.PROTOCOL_MAJOR_VERSION,
            type = type,
            sessionId = state.sessionId,
            messageId = MessageId(UUID.randomUUID().toString()),
            seq = seq,
            body = body
        )
        return envelope to (seq + 1)
    }
}
