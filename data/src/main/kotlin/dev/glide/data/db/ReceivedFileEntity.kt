package dev.glide.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Persistence entity for verified received files catalog in Room database.
 * Matches specification in docs/architecture/STATE_AND_DATA_MODEL.md.
 */
@Entity(
    tableName = "received_files",
    indices = [
        Index(value = ["originating_item_id"], unique = true)
    ]
)
data class ReceivedFileEntity(
    @PrimaryKey
    @ColumnInfo(name = "file_key")
    val fileKey: String,

    @ColumnInfo(name = "originating_item_id")
    val originatingItemId: String?,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "mime")
    val mime: String,

    @ColumnInfo(name = "length")
    val length: Long,

    @ColumnInfo(name = "digest")
    val digest: String,

    @ColumnInfo(name = "received_at_ms")
    val receivedAtMs: Long,

    @ColumnInfo(name = "deleted_at_ms")
    val deletedAtMs: Long? = null
)

@Dao
interface ReceivedFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(file: ReceivedFileEntity)

    @Query("SELECT * FROM received_files WHERE file_key = :fileKey")
    suspend fun getByFileKey(fileKey: String): ReceivedFileEntity?

    @Query("SELECT * FROM received_files WHERE originating_item_id = :itemId")
    suspend fun getByOriginatingItemId(itemId: String): ReceivedFileEntity?

    @Query("SELECT * FROM received_files WHERE deleted_at_ms IS NULL ORDER BY received_at_ms DESC")
    suspend fun getActiveFiles(): List<ReceivedFileEntity>

    @Query("SELECT COUNT(*) FROM received_files WHERE deleted_at_ms IS NULL")
    suspend fun getActiveFileCount(): Int

    @Query("SELECT COALESCE(SUM(length), 0) FROM received_files WHERE deleted_at_ms IS NULL")
    suspend fun getActiveFileTotalBytes(): Long

    @Query("UPDATE received_files SET deleted_at_ms = :deletedAtMs WHERE file_key = :fileKey")
    suspend fun markDeleted(fileKey: String, deletedAtMs: Long)

    @Query("DELETE FROM received_files WHERE file_key = :fileKey")
    suspend fun deletePermanently(fileKey: String)

    @Query(
        """
        UPDATE received_files 
        SET originating_item_id = NULL 
        WHERE originating_item_id IN (
            SELECT item_id FROM items 
            INNER JOIN sessions ON items.session_id = sessions.session_id 
            WHERE sessions.outcome IS NOT NULL AND sessions.started_at_ms < :cutoffMs
        )
        """
    )
    suspend fun detachItemIdsOlderThan(cutoffMs: Long): Int

    @Query(
        """
        UPDATE received_files 
        SET originating_item_id = NULL 
        WHERE originating_item_id IN (
            SELECT item_id FROM items 
            INNER JOIN sessions ON items.session_id = sessions.session_id 
            WHERE sessions.outcome IS NOT NULL AND sessions.session_id IN (
                SELECT session_id FROM sessions 
                WHERE outcome IS NOT NULL 
                ORDER BY started_at_ms DESC 
                LIMIT -1 OFFSET :maxCount
            )
        )
        """
    )
    suspend fun detachItemIdsExceedingMax(maxCount: Int): Int

    @Query("UPDATE received_files SET originating_item_id = NULL")
    suspend fun detachAllItemIds(): Int

    @Query(
        """
        UPDATE received_files 
        SET originating_item_id = NULL 
        WHERE originating_item_id IN (
            SELECT item_id FROM items 
            WHERE session_id = :sessionId
        )
        """
    )
    suspend fun detachItemIdsForSession(sessionId: String): Int
}
