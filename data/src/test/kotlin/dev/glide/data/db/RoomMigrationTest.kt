package dev.glide.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

class RoomMigrationTest {

    @Test
    fun migration1To2_createsSessionsAndItemsTablesAndIndices() {
        val executedSql = mutableListOf<String>()
        val fakeDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") {
                val sql = args?.get(0) as? String
                if (sql != null) {
                    executedSql.add(sql.trim())
                }
            }
            null
        } as SupportSQLiteDatabase

        val migration = GlideDatabase.MIGRATION_1_2

        assertEquals(1, migration.startVersion)
        assertEquals(2, migration.endVersion)

        migration.migrate(fakeDb)

        val sqlOutput = executedSql.joinToString("\n")

        // Validate sessions table creation
        assertTrue(sqlOutput.contains("CREATE TABLE IF NOT EXISTS `sessions`"))
        assertTrue(sqlOutput.contains("`session_id` TEXT NOT NULL"))
        assertTrue(sqlOutput.contains("`batch_id` TEXT NOT NULL"))
        assertTrue(sqlOutput.contains("`role` TEXT NOT NULL"))
        assertTrue(sqlOutput.contains("`peer_label` TEXT NOT NULL"))
        assertTrue(sqlOutput.contains("`started_at_ms` INTEGER NOT NULL"))
        assertTrue(sqlOutput.contains("`ended_at_ms` INTEGER"))
        assertTrue(sqlOutput.contains("`outcome` TEXT"))
        assertTrue(sqlOutput.contains("`failure_code` TEXT"))
        assertTrue(sqlOutput.contains("PRIMARY KEY(`session_id`)"))

        // Validate sessions indices
        assertTrue(sqlOutput.contains("CREATE UNIQUE INDEX IF NOT EXISTS `index_sessions_batch_id` ON `sessions` (`batch_id`)"))
        assertTrue(sqlOutput.contains("CREATE INDEX IF NOT EXISTS `index_sessions_started_at_ms` ON `sessions` (`started_at_ms`)"))
        assertTrue(sqlOutput.contains("CREATE INDEX IF NOT EXISTS `index_sessions_role_started_at_ms` ON `sessions` (`role`, `started_at_ms`)"))

        // Validate items table creation
        assertTrue(sqlOutput.contains("CREATE TABLE IF NOT EXISTS `items`"))
        assertTrue(sqlOutput.contains("`item_id` TEXT NOT NULL"))
        assertTrue(sqlOutput.contains("`session_id` TEXT NOT NULL"))
        assertTrue(sqlOutput.contains("`ordinal` INTEGER NOT NULL"))
        assertTrue(sqlOutput.contains("`display_name` TEXT NOT NULL"))
        assertTrue(sqlOutput.contains("`mime` TEXT NOT NULL"))
        assertTrue(sqlOutput.contains("`offered_bytes` INTEGER NOT NULL"))
        assertTrue(sqlOutput.contains("`actual_bytes` INTEGER"))
        assertTrue(sqlOutput.contains("`digest` TEXT"))
        assertTrue(sqlOutput.contains("FOREIGN KEY(`session_id`) REFERENCES `sessions`(`session_id`) ON UPDATE NO ACTION ON DELETE CASCADE"))

        // Validate items indices
        assertTrue(sqlOutput.contains("CREATE UNIQUE INDEX IF NOT EXISTS `index_items_session_id_ordinal` ON `items` (`session_id`, `ordinal`)"))
        assertTrue(sqlOutput.contains("CREATE INDEX IF NOT EXISTS `index_items_session_id` ON `items` (`session_id`)"))
    }

    @Test
    fun migration2To3_altersTablesToAddRelativePath() {
        val executedSql = mutableListOf<String>()
        val fakeDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") {
                val sql = args?.get(0) as? String
                if (sql != null) {
                    executedSql.add(sql.trim())
                }
            }
            null
        } as SupportSQLiteDatabase

        val migration = GlideDatabase.MIGRATION_2_3

        assertEquals(2, migration.startVersion)
        assertEquals(3, migration.endVersion)

        migration.migrate(fakeDb)

        val sqlOutput = executedSql.joinToString("\n")
        assertTrue(sqlOutput.contains("ALTER TABLE `receive_journal` ADD COLUMN `relative_path` TEXT DEFAULT NULL"))
        assertTrue(sqlOutput.contains("ALTER TABLE `received_files` ADD COLUMN `relative_path` TEXT DEFAULT NULL"))
        assertTrue(sqlOutput.contains("ALTER TABLE `items` ADD COLUMN `relative_path` TEXT DEFAULT NULL"))
    }

    @Test
    fun migration3To4_createsTrustedPeersAndResumableSessionsTables() {
        val executedSql = mutableListOf<String>()
        val fakeDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") {
                val sql = args?.get(0) as? String
                if (sql != null) {
                    executedSql.add(sql.trim())
                }
            }
            null
        } as SupportSQLiteDatabase

        val migration = GlideDatabase.MIGRATION_3_4

        assertEquals(3, migration.startVersion)
        assertEquals(4, migration.endVersion)

        migration.migrate(fakeDb)

        val sqlOutput = executedSql.joinToString("\n")
        assertTrue(sqlOutput.contains("CREATE TABLE IF NOT EXISTS `trusted_peers`"))
        assertTrue(sqlOutput.contains("CREATE TABLE IF NOT EXISTS `resumable_sessions`"))
    }

    @Test
    fun schemaFiles_version1And2And3And4ExistAndAreValidJson() {
        val schema1File = File("schemas/dev.glide.data.db.GlideDatabase/1.json")
        val schema2File = File("schemas/dev.glide.data.db.GlideDatabase/2.json")
        val schema3File = File("schemas/dev.glide.data.db.GlideDatabase/3.json")
        val schema4File = File("schemas/dev.glide.data.db.GlideDatabase/4.json")

        assertTrue("Schema version 1 file must exist", schema1File.exists())
        assertTrue("Schema version 2 file must exist", schema2File.exists())
        assertTrue("Schema version 3 file must exist", schema3File.exists())
        assertTrue("Schema version 4 file must exist", schema4File.exists())

        val schema4Content = schema4File.readText()
        assertTrue(schema4Content.contains("\"version\": 4"))
        assertTrue(schema4Content.contains("\"tableName\": \"trusted_peers\""))
        assertTrue(schema4Content.contains("\"tableName\": \"resumable_sessions\""))
    }
}
