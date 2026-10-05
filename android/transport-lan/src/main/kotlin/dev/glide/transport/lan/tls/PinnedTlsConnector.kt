package dev.glide.transport.lan.tls

import android.os.Build
import androidx.annotation.RequiresApi
import dev.glide.transport.lan.LanConnectionException
import dev.glide.transport.lan.LanConnectionFailure
import dev.glide.transport.lan.network.LanAddressRejectionCode
import dev.glide.transport.lan.network.LanAddressValidationResult
import dev.glide.transport.lan.network.LanAddressValidator
import dev.glide.transport.lan.network.LanNetworkScope
import dev.glide.transport.lan.network.LanNetworkState
import dev.glide.transport.lan.network.SubnetPrefix
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager

/**
 * Connects out to a LAN receiver endpoint with strict SPKI pinning, TLS 1.3, and ALPN "glide-lan/1" (LAN-010, Section 6, 8, 10).
 *
 * Enforces:
 * - Address validation on-link (rejects loopback in production, multicast, off-link).
 * - 5-second TCP connect timeout.
 * - Sockets bound to active network via [LanNetworkScope.bindSocket].
 * - Strict SPKI SHA-256 certificate pin before any application data is sent.
 * - Negotiation of TLS 1.3 and ALPN "glide-lan/1".
 * - Fail-closed cleanup on any handshake error.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class PinnedTlsConnector(
    private val networkScope: LanNetworkScope? = null,
    private val connectTimeoutMs: Int = 5_000,
    private val handshakeTimeoutMs: Int = 10_000,
    private val allowLoopbackForTesting: Boolean = false
) {

    companion object {
        const val ALPN_GLIDE_LAN_V1 = "glide-lan/1"
        const val PROTOCOL_TLS_V1_3 = "TLSv1.3"
    }

    /**
     * Connects to [address] and [port], performing strict pinned TLS handshake.
     *
     * @throws LanConnectionException if the destination is off-link or connection fails with typed failure.
     * @throws SecurityException if the destination address is not on-link or is forbidden.
     * @throws IOException if network connection, TLS handshake, pin verification, or ALPN negotiation fails.
     */
    @Throws(IOException::class, SecurityException::class)
    fun connect(
        address: InetAddress,
        port: Int,
        expectedSpkiSha256Hex: String,
        explicitSubnets: List<SubnetPrefix>? = null
    ): SSLSocket {
        // 1. Destination on-link validation (Section 11, Section 13)
        val activeSubnets = explicitSubnets ?: when (val state = networkScope?.networkState?.value) {
            is LanNetworkState.Available -> state.subnets
            else -> emptyList()
        }

        val validation = LanAddressValidator.validateAddress(
            address = address,
            activeSubnets = activeSubnets,
            allowLoopbackForTesting = allowLoopbackForTesting
        )
        if (validation !is LanAddressValidationResult.Valid) {
            if (validation is LanAddressValidationResult.Rejected &&
                validation.code == LanAddressRejectionCode.OFF_LINK_REJECTED
            ) {
                throw LanConnectionException(LanConnectionFailure.OffLinkDestination())
            }
            throw SecurityException("Destination address validation failed: $validation")
        }

        val rawSocket = Socket()
        var sslSocket: SSLSocket? = null

        try {
            // 2. Bind raw socket to active network if target address belongs to that network's link
            val state = networkScope?.networkState?.value
            if (state is LanNetworkState.Available && state.network != null) {
                val netLinkSubnets = networkScope.context
                    ?.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                    ?.let { it as? android.net.ConnectivityManager }
                    ?.getLinkProperties(state.network)
                    ?.linkAddresses
                    ?.map { SubnetPrefix(it.address, it.prefixLength) }
                    ?: emptyList()
                val isOnManagedNetwork = netLinkSubnets.isEmpty() ||
                    LanAddressValidator.validateAddress(
                        address = address,
                        activeSubnets = netLinkSubnets,
                        allowLoopbackForTesting = allowLoopbackForTesting
                    ) is LanAddressValidationResult.Valid
                if (isOnManagedNetwork) {
                    networkScope.bindSocket(rawSocket)
                }
            }

            // 3. Connect raw TCP socket with 5-second timeout
            rawSocket.connect(InetSocketAddress(address, port), connectTimeoutMs)

            // 4. Wrap with TLS 1.3 and PinnedTrustManager
            val trustManager = PinnedTrustManager(expectedSpkiSha256Hex)
            val sslContext = try {
                SSLContext.getInstance(PROTOCOL_TLS_V1_3)
            } catch (_: Exception) {
                SSLContext.getInstance("TLS")
            }
            sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())

            sslSocket = sslContext.socketFactory.createSocket(
                rawSocket,
                address.hostAddress,
                port,
                true
            ) as SSLSocket

            sslSocket.useClientMode = true
            sslSocket.enabledProtocols = arrayOf(PROTOCOL_TLS_V1_3)

            val params = sslSocket.sslParameters
            params.applicationProtocols = arrayOf(ALPN_GLIDE_LAN_V1)
            sslSocket.sslParameters = params
            sslSocket.soTimeout = handshakeTimeoutMs

            // 5. Perform TLS handshake
            sslSocket.startHandshake()

            // 6. Verify negotiated TLS version and ALPN
            val negotiatedProtocol = sslSocket.session.protocol
            if (negotiatedProtocol != PROTOCOL_TLS_V1_3) {
                throw IOException("Incompatible TLS version: expected $PROTOCOL_TLS_V1_3, got $negotiatedProtocol")
            }

            val negotiatedAlpn = sslSocket.applicationProtocol
            if (negotiatedAlpn != ALPN_GLIDE_LAN_V1) {
                throw IOException("Incompatible ALPN: expected '$ALPN_GLIDE_LAN_V1', got '$negotiatedAlpn'")
            }

            rawSocket.soTimeout = 0
            sslSocket.soTimeout = 0

            return sslSocket
        } catch (e: Exception) {
            try { sslSocket?.close() } catch (_: Exception) {}
            try { rawSocket.close() } catch (_: Exception) {}
            if (e is LanConnectionException) throw e
            if (e is java.net.SocketTimeoutException) {
                throw LanConnectionException(LanConnectionFailure.Timeout(), e)
            }
            if (e is javax.net.ssl.SSLHandshakeException) {
                throw LanConnectionException(LanConnectionFailure.AuthenticationFailed(), e)
            }
            if (e is IOException) throw e
            if (e is SecurityException) throw e
            throw IOException("TLS connection failed: ${e.message}", e)
        }
    }
}
