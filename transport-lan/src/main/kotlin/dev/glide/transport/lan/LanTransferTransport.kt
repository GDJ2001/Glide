package dev.glide.transport.lan

import android.os.Build
import androidx.annotation.RequiresApi
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.SessionId
import dev.glide.core.pairing.LanInvitation
import dev.glide.core.ports.AdvertisingEvent
import dev.glide.core.ports.AuthenticatedChannelContext
import dev.glide.core.ports.AuthenticatedChannelContextProvider
import dev.glide.core.ports.BulkProgress
import dev.glide.core.ports.ConnectionEvent
import dev.glide.core.ports.ControlMessage
import dev.glide.core.ports.DiscoveryEvent
import dev.glide.core.ports.IncomingStream
import dev.glide.core.ports.InputStreamProvider
import dev.glide.core.ports.StreamExpectation
import dev.glide.core.ports.TransferTransport
import dev.glide.core.transport.lan.LanBootstrapCodec
import dev.glide.core.transport.lan.LanBootstrapMessage
import dev.glide.core.transport.lan.LanBootstrapResult
import dev.glide.core.transport.lan.LanRecord
import dev.glide.transport.lan.framing.LanRecordReader
import dev.glide.transport.lan.framing.LanRecordWriter
import dev.glide.transport.lan.framing.LanStreamBridge
import dev.glide.transport.lan.network.LanNetworkScope
import dev.glide.transport.lan.network.LanNetworkState
import dev.glide.transport.lan.pairing.InvitationAuthority
import dev.glide.transport.lan.tls.LanListener
import dev.glide.transport.lan.tls.PinnedTlsConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket

