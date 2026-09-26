package dev.glide.transport.lan.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * Observable active network state for LAN transfer (LAN-009, Section 6, Section 11).
 */
sealed interface LanNetworkState {
    data object NoNetwork : LanNetworkState
    data class VpnActive(val reason: String) : LanNetworkState
    data class Available(
        val network: Network? = null,
        val localAddresses: List<InetAddress>,
        val subnets: List<SubnetPrefix>,
        val generation: Long
    ) : LanNetworkState
    data object Closed : LanNetworkState
}

/**
 * Manages the active network lifecycle, bound sockets, and VPN fail-closed policy (LAN-009, Section 11, Section 13).
 *
 * Sockets are strictly bound to the active [Network] via [Network.bindSocket] rather than
 * process-wide network rebinding.
 */
open class LanNetworkScope(
    val context: Context? = null,
    private val connectivityManager: ConnectivityManager? = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : Closeable {

    private val _networkState = MutableStateFlow<LanNetworkState>(LanNetworkState.NoNetwork)
    val networkState: StateFlow<LanNetworkState> = _networkState.asStateFlow()

    private var currentGeneration: Long = 0L
    private val activeSockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val activeServerSockets = Collections.synchronizedSet(mutableSetOf<ServerSocket>())

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isRegistered = false

    init {
        registerNetworkCallback()
    }

    fun updateNetworkStateForTesting(state: LanNetworkState) {
        _networkState.value = state
    }

    fun onNetworkLostForTesting(network: Network? = null) {
        synchronized(this) {
            val current = _networkState.value
            if (current is LanNetworkState.Available && (network == null || current.network == network)) {
                invalidateAndCloseSockets("Network lost")
                _networkState.value = LanNetworkState.NoNetwork
            }
        }
    }

    fun onVpnActivatedForTesting(reason: String = "Active VPN detected") {
        synchronized(this) {
            invalidateAndCloseSockets("Active VPN detected on network")
            _networkState.value = LanNetworkState.VpnActive("Active VPN detected; LAN sharing fails closed per security policy")
        }
    }

    fun onNetworkSwitchedForTesting(newSubnets: List<SubnetPrefix>, newAddresses: List<InetAddress>) {
        synchronized(this) {
            invalidateAndCloseSockets("Active network changed")
            currentGeneration++
            _networkState.value = LanNetworkState.Available(
                network = null,
                localAddresses = newAddresses,
                subnets = newSubnets,
                generation = currentGeneration
            )
        }
    }

    private fun registerNetworkCallback() {
        val cm = connectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                evaluateNetwork(network)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                evaluateNetwork(network, networkCapabilities)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                evaluateNetwork(network, linkProperties = linkProperties)
            }

            override fun onLost(network: Network) {
                synchronized(this@LanNetworkScope) {
                    val current = _networkState.value
                    if (current is LanNetworkState.Available && current.network == network) {
                        invalidateAndCloseSockets("Network lost")
                        _networkState.value = LanNetworkState.NoNetwork
                    }
                }
            }
        }

        try {
            cm.activeNetwork?.let { active ->
                evaluateNetwork(active)
            }
            if (_networkState.value !is LanNetworkState.Available) {
                evaluateLocalInterfacesFallback()
            }
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
            isRegistered = true
        } catch (e: Exception) {
            evaluateLocalInterfacesFallback()
        }
    }

    private fun collectLocalInterfaceSubnets(): Pair<List<InetAddress>, List<SubnetPrefix>> {
        val addrs = mutableListOf<InetAddress>()
        val prefixes = mutableListOf<SubnetPrefix>()
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return emptyList<InetAddress>() to emptyList()
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                val isEligibleLocalLink =
                    name.startsWith("wlan") ||
                    name.startsWith("eth") ||
                    name.startsWith("rndis") ||
                    name.startsWith("usb") ||
                    name.startsWith("ncm") ||
                    name.startsWith("ap") ||
                    name.startsWith("swlan") ||
                    name.startsWith("p2p")
                if (!isEligibleLocalLink) continue
                for (ia in ni.interfaceAddresses) {
                    val addr = ia.address ?: continue
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val prefixLen = ia.networkPrefixLength.toInt().let {
                        if (it > 0) it else if (addr is java.net.Inet4Address) 24 else 64
                    }
                    addrs.add(addr)
                    prefixes.add(SubnetPrefix(addr, prefixLen))
                }
            }
        } catch (_: Exception) {}
        return addrs to prefixes
    }

    @Synchronized
    fun evaluateLocalInterfacesFallback() {
        if (_networkState.value is LanNetworkState.Closed) return
        val (localAddrs, localSubnets) = collectLocalInterfaceSubnets()
        if (localSubnets.isNotEmpty()) {
            currentGeneration++
            _networkState.value = LanNetworkState.Available(
                network = null,
                localAddresses = localAddrs,
                subnets = localSubnets,
                generation = currentGeneration
            )
        }
    }

    @Synchronized
    fun evaluateNetwork(
        network: Network,
        capabilities: NetworkCapabilities? = connectivityManager?.getNetworkCapabilities(network),
        linkProperties: LinkProperties? = connectivityManager?.getLinkProperties(network)
    ) {
        if (_networkState.value is LanNetworkState.Closed) return

        if (capabilities == null || linkProperties == null) {
            evaluateLocalInterfacesFallback()
            return
        }

        // VPN Fail-Closed Policy (Section 11, Section 13):
        // If VPN transport is present or NOT_VPN capability is missing, fail-closed!
        val hasVpnTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val lacksNotVpnCapability = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        if (hasVpnTransport || lacksNotVpnCapability) {
            invalidateAndCloseSockets("Active VPN detected on network")
            _networkState.value = LanNetworkState.VpnActive("Active VPN detected; LAN sharing fails closed per security policy")
            return
        }

        val (extraAddrs, extraSubnets) = collectLocalInterfaceSubnets()

        // Extract subnets and local addresses (merging active network + USB RNDIS / Hotspot local interfaces)
        val subnets = (linkProperties.linkAddresses.map {
            SubnetPrefix(it.address, it.prefixLength)
        } + extraSubnets).distinct()
        val localAddresses = (linkProperties.linkAddresses.map { it.address } + extraAddrs).distinct()

        if (subnets.isEmpty()) {
            _networkState.value = LanNetworkState.NoNetwork
            return
        }

        val previous = _networkState.value
        val isSameNetwork = previous is LanNetworkState.Available && previous.network == network
        if (!isSameNetwork) {
            invalidateAndCloseSockets("Active network changed")
            currentGeneration++
        }

        _networkState.value = LanNetworkState.Available(
            network = network,
            localAddresses = localAddresses,
            subnets = subnets,
            generation = currentGeneration
        )
    }

    /**
     * Creates and connects a client socket bound to the active network interface.
     */
    fun createBoundSocket(
        targetAddress: String,
        targetPort: Int,
        timeoutMs: Int = 10_000,
        allowLoopbackForTesting: Boolean = false
    ): Socket {
        val state = _networkState.value
        if (state !is LanNetworkState.Available) {
            throw IllegalStateException("Cannot create socket: active local network is not available (current: $state)")
        }

        val validation = LanAddressValidator.validateAddress(
            rawAddress = targetAddress,
            activeSubnets = state.subnets,
            allowLoopbackForTesting = allowLoopbackForTesting
        )
        if (validation is LanAddressValidationResult.Rejected) {
            throw IllegalArgumentException("Target address rejected: ${validation.reason} (${validation.code})")
        }

        val socket = Socket()
        try {
            state.network?.bindSocket(socket)
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000

            val inetAddress = InetAddress.getByName(targetAddress)
            socket.connect(InetSocketAddress(inetAddress, targetPort), timeoutMs)

            activeSockets.add(socket)
            return socket
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Creates a listening server socket bound to the local network port.
     */
    fun createBoundServerSocket(
        port: Int = 0,
        backlog: Int = 50
    ): ServerSocket {
        val state = _networkState.value
        if (state !is LanNetworkState.Available) {
            throw IllegalStateException("Cannot create server socket: active local network is not available")
        }

        val serverSocket = ServerSocket()
        try {
            serverSocket.reuseAddress = true
            val bindAddress = state.localAddresses.firstOrNull()
            if (bindAddress != null) {
                serverSocket.bind(InetSocketAddress(bindAddress, port), backlog)
            } else {
                serverSocket.bind(InetSocketAddress(port), backlog)
            }

            activeServerSockets.add(serverSocket)
            return serverSocket
        } catch (e: Exception) {
            try { serverSocket.close() } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Accepts an incoming client socket and binds it to the active network interface.
     */
    fun acceptBoundSocket(serverSocket: ServerSocket): Socket {
        val clientSocket = serverSocket.accept()
        val state = _networkState.value
        if (state !is LanNetworkState.Available) {
            try { clientSocket.close() } catch (_: Exception) {}
            throw IllegalStateException("Network not available while accepting client socket")
        }
        try {
            state.network?.bindSocket(clientSocket)
            clientSocket.tcpNoDelay = true
            clientSocket.soTimeout = 30_000
            activeSockets.add(clientSocket)
            return clientSocket
        } catch (e: Exception) {
            try { clientSocket.close() } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Binds a pre-created socket to the active network interface and registers it for lifecycle management.
     */
    fun bindSocket(socket: Socket) {
        val state = _networkState.value
        if (state is LanNetworkState.Available) {
            try {
                state.network?.bindSocket(socket)
            } catch (_: Exception) {}
        }
        activeSockets.add(socket)
    }

    /**
     * Registers a pre-created server socket for lifecycle tracking and cleanup on network loss.
     */
    fun registerServerSocket(serverSocket: ServerSocket) {
        activeServerSockets.add(serverSocket)
    }

    fun unregisterSocket(socket: Socket) {
        activeSockets.remove(socket)
    }

    fun unregisterServerSocket(serverSocket: ServerSocket) {
        activeServerSockets.remove(serverSocket)
    }

    @Synchronized
    private fun invalidateAndCloseSockets(reason: String) {
        val socketsToClose = synchronized(activeSockets) {
            val list = activeSockets.toList()
            activeSockets.clear()
            list
        }
        for (s in socketsToClose) {
            try { s.close() } catch (_: Exception) {}
        }

        val serverSocketsToClose = synchronized(activeServerSockets) {
            val list = activeServerSockets.toList()
            activeServerSockets.clear()
            list
        }
        for (ss in serverSocketsToClose) {
            try { ss.close() } catch (_: Exception) {}
        }
    }

    @Synchronized
    override fun close() {
        if (_networkState.value is LanNetworkState.Closed) return

        _networkState.value = LanNetworkState.Closed
        invalidateAndCloseSockets("LanNetworkScope closed")

        if (isRegistered && networkCallback != null) {
            try {
                connectivityManager?.unregisterNetworkCallback(networkCallback!!)
            } catch (_: Exception) {}
            networkCallback = null
            isRegistered = false
        }

        coroutineScope.cancel()
    }
}
