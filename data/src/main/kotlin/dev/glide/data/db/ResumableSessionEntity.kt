package dev.glide.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Persistence entity for interrupted resumable transfer sessions in Room database (ADR-0015, GLIDE-070).
 */
@Entity(tableName = "resumable_sessions")
data class ResumableSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "item_id")
    val itemId: String,

    @ColumnInfo(name = "item_name")
    val itemName: String,

    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long,

    @ColumnInfo(name = "chunk_size")
    val chunkSize: Int,

    @ColumnInfo(name = "verified_chunks")
    val verifiedChunks: Long,

    @ColumnInfo(name = "root_hash_hex")
    val rootHashHex: String,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,

    @ColumnInfo(name = "expires_at_ms")
    val expiresAtMs: Long,

    @ColumnInfo(name = "staging_path")
    val stagingPath: String
)

@Dao
interface ResumableSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(session: ResumableSessionEntity)

    @Query("SELECT * FROM resumable_sessions WHERE session_id = :sessionId")
    suspend fun getBySessionId(sessionId: String): ResumableSessionEntity?

    @Query("SELECT * FROM resumable_sessions ORDER BY created_at_ms DESC")
    suspend fun getAllResumableSessions(): List<ResumableSessionEntity>

    @Query("UPDATE resumable_sessions SET verified_chunks = :verifiedChunks WHERE session_id = :sessionId")
    suspend fun updateVerifiedChunks(sessionId: String, verifiedChunks: Long)

    @Query("DELETE FROM resumable_sessions WHERE session_id = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)

    @Query("DELETE FROM resumable_sessions WHERE expires_at_ms <= :currentMs")
    suspend fun deleteExpired(currentMs: Long): Int

    @Query("SELECT * FROM resumable_sessions WHERE expires_at_ms <= :currentMs")
    suspend fun getExpiredSessions(currentMs: Long): List<ResumableSessionEntity>

    @Query("SELECT COUNT(*) FROM resumable_sessions")
    suspend fun getSessionCount(): Int
}
