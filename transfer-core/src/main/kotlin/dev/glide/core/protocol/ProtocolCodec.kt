package dev.glide.core.protocol

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.MessageId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.TransferFailure
import dev.glide.core.domain.isValidSha256
import dev.glide.core.domain.isValidUuidV4
import dev.glide.core.folder.SafeRelativePath
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object ProtocolCodec {
    const val MAX_CONTROL_BYTES = 16384
    const val MAX_EXTENSIONS_BYTES = 1024
    const val MAX_JSON_DEPTH = 8
    const val PROTOCOL_MAJOR_VERSION = 1

    private const val MAX_ITEM_BYTES = 68_719_476_736L // 64 GiB
    private const val MAX_BATCH_BYTES = 274_877_906_944L // 256 GiB
    private val MIME_REGEX = Regex("^[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+$")

    val json: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = false
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(envelope: Envelope): ByteArray {
        if (envelope.v != PROTOCOL_MAJOR_VERSION) {
            throw ProtocolViolationException(
                TransferFailure.UnsupportedProtocol("Unsupported protocol version ${envelope.v}, expected $PROTOCOL_MAJOR_VERSION")
            )
        }
        if (envelope.seq < 1) {
            throw ProtocolViolationException(
                TransferFailure.MalformedMessage("Envelope seq must be positive (seq >= 1), got: ${envelope.seq}")
            )
        }
        if (envelope.extensions != null) {
            val extBytes = json.encodeToString(envelope.extensions).toByteArray(StandardCharsets.UTF_8)
            if (extBytes.size > MAX_EXTENSIONS_BYTES) {
                throw ProtocolViolationException(
                    TransferFailure.LimitExceeded("Extensions size ${extBytes.size} bytes exceeds maximum $MAX_EXTENSIONS_BYTES bytes")
                )
            }
        }
        val encodedString = json.encodeToString(envelope)
        val bytes = encodedString.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_CONTROL_BYTES) {
            throw ProtocolViolationException(
                TransferFailure.LimitExceeded("Control payload size ${bytes.size} bytes exceeds maximum $MAX_CONTROL_BYTES bytes")
            )
        }
        return bytes
    }

    fun decode(bytes: ByteArray): Envelope {
        if (bytes.size > MAX_CONTROL_BYTES) {
            throw ProtocolViolationException(
                TransferFailure.LimitExceeded("Control payload size ${bytes.size} bytes exceeds maximum $MAX_CONTROL_BYTES bytes")
            )
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            throw ProtocolViolationException(
                TransferFailure.MalformedMessage("UTF-8 Byte Order Mark (BOM) is strictly prohibited")
            )
        }

        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw ProtocolViolationException(
                failure = TransferFailure.MalformedMessage("Malformed UTF-8 sequence in control payload"),
                cause = e
            )
        }

        val root = try {
            StrictJsonParser(text, maxDepth = MAX_JSON_DEPTH).parse()
        } catch (e: Exception) {
            throw ProtocolViolationException(
                failure = TransferFailure.MalformedMessage("Failed to parse JSON: ${e.message}"),
                cause = e
            )
        }

        for (key in root.keys) {
            if (key !in ProtocolCatalog.ALLOWED_ENVELOPE_KEYS) {
                throw ProtocolViolationException(
                    TransferFailure.MalformedMessage("Unknown envelope field: '$key'")
                )
            }
        }

        val vElem = root["v"] ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("Missing envelope field 'v'"))
        val typeElem = root["type"] ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("Missing envelope field 'type'"))
        val sessionIdElem = root["sessionId"] ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("Missing envelope field 'sessionId'"))
        val messageIdElem = root["messageId"] ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("Missing envelope field 'messageId'"))
        val seqElem = root["seq"] ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("Missing envelope field 'seq'"))
        val bodyElem = root["body"] ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("Missing envelope field 'body'"))
        val extensionsElem = root["extensions"]

        if (vElem !is JsonPrimitive || vElem.isString) {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("Envelope field 'v' must be a JSON integer"))
        }
        val v = vElem.content.toIntOrNull() ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("Envelope field 'v' is not an integer: '${vElem.content}'"))
        if (v != PROTOCOL_MAJOR_VERSION) {
            throw ProtocolViolationException(TransferFailure.UnsupportedProtocol("Unsupported protocol major version $v, expected $PROTOCOL_MAJOR_VERSION"))
        }

        val type = (typeElem as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("Envelope field 'type' must be a string"))
        if (type !in MessageTypes.ALL) {
            throw ProtocolViolationException(TransferFailure.UnsupportedProtocol("Unknown protocol message type '$type'"))
        }

        val sessionIdStr = (sessionIdElem as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("Envelope field 'sessionId' must be a string"))
        if (!isValidUuidV4(sessionIdStr)) {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("sessionId must be a canonical lowercase UUIDv4, got: '$sessionIdStr'"))
        }
        val sessionId = SessionId(sessionIdStr)

        val messageIdStr = (messageIdElem as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("Envelope field 'messageId' must be a string"))
        if (!isValidUuidV4(messageIdStr)) {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("messageId must be a canonical lowercase UUIDv4, got: '$messageIdStr'"))
        }
        val messageId = MessageId(messageIdStr)

        if (seqElem !is JsonPrimitive || seqElem.isString) {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("Envelope field 'seq' must be a JSON integer"))
        }
        val seq = seqElem.content.toLongOrNull() ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("Envelope field 'seq' is not an integer: '${seqElem.content}'"))
        if (seq < 1) {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("Envelope field 'seq' must be a positive integer (seq >= 1), got: $seq"))
        }

        val body = bodyElem as? JsonObject
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("Envelope field 'body' must be a JSON object"))

        val extensions: JsonObject? = if (extensionsElem != null && extensionsElem !is kotlinx.serialization.json.JsonNull) {
            if (extensionsElem !is JsonObject) {
                throw ProtocolViolationException(TransferFailure.MalformedMessage("Envelope field 'extensions' must be a JSON object if present"))
            }
            val extBytes = extensionsElem.toString().toByteArray(StandardCharsets.UTF_8)
            if (extBytes.size > MAX_EXTENSIONS_BYTES) {
                throw ProtocolViolationException(
                    TransferFailure.LimitExceeded("Extensions size ${extBytes.size} bytes exceeds maximum $MAX_EXTENSIONS_BYTES bytes")
                )
            }
            extensionsElem
        } else null

        validateBody(type, body)

        return Envelope(
            v = v,
            type = type,
            sessionId = sessionId,
            messageId = messageId,
            seq = seq,
            body = body,
            extensions = extensions
        )
    }

    private fun validateBody(type: String, body: JsonObject) {
        val allowedKeys = ProtocolCatalog.ALLOWED_BODY_KEYS[type] ?: emptySet()
        for (key in body.keys) {
            if (key !in allowedKeys) {
                throw ProtocolViolationException(
                    TransferFailure.MalformedMessage("Unknown field '$key' in body for message type '$type'")
                )
            }
        }

        when (type) {
            MessageTypes.HELLO -> validateHello(body)
            MessageTypes.HELLO_ACK -> validateHelloAck(body)
            MessageTypes.OFFER_BEGIN -> validateOfferBegin(body)
            MessageTypes.OFFER_PAGE -> validateOfferPage(body)
            MessageTypes.OFFER_END -> validateOfferEnd(body)
            MessageTypes.OFFER_DECISION -> validateOfferDecision(body)
            MessageTypes.ITEM_START -> validateItemStart(body)
            MessageTypes.ITEM_READY -> validateItemReady(body)
            MessageTypes.ITEM_END -> validateItemEnd(body)
            MessageTypes.ITEM_RESULT -> validateItemResult(body)
            MessageTypes.BATCH_END -> validateBatchEnd(body)
            MessageTypes.BATCH_RESULT -> validateBatchResult(body)
            MessageTypes.CANCEL -> validateCancel(body)
            MessageTypes.CANCEL_ACK -> validateCancelAck(body)
            MessageTypes.ERROR -> validateError(body)
            MessageTypes.PING -> validatePing(body)
            MessageTypes.PONG -> validatePong(body)
            MessageTypes.IDENTITY_CHALLENGE -> validateIdentityChallenge(body)
            MessageTypes.IDENTITY_RESPONSE -> validateIdentityResponse(body)
            MessageTypes.RESUME_REQUEST -> validateResumeRequest(body)
            MessageTypes.RESUME_ACCEPT -> validateResumeAccept(body)
            MessageTypes.RESUME_REJECT -> validateResumeReject(body)
        }
    }

    private fun validateHello(body: JsonObject) {
        val majors = body["supportedMajors"] as? JsonArray
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("HELLO requires 'supportedMajors' array"))
        if (majors.isEmpty()) {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("HELLO 'supportedMajors' cannot be empty"))
        }
        for (elem in majors) {
            val prim = elem as? JsonPrimitive ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("Major version must be integer"))
            if (prim.isString || prim.content.toIntOrNull() == null) {
                throw ProtocolViolationException(TransferFailure.MalformedMessage("Major version must be integer"))
            }
        }

        val reqCaps = body["requiredCapabilities"] as? JsonArray
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("HELLO requires 'requiredCapabilities' array"))
        for (elem in reqCaps) {
            val prim = elem as? JsonPrimitive ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("Capability must be string"))
            if (!prim.isString) throw ProtocolViolationException(TransferFailure.MalformedMessage("Capability must be string"))
        }

        val optCaps = body["optionalCapabilities"] as? JsonArray
        if (optCaps != null) {
            for (elem in optCaps) {
                val prim = elem as? JsonPrimitive ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("Capability must be string"))
                if (!prim.isString) throw ProtocolViolationException(TransferFailure.MalformedMessage("Capability must be string"))
            }
        }

        val role = requireString(body, "role")
        if (role != "sender" && role != "receiver") {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("HELLO role must be 'sender' or 'receiver', got: '$role'"))
        }

        val peerId = requireString(body, "ephemeralPeerId")
        if (!isValidUuidV4(peerId)) {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("HELLO ephemeralPeerId must be UUIDv4, got: '$peerId'"))
        }

        val displayName = requireString(body, "displayName")
        validateDisplayName(displayName)
    }

    private fun validateHelloAck(body: JsonObject) {
        val majorElem = body["selectedMajor"] as? JsonPrimitive
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("HELLO_ACK requires 'selectedMajor' integer"))
        if (majorElem.isString || majorElem.content.toIntOrNull() != PROTOCOL_MAJOR_VERSION) {
            throw ProtocolViolationException(TransferFailure.UnsupportedProtocol("HELLO_ACK selectedMajor must be $PROTOCOL_MAJOR_VERSION"))
        }

        val caps = body["capabilities"] as? JsonArray
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("HELLO_ACK requires 'capabilities' array"))
        for (elem in caps) {
            val prim = elem as? JsonPrimitive ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("Capability must be string"))
            if (!prim.isString) throw ProtocolViolationException(TransferFailure.MalformedMessage("Capability must be string"))
        }

        val peerId = requireString(body, "ephemeralPeerId")
        if (!isValidUuidV4(peerId)) {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("HELLO_ACK ephemeralPeerId must be UUIDv4, got: '$peerId'"))
        }

        val displayName = requireString(body, "displayName")
        validateDisplayName(displayName)

        val limits = body["limits"] as? JsonObject
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("HELLO_ACK requires 'limits' object"))
        for (key in limits.keys) {
            if (key !in ProtocolCatalog.ALLOWED_LIMITS_KEYS) {
                throw ProtocolViolationException(TransferFailure.MalformedMessage("Unknown field '$key' in limits object"))
            }
        }

        val maxItemsElem = limits["maxItems"] as? JsonPrimitive
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("limits requires 'maxItems'"))
        val maxItems = maxItemsElem.content.toIntOrNull()
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("limits 'maxItems' must be an integer"))
        if (maxItems !in 1..1000) {
            throw ProtocolViolationException(TransferFailure.LimitExceeded("limits 'maxItems' must be in 1..1000, got: $maxItems"))
        }

        val maxItemBytes = requireByteCount(limits, "maxItemBytes", MAX_ITEM_BYTES)
        if (maxItemBytes <= 0) {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("limits 'maxItemBytes' must be positive"))
        }

        val maxBatchBytes = requireByteCount(limits, "maxBatchBytes", MAX_BATCH_BYTES)
        if (maxBatchBytes <= 0) {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("limits 'maxBatchBytes' must be positive"))
        }

        val maxControlBytesElem = limits["maxControlBytes"] as? JsonPrimitive
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("limits requires 'maxControlBytes'"))
        val maxControlBytes = maxControlBytesElem.content.toIntOrNull()
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("limits 'maxControlBytes' must be an integer"))
        if (maxControlBytes !in 1024..MAX_CONTROL_BYTES) {
            throw ProtocolViolationException(TransferFailure.UnsupportedProtocol("limits 'maxControlBytes' must be in 1024..16384, got: $maxControlBytes"))
        }
    }

    private fun validateOfferBegin(body: JsonObject) {
        requireUuid(body, "batchId")
        val itemCountElem = body["itemCount"] as? JsonPrimitive
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("OFFER_BEGIN requires 'itemCount'"))
        val itemCount = itemCountElem.content.toIntOrNull()
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("OFFER_BEGIN 'itemCount' must be integer"))
        if (itemCount !in 1..1000) {
            throw ProtocolViolationException(TransferFailure.LimitExceeded("OFFER_BEGIN 'itemCount' must be in 1..1000, got: $itemCount"))
        }

        requireByteCount(body, "totalBytes", MAX_BATCH_BYTES)

        val pageCountElem = body["pageCount"] as? JsonPrimitive
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("OFFER_BEGIN requires 'pageCount'"))
        val pageCount = pageCountElem.content.toIntOrNull()
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("OFFER_BEGIN 'pageCount' must be integer"))
        if (pageCount !in 1..1000) {
            throw ProtocolViolationException(TransferFailure.LimitExceeded("OFFER_BEGIN 'pageCount' must be in 1..1000, got: $pageCount"))
        }
    }

    private fun validateOfferPage(body: JsonObject) {
        requireUuid(body, "batchId")
        val pageIndexElem = body["pageIndex"] as? JsonPrimitive
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("OFFER_PAGE requires 'pageIndex'"))
        val pageIndex = pageIndexElem.content.toIntOrNull()
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("OFFER_PAGE 'pageIndex' must be integer"))
        if (pageIndex < 0) {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("OFFER_PAGE 'pageIndex' cannot be negative"))
        }

        val items = body["items"] as? JsonArray
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("OFFER_PAGE requires 'items' array"))
        if (items.isEmpty() || items.size > 32) {
            throw ProtocolViolationException(TransferFailure.LimitExceeded("OFFER_PAGE items count must be in 1..32, got: ${items.size}"))
        }

        for (elem in items) {
            val itemObj = elem as? JsonObject
                ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("Item in OFFER_PAGE must be JSON object"))
            for (key in itemObj.keys) {
                if (key !in ProtocolCatalog.ALLOWED_ITEM_KEYS) {
                    throw ProtocolViolationException(TransferFailure.MalformedMessage("Unknown field '$key' in OFFER_PAGE item"))
                }
            }
            requireUuid(itemObj, "itemId")
            val name = requireString(itemObj, "name")
            val codePoints = name.codePointCount(0, name.length)
            val nameBytes = name.toByteArray(StandardCharsets.UTF_8).size
            if (codePoints !in 1..255 || nameBytes > 1024) {
                throw ProtocolViolationException(TransferFailure.LimitExceeded("Item name must be 1..255 code points and <= 1024 bytes, got $codePoints cps, $nameBytes bytes"))
            }
            if (name.contains('/') || name.contains('\\') || name.contains('\u0000')) {
                throw ProtocolViolationException(TransferFailure.MalformedMessage("Item name cannot contain path separators or null bytes: '$name'"))
            }

            val mime = requireString(itemObj, "mime")
            if (mime.length > 127 || !MIME_REGEX.matches(mime)) {
                throw ProtocolViolationException(TransferFailure.MalformedMessage("Item mime is invalid: '$mime'"))
            }

            val itemSize =
                requireByteCount(
                    itemObj,
                    "sizeBytes",
                    MAX_ITEM_BYTES
                )

            val rootHashElem =
                itemObj["rootHash"]
            val chunkSizeElem =
                itemObj["chunkSize"]
            if ((rootHashElem == null) !=
                (chunkSizeElem == null)
            ) {
                throw ProtocolViolationException(
                    TransferFailure.MalformedMessage(
                        "OFFER_PAGE resume metadata requires both rootHash and chunkSize"
                    )
                )
            }
            if (rootHashElem != null &&
                chunkSizeElem != null
            ) {
                val rootHash =
                    (rootHashElem as?
                        JsonPrimitive)
                        ?.takeIf {
                            it.isString
                        }
                        ?.content
                        ?: throw ProtocolViolationException(
                            TransferFailure.MalformedMessage(
                                "OFFER_PAGE rootHash must be a string"
                            )
                        )
                if (rootHash.length != 64 ||
                    rootHash.any {
                        it !in '0'..'9' &&
                            it !in 'a'..'f'
                    }
                ) {
                    throw ProtocolViolationException(
                        TransferFailure.MalformedMessage(
                            "OFFER_PAGE rootHash must be canonical lowercase SHA-256"
                        )
                    )
                }

                val chunkPrimitive =
                    chunkSizeElem as?
                        JsonPrimitive
                        ?: throw ProtocolViolationException(
                            TransferFailure.MalformedMessage(
                                "OFFER_PAGE chunkSize must be an integer"
                            )
                        )
                if (chunkPrimitive.isString) {
                    throw ProtocolViolationException(
                        TransferFailure.MalformedMessage(
                            "OFFER_PAGE chunkSize must be an integer"
                        )
                    )
                }
                val chunkSize =
                    chunkPrimitive.content
                        .toIntOrNull()
                        ?: throw ProtocolViolationException(
                            TransferFailure.MalformedMessage(
                                "OFFER_PAGE chunkSize must be an integer"
                            )
                        )
                if (chunkSize !in
                    1..16 * 1024 * 1024
                ) {
                    throw ProtocolViolationException(
                        TransferFailure.LimitExceeded(
                            "OFFER_PAGE chunkSize must be in 1..16 MiB"
                        )
                    )
                }
                if (itemSize == 0L) {
                    throw ProtocolViolationException(
                        TransferFailure.MalformedMessage(
                            "Zero-byte item cannot carry resume metadata"
                        )
                    )
                }
            }

            val relPathElem = itemObj["relativePath"] as? JsonPrimitive
            if (relPathElem != null) {
                if (!relPathElem.isString) {
                    throw ProtocolViolationException(TransferFailure.MalformedMessage("Item relativePath must be string"))
                }
                val rawPath = relPathElem.content
                val parsed = SafeRelativePath.parse(rawPath)
                if (parsed.isFailure) {
                    throw ProtocolViolationException(
                        TransferFailure.MalformedMessage(
                            parsed.exceptionOrNull()?.message ?: "Invalid relativePath: '$rawPath'"
                        )
                    )
                }
            }
        }
    }

    private fun validateOfferEnd(body: JsonObject) {
        requireUuid(body, "batchId")
    }

    private fun validateOfferDecision(body: JsonObject) {
        requireUuid(body, "batchId")
        val acceptedElem = body["accepted"] as? JsonPrimitive
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("OFFER_DECISION requires 'accepted' boolean"))
        if (acceptedElem.isString) {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("OFFER_DECISION 'accepted' must be boolean"))
        }
        val accepted = acceptedElem.content.toBooleanStrictOrNull()
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("OFFER_DECISION 'accepted' must be boolean"))

        val codeElem = body["code"] as? JsonPrimitive
        val code = codeElem?.takeIf { it.isString }?.content

        if (!accepted) {
            if (code == null) {
                throw ProtocolViolationException(TransferFailure.MalformedMessage("OFFER_DECISION rejection requires 'code'"))
            }
            val validRejectionCodes = setOf("USER_REJECTED", "INSUFFICIENT_STORAGE", "DESTINATION_UNAVAILABLE")
            if (code !in validRejectionCodes) {
                throw ProtocolViolationException(TransferFailure.MalformedMessage("Invalid OFFER_DECISION rejection code: '$code'"))
            }
        } else {
            if (code != null) {
                throw ProtocolViolationException(TransferFailure.MalformedMessage("OFFER_DECISION acceptance must not contain 'code'"))
            }
        }
    }

    private fun validateItemStart(body: JsonObject) {
        requireUuid(body, "batchId")
        requireUuid(body, "itemId")
        val bulkRef = requireString(body, "bulkRef")
        if (bulkRef.isBlank() || bulkRef.length > 64) {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("ITEM_START 'bulkRef' must be 1..64 characters"))
        }
        requireByteCount(body, "sizeBytes", MAX_ITEM_BYTES)
    }

    private fun validateItemReady(body: JsonObject) {
        requireUuid(body, "batchId")
        requireUuid(body, "itemId")
    }

    private fun validateItemEnd(body: JsonObject) {
        requireUuid(body, "batchId")
        requireUuid(body, "itemId")
        requireByteCount(body, "actualBytes", MAX_ITEM_BYTES)
        val sha256 = requireString(body, "sha256")
        if (!isValidSha256(sha256)) {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("ITEM_END 'sha256' must be 64 lowercase hex characters"))
        }
    }

    private fun validateItemResult(body: JsonObject) {
        requireUuid(body, "batchId")
        requireUuid(body, "itemId")
        val status = requireString(body, "status")
        when (status) {
            "verified" -> {
                requireByteCount(body, "sizeBytes", MAX_ITEM_BYTES)
                val sha256 = requireString(body, "sha256")
                if (!isValidSha256(sha256)) {
                    throw ProtocolViolationException(TransferFailure.MalformedMessage("ITEM_RESULT verified 'sha256' must be 64 lowercase hex characters"))
                }
                if (body.containsKey("code")) {
                    throw ProtocolViolationException(TransferFailure.MalformedMessage("ITEM_RESULT verified must not contain 'code'"))
                }
            }
            "failed" -> {
                val code = requireString(body, "code")
                if (code !in WireErrorCodes.ALL) {
                    throw ProtocolViolationException(TransferFailure.MalformedMessage("ITEM_RESULT failed 'code' must be a valid catalog code, got: '$code'"))
                }
                if (body.containsKey("sizeBytes") || body.containsKey("sha256")) {
                    throw ProtocolViolationException(TransferFailure.MalformedMessage("ITEM_RESULT failed must not contain sizeBytes or sha256"))
                }
            }
            else -> throw ProtocolViolationException(TransferFailure.MalformedMessage("ITEM_RESULT status must be 'verified' or 'failed', got: '$status'"))
        }
    }

    private fun validateBatchEnd(body: JsonObject) {
        requireUuid(body, "batchId")
    }

    private fun validateBatchResult(body: JsonObject) {
        requireUuid(body, "batchId")
        val status = requireString(body, "status")
        if (status != "completed") {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("BATCH_RESULT status must be 'completed', got: '$status'"))
        }
        val countElem = body["verifiedCount"] as? JsonPrimitive
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("BATCH_RESULT requires 'verifiedCount'"))
        val verifiedCount = countElem.content.toIntOrNull()
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("BATCH_RESULT 'verifiedCount' must be integer"))
        if (verifiedCount !in 1..1000) {
            throw ProtocolViolationException(TransferFailure.LimitExceeded("BATCH_RESULT 'verifiedCount' must be in 1..1000, got: $verifiedCount"))
        }
        requireByteCount(body, "totalVerifiedBytes", MAX_BATCH_BYTES)
    }

    private fun validateCancel(body: JsonObject) {
        if (body.containsKey("batchId")) {
            requireUuid(body, "batchId")
        }
        val code = requireString(body, "code")
        if (code != "USER_CANCELLED" && code != "SYSTEM_CANCELLED") {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("CANCEL code must be USER_CANCELLED or SYSTEM_CANCELLED, got: '$code'"))
        }
    }

    private fun validateCancelAck(body: JsonObject) {
        requireUuid(body, "replyTo")
    }

    private fun validateError(body: JsonObject) {
        val code = requireString(body, "code")
        if (code !in WireErrorCodes.ALL) {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("ERROR code '$code' is not in catalog"))
        }
        if (body.containsKey("batchId")) {
            requireUuid(body, "batchId")
        }
        if (body.containsKey("itemId")) {
            requireUuid(body, "itemId")
        }
        val detail = requireString(body, "detail")
        if (detail.length > 160) {
            throw ProtocolViolationException(TransferFailure.LimitExceeded("ERROR detail length must be <= 160 characters, got: ${detail.length}"))
        }
    }

    private fun validatePing(body: JsonObject) {
        requireUuid(body, "nonce")
    }

    private fun validatePong(body: JsonObject) {
        requireUuid(body, "nonce")
    }

    private fun validateIdentityChallenge(body: JsonObject) {
        requireProfile(body, IdentityResumeTranscript.IDENTITY_PROFILE)
        requireLowerHex64(body, "challengeNonceHex")
        requireLowerHex64(body, "verifierFingerprintHex")
        val verifierRole = requireRole(body, "verifierRole")
        val signerRole = requireRole(body, "signerRole")
        if (verifierRole == signerRole) {
            throw ProtocolViolationException(
                TransferFailure.MalformedMessage("IDENTITY_CHALLENGE roles must be opposite")
            )
        }
        requireLowerHex64(body, "channelBindingHex")
        requireLowerHex64(body, "capabilityHashHex")
    }

    private fun validateIdentityResponse(body: JsonObject) {
        requireProfile(body, IdentityResumeTranscript.IDENTITY_PROFILE)
        requireUuid(body, "challengeMessageId")
        requireLowerHex64(body, "challengeNonceHex")
        requireLowerHex64(body, "signerFingerprintHex")
        requireEvenLowerHex(body, "publicKeyDerHex", maxChars = 4096)
        requireEvenLowerHex(body, "signatureHex", maxChars = 1024)
    }

    private fun validateResumeRequest(body: JsonObject) {
        requireProfile(body, IdentityResumeTranscript.RESUME_PROFILE)
        requireUuid(body, "originalSessionId")
        requireUuid(body, "itemId")
        requireLowerHex64(body, "requesterFingerprintHex")
        requireLowerHex64(body, "responderFingerprintHex")

        val requesterRole = requireRole(body, "requesterRole")
        val responderRole = requireRole(body, "responderRole")
        if (requesterRole != "receiver" || responderRole != "sender") {
            throw ProtocolViolationException(
                TransferFailure.MalformedMessage("RESUME_REQUEST must be receiver -> sender")
            )
        }

        val verifiedChunkCount = requireLong(body, "verifiedChunkCount", min = 0)
        val contiguousByteOffset =
            requireByteCount(body, "contiguousByteOffset", MAX_ITEM_BYTES)

        val chunkSizeElement = body["chunkSize"] as? JsonPrimitive
            ?: throw ProtocolViolationException(
                TransferFailure.MalformedMessage("RESUME_REQUEST requires integer 'chunkSize'")
            )
        if (chunkSizeElement.isString) {
            throw ProtocolViolationException(
                TransferFailure.MalformedMessage("RESUME_REQUEST 'chunkSize' must be an integer")
            )
        }
        val chunkSize = chunkSizeElement.content.toIntOrNull()
            ?: throw ProtocolViolationException(
                TransferFailure.MalformedMessage("RESUME_REQUEST 'chunkSize' must be an integer")
            )
        if (chunkSize !in 1..16_777_216) {
            throw ProtocolViolationException(
                TransferFailure.LimitExceeded("RESUME_REQUEST 'chunkSize' must be in 1..16777216")
            )
        }

        val totalSizeBytes =
            requireByteCount(body, "totalSizeBytes", MAX_ITEM_BYTES)
        if (contiguousByteOffset > totalSizeBytes) {
            throw ProtocolViolationException(
                TransferFailure.MalformedMessage("RESUME_REQUEST offset exceeds totalSizeBytes")
            )
        }
        if (contiguousByteOffset % chunkSize != 0L &&
            contiguousByteOffset != totalSizeBytes
        ) {
            throw ProtocolViolationException(
                TransferFailure.MalformedMessage("RESUME_REQUEST offset is not chunk-aligned")
            )
        }

        val expectedChunkCount =
            if (contiguousByteOffset == totalSizeBytes &&
                totalSizeBytes % chunkSize != 0L
            ) {
                (totalSizeBytes + chunkSize - 1L) / chunkSize
            } else {
                contiguousByteOffset / chunkSize
            }
        if (verifiedChunkCount != expectedChunkCount) {
            throw ProtocolViolationException(
                TransferFailure.MalformedMessage(
                    "RESUME_REQUEST verifiedChunkCount does not match offset/chunkSize"
                )
            )
        }

        requireLowerHex64(body, "chunkRootHashHex")
        requireLowerHex64(body, "sessionNonceHex")
        requireLowerHex64(body, "channelBindingHex")
        requireLowerHex64(body, "capabilityHashHex")
        requireEvenLowerHex(body, "signatureHex", maxChars = 1024)
    }

    private fun validateResumeAccept(body: JsonObject) {
        requireProfile(body, IdentityResumeTranscript.RESUME_PROFILE)
        requireUuid(body, "requestMessageId")
        requireUuid(body, "originalSessionId")
        requireUuid(body, "itemId")
        requireLong(body, "startChunkIndex", min = 0)
        requireByteCount(body, "startByteOffset", MAX_ITEM_BYTES)
    }

    private fun validateResumeReject(body: JsonObject) {
        requireProfile(body, IdentityResumeTranscript.RESUME_PROFILE)
        requireUuid(body, "requestMessageId")
        requireUuid(body, "originalSessionId")
        requireUuid(body, "itemId")
        val code = requireString(body, "code")
        if (code !in ResumeRejectCodes.ALL) {
            throw ProtocolViolationException(
                TransferFailure.MalformedMessage("Unknown RESUME_REJECT code '$code'")
            )
        }
    }

    private fun requireProfile(body: JsonObject, expected: String) {
        val profile = requireString(body, "profile")
        if (profile != expected) {
            throw ProtocolViolationException(
                TransferFailure.UnsupportedProtocol(
                    "Unsupported extension profile '$profile', expected '$expected'"
                )
            )
        }
    }

    private fun requireRole(body: JsonObject, key: String): String {
        val role = requireString(body, key)
        if (role != "sender" && role != "receiver") {
            throw ProtocolViolationException(
                TransferFailure.MalformedMessage("$key must be 'sender' or 'receiver'")
            )
        }
        return role
    }

    private fun requireLowerHex64(body: JsonObject, key: String): String {
        val value = requireString(body, key)
        if (value.length != 64 ||
            value.any { it !in '0'..'9' && it !in 'a'..'f' }
        ) {
            throw ProtocolViolationException(
                TransferFailure.MalformedMessage(
                    "$key must be exactly 64 lowercase hexadecimal characters"
                )
            )
        }
        return value
    }

    private fun requireEvenLowerHex(
        body: JsonObject,
        key: String,
        maxChars: Int
    ): String {
        val value = requireString(body, key)
        if (value.isEmpty() ||
            value.length > maxChars ||
            value.length % 2 != 0 ||
            value.any { it !in '0'..'9' && it !in 'a'..'f' }
        ) {
            throw ProtocolViolationException(
                TransferFailure.MalformedMessage(
                    "$key must be non-empty even-length lowercase hexadecimal (max $maxChars chars)"
                )
            )
        }
        return value
    }

    private fun requireLong(obj: JsonObject, key: String, min: Long = 0): Long {
        val elem = obj[key] as? JsonPrimitive
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("Missing required field '$key'"))
        val value = elem.content.toLongOrNull()
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("Field '$key' must be an integer, got: '${elem.content}'"))
        if (value < min) {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("Field '$key' ($value) must be >= $min"))
        }
        return value
    }

    private fun requireString(obj: JsonObject, key: String): String {
        val elem = obj[key] as? JsonPrimitive
            ?: throw ProtocolViolationException(TransferFailure.MalformedMessage("Missing required field '$key'"))
        if (!elem.isString) {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("Field '$key' must be a string"))
        }
        return elem.content
    }

    private fun requireUuid(obj: JsonObject, key: String) {
        val value = requireString(obj, key)
        if (!isValidUuidV4(value)) {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("Field '$key' must be a canonical lowercase UUIDv4, got: '$value'"))
        }
    }

    private fun requireByteCount(obj: JsonObject, key: String, maxAllowed: Long): Long {
        val text = requireString(obj, key)
        val value = try {
            DecimalByteCount.parse(text)
        } catch (e: Exception) {
            throw ProtocolViolationException(
                failure = TransferFailure.MalformedMessage("Invalid byte count for '$key': ${e.message}"),
                cause = e
            )
        }
        if (value > maxAllowed) {
            throw ProtocolViolationException(TransferFailure.LimitExceeded("Byte count for '$key' ($value bytes) exceeds maximum $maxAllowed bytes"))
        }
        return value
    }

    private fun validateDisplayName(displayName: String) {
        val codePoints = displayName.codePointCount(0, displayName.length)
        val bytes = displayName.toByteArray(StandardCharsets.UTF_8).size
        if (codePoints !in 1..64 || bytes > 256) {
            throw ProtocolViolationException(TransferFailure.LimitExceeded("displayName must be 1..64 code points and <= 256 UTF-8 bytes, got $codePoints cps, $bytes bytes"))
        }
    }

    fun encodeFramed(envelope: Envelope): ByteArray {
        val payload = encode(envelope)
        val len = payload.size
        val buffer = ByteArray(4 + len)
        buffer[0] = (len ushr 24).toByte()
        buffer[1] = (len ushr 16).toByte()
        buffer[2] = (len ushr 8).toByte()
        buffer[3] = len.toByte()
        System.arraycopy(payload, 0, buffer, 4, len)
        return buffer
    }

    fun decodeFramed(stream: InputStream): Envelope {
        val header = stream.readNBytes(4)
        if (header.size < 4) {
            if (header.isEmpty()) throw java.io.EOFException("End of stream reached")
            throw ProtocolViolationException(TransferFailure.MalformedMessage("Truncated length prefix in stream: read ${header.size} bytes"))
        }
        val len = ((header[0].toInt() and 0xFF) shl 24) or
                ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)

        if (len <= 0) {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("Framed message length must be positive, got: $len"))
        }
        if (len > MAX_CONTROL_BYTES) {
            throw ProtocolViolationException(TransferFailure.LimitExceeded("Framed control payload length $len exceeds maximum $MAX_CONTROL_BYTES bytes"))
        }
        val payload = stream.readNBytes(len)
        if (payload.size < len) {
            throw ProtocolViolationException(TransferFailure.MalformedMessage("Stream truncated: expected $len bytes, got ${payload.size}"))
        }
        return decode(payload)
    }

    inline fun <reified T> decodeBody(envelope: Envelope): T {
        return json.decodeFromJsonElement<T>(envelope.body)
    }

    inline fun <reified T> encodeBody(body: T): JsonObject {
        return json.encodeToJsonElement(body) as JsonObject
    }
}
