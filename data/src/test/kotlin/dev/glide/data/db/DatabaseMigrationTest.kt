package dev.glide.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Tests for MIGRATION_1_2 SQL correctness, version bounds, and schema-gap detection.
 * Uses a JVM proxy for [SupportSQLiteDatabase] — no Android runtime required.
 *
 * GLIDE-056: database/upgrade regression suite.
 */
class DatabaseMigrationTest {

    // ---------------------------------------------------------------------------
    // Helper: captures all SQL strings passed to execSQL() during a migration run
    // ---------------------------------------------------------------------------

    private fun captureMigrationSql(block: (SupportSQLiteDatabase) -> Unit): List<String> {
        val captured = mutableListOf<String>()
        val fakeDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") {
                val sql = args?.get(0) as? String
                if (sql != null) captured.add(sql.trim())
            }
            null
        } as SupportSQLiteDatabase
        block(fakeDb)
        return captured
    }

    // ---------------------------------------------------------------------------
    // 1. Version bounds
    // ---------------------------------------------------------------------------

    @Test
    fun migration1To2_versionBounds_are1And2() {
        val migration = GlideDatabase.MIGRATION_1_2
        assertEquals(
            "MIGRATION_1_2 must start at version 1",
            1, migration.startVersion
        )
        assertEquals(
            "MIGRATION_1_2 must end at version 2",
            2, migration.endVersion
        )
    }

    // ---------------------------------------------------------------------------
    // 2. Sessions table — all 8 columns + 3 indices
    // ---------------------------------------------------------------------------

    @Test
    fun migration1To2_createsSessionsTable_withAllColumnsAndIndices() {
        val sqls = captureMigrationSql { db -> GlideDatabase.MIGRATION_1_2.migrate(db) }
        val combined = sqls.joinToString("\n")

        // Table DDL
        assertTrue("sessions CREATE TABLE missing", combined.contains("CREATE TABLE IF NOT EXISTS `sessions`"))
        assertTrue("session_id column missing", combined.contains("`session_id` TEXT NOT NULL"))
        assertTrue("batch_id column missing", combined.contains("`batch_id` TEXT NOT NULL"))
        assertTrue("role column missing", combined.contains("`role` TEXT NOT NULL"))
        assertTrue("peer_label column missing", combined.contains("`peer_label` TEXT NOT NULL"))
        assertTrue("started_at_ms column missing", combined.contains("`started_at_ms` INTEGER NOT NULL"))
        assertTrue("ended_at_ms column missing", combined.contains("`ended_at_ms` INTEGER"))
        assertTrue("outcome column missing", combined.contains("`outcome` TEXT"))
        assertTrue("failure_code column missing", combined.contains("`failure_code` TEXT"))
        assertTrue("sessions PK missing", combined.contains("PRIMARY KEY(`session_id`)"))

        // 3 indices
        assertTrue(
            "batch_id unique index missing",
            combined.contains("CREATE UNIQUE INDEX IF NOT EXISTS `index_sessions_batch_id` ON `sessions` (`batch_id`)")
        )
        assertTrue(
            "started_at_ms index missing",
            combined.contains("CREATE INDEX IF NOT EXISTS `index_sessions_started_at_ms` ON `sessions` (`started_at_ms`)")
        )
        assertTrue(
            "role+started_at_ms composite index missing",
            combined.contains("CREATE INDEX IF NOT EXISTS `index_sessions_role_started_at_ms` ON `sessions` (`role`, `started_at_ms`)")
        )
    }

    // ---------------------------------------------------------------------------
    // 3. Items table — FK with ON DELETE CASCADE
    // ---------------------------------------------------------------------------

    @Test
    fun migration1To2_createsItemsTable_withForeignKeyOnDeleteCascade() {
        val sqls = captureMigrationSql { db -> GlideDatabase.MIGRATION_1_2.migrate(db) }
        val combined = sqls.joinToString("\n")

        assertTrue("items CREATE TABLE missing", combined.contains("CREATE TABLE IF NOT EXISTS `items`"))
        assertTrue(
            "CASCADE FK missing",
            combined.contains(
                "FOREIGN KEY(`session_id`) REFERENCES `sessions`(`session_id`) ON UPDATE NO ACTION ON DELETE CASCADE"
            )
        )
        assertTrue(
            "items composite unique index missing",
            combined.contains("CREATE UNIQUE INDEX IF NOT EXISTS `index_items_session_id_ordinal` ON `items` (`session_id`, `ordinal`)")
        )
        assertTrue(
            "items session_id index missing",
            combined.contains("CREATE INDEX IF NOT EXISTS `index_items_session_id` ON `items` (`session_id`)")
        )
    }

    // ---------------------------------------------------------------------------
    // 4. receive_journal table must NOT be altered by MIGRATION_1_2
    // ---------------------------------------------------------------------------

    @Test
    fun migration1To2_doesNotAlterReceiveJournalTable() {
        val sqls = captureMigrationSql { db -> GlideDatabase.MIGRATION_1_2.migrate(db) }
        val journalAlteringStatements = sqls.filter { sql ->
            sql.contains("receive_journal", ignoreCase = true) &&
                (sql.startsWith("ALTER", ignoreCase = true) ||
                    sql.startsWith("DROP", ignoreCase = true) ||
                    (sql.startsWith("CREATE", ignoreCase = true) &&
                        sql.contains("receive_journal", ignoreCase = true)))
        }
        assertTrue(
            "MIGRATION_1_2 must not alter receive_journal. Found: $journalAlteringStatements",
            journalAlteringStatements.isEmpty()
        )
    }

    // -------------------------------------------------------------------------
    // 5. Schema-gap detection: MIGRATION_1_2.endVersion must equal the current DB version.
    //    If a future developer bumps @Database(version=N) without adding MIGRATION_(N-1)_N,
    //    this test fails — update EXPECTED_CURRENT_DB_VERSION when intentionally bumping.
    // -------------------------------------------------------------------------

    @Test
    fun migration1To2_endVersion_matchesCurrentDatabaseVersion() {
        // Update this constant when the @Database version is intentionally bumped.
        // This guards against silent schema drift: version bump requires both a new migration AND this update.
        val EXPECTED_CURRENT_DB_VERSION = 2

        val migrationEndVersion = GlideDatabase.MIGRATION_1_2.endVersion

        assertEquals(
            "MIGRATION_1_2.endVersion ($migrationEndVersion) must equal the current @Database version ($EXPECTED_CURRENT_DB_VERSION). " +
                "If the DB version was bumped, add a new MIGRATION_N_(N+1) companion entry AND update EXPECTED_CURRENT_DB_VERSION.",
            EXPECTED_CURRENT_DB_VERSION, migrationEndVersion
        )
    }
}
