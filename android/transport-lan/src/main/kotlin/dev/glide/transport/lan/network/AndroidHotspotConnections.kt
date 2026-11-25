package dev.glide.transport.lan.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import kotlin.coroutines.resume

/** Leases keep a group or requested Wi-Fi connection alive until Glide's transfer service is done. */
object AndroidHotspotConnections {
    class Host internal constructor(
        val ssid: String,
        val passphrase: String,
        val addresses: Set<String>,
        private val release: () -> Unit
    ) : Closeable {
        override fun close() = release()
        override fun toString(): String = "Hotspot host (credentials redacted)"
    }

    private class Client(val network: Network, val release: () -> Unit)
    @Volatile var host: Host? = null
        private set
    @Volatile private var client: Client? = null
    val preferredNetwork: Network? get() = client?.network

    @Synchronized
    fun claimCurrentLease(): () -> Unit {
        val claimedHost = host
        val claimedClient = client
        return {
            synchronized(this) {
                if (claimedHost != null && host === claimedHost) {
                    claimedHost.close()
                    host = null
                }
                if (claimedClient != null && client === claimedClient) {
                    claimedClient.release()
                    client = null
                }
            }
        }
    }

    @Synchronized
    fun close() {
        host?.close()
        host = null
        client?.release?.invoke()
        client = null
    }

    @SuppressLint("MissingPermission")
    suspend fun createHost(context: Context): Host? {
        close()
        val appContext = context.applicationContext
        val result = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                createP2pHost(appContext, prefer5Ghz = true)
                    ?: createP2pHost(appContext, prefer5Ghz = false)
                    ?: createLocalOnlyHost(appContext)
            } else createLocalOnlyHost(appContext)
        }
        host = result
        return result
    }

    @SuppressLint("MissingPermission")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun createP2pHost(context: Context, prefer5Ghz: Boolean): Host? {
        val manager = context.getSystemService(WifiP2pManager::class.java) ?: return null
        val channel = runCatching { manager.initialize(context, Looper.getMainLooper(), null) }.getOrNull()
            ?: return null
        var created = false
        var retained = false
        try {
            val existing = withTimeoutOrNull(1500) { GroupReply(requestGroup(manager, channel)) } ?: return null
            if (existing.group != null) return null
            val random = UUID.randomUUID().toString().replace("-", "")
            val config = WifiP2pConfig.Builder()
                .setNetworkName("DIRECT-GL-${random.take(6)}")
                .setPassphrase(random.take(20))
                .enablePersistentMode(false)
                .setGroupOperatingBand(if (prefer5Ghz) WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
                    else WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                .build()
            created = withTimeoutOrNull(5000) {
                suspendCancellableCoroutine { continuation ->
                    try {
                        manager.createGroup(channel, config, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                continuation.resume(true) { _, _, _ ->
                                    runCatching { manager.removeGroup(channel, null) }
                                }
                            }
                            override fun onFailure(reason: Int) {
                                if (continuation.isActive) continuation.resume(false)
                            }
                        })
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            } == true
            if (!created) return null
            return withTimeoutOrNull(3500) {
                while (true) {
                    val group = requestGroup(manager, channel)
                    if (group?.isGroupOwner == true) {
                        val addresses = localAddresses(group.`interface`)
                        val name = group.networkName
                        val password = group.passphrase
                        if (addresses.isNotEmpty() && validCredentials(name, password)) {
                            retained = true
                            return@withTimeoutOrNull Host(name, password, addresses) {
                                runCatching { manager.removeGroup(channel, null) }
                                runCatching { channel.close() }
                            }
                        }
                    }
                    delay(250)
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
        } finally {
            if (!retained) {
                if (created) runCatching { manager.removeGroup(channel, null) }
                runCatching { channel.close() }
            }
        }
    }

    private data class GroupReply(val group: WifiP2pGroup?)

    @SuppressLint("MissingPermission")
    private suspend fun requestGroup(manager: WifiP2pManager, channel: WifiP2pManager.Channel): WifiP2pGroup? =
        suspendCancellableCoroutine { continuation ->
            try {
                manager.requestGroupInfo(channel) { if (continuation.isActive) continuation.resume(it) }
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private suspend fun createLocalOnlyHost(context: Context): Host? {
        val wifi = context.getSystemService(WifiManager::class.java) ?: return null
        val before = localAddresses()
        val reservation = withTimeoutOrNull(5000) {
            suspendCancellableCoroutine<WifiManager.LocalOnlyHotspotReservation?> { continuation ->
                try {
                    wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                        override fun onStarted(value: WifiManager.LocalOnlyHotspotReservation) {
                            continuation.resume(value) { _, lease, _ -> lease.close() }
                        }
                        override fun onFailed(reason: Int) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }, Handler(Looper.getMainLooper()))
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        } ?: return null
        var retained = false
        try {
            val name: String?
            val password: String?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                name = reservation.softApConfiguration?.ssid
                password = reservation.softApConfiguration?.passphrase
            } else {
                name = reservation.wifiConfiguration?.SSID?.removeSurrounding("\"")
                password = reservation.wifiConfiguration?.preSharedKey?.removeSurrounding("\"")
            }
            val ssid = name ?: return null
            val passphrase = password ?: return null
            if (!validCredentials(ssid, passphrase)) return null
            return withTimeoutOrNull(3500) {
                while (true) {
                    val addresses = localAddresses() - before
                    if (addresses.isNotEmpty()) {
                        retained = true
                        return@withTimeoutOrNull Host(ssid, passphrase, addresses, reservation::close)
                    }
                    delay(250)
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
        } finally {
            if (!retained) reservation.close()
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun join(context: Context, ssid: String, password: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val request = try {
            val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(password).build()
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier).build()
        } catch (_: IllegalArgumentException) {
            return false
        }
        client?.release?.invoke()
        client = null
        val joined = withTimeoutOrNull(16_000) {
            suspendCancellableCoroutine<Client?> { continuation ->
                lateinit var callback: ConnectivityManager.NetworkCallback
                val release = { runCatching { cm.unregisterNetworkCallback(callback) }; Unit }
                callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (continuation.isActive) {
                            continuation.resume(Client(network, release)) { _, value, _ -> value.release() }
                        }
                    }
                    override fun onUnavailable() {
                        release()
                        if (continuation.isActive) continuation.resume(null)
                    }
                    override fun onLost(network: Network) {
                        synchronized(this@AndroidHotspotConnections) {
                            if (client?.network == network) {
                                release()
                                client = null
                            }
                        }
                    }
                }
                continuation.invokeOnCancellation { release() }
                try {
                    cm.requestNetwork(request, callback, 15_000)
                } catch (_: Exception) {
                    release()
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        client = joined
        return joined != null
    }

    private fun localAddresses(interfaceName: String? = null): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty()
            .filter { it.isUp && !it.isLoopback && (interfaceName == null || it.name == interfaceName) }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }.toSet()
    }.getOrDefault(emptySet())

    private fun validCredentials(ssid: String?, password: String?): Boolean =
        !ssid.isNullOrBlank() && ssid.encodeToByteArray().size <= 32 &&
            password != null && password.length in 8..63 && password.all { it in ' '..'~' }
}
