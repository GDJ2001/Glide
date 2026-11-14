package dev.glide.transport.lan

import android.os.Build
import androidx.annotation.RequiresApi
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.SessionId
import dev.glide.core.identity.PersistentTrustManager
import dev.glide.core.identity.PeerIdentityVerifier
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    private val _controlMessages = MutableSharedFlow<ControlMessage>(replay = 16, extraBufferCapacity = 64)
    private val _incomingStreams = MutableSharedFlow<IncomingStream>(replay = 16, extraBufferCapacity = 16)
    private val _bulkProgress = MutableSharedFlow<BulkProgress>(extraBufferCapacity = 64)

    private val connector = PinnedTlsConnector(
        networkScope = networkScope,
        allowLoopbackForTesting = allowLoopbackForTesting
    )

    private var listener: LanListener? = null
    private var authority: InvitationAuthority? = null
    @Volatile private var trustedIdentity: PersistentTrustManager? = null
    @Volatile private var localDeviceName: String = "Glide Device"

    fun configureTrustedReceive(manager: PersistentTrustManager?, deviceName: String) {
        trustedIdentity = manager
        localDeviceName = deviceName.trim().ifEmpty { "Glide Device" }
    }
    private val streamBridge = LanStreamBridge()

    private data class PendingInvitation(
        val invitation: LanInvitation,
        val bootstrapRole: String
    )

    data class PendingTrustedPeer(
        val expectedFingerprintHex: String,
        val expectedPublicKeyDerHex: String,
        val displayName: String,
        val endpoints: List<dev.glide.core.pairing.LanEndpoint>,
        val localIdentityPublicKeyDer: ByteArray,
        val expectedPresenceId: String? = null
    )

    private val pendingInvitations = ConcurrentHashMap<PeerId, PendingInvitation>()
    private val pendingTrustedPeers = ConcurrentHashMap<PeerId, PendingTrustedPeer>()
    private val activeConnections = ConcurrentHashMap<PeerId, ActiveConnection>()
    private val inboundSessionReserved = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var inboundAcceptedSocket: SSLSocket? = null
    @Volatile private var advertisedPresenceId: String? = null
    private val markedReadyStreams = Collections.synchronizedSet(mutableSetOf<BulkRef>())

    private var inboundAcceptJob: Job? = null

    private data class ActiveConnection(
        val peerId: PeerId,
        val socket: SSLSocket,
        val writer: LanRecordWriter,
        val readerJob: Job,
        val channelContext: AuthenticatedChannelContext,
        val trustedPeerFingerprintHex: String? = null
    )

    fun registerPendingInvitation(
        peerId: PeerId,
        invitation: LanInvitation,
        bootstrapRole: String = LanBootstrapMessage.ROLE_SENDER
    ) {
        require(
            bootstrapRole == LanBootstrapMessage.ROLE_SENDER ||
                bootstrapRole == LanBootstrapMessage.ROLE_RECEIVER
        ) { "Unsupported LAN bootstrap role: $bootstrapRole" }
        pendingInvitations[peerId] = PendingInvitation(invitation, bootstrapRole)
    }

    fun registerPendingTrustedPeer(
        peerId: PeerId,
        trustedPeer: PendingTrustedPeer
    ) {
        pendingTrustedPeers[peerId] = trustedPeer
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
        var ownsInboundReservation = false
        try {
            val rawInput = java.io.PushbackInputStream(socket.inputStream, 4)
            val outputStream = socket.outputStream

            socket.soTimeout = 10_000
            val preamble = ByteArray(4)
            var readCount = 0
            while (readCount < 4) {
                val r = rawInput.read(preamble, readCount, 4 - readCount)
                if (r < 0) {
                    socket.close()
                    return
                }
                readCount += r
            }

            ownsInboundReservation = inboundSessionReserved.compareAndSet(false, true)
            if (!ownsInboundReservation) {
                if (preamble.contentEquals(dev.glide.core.pairing.Profile2Constants.GLD2_MAGIC_BYTES)) {
                    val open = runCatching {
                        dev.glide.core.pairing.Profile2WireCodec.readMessage(rawInput)
                            as? dev.glide.core.pairing.Profile2Message.SessionOpen
                    }.getOrNull()
                    if (open != null) {
                        dev.glide.core.pairing.Profile2WireCodec.writeMessage(
                            outputStream,
                            dev.glide.core.pairing.Profile2Message.SessionOpenAck(
                                sessionId = open.sessionId,
                                accepted = false,
                                errorCode = dev.glide.core.pairing.Profile2Constants.ERROR_BUSY
                            )
                        )
                    }
                }
                socket.close()
                return
            }

            if (preamble.contentEquals(dev.glide.core.pairing.Profile2Constants.GLD2_MAGIC_BYTES)) {
                handleInboundProfile2(socket, rawInput, outputStream)
                return
            }

            rawInput.unread(preamble)

            // 1. Read first record: must be BOOTSTRAP
            val firstRecord = LanRecordReader.readRecord(rawInput)
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
            val writer = LanRecordWriter(outputStream, coalesceDataWrites = true)
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

            socket.soTimeout = 0
            val readerJob = coroutineScope.launch(start = CoroutineStart.LAZY) {
                readLoop(peerId, socket, rawInput)
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
            inboundAcceptedSocket = socket
            readerJob.start()

            _connectionEvents.emit(ConnectionEvent.Connected(peerDevice))
        } catch (e: Exception) {
            safeLogE("LanTransferTransport", "Inbound LAN connection failed (${e.javaClass.simpleName})")
            try { socket.close() } catch (_: Exception) {}
        } finally {
            if (ownsInboundReservation && inboundAcceptedSocket !== socket) {
                inboundSessionReserved.set(false)
            }
        }
    }

    private suspend fun handleInboundProfile2(
        socket: SSLSocket,
        input: java.io.InputStream,
        output: java.io.OutputStream
    ) {
        val open = dev.glide.core.pairing.Profile2WireCodec.readMessage(input)
            as? dev.glide.core.pairing.Profile2Message.SessionOpen
            ?: run { socket.close(); return }
        val manager = trustedIdentity
        val key = runCatching {
            java.util.Base64.getUrlDecoder().decode(open.initiatorIdentityPublicKey)
        }.getOrNull()
        val fingerprint = key?.let { PeerIdentityVerifier.deriveFingerprint(it) }
        val saved = fingerprint?.let { manager?.trustedPeerByFingerprint(it) }
        val localFingerprint = runCatching { manager?.localFingerprintHex() }.getOrNull()
        val busy = activeConnections.isNotEmpty()
        if (open.expectedPresenceId != null &&
            open.expectedPresenceId != advertisedPresenceId) {
            dev.glide.core.pairing.Profile2WireCodec.writeMessage(
                output,
                dev.glide.core.pairing.Profile2Message.SessionOpenAck(
                    sessionId = open.sessionId,
                    accepted = false,
                    errorCode = dev.glide.core.pairing.Profile2Constants.ERROR_STALE_PRESENCE
                )
            )
            socket.close()
            return
        }
        val authorized = open.purpose == dev.glide.core.pairing.Profile2Constants.PURPOSE_TRUSTED_SEND &&
            open.authMethod == dev.glide.core.pairing.Profile2Constants.AUTH_METHOD_IDENTITY_V1 &&
            manager != null && key != null && saved != null &&
            saved.publicKeyDerHex == key.joinToString("") { "%02x".format(it.toInt() and 0xff) } &&
            (open.expectedPeerIdentityFingerprint == null ||
                open.expectedPeerIdentityFingerprint == localFingerprint)
        if (busy || !authorized) {
            dev.glide.core.pairing.Profile2WireCodec.writeMessage(
                output,
                dev.glide.core.pairing.Profile2Message.SessionOpenAck(
                    sessionId = open.sessionId,
                    accepted = false,
                    errorCode = if (busy) dev.glide.core.pairing.Profile2Constants.ERROR_BUSY
                        else dev.glide.core.pairing.Profile2Constants.ERROR_UNTRUSTED_PEER
                )
            )
            socket.close()
            return
        }

        val localKey = manager.localPublicKeyDer()
        val nonce = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        dev.glide.core.pairing.Profile2WireCodec.writeMessage(
            output,
            dev.glide.core.pairing.Profile2Message.SessionOpenAck(
                sessionId = open.sessionId,
                accepted = true,
                responderNonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
                responderDeviceName = localDeviceName,
                responderPlatform = "android",
                responderIdentityPublicKey = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(localKey)
            )
        )
        val serverSpki = listener?.identity?.spkiSha256
            ?: throw IOException("Authenticated LAN listener identity is unavailable.")
        val peerId = PeerId("lan-${open.sessionId}")
        val peerDevice = PeerDevice(id = peerId, displayName = open.initiatorDeviceName)
        val writer = LanRecordWriter(output, coalesceDataWrites = true)
        socket.soTimeout = 0
        val readerJob = coroutineScope.launch(start = CoroutineStart.LAZY) { readLoop(peerId, socket, input) }
        activeConnections[peerId] = ActiveConnection(
            peerId = peerId,
            socket = socket,
            writer = writer,
            readerJob = readerJob,
            channelContext = AuthenticatedChannelContext(
                sessionId = SessionId(open.sessionId),
                channelBindingHex = serverSpki
            ),
            trustedPeerFingerprintHex = fingerprint
        )
        inboundAcceptedSocket = socket
        readerJob.start()
        _connectionEvents.emit(ConnectionEvent.Connected(peerDevice))
    }

    override suspend fun startDiscovery(serviceId: String): Flow<DiscoveryEvent> {
        return nsdDiscoveryAdapter?.startDiscovery() ?: emptyFlow()
    }

    fun trustedInboundPeerFingerprint(peerId: PeerId): String? =
        activeConnections[peerId]?.trustedPeerFingerprintHex

    val trustedAdvertisingStatus:
        kotlinx.coroutines.flow.StateFlow<dev.glide.transport.lan.discovery.NsdDiscoveryAdapter.RegistrationStatus>?
        get() = nsdDiscoveryAdapter?.registrationStatus

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

    fun advertiseTrustedListener(deviceName: String, presenceId: String) {
        val port = listener?.localPort ?: error("LAN listener is not bound")
        check(port in 1..65535)
        check(trustedIdentity != null) { "Trusted receive handler is unavailable" }
        val adapter = nsdDiscoveryAdapter ?: error("LAN discovery is unavailable")
        advertisedPresenceId = presenceId
        try {
            adapter.startAdvertising(
                deviceName = deviceName,
                localPort = port,
                presenceId = presenceId,
                supportsTrustedReconnect = true
            )
        } catch (error: Throwable) {
            advertisedPresenceId = null
            throw error
        }
    }

    override suspend fun stopAdvertising() {
        nsdDiscoveryAdapter?.stopAdvertising()
        advertisedPresenceId = null
    }

    fun stopTrustedAdvertising() {
        nsdDiscoveryAdapter?.stopAdvertising()
        advertisedPresenceId = null
    }

    override suspend fun initiateConnection(peerId: PeerId, localDeviceName: String) {
        val pendingTrusted = pendingTrustedPeers[peerId]
        if (pendingTrusted != null) {
            initiateTrustedConnection(peerId, pendingTrusted, localDeviceName)
            return
        }

        val pendingInvitation = pendingInvitations[peerId]
            ?: throw IllegalStateException("No pending invitation registered for peer: ${peerId.value}")
        val invitation = pendingInvitation.invitation

        if (invitation.endpoints.isEmpty()) {
            throw IllegalStateException("Invitation contains no valid endpoint")
        }

        val activeSubnets = when (val state = networkScope.networkState.value) {
            is LanNetworkState.Available -> state.subnets
            else -> emptyList()
        }

        val orderedEndpoints = invitation.endpoints.sortedBy { ep ->
            val addr = runCatching { InetAddress.getByName(ep.address) }.getOrNull()
            val isValidOnLink = addr != null &&
                dev.glide.transport.lan.network.LanAddressValidator.validateAddress(
                    address = addr,
                    activeSubnets = activeSubnets,
                    allowLoopbackForTesting = allowLoopbackForTesting
                ) is dev.glide.transport.lan.network.LanAddressValidationResult.Valid
            if (isValidOnLink) 0 else 1
        }

        val allOffLink = invitation.endpoints.all { ep ->
            val addr = runCatching { InetAddress.getByName(ep.address) }.getOrNull()
            val validation = if (addr != null) {
                dev.glide.transport.lan.network.LanAddressValidator.validateAddress(
                    address = addr,
                    activeSubnets = activeSubnets,
                    allowLoopbackForTesting = allowLoopbackForTesting
                )
            } else null
            validation is dev.glide.transport.lan.network.LanAddressValidationResult.Rejected &&
                validation.code == dev.glide.transport.lan.network.LanAddressRejectionCode.OFF_LINK_REJECTED
        }
        if (allOffLink && invitation.endpoints.isNotEmpty()) {
            throw LanConnectionException(LanConnectionFailure.OffLinkDestination())
        }

        var sslSocket: SSLSocket? = null
        var lastError: Throwable? = null

        for (endpoint in orderedEndpoints) {
            try {
                val inetAddress = InetAddress.getByName(endpoint.address)
                sslSocket = connector.connect(
                    address = inetAddress,
                    port = endpoint.port,
                    expectedSpkiSha256Hex = invitation.spkiSha256
                )
                break
            } catch (e: Throwable) {
                lastError = e
            }
        }

        if (sslSocket == null) {
            if (lastError is LanConnectionException) throw lastError
            throw (lastError ?: IOException("Failed to connect to any invitation endpoint"))
        }

        val inputStream = sslSocket.inputStream
        val outputStream = sslSocket.outputStream
        val writer = LanRecordWriter(outputStream, coalesceDataWrites = true)

        // 2. Send BOOTSTRAP record
        val senderSessionId = UUID.randomUUID().toString()
        val bootstrap = LanBootstrapMessage(
            v = 1,
            invitationId = invitation.invitationId,
            token = invitation.token,
            invitationHash = invitation.invitationHash,
            sessionId = senderSessionId,
            role = pendingInvitation.bootstrapRole
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

        sslSocket.soTimeout = 0
        val readerJob = coroutineScope.launch(start = CoroutineStart.LAZY) {
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

        readerJob.start()

        _connectionEvents.emit(ConnectionEvent.Connected(peerDevice))
    }

    private suspend fun initiateTrustedConnection(
        peerId: PeerId,
        trustedPeer: PendingTrustedPeer,
        localDeviceName: String
    ) {
        if (trustedPeer.endpoints.isEmpty()) {
            throw IOException("Couldn’t reach this device — check the local network")
        }

        var sslSocket: SSLSocket? = null
        for (endpoint in trustedPeer.endpoints) {
            try {
                val inetAddress = InetAddress.getByName(endpoint.address)
                val subnets = (networkScope.networkState.value as? LanNetworkState.Available)?.subnets
                    ?: emptyList()
                val addressResult = dev.glide.transport.lan.network.LanAddressValidator.validateAddress(
                    address = inetAddress,
                    activeSubnets = subnets,
                    allowLoopbackForTesting = allowLoopbackForTesting
                )
                if (addressResult !is dev.glide.transport.lan.network.LanAddressValidationResult.Valid) {
                    continue
                }
                sslSocket = connector.connect(
                    address = inetAddress,
                    port = endpoint.port,
                    expectedSpkiSha256Hex = null
                )
                break
            } catch (_: Throwable) {
                // try next endpoint
            }
        }

        if (sslSocket == null) {
            throw IOException("Couldn’t reach this device — check the local network")
        }

        val inputStream = sslSocket.inputStream
        val outputStream = sslSocket.outputStream

        try {
            val serverCert = sslSocket.session.peerCertificates.firstOrNull() as? java.security.cert.X509Certificate
                ?: throw IOException("Identity changed — pair again")
            val serverSpkiHex = dev.glide.transport.lan.tls.PinnedTrustManager.computeSpkiSha256Hex(serverCert)

            outputStream.write(dev.glide.core.pairing.Profile2Constants.GLD2_MAGIC_BYTES)
            outputStream.flush()

            val senderSessionId = UUID.randomUUID().toString().lowercase()
            val nonceBytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
            val open = dev.glide.core.pairing.Profile2Message.SessionOpen(
                sessionId = senderSessionId,
                purpose = dev.glide.core.pairing.Profile2Constants.PURPOSE_TRUSTED_SEND,
                authMethod = dev.glide.core.pairing.Profile2Constants.AUTH_METHOD_IDENTITY_V1,
                initiatorNonce = encoder.encodeToString(nonceBytes),
                initiatorDeviceName = localDeviceName,
                initiatorPlatform = "android",
                initiatorIdentityPublicKey = encoder.encodeToString(trustedPeer.localIdentityPublicKeyDer),
                expectedPresenceId = trustedPeer.expectedPresenceId
            )
            dev.glide.core.pairing.Profile2WireCodec.writeMessage(outputStream, open)

            val ack = dev.glide.core.pairing.Profile2WireCodec.readMessage(inputStream)
                as? dev.glide.core.pairing.Profile2Message.SessionOpenAck
                ?: throw IOException("Identity changed — pair again")

            if (ack.sessionId != senderSessionId) {
                throw IOException("Identity changed — pair again")
            }

            if (!ack.accepted) {
                val mapped = when (ack.errorCode) {
                    dev.glide.core.pairing.Profile2Constants.ERROR_BUSY -> "This device is busy — try again"
                    dev.glide.core.pairing.Profile2Constants.ERROR_EXPIRED -> "Code expired — get a new code"
                    else -> "Identity changed — pair again"
                }
                throw IOException(mapped)
            }

            val responderSpki = java.util.Base64.getUrlDecoder().decode(ack.responderIdentityPublicKey ?: "")
            val responderFp = dev.glide.core.identity.PeerIdentityVerifier.deriveFingerprint(responderSpki)
            val responderPubHex = responderSpki.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (responderFp != trustedPeer.expectedFingerprintHex ||
                responderPubHex != trustedPeer.expectedPublicKeyDerHex
            ) {
                throw IOException("Identity changed — pair again")
            }

            val writer = LanRecordWriter(outputStream, coalesceDataWrites = true)
            val peerDevice = PeerDevice(
                id = peerId,
                displayName = ack.responderDeviceName ?: trustedPeer.displayName
            )

            sslSocket.soTimeout = 0
            val readerJob = coroutineScope.launch(start = CoroutineStart.LAZY) {
                readLoop(peerId, sslSocket, inputStream)
            }

            activeConnections[peerId] = ActiveConnection(
                peerId = peerId,
                socket = sslSocket,
                writer = writer,
                readerJob = readerJob,
                channelContext = AuthenticatedChannelContext(
                    sessionId = SessionId(senderSessionId),
                    channelBindingHex = serverSpkiHex
                )
            )

            readerJob.start()

            _connectionEvents.emit(ConnectionEvent.Connected(peerDevice))
        } catch (e: Throwable) {
            try { sslSocket.close() } catch (_: Exception) {}
            throw e
        }
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
            if (inboundAcceptedSocket === connection.socket) {
                inboundAcceptedSocket = null
                inboundSessionReserved.set(false)
            }
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
            // A completed or cancelled session deliberately closes its socket during
            // cleanup. Do not turn that expected lifecycle event into a scary error
            // record that can be mistaken for an encrypted-channel failure.
            val expectedClose = socket.isClosed || e is CancellationException
            if (!expectedClose) {
                safeLogE("LanTransferTransport", "LAN read loop terminated (${e.javaClass.simpleName})")
            }
            disconnect(peerId)
        }
    }

    private fun safeLogE(tag: String, msg: String, tr: Throwable? = null) {
        try {
            android.util.Log.e(tag, msg, tr)
        } catch (_: Throwable) {
        }
    }

    override fun close() {
        advertisedPresenceId = null
        inboundAcceptJob?.cancel()
        for ((_, conn) in activeConnections) {
            conn.readerJob.cancel()
            try { conn.socket.close() } catch (_: Exception) {}
        }
        activeConnections.clear()
        inboundAcceptedSocket = null
        inboundSessionReserved.set(false)
        pendingInvitations.clear()
        nsdDiscoveryAdapter?.close()
        streamBridge.close()
        networkScope.close()
        coroutineScope.cancel()
    }
}
