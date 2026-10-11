package dev.glide.core.pairing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Thrown when an invitation violates URI format, size bounds, JSON depth,
 * or cryptographic/identity constraints (LAN-006, Section 8).
 */
class InvitationException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Strict canonical codec for Glide QR Pairing invitations (LAN-006, Section 8).
 *
 * Encoding format: glide://pair/v1#<unpadded-base64url(UTF-8 JSON)>
 * Invariants:
 * - Decoded JSON <= 2,048 bytes
 * - Raw URI <= 2,768 ASCII characters
 * - JSON depth <= 4
 * - Duplicate keys, unknown keys, non-canonical base64url, extra fields, and invalid UTF-8 are strictly rejected.
 */
object InvitationCodec {

    const val URI_PREFIX = "glide://pair/v1#"
    const val URI_PREFIX_V2 = "glide://pair/v2#"
    const val MAX_URI_CHARS = 2768
    const val MAX_JSON_BYTES = 2048
    const val MAX_JSON_DEPTH = 4

    private val EXPECTED_ROOT_KEYS = setOf(
        "v", "transport", "profile", "protocol", "invitationId",
        "presence", "name", "platform", "endpoints", "spkiSha256",
        "token", "expiresAt", "ttlSeconds"
    )

    private val EXPECTED_V2_ROOT_KEYS = setOf(
        "v", "purpose", "transport", "profile", "protocol", "invitationId",
        "presence", "name", "platform", "endpoints", "spkiSha256",
        "token", "expiresAt", "ttlSeconds"
    )

    private val EXPECTED_ENDPOINT_KEYS = setOf("address", "port")

    private val BASE64URL_REGEX = Regex("^[A-Za-z0-9_-]+$")

