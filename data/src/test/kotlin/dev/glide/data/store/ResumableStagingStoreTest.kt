package dev.glide.data.store

import dev.glide.core.resume.ContentDigestTree
import dev.glide.data.db.ResumableSessionDao
import dev.glide.data.db.ResumableSessionEntity
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertIs
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ResumableStagingStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher =
        StandardTestDispatcher()
    private lateinit var stagingDir: File
    private lateinit var fakeDao:
        FakeResumableSessionDao
    private lateinit var store:
        ResumableStagingStore

    @Before
    fun setUp() {
        stagingDir =
            tempFolder.newFolder(
                "resumable_staging"
            )
        fakeDao =
            FakeResumableSessionDao()
        store =
            ResumableStagingStore(
                stagingDir,
                fakeDao,
                testDispatcher
            )
    }

    @Test
    fun initSession_persistsCompositeAuthorityAndZeroPrefix() =
        runTest(testDispatcher) {
            val chunks =
                listOf(
                    "AAAA".toByteArray(),
                    "BBBB".toByteArray()
                )
            val tree =
                digestTree(chunks)

            val sessionDir =
                store.initSession(
                    originalSessionId =
                        "session-1",
                    itemId = "item-1",
                    itemName = "big.bin",
                    peerFingerprintHex =
                        fingerprint('a'),
                    sourceIdentity =
                        "content://source/v1",
                    totalBytes = 8L,
                    chunkSize = 4,
                    digestTree = tree,
                    wholeFileSha256Hex =
                        wholeDigest(chunks),
                    nowMs = 1_000L
                )

            assertTrue(sessionDir.isDirectory)
            val entity =
                fakeDao.get(
                    "session-1",
                    "item-1"
                )
            assertNotNull(entity)
            assertEquals(
                fingerprint('a'),
                entity?.peerFingerprintHex
            )
            assertEquals(
                "content://source/v1",
                entity?.sourceIdentity
            )
            assertEquals(
                0L,
                entity?.verifiedChunks
            )
            assertEquals(
                0L,
                entity?.contiguousByteOffset
            )
            assertEquals(
                tree.rootHashHex,
                entity?.chunkRootHashHex
            )
        }

    @Test
    fun outOfOrderChunk_cannotAdvanceAcrossGap() =
        runTest(testDispatcher) {
            val chunks =
                listOf(
                    "0000".toByteArray(),
                    "1111".toByteArray(),
                    "2222".toByteArray()
                )
            init(
                session = "gap-session",
                item = "gap-item",
                chunks = chunks,
                nowMs = 1_000L
            )

            assertFalse(
                store.writeAndVerifyChunk(
                    "gap-session",
                    "gap-item",
                    1L,
                    chunks[1],
                    nowMs = 1_100L
                )
            )
            assertEquals(
                0L,
                store.getVerifiedChunkCount(
                    "gap-session",
                    "gap-item"
                )
            )

            assertTrue(
                store.writeAndVerifyChunk(
                    "gap-session",
                    "gap-item",
                    0L,
                    chunks[0],
                    nowMs = 1_200L
                )
            )
            assertEquals(
                1L,
                store.getVerifiedChunkCount(
                    "gap-session",
                    "gap-item"
                )
            )

            assertFalse(
                store.writeAndVerifyChunk(
                    "gap-session",
                    "gap-item",
                    2L,
                    chunks[2],
                    nowMs = 1_300L
                )
            )
            assertEquals(
                1L,
                store.getVerifiedChunkCount(
                    "gap-session",
                    "gap-item"
                )
            )
        }

    @Test
    fun corruptedChunk_isRejectedWithoutJournalAdvance() =
        runTest(testDispatcher) {
            val chunks =
                listOf(
                    "AAAA".toByteArray(),
                    "BBBB".toByteArray()
                )
            init(
                "corrupt-session",
                "corrupt-item",
                chunks
            )

            assertTrue(
                store.writeAndVerifyChunk(
                    "corrupt-session",
                    "corrupt-item",
                    0L,
                    chunks[0]
                )
            )
            assertFalse(
                store.writeAndVerifyChunk(
                    "corrupt-session",
                    "corrupt-item",
                    1L,
                    "XXXX".toByteArray()
                )
            )
            val entity =
                store.getJournal(
                    "corrupt-session",
                    "corrupt-item"
                )
            assertEquals(
                1L,
                entity?.verifiedChunks
            )
            assertEquals(
                4L,
                entity?.contiguousByteOffset
            )
        }

    @Test
    fun processDeathRevalidation_rewindsAtFirstCorruptRetainedChunk() =
        runTest(testDispatcher) {
            val chunks =
                listOf(
                    "AAAA".toByteArray(),
                    "BBBB".toByteArray(),
                    "CCCC".toByteArray()
                )
            val tree =
                init(
                    "restart-session",
                    "restart-item",
                    chunks
                )

            chunks.forEachIndexed {
                index,
                bytes ->
                assertTrue(
                    store.writeAndVerifyChunk(
                        "restart-session",
                        "restart-item",
                        index.toLong(),
                        bytes
                    )
                )
            }

            val journal =
                store.getJournal(
                    "restart-session",
                    "restart-item"
                )!!
            File(
                journal.stagingPath,
                "chunk_000000000001.part"
            ).writeBytes(
                "FAIL".toByteArray()
            )

            // Simulate a process restart by constructing a new store over the same persisted DAO.
            val restarted =
                ResumableStagingStore(
                    stagingDir,
                    fakeDao,
                    testDispatcher
                )
            val result =
                restarted.reconcileForResume(
                    originalSessionId =
                        "restart-session",
                    itemId =
                        "restart-item",
                    authenticatedPeerFingerprintHex =
                        fingerprint('a'),
                    peerStillTrusted = true,
                    sourceStillAvailable = true,
                    currentSourceIdentity =
                        "source:v1",
                    currentTotalBytes =
                        12L,
                    currentChunkRootHashHex =
                        tree.rootHashHex
                )

            val ready =
                assertIs<
                    ResumeRevalidationResult.Ready
                >(result)
            assertEquals(
                1L,
                ready.verifiedChunks
            )
            assertEquals(
                4L,
                ready.contiguousByteOffset
            )
            assertFalse(
                File(
                    journal.stagingPath,
                    "chunk_000000000001.part"
                ).exists()
            )
            assertFalse(
                File(
                    journal.stagingPath,
                    "chunk_000000000002.part"
                ).exists()
            )
        }

    @Test
    fun revokedOrDifferentPeer_cannotAuthorizeRetainedPrefix() =
        runTest(testDispatcher) {
            val chunks =
                listOf(
                    "AAAA".toByteArray()
                )
            val tree =
                init(
                    "peer-session",
                    "peer-item",
                    chunks
                )
            assertTrue(
                store.writeAndVerifyChunk(
                    "peer-session",
                    "peer-item",
                    0L,
                    chunks[0]
                )
            )

            val revoked =
                store.reconcileForResume(
                    "peer-session",
                    "peer-item",
                    fingerprint('a'),
                    peerStillTrusted = false,
                    sourceStillAvailable = true,
                    currentSourceIdentity =
                        "source:v1",
                    currentTotalBytes = 4L,
                    currentChunkRootHashHex =
                        tree.rootHashHex
                )
            assertIs<
                ResumeRevalidationResult.Unauthorized
            >(revoked)

            val substituted =
                store.reconcileForResume(
                    "peer-session",
                    "peer-item",
                    fingerprint('b'),
                    peerStillTrusted = true,
                    sourceStillAvailable = true,
                    currentSourceIdentity =
                        "source:v1",
                    currentTotalBytes = 4L,
                    currentChunkRootHashHex =
                        tree.rootHashHex
                )
            assertIs<
                ResumeRevalidationResult.Unauthorized
            >(substituted)
        }

    @Test
    fun changedOrUnavailableSource_isRejected() =
        runTest(testDispatcher) {
            val chunks =
                listOf(
                    "AAAA".toByteArray()
                )
            val tree =
                init(
                    "source-session",
                    "source-item",
                    chunks
                )

            val unavailable =
                store.reconcileForResume(
                    "source-session",
                    "source-item",
                    fingerprint('a'),
                    peerStillTrusted = true,
                    sourceStillAvailable = false,
                    currentSourceIdentity =
                        "source:v1",
                    currentTotalBytes = 4L,
                    currentChunkRootHashHex =
                        tree.rootHashHex
                )
            assertIs<
                ResumeRevalidationResult.SourceUnavailable
            >(unavailable)

            val changed =
                store.reconcileForResume(
                    "source-session",
                    "source-item",
                    fingerprint('a'),
                    peerStillTrusted = true,
                    sourceStillAvailable = true,
                    currentSourceIdentity =
                        "source:v2",
                    currentTotalBytes = 4L,
                    currentChunkRootHashHex =
                        tree.rootHashHex
                )
            assertIs<
                ResumeRevalidationResult.SourceChanged
            >(changed)
        }

    @Test
    fun expiredJournal_isPurgedAtResumeBoundary() =
        runTest(testDispatcher) {
            val chunks =
                listOf(
                    "AAAA".toByteArray()
                )
            val tree =
                init(
                    "expired-session",
                    "expired-item",
                    chunks,
                    nowMs = 100L
                )

            val result =
                store.reconcileForResume(
                    "expired-session",
                    "expired-item",
                    fingerprint('a'),
                    peerStillTrusted = true,
                    sourceStillAvailable = true,
                    currentSourceIdentity =
                        "source:v1",
                    currentTotalBytes = 4L,
                    currentChunkRootHashHex =
                        tree.rootHashHex,
                    nowMs =
                        100L +
                            dev.glide.core.resume
                                .ResumeToken
                                .MAX_TTL_MILLIS
                )

            assertIs<
                ResumeRevalidationResult.Expired
            >(result)
            assertNull(
                fakeDao.get(
                    "expired-session",
                    "expired-item"
                )
            )
        }

    @Test
    fun assembly_requiresCompletePrefixAndWholeFileDigest() =
        runTest(testDispatcher) {
            val chunks =
                listOf(
                    "AAAA".toByteArray(),
                    "BBBB".toByteArray()
                )
            init(
                "assemble-session",
                "assemble-item",
                chunks
            )
            val destination =
                File(
                    tempFolder.root,
                    "output/assembled.bin"
                )

            assertTrue(
                store.writeAndVerifyChunk(
                    "assemble-session",
                    "assemble-item",
                    0L,
                    chunks[0]
                )
            )
            assertFalse(
                store.assembleVerifiedFile(
                    "assemble-session",
                    "assemble-item",
                    destination
                )
            )
            assertFalse(destination.exists())

            assertTrue(
                store.writeAndVerifyChunk(
                    "assemble-session",
                    "assemble-item",
                    1L,
                    chunks[1]
                )
            )
            assertTrue(
                store.assembleVerifiedFile(
                    "assemble-session",
                    "assemble-item",
                    destination
                )
            )
            assertEquals(
                chunks
                    .flatMap {
                        it.toList()
                    }
                    .toByteArray()
                    .toList(),
                destination
                    .readBytes()
                    .toList()
            )
        }

    @Test
    fun storagePressure_evictsOldestRetainedItemFirst() =
        runTest(testDispatcher) {
            val chunks =
                listOf(
                    "AAAA".toByteArray()
                )
            init(
                "old-session",
                "old-item",
                chunks,
                nowMs = 1_000L
            )
            init(
                "new-session",
                "new-item",
                chunks,
                nowMs = 2_000L
            )

            var call = 0
            val evicted =
                store.evictUnderStoragePressure(
                    minFreeBytes = 1_000L
                ) {
                    call++
                    if (call == 1) {
                        500L
                    } else {
                        1_500L
                    }
                }

            assertEquals(1, evicted)
            assertNull(
                fakeDao.get(
                    "old-session",
                    "old-item"
                )
            )
            assertNotNull(
                fakeDao.get(
                    "new-session",
                    "new-item"
                )
            )
        }

    private suspend fun init(
        session: String,
        item: String,
        chunks: List<ByteArray>,
        nowMs: Long = 1_000L
    ): ContentDigestTree {
        val tree =
            digestTree(chunks)
        store.initSession(
            originalSessionId = session,
            itemId = item,
            itemName = "$item.bin",
            peerFingerprintHex =
                fingerprint('a'),
            sourceIdentity =
                "source:v1",
            totalBytes =
                chunks.sumOf {
                    it.size.toLong()
                },
            chunkSize =
                chunks.first().size,
            digestTree = tree,
            wholeFileSha256Hex =
                wholeDigest(chunks),
            nowMs = nowMs
        )
        return tree
    }

    private fun digestTree(
        chunks: List<ByteArray>
    ): ContentDigestTree =
        ContentDigestTree(
            chunks.map {
                ContentDigestTree
                    .sha256(it)
            }
        )

    private fun wholeDigest(
        chunks: List<ByteArray>
    ): String {
        val digest =
            MessageDigest.getInstance(
                "SHA-256"
            )
        chunks.forEach {
            digest.update(it)
        }
        return digest.digest()
            .joinToString("") {
                "%02x".format(it)
            }
    }

    private fun fingerprint(
        value: Char
    ): String =
        value.toString()
            .repeat(64)

    private class FakeResumableSessionDao :
        ResumableSessionDao {

        val sessions =
            mutableMapOf<
                Pair<String, String>,
                ResumableSessionEntity
            >()

        override suspend fun insertOrUpdate(
            session: ResumableSessionEntity
        ) {
            sessions[
                session.originalSessionId to
                    session.itemId
            ] = session
        }

        override suspend fun get(
            originalSessionId: String,
            itemId: String
        ): ResumableSessionEntity? =
            sessions[
                originalSessionId to
                    itemId
            ]

        override suspend fun getAllResumableSessions():
            List<ResumableSessionEntity> =
            sessions.values
                .sortedByDescending {
                    it.updatedAtMs
                }

        override suspend fun compareAndAdvance(
            originalSessionId: String,
            itemId: String,
            expectedVerifiedChunks: Long,
            expectedContiguousByteOffset: Long,
            newVerifiedChunks: Long,
            newContiguousByteOffset: Long,
            updatedAtMs: Long
        ): Int {
            val key =
                originalSessionId to
                    itemId
            val existing =
                sessions[key]
                    ?: return 0
            if (existing.verifiedChunks !=
                    expectedVerifiedChunks ||
                existing.contiguousByteOffset !=
                    expectedContiguousByteOffset
            ) {
                return 0
            }

            sessions[key] =
                existing.copy(
                    verifiedChunks =
                        newVerifiedChunks,
                    contiguousByteOffset =
                        newContiguousByteOffset,
                    updatedAtMs =
                        updatedAtMs
                )
            return 1
        }

        override suspend fun rewindProgress(
            originalSessionId: String,
            itemId: String,
            verifiedChunks: Long,
            contiguousByteOffset: Long,
            updatedAtMs: Long
        ): Int {
            val key =
                originalSessionId to
                    itemId
            val existing =
                sessions[key]
                    ?: return 0
            if (existing.verifiedChunks <
                    verifiedChunks ||
                existing.contiguousByteOffset <
                    contiguousByteOffset
            ) {
                return 0
            }

            sessions[key] =
                existing.copy(
                    verifiedChunks =
                        verifiedChunks,
                    contiguousByteOffset =
                        contiguousByteOffset,
                    updatedAtMs =
                        updatedAtMs
                )
            return 1
        }

        override suspend fun delete(
            originalSessionId: String,
            itemId: String
        ) {
            sessions.remove(
                originalSessionId to
                    itemId
            )
        }

        override suspend fun getExpiredSessions(
            currentMs: Long
        ): List<ResumableSessionEntity> =
            sessions.values
                .filter {
                    it.expiresAtMs <=
                        currentMs
                }

        override suspend fun deleteExpired(
            currentMs: Long
        ): Int {
            val keys =
                sessions
                    .filterValues {
                        it.expiresAtMs <=
                            currentMs
                    }
                    .keys
                    .toList()
            keys.forEach {
                sessions.remove(it)
            }
            return keys.size
        }

        override suspend fun getSessionCount():
            Int =
            sessions.size
    }
}
