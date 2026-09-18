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

    // ---------------------------------------------------------------------------
    // 5. MIGRATION_2_3 adds relative_path to receive_journal, received_files, and items
    // ---------------------------------------------------------------------------

    @Test
    fun migration2To3_versionBounds_are2And3() {
        val migration = GlideDatabase.MIGRATION_2_3
        assertEquals("MIGRATION_2_3 must start at version 2", 2, migration.startVersion)
        assertEquals("MIGRATION_2_3 must end at version 3", 3, migration.endVersion)
    }

    @Test
    fun migration2To3_addsRelativePathColumnsToAllThreeTables() {
        val sqls = captureMigrationSql { db -> GlideDatabase.MIGRATION_2_3.migrate(db) }
        val combined = sqls.joinToString("\n")

        assertTrue("receive_journal relative_path missing", combined.contains("ALTER TABLE `receive_journal` ADD COLUMN `relative_path` TEXT DEFAULT NULL"))
        assertTrue("received_files relative_path missing", combined.contains("ALTER TABLE `received_files` ADD COLUMN `relative_path` TEXT DEFAULT NULL"))
        assertTrue("items relative_path missing", combined.contains("ALTER TABLE `items` ADD COLUMN `relative_path` TEXT DEFAULT NULL"))
    }

    // ---------------------------------------------------------------------------
    // 6. MIGRATION_3_4 adds trusted_peers and resumable_sessions tables
    // ---------------------------------------------------------------------------

    @Test
    fun migration3To4_versionBounds_are3And4() {
        val migration = GlideDatabase.MIGRATION_3_4
        assertEquals("MIGRATION_3_4 must start at version 3", 3, migration.startVersion)
        assertEquals("MIGRATION_3_4 must end at version 4", 4, migration.endVersion)
    }

    @Test
    fun migration3To4_createsTrustedPeersAndResumableSessionsTables() {
        val sqls = captureMigrationSql { db -> GlideDatabase.MIGRATION_3_4.migrate(db) }
        val combined = sqls.joinToString("\n")

        assertTrue("trusted_peers creation missing", combined.contains("CREATE TABLE IF NOT EXISTS `trusted_peers`"))
        assertTrue("trusted_peers peer_fingerprint missing", combined.contains("`peer_fingerprint` TEXT NOT NULL"))
        assertTrue("trusted_peers public_key_der missing", combined.contains("`public_key_der` TEXT NOT NULL"))
        assertTrue("resumable_sessions creation missing", combined.contains("CREATE TABLE IF NOT EXISTS `resumable_sessions`"))
        assertTrue("resumable_sessions session_id missing", combined.contains("`session_id` TEXT NOT NULL"))
        assertTrue("resumable_sessions verified_chunks missing", combined.contains("`verified_chunks` INTEGER NOT NULL"))
    }

    // -------------------------------------------------------------------------
    // 7. MIGRATION_4_5 replaces unauthenticated offset-only resume rows fail-closed.
    // -------------------------------------------------------------------------

    @Test
    fun migration4To5_versionBounds_are4And5() {
        val migration =
            GlideDatabase.MIGRATION_4_5
        assertEquals(4, migration.startVersion)
        assertEquals(5, migration.endVersion)
    }

    @Test
    fun migration4To5_discardsLegacyResumeAuthorityAndCreatesContiguousJournal() {
        val sqls =
            captureMigrationSql { db ->
                GlideDatabase.MIGRATION_4_5
                    .migrate(db)
            }
        val combined =
            sqls.joinToString("\n")

        assertTrue(
            "Legacy resumable rows must be dropped fail-closed",
            combined.contains(
                "DROP TABLE IF EXISTS `resumable_sessions`"
            )
        )
        assertTrue(
            combined.contains(
                "`original_session_id` TEXT NOT NULL"
            )
        )
        assertTrue(
            combined.contains(
                "`peer_fingerprint` TEXT NOT NULL"
            )
        )
        assertTrue(
            combined.contains(
                "`source_identity` TEXT NOT NULL"
            )
        )
        assertTrue(
            combined.contains(
                "`contiguous_byte_offset` INTEGER NOT NULL"
            )
        )
        assertTrue(
            combined.contains(
                "`chunk_hashes_hex` TEXT NOT NULL"
            )
        )
        assertTrue(
            combined.contains(
                "`whole_file_sha256_hex` TEXT NOT NULL"
            )
        )
        assertTrue(
            combined.contains(
                "PRIMARY KEY("
            ) &&
                combined.contains(
                    "`original_session_id`"
                ) &&
                combined.contains(
                    "`item_id`"
                )
        )
        assertTrue(
            combined.contains(
                "index_resumable_sessions_peer_fingerprint"
            )
        )
        assertTrue(
            combined.contains(
                "index_resumable_sessions_expires_at_ms"
            )
        )
        assertTrue(
            combined.contains(
                "index_resumable_sessions_updated_at_ms"
            )
        )
    }

    // -------------------------------------------------------------------------
    // 8. Schema-gap detection: latest migration must equal current DB version.
    // -------------------------------------------------------------------------

    @Test
    fun migrationEndVersion_matchesCurrentDatabaseVersion() {
        val expectedCurrentDbVersion = 5
        val migrationEndVersion =
            GlideDatabase.MIGRATION_4_5
                .endVersion

        assertEquals(
            "Latest migration endVersion must equal current @Database version.",
            expectedCurrentDbVersion,
            migrationEndVersion
        )
    }
}