    private val strictJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = false
    }

    /**
     * Encodes a [LanInvitation] into the canonical, compact QR URI.
     */
    fun encode(invitation: LanInvitation): String {
        val jsonObject = buildJsonObject {
            put("v", invitation.v)
            put("transport", invitation.transport)
            put("profile", invitation.profile)
            put("protocol", invitation.protocol)
            put("invitationId", invitation.invitationId)
            put("presence", invitation.presence)
            put("name", invitation.name)
            put("platform", invitation.platform)
            put("endpoints", buildJsonArray {
                for (endpoint in invitation.endpoints) {
                    add(buildJsonObject {
                        put("address", endpoint.address)
                        put("port", endpoint.port)
                    })
                }
            })
            put("spkiSha256", invitation.spkiSha256)
            put("token", invitation.token)
            put("expiresAt", invitation.expiresAt)
            put("ttlSeconds", invitation.ttlSeconds)
        }

        val jsonString = strictJson.encodeToString(JsonObject.serializer(), jsonObject)
        val jsonBytes = jsonString.toByteArray(StandardCharsets.UTF_8)
        if (jsonBytes.size > MAX_JSON_BYTES) {
            throw InvitationException("Encoded JSON size ${jsonBytes.size} exceeds maximum $MAX_JSON_BYTES bytes")
        }

        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(jsonBytes)
        val uri = "$URI_PREFIX$base64"
        if (uri.length > MAX_URI_CHARS) {
            throw InvitationException("Encoded URI length ${uri.length} exceeds maximum $MAX_URI_CHARS characters")
        }
        return uri
    }

    /**
     * Encodes a [LanPairV2Invitation] into the canonical `glide://pair/v2#...` QR URI.
     */
    fun encodePairV2(invitation: LanPairV2Invitation): String {
        val jsonObject = buildJsonObject {
            put("v", invitation.v)
            put("purpose", invitation.purpose)
            put("transport", invitation.transport)
            put("profile", invitation.profile)
            put("protocol", invitation.protocol)
            put("invitationId", invitation.invitationId)
            put("presence", invitation.presence)
            put("name", invitation.name)
            put("platform", invitation.platform)
            put("endpoints", buildJsonArray {
                for (endpoint in invitation.endpoints) {
                    add(buildJsonObject {
                        put("address", endpoint.address)
                        put("port", endpoint.port)
                    })
                }
            })
            put("spkiSha256", invitation.spkiSha256)
            put("token", invitation.token)
            put("expiresAt", invitation.expiresAt)
            put("ttlSeconds", invitation.ttlSeconds)
        }

        val jsonString = strictJson.encodeToString(JsonObject.serializer(), jsonObject)
        val jsonBytes = jsonString.toByteArray(StandardCharsets.UTF_8)
        if (jsonBytes.size > MAX_JSON_BYTES) {
            throw InvitationException("Encoded JSON size ${jsonBytes.size} exceeds maximum $MAX_JSON_BYTES bytes")
        }

        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(jsonBytes)
        val uri = "$URI_PREFIX_V2$base64"
        if (uri.length > MAX_URI_CHARS) {
            throw InvitationException("Encoded URI length ${uri.length} exceeds maximum $MAX_URI_CHARS characters")
        }
        return uri
    }

    /**
     * Decodes either a v1 transfer invitation (`glide://pair/v1#`) or a v2 pair-only invitation (`glide://pair/v2#`).
     */
    fun decodeAny(rawUri: String): ParsedGlideInvitation {
        val trimmed = rawUri.trim()
        return when {
            trimmed.startsWith(URI_PREFIX) -> ParsedGlideInvitation.TransferV1(decode(trimmed))
            trimmed.startsWith(URI_PREFIX_V2) -> ParsedGlideInvitation.PairOnlyV2(decodePairV2(trimmed))
            else -> throw InvitationException("Invalid URI scheme or prefix")
        }
    }

    /**
     * Computes the exact SHA-256 hex digest of the canonical JSON bytes for an invitation.
     */
    fun computeInvitationHash(invitation: LanInvitation): String {
        val uri = encode(invitation)
        return decode(uri).invitationHash
    }

    /**
     * Decodes and strictly validates a QR URI into a [LanInvitation].
     */
    fun decode(rawUri: String): LanInvitation {
        if (rawUri.length > MAX_URI_CHARS) {
            throw InvitationException("URI length ${rawUri.length} exceeds maximum $MAX_URI_CHARS characters")
        }
        if (rawUri.startsWith(URI_PREFIX_V2)) {
            throw InvitationException("Update Glide to pair this device")
        }
        if (!rawUri.startsWith(URI_PREFIX)) {
            throw InvitationException("Invalid URI scheme or prefix, expected to start with '$URI_PREFIX'")
        }

        val base64Payload = rawUri.substring(URI_PREFIX.length)
        if (base64Payload.isEmpty()) {
            throw InvitationException("URI payload is empty")
        }
        if (base64Payload.contains('=')) {
            throw InvitationException("Padding character '=' is strictly forbidden in unpadded base64url")
        }
        if (!BASE64URL_REGEX.matches(base64Payload)) {
            throw InvitationException("Invalid characters found in base64url payload")
        }

        val decodedBytes = try {
            Base64.getUrlDecoder().decode(base64Payload)
        } catch (e: Exception) {
            throw InvitationException("Failed to decode base64url payload", e)
        }

        if (decodedBytes.size > MAX_JSON_BYTES) {
            throw InvitationException("Decoded JSON size ${decodedBytes.size} exceeds maximum $MAX_JSON_BYTES bytes")
        }

        // Strict UTF-8 validation
        val jsonText = try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(decodedBytes)).toString()
        } catch (e: CharacterCodingException) {
            throw InvitationException("Invalid UTF-8 byte sequence in payload", e)
        }

        val computedHash = sha256Hex(decodedBytes)

        // Strict syntactic depth and duplicate-key validation
        verifyJsonDepthAndDuplicateKeys(jsonText)

        // Parse JSON AST
        val element: JsonElement = try {
            strictJson.parseToJsonElement(jsonText)
        } catch (e: Exception) {
            throw InvitationException("Malformed JSON syntax in payload: ${e.message}", e)
        }

        if (element !is JsonObject) {
            throw InvitationException("Root JSON element must be an object")
        }

        // Verify root object keys
        val actualKeys = element.keys
        val unknownKeys = actualKeys - EXPECTED_ROOT_KEYS
        if (unknownKeys.isNotEmpty()) {
            throw InvitationException("Unknown keys in invitation JSON: $unknownKeys")
        }
        val missingKeys = EXPECTED_ROOT_KEYS - actualKeys
        if (missingKeys.isNotEmpty()) {
            throw InvitationException("Missing required keys in invitation JSON: $missingKeys")
        }

        try {
            val vPrimitive = element.getValue("v").jsonPrimitive
            val v = vPrimitive.intOrNull ?: throw InvitationException("'v' must be an integer")

            val transportPrimitive = element.getValue("transport").jsonPrimitive
            if (transportPrimitive.isString.not()) throw InvitationException("'transport' must be a string")
            val transport = transportPrimitive.content

            val profilePrimitive = element.getValue("profile").jsonPrimitive
            val profile = profilePrimitive.intOrNull ?: throw InvitationException("'profile' must be an integer")

            val protocolPrimitive = element.getValue("protocol").jsonPrimitive
            val protocol = protocolPrimitive.intOrNull ?: throw InvitationException("'protocol' must be an integer")

            val invitationIdPrimitive = element.getValue("invitationId").jsonPrimitive
            if (invitationIdPrimitive.isString.not()) throw InvitationException("'invitationId' must be a string")
            val invitationId = invitationIdPrimitive.content

            val presencePrimitive = element.getValue("presence").jsonPrimitive
            if (presencePrimitive.isString.not()) throw InvitationException("'presence' must be a string")
            val presence = presencePrimitive.content

            val namePrimitive = element.getValue("name").jsonPrimitive
            if (namePrimitive.isString.not()) throw InvitationException("'name' must be a string")
            val name = namePrimitive.content

            val platformPrimitive = element.getValue("platform").jsonPrimitive
            if (platformPrimitive.isString.not()) throw InvitationException("'platform' must be a string")
            val platform = platformPrimitive.content

            val endpointsElement = element.getValue("endpoints")
            if (endpointsElement !is JsonArray) throw InvitationException("'endpoints' must be an array")
            val endpointsArray = endpointsElement.jsonArray

            val endpointList = mutableListOf<LanEndpoint>()
            for ((index, item) in endpointsArray.withIndex()) {
                if (item !is JsonObject) throw InvitationException("Endpoint at index $index must be an object")
                val epKeys = item.keys
                val epUnknown = epKeys - EXPECTED_ENDPOINT_KEYS
                if (epUnknown.isNotEmpty()) {
                    throw InvitationException("Unknown keys in endpoint at index $index: $epUnknown")
                }
                val epMissing = EXPECTED_ENDPOINT_KEYS - epKeys
                if (epMissing.isNotEmpty()) {
                    throw InvitationException("Missing required keys in endpoint at index $index: $epMissing")
                }

                val addrPrimitive = item.getValue("address").jsonPrimitive
                if (addrPrimitive.isString.not()) throw InvitationException("Endpoint address must be a string")
                val address = addrPrimitive.content

                val portPrimitive = item.getValue("port").jsonPrimitive
                val port = portPrimitive.intOrNull ?: throw InvitationException("Endpoint port must be an integer")

                endpointList.add(LanEndpoint(address = address, port = port))
            }

            val spkiSha256Primitive = element.getValue("spkiSha256").jsonPrimitive
            if (spkiSha256Primitive.isString.not()) throw InvitationException("'spkiSha256' must be a string")
            val spkiSha256 = spkiSha256Primitive.content

            val tokenPrimitive = element.getValue("token").jsonPrimitive
            if (tokenPrimitive.isString.not()) throw InvitationException("'token' must be a string")
            val token = tokenPrimitive.content

            val expiresAtPrimitive = element.getValue("expiresAt").jsonPrimitive
            if (expiresAtPrimitive.isString.not()) throw InvitationException("'expiresAt' must be a string")
            val expiresAt = expiresAtPrimitive.content

            val ttlSecondsPrimitive = element.getValue("ttlSeconds").jsonPrimitive
            val ttlSeconds = ttlSecondsPrimitive.intOrNull ?: throw InvitationException("'ttlSeconds' must be an integer")

            return LanInvitation(
                v = v,
                transport = transport,
                profile = profile,
                protocol = protocol,
                invitationId = invitationId,
                presence = presence,
                name = name,
                platform = platform,
                endpoints = endpointList,
                spkiSha256 = spkiSha256,
                token = token,
                expiresAt = expiresAt,
                ttlSeconds = ttlSeconds,
                invitationHash = computedHash
            )
        } catch (e: InvitationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw InvitationException("Invitation domain validation failed: ${e.message}", e)
        } catch (e: Exception) {
            throw InvitationException("Failed to parse invitation JSON fields: ${e.message}", e)
        }
    }

    /**
     * Decodes and strictly validates a Profile 2 pair-only QR/link URI (`glide://pair/v2#...`) into a [LanPairV2Invitation].
     */
    fun decodePairV2(rawUri: String): LanPairV2Invitation {
        if (rawUri.length > MAX_URI_CHARS) {
            throw InvitationException("URI length ${rawUri.length} exceeds maximum $MAX_URI_CHARS characters")
        }
        if (!rawUri.startsWith(URI_PREFIX_V2)) {
            throw InvitationException("Invalid URI scheme or prefix, expected to start with '$URI_PREFIX_V2'")
        }

        val base64Payload = rawUri.substring(URI_PREFIX_V2.length)
        if (base64Payload.isEmpty()) {
            throw InvitationException("URI payload is empty")
        }
        if (base64Payload.contains('=')) {
            throw InvitationException("Padding character '=' is strictly forbidden in unpadded base64url")
        }
        if (!BASE64URL_REGEX.matches(base64Payload)) {
            throw InvitationException("Invalid characters found in base64url payload")
        }

        val decodedBytes = try {
            Base64.getUrlDecoder().decode(base64Payload)
        } catch (e: Exception) {
            throw InvitationException("Failed to decode base64url payload", e)
        }

        val reencodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(decodedBytes)
        if (reencodedPayload != base64Payload) {
            throw InvitationException("Base64url payload is not canonically encoded")
        }

        if (decodedBytes.size > MAX_JSON_BYTES) {
            throw InvitationException("Decoded JSON size ${decodedBytes.size} exceeds maximum $MAX_JSON_BYTES bytes")
        }

        val jsonText = try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(decodedBytes)).toString()
        } catch (e: CharacterCodingException) {
            throw InvitationException("Invalid UTF-8 byte sequence in payload", e)
        }

        val computedHash = sha256Hex(decodedBytes)
        verifyJsonDepthAndDuplicateKeys(jsonText)

        val element: JsonElement = try {
            strictJson.parseToJsonElement(jsonText)
        } catch (e: Exception) {
            throw InvitationException("Malformed JSON syntax in payload: ${e.message}", e)
        }

        if (element !is JsonObject) {
            throw InvitationException("Root JSON element must be an object")
        }

        val actualKeys = element.keys
        val unknownKeys = actualKeys - EXPECTED_V2_ROOT_KEYS
        if (unknownKeys.isNotEmpty()) {
            throw InvitationException("Unknown keys in v2 invitation JSON: $unknownKeys")
        }
        val missingKeys = EXPECTED_V2_ROOT_KEYS - actualKeys
        if (missingKeys.isNotEmpty()) {
            throw InvitationException("Missing required keys in v2 invitation JSON: $missingKeys")
        }

        try {
            val v = element.getValue("v").jsonPrimitive.intOrNull
                ?: throw InvitationException("'v' must be an integer")

            val purposePrimitive = element.getValue("purpose").jsonPrimitive
            if (purposePrimitive.isString.not()) throw InvitationException("'purpose' must be a string")
            val purpose = purposePrimitive.content

            val transportPrimitive = element.getValue("transport").jsonPrimitive
            if (transportPrimitive.isString.not()) throw InvitationException("'transport' must be a string")
            val transport = transportPrimitive.content

            val profile = element.getValue("profile").jsonPrimitive.intOrNull
                ?: throw InvitationException("'profile' must be an integer")

            val protocol = element.getValue("protocol").jsonPrimitive.intOrNull
                ?: throw InvitationException("'protocol' must be an integer")

            val invitationIdPrimitive = element.getValue("invitationId").jsonPrimitive
            if (invitationIdPrimitive.isString.not()) throw InvitationException("'invitationId' must be a string")
            val invitationId = invitationIdPrimitive.content

            val presencePrimitive = element.getValue("presence").jsonPrimitive
            if (presencePrimitive.isString.not()) throw InvitationException("'presence' must be a string")
            val presence = presencePrimitive.content

            val namePrimitive = element.getValue("name").jsonPrimitive
            if (namePrimitive.isString.not()) throw InvitationException("'name' must be a string")
            val name = namePrimitive.content

            val platformPrimitive = element.getValue("platform").jsonPrimitive
            if (platformPrimitive.isString.not()) throw InvitationException("'platform' must be a string")
            val platform = platformPrimitive.content

            val endpointsElement = element.getValue("endpoints")
            if (endpointsElement !is JsonArray) throw InvitationException("'endpoints' must be an array")
            val endpointsArray = endpointsElement.jsonArray

            val endpointList = mutableListOf<LanEndpoint>()
            for ((index, item) in endpointsArray.withIndex()) {
                if (item !is JsonObject) throw InvitationException("Endpoint at index $index must be an object")
                val epKeys = item.keys
                val epUnknown = epKeys - EXPECTED_ENDPOINT_KEYS
                if (epUnknown.isNotEmpty()) {
                    throw InvitationException("Unknown keys in endpoint at index $index: $epUnknown")
                }
                val epMissing = EXPECTED_ENDPOINT_KEYS - epKeys
                if (epMissing.isNotEmpty()) {
                    throw InvitationException("Missing required keys in endpoint at index $index: $epMissing")
                }

                val addrPrimitive = item.getValue("address").jsonPrimitive
                if (addrPrimitive.isString.not()) throw InvitationException("Endpoint address must be a string")
                val address = addrPrimitive.content

                val port = item.getValue("port").jsonPrimitive.intOrNull
                    ?: throw InvitationException("Endpoint port must be an integer")

                endpointList.add(LanEndpoint(address = address, port = port))
            }

            val spkiSha256Primitive = element.getValue("spkiSha256").jsonPrimitive
            if (spkiSha256Primitive.isString.not()) throw InvitationException("'spkiSha256' must be a string")
            val spkiSha256 = spkiSha256Primitive.content

            val tokenPrimitive = element.getValue("token").jsonPrimitive
            if (tokenPrimitive.isString.not()) throw InvitationException("'token' must be a string")
            val token = tokenPrimitive.content

            val expiresAtPrimitive = element.getValue("expiresAt").jsonPrimitive
            if (expiresAtPrimitive.isString.not()) throw InvitationException("'expiresAt' must be a string")
            val expiresAt = expiresAtPrimitive.content

            val ttlSeconds = element.getValue("ttlSeconds").jsonPrimitive.intOrNull
                ?: throw InvitationException("'ttlSeconds' must be an integer")

            return LanPairV2Invitation(
                v = v,
                purpose = purpose,
                transport = transport,
                profile = profile,
                protocol = protocol,
                invitationId = invitationId,
                presence = presence,
                name = name,
                platform = platform,
                endpoints = endpointList,
                spkiSha256 = spkiSha256,
                token = token,
                expiresAt = expiresAt,
                ttlSeconds = ttlSeconds,
                invitationHash = computedHash
            )
        } catch (e: InvitationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw InvitationException("Pair v2 invitation domain validation failed: ${e.message}", e)
        } catch (e: Exception) {
            throw InvitationException("Failed to parse v2 invitation JSON fields: ${e.message}", e)
        }
    }

    /**
     * Verifies that JSON nesting depth does not exceed [MAX_JSON_DEPTH]
     * and that no object scope contains duplicate keys.
     */
    private fun verifyJsonDepthAndDuplicateKeys(jsonText: String) {
        var depth = 0
        var inString = false
        var isEscaped = false

        // Stack of key sets for checking duplicates at each nested object level
        val objectKeySets = mutableListOf<MutableSet<String>>()
        var expectingKey = false
        val currentKeyBuilder = StringBuilder()
        var collectingKey = false

        for (i in jsonText.indices) {
            val c = jsonText[i]

            if (inString) {
                if (isEscaped) {
                    if (collectingKey) currentKeyBuilder.append(c)
                    isEscaped = false
                } else if (c == '\\') {
                    isEscaped = true
                } else if (c == '"') {
                    inString = false
                    if (collectingKey) {
                        collectingKey = false
                        val key = currentKeyBuilder.toString()
                        val currentScope = objectKeySets.lastOrNull()
                        if (currentScope != null) {
                            if (!currentScope.add(key)) {
                                throw InvitationException("Duplicate key '$key' found in JSON object")
                            }
                        }
                    }
                } else {
                    if (collectingKey) currentKeyBuilder.append(c)
                }
            } else {
                when (c) {
                    '"' -> {
                        inString = true
                        if (expectingKey) {
                            collectingKey = true
                            currentKeyBuilder.setLength(0)
                            expectingKey = false
                        }
                    }
                    '{' -> {
                        depth++
                        if (depth > MAX_JSON_DEPTH) {
                            throw InvitationException("JSON nesting depth $depth exceeds maximum $MAX_JSON_DEPTH")
                        }
                        objectKeySets.add(mutableSetOf())
                        expectingKey = true
                    }
                    '}' -> {
                        depth--
                        if (objectKeySets.isNotEmpty()) {
                            objectKeySets.removeAt(objectKeySets.size - 1)
                        }
                        expectingKey = false
                    }
                    '[' -> {
                        depth++
                        if (depth > MAX_JSON_DEPTH) {
                            throw InvitationException("JSON nesting depth $depth exceeds maximum $MAX_JSON_DEPTH")
                        }
                    }
                    ']' -> {
                        depth--
                    }
                    ':' -> {
                        // Value follows key
                        expectingKey = false
                    }
                    ',' -> {
                        // In an object scope, next element is expected to be a key
                        // Check if immediate parent is an object
                        if (objectKeySets.isNotEmpty()) {
                            expectingKey = true
                        }
                    }
                }
            }
        }

        if (depth != 0) {
            throw InvitationException("Unbalanced JSON structure")
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b.toInt() and 0xff))
        }
        return sb.toString()
    }
}
