package dev.glide.data.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.glide.core.identity.PeerIdentityVerifier
import dev.glide.core.identity.PersistentIdentityStore
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

/**
 * Android Keystore implementation of the platform-neutral persistent identity port.
 *
 * The private key never leaves Android Keystore. Callers supply the complete canonical HARD-018
 * transcript to sign; this adapter cannot accidentally downgrade authentication to a bare nonce.
 */
class AndroidDeviceIdentityKeyStore(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : PersistentIdentityStore {

    private val keyStore: KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply {
            load(null)
        }

    @Synchronized
    override fun hasIdentity(): Boolean =
        keyStore.containsAlias(keyAlias)

    @Synchronized
    fun getOrCreateKeyPair(): KeyPair {
        if (keyStore.containsAlias(keyAlias)) {
            val privateKey =
                keyStore.getKey(
                    keyAlias,
                    null
                ) as PrivateKey
            val certificate =
                keyStore.getCertificate(keyAlias)
            return KeyPair(
                certificate.publicKey,
                privateKey
            )
        }

        val keyPairGenerator =
            KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEY_STORE
            )

        val spec =
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_SIGN or
                    KeyProperties.PURPOSE_VERIFY
            ).apply {
                setDigests(
                    KeyProperties.DIGEST_SHA256
                )
                setAlgorithmParameterSpec(
                    ECGenParameterSpec(
                        "secp256r1"
                    )
                )
                setUserAuthenticationRequired(false)
            }.build()

        keyPairGenerator.initialize(spec)
        return keyPairGenerator.generateKeyPair()
    }

    override fun getOrCreatePublicKeyDer(): ByteArray =
        getOrCreateKeyPair()
            .public
            .encoded

    override fun getFingerprint(): String =
        PeerIdentityVerifier.deriveFingerprint(
            getOrCreatePublicKeyDer()
        )

    override fun signTranscript(
        transcript: ByteArray
    ): ByteArray {
        val privateKey =
            getOrCreateKeyPair().private
        return PeerIdentityVerifier
            .signTranscript(
                privateKey,
                transcript
            )
    }

    @Synchronized
    override fun deleteIdentity() {
        if (keyStore.containsAlias(keyAlias)) {
            keyStore.deleteEntry(keyAlias)
        }
    }

    companion object {
        const val ANDROID_KEY_STORE =
            "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS =
            "glide_device_identity"
    }
}

/**
 * Pure JVM in-memory identity store for unit tests.
 */
class JvmDeviceIdentityKeyStore :
    PersistentIdentityStore {

    private var keyPair: KeyPair? = null

    @Synchronized
    private fun generate(): KeyPair {
        val kpg =
            KeyPairGenerator.getInstance("EC")
        kpg.initialize(
            ECGenParameterSpec(
                "secp256r1"
            )
        )
        return kpg.generateKeyPair()
    }

    @Synchronized
    fun getOrCreateKeyPair(): KeyPair {
        val existing = keyPair
        if (existing != null) {
            return existing
        }

        return generate().also {
            keyPair = it
        }
    }

    @Synchronized
    override fun hasIdentity(): Boolean =
        keyPair != null

    override fun getOrCreatePublicKeyDer(): ByteArray =
        getOrCreateKeyPair()
            .public
            .encoded

    override fun getFingerprint(): String =
        PeerIdentityVerifier.deriveFingerprint(
            getOrCreatePublicKeyDer()
        )

    override fun signTranscript(
        transcript: ByteArray
    ): ByteArray =
        PeerIdentityVerifier.signTranscript(
            getOrCreateKeyPair().private,
            transcript
        )

    @Synchronized
    override fun deleteIdentity() {
        keyPair = null
    }

    /**
     * Explicit test helper matching user-initiated identity rotation semantics.
     */
    fun reset() {
        deleteIdentity()
        getOrCreateKeyPair()
    }
}
