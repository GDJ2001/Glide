package dev.glide.transport.nearby

import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.ports.BulkProgress
import dev.glide.core.ports.ControlMessage
import dev.glide.core.ports.IncomingStream
import dev.glide.core.ports.InputStreamProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.TimeoutException

/**
 * Bridges Google Play Services Nearby Connections payloads to Glide domain contracts.
 * Enforces:
 * - Strict 16 KiB bounds on control messages (BYTES).
 * - Opaque BulkRef to decimal signed payload ID mapping on STREAM payloads.
 * - READY handshake enforcement before stream payload transmission.
 * - Bounded pending unmapped stream holding (max 2 entries) with 30s deadline.
 * - Single-stream maximum constraint across sender and receiver.
 * - Callback order normalization and exact-length streaming.
 * - Complete cancellation and radio resource release.
 */
class NearbyPayloadBridge(
    private val clientWrapper: NearbyClientWrapper,
    private val isPeerConnected: (PeerId) -> Boolean = { true },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    companion object {
        const val MAX_CONTROL_BYTES: Int = ProtocolLimits.MAX_CONTROL_PAYLOAD_BYTES
        const val MAX_PENDING_STREAMS: Int = 2
        const val PENDING_STREAM_TIMEOUT_MS: Long = 30_000L
        const val READY_HANDSHAKE_TIMEOUT_MS: Long = 30_000L
    }

    private val lock = Any()

    private val _controlMessages = MutableSharedFlow<ControlMessage>(extraBufferCapacity = 64)
    val controlMessages: Flow<ControlMessage> = _controlMessages.asSharedFlow()

    private val _incomingStreams = MutableSharedFlow<IncomingStream>(extraBufferCapacity = 16)
    val incomingStreams: Flow<IncomingStream> = _incomingStreams.asSharedFlow()

    private val _bulkProgress = MutableSharedFlow<BulkProgress>(extraBufferCapacity = 64)
    val bulkProgress: Flow<BulkProgress> = _bulkProgress.asSharedFlow()

    // Internal state tracking
    private var activeSendStream: ActiveSendStream? = null
    private var activeReceiveStream: ActiveReceiveStream? = null

    private val pendingStreams = mutableMapOf<BulkRef, PendingReceiveStream>()
    private val expectedStreams = mutableMapOf<BulkRef, ExpectedStreamEntry>()
    private val preparedSendStreams = mutableMapOf<BulkRef, PreparedSendStream>()
    private val readyHandshakes = mutableMapOf<BulkRef, CompletableDeferred<Unit>>()
    private val processedPayloadIds: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf<Long>())

    // Progress listeners for active send streams
    private val sendProgressListeners = mutableMapOf<Long, (BulkProgress) -> Unit>()

    val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            handlePayloadReceived(endpointId, payload)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            handlePayloadTransferUpdate(endpointId, update)
        }
    }

    /**
     * Sends a framed control message to a connected peer.
     */
    suspend fun sendControl(peerId: PeerId, payload: ByteArray) {
        if (!isPeerConnected(peerId)) {
            throw IllegalStateException("Cannot send control message: peer $peerId is not connected")
        }
        if (payload.isEmpty()) {
            throw IllegalArgumentException("Control payload must not be empty")
        }
        if (payload.size > MAX_CONTROL_BYTES) {
            throw IllegalArgumentException("Control payload size ${payload.size} exceeds maximum $MAX_CONTROL_BYTES bytes")
        }

        val nearbyPayload = Payload.fromBytes(payload)
        clientWrapper.sendPayload(peerId.value, nearbyPayload)
    }

    /**
     * Observes incoming control messages (BYTES) from connected peers.
     */
    fun observeControl(): Flow<ControlMessage> = controlMessages

    /**
     * Prepares a stream payload ahead of ITEM_START announcement.
     * Returns an opaque [BulkRef] representing the decimal signed payload ID string.
     * Enforces one stream maximum.
     */
    fun prepareStream(streamSource: InputStreamProvider): BulkRef {
        synchronized(lock) {
            if (activeSendStream != null || preparedSendStreams.isNotEmpty()) {
                throw IllegalStateException("Concurrent streams not permitted; one stream maximum")
            }

            val stream = streamSource.openStream()
            val nearbyPayload = Payload.fromStream(stream)
            val bulkRef = BulkRef(nearbyPayload.id.toString())

            preparedSendStreams[bulkRef] = PreparedSendStream(
                payload = nearbyPayload,
                stream = stream
            )
            readyHandshakes[bulkRef] = CompletableDeferred()
            return bulkRef
        }
    }

    /**
     * Signals that the receiver has sent ITEM_READY for the specified [bulkRef].
     */
    fun markItemReady(bulkRef: BulkRef) {
        synchronized(lock) {
            readyHandshakes[bulkRef]?.complete(Unit)
        }
    }

    /**
     * Registers metadata from ITEM_START for an expected incoming stream.
     * If the payload arrived early (payload-before-metadata), it is immediately promoted and emitted.
     */
    fun registerExpectedStream(bulkRef: BulkRef) {
        var streamToEmit: IncomingStream? = null

        synchronized(lock) {
            val pending = pendingStreams.remove(bulkRef)
            if (pending != null) {
                pending.timeoutJob.cancel()
                if (activeReceiveStream != null) {
                    try { pending.stream.close() } catch (_: Exception) {}
                    scope.launch { clientWrapper.cancelPayload(pending.payloadId) }
                    return
                }
                activeReceiveStream = ActiveReceiveStream(
                    peerId = pending.peerId,
                    bulkRef = bulkRef,
                    payloadId = pending.payloadId,
                    stream = pending.stream
                )
                streamToEmit = IncomingStream(
                    peerId = pending.peerId,
                    bulkRef = bulkRef,
                    stream = pending.stream
                )
            } else {
                val timeoutJob = scope.launch {
                    delay(PENDING_STREAM_TIMEOUT_MS)
                    synchronized(lock) {
                        expectedStreams.remove(bulkRef)
                    }
                }
                expectedStreams[bulkRef] = ExpectedStreamEntry(timeoutJob)
            }
        }

        streamToEmit?.let { incoming ->
            scope.launch {
                _incomingStreams.emit(incoming)
            }
        }
    }

    /**
     * Sends a bulk stream payload to the remote peer.
     * Enforces connection verification, READY handshake, one stream maximum, and cancellation safety.
     */
    suspend fun sendStream(
        peerId: PeerId,
        bulkRef: BulkRef,
        streamSource: InputStreamProvider
    ): Flow<BulkProgress> = callbackFlow {
        if (!isPeerConnected(peerId)) {
            throw IllegalStateException("Cannot send stream: peer $peerId is not connected")
        }

        val (nearbyPayload, stream) = synchronized(lock) {
            if (activeSendStream != null) {
                throw IllegalStateException("Concurrent streams not permitted; one stream maximum")
            }

            val prepared = preparedSendStreams.remove(bulkRef)
            if (prepared != null) {
                prepared.payload to prepared.stream
            } else {
                val s = streamSource.openStream()
                val p = Payload.fromStream(s)
                p to s
            }
        }

        val payloadId = nearbyPayload.id
        val sendBulkRef = BulkRef(payloadId.toString())

        val readyDeferred = synchronized(lock) {
            readyHandshakes[bulkRef] ?: readyHandshakes[sendBulkRef] ?: CompletableDeferred<Unit>().also {
                readyHandshakes[sendBulkRef] = it
            }
        }

        // Enforce READY handshake before sending payload
        try {
            withTimeout(READY_HANDSHAKE_TIMEOUT_MS) {
                readyDeferred.await()
            }
        } catch (e: Exception) {
            try { stream.close() } catch (_: Exception) {}
            synchronized(lock) {
                readyHandshakes.remove(bulkRef)
                readyHandshakes.remove(sendBulkRef)
            }
            throw TimeoutException("ITEM_READY handshake timed out after ${READY_HANDSHAKE_TIMEOUT_MS}ms for $bulkRef")
        }

        synchronized(lock) {
            activeSendStream = ActiveSendStream(peerId, sendBulkRef, payloadId, stream)
            sendProgressListeners[payloadId] = { progress ->
                trySend(progress)
                if (progress.isComplete) {
                    close()
                }
            }
        }

        try {
            clientWrapper.sendPayload(peerId.value, nearbyPayload)
        } catch (e: Exception) {
            synchronized(lock) {
                sendProgressListeners.remove(payloadId)
                if (activeSendStream?.payloadId == payloadId) {
                    activeSendStream = null
                }
            }
            try { stream.close() } catch (_: Exception) {}
            throw e
        }

        awaitClose {
            synchronized(lock) {
                sendProgressListeners.remove(payloadId)
                readyHandshakes.remove(bulkRef)
                readyHandshakes.remove(sendBulkRef)
                if (activeSendStream?.payloadId == payloadId) {
                    activeSendStream = null
                    scope.launch {
                        try {
                            clientWrapper.cancelPayload(payloadId)
                        } catch (_: Exception) {}
                    }
                }
            }
            try { stream.close() } catch (_: Exception) {}
        }
    }

    /**
     * Observes incoming verified and mapped streams.
     */
    fun observeIncomingStream(): Flow<IncomingStream> = incomingStreams

    /**
     * Observes bulk progress events for all active streams.
     */
    fun observeBulkProgress(): Flow<BulkProgress> = bulkProgress

    /**
     * Cancels an active or pending payload by BulkRef.
     */
    suspend fun cancelPayload(bulkRef: BulkRef) {
        val payloadIdsToCancel = mutableListOf<Long>()
        val streamsToClose = mutableListOf<InputStream>()

        synchronized(lock) {
            preparedSendStreams.remove(bulkRef)?.let { prep ->
                payloadIdsToCancel.add(prep.payload.id)
                streamsToClose.add(prep.stream)
            }
            if (activeSendStream?.bulkRef == bulkRef) {
                activeSendStream?.let { active ->
                    payloadIdsToCancel.add(active.payloadId)
                    streamsToClose.add(active.stream)
                }
                activeSendStream = null
            }
            pendingStreams.remove(bulkRef)?.let { pending ->
                pending.timeoutJob.cancel()
                payloadIdsToCancel.add(pending.payloadId)
                streamsToClose.add(pending.stream)
            }
            if (activeReceiveStream?.bulkRef == bulkRef) {
                activeReceiveStream?.let { active ->
                    payloadIdsToCancel.add(active.payloadId)
                    streamsToClose.add(active.stream)
                }
                activeReceiveStream = null
            }
            expectedStreams.remove(bulkRef)?.timeoutJob?.cancel()
            readyHandshakes.remove(bulkRef)?.cancel()
        }

        streamsToClose.forEach { stream ->
            try { stream.close() } catch (_: Exception) {}
        }
        payloadIdsToCancel.forEach { id ->
            try { clientWrapper.cancelPayload(id) } catch (_: Exception) {}
        }
    }

    /**
     * Cleans up all streams and pending state when a peer disconnects.
     */
    fun onDisconnected(peerId: PeerId) {
        val streamsToClose = mutableListOf<InputStream>()
        val payloadsToCancel = mutableListOf<Long>()

        synchronized(lock) {
            if (activeSendStream?.peerId == peerId) {
                activeSendStream?.let {
                    streamsToClose.add(it.stream)
                    payloadsToCancel.add(it.payloadId)
                }
                activeSendStream = null
            }
            if (activeReceiveStream?.peerId == peerId) {
                activeReceiveStream?.let {
                    streamsToClose.add(it.stream)
                    payloadsToCancel.add(it.payloadId)
                }
                activeReceiveStream = null
            }

            val pendingToRemove = pendingStreams.filterValues { it.peerId == peerId }
            pendingToRemove.forEach { (ref, pending) ->
                pending.timeoutJob.cancel()
                streamsToClose.add(pending.stream)
                payloadsToCancel.add(pending.payloadId)
                pendingStreams.remove(ref)
            }

            preparedSendStreams.values.forEach { prep ->
                streamsToClose.add(prep.stream)
                payloadsToCancel.add(prep.payload.id)
            }
            preparedSendStreams.clear()

            expectedStreams.values.forEach { it.timeoutJob.cancel() }
            expectedStreams.clear()
            readyHandshakes.values.forEach { it.cancel() }
            readyHandshakes.clear()
            sendProgressListeners.clear()
        }

        streamsToClose.forEach { stream ->
            try { stream.close() } catch (_: Exception) {}
        }
        if (payloadsToCancel.isNotEmpty()) {
            scope.launch {
                payloadsToCancel.forEach { id ->
                    try { clientWrapper.cancelPayload(id) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun handlePayloadReceived(endpointId: String, payload: Payload) {
        val peerId = PeerId(endpointId)
        if (!isPeerConnected(peerId)) {
            // Threat model T01/T02: strictly drop unauthenticated payloads
            try { payload.close() } catch (_: Exception) {}
            return
        }

        // Deduplication check
        if (!processedPayloadIds.add(payload.id)) {
            try { payload.close() } catch (_: Exception) {}
            return
        }

        when (payload.type) {
            Payload.Type.BYTES -> {
                val bytes = payload.asBytes() ?: return
                if (bytes.isEmpty() || bytes.size > MAX_CONTROL_BYTES) {
                    return
                }
                scope.launch {
                    _controlMessages.emit(ControlMessage(peerId, bytes))
                }
            }
            Payload.Type.STREAM -> {
                val rawStream = payload.asStream()?.asInputStream()
                if (rawStream == null) {
                    return
                }
                val stream = SafeManagedInputStream(rawStream) {
                    synchronized(lock) {
                        if (activeReceiveStream?.payloadId == payload.id) {
                            activeReceiveStream = null
                        }
                    }
                }

                val bulkRef = BulkRef(payload.id.toString())
                var streamToEmit: IncomingStream? = null

                synchronized(lock) {
                    // Check one stream maximum constraint
                    if (activeReceiveStream != null) {
                        try { stream.close() } catch (_: Exception) {}
                        scope.launch { clientWrapper.cancelPayload(payload.id) }
                        return
                    }

                    val expected = expectedStreams.remove(bulkRef)
                    if (expected != null) {
                        expected.timeoutJob.cancel()
                        activeReceiveStream = ActiveReceiveStream(peerId, bulkRef, payload.id, stream)
                        streamToEmit = IncomingStream(peerId, bulkRef, stream)
                    } else {
                        // Payload-before-metadata: hold in bounded pending map
                        if (pendingStreams.size >= MAX_PENDING_STREAMS) {
                            try { stream.close() } catch (_: Exception) {}
                            scope.launch { clientWrapper.cancelPayload(payload.id) }
                            return
                        }

                        val timeoutJob = scope.launch {
                            delay(PENDING_STREAM_TIMEOUT_MS)
                            synchronized(lock) {
                                pendingStreams.remove(bulkRef)?.let { pending ->
                                    try { pending.stream.close() } catch (_: Exception) {}
                                    scope.launch { clientWrapper.cancelPayload(pending.payloadId) }
                                }
                            }
                        }
                        pendingStreams[bulkRef] = PendingReceiveStream(
                            peerId = peerId,
                            bulkRef = bulkRef,
                            payloadId = payload.id,
                            stream = stream,
                            timeoutJob = timeoutJob
                        )
                    }
                }

                streamToEmit?.let { incoming ->
                    scope.launch {
                        _incomingStreams.emit(incoming)
                    }
                }
            }
            else -> {
                try { payload.close() } catch (_: Exception) {}
            }
        }
    }

    private fun handlePayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
        val peerId = PeerId(endpointId)
        if (!isPeerConnected(peerId)) {
            return
        }

        val payloadId = update.payloadId
        val bulkRef = BulkRef(payloadId.toString())
        val isComplete = update.status == PayloadTransferUpdate.Status.SUCCESS
        val isCancelled = update.status == PayloadTransferUpdate.Status.CANCELED
        val isFailed = update.status == PayloadTransferUpdate.Status.FAILURE

        val progress = BulkProgress(
            bulkRef = bulkRef,
            bytesTransferred = update.bytesTransferred,
            isComplete = isComplete
        )

        // Sender update
        val senderListener = synchronized(lock) {
            sendProgressListeners[payloadId]
        }
        senderListener?.invoke(progress)

        // Broadcast to general progress observer
        scope.launch {
            _bulkProgress.emit(progress)
        }

        if (isComplete || isCancelled || isFailed) {
            synchronized(lock) {
                if (activeSendStream?.payloadId == payloadId) {
                    activeSendStream = null
                }
                if (activeReceiveStream?.payloadId == payloadId) {
                    activeReceiveStream = null
                }
                sendProgressListeners.remove(payloadId)
            }
        }
    }

    // Helper data structures
    private data class ActiveSendStream(
        val peerId: PeerId,
        val bulkRef: BulkRef,
        val payloadId: Long,
        val stream: InputStream
    )

    private data class ActiveReceiveStream(
        val peerId: PeerId,
        val bulkRef: BulkRef,
        val payloadId: Long,
        val stream: InputStream
    )

    private data class PendingReceiveStream(
        val peerId: PeerId,
        val bulkRef: BulkRef,
        val payloadId: Long,
        val stream: InputStream,
        val timeoutJob: Job
    )

    private data class ExpectedStreamEntry(
        val timeoutJob: Job
    )

    private data class PreparedSendStream(
        val payload: Payload,
        val stream: InputStream
    )

    /**
     * Managed wrapper around input streams ensuring descriptor closure on close or EOF.
     */
    private class SafeManagedInputStream(
        delegate: InputStream,
        private val onClose: () -> Unit
    ) : FilterInputStream(delegate) {
        private var isClosed = false

        override fun close() {
            if (!isClosed) {
                isClosed = true
                try {
                    super.close()
                } finally {
                    onClose()
                }
            }
        }
    }
}
