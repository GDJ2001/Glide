package dev.glide.core.ports

import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.SessionId
import kotlinx.coroutines.flow.Flow
import java.io.InputStream

sealed interface DiscoveryEvent {
    data class PeerAppeared(val peer: PeerDevice) : DiscoveryEvent
    data class PeerLost(val peerId: PeerId) : DiscoveryEvent
}

/**
 * Typed cryptographic or link verification evidence (LAN-006, Section 8, Section 18).
 *
 * Distinguishes between UKEY2 Nearby SAS authentication digits and pinned TLS LAN invitations
 * without manufacturing fake digits or bypassing mutual link verification.
 */
sealed interface VerificationEvidence {
    /**
     * UKEY2 SAS 6-digit mutual authentication code from Google Nearby Connections.
     */
    data class NearbyDigits(val digits: String) : VerificationEvidence {
        init {
            require(digits.isNotBlank()) { "Nearby authentication digits must not be blank" }
        }
    }

    /**
     * Out-of-band verified LAN pairing invitation (SHA-256 SPKI pin and ephemeral token).
     */
    data class InvitationVerified(
        val invitationId: String,
        val serverPin: String
    ) : VerificationEvidence {
        init {
            require(invitationId.isNotBlank()) { "Invitation ID must not be blank" }
            require(serverPin.isNotBlank()) { "Server pin must not be blank" }
        }

        override fun toString(): String {
            val truncatedPin = if (serverPin.length > 8) "${serverPin.take(8)}..." else "[REDACTED]"
            return "InvitationVerified(invitationId='$invitationId', serverPin='$truncatedPin')"
        }
    }
}

sealed interface AdvertisingEvent {
    data class ConnectionInitiated(
        val peer: PeerDevice,
        val evidence: VerificationEvidence
    ) : AdvertisingEvent {
        constructor(peer: PeerDevice, authDigits: String) : this(peer, VerificationEvidence.NearbyDigits(authDigits))
        val authDigits: String get() = (evidence as? VerificationEvidence.NearbyDigits)?.digits ?: ""
    }
}

sealed interface ConnectionEvent {
    data class Initiated(
        val peer: PeerDevice,
        val evidence: VerificationEvidence
    ) : ConnectionEvent {
        constructor(peer: PeerDevice, authDigits: String) : this(peer, VerificationEvidence.NearbyDigits(authDigits))
        val authDigits: String get() = (evidence as? VerificationEvidence.NearbyDigits)?.digits ?: ""
    }
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

/**
 * Typed expectation for an incoming stream item (LAN-007, Section 6, Section 18).
 *
 * Registered only after an accepted offer, reducer-validated ITEM_START and
 * successful staging preparation, at the serialized ITEM_READY transition.
 */
data class StreamExpectation(
    val sessionId: SessionId,
    val itemId: ItemId,
    val bulkRef: BulkRef,
    val sizeBytes: Long
) {
    init {
        require(sizeBytes >= 0L) { "sizeBytes must be non-negative, got $sizeBytes" }
    }
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
    suspend fun registerExpectedStream(expectation: StreamExpectation) {
        registerExpectedStream(expectation.bulkRef)
    }
    suspend fun registerExpectedStream(bulkRef: BulkRef) {}

    suspend fun cancelPayload(bulkRef: BulkRef)
    suspend fun disconnect(peerId: PeerId)
}
