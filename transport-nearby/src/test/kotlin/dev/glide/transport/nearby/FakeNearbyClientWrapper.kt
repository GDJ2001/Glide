package dev.glide.transport.nearby

import com.google.android.gms.common.api.Status
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate

@Suppress("DEPRECATION")
class FakeNearbyClientWrapper : NearbyClientWrapper {
    var isDiscovering: Boolean = false
        private set
    var isAdvertising: Boolean = false
        private set

    var discoveryServiceId: String? = null
        private set
    var discoveryOptions: DiscoveryOptions? = null
        private set
    var discoveryCallback: EndpointDiscoveryCallback? = null
        private set

    var advertisingName: String? = null
        private set
    var advertisingServiceId: String? = null
        private set
    var advertisingOptions: AdvertisingOptions? = null
        private set
    var connectionCallback: ConnectionLifecycleCallback? = null

    val requestedConnections = mutableListOf<Pair<String, String>>()
    val acceptedConnections = mutableListOf<String>()
    val rejectedConnections = mutableListOf<String>()
    val disconnectedEndpoints = mutableListOf<String>()
    val sentPayloads = mutableListOf<Pair<String, Payload>>()
    val cancelledPayloads = mutableListOf<Long>()
    var payloadCallback: PayloadCallback? = null

    var stopDiscoveryCallCount: Int = 0
        private set
    var stopAdvertisingCallCount: Int = 0
        private set
    var stopAllEndpointsCallCount: Int = 0
        private set

    var startDiscoveryFailure: Exception? = null
    var startAdvertisingFailure: Exception? = null
    var requestConnectionFailure: Exception? = null
    var acceptConnectionFailure: Exception? = null
    var rejectConnectionFailure: Exception? = null
    var sendPayloadFailure: Exception? = null
    var cancelPayloadFailure: Exception? = null

    override suspend fun startDiscovery(
        serviceId: String,
        callback: EndpointDiscoveryCallback,
        options: DiscoveryOptions
    ) {
        startDiscoveryFailure?.let { throw it }
        isDiscovering = true
        discoveryServiceId = serviceId
        discoveryCallback = callback
        discoveryOptions = options
    }

    override fun stopDiscovery() {
        isDiscovering = false
        discoveryCallback = null
        stopDiscoveryCallCount++
    }

    override suspend fun startAdvertising(
        name: String,
        serviceId: String,
        callback: ConnectionLifecycleCallback,
        options: AdvertisingOptions
    ) {
        startAdvertisingFailure?.let { throw it }
        isAdvertising = true
        advertisingName = name
        advertisingServiceId = serviceId
        connectionCallback = callback
        advertisingOptions = options
    }

    override fun stopAdvertising() {
        isAdvertising = false
        connectionCallback = null
        stopAdvertisingCallCount++
    }

    override suspend fun requestConnection(
        name: String,
        endpointId: String,
        callback: ConnectionLifecycleCallback
    ) {
        requestConnectionFailure?.let { throw it }
        requestedConnections.add(name to endpointId)
        connectionCallback = callback
    }

    override suspend fun acceptConnection(
        endpointId: String,
        callback: PayloadCallback
    ) {
        acceptConnectionFailure?.let { throw it }
        acceptedConnections.add(endpointId)
        this.payloadCallback = callback
    }

    override suspend fun rejectConnection(
        endpointId: String
    ) {
        rejectConnectionFailure?.let { throw it }
        rejectedConnections.add(endpointId)
    }

    override suspend fun sendPayload(
        endpointId: String,
        payload: Payload
    ) {
        sendPayloadFailure?.let { throw it }
        sentPayloads.add(endpointId to payload)
    }

    override suspend fun cancelPayload(
        payloadId: Long
    ) {
        cancelPayloadFailure?.let { throw it }
        cancelledPayloads.add(payloadId)
    }

    override fun disconnectFromEndpoint(
        endpointId: String
    ) {
        disconnectedEndpoints.add(endpointId)
    }

    override fun stopAllEndpoints() {
        stopDiscovery()
        stopAdvertising()
        stopAllEndpointsCallCount++
    }

    fun simulatePayloadReceived(
        endpointId: String,
        payload: Payload
    ) {
        payloadCallback?.onPayloadReceived(endpointId, payload)
    }

    fun simulatePayloadTransferUpdate(
        endpointId: String,
        update: PayloadTransferUpdate
    ) {
        payloadCallback?.onPayloadTransferUpdate(endpointId, update)
    }

    fun simulateEndpointFound(
        endpointId: String,
        endpointName: String,
        serviceId: String = discoveryServiceId ?: NearbyConfig.SERVICE_ID_RELEASE
    ) {
        discoveryCallback?.onEndpointFound(
            endpointId,
            DiscoveredEndpointInfo(serviceId, endpointName)
        )
    }

    fun simulateEndpointLost(endpointId: String) {
        discoveryCallback?.onEndpointLost(endpointId)
    }

    fun simulateConnectionInitiated(
        endpointId: String,
        endpointName: String,
        authDigits: String,
        isIncoming: Boolean = true
    ) {
        connectionCallback?.onConnectionInitiated(
            endpointId,
            ConnectionInfo(endpointName, authDigits, isIncoming)
        )
    }

    fun simulateConnectionResult(
        endpointId: String,
        statusCode: Int = ConnectionsStatusCodes.STATUS_OK
    ) {
        connectionCallback?.onConnectionResult(
            endpointId,
            ConnectionResolution(Status(statusCode))
        )
    }

    fun simulateDisconnected(
        endpointId: String
    ) {
        connectionCallback?.onDisconnected(endpointId)
    }
}
