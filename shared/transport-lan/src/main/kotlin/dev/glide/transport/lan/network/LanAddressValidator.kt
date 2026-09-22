package dev.glide.transport.lan.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Local network subnet prefix representation for on-link calculation (LAN-009, Section 11).
 */
data class SubnetPrefix(
    val address: InetAddress,
    val prefixLength: Int
) {
    init {
        require(prefixLength in 0..maxPrefixLength) {
            "Prefix length $prefixLength out of bounds (0..$maxPrefixLength) for ${address.javaClass.simpleName}"
        }
    }

    val maxPrefixLength: Int
        get() = if (address is Inet4Address) 32 else 128

    /**
     * Checks if the given target address is on-link within this subnet prefix.
     */
    fun matches(target: InetAddress): Boolean {
        if (address.javaClass != target.javaClass) return false

        val addrBytes = address.address
        val targetBytes = target.address

        val fullBytes = prefixLength / 8
        for (i in 0 until fullBytes) {
            if (addrBytes[i] != targetBytes[i]) return false
        }

        val remainingBits = prefixLength % 8
        if (remainingBits > 0) {
            val mask = ((0xFF shl (8 - remainingBits)) and 0xFF).toByte()
            if ((addrBytes[fullBytes].toInt() and mask.toInt()) != (targetBytes[fullBytes].toInt() and mask.toInt())) {
                return false
            }
        }

        return true
    }
}

/**
 * Address rejection taxonomy for LAN connections (LAN-009, Section 11, Section 20).
 */
enum class LanAddressRejectionCode {
    INVALID_IP_LITERAL,
    LOOPBACK_REJECTED,
    MULTICAST_REJECTED,
    IPV4_MAPPED_IPV6_REJECTED,
    UNSPECIFIED_OR_BROADCAST_REJECTED,
    OFF_LINK_REJECTED
}

sealed interface LanAddressValidationResult {
    data object Valid : LanAddressValidationResult
    data class Rejected(val code: LanAddressRejectionCode, val reason: String) : LanAddressValidationResult {
        val isRejected: Boolean get() = true
    }
}

/**
 * Pure on-link address validator enforcing Section 11 and Section 18 network security invariants:
 * - Rejects non-numeric hostnames, URLs, URIs, credentials, percent-zones.
 * - Rejects loopback (127.0.0.0/8, ::1) in production.
 * - Rejects multicast (224.0.0.0/4, ff00::/8).
 * - Rejects IPv4-mapped IPv6 (::ffff:x.x.x.x).
 * - Rejects wildcard / broadcast (0.0.0.0, 255.255.255.255, ::).
 * - Enforces on-link subnet containment against active link prefixes.
 */
object LanAddressValidator {

    private val IPV4_NUMERIC_REGEX = Regex("""^(?:(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9][0-9]|[0-9])\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9][0-9]|[0-9])$""")

