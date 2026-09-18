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
 * Durable HARD-022 true-resume authority for Android.
 *
 * Sender rows retain source identity + source handle + preflight content authority.
 * Receiver rows retain only private chunks and the locally verified contiguous-prefix digests.
 *
 * The receiver does not need the sender's full chunk manifest before interruption. On reconnect,
 * the receiver proves its retained offset/root in the signed resume transcript while the sender
 * freshly re-hashes the persisted source and verifies that the root still matches.
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

    suspend fun initReceiverSession(
        originalSessionId: String,
        itemId: String,
        itemName: String,
        mimeType: String =
            "application/octet-stream",
        relativePath: String? = null,
        peerFingerprintHex: String,
        totalBytes: Long,
        chunkSize: Int,
        chunkRootHashHex: String,
        nowMs: Long = System.currentTimeMillis()
    ): File = withContext(ioDispatcher) {
        validateCommonMetadata(
            originalSessionId = originalSessionId,
            itemId = itemId,
            itemName = itemName,
            mimeType = mimeType,
            relativePath = relativePath,
            peerFingerprintHex = peerFingerprintHex,
            totalBytes = totalBytes,
            chunkSize = chunkSize,
            chunkRootHashHex = chunkRootHashHex
        )

        val existing = activeExistingOrNull(
            originalSessionId,
            itemId,
            nowMs
        )
        if (existing != null) {
            require(
                existing.itemName ==
                    itemName &&
                    existing.mimeType ==
                    mimeType &&
                    existing.relativePath ==
                    relativePath
            ) {
                "Existing receiver output metadata does not match this item"
            }
            requireReceiverAuthority(
                existing,
                peerFingerprintHex,
                totalBytes,
                chunkSize,
                chunkRootHashHex
            )
            val existingPath =
                existing.stagingPath
                    ?: throw IllegalStateException(
                        "Receiver journal is missing private staging authority"
                    )
            return@withContext File(existingPath)
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
                originalSessionId = originalSessionId,
                itemId = itemId,
                journalRole =
                    ResumableSessionEntity
                        .ROLE_RECEIVER,
                itemName = itemName,
                mimeType = mimeType,
                relativePath =
                    relativePath,
                peerFingerprintHex =
                    peerFingerprintHex,
                sourceHandle = null,
                sourceIdentity = null,
                totalBytes = totalBytes,
                chunkSize = chunkSize,
                verifiedChunks = 0L,
                contiguousByteOffset = 0L,
                chunkRootHashHex =
                    chunkRootHashHex,
                verifiedChunkHashesHex = "",
                wholeFileSha256Hex = null,
                createdAtMs = nowMs,
                updatedAtMs = nowMs,
                expiresAtMs =
                    nowMs +
                        ResumeToken
                            .MAX_TTL_MILLIS,
                stagingPath =
                    itemDir.absolutePath
            )

        insertOrRequireReceiverRace(
            entity
        )
        itemDir
    }

    suspend fun initSenderSession(
        originalSessionId: String,
        itemId: String,
        itemName: String,
        mimeType: String =
            "application/octet-stream",
        relativePath: String? = null,
        peerFingerprintHex: String,
        sourceHandle: String,
        sourceIdentity: String,
        totalBytes: Long,
        chunkSize: Int,
        chunkRootHashHex: String,
        wholeFileSha256Hex: String,
        nowMs: Long = System.currentTimeMillis()
    ): ResumableSessionEntity =
        withContext(ioDispatcher) {
            validateCommonMetadata(
                originalSessionId =
                    originalSessionId,
                itemId = itemId,
                itemName = itemName,
                mimeType = mimeType,
                relativePath = relativePath,
                peerFingerprintHex =
                    peerFingerprintHex,
                totalBytes = totalBytes,
                chunkSize = chunkSize,
                chunkRootHashHex =
                    chunkRootHashHex
            )
            require(
                sourceHandle.isNotBlank() &&
                    sourceHandle.length <=
                        MAX_SOURCE_HANDLE_CHARS
            ) {
                "Source handle is missing or too large"
            }
            require(
                LOWER_HEX_64.matches(
                    sourceIdentity
                )
            ) {
                "Source identity must be canonical lowercase SHA-256"
            }
            require(
                LOWER_HEX_64.matches(
                    wholeFileSha256Hex
                )
            ) {
                "Whole-file digest must be canonical lowercase SHA-256"
            }

            val existing =
                activeExistingOrNull(
                    originalSessionId,
                    itemId,
                    nowMs
                )
            if (existing != null) {
                require(
                    existing.itemName ==
                        itemName &&
                        existing.mimeType ==
                            mimeType &&
                        existing.relativePath ==
                            relativePath
                ) {
                    "Existing sender output metadata does not match this item"
                }
                requireSenderAuthority(
                    existing =
                        existing,
                    peerFingerprintHex =
                        peerFingerprintHex,
                    sourceHandle =
                        sourceHandle,
                    sourceIdentity =
                        sourceIdentity,
                    totalBytes =
                        totalBytes,
                    chunkSize =
                        chunkSize,
                    chunkRootHashHex =
                        chunkRootHashHex,
                    wholeFileSha256Hex =
                        wholeFileSha256Hex
                )
                return@withContext existing
            }

            val entity =
                ResumableSessionEntity(
                    originalSessionId =
                        originalSessionId,
                    itemId = itemId,
                    journalRole =
                        ResumableSessionEntity
                            .ROLE_SENDER,
                    itemName = itemName,
                    mimeType = mimeType,
                    relativePath =
                        relativePath,
                    peerFingerprintHex =
                        peerFingerprintHex,
                    sourceHandle =
                        sourceHandle,
                    sourceIdentity =
                        sourceIdentity,
                    totalBytes =
                        totalBytes,
                    chunkSize =
                        chunkSize,
                    verifiedChunks = 0L,
                    contiguousByteOffset = 0L,
                    chunkRootHashHex =
                        chunkRootHashHex,
                    verifiedChunkHashesHex = "",
                    wholeFileSha256Hex =
                        wholeFileSha256Hex,
                    createdAtMs = nowMs,
                    updatedAtMs = nowMs,
                    expiresAtMs =
                        nowMs +
                            ResumeToken
                                .MAX_TTL_MILLIS,
                    stagingPath = null
                )

            val inserted =
                resumableSessionDao
                    .insertIfAbsent(
                        entity
                    )
            if (inserted != -1L) {
                return@withContext entity
            }

            val raced =
                resumableSessionDao.get(
                    originalSessionId,
                    itemId
                )
                    ?: throw IllegalStateException(
                        "Resume journal conflicted but no persisted row is available"
                    )
            requireSenderAuthority(
                existing = raced,
                peerFingerprintHex =
                    peerFingerprintHex,
                sourceHandle =
                    sourceHandle,
                sourceIdentity =
                    sourceIdentity,
                totalBytes =
                    totalBytes,
                chunkSize =
                    chunkSize,
                chunkRootHashHex =
                    chunkRootHashHex,
                wholeFileSha256Hex =
                    wholeFileSha256Hex
            )
            raced
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

    /**
     * Records exactly the next receiver chunk from the authenticated transfer stream.
     *
     * The chunk is hashed locally, fsynced to a private temporary file, then the DB prefix is
     * atomically compare-and-advanced with the new digest. A crash between DB advance and final
     * rename is recovered by [reconcileReceiverForResume], which rewinds at the missing chunk.
     */
    suspend fun recordReceiverChunk(
        originalSessionId: String,
        itemId: String,
        chunkIndex: Long,
        chunkData: ByteArray,
        nowMs: Long =
            System.currentTimeMillis()
    ): Boolean =
        withContext(ioDispatcher) {
            val journal =
                resumableSessionDao.get(
                    originalSessionId,
                    itemId
                ) ?: return@withContext false

            if (journal.journalRole !=
                    ResumableSessionEntity
                        .ROLE_RECEIVER ||
                nowMs >=
                    journal.expiresAtMs ||
                chunkIndex !=
                    journal.verifiedChunks
            ) {
                return@withContext false
            }

            val chunkCount =
                totalChunkCount(
                    journal.totalBytes,
                    journal.chunkSize
                )
            if (chunkIndex !in
                0 until chunkCount
            ) {
                return@withContext false
            }

            val expectedLength =
                expectedChunkLength(
                    journal.totalBytes,
                    journal.chunkSize,
                    chunkIndex
                )
            if (chunkData.size !=
                expectedLength
            ) {
                return@withContext false
            }

            val itemDir =
                journal.stagingPath
                    ?.let(::File)
                    ?: return@withContext false
            if (!isInsideStagingRoot(
                    itemDir
                )
            ) {
                return@withContext false
            }
            if (!itemDir.exists() &&
                !itemDir.mkdirs()
            ) {
                return@withContext false
            }

            val prefix =
                decodeVerifiedPrefix(
                    journal
                        .verifiedChunkHashesHex
                )
            if (prefix.size.toLong() !=
                journal.verifiedChunks
            ) {
                return@withContext false
            }

            val digest =
                sha256Hex(
                    chunkData
                )
            val nextPrefix =
                prefix + digest
            val newOffset =
                min(
                    journal.totalBytes,
                    journal
                        .contiguousByteOffset +
                        chunkData.size
                            .toLong()
                )

            val chunkFile =
                chunkFile(
                    itemDir,
                    chunkIndex
                )
            val tempFile =
                File(
                    itemDir,
                    chunkFile.name +
                        ".tmp-" +
                        System.nanoTime()
                )

            try {
                FileOutputStream(
                    tempFile
                ).use { out ->
                    out.write(
                        chunkData
                    )
                    out.fd.sync()
                }

                val advanced =
                    resumableSessionDao
                        .compareAndAdvance(
                            originalSessionId =
                                originalSessionId,
                            itemId = itemId,
                            expectedVerifiedChunks =
                                journal
                                    .verifiedChunks,
                            expectedContiguousByteOffset =
                                journal
                                    .contiguousByteOffset,
                            newVerifiedChunks =
                                journal
                                    .verifiedChunks +
                                    1L,
                            newContiguousByteOffset =
                                newOffset,
                            newVerifiedChunkHashesHex =
                                encodeVerifiedPrefix(
                                    nextPrefix
                                ),
                            updatedAtMs =
                                nowMs
                        )
                if (advanced != 1) {
                    return@withContext false
                }

                atomicReplace(
                    tempFile,
                    chunkFile
                )
                true
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }

    suspend fun reconcileReceiverForResume(
        originalSessionId: String,
        itemId: String,
        authenticatedPeerFingerprintHex: String,
        peerStillTrusted: Boolean,
        nowMs: Long =
            System.currentTimeMillis()
    ): ResumeReceiverRevalidationResult =
        withContext(ioDispatcher) {
            val journal =
                resumableSessionDao.get(
                    originalSessionId,
                    itemId
                )
                    ?: return@withContext
                        ResumeReceiverRevalidationResult
                            .NotFound

            if (journal.journalRole !=
                ResumableSessionEntity
                    .ROLE_RECEIVER
            ) {
                return@withContext
                    ResumeReceiverRevalidationResult
                        .JournalMismatch
            }

            if (nowMs >=
                journal.expiresAtMs
            ) {
                purgeInternal(
                    journal
                )
                return@withContext
                    ResumeReceiverRevalidationResult
                        .Expired
            }

            if (!peerStillTrusted ||
                authenticatedPeerFingerprintHex !=
                    journal.peerFingerprintHex
            ) {
                return@withContext
                    ResumeReceiverRevalidationResult
                        .Unauthorized
            }

            val itemDir =
                journal.stagingPath
                    ?.let(::File)
                    ?: run {
                        purgeInternal(
                            journal
                        )
                        return@withContext
                            ResumeReceiverRevalidationResult
                                .JournalMismatch
                    }
            if (!isInsideStagingRoot(
                    itemDir
                )
            ) {
                purgeInternal(
                    journal
                )
                return@withContext
                    ResumeReceiverRevalidationResult
                        .JournalMismatch
            }

            val prefix =
                try {
                    decodeVerifiedPrefix(
                        journal
                            .verifiedChunkHashesHex
                    )
                } catch (
                    _: IllegalArgumentException
                ) {
                    purgeInternal(
                        journal
                    )
                    return@withContext
                        ResumeReceiverRevalidationResult
                            .JournalMismatch
                }

            if (prefix.size.toLong() !=
                journal.verifiedChunks
            ) {
                purgeInternal(
                    journal
                )
                return@withContext
                    ResumeReceiverRevalidationResult
                        .JournalMismatch
            }

            var validChunks = 0L
            var validOffset = 0L
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
                        journal.totalBytes,
                        journal.chunkSize,
                        validChunks
                    )
                if (!file.isFile ||
                    file.length() !=
                        expectedLength
                            .toLong() ||
                    sha256Hex(
                        file
                    ) !=
                        prefix[
                            validChunks
                                .toInt()
                        ]
                ) {
                    break
                }

                validOffset +=
                    expectedLength
                        .toLong()
                validChunks++
            }

            if (validChunks <
                journal.verifiedChunks
            ) {
                deleteChunkSuffix(
                    itemDir,
                    validChunks
                )
                val validPrefix =
                    prefix.take(
                        validChunks.toInt()
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
                        verifiedChunkHashesHex =
                            encodeVerifiedPrefix(
                                validPrefix
                            ),
                        updatedAtMs =
                            nowMs
                    )
            }

            ResumeReceiverRevalidationResult
                .Ready(
                    verifiedChunks =
                        validChunks,
                    contiguousByteOffset =
                        validOffset,
                    totalBytes =
                        journal.totalBytes,
                    chunkSize =
                        journal.chunkSize,
                    chunkRootHashHex =
                        journal
                            .chunkRootHashHex
                )
        }

    suspend fun reconcileSenderForResume(
        originalSessionId: String,
        itemId: String,
        authenticatedPeerFingerprintHex: String,
        peerStillTrusted: Boolean,
        sourceStillAvailable: Boolean,
        currentSourceIdentity: String,
        currentTotalBytes: Long,
        currentChunkRootHashHex: String,
        currentWholeFileSha256Hex: String,
        nowMs: Long =
            System.currentTimeMillis()
    ): ResumeSenderRevalidationResult =
        withContext(ioDispatcher) {
            val journal =
                resumableSessionDao.get(
                    originalSessionId,
                    itemId
                )
                    ?: return@withContext
                        ResumeSenderRevalidationResult
                            .NotFound

            if (journal.journalRole !=
                ResumableSessionEntity
                    .ROLE_SENDER
            ) {
                return@withContext
                    ResumeSenderRevalidationResult
                        .JournalMismatch
            }

            if (nowMs >=
                journal.expiresAtMs
            ) {
                purgeInternal(
                    journal
                )
                return@withContext
                    ResumeSenderRevalidationResult
                        .Expired
            }

            if (!peerStillTrusted ||
                authenticatedPeerFingerprintHex !=
                    journal.peerFingerprintHex
            ) {
                return@withContext
                    ResumeSenderRevalidationResult
                        .Unauthorized
            }

            if (!sourceStillAvailable) {
                return@withContext
                    ResumeSenderRevalidationResult
                        .SourceUnavailable
            }

            if (journal.sourceIdentity == null ||
                journal.sourceHandle == null ||
                journal.wholeFileSha256Hex == null ||
                currentSourceIdentity !=
                    journal.sourceIdentity ||
                currentTotalBytes !=
                    journal.totalBytes ||
                currentChunkRootHashHex !=
                    journal.chunkRootHashHex ||
                currentWholeFileSha256Hex !=
                    journal.wholeFileSha256Hex
            ) {
                return@withContext
                    ResumeSenderRevalidationResult
                        .SourceChanged
            }

            ResumeSenderRevalidationResult
                .Ready(
                    sourceHandle =
                        journal.sourceHandle,
                    totalBytes =
                        journal.totalBytes,
                    chunkSize =
                        journal.chunkSize,
                    chunkRootHashHex =
                        journal
                            .chunkRootHashHex,
                    wholeFileSha256Hex =
                        journal
                            .wholeFileSha256Hex
                )
        }

    /**
     * Final receiver promotion requires every retained chunk, a root matching the authenticated
     * resumable item root, and the complete ITEM_END whole-file SHA-256.
     */
    suspend fun assembleVerifiedReceiverFile(
        originalSessionId: String,
        itemId: String,
        expectedWholeFileSha256Hex: String,
        destinationFile: File
    ): Boolean =
        withContext(ioDispatcher) {
            if (!LOWER_HEX_64.matches(
                    expectedWholeFileSha256Hex
                )
            ) {
                return@withContext false
            }

            val journal =
                resumableSessionDao.get(
                    originalSessionId,
                    itemId
                ) ?: return@withContext false
            if (journal.journalRole !=
                ResumableSessionEntity
                    .ROLE_RECEIVER
            ) {
                return@withContext false
            }

            val prefix =
                try {
                    decodeVerifiedPrefix(
                        journal
                            .verifiedChunkHashesHex
                    )
                } catch (
                    _: IllegalArgumentException
                ) {
                    return@withContext false
                }
            val chunkCount =
                totalChunkCount(
                    journal.totalBytes,
                    journal.chunkSize
                )
            if (journal.verifiedChunks !=
                    chunkCount ||
                journal.contiguousByteOffset !=
                    journal.totalBytes ||
                prefix.size.toLong() !=
                    chunkCount
            ) {
                return@withContext false
            }

            val prefixRoot =
                ContentDigestTree(
                    prefix.map(
                        ::hexToBytes
                    )
                ).rootHashHex
            if (prefixRoot !=
                journal.chunkRootHashHex
            ) {
                return@withContext false
            }

            val itemDir =
                journal.stagingPath
                    ?.let(::File)
                    ?: return@withContext false
            if (!itemDir.isDirectory ||
                !isInsideStagingRoot(
                    itemDir
                )
            ) {
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
                        prefix.indices
                    ) {
                        val chunk =
                            chunkFile(
                                itemDir,
                                index.toLong()
                            )
                        if (!chunk.isFile ||
                            chunk.length() !=
                                expectedChunkLength(
                                    journal.totalBytes,
                                    journal.chunkSize,
                                    index.toLong()
                                ).toLong()
                        ) {
                            return@withContext false
                        }

                        val chunkDigest =
                            MessageDigest.getInstance(
                                "SHA-256"
                            )
                        FileInputStream(
                            chunk
                        ).use { input ->
                            val buffer =
                                ByteArray(
                                    COPY_BUFFER_BYTES
                                )
                            while (true) {
                                val read =
                                    input.read(
                                        buffer
                                    )
                                if (read < 0) {
                                    break
                                }
                                if (read == 0) {
                                    continue
                                }
                                chunkDigest.update(
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
                                chunkDigest
                                    .digest()
                            ) !=
                            prefix[index]
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
                        expectedWholeFileSha256Hex
                ) {
                    return@withContext false
                }

                destinationFile
                    .parentFile
                    ?.let { parent ->
                        if (!parent.exists() &&
                            !parent.mkdirs()
                        ) {
                            return@withContext false
                        }
                    }

                val promoted =
                    promoteVerifiedFile(
                        source =
                            assembled,
                        destination =
                            destinationFile,
                        expectedSize =
                            journal.totalBytes,
                        expectedSha256Hex =
                            expectedWholeFileSha256Hex
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
            purgeInternal(it)
        }
        expired.size
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

        for (journal in oldestFirst) {
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

    private suspend fun activeExistingOrNull(
        originalSessionId: String,
        itemId: String,
        nowMs: Long
    ): ResumableSessionEntity? {
        val existing =
            resumableSessionDao.get(
                originalSessionId,
                itemId
            ) ?: return null
        if (nowMs >=
            existing.expiresAtMs
        ) {
            purgeInternal(
                existing
            )
            return null
        }
        return existing
    }

    private suspend fun insertOrRequireReceiverRace(
        entity: ResumableSessionEntity
    ) {
        val inserted =
            resumableSessionDao
                .insertIfAbsent(
                    entity
                )
        if (inserted != -1L) {
            return
        }

        val raced =
            resumableSessionDao.get(
                entity.originalSessionId,
                entity.itemId
            )
                ?: throw IllegalStateException(
                    "Resume journal conflicted but no persisted row is available"
                )
        require(
            raced.itemName ==
                entity.itemName &&
                raced.mimeType ==
                    entity.mimeType &&
                raced.relativePath ==
                    entity.relativePath
        ) {
            "Raced receiver output metadata does not match this item"
        }
        requireReceiverAuthority(
            raced,
            entity.peerFingerprintHex,
            entity.totalBytes,
            entity.chunkSize,
            entity.chunkRootHashHex
        )
    }

    private fun validateCommonMetadata(
        originalSessionId: String,
        itemId: String,
        itemName: String,
        mimeType: String,
        relativePath: String?,
        peerFingerprintHex: String,
        totalBytes: Long,
        chunkSize: Int,
        chunkRootHashHex: String
    ) {
        require(
            originalSessionId.isNotBlank()
        ) {
            "Original session id cannot be blank"
        }
        require(
            itemId.isNotBlank()
        ) {
            "Item id cannot be blank"
        }
        require(
            itemName.isNotBlank()
        ) {
            "Item name cannot be blank"
        }
        require(
            mimeType.isNotBlank() &&
                mimeType.length <= 255
        ) {
            "MIME type is missing or too large"
        }
        require(
            relativePath == null ||
                relativePath.length <=
                    1024
        ) {
            "Relative path is too large"
        }
        require(
            LOWER_HEX_64.matches(
                peerFingerprintHex
            )
        ) {
            "Peer fingerprint must be canonical lowercase SHA-256"
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
                chunkRootHashHex
            )
        ) {
            "Chunk root must be canonical lowercase SHA-256"
        }
        require(
            totalChunkCount(
                totalBytes,
                chunkSize
            ) <=
                Int.MAX_VALUE
                    .toLong()
        ) {
            "Chunk manifest is too large"
        }
    }

    private fun requireReceiverAuthority(
        existing: ResumableSessionEntity,
        peerFingerprintHex: String,
        totalBytes: Long,
        chunkSize: Int,
        chunkRootHashHex: String
    ) {
        require(
            existing.journalRole ==
                ResumableSessionEntity
                    .ROLE_RECEIVER &&
                existing.peerFingerprintHex ==
                    peerFingerprintHex &&
                existing.totalBytes ==
                    totalBytes &&
                existing.chunkSize ==
                    chunkSize &&
                existing.chunkRootHashHex ==
                    chunkRootHashHex &&
                existing.sourceHandle == null &&
                existing.sourceIdentity == null &&
                existing.wholeFileSha256Hex == null &&
                existing.stagingPath != null
        ) {
            "Existing receiver resume authority does not match this item"
        }
    }

    private fun requireSenderAuthority(
        existing: ResumableSessionEntity,
        peerFingerprintHex: String,
        sourceHandle: String,
        sourceIdentity: String,
        totalBytes: Long,
        chunkSize: Int,
        chunkRootHashHex: String,
        wholeFileSha256Hex: String
    ) {
        require(
            existing.journalRole ==
                ResumableSessionEntity
                    .ROLE_SENDER &&
                existing.peerFingerprintHex ==
                    peerFingerprintHex &&
                existing.sourceHandle ==
                    sourceHandle &&
                existing.sourceIdentity ==
                    sourceIdentity &&
                existing.totalBytes ==
                    totalBytes &&
                existing.chunkSize ==
                    chunkSize &&
                existing.chunkRootHashHex ==
                    chunkRootHashHex &&
                existing.wholeFileSha256Hex ==
                    wholeFileSha256Hex &&
                existing.stagingPath == null
        ) {
            "Existing sender resume authority does not match this item"
        }
    }

    private suspend fun purgeInternal(
        journal: ResumableSessionEntity
    ) {
        journal.stagingPath
            ?.let(::File)
            ?.takeIf(
                ::isInsideStagingRoot
            )
            ?.deleteRecursively()

        resumableSessionDao.delete(
            journal.originalSessionId,
            journal.itemId
        )
    }

    private fun totalChunkCount(
        totalBytes: Long,
        chunkSize: Int
    ): Long =
        (
            totalBytes +
                chunkSize - 1L
            ) / chunkSize

    private fun expectedChunkLength(
        totalBytes: Long,
        chunkSize: Int,
        chunkIndex: Long
    ): Int {
        val chunkCount =
            totalChunkCount(
                totalBytes,
                chunkSize
            )
        require(
            chunkIndex in
                0 until chunkCount
        ) {
            "Chunk index outside item"
        }

        return if (chunkIndex ==
            chunkCount - 1L
        ) {
            val prior =
                chunkIndex *
                    chunkSize.toLong()
            (totalBytes - prior)
                .toInt()
        } else {
            chunkSize
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

    private fun encodeVerifiedPrefix(
        hashes: List<String>
    ): String =
        hashes.joinToString(",")

    private fun decodeVerifiedPrefix(
        value: String
    ): List<String> {
        if (value.isEmpty()) {
            return emptyList()
        }
        val hashes =
            value.split(',')
        require(
            hashes.all(
                LOWER_HEX_64::matches
            )
        ) {
            "Stored verified-prefix digest list is invalid"
        }
        return hashes
    }

    private fun hexToBytes(
        value: String
    ): ByteArray {
        require(
            LOWER_HEX_64.matches(
                value
            )
        ) {
            "Digest must be canonical lowercase SHA-256"
        }
        return ByteArray(
            value.length / 2
        ) { index ->
            value.substring(
                index * 2,
                index * 2 + 2
            ).toInt(16)
                .toByte()
        }
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
        FileInputStream(
            file
        ).use { input ->
            val buffer =
                ByteArray(
                    COPY_BUFFER_BYTES
                )
            while (true) {
                val read =
                    input.read(
                        buffer
                    )
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
                    sha256Hex(
                        destination
                    ) ==
                    expectedSha256Hex
            if (!valid) {
                destination.delete()
            }
            return valid
        } catch (
            _: java.io.IOException
        ) {
            // Private staging and final destination may be on different filesystems.
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
            FileInputStream(
                source
            ).use { input ->
                FileOutputStream(
                    destinationTemp
                ).use { output ->
                    val buffer =
                        ByteArray(
                            COPY_BUFFER_BYTES
                        )
                    while (true) {
                        val read =
                            input.read(
                                buffer
                            )
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
            true
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
            MAX_SOURCE_HANDLE_CHARS = 512
        private const val
            MAX_CHUNK_SIZE_BYTES =
            16 * 1024 * 1024
        private const val
            COPY_BUFFER_BYTES =
            64 * 1024
    }
}

sealed interface ResumeReceiverRevalidationResult {
    data class Ready(
        val verifiedChunks: Long,
        val contiguousByteOffset: Long,
        val totalBytes: Long,
        val chunkSize: Int,
        val chunkRootHashHex: String
    ) : ResumeReceiverRevalidationResult

    data object NotFound :
        ResumeReceiverRevalidationResult

    data object Expired :
        ResumeReceiverRevalidationResult

    data object Unauthorized :
        ResumeReceiverRevalidationResult

    data object JournalMismatch :
        ResumeReceiverRevalidationResult
}

sealed interface ResumeSenderRevalidationResult {
    data class Ready(
        val sourceHandle: String,
        val totalBytes: Long,
        val chunkSize: Int,
        val chunkRootHashHex: String,
        val wholeFileSha256Hex: String
    ) : ResumeSenderRevalidationResult

    data object NotFound :
        ResumeSenderRevalidationResult

    data object Expired :
        ResumeSenderRevalidationResult

    data object Unauthorized :
        ResumeSenderRevalidationResult

    data object JournalMismatch :
        ResumeSenderRevalidationResult

    data object SourceUnavailable :
        ResumeSenderRevalidationResult

    data object SourceChanged :
        ResumeSenderRevalidationResult
}
