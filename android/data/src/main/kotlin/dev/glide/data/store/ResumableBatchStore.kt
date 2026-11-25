package dev.glide.data.store

import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.resume.ResumeBatchManifest
import dev.glide.core.resume.ResumeToken
import dev.glide.data.db.ResumableBatchDao
import dev.glide.data.db.ResumableBatchEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Durable consent and verified-item receipts. Byte offsets remain in the per-item journal. */
class ResumableBatchStore(
    private val dao: ResumableBatchDao,
    private val now: () -> Long = System::currentTimeMillis
) {
    data class Accepted(
        val manifest: ResumeBatchManifest,
        val role: DeviceRole,
        val verifiedCount: Int,
        val expiresAtMs: Long
    )

    suspend fun saveAccepted(manifest: ResumeBatchManifest, role: DeviceRole) {
        val digest = manifest.digest().value
        val timestamp = now()
        val entity = ResumableBatchEntity(
            originalSessionId = manifest.sessionId.value,
            journalRole = role.name,
            peerFingerprint = peer(manifest, role).value,
            manifestSha256 = digest,
            manifestJson = Json.encodeToString(manifest),
            progressJson = "0",
            revision = 0,
            expiresAtMs = Math.addExact(timestamp, ResumeToken.MAX_TTL_MILLIS)
        )
        if (dao.insert(entity) == -1L) {
            val existing = dao.findActive(entity.originalSessionId, timestamp)
            check(existing != null && existing.manifestSha256 == digest &&
                existing.journalRole == entity.journalRole &&
                existing.peerFingerprint == entity.peerFingerprint
            ) { "Conflicting accepted resume batch" }
        }
    }

    suspend fun findAccepted(
        sessionId: SessionId,
        role: DeviceRole,
        peerFingerprint: Sha256Digest,
        manifestDigest: Sha256Digest
    ): Accepted? {
        val entry = dao.findActive(sessionId.value, now()) ?: return null
        if (entry.journalRole != role.name || entry.peerFingerprint != peerFingerprint.value ||
            entry.manifestSha256 != manifestDigest.value
        ) return null
        return decode(entry)
    }

    suspend fun recordVerifiedCommit(
        sessionId: SessionId,
        itemId: ItemId,
        sizeBytes: Long,
        digest: Sha256Digest
    ) {
        repeat(4) {
            val timestamp = now()
            val entry = dao.findActive(sessionId.value, timestamp) ?: return
            val accepted = decode(entry)
            check(accepted.role == DeviceRole.Receiver)
            val verified = accepted.manifest.recordVerifiedItem(accepted.verifiedCount, itemId, sizeBytes, digest)
            if (verified == accepted.verifiedCount) return
            if (dao.updateProgress(sessionId.value, entry.revision, timestamp, verified.toString()) == 1) return
        }
        error("Accepted resume batch changed during commit")
    }

    private fun decode(entry: ResumableBatchEntity): Accepted {
        val manifest = Json.decodeFromString<ResumeBatchManifest>(entry.manifestJson)
        val role = DeviceRole.valueOf(entry.journalRole)
        val count = entry.progressJson.toInt()
        check(manifest.sessionId.value == entry.originalSessionId &&
            manifest.digest().value == entry.manifestSha256 &&
            peer(manifest, role).value == entry.peerFingerprint &&
            entry.revision == count.toLong() && count in 0..manifest.items.size
        ) { "Invalid accepted resume batch record" }
        return Accepted(manifest, role, count, entry.expiresAtMs)
    }

    private fun peer(manifest: ResumeBatchManifest, role: DeviceRole): Sha256Digest =
        if (role == DeviceRole.Sender) manifest.receiverFingerprint else manifest.senderFingerprint
}
