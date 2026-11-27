package dev.glide.transport.lan.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import dev.glide.transport.lan.network.LanAddressValidationResult
import dev.glide.transport.lan.network.LanAddressValidator
import dev.glide.transport.lan.network.LanNetworkScope
import dev.glide.transport.lan.network.LanNetworkState
import dev.glide.transport.lan.network.SubnetPrefix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Collections

/**
 * Lightweight on-link UDP presence beacon and unicast/broadcast probe for SoftAP/Hotspot
 * and local Wi-Fi subnets where OS mDNS (`NsdManager`) is unavailable or drops packets.
 *
 * Security invariants:
 * - Only advertises the same public [LanTxtRecord] fields as DNS-SD (`v, tv, q, p, dn, dt, tr`)
 *   plus the TLS listener TCP port; never transmits tokens, SPKI pins, or identity keys.
 * - Strictly validates every peer and target IP against [LanNetworkScope] active subnets via
 *   [LanAddressValidator] (off-link, loopback in prod, multicast, and broadcast destinations rejected
 *   where appropriate).
 */
class LanSubnetPresenceBeacon(
    private val networkScope: LanNetworkScope,
    private val allowLoopbackForTesting: Boolean = false,
    private val beaconPort: Int = DEFAULT_BEACON_PORT,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : Closeable {

    private val lock = Any()
    private val candidates = Collections.synchronizedMap(
        LinkedHashMap<String, NsdDiscoveryAdapter.TrustedReconnectCandidate>()
    )

    private var responderSocket: DatagramSocket? = null
    private var responderJob: Job? = null
    @Volatile private var advertisedPayload: ByteArray? = null
    @Volatile private var isClosed = false

    val activeResponderPort: Int
        get() = responderSocket?.localPort ?: -1

    fun startAdvertising(
        deviceName: String,
        localPort: Int,
        presenceId: String,
        supportsTrustedReconnect: Boolean = true,
        isPairReady: Boolean = false
    ) {
        if (isClosed) return
        require(localPort in 1..65535) { "Invalid localPort: $localPort" }

        val record = LanTxtRecord(
            v = 1,
            tv = 1,
            q = 1,
            p = presenceId,
            dn = deviceName.take(LanTxtRecord.MAX_DN_CODE_POINTS).ifBlank { "Glide Device" },
            dt = LanTxtRecord.DEVICE_TYPE_PHONE,
            isPairReady = isPairReady,
            supportsTrustedReconnect = supportsTrustedReconnect
        )
        val payload = encodeResponse(localPort, record)

        synchronized(lock) {
            stopAdvertisingLocked()
            advertisedPayload = payload

            val socket = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1_000
                    bind(InetSocketAddress(beaconPort))
                }
            }.getOrNull() ?: return
            responderSocket = socket

            responderJob = coroutineScope.launch(Dispatchers.IO) {
                val buf = ByteArray(MAX_PACKET_BYTES)
                val packet = DatagramPacket(buf, buf.size)
                while (isActive && !socket.isClosed) {
                    try {
                        socket.receive(packet)
                        if (isQueryPacket(packet.data, packet.length)) {
                            handleQueryPacket(socket, packet.address, packet.port, payload)
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Normal poll tick so coroutine cancellation & socket shutdown stay responsive
                    } catch (_: CancellationException) {
                        break
                    } catch (_: Exception) {
                        if (socket.isClosed) break
                    }
                }
            }
        }
    }

    fun stopAdvertising() {
        synchronized(lock) {
            stopAdvertisingLocked()
        }
    }

    private fun stopAdvertisingLocked() {
        advertisedPayload = null
        responderJob?.cancel()
        responderJob = null
        runCatching { responderSocket?.close() }
        responderSocket = null
    }

    /**
     * Probes all active on-link subnets (directed broadcast, gateway/.1, and ARP neighbors)
     * and returns validated responders discovered within [timeoutMs].
     */
    suspend fun probeOnce(
        timeoutMs: Long = DEFAULT_PROBE_TIMEOUT_MS,
        targetPortOverride: Int = beaconPort
    ): List<NsdDiscoveryAdapter.TrustedReconnectCandidate> = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext emptyList()
        var state = networkScope.networkState.value as? LanNetworkState.Available
        if (state == null) {
            networkScope.evaluateLocalInterfacesFallback()
            state = networkScope.networkState.value as? LanNetworkState.Available
        }
        val available = state ?: return@withContext emptyList()
        val targets = collectProbeTargets(available)
        if (targets.isEmpty()) return@withContext emptyList()

        val probeSocket = runCatching {
            DatagramSocket(0).apply {
                broadcast = true
                soTimeout = 150
            }
        }.getOrNull() ?: return@withContext emptyList()

        // Bind to the managed network or primary local interface when sending so Cellular/VPN does
        // not hijack UDP probes to private SoftAP gateways (e.g. 192.168.43.1 / 10.158.75.252).
        probeSocket.use { sock ->
            if (!allowLoopbackForTesting && available.network != null) {
                runCatching { available.network.bindSocket(sock) }
            }
            val queryBytes = QUERY_PREFIX.toByteArray(StandardCharsets.US_ASCII)
            val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
            var nextSendNs = 0L

            while (isActive && System.nanoTime() < deadlineNs) {
                val nowNs = System.nanoTime()
                if (nowNs >= nextSendNs) {
                    for (target in targets) {
                        runCatching {
                            sock.send(DatagramPacket(queryBytes, queryBytes.size, target, targetPortOverride))
                        }
                    }
                    nextSendNs = nowNs + PROBE_RETRY_INTERVAL_MS * 1_000_000L
                }

                val buf = ByteArray(MAX_PACKET_BYTES)
                val reply = DatagramPacket(buf, buf.size)
                val received = try {
                    sock.receive(reply)
                    true
                } catch (_: java.net.SocketTimeoutException) {
                    false
                } catch (_: Exception) {
                    false
                }
                if (received) {
                    recordResponseIfValid(
                        sender = reply.address,
                        bytes = reply.data,
                        length = reply.length,
                        available = available
                    )
                    if (System.nanoTime() - (deadlineNs - timeoutMs * 1_000_000L) >= EARLY_COLLECT_MIN_MS * 1_000_000L &&
                        candidates.isNotEmpty()
                    ) {
                        // Drain any immediate concurrent replies for 100ms, then return fast.
                        sock.soTimeout = 100
                        while (isActive && System.nanoTime() < deadlineNs) {
                            val extra = DatagramPacket(ByteArray(MAX_PACKET_BYTES), MAX_PACKET_BYTES)
                            val gotExtra = try {
                                sock.receive(extra)
                                true
                            } catch (_: Exception) {
                                false
                            }
                            if (!gotExtra) break
                            recordResponseIfValid(extra.address, extra.data, extra.length, available)
                        }
                        break
                    }
                }
                delay(10)
            }
        }
        getCandidatesSnapshot(available.generation)
    }

    fun getCandidatesSnapshot(networkGeneration: Long? = null): List<NsdDiscoveryAdapter.TrustedReconnectCandidate> =
        synchronized(candidates) {
            candidates.values.filter { networkGeneration == null || it.networkGeneration == networkGeneration }
        }

    fun clearObservations() {
        synchronized(candidates) {
            candidates.clear()
        }
    }

    private fun handleQueryPacket(
        socket: DatagramSocket,
        sender: InetAddress,
        senderPort: Int,
        payload: ByteArray
    ) {
        if (senderPort !in 1..65535) return
        var state = networkScope.networkState.value as? LanNetworkState.Available
        if (!allowLoopbackForTesting) {
            val ownAddrs = state?.localAddresses?.mapNotNull { it.hostAddress }.orEmpty()
            if (sender.hostAddress in ownAddrs) return
        }
        var validation = LanAddressValidator.validateAddress(
            address = sender,
            activeSubnets = state?.subnets.orEmpty(),
            allowLoopbackForTesting = allowLoopbackForTesting
        )
        if (validation !is LanAddressValidationResult.Valid) {
            networkScope.evaluateLocalInterfacesFallback()
            state = networkScope.networkState.value as? LanNetworkState.Available
            validation = LanAddressValidator.validateAddress(
                address = sender,
                activeSubnets = state?.subnets.orEmpty(),
                allowLoopbackForTesting = allowLoopbackForTesting
            )
        }
        if (validation !is LanAddressValidationResult.Valid) return
        runCatching {
            socket.send(DatagramPacket(payload, payload.size, sender, senderPort))
        }
    }

    private fun recordResponseIfValid(
        sender: InetAddress,
        bytes: ByteArray,
        length: Int,
        available: LanNetworkState.Available
    ) {
        if (!allowLoopbackForTesting) {
            val own = available.localAddresses.mapNotNull { it.hostAddress }.toSet()
            if (sender.hostAddress in own) return
        }
        val validation = LanAddressValidator.validateAddress(
            address = sender,
            activeSubnets = available.subnets,
            allowLoopbackForTesting = allowLoopbackForTesting
        )
        if (validation !is LanAddressValidationResult.Valid) return

        val parsed = decodeResponse(bytes, length) ?: return
        val (tcpPort, txtRecord) = parsed
        if (!txtRecord.supportsTrustedReconnect) return
        val hostIp = sender.hostAddress?.substringBefore('%') ?: return

        val key = "$hostIp:$tcpPort:${txtRecord.p}"
        synchronized(candidates) {
            if (candidates.size >= MAX_CANDIDATES && !candidates.containsKey(key)) {
                val oldest = candidates.keys.firstOrNull()
                if (oldest != null) candidates.remove(oldest)
            }
            candidates[key] = NsdDiscoveryAdapter.TrustedReconnectCandidate(
                displayName = txtRecord.dn,
                address = hostIp,
                port = tcpPort,
                presenceId = txtRecord.p,
                observedAtMs = System.currentTimeMillis(),
                networkGeneration = available.generation
            )
        }
    }

    private fun collectProbeTargets(available: LanNetworkState.Available): List<InetAddress> {
        val ownAddresses = available.localAddresses.mapNotNull { it.hostAddress }.toSet()
        val results = linkedSetOf<InetAddress>()

        if (allowLoopbackForTesting && available.subnets.any { it.address.isLoopbackAddress }) {
            runCatching { InetAddress.getByName("127.0.0.1") }.getOrNull()?.let { results.add(it) }
        }

        // 1. OS LinkProperties routes & DHCP server address (captures randomized Android SoftAP gateways
        // such as OPPO Android 13 10.158.75.252/24).
        val net = available.network
        val ctx = networkScope.context
        if (net != null && ctx != null) {
            val cm = runCatching {
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            }.getOrNull()
            val lp = runCatching { cm?.getLinkProperties(net) }.getOrNull()
            if (lp != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    lp.dhcpServerAddress?.takeIf {
                        it is Inet4Address && !it.isLoopbackAddress && it.hostAddress !in ownAddresses
                    }?.let { results.add(it) }
                }
                for (route in lp.routes) {
                    val gw = route.gateway ?: continue
                    if (gw is Inet4Address && !gw.isLoopbackAddress && !gw.isAnyLocalAddress &&
                        gw.hostAddress !in ownAddresses &&
                        available.subnets.any { it.matches(gw) }
                    ) {
                        results.add(gw)
                    }
                }
            }
        }

        // 2. Per-subnet directed broadcast + .1 gateway host for IPv4 subnets
        for (subnet in available.subnets) {
            val v4 = subnet.address as? Inet4Address ?: continue
            if (v4.isLoopbackAddress || v4.isLinkLocalAddress || v4.isAnyLocalAddress) continue
            subnetGatewayDotOne(v4, subnet.prefixLength)?.let { gw ->
                if (gw.hostAddress !in ownAddresses) results.add(gw)
            }
            subnetDirectedBroadcast(v4, subnet.prefixLength)?.let { bcast ->
                results.add(bcast)
            }
        }

        // 3. Best-effort ARP table neighbors (reaches connected SoftAP clients by unicast on API <= 29
        // when broadcast packets are filtered by Wi-Fi firmware).
        readArpNeighbors(available.subnets, ownAddresses).forEach { results.add(it) }

        return results.toList()
    }

    private fun readArpNeighbors(subnets: List<SubnetPrefix>, ownAddresses: Set<String>): List<InetAddress> {
        val found = mutableListOf<InetAddress>()
        runCatching {
            val arp = File("/proc/net/arp")
            if (!arp.canRead()) return@runCatching
            arp.forEachLine { line ->
                val cols = line.trim().split(WHITESPACE_REGEX)
                if (cols.size >= 4) {
                    val ip = cols[0]
                    val mac = cols[3]
                    if (mac != "00:00:00:00:00:00" && mac.contains(':') &&
                        ip !in ownAddresses && LanAddressValidator.isNumericIpLiteral(ip)
                    ) {
                        val addr = runCatching { InetAddress.getByName(ip) }.getOrNull()
                        if (addr is Inet4Address && subnets.any { it.matches(addr) }) {
                            found.add(addr)
                        }
                    }
                }
            }
        }
        return found
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        stopAdvertising()
        clearObservations()
    }

    companion object {
        const val DEFAULT_BEACON_PORT = 45848
        const val DEFAULT_PROBE_TIMEOUT_MS = 1_200L
        private const val EARLY_COLLECT_MIN_MS = 220L
        private const val PROBE_RETRY_INTERVAL_MS = 280L
        private const val MAX_PACKET_BYTES = 768
        private const val QUERY_PREFIX = "GLIDE_UDP_Q1\n"
        private const val RESPONSE_PREFIX = "GLIDE_UDP_R1\n"
        private val WHITESPACE_REGEX = Regex("\\s+")

        internal fun encodeResponse(localPort: Int, record: LanTxtRecord): ByteArray {
            val encoded = LanTxtCodec.encode(record)
            val out = ByteArrayOutputStream()
            out.write("$RESPONSE_PREFIX$localPort\n".toByteArray(StandardCharsets.US_ASCII))
            for ((key, value) in encoded) {
                val keyBytes = key.toByteArray(StandardCharsets.US_ASCII)
                val entryLen = keyBytes.size + 1 + value.size
                require(entryLen <= 255) { "TXT entry too long: $entryLen" }
                out.write(entryLen)
                out.write(keyBytes)
                out.write('='.code)
                out.write(value)
            }
            return out.toByteArray()
        }

        internal fun decodeResponse(bytes: ByteArray, length: Int = bytes.size): Pair<Int, LanTxtRecord>? {
            val prefixBytes = RESPONSE_PREFIX.toByteArray(StandardCharsets.US_ASCII)
            if (length <= prefixBytes.size) return null
            for (i in prefixBytes.indices) {
                if (bytes[i] != prefixBytes[i]) return null
            }
            var newlineIdx = -1
            for (i in prefixBytes.size until minOf(length, prefixBytes.size + 10)) {
                if (bytes[i] == '\n'.code.toByte()) {
                    newlineIdx = i
                    break
                }
            }
            if (newlineIdx <= prefixBytes.size) return null
            val portStr = String(bytes, prefixBytes.size, newlineIdx - prefixBytes.size, StandardCharsets.US_ASCII)
            val tcpPort = portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null

            val attributes = linkedMapOf<String, ByteArray?>()
            var cursor = newlineIdx + 1
            while (cursor < length) {
                val entryLen = bytes[cursor++].toInt() and 0xFF
                if (entryLen <= 0 || cursor + entryLen > length) return null
                var eqOffset = -1
                for (j in 0 until entryLen) {
                    if (bytes[cursor + j] == '='.code.toByte()) {
                        eqOffset = j
                        break
                    }
                }
                if (eqOffset <= 0) return null
                val key = String(bytes, cursor, eqOffset, StandardCharsets.US_ASCII)
                val valStart = cursor + eqOffset + 1
                val valLen = entryLen - eqOffset - 1
                val value = bytes.copyOfRange(valStart, valStart + valLen)
                attributes[key] = value
                cursor += entryLen
            }

            val record = LanTxtCodec.decode(attributes) ?: return null
            return tcpPort to record
        }

        private fun isQueryPacket(bytes: ByteArray, length: Int): Boolean {
            val prefix = QUERY_PREFIX.toByteArray(StandardCharsets.US_ASCII)
            if (length < prefix.size || length > 64) return false
            for (i in prefix.indices) {
                if (bytes[i] != prefix[i]) return false
            }
            return true
        }

        internal fun subnetDirectedBroadcast(address: Inet4Address, prefixLength: Int): InetAddress? {
            if (prefixLength !in 16..30) return null
            val ip = ipv4ToInt(address.address)
            val mask = (-1 shl (32 - prefixLength))
            val bcast = ip or mask.inv()
            return intToIpv4(bcast)
        }

        internal fun subnetGatewayDotOne(address: Inet4Address, prefixLength: Int): InetAddress? {
            if (prefixLength !in 16..29) return null
            val ip = ipv4ToInt(address.address)
            val mask = (-1 shl (32 - prefixLength))
            val dotOne = (ip and mask) or 1
            return intToIpv4(dotOne)
        }

        private fun ipv4ToInt(bytes: ByteArray): Int =
            ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)

        private fun intToIpv4(value: Int): InetAddress? = runCatching {
            InetAddress.getByAddress(
                byteArrayOf(
                    ((value ushr 24) and 0xFF).toByte(),
                    ((value ushr 16) and 0xFF).toByte(),
                    ((value ushr 8) and 0xFF).toByte(),
                    (value and 0xFF).toByte()
                )
            )
        }.getOrNull()

        private const val MAX_CANDIDATES = 64
    }
}
