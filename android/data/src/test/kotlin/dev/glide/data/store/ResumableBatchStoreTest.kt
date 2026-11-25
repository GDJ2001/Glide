package dev.glide.data.store

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.resume.ResumeBatchManifest
import dev.glide.core.resume.ResumeToken
import dev.glide.data.db.ResumableBatchDao
import dev.glide.data.db.ResumableBatchEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class ResumableBatchStoreTest {
    @Test
    fun verifiedReceiptsSurviveStoreRecreationAndCannotReopenAfterExpiry() = runTest {
        val dao = FakeBatchDao()
        var clock = 1000L
        val manifest = ResumeBatchManifest(
            SessionId(uuid()), BatchId(uuid()), digest('a'), digest('b'),
            listOf(
                ResumeBatchManifest.Item(ItemId(uuid()), "empty.txt", null, 0, 0, null, digest('c')),
                ResumeBatchManifest.Item(ItemId(uuid()), "data.bin", "in/data.bin", 500, 1024, digest('d'), digest('e'))
            )
        )
        val first = manifest.items.first()
        val initial = ResumableBatchStore(dao) { clock }
        initial.saveAccepted(manifest, DeviceRole.Receiver)
        initial.recordVerifiedCommit(manifest.sessionId, first.id, first.sizeBytes, first.digest)
        initial.saveAccepted(manifest, DeviceRole.Receiver)

        val reopened = ResumableBatchStore(dao) { clock }
        val saved = reopened.findAccepted(manifest.sessionId, DeviceRole.Receiver, manifest.senderFingerprint, manifest.digest())
        assertEquals(1, saved?.verifiedCount)
        assertEquals(manifest, saved?.manifest)
        assertNull(reopened.findAccepted(manifest.sessionId, DeviceRole.Receiver, manifest.receiverFingerprint, manifest.digest()))
        assertNull(reopened.findAccepted(manifest.sessionId, DeviceRole.Receiver, manifest.senderFingerprint, digest('f')))
        clock += ResumeToken.MAX_TTL_MILLIS
        assertNull(reopened.findAccepted(manifest.sessionId, DeviceRole.Receiver, manifest.senderFingerprint, manifest.digest()))
    }

    private fun uuid() = UUID.randomUUID().toString()
    private fun digest(value: Char) = Sha256Digest(value.toString().repeat(64))

    private class FakeBatchDao : ResumableBatchDao {
        private val rows = mutableMapOf<String, ResumableBatchEntity>()

        override suspend fun insert(batch: ResumableBatchEntity): Long =
            if (rows.putIfAbsent(batch.originalSessionId, batch) == null) 1 else -1

        override suspend fun findActive(sessionId: String, nowMs: Long): ResumableBatchEntity? =
            rows[sessionId]?.takeIf { it.expiresAtMs > nowMs }

        override suspend fun listActive(nowMs: Long): List<ResumableBatchEntity> =
            rows.values.filter { it.expiresAtMs > nowMs }.sortedByDescending { it.expiresAtMs }

        override suspend fun updateProgress(sessionId: String, revision: Long, nowMs: Long, progressJson: String): Int {
            val row = findActive(sessionId, nowMs)?.takeIf { it.revision == revision } ?: return 0
            rows[sessionId] = row.copy(progressJson = progressJson, revision = revision + 1)
            return 1
        }

        override suspend fun purgeSession(sessionId: String): Int =
            if (rows.remove(sessionId) == null) 0 else 1

        override suspend fun purgePeer(peerFingerprint: String): Int =
            removeMatching { it.peerFingerprint == peerFingerprint }

        override suspend fun purgeExpired(nowMs: Long): Int =
            removeMatching { it.expiresAtMs <= nowMs }

        override suspend fun purgeAll(): Int = removeMatching { true }

        private fun removeMatching(predicate: (ResumableBatchEntity) -> Boolean): Int {
            val before = rows.size
            rows.values.removeAll(predicate)
            return before - rows.size
        }
    }
}
