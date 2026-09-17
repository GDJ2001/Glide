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
        ItemEntity::class,
        TrustedPeerEntity::class,
        ResumableSessionEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class GlideDatabase : RoomDatabase() {
    abstract fun receiveJournalDao(): ReceiveJournalDao
    abstract fun receivedFileDao(): ReceivedFileDao
    abstract fun sessionDao(): SessionDao
    abstract fun itemDao(): ItemDao
    abstract fun trustedPeerDao(): TrustedPeerDao
    abstract fun resumableSessionDao(): ResumableSessionDao

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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `receive_journal` ADD COLUMN `relative_path` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `received_files` ADD COLUMN `relative_path` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `items` ADD COLUMN `relative_path` TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `trusted_peers` (
                        `peer_fingerprint` TEXT NOT NULL,
                        `public_key_der` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `paired_at_ms` INTEGER NOT NULL,
                        `last_seen_at_ms` INTEGER NOT NULL,
                        PRIMARY KEY(`peer_fingerprint`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `resumable_sessions` (
                        `session_id` TEXT NOT NULL,
                        `item_id` TEXT NOT NULL,
                        `item_name` TEXT NOT NULL,
                        `total_bytes` INTEGER NOT NULL,
                        `chunk_size` INTEGER NOT NULL,
                        `verified_chunks` INTEGER NOT NULL,
                        `root_hash_hex` TEXT NOT NULL,
                        `created_at_ms` INTEGER NOT NULL,
                        `expires_at_ms` INTEGER NOT NULL,
                        `staging_path` TEXT NOT NULL,
                        PRIMARY KEY(`session_id`)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
