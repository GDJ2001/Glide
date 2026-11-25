package dev.glide.app.lan

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.glide.app.config.LanFeatureConfig
import dev.glide.app.permission.PermissionPolicy
import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.TrustedPeerRecord
import dev.glide.core.identity.PersistentTrustManager
import dev.glide.core.pairing.LanEndpoint
import dev.glide.core.pairing.LanInvitation
import dev.glide.core.ports.ConnectionEvent
import dev.glide.transport.lan.LanTransferTransport
import dev.glide.transport.lan.network.LanNetworkScope
import dev.glide.transport.lan.network.LanNetworkState
import dev.glide.transport.lan.pairing.InvitationAuthority
import dev.glide.transport.lan.tls.LanLeaseIdentity
import dev.glide.transport.lan.tls.LanListener
import java.io.Closeable
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface LanReceiverRuntimeState {
    data object Idle : LanReceiverRuntimeState
    data object Starting : LanReceiverRuntimeState
    data object Available : LanReceiverRuntimeState

    data class Ready(
        val invitation: LanInvitation,
        val endpointsSummary: String
    ) : LanReceiverRuntimeState

    data class Connected(
        val peer: PeerDevice
    ) : LanReceiverRuntimeState

    data class Error(
        val message: String,
        val canRetry: Boolean = true
    ) : LanReceiverRuntimeState
}

/**
 * Service-scoped production owner for Android LAN QR pairing.
 *
 * Socket/key/invitation creation deliberately lives outside Compose/ViewModels:
 * - a live [LanNetworkScope] must be available before minting;
 * - the SPKI pin comes from a fresh Android Keystore lease identity;
 * - the QR endpoint comes from the bound [LanListener] port + active network addresses;
 * - the exact [InvitationAuthority] used to mint the QR is attached to the receiver transport,
 *   so BOOTSTRAP consumes that invitation atomically.
 */
