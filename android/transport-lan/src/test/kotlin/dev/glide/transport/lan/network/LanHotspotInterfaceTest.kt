package dev.glide.transport.lan.network

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
}
