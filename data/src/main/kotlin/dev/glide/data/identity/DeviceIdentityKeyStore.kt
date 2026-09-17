package dev.glide.data.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.glide.core.identity.PeerIdentityVerifier
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

/**
 * Interface for hardware-backed persistent identity key management (ADR-0015, GLIDE-070).
 */
interface DeviceIdentityKeyStore {
    fun getOrCreateKeyPair(): KeyPair
    fun getPublicKeyDer(): ByteArray
    fun getFingerprint(): String
    fun signNonce(nonce: ByteArray): ByteArray
}

/**
 * Android Keystore implementation generating hardware-isolated EC P-256 keys (secp256r1)
 * with SHA-256 signing and verification capabilities.
 */
class AndroidDeviceIdentityKeyStore(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : DeviceIdentityKeyStore {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply {
        load(null)
    }

    override fun getOrCreateKeyPair(): KeyPair {
        if (keyStore.containsAlias(keyAlias)) {
            val privateKey = keyStore.getKey(keyAlias, null) as PrivateKey
            val certificate = keyStore.getCertificate(keyAlias)
            val publicKey = certificate.publicKey
            return KeyPair(publicKey, privateKey)
        }

        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEY_STORE
        )

        val specBuilder = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        ).apply {
            setDigests(KeyProperties.DIGEST_SHA256)
            setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            setUserAuthenticationRequired(false)
        }

        keyPairGenerator.initialize(specBuilder.build())
        return keyPairGenerator.generateKeyPair()
    }

    override fun getPublicKeyDer(): ByteArray {
        return getOrCreateKeyPair().public.encoded
    }

    override fun getFingerprint(): String {
        return PeerIdentityVerifier.deriveFingerprint(getPublicKeyDer())
    }

    override fun signNonce(nonce: ByteArray): ByteArray {
        val privateKey = getOrCreateKeyPair().private
        return PeerIdentityVerifier.signNonce(privateKey, nonce)
    }

    companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "glide_device_identity"
    }
}

/**
 * Pure JVM in-memory identity key store for tests and decoupled execution.
 */
class JvmDeviceIdentityKeyStore : DeviceIdentityKeyStore {
    private var keyPair: KeyPair = generate()

    private fun generate(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    override fun getOrCreateKeyPair(): KeyPair = keyPair

    override fun getPublicKeyDer(): ByteArray = keyPair.public.encoded

    override fun getFingerprint(): String = PeerIdentityVerifier.deriveFingerprint(getPublicKeyDer())

    override fun signNonce(nonce: ByteArray): ByteArray =
        PeerIdentityVerifier.signNonce(keyPair.private, nonce)

    fun reset() {
        keyPair = generate()
    }
}
