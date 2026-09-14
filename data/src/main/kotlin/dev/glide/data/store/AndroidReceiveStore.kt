package dev.glide.data.store

import dev.glide.core.domain.CommittedFileHandle
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.StagingHandle
import dev.glide.core.domain.TransferFailure
import dev.glide.core.ports.ReceiveStore
import dev.glide.core.ports.ReceiveStoreException
import dev.glide.data.db.JournalPhases
import dev.glide.data.db.ReceiveJournalDao
import dev.glide.data.db.ReceiveJournalEntity
import dev.glide.data.db.ReceivedFileDao
import dev.glide.data.db.ReceivedFileEntity
import dev.glide.data.source.StreamHasher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Production implementation of [ReceiveStore] port from :transfer-core.
 * Quarantines incoming unverified streams in app-private staging storage under noBackupFilesDir/receiving.
 * Enforces preaccept space checks (required + max(256MiB, 5%)), incremental/file SHA-256 verification,
 * atomic filesystem rename to noBackupFilesDir/received, durable Room journal transitions,
 * received-file cataloging, and crash recovery reconciliation.
 */
class AndroidReceiveStore(
    private val rootDir: File,
    private val journalDao: ReceiveJournalDao,
    private val fileCatalogDao: ReceivedFileDao,
    private val freeSpaceProvider: (File) -> Long = { it.usableSpace },
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ReceiveStore {

    val stagingDir: File = File(rootDir, "receiving")
    val receivedDir: File = File(rootDir, "received")

    init {
        if (!stagingDir.exists()) stagingDir.mkdirs()
        if (!receivedDir.exists()) receivedDir.mkdirs()
    }

    override suspend fun hasAvailableSpace(requiredBytes: Long): Boolean = withContext(ioDispatcher) {
        if (requiredBytes < 0) return@withContext false
        try {
            // Checked arithmetic to prevent 64-bit integer overflow:
            // Margin = max(256 MiB, 5% of requiredBytes)
            val margin = maxOf(256L * 1024L * 1024L, (requiredBytes * 0.05).toLong())
            val totalBudget = Math.addExact(requiredBytes, margin)
            val available = freeSpaceProvider(rootDir)
            available >= totalBudget
        } catch (_: ArithmeticException) {
            false
        }
    }

    override suspend fun prepareStaging(
        sessionId: SessionId,
        itemId: ItemId,
        expectedBytes: Long,
        displayName: String,
        mimeType: String
    ): StagingHandle = withContext(ioDispatcher) {
        if (!hasAvailableSpace(expectedBytes)) {
            val available = freeSpaceProvider(rootDir)
            throw ReceiveStoreException(
                TransferFailure.InsufficientStorage(
                    requiredBytes = expectedBytes,
                    availableBytes = available
                )
            )
        }

        if (expectedBytes < 0 || expectedBytes > ProtocolLimits.MAX_ITEM_BYTES) {
            throw ReceiveStoreException(
                TransferFailure.LimitExceeded(
                    "Item expected bytes ($expectedBytes) exceeds allowed limits"
                )
            )
        }

        val stagingFileName = "${itemId.value}.part"
        val stagingFile = File(stagingDir, stagingFileName)
        val finalKey = UUID.randomUUID().toString()

        // Clean any leftover unverified partial file for this item
        if (stagingFile.exists()) {
            stagingFile.delete()
        }
        stagingFile.createNewFile()

        val entry = ReceiveJournalEntity(
            itemId = itemId.value,
            sessionId = sessionId.value,
            stagingKey = stagingFileName,
            finalKey = finalKey,
            displayName = displayName,
            mimeType = mimeType,
            expectedBytes = expectedBytes,
            verifiedDigest = null,
            phase = JournalPhases.PREPARED,
            updatedAtMs = clock()
        )
        journalDao.insertOrUpdate(entry)

        StagingHandle("staging-${itemId.value}")
    }

    override suspend fun openStagingOutput(handle: StagingHandle): OutputStream = withContext(ioDispatcher) {
        val itemId = extractItemId(handle)
        val entry = journalDao.getByItemId(itemId)
            ?: throw ReceiveStoreException(
                TransferFailure.DestinationUnavailable("Staging journal entry missing for handle $handle")
            )

        val stagingFile = File(stagingDir, entry.stagingKey)
        if (!stagingFile.exists()) {
            throw ReceiveStoreException(
                TransferFailure.DestinationUnavailable("Staging file does not exist: ${entry.stagingKey}")
            )
        }

        journalDao.updatePhase(itemId, JournalPhases.WRITING, clock())
        FileOutputStream(stagingFile)
    }

    override suspend fun commitVerified(
        handle: StagingHandle,
        digest: Sha256Digest
    ): CommittedFileHandle = withContext(ioDispatcher) {
        val itemId = extractItemId(handle)
        val entry = journalDao.getByItemId(itemId)
            ?: throw ReceiveStoreException(
                TransferFailure.DestinationUnavailable("Staging journal entry missing for handle $handle")
            )

        val stagingFile = File(stagingDir, entry.stagingKey)
        val finalFile = File(receivedDir, entry.finalKey)

        // Idempotency check: if final file already exists and is cataloged, return it
        val existingCatalog = fileCatalogDao.getByFileKey(entry.finalKey)
        if (finalFile.exists() && existingCatalog != null && existingCatalog.digest == digest.value) {
            journalDao.delete(itemId)
            return@withContext CommittedFileHandle(entry.finalKey)
        }

        if (!stagingFile.exists()) {
            throw ReceiveStoreException(
                TransferFailure.DestinationUnavailable("Staging file missing for commit: ${entry.stagingKey}")
            )
        }

        // Verify file length matches expected
        if (stagingFile.length() != entry.expectedBytes) {
            stagingFile.delete()
            journalDao.delete(itemId)
            throw ReceiveStoreException(
                TransferFailure.IntegrityMismatch(
                    expected = digest,
                    actual = Sha256Digest("0".repeat(64))
                )
            )
        }

        // Verify SHA-256 digest directly from on-disk staged bytes
        val hashResult = StreamHasher.hashStream(
            inputStream = FileInputStream(stagingFile),
            expectedBytes = entry.expectedBytes
        )

        if (hashResult.digest != digest) {
            // Digest mismatch! Unverified file is NEVER visible; delete immediately.
            stagingFile.delete()
            journalDao.delete(itemId)
            throw ReceiveStoreException(
                TransferFailure.IntegrityMismatch(
                    expected = digest,
                    actual = hashResult.digest
                )
            )
        }

        // Phase 1: Persist journal COMMITTING with verified digest
        journalDao.updateVerifiedDigest(
            itemId = itemId,
            phase = JournalPhases.COMMITTING,
            digest = digest.value,
            updatedAtMs = clock()
        )

        // Phase 2: Atomic rename within the same filesystem volume
        val renamed = stagingFile.renameTo(finalFile)
        if (!renamed) {
            throw ReceiveStoreException(
                TransferFailure.WriteFailure("Atomic file rename failed from ${stagingFile.name} to ${finalFile.name}")
            )
        }

        // Phase 3: Transactionally record verified file catalog
        val catalogRecord = ReceivedFileEntity(
            fileKey = entry.finalKey,
            originatingItemId = itemId,
            displayName = entry.displayName,
            mime = entry.mimeType,
            length = hashResult.sizeBytes,
            digest = digest.value,
            receivedAtMs = clock()
        )
        fileCatalogDao.insertOrUpdate(catalogRecord)

        // Phase 4: Clean up journal entry
        journalDao.delete(itemId)

        CommittedFileHandle(entry.finalKey)
    }

    override suspend fun discardStaging(handle: StagingHandle) = withContext(ioDispatcher) {
        val itemId = extractItemId(handle)
        val entry = journalDao.getByItemId(itemId)
        if (entry != null) {
            val stagingFile = File(stagingDir, entry.stagingKey)
            if (stagingFile.exists()) {
                stagingFile.delete()
            }
            journalDao.delete(itemId)
        } else {
            val defaultFile = File(stagingDir, "$itemId.part")
            if (defaultFile.exists()) {
                defaultFile.delete()
            }
        }
    }

    /**
     * Reconciles interrupted sessions, commits boundary-verified files, cleans unverified artifacts,
     * detects missing catalog files, and purges stale partial files.
     *
     * @param activeStagingKeys Staging filenames currently owned by an active in-memory session to prevent deletion.
     */
    suspend fun reconcileDetailed(
        activeStagingKeys: Set<String> = emptySet()
    ): ReceiveStoreReconciliationResult = withContext(ioDispatcher) {
        var reconciledCommits = 0
        var purgedPartials = 0
        var missingFilesMarkedDeleted = 0
        val pendingEntries = journalDao.getPendingEntries()

        for (entry in pendingEntries) {
            val finalFile = File(receivedDir, entry.finalKey)
            val stagingFile = File(stagingDir, entry.stagingKey)

            if (entry.phase == JournalPhases.COMMITTING && entry.verifiedDigest != null) {
                // Post-rename or pre-rename crash recovery:
                if (finalFile.exists()) {
                    // Post-rename crash: finalFile is in receivedDir.
                    // Verify that length matches and on-disk SHA-256 matches verifiedDigest
                    val isValid = try {
                        if (finalFile.length() == entry.expectedBytes) {
                            val hash = StreamHasher.hashStream(FileInputStream(finalFile), entry.expectedBytes)
                            hash.digest.value == entry.verifiedDigest
                        } else false
                    } catch (_: Exception) {
                        false
                    }

                    if (isValid) {
                        fileCatalogDao.insertOrUpdate(
                            ReceivedFileEntity(
                                fileKey = entry.finalKey,
                                originatingItemId = entry.itemId,
                                displayName = entry.displayName,
                                mime = entry.mimeType,
                                length = entry.expectedBytes,
                                digest = entry.verifiedDigest,
                                receivedAtMs = entry.updatedAtMs
                            )
                        )
                        journalDao.delete(entry.itemId)
                        reconciledCommits++
                    } else {
                        // Corrupted file on disk; delete and clean journal
                        if (finalFile.exists()) finalFile.delete()
                        journalDao.delete(entry.itemId)
                        purgedPartials++
                    }
                } else if (stagingFile.exists()) {
                    // Pre-rename crash: hash was verified, but rename didn't execute or complete
                    val isValid = try {
                        if (stagingFile.length() == entry.expectedBytes) {
                            val hash = StreamHasher.hashStream(FileInputStream(stagingFile), entry.expectedBytes)
                            hash.digest.value == entry.verifiedDigest
                        } else false
                    } catch (_: Exception) {
                        false
                    }

                    if (isValid) {
                        // Atomically complete the rename to finalKey
                        val renamed = stagingFile.renameTo(finalFile)
                        if (renamed) {
                            fileCatalogDao.insertOrUpdate(
                                ReceivedFileEntity(
                                    fileKey = entry.finalKey,
                                    originatingItemId = entry.itemId,
                                    displayName = entry.displayName,
                                    mime = entry.mimeType,
                                    length = entry.expectedBytes,
                                    digest = entry.verifiedDigest,
                                    receivedAtMs = entry.updatedAtMs
                                )
                            )
                            journalDao.delete(entry.itemId)
                            reconciledCommits++
                        } else {
                            stagingFile.delete()
                            journalDao.delete(entry.itemId)
                            purgedPartials++
                        }
                    } else {
                        // Corrupted or incomplete staging file; purge
                        stagingFile.delete()
                        journalDao.delete(entry.itemId)
                        purgedPartials++
                    }
                } else {
                    // Neither final nor staging exists; clear orphan journal entry
                    journalDao.delete(entry.itemId)
                    purgedPartials++
                }
            } else {
                // Interrupted during PREPARED or WRITING, or unverified:
                if (entry.stagingKey in activeStagingKeys) {
                    // Active process ownership: do not delete files belonging to an active transfer!
                } else {
                    if (stagingFile.exists()) {
                        stagingFile.delete()
                    }
                    journalDao.delete(entry.itemId)
                    purgedPartials++
                }
            }
        }

        // Database missing file check ("DB missing file"):
        // "A durable record cannot claim a missing file."
        // Verify that all active catalog files actually exist on the filesystem.
        val activeFiles = fileCatalogDao.getActiveFiles()
        for (fileRecord in activeFiles) {
            val diskFile = File(receivedDir, fileRecord.fileKey)
            if (!diskFile.exists()) {
                fileCatalogDao.markDeleted(fileRecord.fileKey, clock())
                missingFilesMarkedDeleted++
            }
        }

        // Remove unreferenced .part files older than 24 hours (excluding active process staging keys)
        val cutoffMs = clock() - (24L * 60L * 60L * 1000L)
        val activeOrJournalKeys = pendingEntries.map { it.stagingKey }.toSet() + activeStagingKeys
        stagingDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".part") && file.name !in activeOrJournalKeys && file.lastModified() < cutoffMs) {
                if (file.delete()) {
                    purgedPartials++
                }
            }
        }

        ReceiveStoreReconciliationResult(
            reconciledCommits = reconciledCommits,
            purgedPartials = purgedPartials,
            missingFilesMarkedDeleted = missingFilesMarkedDeleted
        )
    }

    /**
     * Backward-compatible overload returning total reconciled actions (commits + purged partials).
     */
    suspend fun reconcile(): Int = reconcileDetailed().let { it.reconciledCommits + it.purgedPartials }

    private fun extractItemId(handle: StagingHandle): String {
        return handle.value.removePrefix("staging-")
    }
}

/**
 * Result data class for [AndroidReceiveStore.reconcileDetailed].
 */
data class ReceiveStoreReconciliationResult(
    val reconciledCommits: Int,
    val purgedPartials: Int,
    val missingFilesMarkedDeleted: Int
) {
    val totalCount: Int get() = reconciledCommits + purgedPartials + missingFilesMarkedDeleted
}
