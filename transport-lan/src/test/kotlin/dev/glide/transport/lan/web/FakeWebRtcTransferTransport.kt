package dev.glide.transport.lan.web

import dev.glide.core.ports.DataChannelMessage
import dev.glide.core.ports.IncomingWebRtcStream
import dev.glide.core.ports.WebRtcException
import dev.glide.core.ports.WebRtcPeerState
import dev.glide.core.ports.WebRtcSessionInfo
import dev.glide.core.ports.WebRtcTransferTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory fake WebRTC transfer transport for security regression tests.
 *
 * Simulates the secure WebRTC DataChannel transport specified in HARD-025..030.
 * This fake mimics the behavior of a real WebRTC peer connection without requiring
 * platform-native WebRTC libraries.
 *
 * Security invariants enforced:
 * - DTLS 1.2+ encryption (simulated: no plaintext channel exists)
 * - DTLS fingerprint verification (simulated via [WebRtcSessionInfo.dtlsFingerprintSha256])
 * - Ephemeral, single-session DTLS credentials (new fingerprint per session)
 * - Session expiry (TTL-based, default 120s)
 * - UploadId binding (uploads require accepted proposal)
 * - Mandatory SHA-256 digest verification
 * - Single-use upload IDs (consumed exactly once)
 */
class FakeWebRtcTransferTransport(
    private val ttlSeconds: Long = 120,
    private val secureRandom: SecureRandom = SecureRandom()
) : WebRtcTransferTransport {

    private val sessions = ConcurrentHashMap<String, SessionState>()
    private val peerStateFlow = MutableSharedFlow<WebRtcPeerState>(replay = 16)
    private val localIceCandidates = MutableSharedFlow<dev.glide.core.ports.IceCandidateInfo>(replay = 64)
    private val dataChannelMessages = MutableSharedFlow<DataChannelMessage>(replay = 128)
    private val incomingStreams = MutableSharedFlow<IncomingWebRtcStream>(replay = 16)

    data class SessionState(
        val info: WebRtcSessionInfo,
        val createdAtEpochMs: Long = System.currentTimeMillis(),
        val proposals = ConcurrentHashMap<String, ProposalState>(),
        val completedUploads = mutableSetOf<String>()
    )

    data class ProposalState(
        val uploadId: String,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val sha256: String,
        var consumed: Boolean = false
    )

    override suspend fun createSession(
        sessionId: String,
        localDeviceName: String,
        localPlatform: String
    ): WebRtcSessionInfo {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val fingerprintBytes = ByteArray(32).apply { secureRandom.nextBytes(this) }
        val fingerprint = fingerprintBytes.joinToString("") { "%02x".format(it) }
        val expiresAt = System.currentTimeMillis() + ttlSeconds * 1000

        val info = WebRtcSessionInfo(
            sessionId = sessionId,
            localSdpOffer = "v=0\r\no=- ${sessionId} 1 IN IP4 127.0.0.1\r\n...",
            dtlsFingerprintSha256 = fingerprint,
            signalingUrl = "ws://127.0.0.1:0/signal?session=$sessionId",
            expiresAtEpochMs = expiresAt
        )
        sessions[sessionId] = SessionState(info)
        return info
    }

    override suspend fun handleSdpAnswer(sdpAnswer: String) {
        // Simulate DTLS handshake completing
        peerStateFlow.emit(WebRtcPeerState.CONNECTING)
        peerStateFlow.emit(WebRtcPeerState.CONNECTED)
    }

    override suspend fun addIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        // In a real implementation, this adds remote ICE candidates
    }

    override fun observePeerState(): Flow<WebRtcPeerState> = flow {
        // Emit the connected state for newly created sessions
        emit(WebRtcPeerState.CONNECTED)
    }

    override fun observeLocalIceCandidates(): Flow<dev.glide.core.ports.IceCandidateInfo> = flow {
        // Emit a host candidate
        emit(dev.glide.core.ports.IceCandidateInfo(
            candidate = "candidate:1 1 UDP 2130706431 192.168.1.145 42354 typ host",
            sdpMid = "0",
            sdpMLineIndex = 0
        ))
    }

    override fun observeDataChannelMessages(): Flow<DataChannelMessage> = flow {
        // Yield control — messages come from proposal/upload methods
    }

    override suspend fun sendControlMessage(text: String) {
        // In a real impl, this sends over DataChannel
    }

    override fun sendBinaryData(
        data: ByteArray,
        onProgress: ((bytesSent: Long, totalBytes: Long) -> Unit)?
    ): Flow<dev.glide.core.ports.BulkProgress> = flow {
        val total = data.size.toLong()
        onProgress?.invoke(total, total)
        emit(dev.glide.core.ports.BulkProgress(
            bulkRef = dev.glide.core.domain.BulkRef(UUID.randomUUID().toString().take(64)),
            bytesTransferred = total,
            isComplete = true
        ))
    }

    override fun sendStream(
        stream: InputStream,
        totalBytes: Long,
        onProgress: ((bytesSent: Long, totalBytes: Long) -> Unit)?
    ): Flow<dev.glide.core.ports.BulkProgress> = flow {
        onProgress?.invoke(totalBytes, totalBytes)
        emit(dev.glide.core.ports.BulkProgress(
            bulkRef = dev.glide.core.domain.BulkRef(UUID.randomUUID().toString().take(64)),
            bytesTransferred = totalBytes,
            isComplete = true
        ))
    }

    override fun registerExpectedStream(itemId: String, expectedBytes: Long) {
        // In a real impl, this prepares to receive a stream
    }

    override fun observeIncomingStreams(): Flow<IncomingWebRtcStream> = flow {
        // Streams are observed via the upload flow
    }

    override fun close() {
        sessions.clear()
    }

    // --- Test helpers to simulate browser-side behavior ---

    /**
     * Simulate the browser proposing an upload. Returns the upload ID if accepted,
     * throws WebRtcException if session expired or storage insufficient.
     */
    fun proposeUpload(
        sessionId: String,
        fileName: String,
        fileSize: Long,
        mimeType: String = "application/octet-stream",
        sha256: String? = null
    ): String {
        val session = sessions[sessionId]
            ?: throw WebRtcException("Session not found", code = dev.glide.core.ports.WebRtcErrorCode.SESSION_NOT_FOUND)
        if (System.currentTimeMillis() > session.info.expiresAtEpochMs) {
            throw WebRtcException("Session expired", code = dev.glide.core.ports.WebRtcErrorCode.SESSION_NOT_FOUND)
        }

        val uploadId = UUID.randomUUID().toString()
        val digest = sha256 ?: MessageDigest.getInstance("SHA-256").digest(ByteArray(0))
            .joinToString("") { "%02x".format(it) }

        session.proposals[uploadId] = ProposalState(
            uploadId = uploadId,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            sha256 = digest
        )
        return uploadId
    }

    /**
     * Simulate the browser uploading data.
     * Enforces: uploadId must match an accepted proposal, SHA-256 must match,
     * single-use upload ID.
     */
    fun upload(
        sessionId: String,
        uploadId: String,
        fileName: String,
        payload: ByteArray,
        declaredDigest: String? = null
    ): UploadResult {
        val session = sessions[sessionId]
            ?: return UploadResult(error = "Session not found", success = false)
        if (System.currentTimeMillis() > session.info.expiresAtEpochMs) {
            return UploadResult(error = "Session expired", success = false)
        }

        val proposal = session.proposals[uploadId]
            ?: return UploadResult(error = "UploadId not bound to an accepted proposal", success = false)

        if (proposal.consumed) {
            return UploadResult(error = "UploadId already consumed (single-use)", success = false)
        }

        // Mandatory digest check — no declared digest means rejection
        val digest = declaredDigest
            ?: return UploadResult(error = "SHA-256 digest is required", success = false)

        // Verify the actual digest matches the declared one
        val actualDigest = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }
        if (!MessageDigest.isEqual(digest.toByteArray(), actualDigest.toByteArray())) {
            return UploadResult(error = "SHA-256 checksum mismatch", success = false)
        }

        // Consume the uploadId (single-use)
        proposal.consumed = true
        return UploadResult(success = true, fileName = fileName, sha256 = digest)
    }

    data class UploadResult(
        val success: Boolean,
        val fileName: String? = null,
        val sha256: String? = null,
        val error: String? = null
    )
}
