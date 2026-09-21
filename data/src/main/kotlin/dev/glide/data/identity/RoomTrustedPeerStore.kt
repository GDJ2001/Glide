package dev.glide.data.identity

import dev.glide.core.domain.TrustedPeerRecord
import dev.glide.core.identity.TrustedPeerStore
import dev.glide.data.db.TrustedPeerDao
import dev.glide.data.db.TrustedPeerEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomTrustedPeerStore(
    private val dao: TrustedPeerDao
) : TrustedPeerStore {

    override fun observeAll(): Flow<List<TrustedPeerRecord>> =
        dao.observeAllPeers().map { peers ->
            peers.map { it.toDomain() }
        }

    override suspend fun getAll(): List<TrustedPeerRecord> =
        dao.getAllPeers().map { it.toDomain() }

    override suspend fun getByFingerprint(
        fingerprintHex: String
    ): TrustedPeerRecord? =
        dao.getByFingerprint(
            fingerprintHex
        )?.toDomain()

    override suspend fun upsert(
        peer: TrustedPeerRecord
    ) {
        dao.insertOrUpdate(
            peer.toEntity()
        )
    }

    override suspend fun updateLastSeen(
        fingerprintHex: String,
        lastSeenAtMs: Long
    ) {
        dao.updateLastSeen(
            fingerprintHex,
            lastSeenAtMs
        )
    }

    override suspend fun revoke(
        fingerprintHex: String
    ) {
        dao.deleteByFingerprint(
            fingerprintHex
        )
    }

    override suspend fun revokeAll() {
        dao.deleteAll()
    }

    override suspend fun count(): Int =
        dao.getPeerCount()

    private fun TrustedPeerEntity.toDomain():
        TrustedPeerRecord =
        TrustedPeerRecord(
            peerFingerprintHex =
                peerFingerprint.lowercase(),
            publicKeyDerHex =
                publicKeyDer.lowercase(),
            displayName = displayName,
            pairedAtMs = pairedAtMs,
            lastSeenAtMs = lastSeenAtMs
        )

    private fun TrustedPeerRecord.toEntity():
        TrustedPeerEntity =
        TrustedPeerEntity(
            peerFingerprint =
                peerFingerprintHex,
            publicKeyDer =
                publicKeyDerHex,
            displayName = displayName,
            pairedAtMs = pairedAtMs,
            lastSeenAtMs = lastSeenAtMs
        )
}
