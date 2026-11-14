package dev.glide.core.pairing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import dev.glide.core.protocol.StrictJsonParser
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Profile2Constants {
    // The invitation URI is v2; the Profile 2 control envelope itself is wire v1.
    const val WIRE_VERSION = 1
    const val PURPOSE_PAIR_ONLY = "PAIR_ONLY"
    const val PURPOSE_TRUSTED_SEND = "TRUSTED_SEND"

    const val AUTH_METHOD_SPAKE2_PIN_V1 = "spake2-pin-v1"
    const val AUTH_METHOD_QR_TOKEN_V2 = "qr-token-v2"
    const val AUTH_METHOD_IDENTITY_V1 = "identity-v1"

    const val ROLE_INITIATOR = "initiator"
    const val ROLE_RESPONDER = "responder"

    const val TYPE_SESSION_OPEN = "SESSION_OPEN"
    const val TYPE_SESSION_OPEN_ACK = "SESSION_OPEN_ACK"
    const val TYPE_PAIR_MSG = "PAIR_MSG"
    const val TYPE_PAIR_CONFIRM = "PAIR_CONFIRM"
    const val TYPE_PAIR_APPROVAL = "PAIR_APPROVAL"
    const val TYPE_PAIR_RESULT = "PAIR_RESULT"

    const val ERROR_BUSY = "BUSY"
    const val ERROR_EXPIRED = "EXPIRED"
    const val ERROR_STALE_PRESENCE = "STALE_PRESENCE"
    const val ERROR_AUTH_FAILED = "AUTH_FAILED"
    const val ERROR_DECLINED = "DECLINED"
    const val ERROR_IDENTITY_CHANGED = "IDENTITY_CHANGED"
    const val ERROR_UNTRUSTED_PEER = "UNTRUSTED_PEER"
    const val ERROR_UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
    const val ERROR_PROTOCOL_ORDER = "PROTOCOL_ORDER"

    const val MAX_CONTROL_PAYLOAD_BYTES = 16 * 1024
    val GLD2_MAGIC_BYTES: ByteArray = "GLD2".toByteArray(StandardCharsets.US_ASCII)
}

sealed interface Profile2Message {
    data class SessionOpen(
        val sessionId: String,
        val purpose: String,
        val authMethod: String,
        val initiatorNonce: String,
        val initiatorDeviceName: String,
        val initiatorPlatform: String,
        val initiatorIdentityPublicKey: String,
        val expectedPresenceId: String? = null,
        val expectedPeerIdentityFingerprint: String? = null,
        val invitationId: String? = null
    ) : Profile2Message

    data class SessionOpenAck(
        val sessionId: String,
        val accepted: Boolean,
        val errorCode: String? = null,
        val message: String? = null,
        val responderNonce: String? = null,
        val responderDeviceName: String? = null,
        val responderPlatform: String? = null,
        val responderIdentityPublicKey: String? = null,
        val advertisedPresenceId: String? = null
    ) : Profile2Message

    data class PairMsg(
        val sessionId: String,
        val role: String,
        val spakePublicValue: String? = null,
        val invitationHash: String? = null
    ) : Profile2Message

    data class PairConfirm(
        val sessionId: String,
        val role: String,
        val keyConfirmationMac: String,
        val identitySignature: String
    ) : Profile2Message

    data class PairApproval(
        val sessionId: String,
        val role: String,
        val approved: Boolean,
        val reason: String? = null
    ) : Profile2Message

    data class PairResult(
        val sessionId: String,
        val paired: Boolean,
        val errorCode: String? = null,
        val message: String? = null
    ) : Profile2Message
}

