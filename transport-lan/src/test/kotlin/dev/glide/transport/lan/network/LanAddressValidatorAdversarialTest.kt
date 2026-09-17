package dev.glide.transport.lan.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Adversarial negative security tests for [LanAddressValidator] enforcing Section 20 Requirement 3:
 * "Unknown network destination: rejected before TCP connect; test loopback, multicast, IPv4-mapped IPv6,
 * textual alternate encodings and off-link IPv6. Parser must not invoke DNS to interpret a numeric literal."
 */
class LanAddressValidatorAdversarialTest {

    private val localSubnetV4 = listOf(
        SubnetPrefix(InetAddress.getByName("192.168.1.100"), 24)
    )
    private val localSubnetV6 = listOf(
        SubnetPrefix(InetAddress.getByName("2001:db8:acad:1::1"), 64)
    )

    @Test
    fun testTextualAlternateEncodings_strictlyRejected() {
        // Octal IPv4
        val octalAddresses = listOf(
            "0177.0.0.1",
            "010.0.0.1",
            "192.168.01.1",
            "192.168.001.1"
        )
        for (addr in octalAddresses) {
            assertFalse("Octal address '$addr' must not be recognized as numeric IP literal", LanAddressValidator.isNumericIpLiteral(addr))
            val result = LanAddressValidator.validateAddress(addr, localSubnetV4)
            assertTrue("Octal address '$addr' must be rejected", result is LanAddressValidationResult.Rejected)
            assertEquals(LanAddressRejectionCode.INVALID_IP_LITERAL, (result as LanAddressValidationResult.Rejected).code)
        }

        // Hexadecimal IPv4
        val hexAddresses = listOf(
            "0x7f.0.0.1",
            "0x7f000001",
            "0xc0.0xa8.0x01.0x01"
        )
        for (addr in hexAddresses) {
            assertFalse("Hex address '$addr' must not be recognized as numeric IP literal", LanAddressValidator.isNumericIpLiteral(addr))
            val result = LanAddressValidator.validateAddress(addr, localSubnetV4)
            assertTrue("Hex address '$addr' must be rejected", result is LanAddressValidationResult.Rejected)
            assertEquals(LanAddressRejectionCode.INVALID_IP_LITERAL, (result as LanAddressValidationResult.Rejected).code)
        }

        // 32-bit decimal integer
        val dwordAddresses = listOf(
            "2130706433", // 127.0.0.1
            "3232235777"  // 192.168.1.1
        )
        for (addr in dwordAddresses) {
            assertFalse("Dword integer address '$addr' must not be recognized as numeric IP literal", LanAddressValidator.isNumericIpLiteral(addr))
            val result = LanAddressValidator.validateAddress(addr, localSubnetV4)
            assertTrue("Dword address '$addr' must be rejected", result is LanAddressValidationResult.Rejected)
            assertEquals(LanAddressRejectionCode.INVALID_IP_LITERAL, (result as LanAddressValidationResult.Rejected).code)
        }

        // Shortened dotted forms
        val shortAddresses = listOf(
            "127.1",
            "10.1",
            "192.168.1"
        )
        for (addr in shortAddresses) {
            assertFalse("Shortened address '$addr' must not be recognized as numeric IP literal", LanAddressValidator.isNumericIpLiteral(addr))
            val result = LanAddressValidator.validateAddress(addr, localSubnetV4)
            assertTrue("Shortened address '$addr' must be rejected", result is LanAddressValidationResult.Rejected)
            assertEquals(LanAddressRejectionCode.INVALID_IP_LITERAL, (result as LanAddressValidationResult.Rejected).code)
        }
    }

    @Test
    fun testHostnamesDoNotTriggerDnsAndAreRejected() {
        // Must reject without invoking DNS resolution
        val hostnames = listOf(
            "example.com",
            "evil-attacker.internal",
            "device.local",
            "localhost",
            "my-printer._tcp.local"
        )
        for (host in hostnames) {
            assertFalse("Hostname '$host' must be rejected without DNS lookup", LanAddressValidator.isNumericIpLiteral(host))
            val result = LanAddressValidator.validateAddress(host, localSubnetV4)
            assertTrue("Hostname '$host' must be rejected", result is LanAddressValidationResult.Rejected)
            assertEquals(LanAddressRejectionCode.INVALID_IP_LITERAL, (result as LanAddressValidationResult.Rejected).code)
        }
    }

