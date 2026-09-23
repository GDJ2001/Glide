package dev.glide.transport.lan.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LanAddressValidatorTest {

    private val localSubnet24 = listOf(
        SubnetPrefix(InetAddress.getByName("192.168.1.50"), 24)
    )
    private val localSubnet16 = listOf(
        SubnetPrefix(InetAddress.getByName("10.0.1.1"), 16)
    )
    private val localSubnetIpv6 = listOf(
        SubnetPrefix(InetAddress.getByName("2001:db8:abcd:0012::1"), 64)
    )

    @Test
    fun testIsNumericIpLiteral() {
        assertTrue(LanAddressValidator.isNumericIpLiteral("192.168.1.1"))
        assertTrue(LanAddressValidator.isNumericIpLiteral("10.0.0.1"))
        assertTrue(LanAddressValidator.isNumericIpLiteral("2001:db8::1"))
        assertTrue(LanAddressValidator.isNumericIpLiteral("fe80::1"))

        assertFalse(LanAddressValidator.isNumericIpLiteral("google.com"))
        assertFalse(LanAddressValidator.isNumericIpLiteral("my-device.local"))
        assertFalse(LanAddressValidator.isNumericIpLiteral("http://192.168.1.1"))
        assertFalse(LanAddressValidator.isNumericIpLiteral("192.168.1.1:42424"))
        assertFalse(LanAddressValidator.isNumericIpLiteral("fe80::1%wlan0"))
        assertFalse(LanAddressValidator.isNumericIpLiteral("user@192.168.1.1"))
        assertFalse(LanAddressValidator.isNumericIpLiteral(""))
    }

    @Test
    fun testProductionLoopbackRejected() {
        val resultV4 = LanAddressValidator.validateAddress("127.0.0.1", localSubnet24, allowLoopbackForTesting = false)
        assertTrue(resultV4 is LanAddressValidationResult.Rejected)
        assertEquals(LanAddressRejectionCode.LOOPBACK_REJECTED, (resultV4 as LanAddressValidationResult.Rejected).code)

        val resultV6 = LanAddressValidator.validateAddress("::1", localSubnet24, allowLoopbackForTesting = false)
        assertTrue(resultV6 is LanAddressValidationResult.Rejected)
        assertEquals(LanAddressRejectionCode.LOOPBACK_REJECTED, (resultV6 as LanAddressValidationResult.Rejected).code)

        // Allowed when allowLoopbackForTesting is true
        val testResult = LanAddressValidator.validateAddress("127.0.0.1", localSubnet24, allowLoopbackForTesting = true)
        assertEquals(LanAddressValidationResult.Valid, testResult)
    }

    @Test
    fun testMulticastRejected() {
        val multicastV4 = LanAddressValidator.validateAddress("224.0.0.1", localSubnet24)
        assertTrue(multicastV4 is LanAddressValidationResult.Rejected)
        assertEquals(LanAddressRejectionCode.MULTICAST_REJECTED, (multicastV4 as LanAddressValidationResult.Rejected).code)

        val multicastV6 = LanAddressValidator.validateAddress("ff02::1", localSubnetIpv6)
        assertTrue(multicastV6 is LanAddressValidationResult.Rejected)
        assertEquals(LanAddressRejectionCode.MULTICAST_REJECTED, (multicastV6 as LanAddressValidationResult.Rejected).code)
    }

    @Test
    fun testIpv4MappedIpv6Rejected() {
        val mapped = LanAddressValidator.validateAddress("::ffff:192.168.1.100", localSubnet24)
        assertTrue(mapped is LanAddressValidationResult.Rejected)
        assertEquals(LanAddressRejectionCode.IPV4_MAPPED_IPV6_REJECTED, (mapped as LanAddressValidationResult.Rejected).code)
    }

    @Test
    fun testBroadcastAndWildcardRejected() {
        val wildcard = LanAddressValidator.validateAddress("0.0.0.0", localSubnet24)
        assertTrue(wildcard is LanAddressValidationResult.Rejected)
        assertEquals(LanAddressRejectionCode.UNSPECIFIED_OR_BROADCAST_REJECTED, (wildcard as LanAddressValidationResult.Rejected).code)

        val bcast = LanAddressValidator.validateAddress("255.255.255.255", localSubnet24)
        assertTrue(bcast is LanAddressValidationResult.Rejected)
        assertEquals(LanAddressRejectionCode.UNSPECIFIED_OR_BROADCAST_REJECTED, (bcast as LanAddressValidationResult.Rejected).code)
    }

    @Test
    fun testIpv4OnLinkSubnetMatching() {
        // 192.168.1.150 belongs to 192.168.1.0/24
        val onLink = LanAddressValidator.validateAddress("192.168.1.150", localSubnet24)
        assertEquals(LanAddressValidationResult.Valid, onLink)

        // 192.168.2.1 does not belong to 192.168.1.0/24
        val offLink = LanAddressValidator.validateAddress("192.168.2.1", localSubnet24)
        assertTrue(offLink is LanAddressValidationResult.Rejected)
        assertEquals(LanAddressRejectionCode.OFF_LINK_REJECTED, (offLink as LanAddressValidationResult.Rejected).code)

        // 10.0.250.99 belongs to 10.0.0.0/16
        val onLink16 = LanAddressValidator.validateAddress("10.0.250.99", localSubnet16)
        assertEquals(LanAddressValidationResult.Valid, onLink16)

        // 10.1.0.1 does not belong to 10.0.0.0/16
        val offLink16 = LanAddressValidator.validateAddress("10.1.0.1", localSubnet16)
        assertTrue(offLink16 is LanAddressValidationResult.Rejected)
        assertEquals(LanAddressRejectionCode.OFF_LINK_REJECTED, (offLink16 as LanAddressValidationResult.Rejected).code)
    }

    @Test
    fun testIpv6OnLinkSubnetMatching() {
        // Same /64 prefix
        val onLinkV6 = LanAddressValidator.validateAddress("2001:db8:abcd:12::cafe", localSubnetIpv6)
        assertEquals(LanAddressValidationResult.Valid, onLinkV6)

        // Different /64 prefix
        val offLinkV6 = LanAddressValidator.validateAddress("2001:db8:abcd:13::cafe", localSubnetIpv6)
        assertTrue(offLinkV6 is LanAddressValidationResult.Rejected)
        assertEquals(LanAddressRejectionCode.OFF_LINK_REJECTED, (offLinkV6 as LanAddressValidationResult.Rejected).code)
    }

    @Test
    fun testEmptySubnets_rejectsAsOffLink() {
        val result = LanAddressValidator.validateAddress("192.168.1.1", emptyList())
        assertTrue(result is LanAddressValidationResult.Rejected)
        assertEquals(LanAddressRejectionCode.OFF_LINK_REJECTED, (result as LanAddressValidationResult.Rejected).code)
    }
}
