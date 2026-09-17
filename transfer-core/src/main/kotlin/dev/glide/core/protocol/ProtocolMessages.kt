package dev.glide.core.protocol

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.MessageId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.TransferFailure
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

class ProtocolViolationException(
    val failure: TransferFailure,
    message: String = failure.message,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)

object MessageTypes {
    const val HELLO = "HELLO"
    const val HELLO_ACK = "HELLO_ACK"
    const val OFFER_BEGIN = "OFFER_BEGIN"
    const val OFFER_PAGE = "OFFER_PAGE"
    const val OFFER_END = "OFFER_END"
    const val OFFER_DECISION = "OFFER_DECISION"
    const val ITEM_START = "ITEM_START"
    const val ITEM_READY = "ITEM_READY"
    const val ITEM_END = "ITEM_END"
    const val ITEM_RESULT = "ITEM_RESULT"
    const val BATCH_END = "BATCH_END"
    const val BATCH_RESULT = "BATCH_RESULT"
    const val CANCEL = "CANCEL"
    const val CANCEL_ACK = "CANCEL_ACK"
    const val ERROR = "ERROR"
    const val PING = "PING"
    const val PONG = "PONG"
    const val IDENTITY_CHALLENGE = "IDENTITY_CHALLENGE"
    const val IDENTITY_RESPONSE = "IDENTITY_RESPONSE"
    const val RESUME_REQUEST = "RESUME_REQUEST"
    const val RESUME_ACCEPT = "RESUME_ACCEPT"
    const val RESUME_REJECT = "RESUME_REJECT"

    val ALL: Set<String> = setOf(
        HELLO, HELLO_ACK,
        OFFER_BEGIN, OFFER_PAGE, OFFER_END, OFFER_DECISION,
        ITEM_START, ITEM_READY, ITEM_END, ITEM_RESULT,
        BATCH_END, BATCH_RESULT,
        CANCEL, CANCEL_ACK,
        ERROR, PING, PONG,
        IDENTITY_CHALLENGE, IDENTITY_RESPONSE,
        RESUME_REQUEST, RESUME_ACCEPT, RESUME_REJECT
    )
}

object WireErrorCodes {
    const val UNSUPPORTED_PROTOCOL = "UNSUPPORTED_PROTOCOL"
    const val MALFORMED_MESSAGE = "MALFORMED_MESSAGE"
    const val PROTOCOL_ORDER = "PROTOCOL_ORDER"
    const val AUTHENTICATION_FAILED = "AUTHENTICATION_FAILED"
    const val USER_REJECTED = "USER_REJECTED"
    const val USER_CANCELLED = "USER_CANCELLED"
    const val SYSTEM_CANCELLED = "SYSTEM_CANCELLED"
    const val SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE"
    const val READ_FAILURE = "READ_FAILURE"
    const val DESTINATION_UNAVAILABLE = "DESTINATION_UNAVAILABLE"
    const val WRITE_FAILURE = "WRITE_FAILURE"
    const val INSUFFICIENT_STORAGE = "INSUFFICIENT_STORAGE"
    const val TRANSFER_INTERRUPTED = "TRANSFER_INTERRUPTED"
    const val TIMEOUT = "TIMEOUT"
    const val PEER_LOST = "PEER_LOST"
    const val INTEGRITY_MISMATCH = "INTEGRITY_MISMATCH"
    const val LIMIT_EXCEEDED = "LIMIT_EXCEEDED"
    const val BUSY = "BUSY"

    val ALL: Set<String> = setOf(
        UNSUPPORTED_PROTOCOL,
        MALFORMED_MESSAGE,
        PROTOCOL_ORDER,
        AUTHENTICATION_FAILED,
        USER_REJECTED,
        USER_CANCELLED,
        SYSTEM_CANCELLED,
        SOURCE_UNAVAILABLE,
        READ_FAILURE,
        DESTINATION_UNAVAILABLE,
        WRITE_FAILURE,
        INSUFFICIENT_STORAGE,
        TRANSFER_INTERRUPTED,
        TIMEOUT,
        PEER_LOST,
        INTEGRITY_MISMATCH,
        LIMIT_EXCEEDED,
        BUSY,
    )
}