@Singleton
class ProductionLanController private constructor(
    private val networkScopeFactory: () -> LanNetworkScope,
    private val identityFactory: () -> LanLeaseIdentity,
    private val permissionChecker: (String) -> Boolean,
    private val apiLevelProvider: () -> Int,
    private val transportFactory: (LanNetworkScope, CoroutineScope) -> LanTransferTransport,
    private val allowLoopbackEndpointsForTesting: Boolean,
    private val listenerFactory: (
        LanLeaseIdentity,
        LanNetworkScope,
        CoroutineScope
    ) -> LanListener
) {
    @Volatile private var trustedReceiveManager: PersistentTrustManager? = null

    fun configureTrustedReceive(manager: PersistentTrustManager?) {
        trustedReceiveManager = manager
    }

    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(
        networkScopeFactory = { LanNetworkScope(context.applicationContext) },
        identityFactory = { LanLeaseIdentity.create(preferAndroidKeyStore = true) },
        permissionChecker = { permission ->
            ContextCompat.checkSelfPermission(
                context.applicationContext,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        },
        apiLevelProvider = { Build.VERSION.SDK_INT },
        transportFactory = { networkScope, scope ->
            LanTransferTransport(
                networkScope = networkScope,
                coroutineScope = scope
            )
        },
        allowLoopbackEndpointsForTesting = false,
        listenerFactory = { identity, networkScope, scope ->
            LanListener(
                identity = identity,
                networkScope = networkScope,
                port = 0,
                coroutineScope = scope
            )
        }
    )

    private data class ReceiverResources(
        val networkScope: LanNetworkScope,
        val identity: LanLeaseIdentity,
        val listener: LanListener,
        val authority: InvitationAuthority,
        val transport: LanTransferTransport,
        val listenerScope: CoroutineScope,
        val transportScope: CoroutineScope,
        val connectionJob: Job,
        val networkJob: Job
    )

    private data class SenderResources(
        val networkScope: LanNetworkScope,
        val transport: LanTransferTransport,
        val transportScope: CoroutineScope,
        val connectionJob: Job
    )

    private val lock = Any()
    private var receiverResources: ReceiverResources? = null
    private var senderResources: SenderResources? = null
    private var setupJob: Job? = null
    private var lifecycleGeneration: Long = 0

    private val _receiverState =
        MutableStateFlow<LanReceiverRuntimeState>(LanReceiverRuntimeState.Idle)
    val receiverState: StateFlow<LanReceiverRuntimeState> = _receiverState.asStateFlow()

    @SuppressLint("NewApi")
    fun startReceiver(
        ownerScope: CoroutineScope,
        deviceName: String,
        onConnected: (LanTransferTransport, PeerDevice) -> Unit
    ): Boolean {
        val readinessError = readinessError()
        if (readinessError != null) {
            _receiverState.value = LanReceiverRuntimeState.Error(readinessError)
            return false
        }

        val existing = synchronized(lock) { receiverResources }
        if (existing != null && !existing.identity.isExpiringSoon()) {
            existing.transport.configureTrustedReceive(trustedReceiveManager, deviceName)
            if (trustedReceiveManager != null) {
                runCatching {
                    existing.transport.advertiseTrustedListener(
                        deviceName.trim().ifEmpty { "Glide Device" },
                        java.util.UUID.randomUUID().toString().replace("-", "")
                    )
                }.onFailure {
                    _receiverState.value = LanReceiverRuntimeState.Error("Local discovery is unavailable.")
                    return false
                }
            }
            val available = existing.networkScope.networkState.value as? LanNetworkState.Available
            val port = runCatching { existing.listener.localPort }.getOrDefault(-1)
            if (available != null && port > 0) {
                val endpoints = endpointsFor(addresses = available.localAddresses, port = port)
                if (endpoints.isNotEmpty()) {
                    val refreshedInvitation = existing.authority.mintInvitation(
                        endpoints = endpoints,
                        spkiSha256 = existing.identity.spkiSha256,
                        deviceName = deviceName.trim().ifEmpty { "Glide Device" },
                        platform = "android",
                        networkGeneration = available.generation
                    )
                    _receiverState.value = LanReceiverRuntimeState.Ready(
                        invitation = refreshedInvitation,
                        endpointsSummary = endpoints.joinToString(", ") { "${it.address}:${it.port}" }
                    )
                    return true
                }
            }
        }

        stopAll()
        val generation = synchronized(lock) { ++lifecycleGeneration }
        _receiverState.value = LanReceiverRuntimeState.Starting

        setupJob = ownerScope.launch {
            var networkScope: LanNetworkScope? = null
            var identity: LanLeaseIdentity? = null
            var listener: LanListener? = null
            var transport: LanTransferTransport? = null
            var listenerScope: CoroutineScope? = null
            var transportScope: CoroutineScope? = null

            try {
                networkScope = networkScopeFactory()
                val available = awaitAvailableNetwork(networkScope)
                    ?: throw IllegalStateException(
                        "No eligible local Wi-Fi/Ethernet network is available."
                    )

                val activeIdentity = identityFactory()
                identity = activeIdentity
                val activeListenerScope = childScope(ownerScope)
                listenerScope = activeListenerScope
                val activeTransportScope = childScope(ownerScope)
                transportScope = activeTransportScope

                val activeListener = listenerFactory(
                    activeIdentity,
                    networkScope,
                    activeListenerScope
                )
                listener = activeListener
                val activeTransport = transportFactory(
                    networkScope,
                    activeTransportScope
                )
                transport = activeTransport
                activeTransport.configureTrustedReceive(trustedReceiveManager, deviceName)

                val authority = InvitationAuthority()
                activeTransport.attachServerListener(activeListener, authority)

                val endpoints = endpointsFor(
                    addresses = available.localAddresses,
                    port = activeListener.localPort
                )
                if (endpoints.isEmpty()) {
                    throw IllegalStateException(
                        "The active local network has no usable on-link IP address."
                    )
                }

                val invitation = authority.mintInvitation(
                    endpoints = endpoints,
                    spkiSha256 = activeIdentity.spkiSha256,
                    deviceName = deviceName.trim().ifEmpty { "Glide Device" },
                    platform = "android",
                    networkGeneration = available.generation
                )

                if (trustedReceiveManager != null) {
                    activeTransport.advertiseTrustedListener(
                        deviceName.trim().ifEmpty { "Glide Device" },
                        java.util.UUID.randomUUID().toString().replace("-", "")
                    )
                    val registered = withTimeoutOrNull(NETWORK_READY_TIMEOUT_MS) {
                        activeTransport.trustedAdvertisingStatus
                            ?.filter { status ->
                                status == dev.glide.transport.lan.discovery.NsdDiscoveryAdapter.RegistrationStatus.Ready ||
                                    status == dev.glide.transport.lan.discovery.NsdDiscoveryAdapter.RegistrationStatus.Failed
                            }
                            ?.first()
                    }
                    check(registered == dev.glide.transport.lan.discovery.NsdDiscoveryAdapter.RegistrationStatus.Ready) {
                        "Local discovery could not advertise the bound receiver."
                    }
                }

                lateinit var connectionJob: Job
                connectionJob = activeTransportScope.launch {
                    activeTransport.observeConnectionEvents().collect { event ->
                        if (!isCurrentGeneration(generation)) return@collect
                        when (event) {
                            is ConnectionEvent.Connected -> {
                                authority.revokeActiveInvitation()
                                _receiverState.value =
                                    LanReceiverRuntimeState.Connected(event.peer)
                                onConnected(activeTransport, event.peer)
                            }
                            is ConnectionEvent.Failed -> {
                                _receiverState.value =
                                    LanReceiverRuntimeState.Error(event.reason)
                            }
                            is ConnectionEvent.Rejected -> {
                                _receiverState.value =
                                    LanReceiverRuntimeState.Error(
                                        "LAN pairing was rejected."
                                    )
                            }
                            is ConnectionEvent.Disconnected -> {
                                // The listener remains bound for the next authenticated offer.
                                if (_receiverState.value is LanReceiverRuntimeState.Connected) {
                                    _receiverState.value = LanReceiverRuntimeState.Available
                                }
                            }
                            is ConnectionEvent.Initiated -> Unit
                        }
                    }
                }

                val networkJob = activeListenerScope.launch {
                    networkScope.networkState.collect { state ->
                        if (!isCurrentGeneration(generation)) return@collect
                        when (state) {
                            is LanNetworkState.Available -> {
                                if (state.generation != available.generation ||
                                    state.localAddresses != available.localAddresses) {
                                    ownerScope.launch {
                                        stopAll()
                                        startReceiver(ownerScope, deviceName, onConnected)
                                    }
                                }
                            }
                            else -> {
                                activeTransport.stopTrustedAdvertising()
                                _receiverState.value = LanReceiverRuntimeState.Error(
                                    "Local network unavailable. Reconnect Wi-Fi and reopen Glide."
                                )
                            }
                        }
                    }
                }

                activeListenerScope.launch {
                    activeTransport.trustedAdvertisingStatus?.collect { status ->
                        if (status == dev.glide.transport.lan.discovery.NsdDiscoveryAdapter.RegistrationStatus.Failed &&
                            isCurrentGeneration(generation)) {
                            _receiverState.value = LanReceiverRuntimeState.Error(
                                "Local discovery failed. Check Wi-Fi and try again."
                            )
                        }
                    }
                }

                // Rotate ephemeral LanLeaseIdentity before its 2-minute certificate validity window expires
                val rotationMarginMs = (activeIdentity.ttlMillis / 4).coerceAtMost(LEASE_RENEWAL_MARGIN_MS)
                val rotationDelayMs = (activeIdentity.ttlMillis - rotationMarginMs).coerceAtLeast(1_000L)
                activeListenerScope.launch {
                    delay(rotationDelayMs)
                    while (isCurrentGeneration(generation) &&
                        _receiverState.value is LanReceiverRuntimeState.Connected
                    ) {
                        delay(2_000L)
                    }
                    if (isCurrentGeneration(generation)) {
                        ownerScope.launch {
                            stopAll()
                            startReceiver(ownerScope, deviceName, onConnected)
                        }
                    }
                }

                synchronized(lock) {
                    receiverResources = ReceiverResources(
                        networkScope = networkScope,
                        identity = activeIdentity,
                        listener = activeListener,
                        authority = authority,
                        transport = activeTransport,
                        listenerScope = activeListenerScope,
                        transportScope = activeTransportScope,
                        connectionJob = connectionJob,
                        networkJob = networkJob
                    )
                }

                _receiverState.value = LanReceiverRuntimeState.Ready(
                    invitation = invitation,
                    endpointsSummary = endpoints.joinToString(", ") {
                        "${it.address}:${it.port}"
                    }
                )
            } catch (cancelled: CancellationException) {
                runCatching { transport?.close() }
                runCatching { listener?.close() }
                runCatching { identity?.close() }
                runCatching { networkScope?.close() }
                listenerScope?.cancel()
                transportScope?.cancel()
                return@launch
            } catch (error: Throwable) {
                runCatching { transport?.close() }
                runCatching { listener?.close() }
                runCatching { identity?.close() }
                runCatching { networkScope?.close() }
                listenerScope?.cancel()
                transportScope?.cancel()
                if (!isCurrentGeneration(generation)) return@launch
                _receiverState.value = LanReceiverRuntimeState.Error(
                    message = error.message ?: "Unable to start LAN receiver."
                )
            }
        }
        return true
    }

    @SuppressLint("NewApi")
    fun startSender(
        ownerScope: CoroutineScope,
        invitation: LanInvitation,
        localDeviceName: String,
        bootstrapRole: String = dev.glide.core.transport.lan.LanBootstrapMessage.ROLE_SENDER,
        onConnected: (LanTransferTransport, PeerDevice) -> Unit,
        onFailure: (String) -> Unit
    ): Boolean = startSender(
        ownerScope = ownerScope,
        invitation = invitation,
        localDeviceName = localDeviceName,
        bootstrapRole = bootstrapRole,
        onConnected = onConnected,
        onFailureTyped = { failure -> onFailure(failure.message) }
    )

    @JvmName("startSenderTyped")
    @SuppressLint("NewApi")
    fun startSender(
        ownerScope: CoroutineScope,
        invitation: LanInvitation,
        localDeviceName: String,
        bootstrapRole: String = dev.glide.core.transport.lan.LanBootstrapMessage.ROLE_SENDER,
        onConnected: (LanTransferTransport, PeerDevice) -> Unit,
        onFailureTyped: (dev.glide.transport.lan.LanConnectionFailure) -> Unit
    ): Boolean {
        val readinessError = readinessError()
        if (readinessError != null) {
            onFailureTyped(dev.glide.transport.lan.LanConnectionFailure.NetworkUnavailable(readinessError))
            return false
        }

        stopAll()
        val generation = synchronized(lock) { ++lifecycleGeneration }

        setupJob = ownerScope.launch {
            var networkScope: LanNetworkScope? = null
            var transport: LanTransferTransport? = null
            var transportScope: CoroutineScope? = null

            try {
                networkScope = networkScopeFactory()
                if (awaitAvailableNetwork(networkScope) == null) {
                    throw dev.glide.transport.lan.LanConnectionException(
                        dev.glide.transport.lan.LanConnectionFailure.NetworkUnavailable()
                    )
                }

                val activeTransportScope = childScope(ownerScope)
                transportScope = activeTransportScope
                val activeTransport = transportFactory(networkScope, activeTransportScope)
                transport = activeTransport
                val peerId = PeerId("lan-${invitation.invitationId}")
                activeTransport.registerPendingInvitation(peerId, invitation, bootstrapRole)

                lateinit var connectionJob: Job
                connectionJob = activeTransportScope.launch {
                    activeTransport.observeConnectionEvents().collect { event ->
                        if (!isCurrentGeneration(generation)) return@collect
                        when (event) {
                            is ConnectionEvent.Connected ->
                                onConnected(activeTransport, event.peer)
                            is ConnectionEvent.Failed ->
                                onFailureTyped(dev.glide.transport.lan.LanConnectionFailure.Other(event.reason))
                            is ConnectionEvent.Rejected ->
                                onFailureTyped(dev.glide.transport.lan.LanConnectionFailure.Other("LAN pairing was rejected."))
                            is ConnectionEvent.Disconnected,
                            is ConnectionEvent.Initiated -> Unit
                        }
                    }
                }

                synchronized(lock) {
                    senderResources = SenderResources(
                        networkScope = networkScope,
                        transport = activeTransport,
                        transportScope = activeTransportScope,
                        connectionJob = connectionJob
                    )
                }

                activeTransport.initiateConnection(
                    peerId = peerId,
                    localDeviceName = localDeviceName.trim().ifEmpty { "Glide Device" }
                )
            } catch (cancelled: CancellationException) {
                runCatching { transport?.close() }
                runCatching { networkScope?.close() }
                transportScope?.cancel()
                return@launch
            } catch (error: Throwable) {
                runCatching { transport?.close() }
                runCatching { networkScope?.close() }
                transportScope?.cancel()
                if (!isCurrentGeneration(generation)) return@launch
                val failure = when (error) {
                    is dev.glide.transport.lan.LanConnectionException -> error.failure
                    is java.net.SocketTimeoutException -> dev.glide.transport.lan.LanConnectionFailure.Timeout()
                    is javax.net.ssl.SSLHandshakeException -> dev.glide.transport.lan.LanConnectionFailure.AuthenticationFailed()
                    else -> {
                        val msg = error.message ?: ""
                        if (msg.contains("OFF_LINK", ignoreCase = true) || msg.contains("off-link", ignoreCase = true)) {
                            dev.glide.transport.lan.LanConnectionFailure.OffLinkDestination()
                        } else {
                            dev.glide.transport.lan.LanConnectionFailure.Other(msg.ifBlank { "Unable to connect to LAN invitation." })
                        }
                    }
                }
                onFailureTyped(failure)
            }
        }
        return true
    }

    /** Resolve a current LAN endpoint for an enrolled identity without minting a QR invitation. */
    @SuppressLint("NewApi")
    fun startTrustedSender(
        ownerScope: CoroutineScope,
        trustedPeer: TrustedPeerRecord,
        trustManager: PersistentTrustManager,
        localDeviceName: String,
        onConnected: (LanTransferTransport, PeerDevice) -> Unit,
        onFailure: (dev.glide.transport.lan.LanConnectionFailure) -> Unit,
        resume: LanTransferTransport.TrustedResumeOpening? = null
    ): Boolean {
        val readinessError = readinessError()
        if (readinessError != null) {
            onFailure(dev.glide.transport.lan.LanConnectionFailure.NetworkUnavailable(readinessError))
            return false
        }

        stopAll()
        val generation = synchronized(lock) { ++lifecycleGeneration }
        setupJob = ownerScope.launch {
            var networkScope: LanNetworkScope? = null
            var transport: LanTransferTransport? = null
            var transportScope: CoroutineScope? = null
            try {
                networkScope = networkScopeFactory()
                check(awaitAvailableNetwork(networkScope) != null) {
                    "No eligible local network is available."
                }
                val activeScope = childScope(ownerScope)
                transportScope = activeScope
                val activeTransport = transportFactory(networkScope, activeScope)
                transport = activeTransport
                val discovery = activeTransport.nsdDiscoveryAdapter
                    ?: throw IllegalStateException("Local device discovery is unavailable.")
                discovery.clearObservations()
                discovery.startDiscovery()
                val deadlineNs = System.nanoTime() + TRUSTED_DISCOVERY_TIMEOUT_MS * 1_000_000
                fun currentCandidates(): List<dev.glide.transport.lan.discovery.NsdDiscoveryAdapter.TrustedReconnectCandidate> {
                    val currentGeneration =
                        (networkScope.networkState.value as? LanNetworkState.Available)?.generation
                            ?: return emptyList()
                    val nowMs = System.currentTimeMillis()
                    return discovery.getTrustedReconnectCandidates().filter {
                        it.networkGeneration == currentGeneration &&
                            nowMs - it.observedAtMs in 0..TRUSTED_CANDIDATE_MAX_AGE_MS
                    }
                }
                var found = currentCandidates()
                while (found.none { it.displayName.equals(trustedPeer.displayName, ignoreCase = true) } &&
                    System.nanoTime() < deadlineNs) {
                    delay(150)
                    found = currentCandidates()
                }
                discovery.stopDiscovery()

                // Names order candidates; the selected saved identity is verified before any offer.
                val candidates = found
                    .sortedWith(compareByDescending<dev.glide.transport.lan.discovery.NsdDiscoveryAdapter.TrustedReconnectCandidate> {
                        it.displayName.equals(trustedPeer.displayName, ignoreCase = true)
                    }.thenByDescending { it.observedAtMs })
                    .take(MAX_TRUSTED_CANDIDATES)
                if (candidates.isEmpty()) {
                    throw IllegalStateException("The paired device is not available on this network.")
                }

                val peerId = PeerId("lan-trusted-${java.util.UUID.randomUUID()}")
                activeTransport.registerPendingTrustedPeer(
                    peerId,
                    LanTransferTransport.PendingTrustedPeer(
                        expectedFingerprintHex = trustedPeer.peerFingerprintHex,
                        expectedPublicKeyDerHex = trustedPeer.publicKeyDerHex,
                        displayName = trustedPeer.displayName,
                        endpoints = candidates.map { LanEndpoint(it.address, it.port) }.distinct(),
                        localIdentityPublicKeyDer = trustManager.localPublicKeyDer(),
                        expectedPresenceId = candidates.singleOrNull()?.presenceId,
                        resume = resume
                    )
                )
                val connectionJob = activeScope.launch {
                    activeTransport.observeConnectionEvents().collect { event ->
                        if (!isCurrentGeneration(generation)) return@collect
                        when (event) {
                            is ConnectionEvent.Connected -> onConnected(activeTransport, event.peer)
                            is ConnectionEvent.Failed -> onFailure(
                                dev.glide.transport.lan.LanConnectionFailure.Other(event.reason)
                            )
                            is ConnectionEvent.Rejected -> onFailure(
                                dev.glide.transport.lan.LanConnectionFailure.Other("Connection declined.")
                            )
                            else -> Unit
                        }
                    }
                }
                synchronized(lock) {
                    senderResources = SenderResources(
                        networkScope = networkScope,
                        transport = activeTransport,
                        transportScope = activeScope,
                        connectionJob = connectionJob
                    )
                }
                activeTransport.initiateConnection(peerId, localDeviceName)
            } catch (cancelled: CancellationException) {
                runCatching { transport?.close() }
                runCatching { networkScope?.close() }
                transportScope?.cancel()
            } catch (error: Throwable) {
                runCatching { transport?.close() }
                runCatching { networkScope?.close() }
                transportScope?.cancel()
                if (isCurrentGeneration(generation)) {
                    val failure = when (error) {
                        is dev.glide.transport.lan.LanConnectionException -> error.failure
                        is javax.net.ssl.SSLHandshakeException ->
                            dev.glide.transport.lan.LanConnectionFailure.AuthenticationFailed()
                        is java.net.SocketTimeoutException ->
                            dev.glide.transport.lan.LanConnectionFailure.Timeout()
                        else -> dev.glide.transport.lan.LanConnectionFailure.Other(
                            error.message ?: "Could not connect to the paired device."
                        )
                    }
                    onFailure(failure)
                }
            }
        }
        return true
    }

    /**
     * Connects to a sender-hosted invitation. The socket initiator is the local receiver;
     * transfer direction is therefore intentionally independent of connection direction.
     */
    @SuppressLint("NewApi")
    fun startReceiverViaInvitation(
        ownerScope: CoroutineScope,
        invitation: LanInvitation,
        localDeviceName: String,
        onConnected: (LanTransferTransport, PeerDevice) -> Unit,
        onFailure: (String) -> Unit
    ): Boolean = startSender(
        ownerScope = ownerScope,
        invitation = invitation,
        localDeviceName = localDeviceName,
        bootstrapRole = dev.glide.core.transport.lan.LanBootstrapMessage.ROLE_RECEIVER,
        onConnected = onConnected,
        onFailure = onFailure
    )

    @JvmName("startReceiverViaInvitationTyped")
    @SuppressLint("NewApi")
    fun startReceiverViaInvitation(
        ownerScope: CoroutineScope,
        invitation: LanInvitation,
        localDeviceName: String,
        onConnected: (LanTransferTransport, PeerDevice) -> Unit,
        onFailureTyped: (dev.glide.transport.lan.LanConnectionFailure) -> Unit
    ): Boolean = startSender(
        ownerScope = ownerScope,
        invitation = invitation,
        localDeviceName = localDeviceName,
        bootstrapRole = dev.glide.core.transport.lan.LanBootstrapMessage.ROLE_RECEIVER,
        onConnected = onConnected,
        onFailureTyped = onFailureTyped
    )

    @SuppressLint("NewApi")
    fun revokeReceiverInvitation(closeListener: Boolean = true) {
        val resources = synchronized(lock) { receiverResources } ?: return
        resources.authority.revokeActiveInvitation()
        if (closeListener) {
            runCatching { resources.listener.close() }
        }
        if (_receiverState.value is LanReceiverRuntimeState.Ready ||
            _receiverState.value is LanReceiverRuntimeState.Starting
        ) {
            _receiverState.value = if (closeListener) LanReceiverRuntimeState.Idle
                else LanReceiverRuntimeState.Available
        }
    }

    @SuppressLint("NewApi")
    fun stopAll() {
        val receiver: ReceiverResources?
        val sender: SenderResources?
        synchronized(lock) {
            lifecycleGeneration++
            setupJob?.cancel()
            setupJob = null
            receiver = receiverResources
            sender = senderResources
            receiverResources = null
            senderResources = null
        }

        receiver?.let {
            it.authority.revokeAll()
            it.connectionJob.cancel()
            it.networkJob.cancel()
            runCatching { it.transport.close() }
            runCatching { it.listener.close() }
            runCatching { it.identity.close() }
            runCatching { it.networkScope.close() }
            it.listenerScope.cancel()
            it.transportScope.cancel()
        }

        sender?.let {
            it.connectionJob.cancel()
            runCatching { it.transport.close() }
            runCatching { it.networkScope.close() }
            it.transportScope.cancel()
        }

        _receiverState.value = LanReceiverRuntimeState.Idle
    }

    private fun isCurrentGeneration(generation: Long): Boolean =
        synchronized(lock) { lifecycleGeneration == generation }

    private suspend fun awaitAvailableNetwork(
        networkScope: LanNetworkScope
    ): LanNetworkState.Available? {
        val current = networkScope.networkState.value
        if (current is LanNetworkState.Available) return current

        return withTimeoutOrNull(NETWORK_READY_TIMEOUT_MS) {
            networkScope.networkState
                .filterIsInstance<LanNetworkState.Available>()
                .first()
        }
    }

    private fun endpointsFor(
        addresses: List<InetAddress>,
        port: Int
    ): List<LanEndpoint> {
        return addresses
            .asSequence()
            .filter { address ->
                dev.glide.transport.lan.network.AndroidHotspotConnections.host?.let {
                    address.hostAddress?.let(it.addresses::contains) == true
                } ?: true
            }
            .filterNot {
                it.isAnyLocalAddress ||
                    it.isLinkLocalAddress ||
                    (!allowLoopbackEndpointsForTesting && it.isLoopbackAddress)
            }
            .sortedByDescending { it is java.net.Inet4Address }
            .mapNotNull { address ->
                val numeric = address.hostAddress
                    ?.substringBefore('%')
                    ?.takeIf(LanEndpoint::isValidAddress)
                numeric?.let { LanEndpoint(it, port) }
            }
            .distinct()
            .take(LanInvitation.MAX_ENDPOINTS)
            .toList()
    }

    private fun readinessError(): String? {
        if (!LanFeatureConfig.isLanTransportEnabled) {
            return "LAN transfer is disabled in this build."
        }
        if (apiLevelProvider() < Build.VERSION_CODES.O) {
            return "LAN transfer requires Android 8.0 or newer."
        }
        val missing = PermissionPolicy.lanTransferPermissions(apiLevelProvider())
            .filterNot(permissionChecker)
        if (missing.isNotEmpty()) {
            return "Local network permission is required for LAN transfer."
        }
        return null
    }

    private fun childScope(parent: CoroutineScope): CoroutineScope {
        val parentJob = parent.coroutineContext[Job]
        return CoroutineScope(
            parent.coroutineContext + SupervisorJob(parentJob)
        )
    }

    companion object {
        private const val NETWORK_READY_TIMEOUT_MS = 5_000L
        private const val TRUSTED_DISCOVERY_TIMEOUT_MS = 6_000L
        private const val TRUSTED_CANDIDATE_MAX_AGE_MS = 10_000L
        private const val MAX_TRUSTED_CANDIDATES = 8
        private const val LEASE_RENEWAL_MARGIN_MS = 30_000L

        @SuppressLint("NewApi")
        internal fun forTesting(
            networkScopeFactory: () -> LanNetworkScope,
            identityFactory: () -> LanLeaseIdentity =
                { LanLeaseIdentity.create(preferAndroidKeyStore = false) },
            permissionChecker: (String) -> Boolean = { true },
            apiLevelProvider: () -> Int = { Build.VERSION_CODES.Q },
            transportFactory: (LanNetworkScope, CoroutineScope) -> LanTransferTransport =
                { networkScope, scope ->
                    LanTransferTransport(
                        networkScope = networkScope,
                        allowLoopbackForTesting = true,
                        coroutineScope = scope
                    )
                },
            listenerFactory: (
                LanLeaseIdentity,
                LanNetworkScope,
                CoroutineScope
            ) -> LanListener = { identity, networkScope, scope ->
                LanListener(
                    identity = identity,
                    networkScope = networkScope,
                    bindAddress = InetAddress.getByName("127.0.0.1"),
                    port = 0,
                    allowLoopbackForTesting = true,
                    coroutineScope = scope
                )
            }
        ): ProductionLanController = ProductionLanController(
            networkScopeFactory = networkScopeFactory,
            identityFactory = identityFactory,
            permissionChecker = permissionChecker,
            apiLevelProvider = apiLevelProvider,
            transportFactory = transportFactory,
            allowLoopbackEndpointsForTesting = true,
            listenerFactory = listenerFactory
        )
    }
}
