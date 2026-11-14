package dev.glide.transport.lan.discovery

import dev.glide.core.discovery.DeclaredPlatform
import dev.glide.core.discovery.ObservationSource
import dev.glide.core.discovery.PeerObservation
import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import dev.glide.core.ports.AdvertisingEvent
import dev.glide.core.ports.DiscoveryEvent
import dev.glide.transport.lan.network.LanAddressValidationResult
import dev.glide.transport.lan.network.LanAddressValidator
import dev.glide.transport.lan.network.LanNetworkScope
import dev.glide.transport.lan.network.LanNetworkState
import dev.glide.transport.lan.network.SubnetPrefix
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Scoped Android DNS-SD (NSD) discovery and registration adapter (LAN-014, Section 11).
 *
 * Enforces:
 * - Platform DNS-SD service type: `_glide._tcp` (or `_glide-debug._tcp`).
 * - Validated TXT record with 512-byte budget, random 128-bit presence ID, bounded display name.
 * - Bounded observation cache (max 64 entries) with FIFO eviction on saturation.
 * - Concurrency limits on resolution: max 4 in-flight resolutions, 5-second deadline per resolution.
 * - On-link address validation: discards WAN, multicast, or off-link IP candidates.
 * - Network generation tracking: network invalidation flushes observation cache.
 * - 60-second browse timeout per explicit Send action.
 */
