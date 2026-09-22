package dev.glide.app.discovery

import dev.glide.app.ui.discovery.DeviceType
import dev.glide.app.ui.discovery.DiscoveredPeer
import dev.glide.core.discovery.CandidateDeduplicator
import dev.glide.core.discovery.DeclaredPlatform
import dev.glide.core.discovery.ObservationSource
import dev.glide.core.discovery.PeerCandidate
import dev.glide.core.discovery.PeerObservation
import dev.glide.core.domain.PeerId
import dev.glide.core.ports.DiscoveryEvent
import dev.glide.core.routing.RouteCandidate
import dev.glide.core.transport.TransportType
import dev.glide.transport.lan.network.LanNetworkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * Aggregates and deduplicates discovered peer observations across Nearby and LAN transports (LAN-015).
 *
 * Invariants:
 * - Uses [CandidateDeduplicator] to enforce Section 7 identity rules.
 * - Adversarial duplicate-name handling: distinct peers with identical display names remain separate cards.
 * - Nearby and LAN observations remain separate cards when unproven (no unverified auto-merge).
 * - Stable non-jumping UI order: existing list indices and positions are preserved during discovery updates.
 * - Peer loss marks candidates as unavailable rather than abruptly dropping them during active discovery.
 * - Network scope tracking: when local network generation increments or becomes unavailable,
 *   stale LAN candidates from older generations are invalidated while Nearby candidates are preserved.
 */
