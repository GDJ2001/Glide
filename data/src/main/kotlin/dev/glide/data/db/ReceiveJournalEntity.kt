package dev.glide.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Persistence entity for staged file transitions in Room database.
 * Matches specification in docs/architecture/STATE_AND_DATA_MODEL.md.
 */
@Entity(tableName = "receive_journal")
data class ReceiveJournalEntity(
    @PrimaryKey
    @ColumnInfo(name = "item_id")
    val itemId: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "staging_key")
    val stagingKey: String,

    @ColumnInfo(name = "final_key")
    val finalKey: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    @ColumnInfo(name = "expected_bytes")
    val expectedBytes: Long,

    @ColumnInfo(name = "verified_digest")
    val verifiedDigest: String? = null,

    @ColumnInfo(name = "phase")
    val phase: String, // "PREPARED", "WRITING", "COMMITTING", "COMPLETED"

    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long
)

object JournalPhases {
    const val PREPARED = "PREPARED"
    const val WRITING = "WRITING"
    const val COMMITTING = "COMMITTING"
    const val COMPLETED = "COMPLETED"
}

@Dao
interface ReceiveJournalDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entry: ReceiveJournalEntity)

    @Query("SELECT * FROM receive_journal WHERE item_id = :itemId")
    suspend fun getByItemId(itemId: String): ReceiveJournalEntity?

    @Query("UPDATE receive_journal SET phase = :phase, updated_at_ms = :updatedAtMs WHERE item_id = :itemId")
    suspend fun updatePhase(itemId: String, phase: String, updatedAtMs: Long)

    @Query("UPDATE receive_journal SET phase = :phase, verified_digest = :digest, updated_at_ms = :updatedAtMs WHERE item_id = :itemId")
    suspend fun updateVerifiedDigest(itemId: String, phase: String, digest: String, updatedAtMs: Long)

    @Query("DELETE FROM receive_journal WHERE item_id = :itemId")
    suspend fun delete(itemId: String)

    @Query("SELECT * FROM receive_journal WHERE phase != 'COMPLETED'")
    suspend fun getPendingEntries(): List<ReceiveJournalEntity>

    @Query("SELECT * FROM receive_journal WHERE session_id = :sessionId")
    suspend fun getEntriesBySessionId(sessionId: String): List<ReceiveJournalEntity>

    @Query("DELETE FROM receive_journal WHERE session_id = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)
}
