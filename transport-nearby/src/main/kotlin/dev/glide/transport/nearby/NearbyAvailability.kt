package dev.glide.transport.nearby

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Result of checking Nearby hardware radios and system service availability.
 */
sealed class NearbyHardwareStatus {
    object Available : NearbyHardwareStatus()
    object BluetoothDisabled : NearbyHardwareStatus()
    object WifiDisabled : NearbyHardwareStatus()
    object LocationServicesDisabled : NearbyHardwareStatus()
    data class PlayServicesUnavailable(val errorCode: Int, val isResolvable: Boolean) : NearbyHardwareStatus()
}

/**
 * Interface for checking Nearby hardware radios and system availability.
 */
interface NearbyAvailabilityChecker {
    fun checkHardwareStatus(): NearbyHardwareStatus
}

/**
 * Android implementation of NearbyAvailabilityChecker.
 */
class AndroidNearbyAvailabilityChecker(
    private val context: Context,
    private val googleApiAvailability: GoogleApiAvailability = GoogleApiAvailability.getInstance()
) : NearbyAvailabilityChecker {

    override fun checkHardwareStatus(): NearbyHardwareStatus {
        // 1. Google Play Services availability check
        val playServicesStatus = googleApiAvailability.isGooglePlayServicesAvailable(context)
        if (playServicesStatus != ConnectionResult.SUCCESS) {
            val resolvable = googleApiAvailability.isUserResolvableError(playServicesStatus)
            return NearbyHardwareStatus.PlayServicesUnavailable(playServicesStatus, resolvable)
        }

        // 2. Bluetooth radio check
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            return NearbyHardwareStatus.BluetoothDisabled
        }

        // 3. Wi-Fi radio check
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null || !wifiManager.isWifiEnabled) {
            return NearbyHardwareStatus.WifiDisabled
        }

        // 4. Location services check (only required for BLE discovery on Android 12L / API 32 and below)
        if (Build.VERSION.SDK_INT <= 32) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (locationManager == null || !LocationManagerCompat.isLocationEnabled(locationManager)) {
                return NearbyHardwareStatus.LocationServicesDisabled
            }
        }

        return NearbyHardwareStatus.Available
    }
}