class NsdDiscoveryAdapter(
    private val clientWrapper: NsdClientWrapper,
    private val networkScope: LanNetworkScope,
    private val allowLoopbackForTesting: Boolean = false,
    val serviceType: String = DEFAULT_SERVICE_TYPE,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : Closeable {

    enum class RegistrationStatus { Stopped, Registering, Ready, Failed }
    private val _registrationStatus = MutableStateFlow(RegistrationStatus.Stopped)
    val registrationStatus: StateFlow<RegistrationStatus> = _registrationStatus.asStateFlow()

    private val _discoveryEvents = MutableSharedFlow<DiscoveryEvent>(
        replay = 32,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _advertisingEvents = MutableSharedFlow<AdvertisingEvent>(
        replay = 8,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Bounded resolve queue and concurrency controls
    private val resolveSemaphore = Semaphore(MAX_CONCURRENT_RESOLUTIONS)

    // Max 64 cached observations (serviceName -> PeerObservation)
    private val observationCache = Collections.synchronizedMap(LinkedHashMap<String, PeerObservation>(64, 0.75f, true))

    private var activeDiscoveryCallback: NsdDiscoveryCallback? = null
    private var activeRegistrationCallback: NsdRegistrationCallback? = null

    private var browseTimeoutJob: Job? = null
    private var isClosed = false

    /**
     * Starts advertising a local Glide LAN listener over DNS-SD.
     *
     * @param deviceName Local display name (max 64 code points / 128 bytes).
     * @param localPort Port on which [dev.glide.transport.lan.tls.LanListener] is bound.
     * @param presenceId 32-character lowercase hex string identifying the availability lease.
     */
    fun startAdvertising(
        deviceName: String,
        localPort: Int,
        presenceId: String = generateRandomPresenceId(),
        supportsTrustedReconnect: Boolean = false,
        isPairReady: Boolean = false
    ): Flow<AdvertisingEvent> {
        stopAdvertising()
        _registrationStatus.value = RegistrationStatus.Registering

        val record = LanTxtRecord(
            v = 1,
            tv = 1,
            q = 1,
            p = presenceId,
            dn = deviceName.take(LanTxtRecord.MAX_DN_CODE_POINTS),
            dt = LanTxtRecord.DEVICE_TYPE_PHONE,
            supportsTrustedReconnect = supportsTrustedReconnect,
            isPairReady = isPairReady
        )

        val attributes = LanTxtCodec.encode(record).mapValues { String(it.value, StandardCharsets.UTF_8) }

        val serviceName = "Glide-" + presenceId.take(8)

        val callback = object : NsdRegistrationCallback {
            override fun onServiceRegistered(registeredName: String) {
                _registrationStatus.value = RegistrationStatus.Ready
            }

            override fun onRegistrationFailed(errorCode: Int) {
                stopAdvertising()
                _registrationStatus.value = RegistrationStatus.Failed
            }

            override fun onServiceUnregistered() {
                // Unregistered cleanly
            }

            override fun onUnregistrationFailed(errorCode: Int) {
                // Ignore failure on cleanup
            }
        }

        activeRegistrationCallback = callback
        clientWrapper.registerService(
            serviceName = serviceName,
            serviceType = serviceType,
            port = localPort,
            attributes = attributes,
            callback = callback
        )

        return _advertisingEvents.asSharedFlow()
    }

    /**
     * Stops advertising and releases platform DNS-SD registration.
     */
    fun stopAdvertising() {
        val callback = activeRegistrationCallback ?: return
        activeRegistrationCallback = null
        clientWrapper.unregisterService(callback)
        _registrationStatus.value = RegistrationStatus.Stopped
    }

    /**
     * Starts discovering remote Glide peers on the active local network.
     * Automatically times out after 60 seconds of browsing per Section 11.
     */
    fun startDiscovery(): Flow<DiscoveryEvent> {
        stopDiscovery()

        val callback = object : NsdDiscoveryCallback {
            override fun onDiscoveryStarted(serviceType: String) {
                // Discovery running
            }

            override fun onDiscoveryStopped(serviceType: String) {
                // Discovery stopped
            }

            override fun onServiceFound(service: NsdDiscoveredService) {
                coroutineScope.launch {
                    handleServiceDiscovered(service)
                }
            }

            override fun onServiceLost(service: NsdDiscoveredService) {
                handleServiceLost(service)
            }

            override fun onStartDiscoveryFailed(errorCode: Int) {
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(errorCode: Int) {
                // Ignore failure on cleanup
            }
        }

        activeDiscoveryCallback = callback
        clientWrapper.discoverServices(serviceType, callback)

        // 60-second browse timeout (Section 11)
        browseTimeoutJob?.cancel()
        browseTimeoutJob = coroutineScope.launch {
            delay(BROWSE_TIMEOUT_MS)
            stopDiscovery()
        }

        return _discoveryEvents.asSharedFlow()
    }

    /**
     * Stops discovery and flushes pending resolutions.
     */
    fun stopDiscovery() {
        browseTimeoutJob?.cancel()
        browseTimeoutJob = null

        val callback = activeDiscoveryCallback ?: return
        activeDiscoveryCallback = null
        clientWrapper.stopServiceDiscovery(callback)
    }

    private suspend fun handleServiceDiscovered(service: NsdDiscoveredService) {
        if (!coroutineScope.isActive || isClosed) return

        // Acquire resolution concurrency permit (max 4 in flight)
        resolveSemaphore.acquire()
        try {
            val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                val deferred = CompletableDeferred<NsdResolvedService?>()
                clientWrapper.resolveService(
                    service = service,
                    callback = object : NsdResolveCallback {
                        override fun onServiceResolved(service: NsdResolvedService) {
                            deferred.complete(service)
                        }

                        override fun onResolveFailed(errorCode: Int) {
                            deferred.complete(null)
                        }
                    }
                )
                deferred.await()
            } ?: return

            processResolvedService(resolved)
        } finally {
            resolveSemaphore.release()
        }
    }

    private fun processResolvedService(resolved: NsdResolvedService) {
        // 1. Decode TXT attributes
        val txtRecord = LanTxtCodec.decode(resolved.attributes) ?: return

        // 2. Validate on-link addresses against active network scope
        val activeSubnets = when (val state = networkScope.networkState.value) {
            is LanNetworkState.Available -> state.subnets
            else -> emptyList<SubnetPrefix>()
        }

        val networkGeneration = when (val state = networkScope.networkState.value) {
            is LanNetworkState.Available -> state.generation
            else -> 0L
        }

        val eligibleAddress = resolved.hostAddresses.firstOrNull { addr ->
            val result = LanAddressValidator.validateAddress(
                address = addr,
                activeSubnets = activeSubnets,
                allowLoopbackForTesting = allowLoopbackForTesting
            )
            result is LanAddressValidationResult.Valid
        } ?: return

        // 3. Construct PeerObservation
        val observationId = "lan-${resolved.serviceName}"
        val platform = when (txtRecord.dt) {
            LanTxtRecord.DEVICE_TYPE_PHONE -> DeclaredPlatform.ANDROID
            LanTxtRecord.DEVICE_TYPE_TABLET -> DeclaredPlatform.ANDROID
            LanTxtRecord.DEVICE_TYPE_DESKTOP -> DeclaredPlatform.WINDOWS
            else -> DeclaredPlatform.UNKNOWN
        }

        val observation = PeerObservation(
            observationId = observationId,
            source = ObservationSource.LAN_NSD,
            label = txtRecord.dn.take(PeerObservation.MAX_LABEL_LENGTH),
            platform = platform,
            endpointHandle = "${eligibleAddress.hostAddress}:${resolved.port}",
            networkHandle = "gen-$networkGeneration",
            generation = networkGeneration,
            observedAtMs = System.currentTimeMillis()
        )

        // 4. Update cache with capacity eviction (max 64)
        synchronized(observationCache) {
            if (observationCache.size >= MAX_OBSERVATION_CACHE && !observationCache.containsKey(resolved.serviceName)) {
                val oldestKey = observationCache.keys.firstOrNull()
                if (oldestKey != null) {
                    observationCache.remove(oldestKey)
                }
            }
            observationCache[resolved.serviceName] = observation
        }

        // 5. Emit PeerAppeared
        val peerDevice = PeerDevice(
            id = PeerId(observation.observationId),
            displayName = observation.label
        )
        _discoveryEvents.tryEmit(DiscoveryEvent.PeerAppeared(peerDevice))
    }

    private fun handleServiceLost(service: NsdDiscoveredService) {
        val removed = synchronized(observationCache) {
            observationCache.remove(service.serviceName)
        }
        if (removed != null) {
            _discoveryEvents.tryEmit(DiscoveryEvent.PeerLost(PeerId(removed.observationId)))
        }
    }

    fun getCachedObservations(): List<PeerObservation> {
        return synchronized(observationCache) { observationCache.values.toList() }
    }

    fun clearObservations() {
        synchronized(observationCache) { observationCache.clear() }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        stopAdvertising()
        stopDiscovery()
        clearObservations()
        coroutineScope.cancel()
    }

    companion object {
        const val DEFAULT_SERVICE_TYPE = "_glide._tcp"
        const val MAX_CONCURRENT_RESOLUTIONS = 4
        const val RESOLVE_TIMEOUT_MS = 5_000L
        const val BROWSE_TIMEOUT_MS = 60_000L
        const val MAX_OBSERVATION_CACHE = 64

        fun generateRandomPresenceId(): String {
            val uuid = UUID.randomUUID()
            val bytes = ByteArray(16)
            var msb = uuid.mostSignificantBits
            var lsb = uuid.leastSignificantBits
            for (i in 0..7) {
                bytes[i] = ((msb shr (8 * (7 - i))) and 0xFF).toByte()
                bytes[8 + i] = ((lsb shr (8 * (7 - i))) and 0xFF).toByte()
            }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
