package dev.glide.transport.nearby

import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import dev.glide.core.ports.AdvertisingEvent
import dev.glide.core.ports.DiscoveryEvent
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * Adapter managing Nearby Connections discovery and advertising reactive lifecycles.
 * Enforces P2P_POINT_TO_POINT strategy, 120s timeouts, safe radio release, and domain event mapping.
 */
class NearbyDiscoveryAdapter(
    private val clientWrapper: NearbyClientWrapper
) {
    @Volatile
    private var activeDiscoveryChannel: SendChannel<DiscoveryEvent>? = null

    @Volatile
    private var activeAdvertisingChannel: SendChannel<AdvertisingEvent>? = null

    /**
     * Discovers nearby peers advertising the specified service ID.
     * Automatically stops scanning and releases radio resources when cancelled or after [timeoutMs].
     */
    fun discoverPeers(
        serviceId: String = NearbyConfig.SERVICE_ID_RELEASE,
        timeoutMs: Long = NearbyConfig.DISCOVERY_TIMEOUT_MS
    ): Flow<DiscoveryEvent> = callbackFlow {
        // Cancel any previous discovery flow to ensure clean radio state
        activeDiscoveryChannel?.close()
        activeDiscoveryChannel = channel

        val callback = object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                val peerId = sanitizeEndpointId(endpointId)
                val displayName = sanitizeDeviceName(info.endpointName)
                val peer = PeerDevice(id = peerId, displayName = displayName)
                trySend(DiscoveryEvent.PeerAppeared(peer))
            }

            override fun onEndpointLost(endpointId: String) {
                val peerId = sanitizeEndpointId(endpointId)
                trySend(DiscoveryEvent.PeerLost(peerId))
            }
        }

        val options = DiscoveryOptions.Builder()
            .setStrategy(NearbyConfig.STRATEGY)
            .build()

        try {
            clientWrapper.startDiscovery(serviceId, callback, options)
        } catch (e: Exception) {
            if (activeDiscoveryChannel === channel) {
                activeDiscoveryChannel = null
            }
            close(e)
            return@callbackFlow
        }

        val timeoutJob = if (timeoutMs > 0) {
            launch {
                delay(timeoutMs)
                channel.close()
            }
        } else null

        awaitClose {
            timeoutJob?.cancel()
            if (activeDiscoveryChannel === channel) {
                activeDiscoveryChannel = null
            }
            clientWrapper.stopDiscovery()
        }
    }

    /**
     * Stops active peer discovery and releases scanning radio resources.
     */
    fun stopDiscovery() {
        activeDiscoveryChannel?.close()
        activeDiscoveryChannel = null
        clientWrapper.stopDiscovery()
    }

    /**
     * Advertises local readiness to accept transfer connections from discovering peers.
     * Automatically stops advertising and releases radio resources when cancelled or after [timeoutMs].
     */
    fun advertiseReadiness(
        serviceId: String = NearbyConfig.SERVICE_ID_RELEASE,
        localDeviceName: String,
        timeoutMs: Long = NearbyConfig.DISCOVERY_TIMEOUT_MS,
        connectionCallback: ConnectionLifecycleCallback? = null
    ): Flow<AdvertisingEvent> = callbackFlow {
        // Cancel any previous advertising flow to ensure clean radio state
        activeAdvertisingChannel?.close()
        activeAdvertisingChannel = channel

        val callback = object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                val peerId = sanitizeEndpointId(endpointId)
                val displayName = sanitizeDeviceName(info.endpointName)
                val authDigits = info.authenticationDigits.takeIf { it.isNotBlank() } ?: "0000"
                val peer = PeerDevice(id = peerId, displayName = displayName)
                trySend(AdvertisingEvent.ConnectionInitiated(peer, authDigits))
                connectionCallback?.onConnectionInitiated(endpointId, info)
            }

            override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
                connectionCallback?.onConnectionResult(endpointId, resolution)
            }

            override fun onDisconnected(endpointId: String) {
                connectionCallback?.onDisconnected(endpointId)
            }
        }

        val options = AdvertisingOptions.Builder()
            .setStrategy(NearbyConfig.STRATEGY)
            .build()

        val safeName = sanitizeDeviceName(localDeviceName, fallback = "Glide Receiver")

        try {
            clientWrapper.startAdvertising(safeName, serviceId, callback, options)
        } catch (e: Exception) {
            if (activeAdvertisingChannel === channel) {
                activeAdvertisingChannel = null
            }
            close(e)
            return@callbackFlow
        }

        val timeoutJob = if (timeoutMs > 0) {
            launch {
                delay(timeoutMs)
                channel.close()
            }
        } else null

        awaitClose {
            timeoutJob?.cancel()
            if (activeAdvertisingChannel === channel) {
                activeAdvertisingChannel = null
            }
            clientWrapper.stopAdvertising()
        }
    }

    /**
     * Stops active advertising and releases advertising radio resources.
     */
    fun stopAdvertising() {
        activeAdvertisingChannel?.close()
        activeAdvertisingChannel = null
        clientWrapper.stopAdvertising()
    }

    companion object {
        internal fun sanitizeDeviceName(name: String?, fallback: String = "Glide Device"): String {
            val trimmed = name?.trim().orEmpty()
            if (trimmed.isEmpty()) return fallback
            return if (trimmed.length > 64) trimmed.substring(0, 64) else trimmed
        }

        internal fun sanitizeEndpointId(endpointId: String?, fallback: String = "unknown"): PeerId {
            val trimmed = endpointId?.trim().orEmpty()
            val valid = if (trimmed.isEmpty()) fallback else trimmed.take(64)
            return PeerId(valid)
        }
    }
}
