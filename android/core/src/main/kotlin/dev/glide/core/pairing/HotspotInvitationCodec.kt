package dev.glide.core.pairing

import dev.glide.core.protocol.StrictJsonParser
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.Base64

class HotspotInvitation(val ssid: String, val passphrase: String, val pairing: LanInvitation) {
    init {
        require(ssid.isNotBlank() && ssid.none(Char::isISOControl) &&
            ssid.encodeToByteArray(throwOnInvalidSequence = true).size <= 32)
        require(passphrase.length in 8..63 && passphrase.all { it in ' '..'~' })
    }

    override fun toString(): String = "HotspotInvitation(credentials=redacted, pairing=redacted)"
}

object HotspotInvitationCodec {
    const val PREFIX = "glide://hotspot/v1#"
    private const val MAX_BYTES = 2048
    private val encoding = Base64.getUrlEncoder().withoutPadding()
    private val tokenPattern = Regex("[A-Za-z0-9_-]+")
    private val keys = setOf("v", "ssid", "psk", "pair")

    fun encode(invitation: HotspotInvitation): String {
        val json = buildJsonObject {
            put("v", 1)
            put("ssid", invitation.ssid)
            put("psk", invitation.passphrase)
            put("pair", InvitationCodec.encode(invitation.pairing))
        }.toString().encodeToByteArray(throwOnInvalidSequence = true)
        require(json.size <= MAX_BYTES)
        return PREFIX + encoding.encodeToString(json)
    }

    fun decode(value: String, now: Instant = Instant.now()): HotspotInvitation {
        require(value.startsWith(PREFIX) && value.length <= PREFIX.length + (MAX_BYTES * 4 + 2) / 3)
        val token = value.removePrefix(PREFIX)
        require(tokenPattern.matches(token))
        val bytes = Base64.getUrlDecoder().decode(token)
        require(bytes.size <= MAX_BYTES && encoding.encodeToString(bytes) == token)
        val json = StrictJsonParser(bytes.decodeToString(throwOnInvalidSequence = true), maxDepth = 2).parse()
        require(json.keys == keys)
        val version = json["v"] as? JsonPrimitive
        require(version != null && !version.isString && version.intOrNull == 1)
        fun field(key: String): String {
            val primitive = json[key] as? JsonPrimitive
            require(primitive != null && primitive.isString)
            return primitive.content
        }
        val pairing = InvitationCodec.decode(field("pair"))
        require(pairing.expiresAt.toLongOrNull()?.let { it > now.epochSecond } == true)
        return HotspotInvitation(field("ssid"), field("psk"), pairing)
    }
}
