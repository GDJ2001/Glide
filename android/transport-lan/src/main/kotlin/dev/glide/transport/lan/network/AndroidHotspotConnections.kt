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
    private var claimedHost: Host? = null
    private var claimedClient: Client? = null
    private var generation = 0L

    @Synchronized
    fun claimCurrentLease(): () -> Unit {
        pruneStaleHostIfDown()
        val ownedHost = host
        val ownedClient = client
        if (ownedHost != null) claimedHost = ownedHost
        if (ownedClient != null) claimedClient = ownedClient
        return {
            synchronized(this) {
                if (ownedHost != null && (host === ownedHost || claimedHost === ownedHost)) {
                    runCatching { ownedHost.close() }
                    if (host === ownedHost) host = null
                    if (claimedHost === ownedHost) claimedHost = null
                }
                if (ownedClient != null && (client === ownedClient || claimedClient === ownedClient)) {
                    runCatching { ownedClient.release() }
                    if (client === ownedClient) client = null
                    if (claimedClient === ownedClient) claimedClient = null
                }
            }
        }
    }

    @Synchronized
    fun claimHostLease(): () -> Unit {
        pruneStaleHostIfDown()
        val ownedHost = host ?: return {}
        claimedHost = ownedHost
        return {
            synchronized(this) {
                if (host === ownedHost || claimedHost === ownedHost) {
                    runCatching { ownedHost.close() }
                    if (host === ownedHost) host = null
                    if (claimedHost === ownedHost) claimedHost = null
                }
            }
        }
    }

    @Synchronized
    fun claimClientLease(): () -> Unit {
        val ownedClient = client ?: return {}
        claimedClient = ownedClient
        return {
            synchronized(this) {
                if (client === ownedClient || claimedClient === ownedClient) {
                    runCatching { ownedClient.release() }
                    if (client === ownedClient) client = null
                    if (claimedClient === ownedClient) claimedClient = null
                }
            }
        }
    }

    @Synchronized
    fun isHostClaimed(targetHost: Host?): Boolean = targetHost != null && claimedHost === targetHost

    @Synchronized
    fun releaseClaimedClient() {
        val ownedClient = client ?: claimedClient
        if (ownedClient != null) {
            runCatching { ownedClient.release() }
        }
        client = null
        claimedClient = null
    }

    @Synchronized
    fun cancelJoin() {
        generation++
        val ownedClient = client ?: claimedClient
        if (ownedClient != null) {
            runCatching { ownedClient.release() }
        }
        client = null
        claimedClient = null
    }

    @Synchronized
    fun closeUnclaimed() {
        generation++
        if (host !== claimedHost) {
            runCatching { host?.close() }
            host = null
        }
        if (client !== claimedClient) {
            runCatching { client?.release?.invoke() }
            client = null
        }
    }

    @Synchronized
    private fun pruneStaleHostIfDown() {
        val activeHost = host ?: claimedHost ?: return
        val activeAddrs = localAddresses()
        if (activeAddrs.none { it in activeHost.addresses }) {
            runCatching { activeHost.close() }
            if (host === activeHost) host = null
            if (claimedHost === activeHost) claimedHost = null
        }
    }

    @Synchronized
    private fun beginSetup(forHost: Boolean): Long? {
        pruneStaleHostIfDown()
        // Starting a new client join or hotspot host supersedes any prior client lease.
        releaseClaimedClient()
        if (forHost && claimedHost != null) {
            // Explicit host request while a previous host was claimed closes the old host first.
            runCatching { claimedHost?.close() }
            if (host === claimedHost) host = null
            claimedHost = null
        }
        closeUnclaimed()
        return generation
    }

    @SuppressLint("MissingPermission")
    suspend fun createHost(context: Context): Host? {
        val attempt = beginSetup(forHost = true) ?: return null
        val appContext = context.applicationContext
        var reservation: Host? = null
        var published = false
        try {
            withContext(Dispatchers.IO) {
                reservation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val order = p2pBandOrder()
                    createLocalOnlyHost(appContext)
                        ?: createP2pHost(appContext, order[0])
                        ?: createP2pHost(appContext, order[1])
                } else {
                    createLocalOnlyHost(appContext)
                        ?: createLegacyP2pHost(appContext)
                }
            }
            return synchronized(this) {
                if (generation != attempt) return@synchronized null
                host = reservation
                published = true
                reservation
            }
        } finally {
            if (!published) reservation?.close()
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    internal fun p2pBandOrder(): List<Int> =
        listOf(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ, WifiP2pConfig.GROUP_OWNER_BAND_AUTO)

    @SuppressLint("MissingPermission")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun createP2pHost(context: Context, operatingBand: Int): Host? {
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
                .setGroupOperatingBand(operatingBand)
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

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private suspend fun createLegacyP2pHost(context: Context): Host? {
        val manager = context.getSystemService(WifiP2pManager::class.java) ?: return null
        val channel = runCatching { manager.initialize(context, Looper.getMainLooper(), null) }.getOrNull()
            ?: return null
        var created = false
        var retained = false
        try {
            val existing = withTimeoutOrNull(1500) { GroupReply(requestGroup(manager, channel)) } ?: return null
            if (existing.group != null) return null
            created = withTimeoutOrNull(5000) {
                suspendCancellableCoroutine { continuation ->
                    try {
                        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
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
            return withTimeoutOrNull(4500) {
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
        var createdHost: Host? = null
        val reservation = withTimeoutOrNull(5000) {
            suspendCancellableCoroutine<WifiManager.LocalOnlyHotspotReservation?> { continuation ->
                try {
                    wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                        override fun onStarted(value: WifiManager.LocalOnlyHotspotReservation) {
                            continuation.resume(value) { _, lease, _ -> lease.close() }
                        }
                        override fun onStopped() {
                            synchronized(this@AndroidHotspotConnections) {
                                val target = createdHost
                                if (target != null) {
                                    if (host === target) host = null
                                    if (claimedHost === target) claimedHost = null
                                }
                            }
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
                val config = reservation.softApConfiguration
                name = config.ssid?.removeSurrounding("\"")
                password = config.passphrase
            } else {
                name = reservation.wifiConfiguration?.SSID?.removeSurrounding("\"")
                password = reservation.wifiConfiguration?.preSharedKey?.removeSurrounding("\"")
            }
            val ssid = name ?: return null
            val passphrase = password ?: return null
            if (!validCredentials(ssid, passphrase)) return null
            return withTimeoutOrNull(7000) {
                while (true) {
                    val addresses = (localAddresses() - before).ifEmpty { localSoftApAddresses(wifi) }
                    if (addresses.isNotEmpty()) {
                        retained = true
                        return@withTimeoutOrNull Host(ssid, passphrase, addresses, reservation::close).also {
                            createdHost = it
                        }
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

    internal fun computeJoinTimeoutMs(nowEpochSeconds: Long, expiresAtSeconds: Long): Long =
        ((expiresAtSeconds - nowEpochSeconds) * 1_000L).coerceIn(15_000L, 60_000L)

    @SuppressLint("MissingPermission")
    suspend fun join(context: Context, ssid: String, password: String, expiresAtSeconds: Long): Boolean {
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
        val attempt = beginSetup(forHost = false) ?: return false
        val joinTimeoutMs = computeJoinTimeoutMs(java.time.Instant.now().epochSecond, expiresAtSeconds)
        val joined = withTimeoutOrNull(joinTimeoutMs + 2_000L) {
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
                                claimedClient = null
                            }
                        }
                    }
                }
                continuation.invokeOnCancellation { release() }
                try {
                    cm.requestNetwork(request, callback, joinTimeoutMs.toInt())
                } catch (_: Exception) {
                    release()
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        val remainingMs = (expiresAtSeconds - java.time.Instant.now().epochSecond)
            .coerceIn(0L, 300L) * 1_000L
        return synchronized(this) {
            if (generation == attempt && joined != null && remainingMs > 0 &&
                runCatching { cm.getNetworkCapabilities(joined.network) }.getOrNull() != null) {
                client = joined
                Handler(Looper.getMainLooper()).postDelayed({
                    synchronized(this) {
                        if (client === joined && claimedClient !== joined) {
                            joined.release()
                            client = null
                        }
                    }
                }, remainingMs)
                true
            } else {
                joined?.release?.invoke()
                false
            }
        }
    }

    private fun localAddresses(interfaceName: String? = null): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty()
            .filter { it.isUp && !it.isLoopback && (interfaceName == null || it.name == interfaceName) }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }.toSet()
    }.getOrDefault(emptySet())

    @Suppress("DEPRECATION")
    private fun localSoftApAddresses(wifiManager: WifiManager?): Set<String> = runCatching {
        val wifiStaEnabled = runCatching { wifiManager?.isWifiEnabled }.getOrNull() ?: false
        NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty()
            .filter { it.isUp && !it.isLoopback && isSoftApInterfaceCandidate(it.name.orEmpty(), wifiStaEnabled) }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }.toSet()
    }.getOrDefault(emptySet())

    private fun validCredentials(ssid: String?, password: String?): Boolean =
        !ssid.isNullOrBlank() && ssid.encodeToByteArray().size <= 32 &&
            password != null && password.length in 8..63 && password.all { it in ' '..'~' }
}