    @Test
    fun testIpv4MappedIpv6Variants_strictlyRejected() {
        val mappedVariants = listOf(
            "::ffff:192.168.1.1",
            "::ffff:127.0.0.1",
            "::ffff:c0a8:0101",
            "0:0:0:0:0:ffff:192.168.1.1",
            "0000:0000:0000:0000:0000:ffff:192.168.1.1"
        )
        for (addr in mappedVariants) {
            val result = LanAddressValidator.validateAddress(addr, localSubnetV4)
            assertTrue("IPv4-mapped address '$addr' must be rejected", result is LanAddressValidationResult.Rejected)
            assertEquals(
                "Expected IPV4_MAPPED_IPV6_REJECTED for $addr",
                LanAddressRejectionCode.IPV4_MAPPED_IPV6_REJECTED,
                (result as LanAddressValidationResult.Rejected).code
            )
        }
    }

    @Test
    fun testProductionLoopbackRejection() {
        val loopbacks = listOf(
            "127.0.0.1",
            "127.0.0.2",
            "127.255.255.254",
            "::1",
            "0:0:0:0:0:0:0:1"
        )
        for (addr in loopbacks) {
            val result = LanAddressValidator.validateAddress(addr, localSubnetV4, allowLoopbackForTesting = false)
            assertTrue("Loopback address '$addr' must be rejected in production", result is LanAddressValidationResult.Rejected)
            assertEquals(LanAddressRejectionCode.LOOPBACK_REJECTED, (result as LanAddressValidationResult.Rejected).code)
        }
    }

    @Test
    fun testMulticastRejection() {
        val multicasts = listOf(
            "224.0.0.1",
            "224.0.0.251", // mDNS multicast
            "239.255.255.250", // SSDP
            "ff02::1",
            "ff02::fb", // mDNS IPv6
            "ff05::2"
        )
        for (addr in multicasts) {
            val result = LanAddressValidator.validateAddress(addr, localSubnetV4)
            assertTrue("Multicast address '$addr' must be rejected", result is LanAddressValidationResult.Rejected)
            assertEquals(LanAddressRejectionCode.MULTICAST_REJECTED, (result as LanAddressValidationResult.Rejected).code)
        }
    }

    @Test
    fun testBroadcastAndUnspecifiedRejection() {
        val invalidAddrs = listOf(
            "0.0.0.0",
            "255.255.255.255",
            "::",
            "0:0:0:0:0:0:0:0"
        )
        for (addr in invalidAddrs) {
            val result = LanAddressValidator.validateAddress(addr, localSubnetV4)
            assertTrue("Address '$addr' must be rejected", result is LanAddressValidationResult.Rejected)
            assertEquals(LanAddressRejectionCode.UNSPECIFIED_OR_BROADCAST_REJECTED, (result as LanAddressValidationResult.Rejected).code)
        }
    }

    @Test
    fun testOffLinkAddressesRejection() {
        // Off-link IPv4
        val offLinkV4 = listOf(
            "192.168.2.1",
            "10.0.0.1",
            "172.16.0.1",
            "8.8.8.8"
        )
        for (addr in offLinkV4) {
            val result = LanAddressValidator.validateAddress(addr, localSubnetV4)
            assertTrue("Off-link IPv4 address '$addr' must be rejected", result is LanAddressValidationResult.Rejected)
            assertEquals(LanAddressRejectionCode.OFF_LINK_REJECTED, (result as LanAddressValidationResult.Rejected).code)
        }

        // Off-link IPv6
        val offLinkV6 = listOf(
            "2001:db8:acad:2::1", // Different /64 subnet
            "2607:f8b0:4005:805::200e" // Public Internet IPv6
        )
        for (addr in offLinkV6) {
            val result = LanAddressValidator.validateAddress(addr, localSubnetV6)
            assertTrue("Off-link IPv6 address '$addr' must be rejected", result is LanAddressValidationResult.Rejected)
            assertEquals(LanAddressRejectionCode.OFF_LINK_REJECTED, (result as LanAddressValidationResult.Rejected).code)
        }
    }

    @Test
    fun testForbiddenDecorations_zonesUrisAndCredentialsRejected() {
        val decorated = listOf(
            "fe80::1%wlan0",
            "fe80::1%12",
            "http://192.168.1.1",
            "https://192.168.1.1:42424",
            "user:password@192.168.1.1",
            "192.168.1.1/24",
            "192.168.1.1:5000",
            "[2001:db8::1]:5000"
        )
        for (addr in decorated) {
            assertFalse("Decorated address '$addr' must not be valid IP literal", LanAddressValidator.isNumericIpLiteral(addr))
            val result = LanAddressValidator.validateAddress(addr, localSubnetV4)
            assertTrue("Decorated address '$addr' must be rejected", result is LanAddressValidationResult.Rejected)
            assertEquals(LanAddressRejectionCode.INVALID_IP_LITERAL, (result as LanAddressValidationResult.Rejected).code)
        }
    }
}
