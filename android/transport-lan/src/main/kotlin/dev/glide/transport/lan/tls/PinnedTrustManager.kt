package dev.glide.transport.lan.tls

import android.annotation.SuppressLint
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Strict SPKI SHA-256 pinning trust manager for server-authenticated LAN transfer (LAN-010, Section 6).
 *
 * Enforces:
 * 1. Non-empty certificate chain with a self-signed EC P-256 leaf.
 * 2. Current validity (not expired or before validity).
 * 3. Valid self-signature.
 * 4. Constant-time match against the expected 64-character lowercase hex SPKI digest.
 * 5. Rejection of client certificate authentication.
 */
@SuppressLint("CustomX509TrustManager")
class PinnedTrustManager(
    private val expectedSpkiSha256Hex: String
) : X509ExtendedTrustManager() {

    init {
        require(expectedSpkiSha256Hex.length == 64 && expectedSpkiSha256Hex.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            "Invalid SPKI SHA-256 hex string: length must be 64 hexadecimal characters"
        }
    }

    private val normalizedExpectedHex = expectedSpkiSha256Hex.lowercase()

    @Throws(CertificateException::class)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) {
            throw CertificateException("Server certificate chain is empty or null")
        }

        val leaf = chain[0]

        // 1. Verify temporal validity
        try {
            leaf.checkValidity()
        } catch (e: Exception) {
            throw CertificateException("Server certificate is expired or not yet valid", e)
        }

        // 2. Verify EC public key
        if (!leaf.publicKey.algorithm.equals("EC", ignoreCase = true)) {
            throw CertificateException("Server public key algorithm must be EC, found: ${leaf.publicKey.algorithm}")
        }

        // 3. Verify self-signed signature
        try {
            leaf.verify(leaf.publicKey)
        } catch (e: Exception) {
            throw CertificateException("Server certificate self-signature verification failed", e)
        }

        // 4. Constant-time SPKI SHA-256 pin verification
        val spkiBytes = leaf.publicKey.encoded
        val md = MessageDigest.getInstance("SHA-256")
        val computedDigest = md.digest(spkiBytes)
        val computedHex = computedDigest.joinToString("") { "%02x".format(it) }

        val matches = MessageDigest.isEqual(
            computedHex.toByteArray(Charsets.US_ASCII),
            normalizedExpectedHex.toByteArray(Charsets.US_ASCII)
        )

        if (!matches) {
            throw CertificateException("Pinned SPKI mismatch: expected $normalizedExpectedHex, got $computedHex")
        }
    }

    @Throws(CertificateException::class)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
        checkServerTrusted(chain, authType)
    }

    @Throws(CertificateException::class)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
        checkServerTrusted(chain, authType)
    }

    @Throws(CertificateException::class)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // LAN profile 1 does not support client certificate authentication; client possession is authenticated via token
        throw CertificateException("Client certificate authentication is not supported in Glide LAN profile 1")
    }

    @Throws(CertificateException::class)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
        checkClientTrusted(chain, authType)
    }

    @Throws(CertificateException::class)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
        checkClientTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
