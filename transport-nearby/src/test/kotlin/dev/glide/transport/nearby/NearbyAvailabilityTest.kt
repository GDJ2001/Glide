package dev.glide.transport.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyAvailabilityTest {

    class FakeNearbyAvailabilityChecker(
        var status: NearbyHardwareStatus = NearbyHardwareStatus.Available
    ) : NearbyAvailabilityChecker {
        override fun checkHardwareStatus(): NearbyHardwareStatus = status
    }

    @Test
    fun available_status_reports_ready() {
        val checker = FakeNearbyAvailabilityChecker(NearbyHardwareStatus.Available)
        assertEquals(NearbyHardwareStatus.Available, checker.checkHardwareStatus())
    }

    @Test
    fun bluetooth_disabled_reports_bluetooth_disabled() {
        val checker = FakeNearbyAvailabilityChecker(NearbyHardwareStatus.BluetoothDisabled)
        assertEquals(NearbyHardwareStatus.BluetoothDisabled, checker.checkHardwareStatus())
    }

    @Test
    fun wifi_disabled_reports_wifi_disabled() {
        val checker = FakeNearbyAvailabilityChecker(NearbyHardwareStatus.WifiDisabled)
        assertEquals(NearbyHardwareStatus.WifiDisabled, checker.checkHardwareStatus())
    }

    @Test
    fun location_services_disabled_reports_location_disabled() {
        val checker = FakeNearbyAvailabilityChecker(NearbyHardwareStatus.LocationServicesDisabled)
        assertEquals(NearbyHardwareStatus.LocationServicesDisabled, checker.checkHardwareStatus())
    }

    @Test
    fun play_services_unavailable_reports_error_code_and_resolvability() {
        val checker = FakeNearbyAvailabilityChecker(NearbyHardwareStatus.PlayServicesUnavailable(2, true))
        val status = checker.checkHardwareStatus()
        assertTrue(status is NearbyHardwareStatus.PlayServicesUnavailable)
        val ps = status as NearbyHardwareStatus.PlayServicesUnavailable
        assertEquals(2, ps.errorCode)
        assertTrue(ps.isResolvable)
    }
}
