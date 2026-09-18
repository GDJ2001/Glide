package dev.glide.data.store

import dev.glide.core.resume.ContentDigestTree
import dev.glide.core.resume.ResumeToken
import dev.glide.data.db.ResumableSessionDao
import dev.glide.data.db.ResumableSessionEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.min

/**
 * Sandboxed durable receiver-side storage for HARD-022 true resume.
 *
 * Invariants:
 * 1. Journal authority is keyed by original transfer + item and bound to authenticated peer/source.
 * 2. Only exactly the next verified chunk may advance the contiguous prefix.
 * 3. Retained chunks are re-hashed after restart before reuse.
 * 4. Expired/revoked/source-changed state cannot authorize resume.
 * 5. Assembly remains in private staging until the complete SHA-256 is verified.
 */
class ResumableStagingStore(
    private val stagingBaseDir: File,
    private val resumableSessionDao: ResumableSessionDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    init {
        if (!stagingBaseDir.exists() &&
            !stagingBaseDir.mkdirs()
        ) {
            throw IllegalStateException(
                "Unable to create resumable staging directory"
            )
        }
    }

    suspend fun initSession(
        originalSessionId: String,
        itemId: String,
        itemName: String,
        peerFingerprintHex: String,
        sourceIdentity: String,
        totalBytes: Long,
        chunkSize: Int,
        digestTree: ContentDigestTree,
        wholeFileSha256Hex: String,
        nowMs: Long = System.currentTimeMillis()
    ): File = withContext(ioDispatcher) {
        validateMetadata(
            originalSessionId = originalSessionId,
            itemId = itemId,
            itemName = itemName,
            peerFingerprintHex = peerFingerprintHex,
            sourceIdentity = sourceIdentity,
            totalBytes = totalBytes,
            chunkSize = chunkSize,
            digestTree = digestTree,
            wholeFileSha256Hex = wholeFileSha256Hex
        )

        val existing =
            resumableSessionDao.get(
                originalSessionId,
                itemId
            )
        if (existing != null) {
            if (nowMs >=
                existing.expiresAtMs
            ) {
                purgeInternal(existing)
            } else {
                requireSameAuthority(
                    existing = existing,
                    peerFingerprintHex =
                        peerFingerprintHex,
                    sourceIdentity =
                        sourceIdentity,
                    totalBytes =
                        totalBytes,
                    chunkSize =
                        chunkSize,
                    chunkRootHashHex =
                        digestTree.rootHashHex,
                    chunkHashesHex =
                        encodeChunkHashes(
                            digestTree
                        ),
                    wholeFileSha256Hex =
                        wholeFileSha256Hex
                )
                return@withContext File(
                    existing.stagingPath
                )
            }
        }

        val itemDir =
            itemDirectory(
                originalSessionId,
                itemId
            )
        if (!itemDir.exists() &&
            !itemDir.mkdirs()
        ) {
            throw IllegalStateException(
                "Unable to create private resume item directory"
            )
        }

        val entity =
            ResumableSessionEntity(
                originalSessionId =
                    originalSessionId,
                itemId = itemId,
                itemName = itemName,
                peerFingerprintHex =
                    peerFingerprintHex,
                sourceIdentity =
                    sourceIdentity,
                totalBytes = totalBytes,
                chunkSize = chunkSize,
                verifiedChunks = 0L,
                contiguousByteOffset = 0L,
                chunkRootHashHex =
                    digestTree.rootHashHex,
                chunkHashesHex =
                    encodeChunkHashes(
                        digestTree
                    ),
                wholeFileSha256Hex =
                    wholeFileSha256Hex,
                createdAtMs = nowMs,
                updatedAtMs = nowMs,
                expiresAtMs =
                    nowMs +
                        ResumeToken.MAX_TTL_MILLIS,
                stagingPath =
                    itemDir.absolutePath
            )
        val inserted =
            resumableSessionDao
                .insertIfAbsent(
                    entity
                )
        if (inserted == -1L) {
            val raced =
                resumableSessionDao.get(
                    originalSessionId,
                    itemId
                )
                    ?: throw IllegalStateException(
                        "Resume journal conflicted but no persisted row is available"
                    )
            requireSameAuthority(
                existing = raced,
                peerFingerprintHex =
                    peerFingerprintHex,
                sourceIdentity =
                    sourceIdentity,
                totalBytes =
                    totalBytes,
                chunkSize =
                    chunkSize,
                chunkRootHashHex =
                    digestTree.rootHashHex,
                chunkHashesHex =
                    entity.chunkHashesHex,
                wholeFileSha256Hex =
                    wholeFileSha256Hex
            )
            return@withContext File(
                raced.stagingPath
            )
        }

        itemDir
    }

    suspend fun getJournal(
        originalSessionId: String,
        itemId: String
    ): ResumableSessionEntity? =
        withContext(ioDispatcher) {
            resumableSessionDao.get(
                originalSessionId,
                itemId
            )
        }

    suspend fun getVerifiedChunkCount(
        originalSessionId: String,
        itemId: String
    ): Long =
        withContext(ioDispatcher) {
            resumableSessionDao.get(
                originalSessionId,
                itemId
            )?.verifiedChunks ?: 0L
        }

    suspend fun writeAndVerifyChunk(
        originalSessionId: String,
        itemId: String,
        chunkIndex: Long,
        chunkData: ByteArray,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean = withContext(ioDispatcher) {
        val journal =
            resumableSessionDao.get(
                originalSessionId,
                itemId
            ) ?: return@withContext false

        if (nowMs >= journal.expiresAtMs ||
            chunkIndex !=
                journal.verifiedChunks
        ) {
            return@withContext false
        }

        val chunkHashes =
            decodeChunkHashes(
                journal.chunkHashesHex
            )
        if (chunkIndex !in
            0 until chunkHashes.size.toLong()
        ) {
            return@withContext false
        }

        val expectedLength =
            expectedChunkLength(
                journal,
                chunkIndex
            )
        if (chunkData.size !=
            expectedLength
        ) {
            return@withContext false
        }

        val actualDigest =
            sha256Hex(chunkData)
        if (!actualDigest.equals(
                chunkHashes[
                    chunkIndex.toInt()
                ],
                ignoreCase = false
            )
        ) {
            return@withContext false
        }

        val itemDir =
            File(journal.stagingPath)
        if (!itemDir.exists() &&
            !itemDir.mkdirs()
        ) {
            return@withContext false
        }

        val chunkFile =
            chunkFile(
                itemDir,
                chunkIndex
            )
        val tempFile =
            File(
                itemDir,
                chunkFile.name + ".tmp-" +
                    System.nanoTime()
            )

        try {
            FileOutputStream(
                tempFile
            ).use { out ->
                out.write(chunkData)
                out.fd.sync()
            }
            atomicReplace(
                tempFile,
                chunkFile
            )

            val newOffset =
                min(
                    journal.totalBytes,
                    journal
                        .contiguousByteOffset +
                        chunkData.size.toLong()
                )
            val advanced =
                resumableSessionDao
                    .compareAndAdvance(
                        originalSessionId =
                            originalSessionId,
                        itemId = itemId,
                        expectedVerifiedChunks =
                            journal.verifiedChunks,
                        expectedContiguousByteOffset =
                            journal
                                .contiguousByteOffset,
                        newVerifiedChunks =
                            journal.verifiedChunks +
                                1,
                        newContiguousByteOffset =
                            newOffset,
                        updatedAtMs = nowMs
                    )

            advanced == 1
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    /**
     * Revalidates resume authority plus every retained chunk after process death.
     * Corrupt/missing retained data rewinds to the first invalid chunk and deletes the stale suffix.
     */
    suspend fun reconcileForResume(
        originalSessionId: String,
        itemId: String,
        authenticatedPeerFingerprintHex: String,
        peerStillTrusted: Boolean,
        sourceStillAvailable: Boolean,
        currentSourceIdentity: String,
        currentTotalBytes: Long,
        currentChunkRootHashHex: String,
        nowMs: Long = System.currentTimeMillis()
    ): ResumeRevalidationResult =
        withContext(ioDispatcher) {
            val journal =
                resumableSessionDao.get(
                    originalSessionId,
                    itemId
                )
                    ?: return@withContext
                        ResumeRevalidationResult
                            .NotFound

            if (nowMs >=
                journal.expiresAtMs
            ) {
                purgeInternal(journal)
                return@withContext
                    ResumeRevalidationResult
                        .Expired
            }

            if (!peerStillTrusted ||
                authenticatedPeerFingerprintHex !=
                    journal.peerFingerprintHex
            ) {
                return@withContext
                    ResumeRevalidationResult
                        .Unauthorized
            }

            if (!sourceStillAvailable) {
                return@withContext
                    ResumeRevalidationResult
                        .SourceUnavailable
            }

            if (currentSourceIdentity !=
                    journal.sourceIdentity ||
                currentTotalBytes !=
                    journal.totalBytes ||
                currentChunkRootHashHex !=
                    journal.chunkRootHashHex
            ) {
                return@withContext
                    ResumeRevalidationResult
                        .SourceChanged
            }

            val chunkHashes =
                decodeChunkHashes(
                    journal.chunkHashesHex
                )
            var validChunks = 0L
            var validOffset = 0L
            val itemDir =
                File(journal.stagingPath)

            while (validChunks <
                journal.verifiedChunks
            ) {
                val file =
                    chunkFile(
                        itemDir,
                        validChunks
                    )
                val expectedLength =
                    expectedChunkLength(
                        journal,
                        validChunks
                    )
                if (!file.isFile ||
                    file.length() !=
                        expectedLength.toLong() ||
                    sha256Hex(file) !=
                        chunkHashes[
                            validChunks.toInt()
                        ]
                ) {
                    break
                }

                validOffset +=
                    expectedLength.toLong()
                validChunks++
            }

            if (validChunks <
                journal.verifiedChunks
            ) {
                deleteChunkSuffix(
                    itemDir,
                    validChunks
                )
                resumableSessionDao
                    .rewindProgress(
                        originalSessionId =
                            originalSessionId,
                        itemId = itemId,
                        verifiedChunks =
                            validChunks,
                        contiguousByteOffset =
                            validOffset,
                        updatedAtMs = nowMs
                    )
            }

            ResumeRevalidationResult.Ready(
                verifiedChunks =
                    validChunks,
                contiguousByteOffset =
                    validOffset,
                totalBytes =
                    journal.totalBytes,
                chunkSize =
                    journal.chunkSize,
                chunkRootHashHex =
                    journal.chunkRootHashHex,
                wholeFileSha256Hex =
                    journal.wholeFileSha256Hex
            )
        }

    /**
     * Assembles only a complete contiguous prefix, verifies the whole file in private staging,
     * and only then atomically promotes it to the requested destination.
     */
    suspend fun assembleVerifiedFile(
        originalSessionId: String,
        itemId: String,
        destinationFile: File
    ): Boolean = withContext(ioDispatcher) {
        val journal =
            resumableSessionDao.get(
                originalSessionId,
                itemId
            ) ?: return@withContext false

        val chunkHashes =
            decodeChunkHashes(
                journal.chunkHashesHex
            )
        if (journal.verifiedChunks !=
                chunkHashes.size.toLong() ||
            journal.contiguousByteOffset !=
                journal.totalBytes
        ) {
            return@withContext false
        }

        val itemDir =
            File(journal.stagingPath)
        if (!itemDir.isDirectory) {
            return@withContext false
        }

        val assembled =
            File(
                itemDir,
                "assembled.verify"
            )
        if (assembled.exists()) {
            assembled.delete()
        }

        val wholeDigest =
            MessageDigest.getInstance(
                "SHA-256"
            )

        try {
            FileOutputStream(
                assembled
            ).use { out ->
                for (index in
                    chunkHashes.indices
                ) {
                    val chunk =
                        chunkFile(
                            itemDir,
                            index.toLong()
                        )
                    if (!chunk.isFile ||
                        chunk.length() !=
                            expectedChunkLength(
                                journal,
                                index.toLong()
                            ).toLong()
                    ) {
                        return@withContext false
                    }

                    val digest =
                        MessageDigest.getInstance(
                            "SHA-256"
                        )
                    FileInputStream(
                        chunk
                    ).use { input ->
                        val buffer =
                            ByteArray(64 * 1024)
                        while (true) {
                            val read =
                                input.read(buffer)
                            if (read < 0) {
                                break
                            }
                            if (read == 0) {
                                continue
                            }
                            digest.update(
                                buffer,
                                0,
                                read
                            )
                            wholeDigest.update(
                                buffer,
                                0,
                                read
                            )
                            out.write(
                                buffer,
                                0,
                                read
                            )
                        }
                    }

                    if (toHex(
                            digest.digest()
                        ) !=
                        chunkHashes[index]
                    ) {
                        return@withContext false
                    }
                }
                out.fd.sync()
            }

            if (assembled.length() !=
                    journal.totalBytes ||
                toHex(
                    wholeDigest.digest()
                ) !=
                    journal.wholeFileSha256Hex
            ) {
                return@withContext false
            }

            destinationFile.parentFile
                ?.let { parent ->
                    if (!parent.exists() &&
                        !parent.mkdirs()
                    ) {
                        return@withContext false
                    }
                }

            val promoted =
                promoteVerifiedFile(
                    source = assembled,
                    destination =
                        destinationFile,
                    expectedSize =
                        journal.totalBytes,
                    expectedSha256Hex =
                        journal
                            .wholeFileSha256Hex
                )
            if (promoted) {
                purgeInternal(
                    journal
                )
            }
            promoted
        } finally {
            if (assembled.exists()) {
                assembled.delete()
            }
        }
    }

    suspend fun purgeSession(
        originalSessionId: String,
        itemId: String
    ) = withContext(ioDispatcher) {
        resumableSessionDao.get(
            originalSessionId,
            itemId
        )?.let {
            purgeInternal(it)
        }
    }

    suspend fun purgeOriginalSession(
        originalSessionId: String
    ): Int = withContext(ioDispatcher) {
        val matching =
            resumableSessionDao
                .getAllResumableSessions()
                .filter {
                    it.originalSessionId ==
                        originalSessionId
                }
        matching.forEach {
            purgeInternal(it)
        }
        matching.size
    }

    suspend fun purgeForPeer(
        peerFingerprintHex: String
    ): Int = withContext(ioDispatcher) {
        val matching =
            resumableSessionDao
                .getAllResumableSessions()
                .filter {
                    it.peerFingerprintHex ==
                        peerFingerprintHex
                }
        matching.forEach {
            purgeInternal(it)
        }
        matching.size
    }

    suspend fun purgeAll(): Int =
        withContext(ioDispatcher) {
            val all =
                resumableSessionDao
                    .getAllResumableSessions()
            all.forEach {
                purgeInternal(it)
            }
            all.size
        }

    suspend fun purgeExpiredSessions(
        currentMs: Long =
            System.currentTimeMillis()
    ): Int = withContext(ioDispatcher) {
        val expired =
            resumableSessionDao
                .getExpiredSessions(
                    currentMs
                )
        expired.forEach {
            val path =
                File(it.stagingPath)
            if (isInsideStagingRoot(path)) {
                path.deleteRecursively()
            }
        }
        resumableSessionDao
            .deleteExpired(
                currentMs
            )
    }

    suspend fun evictUnderStoragePressure(
        minFreeBytes: Long,
        checkFreeSpace: () -> Long = {
            stagingBaseDir.usableSpace
        }
    ): Int = withContext(ioDispatcher) {
        require(minFreeBytes >= 0) {
            "Minimum free bytes cannot be negative"
        }

        if (checkFreeSpace() >=
            minFreeBytes
        ) {
            return@withContext 0
        }

        var evicted = 0
        val oldestFirst =
            resumableSessionDao
                .getAllResumableSessions()
                .sortedWith(
                    compareBy<
                        ResumableSessionEntity
                    > {
                        it.updatedAtMs
                    }.thenBy {
                        it.createdAtMs
                    }
                )

        for (journal in
            oldestFirst
        ) {
            purgeInternal(
                journal
            )
            evicted++
            if (checkFreeSpace() >=
                minFreeBytes
            ) {
                break
            }
        }

        evicted
    }

    private suspend fun purgeInternal(
        journal: ResumableSessionEntity
    ) {
        val path =
            File(journal.stagingPath)
        if (isInsideStagingRoot(path)) {
            path.deleteRecursively()
        }
        resumableSessionDao.delete(
            journal.originalSessionId,
            journal.itemId
        )
    }

    private fun validateMetadata(
        originalSessionId: String,
        itemId: String,
        itemName: String,
        peerFingerprintHex: String,
        sourceIdentity: String,
        totalBytes: Long,
        chunkSize: Int,
        digestTree: ContentDigestTree,
        wholeFileSha256Hex: String
    ) {
        require(
            originalSessionId.isNotBlank()
        ) {
            "Original session id cannot be blank"
        }
        require(itemId.isNotBlank()) {
            "Item id cannot be blank"
        }
        require(itemName.isNotBlank()) {
            "Item name cannot be blank"
        }
        require(
            LOWER_HEX_64.matches(
                peerFingerprintHex
            )
        ) {
            "Peer fingerprint must be canonical lowercase SHA-256"
        }
        require(
            sourceIdentity.isNotBlank() &&
                sourceIdentity.length <=
                    MAX_SOURCE_IDENTITY_CHARS
        ) {
            "Source identity is missing or too large"
        }
        require(totalBytes > 0) {
            "Resumable item size must be positive"
        }
        require(
            chunkSize in
                1..MAX_CHUNK_SIZE_BYTES
        ) {
            "Chunk size is outside the resume profile"
        }
        require(
            LOWER_HEX_64.matches(
                digestTree.rootHashHex
            )
        ) {
            "Chunk root must be canonical lowercase SHA-256"
        }
        require(
            LOWER_HEX_64.matches(
                wholeFileSha256Hex
            )
        ) {
            "Whole-file digest must be canonical lowercase SHA-256"
        }

        val expectedChunks =
            (totalBytes +
                chunkSize - 1L) /
                chunkSize
        require(
            expectedChunks <=
                Int.MAX_VALUE.toLong()
        ) {
            "Chunk manifest is too large"
        }
        require(
            digestTree.chunkHashes.size
                .toLong() ==
                expectedChunks
        ) {
            "Chunk digest count does not match item size/chunk size"
        }
        require(
            digestTree.chunkHashes.all {
                it.size == 32
            }
        ) {
            "Each chunk digest must be SHA-256"
        }
    }

    private fun requireSameAuthority(
        existing: ResumableSessionEntity,
        peerFingerprintHex: String,
        sourceIdentity: String,
        totalBytes: Long,
        chunkSize: Int,
        chunkRootHashHex: String,
        chunkHashesHex: String,
        wholeFileSha256Hex: String
    ) {
        require(
            existing.peerFingerprintHex ==
                peerFingerprintHex &&
                existing.sourceIdentity ==
                    sourceIdentity &&
                existing.totalBytes ==
                    totalBytes &&
                existing.chunkSize ==
                    chunkSize &&
                existing.chunkRootHashHex ==
                    chunkRootHashHex &&
                existing.chunkHashesHex ==
                    chunkHashesHex &&
                existing.wholeFileSha256Hex ==
                    wholeFileSha256Hex
        ) {
            "Existing resume journal authority does not match this item"
        }
    }

    private fun expectedChunkLength(
        journal: ResumableSessionEntity,
        chunkIndex: Long
    ): Int {
        val chunkCount =
            decodeChunkHashes(
                journal.chunkHashesHex
            ).size.toLong()
        require(
            chunkIndex in
                0 until chunkCount
        ) {
            "Chunk index outside manifest"
        }

        return if (chunkIndex ==
            chunkCount - 1
        ) {
            val prior =
                chunkIndex *
                    journal.chunkSize.toLong()
            (journal.totalBytes -
                prior).toInt()
        } else {
            journal.chunkSize
        }
    }

    private fun deleteChunkSuffix(
        itemDir: File,
        startChunkIndex: Long
    ) {
        itemDir.listFiles()
            ?.filter { file ->
                file.name.startsWith(
                    "chunk_"
                ) &&
                    file.name.endsWith(
                        ".part"
                    )
            }
            ?.forEach { file ->
                val index =
                    file.name
                        .removePrefix(
                            "chunk_"
                        )
                        .removeSuffix(
                            ".part"
                        )
                        .toLongOrNull()
                if (index != null &&
                    index >=
                        startChunkIndex
                ) {
                    file.delete()
                }
            }
    }

    private fun isInsideStagingRoot(
        candidate: File
    ): Boolean {
        val root =
            stagingBaseDir
                .canonicalFile
                .toPath()
        val path =
            candidate
                .canonicalFile
                .toPath()
        return path.startsWith(root) &&
            path != root
    }

    private fun itemDirectory(
        originalSessionId: String,
        itemId: String
    ): File {
        val composite =
            sha256Hex(
                (
                    originalSessionId +
                        "\u0000" +
                        itemId
                    ).toByteArray(
                        Charsets.UTF_8
                    )
            )
        return File(
            stagingBaseDir,
            composite
        )
    }

    private fun chunkFile(
        itemDir: File,
        chunkIndex: Long
    ): File =
        File(
            itemDir,
            "chunk_%012d.part"
                .format(
                    chunkIndex
                )
        )

    private fun encodeChunkHashes(
        digestTree: ContentDigestTree
    ): String =
        digestTree.chunkHashes
            .joinToString(",") {
                toHex(it)
            }

    private fun decodeChunkHashes(
        value: String
    ): List<String> {
        val hashes =
            value.split(',')
        require(
            hashes.isNotEmpty() &&
                hashes.all {
                    LOWER_HEX_64
                        .matches(it)
                }
        ) {
            "Stored chunk digest manifest is invalid"
        }
        return hashes
    }

    private fun sha256Hex(
        value: ByteArray
    ): String =
        toHex(
            MessageDigest
                .getInstance(
                    "SHA-256"
                )
                .digest(value)
        )

    private fun sha256Hex(
        file: File
    ): String {
        val digest =
            MessageDigest.getInstance(
                "SHA-256"
            )
        FileInputStream(file)
            .use { input ->
                val buffer =
                    ByteArray(64 * 1024)
                while (true) {
                    val read =
                        input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    if (read > 0) {
                        digest.update(
                            buffer,
                            0,
                            read
                        )
                    }
                }
            }
        return toHex(
            digest.digest()
        )
    }

    private fun toHex(
        value: ByteArray
    ): String =
        value.joinToString("") {
            "%02x".format(it)
        }

    private fun promoteVerifiedFile(
        source: File,
        destination: File,
        expectedSize: Long,
        expectedSha256Hex: String
    ): Boolean {
        try {
            atomicReplace(
                source,
                destination
            )
            val valid =
                destination.length() ==
                    expectedSize &&
                    sha256Hex(destination) ==
                        expectedSha256Hex
            if (!valid) {
                destination.delete()
            }
            return valid
        } catch (_: java.io.IOException) {
            // Private staging and the final destination may be on different filesystems.
        }

        val parent =
            destination.parentFile
                ?: return false
        if (!parent.exists() &&
            !parent.mkdirs()
        ) {
            return false
        }

        val destinationTemp =
            File(
                parent,
                "." +
                    destination.name +
                    ".glide-verify-" +
                    System.nanoTime()
            )

        try {
            val digest =
                MessageDigest.getInstance(
                    "SHA-256"
                )
            FileInputStream(source).use {
                input ->
                FileOutputStream(
                    destinationTemp
                ).use { output ->
                    val buffer =
                        ByteArray(
                            64 * 1024
                        )
                    while (true) {
                        val read =
                            input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        if (read == 0) {
                            continue
                        }
                        digest.update(
                            buffer,
                            0,
                            read
                        )
                        output.write(
                            buffer,
                            0,
                            read
                        )
                    }
                    output.fd.sync()
                }
            }

            if (destinationTemp.length() !=
                    expectedSize ||
                toHex(
                    digest.digest()
                ) !=
                    expectedSha256Hex
            ) {
                return false
            }

            atomicReplace(
                destinationTemp,
                destination
            )
            return true
        } finally {
            if (destinationTemp.exists()) {
                destinationTemp.delete()
            }
        }
    }

    private fun atomicReplace(
        source: File,
        destination: File
    ) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption
                    .ATOMIC_MOVE,
                StandardCopyOption
                    .REPLACE_EXISTING
            )
        } catch (
            _: AtomicMoveNotSupportedException
        ) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption
                    .REPLACE_EXISTING
            )
        }
    }

    companion object {
        private val LOWER_HEX_64 =
            Regex(
                "^[0-9a-f]{64}$"
            )
        private const val
            MAX_SOURCE_IDENTITY_CHARS =
            2048
        private const val
            MAX_CHUNK_SIZE_BYTES =
            16 * 1024 * 1024
    }
}

sealed interface ResumeRevalidationResult {
    data class Ready(
        val verifiedChunks: Long,
        val contiguousByteOffset: Long,
        val totalBytes: Long,
        val chunkSize: Int,
        val chunkRootHashHex: String,
        val wholeFileSha256Hex: String
    ) : ResumeRevalidationResult

    data object NotFound :
        ResumeRevalidationResult

    data object Expired :
        ResumeRevalidationResult

    data object Unauthorized :
        ResumeRevalidationResult

    data object SourceUnavailable :
        ResumeRevalidationResult

    data object SourceChanged :
        ResumeRevalidationResult
}