class UnifiedDiscoveryRepository(
    private val nearbyDiscoveryFlowProvider: (() -> Flow<DiscoveryEvent>)? = null,
    private val lanDiscoveryFlowProvider: (() -> Flow<DiscoveryEvent>)? = null,
    private val lanObservationsProvider: (() -> List<PeerObservation>)? = null,
    private val networkScopeStateProvider: (() -> StateFlow<LanNetworkState>)? = null,
    private val coroutineScope: CoroutineScope
) : Closeable {

    private val lock = Any()
    private var currentCandidates: Map<String, PeerCandidate> = LinkedHashMap()
    private val observationIdToUiKey = mutableMapOf<String, String>()

    private val _unifiedPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val unifiedPeers: StateFlow<List<DiscoveredPeer>> = _unifiedPeers.asStateFlow()

    private var nearbyJob: Job? = null
    private var lanJob: Job? = null
    private var networkMonitorJob: Job? = null

    private var activeNetworkGeneration: Long? = null
    private var isClosed = false

    /**
     * Starts aggregating observations across all available discovery flows.
     */
    fun startDiscovery() {
        stopDiscovery()

        // 1. Monitor network scope state changes if provider exists
        if (dev.glide.app.config.LanFeatureConfig.isLanTransportEnabled && networkScopeStateProvider != null) {
            networkMonitorJob = coroutineScope.launch {
                networkScopeStateProvider.invoke().collect { state ->
                    handleNetworkStateChanged(state)
                }
            }
        }

        // 2. Start Nearby discovery collection
        if (nearbyDiscoveryFlowProvider != null) {
            nearbyJob = coroutineScope.launch {
                try {
                    nearbyDiscoveryFlowProvider.invoke().collect { event ->
                        handleNearbyEvent(event)
                    }
                } catch (_: Exception) {
                    // Flow closed or cancelled
                }
            }
        }

        // 3. Start LAN NSD discovery collection
        if (dev.glide.app.config.LanFeatureConfig.isLanTransportEnabled && lanDiscoveryFlowProvider != null) {
            lanJob = coroutineScope.launch {
                try {
                    lanDiscoveryFlowProvider.invoke().collect { event ->
                        handleLanEvent(event)
                    }
                } catch (_: Exception) {
                    // Flow closed or cancelled
                }
            }
        }
    }

    /**
     * Stops active discovery listeners without immediately dropping already displayed peers.
     */
    fun stopDiscovery() {
        nearbyJob?.cancel()
        nearbyJob = null
        lanJob?.cancel()
        lanJob = null
        networkMonitorJob?.cancel()
        networkMonitorJob = null
    }

    /**
     * Ingests a direct [PeerObservation], useful for testing or direct transport injection.
     */
    fun ingestObservation(
        observation: PeerObservation,
        associatedRoute: RouteCandidate? = null
    ) {
        synchronized(lock) {
            val route = associatedRoute ?: when (observation.source) {
                ObservationSource.NEARBY -> RouteCandidate(
                    routeId = "route-${observation.observationId}",
                    transportType = TransportType.GMS_NEARBY_P2P,
                    endpointHandle = observation.endpointHandle
                )
                ObservationSource.LAN_NSD -> RouteCandidate(
                    routeId = "route-${observation.observationId}",
                    transportType = TransportType.LAN_NSD_TLS,
                    endpointHandle = observation.endpointHandle,
                    networkHandle = observation.networkHandle
                )
                ObservationSource.QR_INVITATION -> RouteCandidate(
                    routeId = "route-${observation.observationId}",
                    transportType = TransportType.LAN_NSD_TLS,
                    endpointHandle = observation.endpointHandle
                )
            }
            val uiKey = CandidateDeduplicator.createUiKey(observation)
            observationIdToUiKey[observation.observationId] = uiKey
            currentCandidates = CandidateDeduplicator.ingestObservation(currentCandidates, observation, route)
            updatePeersLocked()
        }
    }

    /**
     * Retrieves the underlying [PeerCandidate] by its stable UI key for routing decisions (LAN-016).
     */
    fun getCandidate(uiKey: String): PeerCandidate? {
        return synchronized(lock) { currentCandidates[uiKey] }
    }

    /**
     * Clears all discovered candidates and resets the UI list.
     */
    fun clear() {
        synchronized(lock) {
            currentCandidates = LinkedHashMap()
            observationIdToUiKey.clear()
            updatePeersLocked()
        }
    }

    private fun handleNearbyEvent(event: DiscoveryEvent) {
        when (event) {
            is DiscoveryEvent.PeerAppeared -> {
                val observationId = "nearby-${event.peer.id.value}"
                val observation = PeerObservation(
                    observationId = observationId,
                    source = ObservationSource.NEARBY,
                    label = event.peer.displayName,
                    platform = DeclaredPlatform.ANDROID,
                    endpointHandle = event.peer.id.value,
                    observedAtMs = System.currentTimeMillis()
                )
                ingestObservation(observation)
            }

            is DiscoveryEvent.PeerLost -> {
                markObservationLost("nearby-${event.peerId.value}")
            }
        }
    }

    private fun handleLanEvent(event: DiscoveryEvent) {
        when (event) {
            is DiscoveryEvent.PeerAppeared -> {
                val observationId = event.peer.id.value
                val cachedObservation = lanObservationsProvider?.invoke()?.firstOrNull {
                    it.observationId == observationId
                }

                val observation = cachedObservation ?: PeerObservation(
                    observationId = observationId,
                    source = ObservationSource.LAN_NSD,
                    label = event.peer.displayName,
                    platform = DeclaredPlatform.UNKNOWN,
                    endpointHandle = observationId.removePrefix("lan-"),
                    networkHandle = activeNetworkGeneration?.let { "gen-$it" },
                    generation = activeNetworkGeneration ?: 0L,
                    observedAtMs = System.currentTimeMillis()
                )
                ingestObservation(observation)
            }

            is DiscoveryEvent.PeerLost -> {
                markObservationLost(event.peerId.value)
            }
        }
    }

    private fun markObservationLost(observationId: String) {
        synchronized(lock) {
            val uiKey = observationIdToUiKey[observationId]
                ?: currentCandidates.keys.firstOrNull { it.contains(observationId) }

            if (uiKey != null && currentCandidates.containsKey(uiKey)) {
                val candidate = currentCandidates[uiKey]!!
                currentCandidates = currentCandidates + (uiKey to candidate.copy(isAvailable = false))
                updatePeersLocked()
            }
        }
    }

    private fun handleNetworkStateChanged(state: LanNetworkState) {
        synchronized(lock) {
            when (state) {
                is LanNetworkState.Available -> {
                    val newGen = state.generation
                    if (activeNetworkGeneration != null && activeNetworkGeneration != newGen) {
                        // Network generation changed (e.g. reconnected or IP changed).
                        // Invalidate LAN candidates from the older generation.
                        invalidateLanCandidatesLocked()
                    }
                    activeNetworkGeneration = newGen
                }

                is LanNetworkState.NoNetwork, is LanNetworkState.VpnActive, is LanNetworkState.Closed -> {
                    // No valid local network available; flush LAN observations
                    activeNetworkGeneration = null
                    invalidateLanCandidatesLocked()
                }
            }
        }
    }

    private fun invalidateLanCandidatesLocked() {
        val updated = LinkedHashMap<String, PeerCandidate>()
        for ((key, candidate) in currentCandidates) {
            val hasNearby = candidate.observations.any { it.source == ObservationSource.NEARBY }
            if (hasNearby) {
                // Keep Nearby observations
                val nonLanObservations = candidate.observations.filterNot { it.source == ObservationSource.LAN_NSD }
                if (nonLanObservations.isNotEmpty()) {
                    updated[key] = candidate.copy(observations = nonLanObservations)
                }
            }
            // Pure LAN observations are evicted upon network loss/change
        }
        currentCandidates = updated
        observationIdToUiKey.entries.removeIf { it.key.startsWith("lan-") }
        updatePeersLocked()
    }

    private fun updatePeersLocked() {
        val list = currentCandidates.values.map { candidate ->
            DiscoveredPeer(
                id = PeerId(candidate.uiKey),
                displayName = candidate.displayName,
                deviceType = DeviceType.fromPlatform(candidate.platform, candidate.displayName),
                isAvailable = candidate.isAvailable,
                isConnecting = false,
                isSelected = false,
                routeSubtitle = candidate.routeSubtitle,
                uiKey = candidate.uiKey
            )
        }
        _unifiedPeers.value = list
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        stopDiscovery()
        clear()
    }
}
