package dev.glide.app.lan

import dev.glide.transport.lan.network.AndroidHotspotConnections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

internal object HotspotInterfaceWatcher {
    private val SOFT_AP_PREFIXES = listOf("ap", "softap", "sap", "swlan", "p2p")

    suspend fun hasActiveHotspotIpv4(): Boolean = withContext(Dispatchers.IO) {
        if (AndroidHotspotConnections.host?.addresses?.isNotEmpty() == true) {
            return@withContext true
        }
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching false
            interfaces.asSequence().any { ni ->
                if (!ni.isUp || ni.isLoopback) return@any false
                val name = ni.name.lowercase()
                val matchesPrefix = SOFT_AP_PREFIXES.any { name.startsWith(it) }
                if (!matchesPrefix) return@any false
                ni.inetAddresses.asSequence().any { addr ->
                    addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress
                }
            }
        }.getOrDefault(false)
    }
}
