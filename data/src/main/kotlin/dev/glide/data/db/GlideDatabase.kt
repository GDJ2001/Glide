package dev.glide.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Main Room Database for Glide persistence.
 * Version 1 contains the receive staging journal and received files catalog.
 * Version 2 adds session and item transfer history (GLIDE-016).
 */
@Database(
    entities = [
        ReceiveJournalEntity::class,
        ReceivedFileEntity::class,
        SessionEntity::class,
        ItemEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class GlideDatabase : RoomDatabase() {
    abstract fun receiveJournalDao(): ReceiveJournalDao
    abstract fun receivedFileDao(): ReceivedFileDao
    abstract fun sessionDao(): SessionDao
    abstract fun itemDao(): ItemDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sessions` (
                        `session_id` TEXT NOT NULL,
                        `batch_id` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `peer_label` TEXT NOT NULL,
                        `started_at_ms` INTEGER NOT NULL,
                        `ended_at_ms` INTEGER,
                        `outcome` TEXT,
                        `failure_code` TEXT,
                        PRIMARY KEY(`session_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sessions_batch_id` ON `sessions` (`batch_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_started_at_ms` ON `sessions` (`started_at_ms`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_role_started_at_ms` ON `sessions` (`role`, `started_at_ms`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `items` (
                        `item_id` TEXT NOT NULL,
                        `session_id` TEXT NOT NULL,
                        `ordinal` INTEGER NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `mime` TEXT NOT NULL,
                        `offered_bytes` INTEGER NOT NULL,
                        `actual_bytes` INTEGER,
                        `digest` TEXT,
                        `outcome` TEXT,
                        `failure_code` TEXT,
                        PRIMARY KEY(`item_id`),
                        FOREIGN KEY(`session_id`) REFERENCES `sessions`(`session_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_items_session_id_ordinal` ON `items` (`session_id`, `ordinal`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_session_id` ON `items` (`session_id`)")
            }
        }
    }
}