/**
 * Implementation of [TransferTransport] backed by Android local network TLS 1.3 sockets (LAN-012, Section 6, 8, 18).
 *
 * Scoped to transfer session lifecycle. Fully wires pinned TLS connector, server listener,
 * strict bootstrap, serialized record framing, and bounded stream bridging.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class LanTransferTransport(
    val networkScope: LanNetworkScope,
    private val allowLoopbackForTesting: Boolean = false,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    customNsdDiscoveryAdapter: dev.glide.transport.lan.discovery.NsdDiscoveryAdapter? = null
) : TransferTransport, AuthenticatedChannelContextProvider, Closeable {

    val nsdDiscoveryAdapter: dev.glide.transport.lan.discovery.NsdDiscoveryAdapter? =
        customNsdDiscoveryAdapter ?: networkScope.context?.let { ctx ->
            dev.glide.transport.lan.discovery.NsdDiscoveryAdapter(
                clientWrapper = dev.glide.transport.lan.discovery.AndroidNsdClientWrapper(ctx),
                networkScope = networkScope,
                allowLoopbackForTesting = allowLoopbackForTesting,
                coroutineScope = coroutineScope
            )
        }

    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(replay = 1, extraBufferCapacity = 64)
    private val _controlMessages = MutableSharedFlow<ControlMessage>(extraBufferCapacity = 64)
    private val _incomingStreams = MutableSharedFlow<IncomingStream>(extraBufferCapacity = 16)
    private val _bulkProgress = MutableSharedFlow<BulkProgress>(extraBufferCapacity = 64)

    private val connector = PinnedTlsConnector(
        networkScope = networkScope,
        allowLoopbackForTesting = allowLoopbackForTesting
    )

    private var listener: LanListener? = null
    private var authority: InvitationAuthority? = null
    private val streamBridge = LanStreamBridge()

    private val pendingInvitations = ConcurrentHashMap<PeerId, LanInvitation>()
    private val activeConnections = ConcurrentHashMap<PeerId, ActiveConnection>()
    private val markedReadyStreams = Collections.synchronizedSet(mutableSetOf<BulkRef>())

    private var inboundAcceptJob: Job? = null

    private data class ActiveConnection(
        val peerId: PeerId,
        val socket: SSLSocket,
        val writer: LanRecordWriter,
        val readerJob: Job,
        val channelContext: AuthenticatedChannelContext
    )

    fun registerPendingInvitation(peerId: PeerId, invitation: LanInvitation) {
        pendingInvitations[peerId] = invitation
    }

    fun attachServerListener(lanListener: LanListener, invitationAuthority: InvitationAuthority) {
        this.listener = lanListener
        this.authority = invitationAuthority

        inboundAcceptJob?.cancel()
        inboundAcceptJob = coroutineScope.launch {
            for (sslSocket in lanListener.incomingSockets) {
                launch {
                    handleInboundClientSocket(sslSocket, invitationAuthority)
                }
            }
        }
    }

    private suspend fun handleInboundClientSocket(
        socket: SSLSocket,
        invitationAuthority: InvitationAuthority
    ) {
        try {
            val inputStream = socket.inputStream
            val outputStream = socket.outputStream

            // 1. Read first record: must be BOOTSTRAP
            val firstRecord = LanRecordReader.readRecord(inputStream)
            if (firstRecord !is LanRecord.Bootstrap) {
                socket.close()
                return
            }

            val bootstrapMessage = LanBootstrapCodec.decodeBootstrap(firstRecord.jsonBytes)

            // 2. Consume invitation atomically
            val networkGeneration = when (val s = networkScope.networkState.value) {
                is LanNetworkState.Available -> s.generation
                else -> 0L
            }
            val isBusy = activeConnections.isNotEmpty()
            val result = invitationAuthority.consume(
                message = bootstrapMessage,
                currentNetworkGeneration = networkGeneration,
                isBusy = isBusy
            )

            // 3. Send BOOTSTRAP_RESULT
            val writer = LanRecordWriter(outputStream)
            val resultBytes = LanBootstrapCodec.encodeResult(result)
            writer.writeBootstrapResult(resultBytes)

            if (!result.isAccepted) {
                socket.close()
                return
            }

            // 4. Connection established
            val peerId = PeerId("lan-${bootstrapMessage.sessionId}")
            val peerDevice = PeerDevice(
                id = peerId,
                displayName = "LAN Peer"
            )

            val readerJob = coroutineScope.launch {
                readLoop(peerId, socket, inputStream)
            }

            val serverSpki =
                listener?.identity?.spkiSha256
                    ?: throw IOException(
                        "Authenticated LAN listener identity is unavailable."
                    )

            activeConnections[peerId] = ActiveConnection(
                peerId = peerId,
                socket = socket,
                writer = writer,
                readerJob = readerJob,
                channelContext = AuthenticatedChannelContext(
                    sessionId = SessionId(bootstrapMessage.sessionId),
                    channelBindingHex = serverSpki
                )
            )

            _connectionEvents.emit(ConnectionEvent.Connected(peerDevice))
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    override suspend fun startDiscovery(serviceId: String): Flow<DiscoveryEvent> {
        return nsdDiscoveryAdapter?.startDiscovery() ?: emptyFlow()
    }

    override suspend fun stopDiscovery() {
        nsdDiscoveryAdapter?.stopDiscovery()
    }

    override suspend fun startAdvertising(serviceId: String, localDeviceName: String): Flow<AdvertisingEvent> {
        val port = listener?.localPort ?: 0
        return nsdDiscoveryAdapter?.startAdvertising(
            deviceName = localDeviceName,
            localPort = port
        ) ?: emptyFlow()
    }

    override suspend fun stopAdvertising() {
        nsdDiscoveryAdapter?.stopAdvertising()
    }

    override suspend fun initiateConnection(peerId: PeerId, localDeviceName: String) {
        val invitation = pendingInvitations[peerId]
            ?: throw IllegalStateException("No pending invitation registered for peer: ${peerId.value}")

        val endpoint = invitation.endpoints.firstOrNull()
            ?: throw IllegalStateException("Invitation contains no valid endpoint")

        val inetAddress = InetAddress.getByName(endpoint.address)

        // 1. Pinned TLS handshake
        val sslSocket = connector.connect(
            address = inetAddress,
            port = endpoint.port,
            expectedSpkiSha256Hex = invitation.spkiSha256
        )

        val inputStream = sslSocket.inputStream
        val outputStream = sslSocket.outputStream
        val writer = LanRecordWriter(outputStream)

        // 2. Send BOOTSTRAP record
        val senderSessionId = UUID.randomUUID().toString()
        val bootstrap = LanBootstrapMessage(
            v = 1,
            invitationId = invitation.invitationId,
            token = invitation.token,
            invitationHash = invitation.invitationHash,
            sessionId = senderSessionId,
            role = LanBootstrapMessage.ROLE_SENDER
        )
        val bootstrapBytes = LanBootstrapCodec.encodeBootstrap(bootstrap)
        writer.writeBootstrap(bootstrapBytes)

        // 3. Await BOOTSTRAP_RESULT record
        val responseRecord = LanRecordReader.readRecord(inputStream)
        if (responseRecord !is LanRecord.BootstrapResult) {
            sslSocket.close()
            throw IOException("Expected BOOTSTRAP_RESULT record, got: ${responseRecord.kind}")
        }

        val result = LanBootstrapCodec.decodeResult(responseRecord.jsonBytes)
        if (!result.isAccepted) {
            sslSocket.close()
            throw IOException("LAN bootstrap rejected with code: ${result.code}")
        }

        // 4. Connection established
        val peerDevice = PeerDevice(
            id = peerId,
            displayName = invitation.name
        )

        val readerJob = coroutineScope.launch {
            readLoop(peerId, sslSocket, inputStream)
        }

        activeConnections[peerId] = ActiveConnection(
            peerId = peerId,
            socket = sslSocket,
            writer = writer,
            readerJob = readerJob,
            channelContext = AuthenticatedChannelContext(
                sessionId = SessionId(senderSessionId),
                channelBindingHex = invitation.spkiSha256
            )
        )

        _connectionEvents.emit(ConnectionEvent.Connected(peerDevice))
    }

    override fun authenticatedChannelContext(
        peerId: PeerId
    ): AuthenticatedChannelContext? =
        activeConnections[peerId]?.channelContext

    override suspend fun acceptConnection(peerId: PeerId) {
        // Handled automatically upon bootstrap validation
    }

    override suspend fun rejectConnection(peerId: PeerId) {
        _connectionEvents.emit(ConnectionEvent.Rejected(peerId))
        disconnect(peerId)
    }

    override fun observeConnectionEvents(): Flow<ConnectionEvent> = _connectionEvents.asSharedFlow()

    override suspend fun sendControl(peerId: PeerId, payload: ByteArray) {
        val connection = activeConnections[peerId]
            ?: throw IllegalStateException("No active connection for peer: ${peerId.value}")
        connection.writer.writeControl(payload)
    }

    override fun observeControl(): Flow<ControlMessage> = _controlMessages.asSharedFlow()

    override suspend fun sendStream(peerId: PeerId, bulkRef: BulkRef): Flow<BulkProgress> {
        val connection = activeConnections[peerId]
            ?: throw IllegalStateException("No active connection for peer: ${peerId.value}")
        return streamBridge.sendStream(peerId, bulkRef, connection.writer)
    }

    override fun observeIncomingStream(): Flow<IncomingStream> = _incomingStreams.asSharedFlow()

    override fun observeBulkProgress(): Flow<BulkProgress> = _bulkProgress.asSharedFlow()

    override suspend fun prepareStream(streamSource: InputStreamProvider): BulkRef? {
        return streamBridge.prepareStream(streamSource)
    }

    override suspend fun markItemReady(bulkRef: BulkRef) {
        markedReadyStreams.add(bulkRef)
    }

    override suspend fun registerExpectedStream(expectation: StreamExpectation) {
        streamBridge.registerExpectedStream(expectation)
    }

    override suspend fun registerExpectedStream(bulkRef: BulkRef) {
        // Preserved for backwards compatibility
    }

    override suspend fun cancelPayload(bulkRef: BulkRef) {
        streamBridge.cancelPayload(bulkRef)
    }

    override suspend fun disconnect(peerId: PeerId) {
        val connection = activeConnections.remove(peerId)
        if (connection != null) {
            connection.readerJob.cancel()
            try { connection.socket.close() } catch (_: Exception) {}
        }
        _connectionEvents.emit(ConnectionEvent.Disconnected(peerId))
    }

    private suspend fun readLoop(
        peerId: PeerId,
        socket: SSLSocket,
        inputStream: java.io.InputStream
    ) {
        try {
            while (coroutineScope.isActive && !socket.isClosed) {
                val record = LanRecordReader.readRecord(inputStream)
                when (record) {
                    is LanRecord.Control -> {
                        _controlMessages.emit(ControlMessage(peerId = peerId, payload = record.payload))
                    }
                    is LanRecord.Data -> {
                        streamBridge.handleIncomingData(
                            peerId = peerId,
                            record = record,
                            onIncomingStream = { _incomingStreams.tryEmit(it) },
                            onBulkProgress = { _bulkProgress.tryEmit(it) }
                        )
                    }
                    is LanRecord.StreamEnd -> {
                        streamBridge.handleIncomingStreamEnd(
                            peerId = peerId,
                            record = record,
                            onIncomingStream = { _incomingStreams.tryEmit(it) },
                            onBulkProgress = { _bulkProgress.tryEmit(it) }
                        )
                    }
                    else -> {
                        // Unexpected bootstrap record after connected
                        throw IOException("Unexpected record kind during transfer: ${record.kind}")
                    }
                }
            }
        } catch (e: Exception) {
            disconnect(peerId)
        }
    }

    override fun close() {
        inboundAcceptJob?.cancel()
        for ((_, conn) in activeConnections) {
            conn.readerJob.cancel()
            try { conn.socket.close() } catch (_: Exception) {}
        }
        activeConnections.clear()
        pendingInvitations.clear()
        nsdDiscoveryAdapter?.close()
        streamBridge.close()
        networkScope.close()
        coroutineScope.cancel()
    }
}
