package dev.glide.data.store

import dev.glide.core.resume.ContentDigestTree
import dev.glide.data.db.ResumableSessionDao
import dev.glide.data.db.ResumableSessionEntity
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun receiverInit_persistsCompositeAuthorityAndZeroPrefix() =
        runTest(testDispatcher) {
            val chunks =
                chunks("AAAA", "BBBB")
            val tree =
                digestTree(chunks)

            val sessionDir =
                store.initReceiverSession(
                    originalSessionId =
                        "session-1",
                    itemId = "item-1",
                    itemName = "big.bin",
                    peerFingerprintHex =
                        fingerprint('a'),
                    totalBytes = 8L,
                    chunkSize = 4,
                    chunkRootHashHex =
                        tree.rootHashHex,
                    nowMs = 1_000L
                )

            assertTrue(
                sessionDir.isDirectory
            )
            val entity =
                fakeDao.get(
                    "session-1",
                    "item-1"
                )
            assertNotNull(entity)
            assertEquals(
                ResumableSessionEntity
                    .ROLE_RECEIVER,
                entity?.journalRole
            )
            assertEquals(
                fingerprint('a'),
                entity?.peerFingerprintHex
            )
            assertNull(
                entity?.sourceHandle
            )
            assertNull(
                entity?.sourceIdentity
            )
            assertEquals(
                "",
                entity
                    ?.verifiedChunkHashesHex
            )
            assertEquals(
                0L,
                entity?.verifiedChunks
            )
            assertEquals(
                0L,
                entity
                    ?.contiguousByteOffset
            )
            assertEquals(
                tree.rootHashHex,
                entity?.chunkRootHashHex
            )
        }

    @Test
    fun senderInit_persistsSourceAuthorityWithoutReceiverStaging() =
        runTest(testDispatcher) {
            val chunks =
                chunks("AAAA", "BBBB")
            val tree =
                digestTree(chunks)
            val whole =
                wholeDigest(chunks)

            val entity =
                store.initSenderSession(
                    originalSessionId =
                        "send-session",
                    itemId = "send-item",
                    itemName = "send.bin",
                    peerFingerprintHex =
                        fingerprint('b'),
                    sourceHandle =
                        "content://source/42",
                    sourceIdentity =
                        fingerprint('c'),
                    totalBytes = 8L,
                    chunkSize = 4,
                    chunkRootHashHex =
                        tree.rootHashHex,
                    wholeFileSha256Hex =
                        whole,
                    nowMs = 2_000L
                )

            assertEquals(
                ResumableSessionEntity
                    .ROLE_SENDER,
                entity.journalRole
            )
            assertEquals(
                "content://source/42",
                entity.sourceHandle
            )
            assertEquals(
                fingerprint('c'),
                entity.sourceIdentity
            )
            assertEquals(
                whole,
                entity.wholeFileSha256Hex
            )
            assertNull(
                entity.stagingPath
            )
        }

    @Test
    fun outOfOrderReceiverChunk_cannotAdvanceAcrossGap() =
        runTest(testDispatcher) {
            val chunks =
                chunks(
                    "0000",
                    "1111",
                    "2222"
                )
            initReceiver(
                "gap-session",
                "gap-item",
                chunks
            )

            assertFalse(
                store.recordReceiverChunk(
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
                store.recordReceiverChunk(
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
                store.recordReceiverChunk(
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
    fun receiverReinit_doesNotResetExistingProgress() =
        runTest(testDispatcher) {
            val chunks =
                chunks("AAAA", "BBBB")
            val tree =
                initReceiver(
                    "same-session",
                    "same-item",
                    chunks
                )
            assertTrue(
                store.recordReceiverChunk(
                    "same-session",
                    "same-item",
                    0L,
                    chunks[0]
                )
            )

            store.initReceiverSession(
                originalSessionId =
                    "same-session",
                itemId = "same-item",
                itemName = "same.bin",
                peerFingerprintHex =
                    fingerprint('a'),
                totalBytes = 8L,
                chunkSize = 4,
                chunkRootHashHex =
                    tree.rootHashHex
            )

            val entity =
                store.getJournal(
                    "same-session",
                    "same-item"
                )
            assertEquals(
                1L,
                entity?.verifiedChunks
            )
            assertEquals(
                4L,
                entity
                    ?.contiguousByteOffset
            )
            assertTrue(
                entity
                    ?.verifiedChunkHashesHex
                    ?.isNotBlank() ==
                    true
            )
        }

    @Test
    fun processDeathRevalidation_rewindsAtFirstCorruptRetainedChunk() =
        runTest(testDispatcher) {
            val chunks =
                chunks(
                    "AAAA",
                    "BBBB",
                    "CCCC"
                )
            initReceiver(
                "restart-session",
                "restart-item",
                chunks
            )

            chunks.forEachIndexed {
                index,
                bytes ->
                assertTrue(
                    store.recordReceiverChunk(
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
                journal.stagingPath!!,
                "chunk_000000000001.part"
            ).writeBytes(
                "FAIL".toByteArray()
            )

            val restarted =
                ResumableStagingStore(
                    stagingDir,
                    fakeDao,
                    testDispatcher
                )
            val result =
                restarted
                    .reconcileReceiverForResume(
                        originalSessionId =
                            "restart-session",
                        itemId =
                            "restart-item",
                        authenticatedPeerFingerprintHex =
                            fingerprint('a'),
                        peerStillTrusted =
                            true
                    )

            assertTrue(
                result is
                    ResumeReceiverRevalidationResult.Ready
            )
            val ready =
                result as
                    ResumeReceiverRevalidationResult.Ready
            assertEquals(
                1L,
                ready.verifiedChunks
            )
            assertEquals(
                4L,
                ready.contiguousByteOffset
            )

            val updated =
                store.getJournal(
                    "restart-session",
                    "restart-item"
                )!!
            assertEquals(
                1,
                updated
                    .verifiedChunkHashesHex
                    .split(',')
                    .size
            )
            assertFalse(
                File(
                    journal.stagingPath!!,
                    "chunk_000000000001.part"
                ).exists()
            )
            assertFalse(
                File(
                    journal.stagingPath!!,
                    "chunk_000000000002.part"
                ).exists()
            )
        }

    @Test
    fun revokedOrDifferentPeer_cannotAuthorizeReceiverPrefix() =
        runTest(testDispatcher) {
            val chunks =
                chunks("AAAA")
            initReceiver(
                "peer-session",
                "peer-item",
                chunks
            )
            assertTrue(
                store.recordReceiverChunk(
                    "peer-session",
                    "peer-item",
                    0L,
                    chunks[0]
                )
            )

            val revoked =
                store.reconcileReceiverForResume(
                    "peer-session",
                    "peer-item",
                    fingerprint('a'),
                    peerStillTrusted =
                        false
                )
            assertTrue(
                revoked is
                    ResumeReceiverRevalidationResult.Unauthorized
            )

            val substituted =
                store.reconcileReceiverForResume(
                    "peer-session",
                    "peer-item",
                    fingerprint('b'),
                    peerStillTrusted =
                        true
                )
            assertTrue(
                substituted is
                    ResumeReceiverRevalidationResult.Unauthorized
            )
        }

    @Test
    fun senderRevalidation_rejectsSourceChangeAndPermissionLoss() =
        runTest(testDispatcher) {
            val chunks =
                chunks("AAAA", "BBBB")
            val tree =
                digestTree(chunks)
            val whole =
                wholeDigest(chunks)
            store.initSenderSession(
                originalSessionId =
                    "source-session",
                itemId = "source-item",
                itemName = "source.bin",
                peerFingerprintHex =
                    fingerprint('a'),
                sourceHandle =
                    "content://source/7",
                sourceIdentity =
                    fingerprint('c'),
                totalBytes = 8L,
                chunkSize = 4,
                chunkRootHashHex =
                    tree.rootHashHex,
                wholeFileSha256Hex =
                    whole
            )

            val unavailable =
                store.reconcileSenderForResume(
                    originalSessionId =
                        "source-session",
                    itemId = "source-item",
                    authenticatedPeerFingerprintHex =
                        fingerprint('a'),
                    peerStillTrusted = true,
                    sourceStillAvailable =
                        false,
                    currentSourceIdentity =
                        fingerprint('c'),
                    currentTotalBytes = 8L,
                    currentChunkRootHashHex =
                        tree.rootHashHex,
                    currentWholeFileSha256Hex =
                        whole
                )
            assertTrue(
                unavailable is
                    ResumeSenderRevalidationResult.SourceUnavailable
            )

            val changed =
                store.reconcileSenderForResume(
                    originalSessionId =
                        "source-session",
                    itemId = "source-item",
                    authenticatedPeerFingerprintHex =
                        fingerprint('a'),
                    peerStillTrusted = true,
                    sourceStillAvailable =
                        true,
                    currentSourceIdentity =
                        fingerprint('d'),
                    currentTotalBytes = 8L,
                    currentChunkRootHashHex =
                        tree.rootHashHex,
                    currentWholeFileSha256Hex =
                        whole
                )
            assertTrue(
                changed is
                    ResumeSenderRevalidationResult.SourceChanged
            )

            val ready =
                store.reconcileSenderForResume(
                    originalSessionId =
                        "source-session",
                    itemId = "source-item",
                    authenticatedPeerFingerprintHex =
                        fingerprint('a'),
                    peerStillTrusted = true,
                    sourceStillAvailable =
                        true,
                    currentSourceIdentity =
                        fingerprint('c'),
                    currentTotalBytes = 8L,
                    currentChunkRootHashHex =
                        tree.rootHashHex,
                    currentWholeFileSha256Hex =
                        whole
                )
            assertTrue(
                ready is
                    ResumeSenderRevalidationResult.Ready
            )
            assertEquals(
                "content://source/7",
                (ready as
                    ResumeSenderRevalidationResult.Ready)
                    .sourceHandle
            )
        }

    @Test
    fun expiredJournal_isPurgedAtResumeBoundary() =
        runTest(testDispatcher) {
            val chunks =
                chunks("AAAA")
            val tree =
                initReceiver(
                    "expired-session",
                    "expired-item",
                    chunks,
                    nowMs = 100L
                )

            val result =
                store.reconcileReceiverForResume(
                    "expired-session",
                    "expired-item",
                    fingerprint('a'),
                    peerStillTrusted = true,
                    nowMs =
                        100L +
                            dev.glide.core.resume
                                .ResumeToken
                                .MAX_TTL_MILLIS
                )

            assertTrue(
                result is
                    ResumeReceiverRevalidationResult.Expired
            )
            assertNull(
                fakeDao.get(
                    "expired-session",
                    "expired-item"
                )
            )
            assertEquals(
                64,
                tree.rootHashHex.length
            )
        }

    @Test
    fun finalAssembly_requiresCompletePrefixMatchingRootAndWholeDigest() =
        runTest(testDispatcher) {
            val chunks =
                chunks("AAAA", "BBBB")
            initReceiver(
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
                store.recordReceiverChunk(
                    "assemble-session",
                    "assemble-item",
                    0L,
                    chunks[0]
                )
            )
            assertFalse(
                store
                    .assembleVerifiedReceiverFile(
                        "assemble-session",
                        "assemble-item",
                        wholeDigest(chunks),
                        destination
                    )
            )
            assertFalse(
                destination.exists()
            )

            assertTrue(
                store.recordReceiverChunk(
                    "assemble-session",
                    "assemble-item",
                    1L,
                    chunks[1]
                )
            )
            assertTrue(
                store
                    .assembleVerifiedReceiverFile(
                        "assemble-session",
                        "assemble-item",
                        wholeDigest(chunks),
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
            assertNull(
                store.getJournal(
                    "assemble-session",
                    "assemble-item"
                )
            )
        }

    @Test
    fun finalAssembly_rejectsPrefixWhoseComputedRootDoesNotMatchOfferRoot() =
        runTest(testDispatcher) {
            val expectedChunks =
                chunks("AAAA", "BBBB")
            val actualChunks =
                chunks("AAAA", "XXXX")
            initReceiver(
                "root-session",
                "root-item",
                expectedChunks
            )
            assertTrue(
                store.recordReceiverChunk(
                    "root-session",
                    "root-item",
                    0L,
                    actualChunks[0]
                )
            )
            assertTrue(
                store.recordReceiverChunk(
                    "root-session",
                    "root-item",
                    1L,
                    actualChunks[1]
                )
            )

            val destination =
                File(
                    tempFolder.root,
                    "root-mismatch.bin"
                )
            assertFalse(
                store
                    .assembleVerifiedReceiverFile(
                        "root-session",
                        "root-item",
                        wholeDigest(
                            actualChunks
                        ),
                        destination
                    )
            )
            assertFalse(
                destination.exists()
            )
        }

    @Test
    fun storagePressure_evictsOldestRetainedItemFirst() =
        runTest(testDispatcher) {
            val chunks =
                chunks("AAAA")
            initReceiver(
                "old-session",
                "old-item",
                chunks,
                nowMs = 1_000L
            )
            initReceiver(
                "new-session",
                "new-item",
                chunks,
                nowMs = 2_000L
            )

            var call = 0
            val evicted =
                store.evictUnderStoragePressure(
                    minFreeBytes =
                        1_000L
                ) {
                    call++
                    if (call == 1) {
                        500L
                    } else {
                        1_500L
                    }
                }

            assertEquals(
                1,
                evicted
            )
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

    private suspend fun initReceiver(
        session: String,
        item: String,
        chunks: List<ByteArray>,
        nowMs: Long = 1_000L
    ): ContentDigestTree {
        val tree =
            digestTree(chunks)
        store.initReceiverSession(
            originalSessionId = session,
            itemId = item,
            itemName = "$item.bin",
            peerFingerprintHex =
                fingerprint('a'),
            totalBytes =
                chunks.sumOf {
                    it.size.toLong()
                },
            chunkSize =
                chunks.first().size,
            chunkRootHashHex =
                tree.rootHashHex,
            nowMs = nowMs
        )
        return tree
    }

    private fun chunks(
        vararg values: String
    ): List<ByteArray> =
        values.map {
            it.toByteArray()
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

        override suspend fun insertIfAbsent(
            session: ResumableSessionEntity
        ): Long {
            val key =
                session.originalSessionId to
                    session.itemId
            if (sessions.containsKey(key)) {
                return -1L
            }
            sessions[key] = session
            return 1L
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
            newVerifiedChunkHashesHex: String,
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
                    verifiedChunkHashesHex =
                        newVerifiedChunkHashesHex,
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
            verifiedChunkHashesHex: String,
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
                    verifiedChunkHashesHex =
                        verifiedChunkHashesHex,
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
