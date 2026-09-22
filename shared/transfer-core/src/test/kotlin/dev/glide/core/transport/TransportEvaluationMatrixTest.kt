package dev.glide.core.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportEvaluationMatrixTest {

    @Test
    fun testStandardProfilesValidation() {
        assertEquals(5, TransportProfiles.ALL.size)
        for (profile in TransportProfiles.ALL) {
            assertTrue(profile.minApiLevel >= 14)
            assertTrue(profile.maxThroughputMbps > 0)
            assertTrue(profile.hardwareAvailabilityRatio in 0.0..1.0)
            if (profile.type != TransportType.GMS_NEARBY_P2P) {
                assertTrue("Non-GMS must require TLS 1.3", profile.requiresTls13)
            }
        }
    }

    @Test
    fun testGmsDeviceSelectionTier1() {
        val env = DeviceEnvironment(
            apiLevel = 34,
            hasGms = true,
            hasWifiAwareHardware = false,
            hasWifiDirectHardware = true,
            isConnectedToLocalNetwork = true,
            peerSupportsCrossPlatform = false
        )

        val selected = TransportSelectionEngine.selectBestTransport(env)
        assertNotNull(selected)
        assertEquals(TransportType.GMS_NEARBY_P2P, selected?.type)
        assertEquals(TransportTier.TIER_1_DEFAULT_P2P, selected?.tier)
    }

    @Test
    fun testCrossPlatformPeerSelectsLan() {
        // Even if GMS is present on Android, if the peer is a desktop (cross-platform),
        // LAN + NSD + TLS must be selected because GMS Nearby does not support cross-platform.
        val env = DeviceEnvironment(
            apiLevel = 34,
            hasGms = true,
            hasWifiAwareHardware = false,
            hasWifiDirectHardware = true,
            isConnectedToLocalNetwork = true,
            peerSupportsCrossPlatform = true
        )

        val selected = TransportSelectionEngine.selectBestTransport(env)
        assertNotNull(selected)
        assertEquals(TransportType.LAN_NSD_TLS, selected?.type)
        assertEquals(TransportTier.TIER_2_CROSS_PLATFORM_LAN, selected?.tier)
    }

    @Test
    fun testDeGoogledDeviceWithLanSelectsTier2() {
        val env = DeviceEnvironment(
            apiLevel = 33,
            hasGms = false, // De-Googled AOSP
            hasWifiAwareHardware = false,
            hasWifiDirectHardware = true,
            isConnectedToLocalNetwork = true,
            peerSupportsCrossPlatform = false
        )

        val selected = TransportSelectionEngine.selectBestTransport(env)
        assertNotNull(selected)
        assertEquals(TransportType.LAN_NSD_TLS, selected?.type)
        assertEquals(TransportTier.TIER_2_CROSS_PLATFORM_LAN, selected?.tier)
    }

    @Test
    fun testDeGoogledDeviceOfflineSelectsAutonomousWifiDirect() {
        val env = DeviceEnvironment(
            apiLevel = 33,
            hasGms = false, // De-Googled AOSP
            hasWifiAwareHardware = false,
            hasWifiDirectHardware = true,
            isConnectedToLocalNetwork = false, // Offline (no router)
            peerSupportsCrossPlatform = false
        )

        val selected = TransportSelectionEngine.selectBestTransport(env)
        assertNotNull(selected)
        assertEquals(TransportType.WIFI_DIRECT_P2P, selected?.type)
        assertEquals(TransportTier.TIER_3_GMS_FREE_OFFLINE_P2P, selected?.tier)
    }

    @Test
    fun testWifiAwareHardwareConstraint() {
        val envWithoutAware = DeviceEnvironment(
            apiLevel = 34,
            hasGms = false,
            hasWifiAwareHardware = false,
            hasWifiDirectHardware = false,
            isConnectedToLocalNetwork = false,
            peerSupportsCrossPlatform = false
        )
        assertFalse(TransportSelectionEngine.isFeasible(TransportProfiles.WIFI_AWARE_NAN, envWithoutAware))

        val envWithAware = envWithoutAware.copy(hasWifiAwareHardware = true)
        assertTrue(TransportSelectionEngine.isFeasible(TransportProfiles.WIFI_AWARE_NAN, envWithAware))
    }

    @Test
    fun testLowApiLevelRejections() {
        val envApi21 = DeviceEnvironment(
            apiLevel = 21,
            hasGms = true,
            hasWifiAwareHardware = false,
            hasWifiDirectHardware = true,
            isConnectedToLocalNetwork = false,
            peerSupportsCrossPlatform = false
        )
        // GMS Nearby min is 26, Local Hotspot min is 26, Wi-Fi Aware min is 26
        assertFalse(TransportSelectionEngine.isFeasible(TransportProfiles.GMS_NEARBY, envApi21))
        assertFalse(TransportSelectionEngine.isFeasible(TransportProfiles.LOCAL_HOTSPOT, envApi21))
        assertFalse(TransportSelectionEngine.isFeasible(TransportProfiles.WIFI_AWARE_NAN, envApi21))
        // Wi-Fi Direct min is 14
        assertTrue(TransportSelectionEngine.isFeasible(TransportProfiles.WIFI_DIRECT_P2P, envApi21))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testTls13MandateEnforced() {
        // Attempting to register a non-GMS transport without TLS 1.3 must throw
        TransportProfile(
            type = TransportType.WIFI_DIRECT_P2P,
            tier = TransportTier.TIER_3_GMS_FREE_OFFLINE_P2P,
            minApiLevel = 14,
            requiresGms = false,
            requiresAccessPoint = false,
            requiresTls13 = false, // Violates security invariant
            maxThroughputMbps = 450,
            hardwareAvailabilityRatio = 0.95,
            supportsCrossPlatform = false,
            canPreserveStationWifi = false
        )
    }

    @Test
    fun testEligibleTransportsOrderedByTierPriority() {
        val env = DeviceEnvironment(
            apiLevel = 34,
            hasGms = true,
            hasWifiAwareHardware = true,
            hasWifiDirectHardware = true,
            isConnectedToLocalNetwork = true,
            peerSupportsCrossPlatform = false
        )

        val eligible = TransportSelectionEngine.getEligibleTransports(env)
        assertEquals(5, eligible.size)
        // Check strict tier ordering: 1 -> 2 -> 3 -> 4 -> 5
        assertEquals(TransportTier.TIER_1_DEFAULT_P2P, eligible[0].tier)
        assertEquals(TransportTier.TIER_2_CROSS_PLATFORM_LAN, eligible[1].tier)
        assertEquals(TransportTier.TIER_3_GMS_FREE_OFFLINE_P2P, eligible[2].tier)
        assertEquals(TransportTier.OPPORTUNISTIC_PROBE, eligible[3].tier)
        assertEquals(TransportTier.EMERGENCY_FALLBACK, eligible[4].tier)
    }

    @Test
    fun testNoFeasibleTransportReturnsNull() {
        val env = DeviceEnvironment(
            apiLevel = 10, // Too low for anything
            hasGms = false,
            hasWifiAwareHardware = false,
            hasWifiDirectHardware = false,
            isConnectedToLocalNetwork = false,
            peerSupportsCrossPlatform = false
        )

        assertNull(TransportSelectionEngine.selectBestTransport(env))
    }
}
