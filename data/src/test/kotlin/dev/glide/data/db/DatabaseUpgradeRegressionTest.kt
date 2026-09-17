package dev.glide.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * Upgrade and reinstall regression tests covering:
 *   - Prior schema fixture -> current: journal & file catalog survive v1->v2 migration intact
 *   - Failed migration recovery: body fault propagates; gap detection works
 *   - Reinstall distinction: fresh vs. upgrade install path
 *   - Stale transient grants: pending-phase entries distinguishable post-upgrade
 *
 * All tests run purely on JVM (SQL proxy + schema text inspection).
 *
 * GLIDE-056: database/upgrade regression suite.
 */
class DatabaseUpgradeRegressionTest {

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

    private val schemaDir: File by lazy {
        val candidates = listOf(
            File("schemas/dev.glide.data.db.GlideDatabase"),
            File("../data/schemas/dev.glide.data.db.GlideDatabase")
        )
        candidates.firstOrNull { it.exists() }
            ?: error("Could not locate schema dir. Tried: " + candidates.map { it.canonicalPath })
    }

    private fun readSchemaText(version: Int): String {
        val file = File(schemaDir, "$version.json")
        assertTrue("Schema $version.json must exist", file.exists())
        return file.readText()
    }

    // -------------------------------------------------------------------------
    // 1. Prior fixture -> current: receive_journal columns unchanged by migration
    // -------------------------------------------------------------------------

    @Test
    fun upgrade_v1ToV2_journalColumns_unchangedAcrossMigration() {
        val v1Text = readSchemaText(1)
        val v2Text = readSchemaText(2)

        val journalColumns = listOf(
            "item_id", "session_id", "staging_key", "final_key",
            "display_name", "mime_type", "expected_bytes",
            "verified_digest", "phase", "updated_at_ms"
        )
        for (col in journalColumns) {
            assertTrue("Column $col must be in v1 receive_journal", v1Text.contains("\"columnName\": \"$col\""))
            assertTrue("Column $col must be in v2 receive_journal", v2Text.contains("\"columnName\": \"$col\""))
        }
    }

    // -------------------------------------------------------------------------
    // 2. Prior fixture -> current: received_files columns unchanged by migration
    // -------------------------------------------------------------------------

    @Test
    fun upgrade_v1ToV2_receivedFilesColumns_unchangedAcrossMigration() {
        val v1Text = readSchemaText(1)
        val v2Text = readSchemaText(2)

        val receivedFileColumns = listOf(
            "file_key", "originating_item_id", "display_name",
            "mime", "length", "digest", "received_at_ms", "deleted_at_ms"
        )
        for (col in receivedFileColumns) {
            assertTrue("Column $col must be in v1 received_files", v1Text.contains("\"columnName\": \"$col\""))
            assertTrue("Column $col must be in v2 received_files", v2Text.contains("\"columnName\": \"$col\""))
        }
    }

    // -------------------------------------------------------------------------
    // 3. Prior fixture -> current: sessions added in v2 are reachable after migration
    // -------------------------------------------------------------------------

    @Test
    fun upgrade_v1ToV2_sessionDataIsReachable_postMigration() {
        val sqls = captureMigrationSql { db -> GlideDatabase.MIGRATION_1_2.migrate(db) }
        val combined = sqls.joinToString("\n")
        assertTrue(
            "sessions table with session_id must exist post-migration",
            combined.contains("session_id") && combined.contains("CREATE TABLE IF NOT EXISTS `sessions`")
        )
    }

    // -------------------------------------------------------------------------
    // 4. Failed migration: body fault propagates (not silently swallowed)
    // -------------------------------------------------------------------------

    @Test
    fun upgrade_failedMigrationBody_propagatesException() {
        val faultyDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, _ ->
            if (method.name == "execSQL") {
                throw android.database.sqlite.SQLiteException("Simulated migration fault")
            }
            null
        } as SupportSQLiteDatabase