object ProtocolCatalog {
    val ALLOWED_ENVELOPE_KEYS = setOf("v", "type", "sessionId", "messageId", "seq", "body", "extensions")

    val ALLOWED_BODY_KEYS: Map<String, Set<String>> = mapOf(
        MessageTypes.HELLO to setOf("supportedMajors", "requiredCapabilities", "optionalCapabilities", "role", "ephemeralPeerId", "displayName"),
        MessageTypes.HELLO_ACK to setOf("selectedMajor", "capabilities", "ephemeralPeerId", "displayName", "limits"),
        MessageTypes.OFFER_BEGIN to setOf("batchId", "itemCount", "totalBytes", "pageCount"),
        MessageTypes.OFFER_PAGE to setOf("batchId", "pageIndex", "items"),
        MessageTypes.OFFER_END to setOf("batchId"),
        MessageTypes.OFFER_DECISION to setOf("batchId", "accepted", "code"),
        MessageTypes.ITEM_START to setOf("batchId", "itemId", "bulkRef", "sizeBytes"),
        MessageTypes.ITEM_READY to setOf("batchId", "itemId"),
        MessageTypes.ITEM_END to setOf("batchId", "itemId", "actualBytes", "sha256"),
        MessageTypes.ITEM_RESULT to setOf("batchId", "itemId", "status", "sizeBytes", "sha256", "code"),
        MessageTypes.BATCH_END to setOf("batchId"),
        MessageTypes.BATCH_RESULT to setOf("batchId", "status", "verifiedCount", "totalVerifiedBytes"),
        MessageTypes.CANCEL to setOf("batchId", "code"),
        MessageTypes.CANCEL_ACK to setOf("replyTo"),
        MessageTypes.ERROR to setOf("code", "batchId", "itemId", "detail"),
        MessageTypes.PING to setOf("nonce"),
        MessageTypes.PONG to setOf("nonce"),
        MessageTypes.IDENTITY_CHALLENGE to setOf("nonceHex"),
        MessageTypes.IDENTITY_RESPONSE to setOf("signatureHex", "publicKeyDerHex"),
        MessageTypes.RESUME_REQUEST to setOf("sessionId", "itemId", "verifiedChunkCount", "contiguousByteOffset", "chunkRootHashHex", "sessionNonceHex", "signatureHex"),
        MessageTypes.RESUME_ACCEPT to setOf("sessionId", "itemId", "startChunkIndex", "startByteOffset"),
        MessageTypes.RESUME_REJECT to setOf("sessionId", "itemId", "reason"),
    )

    val ALLOWED_LIMITS_KEYS = setOf("maxItems", "maxItemBytes", "maxBatchBytes", "maxControlBytes")
    val ALLOWED_ITEM_KEYS = setOf("itemId", "name", "mime", "sizeBytes", "relativePath", "rootHash", "chunkSize")
}

@Serializable
data class Envelope(
    val v: Int,
    val type: String,
    val sessionId: SessionId,
    val messageId: MessageId,
    val seq: Long,
    val body: JsonObject,
    val extensions: JsonObject? = null
)

@Serializable
data class HelloBody(
    val supportedMajors: List<Int>,
    val requiredCapabilities: List<String>,
    val optionalCapabilities: List<String> = emptyList(),
    val role: String,
    val ephemeralPeerId: String,
    val displayName: String
)

@Serializable
data class LimitsDto(
    val maxItems: Int,
    val maxItemBytes: String,
    val maxBatchBytes: String,
    val maxControlBytes: Int
)

@Serializable
data class HelloAckBody(
    val selectedMajor: Int,
    val capabilities: List<String>,
    val ephemeralPeerId: String,
    val displayName: String,
    val limits: LimitsDto
)

@Serializable
data class OfferBeginBody(
    val batchId: BatchId,
    val itemCount: Int,
    val totalBytes: String,
    val pageCount: Int
)

@Serializable
data class OfferItemDto(
    val itemId: ItemId,
    val name: String,
    val mime: String,
    val sizeBytes: String,
    val relativePath: String? = null
)

