package dev.glide.app.connectivity

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.glide.app.permission.PermissionPolicy
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
                when (withTimeoutOrNull(1_500) {
                    network.networkState.first { it != LanNetworkState.NoNetwork }
                }) {
                    is LanNetworkState.Available -> null
                    is LanNetworkState.VpnActive -> BlockedReason.VpnActive
                    else -> BlockedReason.NoLocalNetwork
                }
            }
        }
    }
}
