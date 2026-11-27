package dev.glide.app.connectivity

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.glide.app.lan.HotspotInterfaceWatcher
import dev.glide.app.permission.PermissionPolicy
import dev.glide.app.ui.settings.NearbyDiagnosticsState
import dev.glide.transport.lan.network.LanNetworkScope
import dev.glide.transport.lan.network.LanNetworkState
import dev.glide.transport.nearby.AndroidNearbyAvailabilityChecker
import dev.glide.transport.nearby.NearbyHardwareStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import dev.glide.app.ui.discovery.BlockedReason

internal enum class ConnectionKind { Nearby, Lan, Hotspot }

internal fun automaticActionFor(reason: BlockedReason?): String? = when (reason) {
    BlockedReason.BluetoothDisabled -> BluetoothAdapter.ACTION_REQUEST_ENABLE
    BlockedReason.WifiDisabled, BlockedReason.NoLocalNetwork ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
    else -> null
}

internal fun requiredPermissions(kind: ConnectionKind): List<String> = when (kind) {
    ConnectionKind.Nearby -> PermissionPolicy.nearbyTransferPermissions()
    ConnectionKind.Lan -> PermissionPolicy.lanTransferPermissions()
    ConnectionKind.Hotspot -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(PermissionPolicy.PERMISSION_NEARBY_WIFI_DEVICES)
    } else {
        listOf(PermissionPolicy.PERMISSION_ACCESS_FINE_LOCATION, PermissionPolicy.PERMISSION_ACCESS_COARSE_LOCATION)
    }
}

internal fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

internal suspend fun connectionBlocker(context: Context, kind: ConnectionKind): BlockedReason? {
    if (!requiredPermissions(kind).all { context.hasPermission(it) }) {
        return if (kind == ConnectionKind.Lan) BlockedReason.LanPermissionDenied else BlockedReason.PermissionsRequired
    }
    return when (kind) {
        ConnectionKind.Nearby -> when (
            runCatching { AndroidNearbyAvailabilityChecker(context).checkHardwareStatus() }.getOrNull()
        ) {
            NearbyHardwareStatus.BluetoothDisabled -> BlockedReason.BluetoothDisabled
            NearbyHardwareStatus.WifiDisabled -> BlockedReason.WifiDisabled
            else -> null // The discovery controller owns transport and Play Services failures.
        }
        ConnectionKind.Hotspot -> null // The platform network request manages its own chooser.
        ConnectionKind.Lan -> withContext(Dispatchers.IO) {
            LanNetworkScope(context.applicationContext).use { network ->
                val observed = withTimeoutOrNull(2_000) {
                    network.networkState.first { it != LanNetworkState.NoNetwork }
                } ?: run {
                    network.evaluateLocalInterfacesFallback()
                    network.networkState.value
                }
                when (observed) {
                    is LanNetworkState.Available -> null
                    is LanNetworkState.VpnActive -> BlockedReason.VpnActive
                    else -> BlockedReason.NoLocalNetwork
                }
            }
        }
    }
}

internal suspend fun isPhoneHotspotActive(context: Context): Boolean =
    HotspotInterfaceWatcher.hasActiveHotspotIpv4(context)

internal fun readNearbyDiagnostics(context: Context): NearbyDiagnosticsState {
    val appContext = context.applicationContext
    val hardwareStatus = runCatching {
        AndroidNearbyAvailabilityChecker(appContext).checkHardwareStatus()
    }.getOrNull()
    val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothEnabled = btManager?.adapter?.isEnabled
        ?: (hardwareStatus != null && hardwareStatus != NearbyHardwareStatus.BluetoothDisabled)
    val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val wifiEnabled = wifiManager?.isWifiEnabled
        ?: (hardwareStatus != null && hardwareStatus != NearbyHardwareStatus.WifiDisabled)
    val permissionsGranted = PermissionPolicy.nearbyTransferPermissions().all { appContext.hasPermission(it) }
    val playServicesLabel = when (hardwareStatus) {
        is NearbyHardwareStatus.PlayServicesUnavailable -> "Play Services unavailable (code ${hardwareStatus.errorCode})"
        NearbyHardwareStatus.LocationServicesDisabled -> "Location services disabled"
        else -> "Nearby Connections v1.0 (P2P Direct)"
    }
    val band = if (wifiManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wifiManager.is5GHzBandSupported) {
        "2.4 GHz / 5 GHz Dual Band"
    } else if (wifiManager != null) {
        "2.4 GHz Wi-Fi"
    } else {
        "Wi-Fi radio unavailable"
    }
    return NearbyDiagnosticsState(
        bluetoothEnabled = bluetoothEnabled,
        wifiEnabled = wifiEnabled,
        permissionsGranted = permissionsGranted,
        radioBand = band,
        gmsVersion = playServicesLabel
    )
}
