package dev.glide.data.store

import dev.glide.core.resume.ContentDigestTree
import dev.glide.data.db.ResumableSessionDao
import dev.glide.data.db.ResumableSessionEntity
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ResumableStagingStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var stagingDir: File
    private lateinit var fakeDao: FakeResumableSessionDao
    private lateinit var store: ResumableStagingStore

    @Before
    fun setUp() {
        stagingDir = tempFolder.newFolder("resumable_staging")
        fakeDao = FakeResumableSessionDao()
        store = ResumableStagingStore(stagingDir, fakeDao, testDispatcher)
    }

    @Test
    fun initSession_createsDirectoryAndEntity() = runTest(testDispatcher) {
        val sessionDir = store.initSession(
            sessionId = "s1",
            itemId = "i1",
            itemName = "big_file.bin",
            totalBytes = 2000L,
            chunkSize = 1000,
            rootHashHex = "root123",
            nowMs = 1000L
        )

        assertTrue(sessionDir.exists())
        assertEquals(1, fakeDao.sessions.size)
        val entity = fakeDao.sessions["s1"]
        assertEquals("big_file.bin", entity?.itemName)
        assertEquals(0L, entity?.verifiedChunks)
    }

    @Test
    fun writeAndVerifyChunk_validAndInvalidChunks() = runTest(testDispatcher) {
        val chunk0 = "hello world chunk 0".toByteArray()
        val chunk1 = "hello world chunk 1".toByteArray()

        val digestTree = ContentDigestTree(
            listOf(
                ContentDigestTree.sha256(chunk0),
                ContentDigestTree.sha256(chunk1)
            )
        )

        store.initSession("s1", "i1", "file.bin", 200L, 100, digestTree.rootHashHex)

        // Write valid chunk 0
        val ok0 = store.writeAndVerifyChunk("s1", 0L, chunk0, digestTree)
        assertTrue("Valid chunk 0 should succeed", ok0)
        assertEquals(1L, store.getVerifiedChunkCount("s1"))

        // Write corrupted chunk 1
        val corruptedChunk1 = "corrupted data".toByteArray()
        val okCorrupt = store.writeAndVerifyChunk("s1", 1L, corruptedChunk1, digestTree)
        assertFalse("Corrupted chunk should be rejected", okCorrupt)
        assertEquals(1L, store.getVerifiedChunkCount("s1"))

        // Write valid chunk 1
        val ok1 = store.writeAndVerifyChunk("s1", 1L, chunk1, digestTree)
        assertTrue("Valid chunk 1 should succeed", ok1)
        assertEquals(2L, store.getVerifiedChunkCount("s1"))
    }

    @Test
    fun assembleVerifiedFile_combinesChunksContiguously() = runTest(testDispatcher) {
        val chunk0 = "AAAA".toByteArray()
        val chunk1 = "BBBB".toByteArray()

        val digestTree = ContentDigestTree(
            listOf(
                ContentDigestTree.sha256(chunk0),
                ContentDigestTree.sha256(chunk1)
            )
        )

        store.initSession("s1", "i1", "assembled.txt", 8L, 4, digestTree.rootHashHex)
        store.writeAndVerifyChunk("s1", 0L, chunk0, digestTree)
        store.writeAndVerifyChunk("s1", 1L, chunk1, digestTree)

        val destinationFile = File(tempFolder.root, "output/assembled.txt")
        val assembled = store.assembleVerifiedFile("s1", destinationFile)
        assertTrue(assembled)
        assertTrue(destinationFile.exists())
        assertEquals("AAAABBBB", destinationFile.readText())
    }

    @Test
    fun purgeExpiredSessions_deletesExpiredDirectoriesAndEntities() = runTest(testDispatcher) {
        val now = 100_000_000L
        store.initSession("expired_session", "i1", "f1", 100L, 50, "hash", nowMs = 100L)
        store.initSession("active_session", "i2", "f2", 100L, 50, "hash", nowMs = now)

        val purgedCount = store.purgeExpiredSessions(currentMs = now)
        assertEquals(1, purgedCount)
        assertFalse(File(stagingDir, "expired_session").exists())
        assertTrue(File(stagingDir, "active_session").exists())
    }

    @Test
    fun evictUnderStoragePressure_purgesOldestSessionFirst() = runTest(testDispatcher) {
        store.initSession("old_session", "i1", "f1", 100L, 50, "hash", nowMs = 1000L)
        store.initSession("new_session", "i2", "f2", 100L, 50, "hash", nowMs = 2000L)

        var simulatedFreeSpace = 500L
        val minFreeThreshold = 1000L

        val evicted = store.evictUnderStoragePressure(minFreeThreshold) {
            simulatedFreeSpace += 500L // Each eviction frees 500L
            simulatedFreeSpace - 500L
        }

        assertEquals(1, evicted)
        assertFalse("Old session should be evicted first", File(stagingDir, "old_session").exists())
        assertTrue("New session should remain", File(stagingDir, "new_session").exists())
    }

    private class FakeResumableSessionDao : ResumableSessionDao {
        val sessions = mutableMapOf<String, ResumableSessionEntity>()

        override suspend fun insertOrUpdate(session: ResumableSessionEntity) {
            sessions[session.sessionId] = session
        }

        override suspend fun getBySessionId(sessionId: String): ResumableSessionEntity? =
            sessions[sessionId]

        override suspend fun getAllResumableSessions(): List<ResumableSessionEntity> =
            sessions.values.sortedByDescending { it.createdAtMs }

        override suspend fun updateVerifiedChunks(sessionId: String, verifiedChunks: Long) {
            sessions[sessionId]?.let {
                sessions[sessionId] = it.copy(verifiedChunks = verifiedChunks)
            }
        }

        override suspend fun deleteBySessionId(sessionId: String) {
            sessions.remove(sessionId)
        }

        override suspend fun deleteExpired(currentMs: Long): Int {
            val toRemove = sessions.values.filter { it.expiresAtMs <= currentMs }.map { it.sessionId }
            toRemove.forEach { sessions.remove(it) }
            return toRemove.size
        }

        override suspend fun getExpiredSessions(currentMs: Long): List<ResumableSessionEntity> =
            sessions.values.filter { it.expiresAtMs <= currentMs }

        override suspend fun getSessionCount(): Int = sessions.size
    }
}
