package dev.glide.core.ports

import kotlinx.coroutines.flow.Flow
import java.io.Closeable
import java.io.InputStream

/**
 * WebRTC peer connection state.
 */
enum class WebRtcPeerState {
    NEW,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
    CLOSED
}

/**
 * WebRTC data channel reliability mode.
 */
enum class DataChannelMode {
    /** Guaranteed delivery, in-order. Default for Glide transfers. */
    RELIABLE_ORDERED,
    /** Guaranteed delivery, any-order. */
    RELIABLE_UNORDERED,
    /** Bounded retransmits, time-limited. Not used by Glide. */
    PARTIAL_RELIABILITY
}

/**
 * Configuration for a WebRTC data channel.
 */
data class DataChannelConfig(
    val label: String,
    val mode: DataChannelMode = DataChannelMode.RELIABLE_ORDERED,
    val maxRetransmits: Int? = null,
    val maxPacketLifeTimeMs: Int? = null,
    val negotiated: Boolean = false,
    val protocol: String = "glide-webrtc/1"
) {
    fun validate() {
        require(label.isNotBlank()) { "DataChannel label must not be blank." }
        require(label.length <= 512) { "DataChannel label must be <= 512 characters." }
        if (mode == DataChannelMode.PARTIAL_RELIABILITY) {
            require(maxRetransmits != null || maxPacketLifeTimeMs != null) {
                "PARTIAL_RELIABILITY requires maxRetransmits or maxPacketLifeTimeMs."
            }
        }
    }
}

/**
 * Result of a WebRTC session bootstrap.
 * Contains all information needed by the remote peer to establish
 * a WebRTC connection and verify the DTLS fingerprint.
 */
data class WebRtcSessionInfo(
    val sessionId: String,
    val localSdpOffer: String,
    val dtlsFingerprintSha256: String,
    val signalingUrl: String,
    val expiresAtEpochMs: Long
) {
    fun validate() {
        require(sessionId.isNotBlank()) { "SessionId must not be blank." }
        require(localSdpOffer.isNotBlank()) { "LocalSdpOffer must not be blank." }
        require(dtlsFingerprintSha256.isNotBlank()) { "DtlsFingerprintSha256 must not be blank." }
        require(signalingUrl.isNotBlank()) { "SignalingUrl must not be blank." }
        require(expiresAtEpochMs > 0) { "ExpiresAtEpochMs must be positive." }
    }
}

/**
 * Incoming data channel message.
 */
sealed class DataChannelMessage {
    /** Text (JSON control) message. */
    data class Text(val content: String) : DataChannelMessage()

    /** Binary (bulk data) message. */
    data class Binary(val content: ByteArray) : DataChannelMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Binary
            return content.contentEquals(other.content)
        }

        override fun hashCode(): Int = content.contentHashCode()
    }
}

/**
 * ICE candidate information for signaling exchange.
 */
data class IceCandidateInfo(
    val candidate: String,
    val sdpMid: String,
    val sdpMLineIndex: Int
) {
    fun validate() {
        require(candidate.isNotBlank()) { "ICE candidate must not be blank." }
        require(sdpMid.isNotBlank()) { "sdpMid must not be blank." }
        require(sdpMLineIndex >= 0) { "sdpMLineIndex must be non-negative." }
    }
}

/**
 * Incoming stream from WebRTC data channel.
 * Represents a file being received from the browser client.
 */
data class IncomingWebRtcStream(
    val itemId: String,
    val fileName: String,
    val totalBytes: Long,
    val stream: InputStream
) {
    fun validate() {
        require(itemId.isNotBlank()) { "ItemId must not be blank." }
        require(fileName.isNotBlank()) { "FileName must not be blank." }
        require(totalBytes >= 0) { "TotalBytes must be non-negative." }
    }
}

/**
 * Error codes for WebRTC transport failures.
 */
enum class WebRtcErrorCode {
    SESSION_NOT_FOUND,
    DTLS_HANDSHAKE_FAILED,
    DTLS_FINGERPRINT_MISMATCH,
    DATACHANNEL_OPEN_FAILED,
    SDP_EXCHANGE_FAILED,
    ICE_NEGOTIATION_FAILED,
    SIGNALING_ERROR,
    FLOW_CONTROL_OVERFLOW,
    UNKNOWN
}

/**
 * Exception type for WebRTC transport errors.
 */
class WebRtcException(
    message: String,
    val code: WebRtcErrorCode = WebRtcErrorCode.UNKNOWN,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * WebRTC transfer transport adapter (HARD-025–030).
 * Replaces the insecure HTTP-based GlideWebGateway with a DTLS-encrypted
 * WebRTC DataChannel transport.
 */
interface WebRtcTransferTransport : Closeable {
    suspend fun createSession(
        sessionId: String,
        localDeviceName: String,
        localPlatform: String
    ): WebRtcSessionInfo

    suspend fun handleSdpAnswer(sdpAnswer: String)

    suspend fun addIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int)

    fun observePeerState(): Flow<WebRtcPeerState>

    fun observeLocalIceCandidates(): Flow<IceCandidateInfo>

    fun observeDataChannelMessages(): Flow<DataChannelMessage>

    suspend fun sendControlMessage(text: String)

    fun sendBinaryData(
        data: ByteArray,
        onProgress: ((bytesSent: Long, totalBytes: Long) -> Unit)? = null
    ): Flow<BulkProgress>

    fun sendStream(
        stream: InputStream,
        totalBytes: Long,
        onProgress: ((bytesSent: Long, totalBytes: Long) -> Unit)? = null
    ): Flow<BulkProgress>

    fun registerExpectedStream(itemId: String, expectedBytes: Long)

    fun observeIncomingStreams(): Flow<IncomingWebRtcStream>

    override fun close()
}
