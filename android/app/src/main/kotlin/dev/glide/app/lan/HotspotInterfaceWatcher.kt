package dev.glide.app.lan

import android.content.Context
import android.net.wifi.WifiManager
import dev.glide.transport.lan.network.AndroidHotspotConnections
import dev.glide.transport.lan.network.isLocalHotspotInterfaceName
import dev.glide.transport.lan.network.isSoftApInterfaceCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

internal object HotspotInterfaceWatcher {

    @Suppress("DEPRECATION")
    suspend fun hasActiveHotspotIpv4(context: Context? = null): Boolean = withContext(Dispatchers.IO) {
        if (AndroidHotspotConnections.host?.addresses?.isNotEmpty() == true) {
            return@withContext true
        }
        val wifi = context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiStaEnabled = runCatching { wifi?.isWifiEnabled }.getOrNull() ?: true
        val wifiApEnabled = isWifiApEnabled(wifi)

        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching false
            interfaces.asSequence().any { ni ->
                if (!ni.isUp || ni.isLoopback) return@any false
                val matchesSoftAp = isSoftApInterfaceCandidate(ni.name.orEmpty(), wifiStaEnabled) ||
                    (wifiApEnabled && isLocalHotspotInterfaceName(ni.name.orEmpty()))
                if (!matchesSoftAp) return@any false
                ni.inetAddresses.asSequence().any { addr ->
                    addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress
                }
            }
        }.getOrDefault(false)
    }

    private fun isWifiApEnabled(wifiManager: WifiManager?): Boolean {
        if (wifiManager == null) return false
        val byBool = runCatching {
            wifiManager.javaClass.getMethod("isWifiApEnabled").invoke(wifiManager) as? Boolean
        }.getOrNull()
        if (byBool == true) return true
        val state = runCatching {
            wifiManager.javaClass.getMethod("getWifiApState").invoke(wifiManager) as? Int
        }.getOrNull()
        return state == 13 // WifiManager.WIFI_AP_STATE_ENABLED
    }
}
