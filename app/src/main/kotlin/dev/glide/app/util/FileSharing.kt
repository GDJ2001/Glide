package dev.glide.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Utility governing safe, verified file sharing via narrowly-scoped FileProvider grants.
 * Enforces read-only URI grants, MIME sanitization, and graceful error handling.
 */
object FileSharing {

    fun shareFile(
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

        val safeMime = MimeSanitizer.sanitize(mimeType)
        return try {
            val contentUri = FileProvider.getUriForFile(context, authority, file, displayName)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = safeMime
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, displayName).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(chooser)
            FileOpenResult.Success
        } catch (e: ActivityNotFoundException) {
            FileOpenResult.NoViewerApp
        } catch (e: SecurityException) {
            FileOpenResult.Error("Security error: ${e.message}")
        } catch (e: IllegalArgumentException) {
            FileOpenResult.Error("FileProvider error: ${e.message}")
        } catch (e: Exception) {
            FileOpenResult.Error("Failed to share file: ${e.message}")
        }
    }

    fun shareFiles(
        context: Context?,
        files: List<File>,
        mimeType: String = "*/*"
    ): FileOpenResult = shareFiles(context, files.map { it to it.name }, mimeType)

    fun shareFiles(
        context: Context?,
        files: List<Pair<File, String>>,
        mimeType: String = "*/*",
        authority: String = context?.let { "${it.packageName}.fileprovider" } ?: "dev.glide.app.fileprovider"
    ): FileOpenResult {
        if (files.isEmpty()) {
            return FileOpenResult.FileNotFound
        }

        val missing = files.any { !it.first.exists() || !it.first.isFile }
        if (missing) {
            return FileOpenResult.FileNotFound
        }

        if (context == null) {
            return FileOpenResult.Error("Context unavailable")
        }

        val safeMime = MimeSanitizer.sanitize(mimeType)
        return try {
            val uris = ArrayList<Uri>(files.size)
            for ((file, displayName) in files) {
                uris.add(FileProvider.getUriForFile(context, authority, file, displayName))
            }

            val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = safeMime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, "Share ${files.size} files").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(chooser)
            FileOpenResult.Success
        } catch (e: ActivityNotFoundException) {
            FileOpenResult.NoViewerApp
        } catch (e: SecurityException) {
            FileOpenResult.Error("Security error: ${e.message}")
        } catch (e: IllegalArgumentException) {
            FileOpenResult.Error("FileProvider error: ${e.message}")
        } catch (e: Exception) {
            FileOpenResult.Error("Failed to share files: ${e.message}")
        }
    }
}
