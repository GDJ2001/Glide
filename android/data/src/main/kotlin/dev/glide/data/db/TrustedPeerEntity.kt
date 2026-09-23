package dev.glide.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Persistence entity for trusted/remembered peer devices in Room database (ADR-0015, GLIDE-070).
 */
@Entity(tableName = "trusted_peers")
data class TrustedPeerEntity(
    @PrimaryKey
    @ColumnInfo(name = "peer_fingerprint")
    val peerFingerprint: String,

    @ColumnInfo(name = "public_key_der")
    val publicKeyDer: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "paired_at_ms")
    val pairedAtMs: Long,

    @ColumnInfo(name = "last_seen_at_ms")
    val lastSeenAtMs: Long
)

@Dao
interface TrustedPeerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(peer: TrustedPeerEntity)

    @Query("SELECT * FROM trusted_peers WHERE peer_fingerprint = :fingerprint")
    suspend fun getByFingerprint(fingerprint: String): TrustedPeerEntity?

    @Query("SELECT * FROM trusted_peers ORDER BY last_seen_at_ms DESC")
    suspend fun getAllPeers(): List<TrustedPeerEntity>

    @Query("SELECT * FROM trusted_peers ORDER BY last_seen_at_ms DESC")
    fun observeAllPeers(): Flow<List<TrustedPeerEntity>>

    @Query("UPDATE trusted_peers SET last_seen_at_ms = :lastSeenAtMs WHERE peer_fingerprint = :fingerprint")
    suspend fun updateLastSeen(fingerprint: String, lastSeenAtMs: Long)

    @Delete
    suspend fun deletePeer(peer: TrustedPeerEntity)

    @Query("DELETE FROM trusted_peers WHERE peer_fingerprint = :fingerprint")
    suspend fun deleteByFingerprint(fingerprint: String)

    @Query("DELETE FROM trusted_peers")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM trusted_peers")
    suspend fun getPeerCount(): Int
}