object Profile2WireCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        prettyPrint = false
    }

    fun encode(message: Profile2Message): ByteArray {
        val obj = buildJsonObject {
            put("v", Profile2Constants.WIRE_VERSION)
            when (message) {
                is Profile2Message.SessionOpen -> {
                    put("type", Profile2Constants.TYPE_SESSION_OPEN)
                    put("sessionId", message.sessionId)
                    put("purpose", message.purpose)
                    put("authMethod", message.authMethod)
                    put("initiatorNonce", message.initiatorNonce)
                    put("initiatorDeviceName", message.initiatorDeviceName)
                    put("initiatorPlatform", message.initiatorPlatform)
                    put("initiatorIdentityPublicKey", message.initiatorIdentityPublicKey)
                    if (!message.expectedPresenceId.isNullOrEmpty()) {
                        put("expectedPresenceId", message.expectedPresenceId)
                    }
                    if (!message.expectedPeerIdentityFingerprint.isNullOrEmpty()) {
                        put("expectedPeerIdentityFingerprint", message.expectedPeerIdentityFingerprint)
                    }
                    if (!message.invitationId.isNullOrEmpty()) {
                        put("invitationId", message.invitationId)
                    }
                }
                is Profile2Message.SessionOpenAck -> {
                    put("type", Profile2Constants.TYPE_SESSION_OPEN_ACK)
                    put("sessionId", message.sessionId)
                    put("accepted", message.accepted)
                    if (!message.errorCode.isNullOrEmpty()) put("errorCode", message.errorCode)
                    if (!message.message.isNullOrEmpty()) put("message", message.message)
                    if (!message.responderNonce.isNullOrEmpty()) put("responderNonce", message.responderNonce)
                    if (!message.responderDeviceName.isNullOrEmpty()) put("responderDeviceName", message.responderDeviceName)
                    if (!message.responderPlatform.isNullOrEmpty()) put("responderPlatform", message.responderPlatform)
                    if (!message.responderIdentityPublicKey.isNullOrEmpty()) {
                        put("responderIdentityPublicKey", message.responderIdentityPublicKey)
                    }
                    if (!message.advertisedPresenceId.isNullOrEmpty()) {
                        put("advertisedPresenceId", message.advertisedPresenceId)
                    }
                }
                is Profile2Message.PairMsg -> {
                    put("type", Profile2Constants.TYPE_PAIR_MSG)
                    put("sessionId", message.sessionId)
                    put("role", message.role)
                    if (!message.spakePublicValue.isNullOrEmpty()) {
                        put("spakePublicValue", message.spakePublicValue)
                    }
                    if (!message.invitationHash.isNullOrEmpty()) {
                        put("invitationHash", message.invitationHash)
                    }
                }
                is Profile2Message.PairConfirm -> {
                    put("type", Profile2Constants.TYPE_PAIR_CONFIRM)
                    put("sessionId", message.sessionId)
                    put("role", message.role)
                    put("keyConfirmationMac", message.keyConfirmationMac)
                    put("identitySignature", message.identitySignature)
                }
                is Profile2Message.PairApproval -> {
                    put("type", Profile2Constants.TYPE_PAIR_APPROVAL)
                    put("sessionId", message.sessionId)
                    put("role", message.role)
                    put("approved", message.approved)
                    if (!message.reason.isNullOrEmpty()) put("reason", message.reason)
                }
                is Profile2Message.PairResult -> {
                    put("type", Profile2Constants.TYPE_PAIR_RESULT)
                    put("sessionId", message.sessionId)
                    put("paired", message.paired)
                    if (!message.errorCode.isNullOrEmpty()) put("errorCode", message.errorCode)
                    if (!message.message.isNullOrEmpty()) put("message", message.message)
                }
            }
        }
        val bytes = json.encodeToString(JsonObject.serializer(), obj).toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= Profile2Constants.MAX_CONTROL_PAYLOAD_BYTES) {
            "Profile 2 control payload size ${bytes.size} exceeds ${Profile2Constants.MAX_CONTROL_PAYLOAD_BYTES}"
        }
        return bytes
    }

    fun decode(utf8Json: ByteArray): Profile2Message {
        require(utf8Json.isNotEmpty() && utf8Json.size <= Profile2Constants.MAX_CONTROL_PAYLOAD_BYTES) {
            "Profile 2 control message size ${utf8Json.size} is out of bounds"
        }
        val text = String(utf8Json, StandardCharsets.UTF_8)
        val obj = StrictJsonParser(text, maxDepth = 4).parse()

        val v = obj["v"]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("Missing 'v' field")
        require(v == Profile2Constants.WIRE_VERSION) {
            "Unsupported Profile 2 wire version $v"
        }

        val type = obj.reqString("type")
        val sessionId = obj.reqString("sessionId")
        val allowedFields = when (type) {
            Profile2Constants.TYPE_SESSION_OPEN -> setOf("v", "type", "sessionId", "purpose", "authMethod", "initiatorNonce", "initiatorDeviceName", "initiatorPlatform", "initiatorIdentityPublicKey", "expectedPresenceId", "expectedPeerIdentityFingerprint", "invitationId")
            Profile2Constants.TYPE_SESSION_OPEN_ACK -> setOf("v", "type", "sessionId", "accepted", "errorCode", "message", "responderNonce", "responderDeviceName", "responderPlatform", "responderIdentityPublicKey", "advertisedPresenceId")
            Profile2Constants.TYPE_PAIR_MSG -> setOf("v", "type", "sessionId", "role", "spakePublicValue", "invitationHash")
            Profile2Constants.TYPE_PAIR_CONFIRM -> setOf("v", "type", "sessionId", "role", "keyConfirmationMac", "identitySignature")
            Profile2Constants.TYPE_PAIR_APPROVAL -> setOf("v", "type", "sessionId", "role", "approved", "reason")
            Profile2Constants.TYPE_PAIR_RESULT -> setOf("v", "type", "sessionId", "paired", "errorCode", "message")
            else -> throw IllegalArgumentException("Unknown Profile 2 message type '$type'")
        }
        require(obj.keys.all { it in allowedFields }) {
            "Profile 2 message contains an unknown field"
        }

        return when (type) {
            Profile2Constants.TYPE_SESSION_OPEN -> Profile2Message.SessionOpen(
                sessionId = sessionId,
                purpose = obj.reqString("purpose"),
                authMethod = obj.reqString("authMethod"),
                initiatorNonce = obj.reqString("initiatorNonce"),
                initiatorDeviceName = obj.reqString("initiatorDeviceName"),
                initiatorPlatform = obj.reqString("initiatorPlatform"),
                initiatorIdentityPublicKey = obj.reqString("initiatorIdentityPublicKey"),
                expectedPresenceId = obj.optString("expectedPresenceId"),
                expectedPeerIdentityFingerprint = obj.optString("expectedPeerIdentityFingerprint"),
                invitationId = obj.optString("invitationId")
            )
            Profile2Constants.TYPE_SESSION_OPEN_ACK -> Profile2Message.SessionOpenAck(
                sessionId = sessionId,
                accepted = obj.reqBool("accepted"),
                errorCode = obj.optString("errorCode"),
                message = obj.optString("message"),
                responderNonce = obj.optString("responderNonce"),
                responderDeviceName = obj.optString("responderDeviceName"),
                responderPlatform = obj.optString("responderPlatform"),
                responderIdentityPublicKey = obj.optString("responderIdentityPublicKey"),
                advertisedPresenceId = obj.optString("advertisedPresenceId")
            )
            Profile2Constants.TYPE_PAIR_MSG -> Profile2Message.PairMsg(
                sessionId = sessionId,
                role = obj.reqString("role"),
                spakePublicValue = obj.optString("spakePublicValue"),
                invitationHash = obj.optString("invitationHash")
            )
            Profile2Constants.TYPE_PAIR_CONFIRM -> Profile2Message.PairConfirm(
                sessionId = sessionId,
                role = obj.reqString("role"),
                keyConfirmationMac = obj.reqString("keyConfirmationMac"),
                identitySignature = obj.reqString("identitySignature")
            )
            Profile2Constants.TYPE_PAIR_APPROVAL -> Profile2Message.PairApproval(
                sessionId = sessionId,
                role = obj.reqString("role"),
                approved = obj.reqBool("approved"),
                reason = obj.optString("reason")
            )
            Profile2Constants.TYPE_PAIR_RESULT -> Profile2Message.PairResult(
                sessionId = sessionId,
                paired = obj.reqBool("paired"),
                errorCode = obj.optString("errorCode"),
                message = obj.optString("message")
            )
            else -> throw IllegalArgumentException("Unknown Profile 2 message type '$type'")
        }
    }

    fun writeMessage(output: OutputStream, message: Profile2Message) {
        val payload = encode(message)
        val header = ByteBuffer.allocate(4).putInt(payload.size).array()
        output.write(header)
        output.write(payload)
        output.flush()
    }

    fun readMessage(input: InputStream): Profile2Message {
        val lenBuf = readExact(input, 4)
        val length = ByteBuffer.wrap(lenBuf).int
        require(length in 1..Profile2Constants.MAX_CONTROL_PAYLOAD_BYTES) {
            "Profile 2 control frame length $length exceeds ceiling ${Profile2Constants.MAX_CONTROL_PAYLOAD_BYTES}"
        }
        val payload = readExact(input, length)
        return decode(payload)
    }

    private fun readExact(input: InputStream, size: Int): ByteArray {
        val buf = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buf, offset, size - offset)
            if (read < 0) throw EOFException("Connection closed while reading Profile 2 control frame")
            offset += read
        }
        return buf
    }

    private fun JsonObject.reqString(key: String): String =
        this[key]?.jsonPrimitive?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("Missing required string field '$key'")

    private fun JsonObject.optString(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf { it.isString }?.content

    private fun JsonObject.reqBool(key: String): Boolean =
        this[key]?.jsonPrimitive?.booleanOrNull
            ?: throw IllegalArgumentException("Missing required boolean field '$key'")
}

