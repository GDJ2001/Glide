package dev.glide.transport.lan.tls

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.Closeable
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.UUID
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/**
 * Manages an EC P-256 lease key and self-signed certificate for server-authenticated LAN transfer (LAN-010, Section 6).
 *
 * Uses Android KeyStore when available; falls back to in-memory software keys for JVM unit tests.
 */
class LanLeaseIdentity(
    val keyPair: KeyPair,
    val certificate: X509Certificate,
    val alias: String,
    val isAndroidKeyStore: Boolean,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val ttlMillis: Long = 120_000L
) : Closeable {

    /**
     * Exact 64 lowercase hexadecimal SHA-256 digest of the DER SubjectPublicKeyInfo (SPKI).
     */
    val spkiSha256: String = computeSpkiSha256(certificate)

    /**
     * Creates an [SSLContext] initialized with this identity's key manager configured for TLS 1.3.
     */
    fun createServerSSLContext(): SSLContext {
        val keyManager = SingleKeyManager(
            alias = alias,
            privateKey = keyPair.private,
            certificateChain = arrayOf(certificate)
        )
        val sslContext = try {
            SSLContext.getInstance("TLSv1.3")
        } catch (_: Exception) {
            SSLContext.getInstance("TLS")
        }
        sslContext.init(arrayOf<KeyManager>(keyManager), null, SecureRandom())
        return sslContext
    }

    override fun close() {
        if (isAndroidKeyStore) {
            try {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (ks.containsAlias(alias)) {
                    ks.deleteEntry(alias)
                }
            } catch (_: Exception) {}
        }
    }

    override fun toString(): String {
        return "LanLeaseIdentity(alias='$alias', isAndroidKeyStore=$isAndroidKeyStore, spkiSha256='${spkiSha256.take(8)}...')"
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        fun create(
            alias: String = "glide_lan_lease_${UUID.randomUUID()}",
            ttlMillis: Long = 120_000L,
            preferAndroidKeyStore: Boolean = true
        ): LanLeaseIdentity {
            if (preferAndroidKeyStore) {
                try {
                    val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                    val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
                    val spec = KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                    )
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setCertificateSubject(X500Principal("CN=Glide LAN Lease"))
                        .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()).abs())
                        .setCertificateNotBefore(Date(System.currentTimeMillis() - 60_000))
                        .setCertificateNotAfter(Date(System.currentTimeMillis() + ttlMillis))
                        .build()
                    kpg.initialize(spec)
                    val keyPair = kpg.generateKeyPair()
                    val cert = ks.getCertificate(alias) as X509Certificate

                    return LanLeaseIdentity(
                        keyPair = keyPair,
                        certificate = cert,
                        alias = alias,
                        isAndroidKeyStore = true,
                        ttlMillis = ttlMillis
                    )
                } catch (_: Throwable) {
                    // Fallback to software EC P-256 generator (e.g. JVM testing or missing KeyStore)
                }
            }

            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val keyPair = kpg.generateKeyPair()
            val cert = SelfSignedCertificateGenerator.generate(
                keyPair = keyPair,
                commonName = "Glide LAN Lease",
                notBefore = Date(System.currentTimeMillis() - 60_000),
                notAfter = Date(System.currentTimeMillis() + ttlMillis)
            )

            return LanLeaseIdentity(
                keyPair = keyPair,
                certificate = cert,
                alias = alias,
                isAndroidKeyStore = false,
                ttlMillis = ttlMillis
            )
        }

        fun computeSpkiSha256(certificate: X509Certificate): String {
            val spkiBytes = certificate.publicKey.encoded
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(spkiBytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * In-memory key manager that provides this identity's key pair directly without keystore serialization.
     */
    private class SingleKeyManager(
        private val alias: String,
        private val privateKey: PrivateKey,
        private val certificateChain: Array<X509Certificate>
    ) : X509ExtendedKeyManager() {

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = arrayOf(alias)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = alias
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = arrayOf(alias)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String = alias
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String = alias
        override fun chooseEngineClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String = alias
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = certificateChain
        override fun getPrivateKey(alias: String?): PrivateKey = privateKey
    }
}
