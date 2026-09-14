package dev.glide.data.source

import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.TransferFailure
import dev.glide.core.ports.SourceAccess
import dev.glide.core.ports.SourceAccessException
import dev.glide.core.ports.SourceMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Android implementation of [SourceAccess] port from :transfer-core.
 * Reads arbitrary binary sources via ContentResolver and SAF content URIs without broad storage permissions.
 * Enforces known length, Unicode NFC normalization, filename sanitization (T06), and off-main I/O.
 */
class AndroidSourceAccess(
    private val resolverWrapper: ContentResolverWrapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val stager: BoundedSourceStager? = null
) : SourceAccess {

    override suspend fun validateSource(handle: SourceHandle): SourceMetadata {
        return validateSource(handle, onStagingProgress = null)
    }

    suspend fun validateSource(
        handle: SourceHandle,
        onStagingProgress: ((bytesStaged: Long) -> Unit)?
    ): SourceMetadata = withContext(ioDispatcher) {
        val uriString = handle.value
        val scheme = uriString.substringBefore("://", "")

        if (scheme.isEmpty() || (scheme != "content" && scheme != "file")) {
            throw SourceAccessException(
                TransferFailure.SourceUnavailable("Unsupported URI scheme: '$scheme', expected 'content://'")
            )
        }

        // Attempt persistable permission grant (FLAG_GRANT_READ_URI_PERMISSION = 1)
        if (scheme == "content") {
            resolverWrapper.takePersistableUriPermission(uriString, 1)
        }

        // Query metadata (display name, size)
        val queryResult = resolverWrapper.queryMetadata(uriString)
            ?: throw SourceAccessException(
                TransferFailure.SourceUnavailable("Source metadata unavailable or inaccessible for handle")
            )

        // Resolve and sanitize display name
        val fallbackName = uriString.substringAfterLast('/').substringBefore('?').ifBlank { "unnamed_file" }
        val rawName = queryResult.displayName ?: fallbackName
        val sanitizedName = FilenameSanitizer.sanitize(rawName)

        // Resolve MIME type
        val rawMime = resolverWrapper.getType(uriString)?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val mimeType = if (rawMime.length > ProtocolLimits.MAX_MIME_LENGTH) {
            rawMime.take(ProtocolLimits.MAX_MIME_LENGTH)
        } else {
            rawMime
        }

        // Validate size: if unknown or negative, stage into private cache (GLIDE-022)
        val size = queryResult.sizeBytes
        if (size == null || size < 0L) {
            return@withContext if (stager != null) {
                val staged = stager.stageSource(
                    handle = handle,
                    displayName = sanitizedName,
                    mimeType = mimeType,
                    onProgress = onStagingProgress ?: {}
                )
                staged.metadata
            } else {
                throw SourceAccessException(
                    TransferFailure.SourceUnavailable("Unknown source size is unsupported for direct streaming without staging")
                )
            }
        }

        if (size > ProtocolLimits.MAX_ITEM_BYTES) {
            throw SourceAccessException(
                TransferFailure.LimitExceeded(
                    "Source file size ($size bytes) exceeds maximum limit of ${ProtocolLimits.MAX_ITEM_BYTES} bytes (64 GiB)"
                )
            )
        }

        // Verify stream readability for direct sources
        try {
            val testStream = resolverWrapper.openInputStream(uriString)
                ?: throw SourceAccessException(
                    TransferFailure.SourceUnavailable("Unable to open input stream for source handle")
                )
            testStream.close()
        } catch (e: SecurityException) {
            throw SourceAccessException(
                TransferFailure.SourceUnavailable("Access denied or permission revoked for source: ${e.message}"),
                cause = e
            )
        } catch (e: FileNotFoundException) {
            throw SourceAccessException(
                TransferFailure.SourceUnavailable("Source file not found or deleted: ${e.message}"),
                cause = e
            )
        } catch (e: SourceAccessException) {
            throw e
        } catch (e: Exception) {
            throw SourceAccessException(
                TransferFailure.SourceUnavailable("Failed to verify source readability: ${e.message}"),
                cause = e
            )
        }

        SourceMetadata(
            name = sanitizedName,
            mimeType = mimeType,
            sizeBytes = size
        )
    }

    override suspend fun openSource(handle: SourceHandle): InputStream = withContext(ioDispatcher) {
        if (stager?.isStaged(handle) == true) {
            val stagedStream = stager.openStagedSource(handle)
            if (stagedStream != null) {
                return@withContext stagedStream
            }
        }

        val uriString = handle.value
        try {
            resolverWrapper.openInputStream(uriString)
                ?: throw SourceAccessException(
                    TransferFailure.SourceUnavailable("ContentResolver returned null stream for source handle")
                )
        } catch (e: SecurityException) {
            throw SourceAccessException(
                TransferFailure.SourceUnavailable("Permission revoked for source: ${e.message}"),
                cause = e
            )
        } catch (e: FileNotFoundException) {
            throw SourceAccessException(
                TransferFailure.SourceUnavailable("Source file deleted or not found: ${e.message}"),
                cause = e
            )
        } catch (e: SourceAccessException) {
            throw e
        } catch (e: Exception) {
            throw SourceAccessException(
                TransferFailure.SourceUnavailable("Error opening source stream: ${e.message}"),
                cause = e
            )
        }
    }

    fun cleanupStaged(handle: SourceHandle) {
        stager?.cleanup(handle)
    }

    fun cleanupAllStaged() {
        stager?.cleanupAll()
    }
}
