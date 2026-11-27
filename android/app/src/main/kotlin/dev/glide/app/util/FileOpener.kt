package dev.glide.app.util

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dev.glide.core.folder.SafeRelativePath
import java.io.File
import java.util.Locale

/**
 * Result of attempting to open a verified received file or its saved folder.
 */
sealed interface FileOpenResult {
    data object Success : FileOpenResult
    data object FileNotFound : FileOpenResult
    data object NoViewerApp : FileOpenResult
    data class Error(val message: String) : FileOpenResult
}

/**
 * Utility governing safe, verified file opening via narrowly-scoped FileProvider grants and
 * direct file-manager folder navigation for exported public copies.
 */
object FileOpener {

    private val EXTENSION_MIME_OVERRIDES = mapOf(
        "apk" to "application/vnd.android.package-archive",
        "pdf" to "application/pdf",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "heic" to "image/heic",
        "svg" to "image/svg+xml",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mkv" to "video/x-matroska",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "flac" to "audio/flac",
        "m4a" to "audio/mp4",
        "ogg" to "audio/ogg",
        "opus" to "audio/ogg",
        "aac" to "audio/aac",
        "txt" to "text/plain",
        "srt" to "text/plain",
        "vtt" to "text/plain",
        "ass" to "text/plain",
        "log" to "text/plain",
        "md" to "text/plain",
        "csv" to "text/csv",
        "json" to "application/json",
        "xml" to "application/xml",
        "html" to "text/html",
        "htm" to "text/html",
        "zip" to "application/zip",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "epub" to "application/epub+zip"
    )

    internal fun resolveMimeType(displayName: String, mimeType: String?): String {
        val sanitized = MimeSanitizer.sanitize(mimeType)
        if (sanitized != "*/*" && sanitized != "application/octet-stream") {
            return sanitized
        }
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension.isNotEmpty()) {
            EXTENSION_MIME_OVERRIDES[extension]?.let { return it }
            val mapped = runCatching {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            }.getOrNull()?.takeIf { it.isNotBlank() }
            if (mapped != null) {
                return MimeSanitizer.sanitize(mapped)
            }
        }
        return sanitized
    }

    fun openFile(
        context: Context?,
        file: File,
        displayName: String = file.name,
        mimeType: String = "*/*",
        authority: String = context?.let { "${it.packageName}.fileprovider" } ?: "dev.glide.app.fileprovider"
    ): FileOpenResult {
        if (!file.exists() || !file.isFile) {
            return FileOpenResult.FileNotFound
        }
        if (context == null) {
            return FileOpenResult.Error("Context unavailable")
        }

        return try {
            val contentUri = FileProvider.getUriForFile(context, authority, file, displayName)
            val resolvedMime = resolveMimeType(displayName, mimeType)
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, resolvedMime)
                clipData = ClipData.newRawUri(displayName, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Launch ACTION_VIEW directly (without Intent.createChooser) so Android opens the
            // matching viewer instead of an OEM Share sheet.
            context.startActivity(viewIntent)
            FileOpenResult.Success
        } catch (_: ActivityNotFoundException) {
            FileOpenResult.NoViewerApp
        } catch (e: SecurityException) {
            FileOpenResult.Error("Security error: ${e.message}")
        } catch (e: IllegalArgumentException) {
            FileOpenResult.Error("FileProvider error: ${e.message}")
        } catch (e: Exception) {
            FileOpenResult.Error("Failed to open file: ${e.message}")
        }
    }

    internal fun resolvePublicFolderDocumentId(
        relativePath: String? = null,
        savedTreeUri: String? = null
    ): String {
        val subfolder = relativePath?.let {
            SafeRelativePath.parse(it).getOrNull()?.parentPath
        }?.trim('/')
        if (!savedTreeUri.isNullOrBlank()) {
            val treeId = runCatching {
                DocumentsContract.getTreeDocumentId(Uri.parse(savedTreeUri))
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "primary:Download/Glide"
            return if (subfolder.isNullOrEmpty()) treeId else "${treeId.trimEnd('/')}/$subfolder"
        }
        return if (subfolder.isNullOrEmpty()) {
            "primary:Download/Glide"
        } else {
            "primary:Download/Glide/$subfolder"
        }
    }

    fun openInFileManager(
        context: Context?,
        relativePath: String? = null,
        savedTreeUri: String? = null
    ): FileOpenResult {
        if (context == null) {
            return FileOpenResult.Error("Context unavailable")
        }
        val subfolder = relativePath?.let {
            SafeRelativePath.parse(it).getOrNull()?.parentPath
        }?.trim('/')
        val treeUri = savedTreeUri?.takeIf { it.isNotBlank() }?.let {
            runCatching { Uri.parse(it) }.getOrNull()
        }
        val folderUri = try {
            if (treeUri != null) {
                val baseDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val targetDocId = if (subfolder.isNullOrEmpty()) baseDocId else "${baseDocId.trimEnd('/')}/$subfolder"
                DocumentsContract.buildDocumentUriUsingTree(treeUri, targetDocId)
            } else {
                val docId = resolvePublicFolderDocumentId(relativePath, null)
                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)
            }
        } catch (_: Exception) {
            null
        }

        val candidateIntents = buildList {
            if (folderUri != null) {
                add(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
                add(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(folderUri, "resource/folder")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
            }
            add(
                Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }

        var lastError: String? = null
        for (candidate in candidateIntents) {
            try {
                context.startActivity(candidate)
                return FileOpenResult.Success
            } catch (_: ActivityNotFoundException) {
                // Try the next file manager intent format
            } catch (e: Exception) {
                lastError = e.message
            }
        }
        return if (lastError != null) FileOpenResult.Error("Could not open file manager: $lastError")
        else FileOpenResult.NoViewerApp
    }

    /**
     * Resolves the on-disk File for a committed file key.
     * Looks within the app-private received directory.
     */
    fun resolveReceivedFile(receivedDir: File, committedFileKey: String): File? {
        val candidate = File(receivedDir, committedFileKey)
        if (candidate.exists() && candidate.isFile) return candidate

        val absolute = File(committedFileKey)
        if (absolute.isAbsolute && absolute.exists() && absolute.isFile) return absolute

        return null
    }

    fun resolveReceivedFile(context: Context, committedFileKey: String): File? {
        return resolveReceivedFile(File(context.filesDir, "received"), committedFileKey)
    }
}
