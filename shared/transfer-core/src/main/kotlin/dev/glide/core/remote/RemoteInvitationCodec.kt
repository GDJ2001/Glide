package dev.glide.core.remote

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * Thrown when a remote invitation violates URI format, size bounds, key constraints,
 * or expiration limits (ADR-0022, Section 2.1).
 */
class RemoteInvitationException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Strict canonical codec for Glide Remote Pairing invitations (ADR-0022, Section 2.1).
 *
 * Format: glide://remote/v1#id=<UUID>&key=<HEX>&sig=<URL_ENCODED_URI>&token=<BASE64URL>&exp=<TIMESTAMP>
 */
object RemoteInvitationCodec {

    const val URI_PREFIX = "glide://remote/v1#"
    const val MAX_URI_LENGTH = 2048

    private val REQUIRED_KEYS = setOf("id", "key", "sig", "token", "exp")

    private val UUID_REGEX = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        RegexOption.IGNORE_CASE
    )

    private val HEX_KEY_REGEX = Regex(
        "^[0-9a-f]{64}$",
        RegexOption.IGNORE_CASE
    )

    private val BASE64URL_REGEX = Regex("^[A-Za-z0-9_-]+$")

    fun encode(invitation: RemoteInvitation): String {
        if (!UUID_REGEX.matches(invitation.rendezvousId)) {
            throw RemoteInvitationException("Rendezvous ID '${invitation.rendezvousId}' is not a valid canonical UUIDv4.")
        }
        if (!HEX_KEY_REGEX.matches(invitation.ephemeralPublicKeyHex)) {
            throw RemoteInvitationException("Ephemeral public key must be exactly 64 hexadecimal characters.")
        }
        if (!BASE64URL_REGEX.matches(invitation.relayBlindToken)) {
            throw RemoteInvitationException("Relay blind token must be a valid Base64url string.")
        }
        if (invitation.expiresAt <= 0) {
            throw RemoteInvitationException("Expiration timestamp must be a positive integer.")
        }

        try {
            val parsed = URI(invitation.signalingEndpoint)
            if (parsed.scheme != "wss" && parsed.scheme != "ws") {
                throw RemoteInvitationException("Signaling endpoint '${invitation.signalingEndpoint}' must have ws:// or wss:// scheme.")
            }
        } catch (e: Exception) {
            throw RemoteInvitationException("Signaling endpoint '${invitation.signalingEndpoint}' is not a valid URI.", e)
        }

        val escapedSig = URLEncoder.encode(invitation.signalingEndpoint, StandardCharsets.UTF_8.name())
        val result = "${URI_PREFIX}id=${invitation.rendezvousId}&key=${invitation.ephemeralPublicKeyHex}&sig=$escapedSig&token=${invitation.relayBlindToken}&exp=${invitation.expiresAt}"

        if (result.length > MAX_URI_LENGTH) {
            throw RemoteInvitationException("Encoded remote invitation URI length (${result.length}) exceeds maximum $MAX_URI_LENGTH characters.")
        }

        return result
    }

    fun decode(
        uriString: String,
        checkExpiry: Boolean = true,
        currentEpochSeconds: Long = Instant.now().epochSecond
    ): RemoteInvitation {
        if (uriString.isBlank()) {
            throw RemoteInvitationException("Remote invitation URI cannot be null, empty, or whitespace.")
        }
        if (uriString.length > MAX_URI_LENGTH) {
            throw RemoteInvitationException("Remote invitation URI length (${uriString.length}) exceeds maximum $MAX_URI_LENGTH characters.")
        }
        if (!uriString.startsWith(URI_PREFIX)) {
            throw RemoteInvitationException("Remote invitation URI must start with '$URI_PREFIX'.")
        }

        val fragment = uriString.substring(URI_PREFIX.length)
        if (fragment.isBlank()) {
            throw RemoteInvitationException("Remote invitation URI fragment is empty.")
        }

        val pairs = fragment.split('&')
        val parsedParams = mutableMapOf<String, String>()

        for (pair in pairs) {
            val eqIdx = pair.indexOf('=')
            if (eqIdx <= 0 || eqIdx == pair.length - 1) {
                throw RemoteInvitationException("Malformed key-value pair in URI fragment: '$pair'")
            }
            val key = pair.substring(0, eqIdx)
            val value = pair.substring(eqIdx + 1)

            if (key !in REQUIRED_KEYS) {
                throw RemoteInvitationException("Unknown or unauthorized query parameter in remote URI: '$key'")
            }
            if (parsedParams.containsKey(key)) {
                throw RemoteInvitationException("Duplicate parameter in remote URI: '$key'")
            }
            parsedParams[key] = value
        }

        if (parsedParams.size != REQUIRED_KEYS.size) {
            val missing = REQUIRED_KEYS - parsedParams.keys
            throw RemoteInvitationException("Missing required parameters in remote URI: ${missing.joinToString(", ")}")
        }

        val id = parsedParams.getValue("id")
        if (!UUID_REGEX.matches(id)) {
            throw RemoteInvitationException("Invalid rendezvousId format: '$id' (must be canonical UUIDv4).")
        }

        val keyHex = parsedParams.getValue("key")
        if (!HEX_KEY_REGEX.matches(keyHex)) {
            throw RemoteInvitationException("Invalid ephemeralPublicKeyHex: '$keyHex' (must be 64 hex characters).")
        }

        val unescapedSig = try {
            URLDecoder.decode(parsedParams.getValue("sig"), StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            throw RemoteInvitationException("Failed to URL decode signaling endpoint: ${e.message}", e)
        }

        try {
            val parsed = URI(unescapedSig)
            if (parsed.scheme != "wss" && parsed.scheme != "ws") {
                throw RemoteInvitationException("Invalid signalingEndpoint URI: '$unescapedSig' (must be absolute ws:// or wss://).")
            }
        } catch (e: Exception) {
            throw RemoteInvitationException("Invalid signalingEndpoint URI: '$unescapedSig'.", e)
        }

        val token = parsedParams.getValue("token")
        if (!BASE64URL_REGEX.matches(token)) {
            throw RemoteInvitationException("Invalid relayBlindToken format: '$token' (must be Base64url).")
        }

        val expStr = parsedParams.getValue("exp")
        val expiresAt = expStr.toLongOrNull()
            ?: throw RemoteInvitationException("Invalid expiresAt timestamp: '$expStr'.")

        if (expiresAt <= 0) {
            throw RemoteInvitationException("Invalid expiresAt timestamp: '$expStr' must be positive.")
        }

        val invitation = RemoteInvitation(
            rendezvousId = id,
            ephemeralPublicKeyHex = keyHex,
            signalingEndpoint = unescapedSig,
            relayBlindToken = token,
            expiresAt = expiresAt
        )

        if (checkExpiry && invitation.isExpired(currentEpochSeconds)) {
            throw RemoteInvitationException("Remote invitation expired at Unix epoch $expiresAt.")
        }

        return invitation
    }
}