object Profile2Crypto {
    fun buildPairV2TranscriptBytes(
        sessionId: String,
        initiatorNonce32: ByteArray,
        responderNonce32: ByteArray,
        initiatorIdentitySpki: ByteArray,
        responderIdentitySpki: ByteArray,
        advertisedPresenceIdHex: String,
        serverTlsSpkiSha256Hex: String,
        purpose: String = Profile2Constants.PURPOSE_PAIR_ONLY,
        method: String = Profile2Constants.AUTH_METHOD_SPAKE2_PIN_V1
    ): ByteArray {
        require(initiatorNonce32.size == 32) { "Initiator nonce must be 32 bytes" }
        require(responderNonce32.size == 32) { "Responder nonce must be 32 bytes" }
        val initFp = sha256Hex(initiatorIdentitySpki)
        val respFp = sha256Hex(responderIdentitySpki)
        val presence = advertisedPresenceIdHex.ifBlank { "0".repeat(32) }.lowercase()

        return buildPairV2TranscriptBytes(
            method = method,
            sessionId = sessionId,
            initiatorNonceHex = toHexLower(initiatorNonce32),
            responderNonceHex = toHexLower(responderNonce32),
            initiatorFingerprintHex = initFp,
            initiatorPublicKeyDerHex = toHexLower(initiatorIdentitySpki),
            responderFingerprintHex = respFp,
            responderPublicKeyDerHex = toHexLower(responderIdentitySpki),
            responderPresenceHex = presence,
            tlsServerSpkiSha256Hex = serverTlsSpkiSha256Hex,
            purpose = purpose
        )
    }

