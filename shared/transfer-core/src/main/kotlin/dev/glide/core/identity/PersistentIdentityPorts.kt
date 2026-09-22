package dev.glide.core.identity

import dev.glide.core.domain.TrustedPeerRecord
import kotlinx.coroutines.flow.Flow

/**
 * Platform protected persistent identity key.
 *
 * Implementations own the private key lifecycle. Protocol code supplies the complete canonical
 * transcript to sign; implementations never decide which fields are authentication authority.
 */
interface PersistentIdentityStore {
    fun hasIdentity(): Boolean
    fun getOrCreatePublicKeyDer(): ByteArray
    fun getFingerprint(): String
    fun signTranscript(transcript: ByteArray): ByteArray
    fun deleteIdentity()
}

/**
 * Platform persistence for enrolled remote identities.
 */
interface TrustedPeerStore {
    fun observeAll(): Flow<List<TrustedPeerRecord>>
    suspend fun getAll(): List<TrustedPeerRecord>
    suspend fun getByFingerprint(fingerprintHex: String): TrustedPeerRecord?
    suspend fun upsert(peer: TrustedPeerRecord)
    suspend fun updateLastSeen(fingerprintHex: String, lastSeenAtMs: Long)
    suspend fun revoke(fingerprintHex: String)
    suspend fun revokeAll()
    suspend fun count(): Int
}
