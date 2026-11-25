package dev.glide.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(
    tableName = "resumable_batches",
    indices = [Index("peer_fingerprint"), Index("expires_at_ms")]
)
data class ResumableBatchEntity(
    @PrimaryKey @ColumnInfo(name = "original_session_id") val originalSessionId: String,
    @ColumnInfo(name = "journal_role") val journalRole: String,
    @ColumnInfo(name = "peer_fingerprint") val peerFingerprint: String,
    @ColumnInfo(name = "manifest_sha256") val manifestSha256: String,
    @ColumnInfo(name = "manifest_json") val manifestJson: String,
    @ColumnInfo(name = "progress_json") val progressJson: String,
    @ColumnInfo(name = "revision") val revision: Long,
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long
)

@Dao
interface ResumableBatchDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(batch: ResumableBatchEntity): Long

    @Query("SELECT * FROM resumable_batches WHERE original_session_id = :sessionId AND expires_at_ms > :nowMs")
    suspend fun findActive(sessionId: String, nowMs: Long): ResumableBatchEntity?

    @Query("SELECT * FROM resumable_batches WHERE expires_at_ms > :nowMs ORDER BY expires_at_ms DESC")
    suspend fun listActive(nowMs: Long): List<ResumableBatchEntity>

    @Query("""
        UPDATE resumable_batches SET progress_json = :progressJson, revision = revision + 1
        WHERE original_session_id = :sessionId AND revision = :revision AND expires_at_ms > :nowMs
    """)
    suspend fun updateProgress(sessionId: String, revision: Long, nowMs: Long, progressJson: String): Int

    @Query("DELETE FROM resumable_batches WHERE peer_fingerprint = :peerFingerprint")
    suspend fun purgePeer(peerFingerprint: String): Int

    @Query("DELETE FROM resumable_batches WHERE expires_at_ms <= :nowMs")
    suspend fun purgeExpired(nowMs: Long): Int
}
