package dev.glide.core.pairing

import dev.glide.core.domain.isValidSha256
import dev.glide.core.domain.isValidUuidV4
import java.nio.charset.StandardCharsets

/**
 * Numeric IP and port endpoint for LAN direct connection (LAN-006, Section 8).
 *
 * Enforces numeric-only IPv4/IPv6 literals without hostnames, schemes, paths, or percent-zones.
 */
data class LanEndpoint(
    val address: String,
    val port: Int
) {
    init {
        require(isValidAddress(address)) {
            "Endpoint address must be a valid numeric IPv4 or IPv6 literal: '$address'"
        }
        require(port in 1..65535) {
            "Endpoint port must be in range 1..65535, got: $port"
        }
    }

    companion object {
        private val IPV4_REGEX = Regex("^(?:(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])$")

        fun isValidAddress(addr: String): Boolean {
            if (addr.isBlank() || addr.contains('/') || addr.contains('%') || addr.contains(' ') || addr.contains("://")) {
                return false
            }
            if (IPV4_REGEX.matches(addr)) {
                val parts = addr.split('.')
                for (part in parts) {
                    if (part.length > 1 && part.startsWith('0')) return false
                }
                return true
            }
            return isValidIpv6(addr)
        }

        private fun isValidIpv6(addr: String): Boolean {
            if (!addr.contains(':')) return false
            if (addr.startsWith(":") && !addr.startsWith("::")) return false
            if (addr.endsWith(":") && !addr.endsWith("::")) return false
            if (addr.count { it == ':' } > 7 && !addr.contains("::")) return false
            if (addr.indexOf("::") != addr.lastIndexOf("::")) return false

            val parts = addr.split(':')
            for (p in parts) {
                if (p.isEmpty()) continue
                if (p.length > 4) return false
                for (c in p) {
                    if (c !in '0'..'9' && c !in 'a'..'f' && c !in 'A'..'F') return false
                }
            }
            return true
        }
    }
}

/**
 * Immutable validated QR pairing invitation representation (LAN-006, Section 8).
 *
 * Implements strict Section-8 schema constraints, zero Android framework imports,
 * and redacts the 32-byte shared [token] from [toString] to avoid secret leakage.
 */
data class LanInvitation(
    val v: Int = 1,
    val transport: String = "lan-tls",
    val profile: Int = 1,
    val protocol: Int = 1,
    val invitationId: String,
    val presence: String,
    val name: String,
    val platform: String,
    val endpoints: List<LanEndpoint>,
    val spkiSha256: String,
    val token: String,
    val expiresAt: String,
    val ttlSeconds: Int = 120,
    val invitationHash: String = ""
) {
    init {
        require(v == 1) { "Unsupported invitation version $v, expected 1" }
        require(transport == "lan-tls") { "Unsupported transport '$transport', expected 'lan-tls'" }
        require(profile == 1) { "Unsupported LAN profile $profile, expected 1" }
        require(protocol == 1) { "Unsupported protocol version $protocol, expected 1" }
        require(isValidUuidV4(invitationId)) { "invitationId must be a canonical lowercase UUIDv4, got: '$invitationId'" }
        require(PRESENCE_REGEX.matches(presence)) { "presence must be 32 lowercase hex characters, got: '$presence'" }
        require(name.isNotBlank()) { "name must not be blank" }
        require(name.codePointCount(0, name.length) <= MAX_NAME_CODE_POINTS) {
            "name exceeds maximum $MAX_NAME_CODE_POINTS code points"
        }
        require(name.toByteArray(StandardCharsets.UTF_8).size <= MAX_NAME_UTF8_BYTES) {
            "name exceeds maximum $MAX_NAME_UTF8_BYTES UTF-8 bytes"
        }
        require(platform in ALLOWED_PLATFORMS) {
            "platform '$platform' is not allowed, must be one of $ALLOWED_PLATFORMS"
        }
        require(endpoints.isNotEmpty() && endpoints.size <= MAX_ENDPOINTS) {
            "endpoints must contain 1..$MAX_ENDPOINTS items, got: ${endpoints.size}"
        }
        require(endpoints.distinct().size == endpoints.size) {
            "endpoints must be unique, duplicate found"
        }
        require(isValidSha256(spkiSha256)) {
            "spkiSha256 must be 64 lowercase hex characters, got: '$spkiSha256'"
        }
        require(TOKEN_REGEX.matches(token)) {
            "token must be 43 unpadded base64url characters encoding 32 bytes, got: '$token'"
        }
        require(EXPIRES_AT_REGEX.matches(expiresAt)) {
            "expiresAt must be a positive decimal epoch seconds string, got: '$expiresAt'"
        }
        require(ttlSeconds > 0) { "ttlSeconds must be positive, got: $ttlSeconds" }
        require(invitationHash.isEmpty() || isValidSha256(invitationHash)) {
            "invitationHash must be 64 lowercase hex characters if present, got: '$invitationHash'"
        }
    }

    /**
     * Redacts the shared secret token to prevent accidental logging or exposure.
     */
    override fun toString(): String {
        return "LanInvitation(v=$v, transport='$transport', profile=$profile, protocol=$protocol, " +
            "invitationId='$invitationId', presence='$presence', name='$name', platform='$platform', " +
            "endpoints=$endpoints, spkiSha256='$spkiSha256', token=[REDACTED], expiresAt='$expiresAt', " +
            "ttlSeconds=$ttlSeconds, invitationHash='$invitationHash')"
    }

    companion object {
        const val MAX_NAME_CODE_POINTS = 64
        const val MAX_NAME_UTF8_BYTES = 128
        const val MAX_ENDPOINTS = 4

        val ALLOWED_PLATFORMS = setOf("android", "windows", "macos", "ios", "linux", "web", "unknown")
        val PRESENCE_REGEX = Regex("^[0-9a-f]{32}$")
        val TOKEN_REGEX = Regex("^[A-Za-z0-9_-]{43}$")
        val EXPIRES_AT_REGEX = Regex("^[1-9][0-9]*$")
    }
}
