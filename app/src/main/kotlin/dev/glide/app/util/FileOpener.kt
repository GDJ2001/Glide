package dev.glide.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Result of attempting to open a verified received file with an external viewer app (GLIDE-016).
 */
sealed interface FileOpenResult {
    data object Success : FileOpenResult
    data object FileNotFound : FileOpenResult
    data object NoViewerApp : FileOpenResult
    data class Error(val message: String) : FileOpenResult
}

/**
 * Utility governing safe, verified file opening via narrowly-scoped FileProvider grants.
 * Enforces read-only URI grants, never auto-opens unverified content, and gracefully handles
 * missing viewer apps and missing files.
 */
object FileOpener {

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

            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(viewIntent, displayName).apply {
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
            FileOpenResult.Error("Failed to open file: ${e.message}")
        }
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
