package dev.glide.app.util

import android.content.Context
import dev.glide.app.di.DatabaseModule
import dev.glide.data.export.ExportResult
import dev.glide.data.export.FileExporter
import java.io.File

/** Publishes an already verified private file to the destination selected in Settings. */
object ReceivedFileSaver {
    suspend fun save(
        context: Context?,
        exporter: FileExporter,
        sourceFile: File,
        displayName: String,
        mimeType: String = "*/*",
        relativePath: String? = null,
        useSavedDestination: Boolean = true
    ): ExportResult {
        val treeUri = if (useSavedDestination && context != null) {
            try {
                DatabaseModule.provideSettingsStore(context).getSettings().receivedFilesTreeUri
            } catch (e: Exception) {
                return ExportResult.Failure("Could not read the save location: ${e.message ?: "Unknown error"}")
            }
        } else null
        return if (treeUri == null) {
            exporter.exportToDownloads(sourceFile, displayName, mimeType, relativePath)
        } else {
            exporter.exportToTree(sourceFile, treeUri, displayName, mimeType, relativePath)
        }
    }
}
