package dev.glide.data.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import dev.glide.core.folder.SafeRelativePath
import dev.glide.data.source.FilenameSanitizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Result of an export operation from app-private quarantine/catalog to public storage (GLIDE-023).
 */
sealed interface ExportResult {
    data class Success(
        val destinationUriString: String,
        val destinationName: String,
        val bytesExported: Long
    ) : ExportResult {
        val destinationUri: Uri?
            get() = try {
                Uri.parse(destinationUriString)
            } catch (_: Exception) {
                null
            }
    }

    data class Failure(
        val message: String,
        val partialDeleted: Boolean = true,
        val cause: Throwable? = null
    ) : ExportResult
}

/**
 * Port governing verified received-file export to public storage with safe duplicate naming.
 */
interface FileExporter {
    suspend fun exportToDownloads(
        sourceFile: File,
        displayName: String,
        mimeType: String = "*/*",
        relativePath: String? = null
    ): ExportResult

    suspend fun exportToUri(
        sourceFile: File,
        destinationUriString: String,
        displayName: String,
        relativePath: String? = null
    ): ExportResult

    suspend fun exportToUri(
        sourceFile: File,
        destinationUri: Uri,
        displayName: String,
        relativePath: String? = null
    ): ExportResult = exportToUri(sourceFile, destinationUri.toString(), displayName, relativePath)
}

/**
 * Adapter abstracting Android MediaStore and ContentResolver interactions for testability.
 */
interface MediaStoreExportAdapter {
    fun queryExistingDownloadNames(): Set<String>
    fun queryExistingDownloadNames(subfolder: String?): Set<String> = queryExistingDownloadNames()
    fun insertPendingDownload(displayName: String, mimeType: String): String?
    fun insertPendingDownload(displayName: String, mimeType: String, subfolder: String?): String? =
        insertPendingDownload(displayName, mimeType)
    fun openOutputStream(uriString: String): OutputStream?
    fun markPendingComplete(uriString: String): Boolean
    fun delete(uriString: String): Boolean
    fun getAvailableBytes(): Long
}

/**
 * Production implementation of [MediaStoreExportAdapter] using Android ContentResolver.
 */
class DefaultMediaStoreExportAdapter(
    private val context: Context
) : MediaStoreExportAdapter {

    override fun queryExistingDownloadNames(): Set<String> = queryExistingDownloadNames(null)

    override fun queryExistingDownloadNames(subfolder: String?): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptySet()
        val names = mutableSetOf<String>()
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val queryUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val (selection, selectionArgs) = if (!subfolder.isNullOrBlank()) {
            val cleanSub = subfolder.trim('/')
            val withTrailing = "${Environment.DIRECTORY_DOWNLOADS}/Glide/$cleanSub/"
            val withoutTrailing = "${Environment.DIRECTORY_DOWNLOADS}/Glide/$cleanSub"
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?" to arrayOf(withTrailing, withoutTrailing)
        } else {
            null to null
        }
        try {
            context.contentResolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrBlank()) {
                            names.add(name)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Fail open on query errors to avoid blocking export
        }
        return names
    }

    override fun insertPendingDownload(displayName: String, mimeType: String): String? =
        insertPendingDownload(displayName, mimeType, null)

    override fun insertPendingDownload(displayName: String, mimeType: String, subfolder: String?): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val targetRelPath = if (!subfolder.isNullOrBlank()) {
            "${Environment.DIRECTORY_DOWNLOADS}/Glide/${subfolder.trim('/')}"
        } else {
            Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifBlank { "*/*" })
            put(MediaStore.MediaColumns.RELATIVE_PATH, targetRelPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return try {
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.toString()
        } catch (_: Exception) {
            null
        }
    }

    override fun openOutputStream(uriString: String): OutputStream? {
        return try {
            val uri = Uri.parse(uriString)
            context.contentResolver.openOutputStream(uri, "wt")
        } catch (_: Exception) {
            null
        }
    }

    override fun markPendingComplete(uriString: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        return try {
            val uri = Uri.parse(uriString)
            val rows = context.contentResolver.update(uri, values, null, null)
            rows > 0
        } catch (_: Exception) {
            false
        }
    }

    override fun delete(uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            val deleted = context.contentResolver.delete(uri, null, null)
            deleted > 0
        } catch (_: Exception) {
            false
        }
    }

    override fun getAvailableBytes(): Long {
        return try {
            Environment.getExternalStorageDirectory().usableSpace
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }
}

/**
 * Production implementation of [FileExporter] (GLIDE-023).
 *
 * Enforces:
 * - API 29+ MediaStore.Downloads with IS_PENDING = 1 during write, IS_PENDING = 0 on success.
 * - API 26-28 fallback & custom destinations via SAF.
 * - Bounded 64 KiB buffer ($O(1)$ memory; zero whole-file RAM loading).
 * - Safe duplicate naming using "name (n).ext" with exclusive retry.
 * - Windows reserved device names and illegal filesystem character sanitization.
 * - Strict failure isolation: original verified private file is never deleted or modified.
 * - Clean deletion of pending/partial files on failure, with disclosure if provider refuses delete.
 */
