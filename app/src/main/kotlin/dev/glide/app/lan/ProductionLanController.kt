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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface LanReceiverRuntimeState {
    data object Idle : LanReceiverRuntimeState
    data object Starting : LanReceiverRuntimeState

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                LanTransferTransport(
                    networkScope = networkScope,
                    coroutineScope = scope
                )
            } else {
                throw IllegalStateException("LAN transfer requires Android 10 or newer.")
            }
        },
        allowLoopbackEndpointsForTesting = false,
        listenerFactory = { identity, networkScope, scope ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                LanListener(
                    identity = identity,
                    networkScope = networkScope,
                    port = 0,
                    coroutineScope = scope
                )
            } else {
                throw IllegalStateException("LAN listener requires Android 10 or newer.")
            }
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
        val connectionJob: Job
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

                lateinit var connectionJob: Job
                connectionJob = activeTransportScope.launch {
                    activeTransport.observeConnectionEvents().collect { event ->
                        if (!isCurrentGeneration(generation)) return@collect
                        when (event) {
                            is ConnectionEvent.Connected -> {
                                authority.revokeActiveInvitation()
                                runCatching { activeListener.close() }
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
                            is ConnectionEvent.Disconnected,
                            is ConnectionEvent.Initiated -> Unit
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
                        connectionJob = connectionJob
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
        onConnected: (LanTransferTransport, PeerDevice) -> Unit,
        onFailure: (String) -> Unit
    ): Boolean {
        val readinessError = readinessError()
        if (readinessError != null) {
            onFailure(readinessError)
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
                    throw IllegalStateException(
                        "No eligible local Wi-Fi/Ethernet network is available."
                    )
                }

                val activeTransportScope = childScope(ownerScope)
                transportScope = activeTransportScope
                val activeTransport = transportFactory(networkScope, activeTransportScope)
                transport = activeTransport
                val peerId = PeerId("lan-${invitation.invitationId}")
                activeTransport.registerPendingInvitation(peerId, invitation)

                lateinit var connectionJob: Job
                connectionJob = activeTransportScope.launch {
                    activeTransport.observeConnectionEvents().collect { event ->
                        if (!isCurrentGeneration(generation)) return@collect
                        when (event) {
                            is ConnectionEvent.Connected ->
                                onConnected(activeTransport, event.peer)
                            is ConnectionEvent.Failed ->
                                onFailure(event.reason)
                            is ConnectionEvent.Rejected ->
                                onFailure("LAN pairing was rejected.")
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
                onFailure(error.message ?: "Unable to connect to LAN invitation.")
            }
        }
        return true
    }

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
            _receiverState.value = LanReceiverRuntimeState.Idle
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
        if (apiLevelProvider() < Build.VERSION_CODES.Q) {
            return "LAN transfer requires Android 10 or newer."
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