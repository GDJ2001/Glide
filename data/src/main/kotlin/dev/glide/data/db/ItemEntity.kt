package dev.glide.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Entity representing an individual transfer item within a session in Room database.
 * Conforms to schema specifications in docs/architecture/STATE_AND_DATA_MODEL.md.
 */
@Entity(
    tableName = "items",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["session_id", "ordinal"], unique = true),
        Index(value = ["session_id"])
    ]
)
data class ItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "item_id")
    val itemId: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "ordinal")
    val ordinal: Int,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "mime")
    val mime: String,

    @ColumnInfo(name = "offered_bytes")
    val offeredBytes: Long,

    @ColumnInfo(name = "actual_bytes")
    val actualBytes: Long? = null,

    @ColumnInfo(name = "digest")
    val digest: String? = null,

    @ColumnInfo(name = "outcome")
    val outcome: String? = null,

    @ColumnInfo(name = "failure_code")
    val failureCode: String? = null
)

@Dao
interface ItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ItemEntity>)

    @Query("SELECT * FROM items WHERE item_id = :itemId")
    suspend fun getByItemId(itemId: String): ItemEntity?

    @Query("SELECT * FROM items WHERE session_id = :sessionId ORDER BY ordinal ASC")
    suspend fun getItemsForSession(sessionId: String): List<ItemEntity>

    @Query("UPDATE items SET outcome = :outcome, actual_bytes = :actualBytes, digest = :digest, failure_code = :failureCode WHERE item_id = :itemId")
    suspend fun updateOutcome(
        itemId: String,
        outcome: String,
        actualBytes: Long? = null,
        digest: String? = null,
        failureCode: String? = null
    )

    @Query("DELETE FROM items WHERE item_id = :itemId")
    suspend fun delete(itemId: String)
}
