package dev.glide.core.ports

import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import kotlinx.coroutines.flow.Flow
import java.io.InputStream

sealed interface DiscoveryEvent {
    data class PeerAppeared(val peer: PeerDevice) : DiscoveryEvent
    data class PeerLost(val peerId: PeerId) : DiscoveryEvent
}

sealed interface AdvertisingEvent {
    data class ConnectionInitiated(val peer: PeerDevice, val authDigits: String) : AdvertisingEvent
}

sealed interface ConnectionEvent {
    data class Initiated(val peer: PeerDevice, val authDigits: String) : ConnectionEvent
    data class Connected(val peer: PeerDevice) : ConnectionEvent
    data class Rejected(val peerId: PeerId) : ConnectionEvent
    data class Disconnected(val peerId: PeerId) : ConnectionEvent
    data class Failed(val peerId: PeerId, val reason: String) : ConnectionEvent
}

data class ControlMessage(
    val peerId: PeerId,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ControlMessage
        if (peerId != other.peerId) return false
        return payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = peerId.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

data class BulkProgress(
    val bulkRef: BulkRef,
    val bytesTransferred: Long,
    val isComplete: Boolean
)

data class IncomingStream(
    val peerId: PeerId,
    val bulkRef: BulkRef,
    val stream: InputStream
)

fun interface InputStreamProvider {
    fun openStream(): InputStream
}

interface TransferTransport {
    suspend fun startDiscovery(serviceId: String): Flow<DiscoveryEvent>
    suspend fun stopDiscovery()

    suspend fun startAdvertising(serviceId: String, localDeviceName: String): Flow<AdvertisingEvent>
    suspend fun stopAdvertising()

    suspend fun initiateConnection(peerId: PeerId, localDeviceName: String)
    suspend fun acceptConnection(peerId: PeerId)
    suspend fun rejectConnection(peerId: PeerId)
    fun observeConnectionEvents(): Flow<ConnectionEvent>

    suspend fun sendControl(peerId: PeerId, payload: ByteArray)
    fun observeControl(): Flow<ControlMessage>

    suspend fun sendStream(peerId: PeerId, bulkRef: BulkRef): Flow<BulkProgress> = kotlinx.coroutines.flow.emptyFlow()
    suspend fun sendStream(peerId: PeerId, bulkRef: BulkRef, streamSource: InputStreamProvider): Flow<BulkProgress> = sendStream(peerId, bulkRef)
    fun observeIncomingStream(): Flow<IncomingStream>
    fun observeBulkProgress(): Flow<BulkProgress> = kotlinx.coroutines.flow.emptyFlow()

    suspend fun prepareStream(streamSource: InputStreamProvider): BulkRef? = null
    suspend fun markItemReady(bulkRef: BulkRef) {}
    suspend fun registerExpectedStream(bulkRef: BulkRef) {}

    suspend fun cancelPayload(bulkRef: BulkRef)
    suspend fun disconnect(peerId: PeerId)
}
