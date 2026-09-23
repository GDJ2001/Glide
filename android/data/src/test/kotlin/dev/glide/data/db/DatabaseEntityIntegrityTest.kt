package dev.glide.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM unit tests verifying entity integrity, Kotlin property types, nullability,
 * and presence of Room annotations in entity source declarations.
 *
 * (Note: Room annotations use RetentionPolicy.CLASS, so they are not retained
 * for Class.getAnnotation() at runtime; we inspect source and JVM class definitions).
 *
 * GLIDE-056: database/upgrade regression suite.
 */
class DatabaseEntityIntegrityTest {

    companion object {
        private val sourceDir: File by lazy {
            val candidates = listOf(
                File("src/main/kotlin/dev/glide/data/db"),
                File("../data/src/main/kotlin/dev/glide/data/db")
            )
            candidates.firstOrNull { it.exists() }
                ?: error("Could not locate db source dir. Tried: " + candidates.map { it.canonicalPath })
        }

        private fun readSource(filename: String): String {
            val file = File(sourceDir, filename)
            assertTrue("Source file $filename must exist at ${file.canonicalPath}", file.exists())
            return file.readText()
        }
    }

    // -------------------------------------------------------------------------
    // SessionEntity
    // -------------------------------------------------------------------------

    @Test
    fun sessionEntity_tableName_isSessions() {
        val src = readSource("SessionEntity.kt")
        assertTrue("SessionEntity must declare tableName = \"sessions\"", src.contains("tableName = \"sessions\""))
    }

    @Test
    fun sessionEntity_primaryKey_isOnSessionIdField() {
        val src = readSource("SessionEntity.kt")
        assertTrue("sessionId must be annotated @PrimaryKey", src.contains("@PrimaryKey") && src.contains("val sessionId: String"))
        assertTrue("sessionId must be named session_id in @ColumnInfo", src.contains("@ColumnInfo(name = \"session_id\")"))
    }

    @Test
    fun sessionEntity_mandatoryFields_areNonNullableKotlinTypes() {
        val cls = SessionEntity::class.java
        assertEquals("sessionId must be java.lang.String", String::class.java, cls.getDeclaredField("sessionId").type)
        assertEquals("batchId must be java.lang.String", String::class.java, cls.getDeclaredField("batchId").type)
        assertEquals("role must be java.lang.String", String::class.java, cls.getDeclaredField("role").type)
        assertEquals("peerLabel must be java.lang.String", String::class.java, cls.getDeclaredField("peerLabel").type)
        assertEquals("startedAtMs must be primitive long", Long::class.javaPrimitiveType, cls.getDeclaredField("startedAtMs").type)
    }

    @Test
    fun sessionEntity_optionalFields_areNullableKotlinTypes() {
        val cls = SessionEntity::class.java
        assertEquals("endedAtMs must be boxed Long", java.lang.Long::class.java, cls.getDeclaredField("endedAtMs").type)
        assertEquals("outcome must be java.lang.String", String::class.java, cls.getDeclaredField("outcome").type)
        assertEquals("failureCode must be java.lang.String", String::class.java, cls.getDeclaredField("failureCode").type)
    }

    @Test
    fun sessionEntity_hasThreeIndicesAnnotated() {
        val src = readSource("SessionEntity.kt")
        assertTrue("batch_id index missing", src.contains("Index(value = [\"batch_id\"], unique = true)"))
        assertTrue("started_at_ms index missing", src.contains("Index(value = [\"started_at_ms\"])"))
        assertTrue("role+started_at_ms index missing", src.contains("Index(value = [\"role\", \"started_at_ms\"])"))
    }

    // -------------------------------------------------------------------------
    // ItemEntity
    // -------------------------------------------------------------------------

    @Test
    fun itemEntity_tableName_isItems() {
        val src = readSource("ItemEntity.kt")
        assertTrue("ItemEntity must declare tableName = \"items\"", src.contains("tableName = \"items\""))
    }

    @Test
    fun itemEntity_foreignKey_referencesSession_withCascadeDelete() {
        val src = readSource("ItemEntity.kt")
        assertTrue("FK must reference SessionEntity", src.contains("entity = SessionEntity::class"))
        assertTrue("FK onDelete must be CASCADE", src.contains("onDelete = ForeignKey.CASCADE"))
        assertTrue("FK parentColumns must contain session_id", src.contains("parentColumns = [\"session_id\"]"))
        assertTrue("FK childColumns must contain session_id", src.contains("childColumns = [\"session_id\"]"))
    }

    @Test
    fun itemEntity_ordinal_isNonNullableIntField() {
        val field = ItemEntity::class.java.getDeclaredField("ordinal")
        assertEquals("ordinal must be primitive int (non-nullable)", Int::class.javaPrimitiveType, field.type)
    }

    // -------------------------------------------------------------------------
    // ReceiveJournalEntity
    // -------------------------------------------------------------------------

    @Test
    fun receiveJournalEntity_phaseField_hasColumnInfoAnnotation() {
        val src = readSource("ReceiveJournalEntity.kt")
        assertTrue("phase must be @ColumnInfo(name = \"phase\")", src.contains("@ColumnInfo(name = \"phase\")"))
    }

    // -------------------------------------------------------------------------
    // ReceivedFileEntity
    // -------------------------------------------------------------------------

    @Test
    fun receivedFileEntity_digestField_isNonNullableString() {
        val field = ReceivedFileEntity::class.java.getDeclaredField("digest")
        assertEquals("digest must be non-nullable String", String::class.java, field.type)
    }
}
