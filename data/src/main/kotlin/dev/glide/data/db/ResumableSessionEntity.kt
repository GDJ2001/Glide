package dev.glide.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Durable HARD-022 journal for one interrupted item.
 *
 * Authority is scoped to the original transfer + item + authenticated peer + source identity.
 * Progress represents only the contiguous verified prefix.
 */
@Entity(
    tableName = "resumable_sessions",
    primaryKeys = [
        "original_session_id",
        "item_id"
    ],
    indices = [
        Index(
            value = ["peer_fingerprint"]
        ),
        Index(
            value = ["expires_at_ms"]
        ),
        Index(
            value = ["updated_at_ms"]
        )
    ]
)
data class ResumableSessionEntity(
    @ColumnInfo(name = "original_session_id")
    val originalSessionId: String,

    @ColumnInfo(name = "item_id")
    val itemId: String,

    @ColumnInfo(name = "item_name")
    val itemName: String,

    @ColumnInfo(name = "peer_fingerprint")
    val peerFingerprintHex: String,

    @ColumnInfo(name = "source_identity")
    val sourceIdentity: String,

    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long,

    @ColumnInfo(name = "chunk_size")
    val chunkSize: Int,

    @ColumnInfo(name = "verified_chunks")
    val verifiedChunks: Long,

    @ColumnInfo(name = "contiguous_byte_offset")
    val contiguousByteOffset: Long,

    @ColumnInfo(name = "chunk_root_hash_hex")
    val chunkRootHashHex: String,

    /**
     * Canonical comma-separated lowercase SHA-256 chunk digests.
     * The list is immutable for the lifetime of the journal.
     */
    @ColumnInfo(name = "chunk_hashes_hex")
    val chunkHashesHex: String,

    @ColumnInfo(name = "whole_file_sha256_hex")
    val wholeFileSha256Hex: String,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,

    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,

    @ColumnInfo(name = "expires_at_ms")
    val expiresAtMs: Long,

    @ColumnInfo(name = "staging_path")
    val stagingPath: String
)

@Dao
interface ResumableSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(
        session: ResumableSessionEntity
    )

    @Query(
        """
        SELECT * FROM resumable_sessions
        WHERE original_session_id = :originalSessionId
          AND item_id = :itemId
        """
    )
    suspend fun get(
        originalSessionId: String,
        itemId: String
    ): ResumableSessionEntity?

    @Query(
        """
        SELECT * FROM resumable_sessions
        ORDER BY updated_at_ms DESC, created_at_ms DESC
        """
    )
    suspend fun getAllResumableSessions():
        List<ResumableSessionEntity>

    /**
     * The only forward-progress mutation. A chunk can advance the journal only when the caller is
     * committing exactly the next contiguous chunk and the stored prefix has not changed.
     */
    @Query(
        """
        UPDATE resumable_sessions
        SET verified_chunks = :newVerifiedChunks,
            contiguous_byte_offset = :newContiguousByteOffset,
            updated_at_ms = :updatedAtMs
        WHERE original_session_id = :originalSessionId
          AND item_id = :itemId
          AND verified_chunks = :expectedVerifiedChunks
          AND contiguous_byte_offset = :expectedContiguousByteOffset
        """
    )
    suspend fun compareAndAdvance(
        originalSessionId: String,
        itemId: String,
        expectedVerifiedChunks: Long,
        expectedContiguousByteOffset: Long,
        newVerifiedChunks: Long,
        newContiguousByteOffset: Long,
        updatedAtMs: Long
    ): Int

    /**
     * Recovery-only rewind after a missing/corrupt retained chunk is detected.
     */
    @Query(
        """
        UPDATE resumable_sessions
        SET verified_chunks = :verifiedChunks,
            contiguous_byte_offset = :contiguousByteOffset,
            updated_at_ms = :updatedAtMs
        WHERE original_session_id = :originalSessionId
          AND item_id = :itemId
          AND verified_chunks >= :verifiedChunks
          AND contiguous_byte_offset >= :contiguousByteOffset
        """
    )
    suspend fun rewindProgress(
        originalSessionId: String,
        itemId: String,
        verifiedChunks: Long,
        contiguousByteOffset: Long,
        updatedAtMs: Long
    ): Int

    @Query(
        """
        DELETE FROM resumable_sessions
        WHERE original_session_id = :originalSessionId
          AND item_id = :itemId
        """
    )
    suspend fun delete(
        originalSessionId: String,
        itemId: String
    )

    @Query(
        "SELECT * FROM resumable_sessions WHERE expires_at_ms <= :currentMs"
    )
    suspend fun getExpiredSessions(
        currentMs: Long
    ): List<ResumableSessionEntity>

    @Query(
        "DELETE FROM resumable_sessions WHERE expires_at_ms <= :currentMs"
    )
    suspend fun deleteExpired(
        currentMs: Long
    ): Int

    @Query("SELECT COUNT(*) FROM resumable_sessions")
    suspend fun getSessionCount(): Int
}
