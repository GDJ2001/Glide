package dev.glide.transport.nearby

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Testable abstraction over Google Play Services ConnectionsClient.
 * Encapsulates discovery, advertising, connection, and radio lifecycle operations.
 */
interface NearbyClientWrapper {
    /**
     * Starts discovering remote endpoints running the specified service ID.
     */
    suspend fun startDiscovery(
        serviceId: String,
        callback: EndpointDiscoveryCallback,
        options: DiscoveryOptions
    )

    /**
     * Stops discovering remote endpoints and releases scanning radio resources.
     */
    fun stopDiscovery()

    /**
     * Starts advertising readiness to accept connections from discovering peers.
     */
    suspend fun startAdvertising(
        name: String,
        serviceId: String,
        callback: ConnectionLifecycleCallback,
        options: AdvertisingOptions
    )

    /**
     * Stops advertising readiness and releases advertising radio resources.
     */
    fun stopAdvertising()

    /**
     * Initiates a connection to a discovered remote endpoint.
     */
    suspend fun requestConnection(
        name: String,
        endpointId: String,
        callback: ConnectionLifecycleCallback
    )

    /**
     * Accepts a pending connection from a remote endpoint.
     */
    suspend fun acceptConnection(
        endpointId: String,
        callback: PayloadCallback
    )

    /**
     * Rejects a pending connection from a remote endpoint.
     */
    suspend fun rejectConnection(
        endpointId: String
    )

    /**
     * Sends a payload to a connected remote endpoint.
     */
    suspend fun sendPayload(
        endpointId: String,
        payload: Payload
    )

    /**
     * Cancels an active payload transfer.
     */
    suspend fun cancelPayload(
        payloadId: Long
    )

    /**
     * Disconnects from a remote endpoint and releases link resources.
     */
    fun disconnectFromEndpoint(
        endpointId: String
    )

    /**
     * Stops all active discovery, advertising, and endpoint connections.
     */
    fun stopAllEndpoints()
}

/**
 * Production implementation of [NearbyClientWrapper] backed by [ConnectionsClient].
 */
class AndroidNearbyClientWrapper(
    private val client: ConnectionsClient
) : NearbyClientWrapper {

    constructor(context: Context) : this(Nearby.getConnectionsClient(context.applicationContext))

    override suspend fun startDiscovery(
        serviceId: String,
        callback: EndpointDiscoveryCallback,
        options: DiscoveryOptions
    ) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                client.stopDiscovery()
            }
            client.startDiscovery(serviceId, callback, options)
                .addOnSuccessListener {
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
                .addOnFailureListener { exception ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(exception)
                    }
                }
        }
    }

    override fun stopDiscovery() {
        client.stopDiscovery()
    }

    override suspend fun startAdvertising(
        name: String,
        serviceId: String,
        callback: ConnectionLifecycleCallback,
        options: AdvertisingOptions
    ) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                client.stopAdvertising()
            }
            client.startAdvertising(name, serviceId, callback, options)
                .addOnSuccessListener {
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
                .addOnFailureListener { exception ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(exception)
                    }
                }
        }
    }

    override fun stopAdvertising() {
        client.stopAdvertising()
    }

    override suspend fun requestConnection(
        name: String,
        endpointId: String,
        callback: ConnectionLifecycleCallback
    ) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                client.disconnectFromEndpoint(endpointId)
            }
            client.requestConnection(name, endpointId, callback)
                .addOnSuccessListener {
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
                .addOnFailureListener { exception ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(exception)
                    }
                }
        }
    }

    override suspend fun acceptConnection(
        endpointId: String,
        callback: PayloadCallback
    ) {
        suspendCancellableCoroutine { continuation ->
            client.acceptConnection(endpointId, callback)
                .addOnSuccessListener {
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
                .addOnFailureListener { exception ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(exception)
                    }
                }
        }
    }

    override suspend fun rejectConnection(
        endpointId: String
    ) {
        suspendCancellableCoroutine { continuation ->
            client.rejectConnection(endpointId)
                .addOnSuccessListener {
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
                .addOnFailureListener { exception ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(exception)
                    }
                }
        }
    }

    override suspend fun sendPayload(
        endpointId: String,
        payload: Payload
    ) {
        suspendCancellableCoroutine { continuation ->
            client.sendPayload(endpointId, payload)
                .addOnSuccessListener {
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
                .addOnFailureListener { exception ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(exception)
                    }
                }
        }
    }

    override suspend fun cancelPayload(
        payloadId: Long
    ) {
        suspendCancellableCoroutine { continuation ->
            client.cancelPayload(payloadId)
                .addOnSuccessListener {
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
                .addOnFailureListener { exception ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(exception)
                    }
                }
        }
    }

    override fun disconnectFromEndpoint(endpointId: String) {
        client.disconnectFromEndpoint(endpointId)
    }

    override fun stopAllEndpoints() {
        client.stopAllEndpoints()
    }
}
