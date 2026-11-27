package dev.glide.transport.lan.tls

import android.os.Build
import org.conscrypt.Conscrypt
import java.security.Provider
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Provides genuine TLS 1.3 and ALPN ("glide-lan/1") across all supported Android versions.
 *
 * - Android 10+ (API 29+) and JVM unit tests (`SDK_INT == 0`): uses the standard platform JSSE provider.
 * - Android 8.0–9 (API 26–28): lazily instantiates bundled Conscrypt without mutating global
 *   `java.security.Security` provider registrations or interfering with `AndroidKeyStore`.
 */
internal object LanTlsProvider {

    private const val PROTOCOL_TLS_V1_3 = "TLSv1.3"

    private val conscryptProvider: Provider by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Conscrypt.newProvider()
    }

    fun usesBundledConscrypt(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt in 1 until Build.VERSION_CODES.Q

    fun createTls13Context(sdkInt: Int = Build.VERSION.SDK_INT): SSLContext {
        if (usesBundledConscrypt(sdkInt)) {
            return SSLContext.getInstance(PROTOCOL_TLS_V1_3, conscryptProvider)
        }
        return try {
            SSLContext.getInstance(PROTOCOL_TLS_V1_3)
        } catch (_: Exception) {
            SSLContext.getInstance("TLS")
        }
    }

    fun configureAlpn(
        sslSocket: SSLSocket,
        protocols: Array<String>,
        sdkInt: Int = Build.VERSION.SDK_INT
    ) {
        if (usesBundledConscrypt(sdkInt)) {
            if (Conscrypt.isConscrypt(sslSocket)) {
                Conscrypt.setApplicationProtocols(sslSocket, protocols)
            }
            return
        }
        if (sdkInt == 0 || sdkInt >= Build.VERSION_CODES.Q) {
            val params = sslSocket.sslParameters
            params.applicationProtocols = protocols
            sslSocket.sslParameters = params
        }
    }

    fun supportsAlpn(sslSocket: SSLSocket, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        if (sdkInt == 0 || sdkInt >= Build.VERSION_CODES.Q) return true
        return usesBundledConscrypt(sdkInt) && Conscrypt.isConscrypt(sslSocket)
    }

    fun negotiatedAlpn(sslSocket: SSLSocket, sdkInt: Int = Build.VERSION.SDK_INT): String? {
        if (usesBundledConscrypt(sdkInt)) {
            return if (Conscrypt.isConscrypt(sslSocket)) {
                Conscrypt.getApplicationProtocol(sslSocket)
            } else {
                null
            }
        }
        if (sdkInt == 0 || sdkInt >= Build.VERSION_CODES.Q) {
            return sslSocket.applicationProtocol
        }
        return null
    }
}