    fun buildPairV2TranscriptBytes(
        method: String,
        sessionId: String,
        initiatorNonceHex: String,
        responderNonceHex: String,
        initiatorFingerprintHex: String,
        initiatorPublicKeyDerHex: String,
        responderFingerprintHex: String,
        responderPublicKeyDerHex: String,
        responderPresenceHex: String,
        tlsServerSpkiSha256Hex: String,
        purpose: String = Profile2Constants.PURPOSE_PAIR_ONLY
    ): ByteArray {
        val out = ByteArrayOutputStream(640)
        fun writeString(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            out.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
            out.write(bytes)
        }

        out.write("GLIDE-PAIR-V2".toByteArray(StandardCharsets.US_ASCII))
        out.write(0)
        writeString(method)
        writeString(sessionId.lowercase())
        writeString("initiator")
        writeString("responder")
        writeString(initiatorNonceHex.lowercase())
        writeString(responderNonceHex.lowercase())
        writeString(initiatorFingerprintHex.lowercase())
        writeString(initiatorPublicKeyDerHex.lowercase())
        writeString(responderFingerprintHex.lowercase())
        writeString(responderPublicKeyDerHex.lowercase())
        writeString(responderPresenceHex.lowercase())
        writeString(tlsServerSpkiSha256Hex.lowercase())
        writeString(purpose)
        return out.toByteArray()
    }

    fun deriveConfirmationKeys(
        sharedSecret: ByteArray,
        transcriptHash32: ByteArray
    ): Pair<ByteArray, ByteArray> {
        require(sharedSecret.isNotEmpty()) { "Shared secret must not be empty" }
        require(transcriptHash32.size == 32) { "Transcript hash must be 32 bytes" }

        val prk = hkdfExtractSha256(salt = transcriptHash32, ikm = sharedSecret)
        val i2r = hkdfExpandSha256(prk, "glide-pair-v2-confirm-i2r".toByteArray(StandardCharsets.US_ASCII), 32)
        val r2i = hkdfExpandSha256(prk, "glide-pair-v2-confirm-r2i".toByteArray(StandardCharsets.US_ASCII), 32)
        prk.fill(0)
        return Pair(i2r, r2i)
    }

    fun computeConfirmationMac(confirmationKey32: ByteArray, transcriptHash32: ByteArray): ByteArray {
        require(confirmationKey32.size == 32) { "Confirmation key must be 32 bytes" }
        require(transcriptHash32.size == 32) { "Transcript hash must be 32 bytes" }
        return hmacSha256(confirmationKey32, transcriptHash32)
    }

    fun deriveQrSharedSecret(tokenBase64Url: String, invitationHashHex: String): ByteArray {
        val tokenBytes = Base64.getUrlDecoder().decode(tokenBase64Url)
        val salt = sha256(invitationHashHex.lowercase().toByteArray(StandardCharsets.US_ASCII))
        val prk = hkdfExtractSha256(salt = salt, ikm = tokenBytes)
        val secret = hkdfExpandSha256(prk, "glide-pair-v2-qr-secret".toByteArray(StandardCharsets.US_ASCII), 64)
        tokenBytes.fill(0)
        prk.fill(0)
        return secret
    }

    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(bytes: ByteArray): String = toHexLower(sha256(bytes))

    fun toHexLower(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xff))
        }
        return sb.toString()
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdfExtractSha256(salt: ByteArray, ikm: ByteArray): ByteArray =
        hmacSha256(salt, ikm)

    private fun hkdfExpandSha256(prk: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        val result = ByteArray(outLen)
        var prev = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < outLen) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(prev)
            mac.update(info)
            mac.update(counter.toByte())
            prev = mac.doFinal()
            val toCopy = minOf(prev.size, outLen - offset)
            System.arraycopy(prev, 0, result, offset, toCopy)
            offset += toCopy
            counter++
        }
        return result
    }
}