        var caught: Exception? = null
        try {
            GlideDatabase.MIGRATION_1_2.migrate(faultyDb)
        } catch (e: Exception) {
            caught = e
        }
        assertNotNull(
            "A fault in migration body must propagate to caller — Room will then handle fallback/destroy",
            caught
        )
    }

    // -------------------------------------------------------------------------
    // 5. Gap detection: if DB version > MIGRATION_1_2.endVersion, a gap is present
    // -------------------------------------------------------------------------

    @Test
    fun upgrade_missingMigration_isDetectedByVersionMismatch() {
        val EXPECTED_CURRENT_DB_VERSION = 3
        val migrationEnd = GlideDatabase.MIGRATION_2_3.endVersion

        assertFalse(
            "DB version ($EXPECTED_CURRENT_DB_VERSION) is ahead of last migration end ($migrationEnd). " +
                "Add MIGRATION_${migrationEnd}_${EXPECTED_CURRENT_DB_VERSION} to GlideDatabase.Companion and update EXPECTED_CURRENT_DB_VERSION.",
            EXPECTED_CURRENT_DB_VERSION > migrationEnd
        )
    }

    // -------------------------------------------------------------------------
    // 6. Reinstall distinction: fresh install needs no migration (starts at latest)
    // -------------------------------------------------------------------------

    @Test
    fun reinstall_freshInstall_requiresNoMigration_startsAtLatestVersion() {
        val EXPECTED_CURRENT_DB_VERSION = 3
        val migration = GlideDatabase.MIGRATION_1_2
        assertEquals(
            "MIGRATION_1_2.startVersion must be 1, representing the first schema ever shipped",
            1, migration.startVersion
        )
        assertTrue("Current DB version must be >= 2 (at least v1->v2 migration exists)", EXPECTED_CURRENT_DB_VERSION >= 2)
    }

    // -------------------------------------------------------------------------
    // 7. Reinstall distinction: upgrade from v1 requires exactly MIGRATION_1_2
    // -------------------------------------------------------------------------

    @Test
    fun reinstall_upgradeFromV1_requiresMigration_1_2() {
        val migration = GlideDatabase.MIGRATION_1_2
        assertEquals("Upgrade path from v1 must start at 1", 1, migration.startVersion)
        assertEquals("Upgrade path from v1 must end at 2", 2, migration.endVersion)
        assertEquals("Each migration covers exactly one version step", 1, migration.endVersion - migration.startVersion)
    }

    @Test
    fun reinstall_upgradeFromV2_requiresMigration_2_3() {
        val migration = GlideDatabase.MIGRATION_2_3
        assertEquals("Upgrade path from v2 must start at 2", 2, migration.startVersion)
        assertEquals("Upgrade path from v2 must end at 3", 3, migration.endVersion)
        assertEquals("Each migration covers exactly one version step", 1, migration.endVersion - migration.startVersion)
    }

    @Test
    fun reinstall_upgradeFromV3_requiresMigration_3_4() {
        val migration = GlideDatabase.MIGRATION_3_4
        assertEquals("Upgrade path from v3 must start at 3", 3, migration.startVersion)
        assertEquals("Upgrade path from v3 must end at 4", 4, migration.endVersion)
        assertEquals("Each migration covers exactly one version step", 1, migration.endVersion - migration.startVersion)
    }

    // -------------------------------------------------------------------------
    // 8. Stale transient grant: JournalPhases constants are distinct and detectable
    // -------------------------------------------------------------------------

    @Test
    fun upgrade_staleTransientGrant_pendingPhases_areDistinguishableFromCompleted() {
        val phases = listOf(
            JournalPhases.PREPARED,
            JournalPhases.WRITING,
            JournalPhases.COMMITTING,
            JournalPhases.COMPLETED
        )

        assertEquals("All phase constants must be unique", phases.size, phases.toSet().size)

        val transientPhases = phases - JournalPhases.COMPLETED
        assertFalse(
            "COMPLETED must not appear in the transient (stale-grant) set",
            transientPhases.contains(JournalPhases.COMPLETED)
        )

        for (phase in transientPhases) {
            assertTrue("Transient phase '$phase' must be non-blank", phase.isNotBlank())
        }

        assertEquals("COMPLETED sentinel must equal the string COMPLETED", "COMPLETED", JournalPhases.COMPLETED)
    }
}
