package dev.glide.transport.nearby

import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.PeerId
import dev.glide.core.ports.AdvertisingEvent
import dev.glide.core.ports.BulkProgress
import dev.glide.core.ports.ConnectionEvent
import dev.glide.core.ports.ControlMessage
import dev.glide.core.ports.DiscoveryEvent
import dev.glide.core.ports.IncomingStream
import dev.glide.core.ports.InputStreamProvider
import dev.glide.core.ports.StreamExpectation
import dev.glide.core.ports.TransferTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow

/**
 * Implementation of [TransferTransport] backed by Google Nearby Connections.
 * Implements discovery, advertising, verified connection lifecycle, control messaging,
 * and bounded bulk streaming (GLIDE-010, GLIDE-011, GLIDE-014).
 */
class NearbyTransferTransport(
    private val discoveryAdapter: NearbyDiscoveryAdapter,
    private val connectionManager: NearbyConnectionManager,
    val payloadBridge: NearbyPayloadBridge
) : TransferTransport {

    constructor(
        clientWrapper: NearbyClientWrapper,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    ) : this(
        Builder(clientWrapper, scope)
    )

    private constructor(builder: Builder) : this(
        discoveryAdapter = builder.discoveryAdapter,
        connectionManager = builder.connectionManager,
        payloadBridge = builder.payloadBridge
    )

    private class Builder(
        clientWrapper: NearbyClientWrapper,
        scope: CoroutineScope
    ) {
        private var cmRef: NearbyConnectionManager? = null
        val payloadBridge = NearbyPayloadBridge(
            clientWrapper = clientWrapper,
            isPeerConnected = { peerId ->
                val state = cmRef?.linkState?.value
                state is LinkState.Connected && state.peer.id == peerId
            },
            scope = scope
        )
        val connectionManager = NearbyConnectionManager(
            clientWrapper = clientWrapper,
            scope = scope,
            customPayloadCallback = payloadBridge.payloadCallback
        ).also { cmRef = it }
        val discoveryAdapter = NearbyDiscoveryAdapter(clientWrapper)
    }

    override suspend fun startDiscovery(serviceId: String): Flow<DiscoveryEvent> {
        return discoveryAdapter.discoverPeers(serviceId = serviceId)
    }

    override suspend fun stopDiscovery() {
        discoveryAdapter.stopDiscovery()
    }

    override suspend fun startAdvertising(serviceId: String, localDeviceName: String): Flow<AdvertisingEvent> {
        return discoveryAdapter.advertiseReadiness(
            serviceId = serviceId,
            localDeviceName = localDeviceName,
            connectionCallback = connectionManager.connectionLifecycleCallback
        )
    }

    override suspend fun stopAdvertising() {
        discoveryAdapter.stopAdvertising()
    }

    override suspend fun initiateConnection(peerId: PeerId, localDeviceName: String) {
        connectionManager.initiateConnection(peerId, peerId.value, localDeviceName)
    }

    override suspend fun acceptConnection(peerId: PeerId) {
        connectionManager.acceptConnection(peerId)
    }

    override suspend fun rejectConnection(peerId: PeerId) {
        connectionManager.rejectConnection(peerId)
    }

    override fun observeConnectionEvents(): Flow<ConnectionEvent> {
        return connectionManager.connectionEvents
    }

    override suspend fun disconnect(peerId: PeerId) {
        payloadBridge.onDisconnected(peerId)
        connectionManager.disconnect(peerId)
    }

    override suspend fun sendControl(peerId: PeerId, payload: ByteArray) {
        payloadBridge.sendControl(peerId, payload)
    }

    override fun observeControl(): Flow<ControlMessage> {
        return payloadBridge.observeControl()
    }

    override suspend fun sendStream(
        peerId: PeerId,
        bulkRef: BulkRef
    ): Flow<BulkProgress> {
        return payloadBridge.sendStream(peerId, bulkRef)
    }

    override suspend fun sendStream(
        peerId: PeerId,
        bulkRef: BulkRef,
        streamSource: InputStreamProvider
    ): Flow<BulkProgress> {
        return payloadBridge.sendStream(peerId, bulkRef)
    }

    override fun observeIncomingStream(): Flow<IncomingStream> {
        return payloadBridge.observeIncomingStream()
    }

    override fun observeBulkProgress(): Flow<BulkProgress> {
        return payloadBridge.observeBulkProgress()
    }

    override suspend fun prepareStream(streamSource: InputStreamProvider): BulkRef {
        return payloadBridge.prepareStream(streamSource)
    }

    override suspend fun markItemReady(bulkRef: BulkRef) {
        payloadBridge.markItemReady(bulkRef)
    }

    override suspend fun registerExpectedStream(expectation: StreamExpectation) {
        payloadBridge.registerExpectedStream(expectation)
    }

    override suspend fun registerExpectedStream(bulkRef: BulkRef) {
        payloadBridge.registerExpectedStream(bulkRef)
    }

    override suspend fun cancelPayload(bulkRef: BulkRef) {
        payloadBridge.cancelPayload(bulkRef)
    }

    companion object {
        fun create(
            context: android.content.Context,
            scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        ): NearbyTransferTransport {
            val connectionsClient = com.google.android.gms.nearby.Nearby.getConnectionsClient(context)
            return NearbyTransferTransport(
                clientWrapper = AndroidNearbyClientWrapper(connectionsClient),
                scope = scope
            )
        }
    }
}
