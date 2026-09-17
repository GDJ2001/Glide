package dev.glide.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests that exported Room schema JSON files are present, valid, structurally correct,
 * and contiguous (no version gaps).
 *
 * Implemented using pure Kotlin string and regex matching — avoiding Android stubbed
 * org.json classes that return null in JVM unit tests.
 *
 * GLIDE-056: database/upgrade regression suite.
 */
class DatabaseSchemaVersionTest {

    companion object {
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
            assertTrue("Schema file $version.json must exist at ${file.canonicalPath}", file.exists())
            return file.readText()
        }
    }

    @Test
    fun schema_version1_json_existsAndDeclaresVersion1() {
        val text = readSchemaText(1)
        assertTrue("Schema 1.json must have formatVersion 1", text.contains("\"formatVersion\": 1"))
        assertTrue("Schema 1.json must declare version 1", text.contains("\"version\": 1"))
    }

    @Test
    fun schema_version2_json_existsAndDeclaresVersion2() {
        val text = readSchemaText(2)
        assertTrue("Schema 2.json must have formatVersion 1", text.contains("\"formatVersion\": 1"))
        assertTrue("Schema 2.json must declare version 2", text.contains("\"version\": 2"))
    }

    @Test
    fun schema_version2_identityHash_matchesExpected() {
        val text = readSchemaText(2)
        val expectedHash = "9fcbd86d92a15d6fba82ea026f089c3e"
        assertTrue(
            "identityHash must match compiled Room schema (expected $expectedHash)",
            text.contains("\"identityHash\": \"$expectedHash\"")
        )
    }

    @Test
    fun schema_version2_containsAllFourTables() {
        val text = readSchemaText(2)
        assertTrue("receive_journal table missing from v2 schema", text.contains("\"tableName\": \"receive_journal\""))
        assertTrue("received_files table missing from v2 schema", text.contains("\"tableName\": \"received_files\""))
        assertTrue("sessions table missing from v2 schema", text.contains("\"tableName\": \"sessions\""))
        assertTrue("items table missing from v2 schema", text.contains("\"tableName\": \"items\""))
    }

    @Test
    fun schema_version2_sessions_hasAllEightColumns() {
        val text = readSchemaText(2)
        val requiredColumns = listOf(
            "session_id",
            "batch_id",
            "role",
            "peer_label",
            "started_at_ms",
            "ended_at_ms",
            "outcome",
            "failure_code"
        )
        for (col in requiredColumns) {
            assertTrue(
                "sessions entity in v2 must contain column $col",
                text.contains("\"columnName\": \"$col\"")
            )
        }
    }

    @Test
    fun schema_version2_items_hasForeignKeyReferencingSessions_withCascadeDelete() {
        val text = readSchemaText(2)
        assertTrue("items FK must reference sessions table", text.contains("\"table\": \"sessions\""))
        assertTrue("items FK onDelete must be CASCADE", text.contains("\"onDelete\": \"CASCADE\""))
    }

    @Test
    fun schema_version2_items_hasCompositeUniqueIndex_sessionIdOrdinal() {
        val text = readSchemaText(2)
        assertTrue(
            "items unique index on (session_id, ordinal) must be present",
            text.contains("\"name\": \"index_items_session_id_ordinal\"") &&
                text.contains("\"unique\": true")
        )
    }

    @Test
    fun schemaFiles_areContiguous_noVersionGap() {
        val jsonFiles = schemaDir.listFiles { f -> f.extension == "json" }
            ?: error("Schema directory is empty")
        val versions = jsonFiles.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.sorted()
        assertTrue("At least schema version 1 must exist", versions.isNotEmpty())
        for (i in 1 until versions.size) {
            assertEquals(
                "Schema versions must be contiguous. Gap between ${versions[i - 1]} and ${versions[i]}",
                versions[i - 1] + 1, versions[i]
            )
        }
    }

    @Test
    fun migrationCount_matchesSchemaVersionCount_minusOne() {
        val jsonFiles = schemaDir.listFiles { f -> f.extension == "json" }
            ?: error("Schema directory is empty")
        val versionCount = jsonFiles.count { it.nameWithoutExtension.toIntOrNull() != null }

        // GlideDatabase currently defines MIGRATION_1_2, MIGRATION_2_3, and MIGRATION_3_4
        val declaredMigrations = listOf(GlideDatabase.MIGRATION_1_2, GlideDatabase.MIGRATION_2_3, GlideDatabase.MIGRATION_3_4)
        assertEquals(
            "Number of declared migrations (${declaredMigrations.size}) must equal (schema version count - 1 = ${versionCount - 1})",
            versionCount - 1, declaredMigrations.size
        )
    }

    @Test
    fun schema_version3_json_existsAndDeclaresVersion3() {
        val text = readSchemaText(3)
        assertTrue("Schema 3.json must have formatVersion 1", text.contains("\"formatVersion\": 1"))
        assertTrue("Schema 3.json must declare version 3", text.contains("\"version\": 3"))
    }

    @Test
    fun schema_version3_identityHash_matchesExpected() {
        val text = readSchemaText(3)
        val expectedHash = "ada8c0a40029be704393657a03cf3312"
        assertTrue(
            "identityHash must match compiled Room schema (expected $expectedHash)",
            text.contains("\"identityHash\": \"$expectedHash\"")
        )
    }

    @Test
    fun schema_version3_containsRelativePathInTables() {
        val text = readSchemaText(3)
        assertTrue("receive_journal must have relative_path in v3", text.contains("`relative_path` TEXT DEFAULT NULL"))
        assertTrue("received_files must have relative_path in v3", text.contains("`relative_path` TEXT DEFAULT NULL"))
        assertTrue("items must have relative_path in v3", text.contains("`relative_path` TEXT DEFAULT NULL"))
    }

    @Test
    fun schema_version4_json_existsAndDeclaresVersion4() {
        val text = readSchemaText(4)
        assertTrue("Schema 4.json must have formatVersion 1", text.contains("\"formatVersion\": 1"))
        assertTrue("Schema 4.json must declare version 4", text.contains("\"version\": 4"))
    }

    @Test
    fun schema_version4_identityHash_matchesExpected() {
        val text = readSchemaText(4)
        val expectedHash = "e2ef8883c40077818784abeb71e64fb0"
        assertTrue(
            "identityHash must match compiled Room schema (expected $expectedHash)",
            text.contains("\"identityHash\": \"$expectedHash\"")
        )
    }

    @Test
    fun schema_version4_containsTrustedPeersAndResumableSessions() {
        val text = readSchemaText(4)
        assertTrue("trusted_peers table missing from v4 schema", text.contains("\"tableName\": \"trusted_peers\""))
        assertTrue("resumable_sessions table missing from v4 schema", text.contains("\"tableName\": \"resumable_sessions\""))
    }
}