    /**
     * Verifies that a string is a strict numeric IPv4 or IPv6 literal without DNS,
     * hostnames, port, URI schemes, or percent-zones.
     */
    fun isNumericIpLiteral(rawAddress: String): Boolean {
        if (rawAddress.isBlank()) return false
        if (rawAddress.contains("/") || rawAddress.contains("%") || rawAddress.contains("@")) {
            return false // Disallow zones, CIDR, credentials
        }

        // Fast path for strict decimal IPv4: no colons, exactly 4 octets without leading zeros.
        // Guarantees zero DNS resolution for non-IP hostnames (Section 20).
        if (!rawAddress.contains(":")) {
            return IPV4_NUMERIC_REGEX.matches(rawAddress)
        }

        // Test IPv6 literal: must contain only hex chars, colons, or dots (for embedded v4)
        if (rawAddress.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' && it != ':' && it != '.' }) {
            return false
        }

        return try {
            val addr = InetAddress.getByName(rawAddress)
            addr is Inet6Address || (addr is Inet4Address && rawAddress.contains(":"))
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Validates whether an address string is an acceptable on-link destination or peer.
     */
    fun validateAddress(
        address: InetAddress,
        activeSubnets: List<SubnetPrefix>,
        allowLoopbackForTesting: Boolean = false
    ): LanAddressValidationResult {
        val host = address.hostAddress ?: return LanAddressValidationResult.Rejected(
            LanAddressRejectionCode.INVALID_IP_LITERAL,
            "Null host address"
        )
        return validateAddress(host, activeSubnets, allowLoopbackForTesting)
    }

    /**
     * Validates whether an address string is an acceptable on-link destination or peer.
     */
    fun validateAddress(
        rawAddress: String,
        activeSubnets: List<SubnetPrefix>,
        allowLoopbackForTesting: Boolean = false
    ): LanAddressValidationResult {
        if (!isNumericIpLiteral(rawAddress)) {
            return LanAddressValidationResult.Rejected(
                LanAddressRejectionCode.INVALID_IP_LITERAL,
                "Address '$rawAddress' is not a valid numeric IP literal; DNS hostnames and percent-zones are prohibited"
            )
        }

        val lower = rawAddress.lowercase()
        val isMapped = lower.contains("::ffff:") ||
            lower.contains(":ffff:") ||
            lower.startsWith("::ffff:") ||
            (lower.contains(":") && lower.contains("."))
        if (isMapped) {
            return LanAddressValidationResult.Rejected(
                LanAddressRejectionCode.IPV4_MAPPED_IPV6_REJECTED,
                "IPv4-mapped IPv6 address '$rawAddress' is prohibited; use native IPv4 or IPv6 literals"
            )
        }

        val inetAddress = try {
            InetAddress.getByName(rawAddress)
        } catch (e: Exception) {
            return LanAddressValidationResult.Rejected(
                LanAddressRejectionCode.INVALID_IP_LITERAL,
                "Failed to parse IP address '$rawAddress': ${e.message}"
            )
        }

        // 1. Loopback check
        if (inetAddress.isLoopbackAddress) {
            if (!allowLoopbackForTesting) {
                return LanAddressValidationResult.Rejected(
                    LanAddressRejectionCode.LOOPBACK_REJECTED,
                    "Loopback address '$rawAddress' is strictly prohibited in production"
                )
            }
        }

        // 2. Multicast check
        if (inetAddress.isMulticastAddress) {
            return LanAddressValidationResult.Rejected(
                LanAddressRejectionCode.MULTICAST_REJECTED,
                "Multicast address '$rawAddress' is prohibited"
            )
        }

        // 3. Unspecified or broadcast check
        if (inetAddress.isAnyLocalAddress || isDirectedBroadcast(inetAddress)) {
            return LanAddressValidationResult.Rejected(
                LanAddressRejectionCode.UNSPECIFIED_OR_BROADCAST_REJECTED,
                "Unspecified or broadcast address '$rawAddress' is prohibited"
            )
        }

        // 4. IPv4-mapped IPv6 check (::ffff:x.x.x.x)
        if (isIpv4MappedIpv6(inetAddress)) {
            return LanAddressValidationResult.Rejected(
                LanAddressRejectionCode.IPV4_MAPPED_IPV6_REJECTED,
                "IPv4-mapped IPv6 address '$rawAddress' is prohibited; use native IPv4 or IPv6 literals"
            )
        }

        // 5. On-link subnet check
        if (!allowLoopbackForTesting || !inetAddress.isLoopbackAddress) {
            if (activeSubnets.isEmpty()) {
                return LanAddressValidationResult.Rejected(
                    LanAddressRejectionCode.OFF_LINK_REJECTED,
                    "No active local subnets available to verify on-link destination '$rawAddress'"
                )
            }

            val onLink = activeSubnets.any { it.matches(inetAddress) }
            if (!onLink) {
                return LanAddressValidationResult.Rejected(
                    LanAddressRejectionCode.OFF_LINK_REJECTED,
                    "Destination '$rawAddress' is off-link; does not match any active local network prefix"
                )
            }
        }

        return LanAddressValidationResult.Valid
    }

    private fun isDirectedBroadcast(address: InetAddress): Boolean {
        if (address is Inet4Address) {
            val bytes = address.address
            return bytes[0] == 255.toByte() && bytes[1] == 255.toByte() &&
                bytes[2] == 255.toByte() && bytes[3] == 255.toByte()
        }
        return false
    }

    private fun isIpv4MappedIpv6(address: InetAddress): Boolean {
        if (address is Inet6Address) {
            val bytes = address.address
            // ::ffff:x.x.x.x has 10 zero bytes, followed by 2 0xFF bytes, followed by 4 IPv4 bytes
            for (i in 0 until 10) {
                if (bytes[i] != 0.toByte()) return false
            }
            return bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
        }
        return false
    }
}
