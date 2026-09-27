package dev.glide.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Composite relation containing a [SessionEntity] and its associated [ItemEntity] records.
 */
data class SessionWithItems(
    @Embedded val session: SessionEntity,
    @Relation(
        parentColumn = "session_id",
        entityColumn = "session_id"
    )
    val items: List<ItemEntity>
)

/**
 * Entity representing a transfer session in the Room database.
 * Conforms to schema specifications in docs/architecture/STATE_AND_DATA_MODEL.md.
 */
@Entity(
    tableName = "sessions",
    indices = [
        Index(value = ["batch_id"], unique = true),
        Index(value = ["started_at_ms"]),
        Index(value = ["role", "started_at_ms"])
    ]
)
data class SessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "batch_id")
    val batchId: String,

    @ColumnInfo(name = "role")
    val role: String,

    @ColumnInfo(name = "peer_label")
    val peerLabel: String,

    @ColumnInfo(name = "started_at_ms")
    val startedAtMs: Long,

    @ColumnInfo(name = "ended_at_ms")
    val endedAtMs: Long? = null,

    @ColumnInfo(name = "outcome")
    val outcome: String? = null,

    @ColumnInfo(name = "failure_code")
    val failureCode: String? = null
)

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE session_id = :sessionId")
    suspend fun getBySessionId(sessionId: String): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY started_at_ms DESC")
    suspend fun getAll(): List<SessionEntity>

    @Query("SELECT * FROM sessions ORDER BY started_at_ms DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<SessionEntity>

    @Query("UPDATE sessions SET outcome = :outcome, ended_at_ms = :endedAtMs, failure_code = :failureCode WHERE session_id = :sessionId")
    suspend fun updateOutcome(
        sessionId: String,
        outcome: String,
        endedAtMs: Long,
        failureCode: String? = null
    )

    @Query("DELETE FROM sessions WHERE session_id = :sessionId")
    suspend fun delete(sessionId: String)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()

    @Query(
        """
        SELECT * FROM sessions 
        WHERE (:role IS NULL OR role = :role)
        ORDER BY started_at_ms DESC 
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getSessionsPaged(role: String?, limit: Int, offset: Int): List<SessionEntity>

    @Query("SELECT COUNT(*) FROM sessions WHERE (:role IS NULL OR role = :role)")
    suspend fun getSessionCount(role: String?): Int

    @Query("SELECT COUNT(*) FROM sessions WHERE outcome IS NOT NULL")
    suspend fun getTerminalSessionCount(): Int

    @Query("SELECT * FROM sessions WHERE outcome IS NULL")
    suspend fun getActiveSessions(): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE outcome IS NOT NULL AND started_at_ms < :cutoffMs")
    suspend fun deleteTerminalOlderThan(cutoffMs: Long): Int

    @Query(
        """
        DELETE FROM sessions 
        WHERE outcome IS NOT NULL 
          AND session_id IN (
            SELECT session_id FROM sessions 
            WHERE outcome IS NOT NULL 
            ORDER BY started_at_ms DESC 
            LIMIT -1 OFFSET :maxCount
          )
        """
    )
    suspend fun deleteTerminalExceedingMax(maxCount: Int): Int

    @Query("DELETE FROM sessions WHERE outcome IS NOT NULL")
    suspend fun deleteTerminalSessions(): Int

    @Transaction
    @Query("SELECT * FROM sessions WHERE session_id = :sessionId")
    suspend fun getSessionWithItems(sessionId: String): SessionWithItems?

    @Transaction
    @Query(
        """
        SELECT * FROM sessions 
        WHERE (:role IS NULL OR role = :role)
        ORDER BY started_at_ms DESC 
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getSessionsWithItemsPaged(role: String?, limit: Int, offset: Int): List<SessionWithItems>

    @Transaction
    @Query(
        """
        SELECT * FROM sessions
        WHERE (:role IS NULL OR role = :role)
        ORDER BY started_at_ms DESC
        LIMIT :limit
        """
    )
    fun observeSessionsWithItems(role: String?, limit: Int): Flow<List<SessionWithItems>>
}
