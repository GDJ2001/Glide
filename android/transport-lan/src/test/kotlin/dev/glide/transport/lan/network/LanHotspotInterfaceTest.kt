package dev.glide.transport.lan.network

import android.net.wifi.p2p.WifiP2pConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanHotspotInterfaceTest {
    @Test
    fun acceptsHotspotTetheringAndLanInterfaces() {
        assertTrue(isLocalHotspotInterfaceName("wlan0"))
        assertTrue(isLocalHotspotInterfaceName("ap0"))
        assertTrue(isLocalHotspotInterfaceName("softap0"))
        assertTrue(isLocalHotspotInterfaceName("sap0"))
        assertTrue(isLocalHotspotInterfaceName("swlan0"))
        assertTrue(isLocalHotspotInterfaceName("p2p-wlan0-0"))
        assertTrue(isLocalHotspotInterfaceName("eth0"))
        assertTrue(isLocalHotspotInterfaceName("rndis0"))
        assertTrue(isLocalHotspotInterfaceName("usb0"))
    }

    @Test
    fun rejectsCellularTunnelAndLoopbackInterfaces() {
        assertFalse(isLocalHotspotInterfaceName(""))
        assertFalse(isLocalHotspotInterfaceName("rmnet_data0"))
        assertFalse(isLocalHotspotInterfaceName("ccmni0"))
        assertFalse(isLocalHotspotInterfaceName("tun0"))
        assertFalse(isLocalHotspotInterfaceName("ppp0"))
        assertFalse(isLocalHotspotInterfaceName("clat4"))
        assertFalse(isLocalHotspotInterfaceName("dummy0"))
        assertFalse(isLocalHotspotInterfaceName("lo"))
    }

    @Test
    fun softApCandidateRecognizesWlan0OnlyWhenWifiStaDisabled() {
        assertTrue(isSoftApInterfaceCandidate("wlan0", isWifiStaEnabled = false))
        assertTrue(isSoftApInterfaceCandidate("wifi0", isWifiStaEnabled = false))
        assertFalse(isSoftApInterfaceCandidate("wlan0", isWifiStaEnabled = true))

        assertTrue(isSoftApInterfaceCandidate("ap0", isWifiStaEnabled = true))
        assertTrue(isSoftApInterfaceCandidate("softap0", isWifiStaEnabled = true))
        assertTrue(isSoftApInterfaceCandidate("swlan0", isWifiStaEnabled = true))
        assertTrue(isSoftApInterfaceCandidate("p2p-wlan0-0", isWifiStaEnabled = true))

        assertFalse(isSoftApInterfaceCandidate("rmnet_data0", isWifiStaEnabled = false))
        assertFalse(isSoftApInterfaceCandidate("tun0", isWifiStaEnabled = false))
        assertFalse(isSoftApInterfaceCandidate("lo", isWifiStaEnabled = false))
    }

    @Test
    fun joinTimeoutGivesUserHeadroomAndClampsToBounds() {
        assertEquals(60_000L, AndroidHotspotConnections.computeJoinTimeoutMs(1_000L, 1_120L))
        assertEquals(35_000L, AndroidHotspotConnections.computeJoinTimeoutMs(1_000L, 1_035L))
        assertEquals(15_000L, AndroidHotspotConnections.computeJoinTimeoutMs(1_000L, 1_005L))
        assertEquals(15_000L, AndroidHotspotConnections.computeJoinTimeoutMs(1_000L, 990L))
    }

    @Test
    fun p2pBandOrderPrioritizes2GhzForBroadDeviceCompatibility() {
        val bands = AndroidHotspotConnections.p2pBandOrder()
        assertEquals(
            listOf(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ, WifiP2pConfig.GROUP_OWNER_BAND_AUTO),
            bands
        )
        assertFalse(bands.contains(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ))
    }

    @Test
    fun subnetPresenceBeacon_computesGatewayBroadcastAndRespondsOverLoopback() {
        val sampleIp = java.net.InetAddress.getByName("192.168.43.18") as java.net.Inet4Address
        assertEquals("192.168.43.1", dev.glide.transport.lan.discovery.LanSubnetPresenceBeacon.subnetGatewayDotOne(sampleIp, 24)?.hostAddress)
        assertEquals("192.168.43.255", dev.glide.transport.lan.discovery.LanSubnetPresenceBeacon.subnetDirectedBroadcast(sampleIp, 24)?.hostAddress)

        val scope = LanNetworkScope(null).also {
            val loopback = java.net.InetAddress.getByName("127.0.0.1")
            it.updateNetworkStateForTesting(
                LanNetworkState.Available(
                    network = null,
                    localAddresses = listOf(loopback),
                    subnets = listOf(SubnetPrefix(loopback, 8)),
                    generation = 3L
                )
            )
        }
        val responder = dev.glide.transport.lan.discovery.LanSubnetPresenceBeacon(
            networkScope = scope,
            allowLoopbackForTesting = true,
            beaconPort = 0
        )
        val probe = dev.glide.transport.lan.discovery.LanSubnetPresenceBeacon(
            networkScope = scope,
            allowLoopbackForTesting = true,
            beaconPort = 0
        )
        try {
            val pid = "00112233445566778899aabbccddeeff"
            responder.startAdvertising(
                deviceName = "Samsung J7",
                localPort = 49152,
                presenceId = pid,
                supportsTrustedReconnect = true
            )
            assertTrue(responder.activeResponderPort > 0)
            val found = kotlinx.coroutines.runBlocking {
                probe.probeOnce(timeoutMs = 1_500L, targetPortOverride = responder.activeResponderPort)
            }
            assertEquals(1, found.size)
            assertEquals("Samsung J7", found.single().displayName)
            assertEquals(49152, found.single().port)
            assertEquals(pid, found.single().presenceId)
        } finally {
            probe.close()
            responder.close()
            scope.close()
        }
    }
}
