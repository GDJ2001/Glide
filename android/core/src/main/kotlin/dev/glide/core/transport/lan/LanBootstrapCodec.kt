package dev.glide.core.transport.lan

import dev.glide.core.domain.isValidSha256
import dev.glide.core.domain.isValidUuidV4
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.charset.StandardCharsets

/**
 * Valid failure codes catalog for LAN Profile 1 bootstrap result (LAN-007, Section 8, Section 20).
 */
enum class LanBootstrapFailureCode {
    AUTHENTICATION_FAILED,
    INVITATION_EXPIRED,
    INVITATION_USED,
    BUSY,
    UNSUPPORTED_PROFILE;

    companion object {
        fun fromString(value: String): LanBootstrapFailureCode? = entries.firstOrNull { it.name == value }
    }
}

/**
 * Client's first record after TLS verification (LAN-007, Section 8).
 * Carries no file names or paths.
 */
data class LanBootstrapMessage(
    val v: Int = 1,
    val invitationId: String,
    val token: String,
    val invitationHash: String,
    val sessionId: String,
    val role: String = ROLE_SENDER
) {
    init {
        require(v == 1) { "Unsupported bootstrap version: $v" }
        require(isValidUuidV4(invitationId)) { "invitationId must be a canonical UUIDv4, got: '$invitationId'" }
        require(TOKEN_REGEX.matches(token)) { "token must be exactly 43 base64url characters, got length ${token.length}" }
        require(isValidSha256(invitationHash)) { "invitationHash must be 64 lowercase hex characters, got: '$invitationHash'" }
        require(isValidUuidV4(sessionId)) { "sessionId must be a canonical UUIDv4, got: '$sessionId'" }
        require(role == ROLE_SENDER) { "role must be '$ROLE_SENDER', got: '$role'" }
    }

    override fun toString(): String {
        return "LanBootstrapMessage(v=$v, invitationId=$invitationId, token=[REDACTED], invitationHash=$invitationHash, sessionId=$sessionId, role=$role)"
    }

    companion object {
        const val ROLE_SENDER = "sender"
        private val TOKEN_REGEX = Regex("^[A-Za-z0-9_-]{43}$")
    }
}

/**
 * Server's one bootstrap response (LAN-007, Section 8).
 */
data class LanBootstrapResult(
    val v: Int = 1,
    val status: String,
    val sessionId: String? = null,
    val code: String? = null
) {
    init {
        require(v == 1) { "Unsupported bootstrap result version: $v" }
        when (status) {
            STATUS_ACCEPTED -> {
                requireNotNull(sessionId) { "accepted status requires non-null sessionId" }
                require(isValidUuidV4(sessionId)) { "sessionId must be a canonical UUIDv4, got: '$sessionId'" }
                require(code == null) { "accepted status must not have code" }
            }
            STATUS_REJECTED -> {
                requireNotNull(code) { "rejected status requires non-null code" }
                require(LanBootstrapFailureCode.fromString(code) != null) {
                    "Invalid rejection code '$code', must be one of ${LanBootstrapFailureCode.entries.map { it.name }}"
                }
            }
            else -> throw IllegalArgumentException("status must be '$STATUS_ACCEPTED' or '$STATUS_REJECTED', got: '$status'")
        }
    }

    val isAccepted: Boolean get() = status == STATUS_ACCEPTED

    companion object {
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_REJECTED = "rejected"

        fun accepted(sessionId: String): LanBootstrapResult =
            LanBootstrapResult(v = 1, status = STATUS_ACCEPTED, sessionId = sessionId)

        fun rejected(code: LanBootstrapFailureCode): LanBootstrapResult =
            LanBootstrapResult(v = 1, status = STATUS_REJECTED, code = code.name)
    }
}

/**
 * Strict canonical JSON codec for LAN Profile 1 BOOTSTRAP and BOOTSTRAP_RESULT messages.
 * Enforces depth <= 4, strict field bounds, no unknown fields, no duplicate keys, and bounds checking.
 */
object LanBootstrapCodec {
    const val MAX_BOOTSTRAP_JSON_BYTES = 2048
    const val MAX_RESULT_JSON_BYTES = 512
    const val MAX_JSON_DEPTH = 4

    private val STRICT_JSON = Json {
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
        prettyPrint = false
    }

    private val ALLOWED_BOOTSTRAP_KEYS = setOf("v", "invitationId", "token", "invitationHash", "sessionId", "role")
    private val ALLOWED_RESULT_KEYS = setOf("v", "status", "sessionId", "code")