@Serializable
data class OfferPageBody(
    val batchId: BatchId,
    val pageIndex: Int,
    val items: List<OfferItemDto>
)

@Serializable
data class OfferEndBody(
    val batchId: BatchId
)

@Serializable
data class OfferDecisionBody(
    val batchId: BatchId,
    val accepted: Boolean,
    val code: String? = null
)

@Serializable
data class ItemStartBody(
    val batchId: BatchId,
    val itemId: ItemId,
    val bulkRef: BulkRef,
    val sizeBytes: String
)

@Serializable
data class ItemReadyBody(
    val batchId: BatchId,
    val itemId: ItemId
)

@Serializable
data class ItemEndBody(
    val batchId: BatchId,
    val itemId: ItemId,
    val actualBytes: String,
    val sha256: Sha256Digest
)

@Serializable
data class ItemResultBody(
    val batchId: BatchId,
    val itemId: ItemId,
    val status: String,
    val sizeBytes: String? = null,
    val sha256: Sha256Digest? = null,
    val code: String? = null
)

@Serializable
data class BatchEndBody(
    val batchId: BatchId
)

@Serializable
data class BatchResultBody(
    val batchId: BatchId,
    val status: String,
    val verifiedCount: Int,
    val totalVerifiedBytes: String
)

@Serializable
data class CancelBody(
    val batchId: BatchId? = null,
    val code: String
)

@Serializable
data class CancelAckBody(
    val replyTo: MessageId
)

@Serializable
data class ErrorBody(
    val code: String,
    val batchId: BatchId? = null,
    val itemId: ItemId? = null,
    val detail: String
)

@Serializable
data class PingBody(
    val nonce: String
)

@Serializable
data class PongBody(
    val nonce: String
)

@Serializable
data class IdentityChallengeDto(
    val nonceHex: String
) {
    init {
        require(nonceHex.length == 64) { "Nonce must be 64 hex characters (32 bytes)" }
    }
}

@Serializable
data class IdentityResponseDto(
    val signatureHex: String,
    val publicKeyDerHex: String
) {
    init {
        require(signatureHex.isNotBlank()) { "Signature cannot be blank" }
        require(publicKeyDerHex.isNotBlank()) { "Public key DER cannot be blank" }
    }
}

@Serializable
data class ResumeRequestDto(
    val sessionId: String,
    val itemId: String,
    val verifiedChunkCount: Long,
    val contiguousByteOffset: Long,
    val chunkRootHashHex: String,
    val sessionNonceHex: String,
    val signatureHex: String
) {
    init {
        require(sessionId.isNotBlank()) { "Session ID cannot be blank" }
        require(itemId.isNotBlank()) { "Item ID cannot be blank" }
        require(verifiedChunkCount >= 0) { "Verified chunk count cannot be negative" }
        require(contiguousByteOffset >= 0) { "Contiguous byte offset cannot be negative" }
        require(chunkRootHashHex.length == 64) { "Root hash must be 64 hex characters" }
        require(sessionNonceHex.length == 64) { "Session nonce must be 64 hex characters" }
        require(signatureHex.isNotBlank()) { "Signature cannot be blank" }
    }
}

@Serializable
data class ResumeAcceptDto(
    val sessionId: String,
    val itemId: String,
    val startChunkIndex: Long,
    val startByteOffset: Long
) {
    init {
        require(sessionId.isNotBlank()) { "Session ID cannot be blank" }
        require(itemId.isNotBlank()) { "Item ID cannot be blank" }
        require(startChunkIndex >= 0) { "Start chunk index cannot be negative" }
        require(startByteOffset >= 0) { "Start byte offset cannot be negative" }
    }
}

@Serializable
data class ResumeRejectDto(
    val sessionId: String,
    val itemId: String,
    val reason: String
) {
    init {
        require(sessionId.isNotBlank()) { "Session ID cannot be blank" }
        require(itemId.isNotBlank()) { "Item ID cannot be blank" }
        require(reason.isNotBlank() && reason.length <= 256) { "Reason must be 1..256 characters" }
    }
}
