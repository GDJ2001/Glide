package dev.glide.data.source

import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.TransferFailure
import dev.glide.core.ports.SourceAccessException
import dev.glide.core.ports.SourceMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Collections
import java.util.UUID

/**
 * Result of successfully staging an unknown-length or non-persistable source.
 */
data class StagedSource(
    val handle: SourceHandle,
    val metadata: SourceMetadata,
    val stagedFile: File
)

/**
 * Manages bounded, private source staging for unknown-length sources (cloud providers,
 * dynamic streams) and transient non-persistable grants (Share Sheet ingress).
 *
 * Enforces:
 * - 64 KiB streaming chunks (no whole-file RAM loading).
 * - Minimum free disk space reserve margin (default 256 MiB).
 * - Protocol item size ceiling (64 GiB).
 * - Read timeout for provider hang protection.
 * - Immediate synchronous partial file deletion on cancellation or error.
 */
class BoundedSourceStager(
    val stagingDir: File,
    private val resolverWrapper: ContentResolverWrapper,
    val diskReserveBytes: Long = 256 * 1024 * 1024L, // 256 MiB safety margin
    val readTimeoutMs: Long = 30_000L, // 30s
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val spaceProvider: (File) -> Long = { it.usableSpace }
) {
    companion object {
        const val BUFFER_SIZE = 64 * 1024 // 64 KiB
    }

    private val stagedMap = Collections.synchronizedMap(mutableMapOf<SourceHandle, StagedSource>())

    init {
        if (!stagingDir.exists()) {
            stagingDir.mkdirs()
        }
    }

    /**
     * Stages an unknown-length source from [handle] into an app-private cache file.
     * Streams using a fixed 64 KiB buffer without buffering the whole file in RAM.
     */
    suspend fun stageSource(
        handle: SourceHandle,
        displayName: String = "staged_file",
        mimeType: String = "application/octet-stream",
        onProgress: (bytesStaged: Long) -> Unit = {}
    ): StagedSource = withContext(ioDispatcher) {
        val sanitizedName = FilenameSanitizer.sanitize(displayName)
        val stagingId = UUID.randomUUID().toString()
        val partFile = File(stagingDir, "src_stage_${stagingId}.part")
        val finalFile = File(stagingDir, "src_stage_${stagingId}.staged")

        // Initial free space check
        val usableSpace = spaceProvider(stagingDir)
        if (usableSpace < diskReserveBytes) {
            throw SourceAccessException(
                TransferFailure.InsufficientStorage(
                    requiredBytes = diskReserveBytes,
                    availableBytes = usableSpace
                ),
                message = "Insufficient disk space to stage source: requires at least ${diskReserveBytes / (1024 * 1024)} MiB reserve"
            )
        }

        var bytesWritten = 0L
        try {
            val inputStream = resolverWrapper.openInputStream(handle.value)
                ?: throw SourceAccessException(
                    TransferFailure.SourceUnavailable("Unable to open source input stream for handle: ${handle.value}")
                )

            inputStream.use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        ensureActive()

                        // Read with provider timeout to detect hung providers
                        val bytesRead = try {
                            withTimeout(readTimeoutMs) {
                                kotlinx.coroutines.runInterruptible(ioDispatcher) {
                                    input.read(buffer)
                                }
                            }
                        } catch (e: TimeoutCancellationException) {
                            throw SourceAccessException(
                                TransferFailure.Timeout(
                                    phase = "staging",
                                    detail = "Source provider timed out while reading after $bytesWritten bytes"
                                ),
                                cause = e
                            )
                        } catch (e: SecurityException) {
                            throw SourceAccessException(
                                TransferFailure.SourceUnavailable("Source access revoked during staging: ${e.message}"),
                                cause = e
                            )
                        }

                        if (bytesRead == -1) break

                        output.write(buffer, 0, bytesRead)
                        bytesWritten += bytesRead

                        // Verify item limit
                        if (bytesWritten > ProtocolLimits.MAX_ITEM_BYTES) {
                            throw SourceAccessException(
                                TransferFailure.LimitExceeded(
                                    "Staged source size ($bytesWritten bytes) exceeds protocol ceiling of ${ProtocolLimits.MAX_ITEM_BYTES} bytes"
                                )
                            )
                        }

                        // Verify disk reserve margin
                        val remainingSpace = spaceProvider(stagingDir)
                        if (remainingSpace < diskReserveBytes) {
                            throw SourceAccessException(
                                TransferFailure.InsufficientStorage(
                                    requiredBytes = diskReserveBytes,
                                    availableBytes = remainingSpace
                                ),
                                message = "Disk space exhausted during staging: below ${diskReserveBytes / (1024 * 1024)} MiB safety reserve"
                            )
                        }

                        onProgress(bytesWritten)
                    }
                    output.flush()
                }
            }

            // Atomic rename from .part to .staged
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }

            val metadata = SourceMetadata(
                name = sanitizedName,
                mimeType = mimeType,
                sizeBytes = bytesWritten
            )

            val staged = StagedSource(
                handle = handle,
                metadata = metadata,
                stagedFile = finalFile
            )

            stagedMap[handle] = staged
            staged
        } catch (e: Throwable) {
            // Synchronously clean up partial file on any failure or cancellation
            if (partFile.exists()) {
                partFile.delete()
            }
            if (finalFile.exists()) {
                finalFile.delete()
            }
            stagedMap.remove(handle)
            throw e
        }
    }

    /**
     * Opens an input stream for an already staged source handle.
     */
    fun openStagedSource(handle: SourceHandle): InputStream? {
        val staged = stagedMap[handle] ?: return null
        return if (staged.stagedFile.exists()) {
            FileInputStream(staged.stagedFile)
        } else {
            null
        }
    }

    fun isStaged(handle: SourceHandle): Boolean = stagedMap.containsKey(handle)

    fun getStagedSource(handle: SourceHandle): StagedSource? = stagedMap[handle]

    /**
     * Cleans up the staged file for a specific source handle.
     */
    fun cleanup(handle: SourceHandle) {
        val staged = stagedMap.remove(handle)
        staged?.stagedFile?.let { file ->
            if (file.exists()) {
                file.delete()
            }
        }
    }

    /**
     * Cleans up all currently staged files in this stager.
     */
    fun cleanupAll() {
        val handles = stagedMap.keys.toList()
        for (h in handles) {
            cleanup(h)
        }
        stagingDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("src_stage_")) {
                file.delete()
            }
        }
    }

    /**
     * Removes orphaned staging files older than [maxAgeMs] (default 24h).
     */
    fun cleanupOrphans(maxAgeMs: Long = 24 * 60 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        stagingDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("src_stage_") && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }
}