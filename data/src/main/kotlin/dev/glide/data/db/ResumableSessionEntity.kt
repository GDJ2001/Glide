package dev.glide.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Durable HARD-022 journal for one interrupted Android item.
 *
 * The row is keyed by original transfer + item and records the local role:
 * - receiver: private retained chunks + locally recorded verified-prefix chunk hashes;
 * - sender: persisted source handle/identity + preflight chunk-root/whole-file digest authority.
 *
 * Progress always means the remote receiver's / local receiver's contiguous verified prefix.
 */
@Entity(
    tableName = "resumable_sessions",
    primaryKeys = [
        "original_session_id",
        "item_id"
    ],
    indices = [
        Index(value = ["peer_fingerprint"]),
        Index(value = ["expires_at_ms"]),
        Index(value = ["updated_at_ms"])
    ]
)
data class ResumableSessionEntity(
    @ColumnInfo(name = "original_session_id")
    val originalSessionId: String,

    @ColumnInfo(name = "item_id")
    val itemId: String,

    @ColumnInfo(name = "journal_role")
    val journalRole: String,

    @ColumnInfo(name = "item_name")
    val itemName: String,

    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    @ColumnInfo(name = "relative_path")
    val relativePath: String?,

    @ColumnInfo(name = "peer_fingerprint")
    val peerFingerprintHex: String,

    @ColumnInfo(name = "source_handle")
    val sourceHandle: String?,

    @ColumnInfo(name = "source_identity")
    val sourceIdentity: String?,

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
     * Receiver: comma-separated SHA-256 digests for exactly the contiguous retained prefix.
     * Sender: empty; source preflight is recomputed on reconnect.
     */
    @ColumnInfo(name = "verified_chunk_hashes_hex")
    val verifiedChunkHashesHex: String,

    /**
     * Sender preflight digest. Receiver may learn the final digest only at ITEM_END, so this field
     * is nullable and final receiver promotion takes the authenticated final digest explicitly.
     */
    @ColumnInfo(name = "whole_file_sha256_hex")
    val wholeFileSha256Hex: String?,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,

    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,

    @ColumnInfo(name = "expires_at_ms")
    val expiresAtMs: Long,

    /**
     * Receiver private retained-item directory. Null for sender journal rows.
     */
    @ColumnInfo(name = "staging_path")
    val stagingPath: String?
) {
    companion object {
        const val ROLE_SENDER = "sender"
        const val ROLE_RECEIVER = "receiver"
    }
}

@Dao
interface ResumableSessionDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(
        session: ResumableSessionEntity
    ): Long

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
     * The only forward-progress mutation. Progress and the receiver's retained-prefix digest list
     * advance in one Room statement and only from the exact expected prefix.
     */
    @Query(
        """
        UPDATE resumable_sessions
        SET verified_chunks = :newVerifiedChunks,
            contiguous_byte_offset = :newContiguousByteOffset,
            verified_chunk_hashes_hex = :newVerifiedChunkHashesHex,
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
        newVerifiedChunkHashesHex: String,
        updatedAtMs: Long
    ): Int

    /**
     * Recovery-only rewind after a missing/corrupt retained receiver chunk is detected.
     */
    @Query(
        """
        UPDATE resumable_sessions
        SET verified_chunks = :verifiedChunks,
            contiguous_byte_offset = :contiguousByteOffset,
            verified_chunk_hashes_hex = :verifiedChunkHashesHex,
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
        verifiedChunkHashesHex: String,
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