    fun encodeBootstrap(msg: LanBootstrapMessage): ByteArray {
        val jsonString = buildString {
            append("{\"v\":").append(msg.v)
            append(",\"invitationId\":\"").append(msg.invitationId).append("\"")
            append(",\"token\":\"").append(msg.token).append("\"")
            append(",\"invitationHash\":\"").append(msg.invitationHash).append("\"")
            append(",\"sessionId\":\"").append(msg.sessionId).append("\"")
            append(",\"role\":\"").append(msg.role).append("\"}")
        }
        val bytes = jsonString.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_BOOTSTRAP_JSON_BYTES) {
            "Encoded bootstrap JSON exceeds $MAX_BOOTSTRAP_JSON_BYTES bytes: ${bytes.size}"
        }
        return bytes
    }

    fun decodeBootstrap(bytes: ByteArray): LanBootstrapMessage {
        require(bytes.isNotEmpty()) { "Bootstrap JSON payload must not be empty" }
        require(bytes.size <= MAX_BOOTSTRAP_JSON_BYTES) {
            "Bootstrap JSON payload exceeds $MAX_BOOTSTRAP_JSON_BYTES bytes: ${bytes.size}"
        }

        val jsonString = String(bytes, StandardCharsets.UTF_8)
        val root = parseAndValidateObject(jsonString, ALLOWED_BOOTSTRAP_KEYS)

        val v = (root["v"] as? JsonPrimitive)?.intOrNull
            ?: throw IllegalArgumentException("Missing or invalid 'v' in bootstrap JSON")
        require(v == 1) { "Unsupported bootstrap version $v" }

        val invitationId = (root["invitationId"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("Missing or invalid 'invitationId' in bootstrap JSON")

        val token = (root["token"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("Missing or invalid 'token' in bootstrap JSON")

        val invitationHash = (root["invitationHash"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("Missing or invalid 'invitationHash' in bootstrap JSON")

        val sessionId = (root["sessionId"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("Missing or invalid 'sessionId' in bootstrap JSON")

        val role = (root["role"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("Missing or invalid 'role' in bootstrap JSON")

        return LanBootstrapMessage(
            v = v,
            invitationId = invitationId,
            token = token,
            invitationHash = invitationHash,
            sessionId = sessionId,
            role = role
        )
    }

    fun encodeResult(result: LanBootstrapResult): ByteArray {
        val jsonString = buildString {
            append("{\"v\":").append(result.v)
            append(",\"status\":\"").append(result.status).append("\"")
            if (result.sessionId != null) {
                append(",\"sessionId\":\"").append(result.sessionId).append("\"")
            }
            if (result.code != null) {
                append(",\"code\":\"").append(result.code).append("\"")
            }
            append("}")
        }
        val bytes = jsonString.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_RESULT_JSON_BYTES) {
            "Encoded bootstrap result JSON exceeds $MAX_RESULT_JSON_BYTES bytes: ${bytes.size}"
        }
        return bytes
    }

    fun decodeResult(bytes: ByteArray): LanBootstrapResult {
        require(bytes.isNotEmpty()) { "Bootstrap result JSON payload must not be empty" }
        require(bytes.size <= MAX_RESULT_JSON_BYTES) {
            "Bootstrap result JSON payload exceeds $MAX_RESULT_JSON_BYTES bytes: ${bytes.size}"
        }

        val jsonString = String(bytes, StandardCharsets.UTF_8)
        val root = parseAndValidateObject(jsonString, ALLOWED_RESULT_KEYS)

        val v = (root["v"] as? JsonPrimitive)?.intOrNull
            ?: throw IllegalArgumentException("Missing or invalid 'v' in bootstrap result JSON")
        require(v == 1) { "Unsupported bootstrap result version $v" }

        val status = (root["status"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("Missing or invalid 'status' in bootstrap result JSON")

        val sessionId = (root["sessionId"] as? JsonPrimitive)?.content
        val code = (root["code"] as? JsonPrimitive)?.content

        return LanBootstrapResult(
            v = v,
            status = status,
            sessionId = sessionId,
            code = code
        )
    }

    private fun parseAndValidateObject(jsonString: String, allowedKeys: Set<String>): JsonObject {
        val element = try {
            STRICT_JSON.parseToJsonElement(jsonString)
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed JSON in LAN bootstrap message: ${e.message}", e)
        }

        if (element !is JsonObject) {
            throw IllegalArgumentException("LAN bootstrap payload must be a JSON object, got: ${element::class.simpleName}")
        }

        validateDepth(element, currentDepth = 1)

        val unknownKeys = element.keys - allowedKeys
        require(unknownKeys.isEmpty()) {
            "Unknown keys in bootstrap message: $unknownKeys"
        }

        return element
    }

    private fun validateDepth(element: JsonElement, currentDepth: Int) {
        require(currentDepth <= MAX_JSON_DEPTH) {
            "JSON nesting depth exceeds maximum limit of $MAX_JSON_DEPTH (at depth $currentDepth)"
        }
        when (element) {
            is JsonObject -> {
                for ((_, child) in element) {
                    validateDepth(child, currentDepth + 1)
                }
            }
            is kotlinx.serialization.json.JsonArray -> {
                for (child in element) {
                    validateDepth(child, currentDepth + 1)
                }
            }
            else -> {}
        }
    }
}