class AndroidFileExporter(
    private val adapter: MediaStoreExportAdapter,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val uriDeleteHandler: ((String) -> Boolean)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : FileExporter {

    constructor(context: Context) : this(
        adapter = DefaultMediaStoreExportAdapter(context.applicationContext),
        sdkInt = Build.VERSION.SDK_INT,
        uriDeleteHandler = { uriString ->
            try {
                val uri = Uri.parse(uriString)
                if (DocumentsContract.isDocumentUri(context, uri)) {
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                } else {
                    context.contentResolver.delete(uri, null, null) > 0
                }
            } catch (_: Exception) {
                false
            }
        }
    )

    companion object {
        const val BUFFER_SIZE = 64 * 1024 // 64 KiB fixed streaming buffer
    }

    override suspend fun exportToDownloads(
        sourceFile: File,
        displayName: String,
        mimeType: String,
        relativePath: String?
    ): ExportResult = withContext(ioDispatcher) {
        if (!sourceFile.exists() || !sourceFile.isFile) {
            return@withContext ExportResult.Failure(
                message = "Source file does not exist: ${sourceFile.name}"
            )
        }

        if (sdkInt < 29) {
            return@withContext ExportResult.Failure(
                message = "Direct Downloads export requires Android 10+ (API 29+). Please use custom destination."
            )
        }

        val requiredBytes = sourceFile.length()
        val availableBytes = adapter.getAvailableBytes()
        if (availableBytes < requiredBytes) {
            return@withContext ExportResult.Failure(
                message = "Insufficient storage space: requires $requiredBytes bytes, available $availableBytes bytes"
            )
        }

        val subfolder = relativePath?.let {
            SafeRelativePath.parse(it).getOrNull()?.parentPath
        }

        // Safe duplicate naming resolution
        val existingNames = adapter.queryExistingDownloadNames(subfolder)
        val uniqueName = FilenameSanitizer.resolveUniqueName(
            desiredName = displayName,
            existsPredicate = { it in existingNames }
        )

        // Allocate pending entry in MediaStore.Downloads
        val targetUriString = adapter.insertPendingDownload(uniqueName, mimeType, subfolder)
            ?: return@withContext ExportResult.Failure(
                message = "Failed to allocate entry in MediaStore.Downloads"
            )

        var totalCopied = 0L
        var deleteSucceeded = true

        try {
            val outputStream = adapter.openOutputStream(targetUriString)
                ?: throw IOException("Failed to open output stream for destination: $targetUriString")

            FileInputStream(sourceFile).use { input ->
                outputStream.use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalCopied += bytesRead
                    }
                    output.flush()
                }
            }

            // Mark pending complete
            val marked = adapter.markPendingComplete(targetUriString)
            if (!marked) {
                throw IOException("Failed to clear IS_PENDING on exported MediaStore record")
            }

            ExportResult.Success(
                destinationUriString = targetUriString,
                destinationName = uniqueName,
                bytesExported = totalCopied
            )
        } catch (e: Exception) {
            // Failure occurred! Delete the pending unverified entry
            try {
                deleteSucceeded = adapter.delete(targetUriString)
            } catch (_: Exception) {
                deleteSucceeded = false
            }

            ExportResult.Failure(
                message = "Export to Downloads failed: ${e.message ?: "Unknown error"}",
                partialDeleted = deleteSucceeded,
                cause = e
            )
        }
    }

    override suspend fun exportToUri(
        sourceFile: File,
        destinationUriString: String,
        displayName: String,
        relativePath: String?
    ): ExportResult = withContext(ioDispatcher) {
        if (!sourceFile.exists() || !sourceFile.isFile) {
            return@withContext ExportResult.Failure(
                message = "Source file does not exist: ${sourceFile.name}"
            )
        }

        val requiredBytes = sourceFile.length()
        val availableBytes = adapter.getAvailableBytes()
        if (availableBytes < requiredBytes) {
            return@withContext ExportResult.Failure(
                message = "Insufficient storage space: requires $requiredBytes bytes, available $availableBytes bytes"
            )
        }

        val sanitizedName = FilenameSanitizer.sanitize(displayName)
        var totalCopied = 0L
        var deleteSucceeded = true

        try {
            val outputStream = adapter.openOutputStream(destinationUriString)
                ?: throw IOException("Failed to open output stream for target: $destinationUriString")

            FileInputStream(sourceFile).use { input ->
                outputStream.use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalCopied += bytesRead
                    }
                    output.flush()
                }
            }

            ExportResult.Success(
                destinationUriString = destinationUriString,
                destinationName = sanitizedName,
                bytesExported = totalCopied
            )
        } catch (e: Exception) {
            // Attempt to delete partial file on destination
            deleteSucceeded = try {
                uriDeleteHandler?.invoke(destinationUriString) ?: adapter.delete(destinationUriString)
            } catch (_: Exception) {
                false
            }

            ExportResult.Failure(
                message = "Export to custom destination failed: ${e.message ?: "Unknown error"}",
                partialDeleted = deleteSucceeded,
                cause = e
            )
        }
    }
}
