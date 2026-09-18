package dev.glide.app.discovery

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.glide.app.permission.PermissionPolicy
import dev.glide.app.ui.discovery.BlockedReason
import dev.glide.app.ui.discovery.DeviceType
import dev.glide.app.ui.discovery.DiscoveredPeer
import dev.glide.app.ui.discovery.DiscoveryUiState
import dev.glide.app.ui.discovery.ReceiveReadinessUiState
import dev.glide.core.ports.AdvertisingEvent
import dev.glide.core.ports.DiscoveryEvent
import dev.glide.core.ports.TransferTransport
import dev.glide.transport.nearby.AndroidNearbyAvailabilityChecker
import dev.glide.transport.nearby.NearbyAvailabilityChecker
import dev.glide.transport.nearby.NearbyConfig
import dev.glide.transport.nearby.NearbyHardwareStatus
import dev.glide.transport.nearby.NearbyTransferTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production authority for Android Nearby discovery/advertising (HARD-006).
 *
 * Radio work is owned outside presentation/ViewModel scopes. Permission and hardware readiness are
 * revalidated immediately before each start, so a UI/test caller cannot bypass the production gate.
 */
@Singleton
class ProductionDiscoveryController private constructor(
    private val runtimeScope: CoroutineScope,
    private val transportFactory: (CoroutineScope) -> TransferTransport,
    private val permissionChecker: (String) -> Boolean,
    private val hardwareChecker: NearbyAvailabilityChecker,
    private val apiLevelProvider: () -> Int,
    private val serviceIdProvider: () -> String
) {

    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(
        runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        transportFactory = { scope ->
            NearbyTransferTransport.create(context.applicationContext, scope)
        },
        permissionChecker = { permission ->
            ContextCompat.checkSelfPermission(
                context.applicationContext,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        },
        hardwareChecker = AndroidNearbyAvailabilityChecker(context.applicationContext),
        apiLevelProvider = { Build.VERSION.SDK_INT },
        serviceIdProvider = {
            val isDebuggable =
                (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            NearbyConfig.serviceId(isDebuggable)
        }
    )

    private val lock = Any()
    private var discoveryTransport: TransferTransport? = null
    private var sessionTransport: TransferTransport? = null
    private var activeAdvertisingTransport: TransferTransport? = null
    private var discoveryJob: Job? = null
    private var discoveryTimerJob: Job? = null
    private var advertisingJob: Job? = null
    private var advertisingTimerJob: Job? = null

    private val _discoveryState = MutableStateFlow<DiscoveryUiState>(
        DiscoveryUiState.Blocked(
            BlockedReason.TransportError("Nearby discovery has not started")
        )
    )
    val discoveryState: StateFlow<DiscoveryUiState> = _discoveryState.asStateFlow()

    private val _receiveReadinessState = MutableStateFlow<ReceiveReadinessUiState>(
        ReceiveReadinessUiState.Blocked(
            BlockedReason.TransportError("Nearby receive availability has not started")
        )
    )
    val receiveReadinessState: StateFlow<ReceiveReadinessUiState> =
        _receiveReadinessState.asStateFlow()

    fun startDiscovery(): Boolean {
        stopDiscovery()
        stopAdvertising()

        val blockedReason = evaluateNearbyBlock()
        if (blockedReason != null) {
            _discoveryState.value = DiscoveryUiState.Blocked(blockedReason)
            return false
        }

        val activeTransport = getOrCreateDiscoveryTransport()
        _discoveryState.value = DiscoveryUiState.Searching()

        discoveryJob = runtimeScope.launch {
            try {
                activeTransport.startDiscovery(serviceIdProvider()).collect { event ->
                    when (event) {
                        is DiscoveryEvent.PeerAppeared -> onPeerAppeared(event)
                        is DiscoveryEvent.PeerLost -> onPeerLost(event)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _discoveryState.value = DiscoveryUiState.Blocked(
                    BlockedReason.TransportError(
                        error.message ?: "Nearby discovery failed"
                    )
                )
            }
        }

        discoveryTimerJob = runtimeScope.launch {
            for (second in 1..120) {
                delay(1_000L)
                val current = _discoveryState.value
                if (current !is DiscoveryUiState.Searching) break

                if (second >= 120) {
                    _discoveryState.value = DiscoveryUiState.TimedOut(current.peers)
                    stopDiscovery()
                    break
                }

                _discoveryState.value = current.copy(secondsElapsed = second)
            }
        }
        return true
    }

    fun stopDiscovery() {
        discoveryTimerJob?.cancel()
        discoveryTimerJob = null
        discoveryJob?.cancel()
        discoveryJob = null

        val activeTransport = synchronized(lock) { discoveryTransport }
        if (activeTransport != null) {
            runtimeScope.launch {
                runCatching { activeTransport.stopDiscovery() }
            }
        }
    }

    fun startAdvertising(localDeviceName: String, transportOverride: TransferTransport? = null): Boolean {
        stopDiscovery()
        stopAdvertising()

        val blockedReason = evaluateNearbyBlock()
        if (blockedReason != null) {
            _receiveReadinessState.value = ReceiveReadinessUiState.Blocked(blockedReason)
            return false
        }

        val activeTransport = transportOverride ?: getOrCreateDiscoveryTransport()
        synchronized(lock) {
            activeAdvertisingTransport = activeTransport
        }
        val safeName = localDeviceName.trim().ifEmpty { "Glide Device" }
        _receiveReadinessState.value = ReceiveReadinessUiState.Advertising(
            localDeviceName = safeName,
            secondsRemaining = 120
        )

        advertisingJob = runtimeScope.launch {
            try {
                activeTransport.startAdvertising(
                    serviceId = serviceIdProvider(),
                    localDeviceName = safeName
                ).collect { event ->
                    when (event) {
                        is AdvertisingEvent.ConnectionInitiated -> {
                            advertisingTimerJob?.cancel()
                            advertisingTimerJob = null
                            _receiveReadinessState.value =
                                ReceiveReadinessUiState.IncomingConnection(
                                    peer = DiscoveredPeer(
                                        id = event.peer.id,
                                        displayName = event.peer.displayName,
                                        deviceType = DeviceType.fromDeviceName(event.peer.displayName)
                                    ),
                                    authDigits = event.authDigits
                                )
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _receiveReadinessState.value = ReceiveReadinessUiState.Blocked(
                    BlockedReason.TransportError(
                        error.message ?: "Nearby advertising failed"
                    )
                )
            }
        }

        advertisingTimerJob = runtimeScope.launch {
            for (remaining in 119 downTo 0) {
                delay(1_000L)
                val current = _receiveReadinessState.value
                if (current !is ReceiveReadinessUiState.Advertising) break

                if (remaining <= 0) {
                    _receiveReadinessState.value = ReceiveReadinessUiState.TimedOut
                    stopAdvertising()
                    break
                }

                _receiveReadinessState.value = current.copy(secondsRemaining = remaining)
            }
        }
        return true
    }

    fun stopAdvertising() {
        advertisingTimerJob?.cancel()
        advertisingTimerJob = null
        advertisingJob?.cancel()
        advertisingJob = null

        val activeTransport = synchronized(lock) {
            activeAdvertisingTransport.also { activeAdvertisingTransport = null }
        }
        if (activeTransport != null) {
            runtimeScope.launch {
                runCatching { activeTransport.stopAdvertising() }
            }
        }
    }

    fun onPermissionDenied(receiveMode: Boolean) {
        if (receiveMode) {
            stopAdvertising()
            _receiveReadinessState.value = ReceiveReadinessUiState.Blocked(
                BlockedReason.PermissionsRequired
            )
        } else {
            stopDiscovery()
            _discoveryState.value = DiscoveryUiState.Blocked(
                BlockedReason.PermissionsRequired
            )
        }
    }

    fun hasNearbyPermissions(): Boolean {
        val required = PermissionPolicy.nearbyTransferPermissions(apiLevelProvider())
        return required.all(permissionChecker)
    }

    internal fun resetForTesting() {
        stopDiscovery()
        stopAdvertising()
        _discoveryState.value = DiscoveryUiState.Blocked(
            BlockedReason.TransportError("Nearby discovery has not started")
        )
        _receiveReadinessState.value = ReceiveReadinessUiState.Blocked(
            BlockedReason.TransportError("Nearby receive availability has not started")
        )
    }

    /**
     * Acquires the transport used for authenticated pairing/transfer. In production the caller
     * supplies the foreground-service scope so connection timeouts/payload work cannot outlive it.
     */
    internal fun acquireSessionTransport(ownerScope: CoroutineScope): TransferTransport? {
        val blockedReason = evaluateNearbyBlock()
        if (blockedReason != null) {
            _discoveryState.value = DiscoveryUiState.Blocked(blockedReason)
            _receiveReadinessState.value = ReceiveReadinessUiState.Blocked(blockedReason)
            return null
        }

        return synchronized(lock) {
            sessionTransport ?: transportFactory(ownerScope).also { sessionTransport = it }
        }
    }

    internal fun currentSessionTransport(): TransferTransport? =
        synchronized(lock) { sessionTransport }

    private fun getOrCreateDiscoveryTransport(): TransferTransport {
        return synchronized(lock) {
            discoveryTransport ?: transportFactory(runtimeScope).also { discoveryTransport = it }
        }
    }

    private fun evaluateNearbyBlock(): BlockedReason? {
        if (!hasNearbyPermissions()) {
            return BlockedReason.PermissionsRequired
        }

        return when (val status = hardwareChecker.checkHardwareStatus()) {
            NearbyHardwareStatus.Available -> null
            NearbyHardwareStatus.BluetoothDisabled -> BlockedReason.BluetoothDisabled
            NearbyHardwareStatus.WifiDisabled -> BlockedReason.WifiDisabled
            NearbyHardwareStatus.LocationServicesDisabled ->
                BlockedReason.TransportError("Location services are required for Nearby discovery on this Android version.")
            is NearbyHardwareStatus.PlayServicesUnavailable ->
                BlockedReason.TransportError(
                    "Google Play services Nearby is unavailable (error ${status.errorCode})."
                )
        }
    }

    private fun onPeerAppeared(event: DiscoveryEvent.PeerAppeared) {
        val current = _discoveryState.value
        if (current !is DiscoveryUiState.Searching) return

        val peer = DiscoveredPeer(
            id = event.peer.id,
            displayName = event.peer.displayName,
            deviceType = DeviceType.fromDeviceName(event.peer.displayName)
        )
        val existingIndex = current.peers.indexOfFirst { it.id == peer.id }
        val peers = if (existingIndex >= 0) {
            current.peers.toMutableList().apply {
                val existing = this[existingIndex]
                this[existingIndex] = peer.copy(
                    isConnecting = existing.isConnecting,
                    isSelected = existing.isSelected,
                    isBusy = existing.isBusy
                )
            }
        } else {
            current.peers + peer
        }

        _discoveryState.value = current.copy(peers = peers)
    }

    private fun onPeerLost(event: DiscoveryEvent.PeerLost) {
        val current = _discoveryState.value
        if (current !is DiscoveryUiState.Searching) return

        _discoveryState.value = current.copy(
            peers = current.peers.map { peer ->
                if (peer.id == event.peerId) peer.copy(isAvailable = false) else peer
            }
        )
    }

    companion object {
        internal fun forTesting(
            runtimeScope: CoroutineScope,
            transportFactory: (CoroutineScope) -> TransferTransport,
            permissionChecker: (String) -> Boolean = { true },
            hardwareChecker: NearbyAvailabilityChecker = NearbyAvailabilityChecker {
                NearbyHardwareStatus.Available
            },
            apiLevelProvider: () -> Int = { 34 },
            serviceIdProvider: () -> String = { "glide.test" }
        ): ProductionDiscoveryController = ProductionDiscoveryController(
            runtimeScope = runtimeScope,
            transportFactory = transportFactory,
            permissionChecker = permissionChecker,
            hardwareChecker = hardwareChecker,
            apiLevelProvider = apiLevelProvider,
            serviceIdProvider = serviceIdProvider
        )
    }
}
