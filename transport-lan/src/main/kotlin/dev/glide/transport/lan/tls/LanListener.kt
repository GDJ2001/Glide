package dev.glide.transport.lan.tls

import android.os.Build
import androidx.annotation.RequiresApi
import dev.glide.transport.lan.network.LanAddressValidationResult
import dev.glide.transport.lan.network.LanAddressValidator
import dev.glide.transport.lan.network.LanNetworkScope
import dev.glide.transport.lan.network.LanNetworkState
import dev.glide.transport.lan.network.SubnetPrefix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Bounded server listener for incoming LAN TLS 1.3 connections (LAN-010, Section 6, 8, 11).
 *
 * Enforces:
 * - Max 2 concurrent pre-auth TLS handshakes.
 * - 10-second handshake deadline.
 * - Rate limiting (5 failures/min per remote address, 20/min overall, 30s cooldown).
 * - Sockets bound to active network and validated against on-link subnets.
 * - TLS 1.3 with ALPN "glide-lan/1".
 */
@RequiresApi(Build.VERSION_CODES.Q)
class LanListener(
    val identity: LanLeaseIdentity,
    private val networkScope: LanNetworkScope? = null,
    bindAddress: InetAddress? = null,
    port: Int = 0,
    private val allowLoopbackForTesting: Boolean = false,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : Closeable {

    private val serverSocket: ServerSocket = ServerSocket()
    val localPort: Int
    val localAddress: InetAddress

    private val sslContext: SSLContext = identity.createServerSSLContext()
    private val preAuthSemaphore = Semaphore(2) // Max 2 concurrent pre-auth handshakes
    val rateLimiter = LanRateLimiter()

    private val _incomingSockets = Channel<SSLSocket>(Channel.BUFFERED)
    val incomingSockets: ReceiveChannel<SSLSocket> = _incomingSockets

    private var isClosed = false

    init {
        serverSocket.reuseAddress = true
        val socketAddress = if (bindAddress != null) {
            InetSocketAddress(bindAddress, port)
        } else {
            InetSocketAddress(port)
        }
        serverSocket.bind(socketAddress)
        localPort = serverSocket.localPort
        localAddress = serverSocket.inetAddress

        networkScope?.registerServerSocket(serverSocket)

        startAcceptLoop()
    }

    private fun startAcceptLoop() {
        coroutineScope.launch {
            while (isActive && !isClosed) {
                val clientSocket: Socket = try {
                    serverSocket.accept()
                } catch (e: Exception) {
                    if (isClosed || !isActive) break
                    continue
                }

                handleInboundConnection(clientSocket)
            }
        }
    }

    private fun handleInboundConnection(rawSocket: Socket) {
        val remoteAddress = rawSocket.inetAddress

        // 1. Rate limiter check (Section 6, Section 20)
        if (rateLimiter.isBlocked(remoteAddress)) {
            try { rawSocket.close() } catch (_: Exception) {}
            return
        }

        // 2. On-link validation check
        val activeSubnets = when (val state = networkScope?.networkState?.value) {
            is LanNetworkState.Available -> state.subnets
            else -> emptyList<SubnetPrefix>()
        }
        val validation = LanAddressValidator.validateAddress(
            address = remoteAddress,
            activeSubnets = activeSubnets,
            allowLoopbackForTesting = allowLoopbackForTesting
        )
        if (validation !is LanAddressValidationResult.Valid) {
            try { rawSocket.close() } catch (_: Exception) {}
            return
        }

        // 3. Bind socket to active network if network scope is present
        networkScope?.bindSocket(rawSocket)

        // 4. Concurrency limit: max 2 concurrent pre-auth handshakes
        if (!preAuthSemaphore.tryAcquire()) {
            // Server busy with pre-auth handshakes; reject immediately
            try { rawSocket.close() } catch (_: Exception) {}
            return
        }

        // 5. Perform TLS handshake on IO dispatcher
        coroutineScope.launch {
            var sslSocket: SSLSocket? = null
            try {
                rawSocket.soTimeout = HANDSHAKE_TIMEOUT_MS

                sslSocket = sslContext.socketFactory.createSocket(
                    rawSocket,
                    null,
                    rawSocket.port,
                    false // do not auto-close underlying socket on wrap failure
                ) as SSLSocket

                sslSocket.useClientMode = false
                sslSocket.enabledProtocols = arrayOf(PinnedTlsConnector.PROTOCOL_TLS_V1_3)

                val params = sslSocket.sslParameters
                params.applicationProtocols = arrayOf(PinnedTlsConnector.ALPN_GLIDE_LAN_V1)
                sslSocket.sslParameters = params
                sslSocket.soTimeout = HANDSHAKE_TIMEOUT_MS

                sslSocket.startHandshake()

                // Verify ALPN & protocol
                val negotiatedProtocol = sslSocket.session.protocol
                val negotiatedAlpn = sslSocket.applicationProtocol

                if (negotiatedProtocol != PinnedTlsConnector.PROTOCOL_TLS_V1_3 ||
                    negotiatedAlpn != PinnedTlsConnector.ALPN_GLIDE_LAN_V1
                ) {
                    throw IOException("Incompatible handshake: protocol=$negotiatedProtocol, alpn=$negotiatedAlpn")
                }

                // Handshake succeeded; emit to channel
                _incomingSockets.send(sslSocket)
            } catch (e: Exception) {
                rateLimiter.recordFailure(remoteAddress)
                try { sslSocket?.close() } catch (_: Exception) {}
                try { rawSocket.close() } catch (_: Exception) {}
            } finally {
                preAuthSemaphore.release()
            }
        }
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true

        try { serverSocket.close() } catch (_: Exception) {}
        _incomingSockets.close()
        coroutineScope.cancel()
    }

    companion object {
        const val HANDSHAKE_TIMEOUT_MS = 10_000
    }
}
