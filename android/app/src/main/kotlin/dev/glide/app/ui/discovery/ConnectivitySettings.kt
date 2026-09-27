package dev.glide.app.ui.discovery

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Both discovery and receive recovery open the settings relevant to the blocker. */
internal fun openConnectivitySettings(context: Context, reason: BlockedReason) {
    val intent = when (reason) {
        is BlockedReason.BluetoothDisabled -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        is BlockedReason.WifiDisabled, is BlockedReason.NoLocalNetwork -> Intent(Settings.ACTION_WIFI_SETTINGS)
        is BlockedReason.VpnActive -> Intent(Settings.ACTION_VPN_SETTINGS)
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            // The retry action remains available on devices without a settings handler.
        }
    }
}
