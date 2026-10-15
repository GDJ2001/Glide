package dev.glide.app.service

import dev.glide.app.ui.transfer.ExportUiStatus
import dev.glide.app.ui.transfer.TransferUiState
import dev.glide.app.ui.transfer.VerifiedItemUiState
import dev.glide.data.export.ExportResult
import java.io.File

/** Publishes only verified, committed items; a failure leaves the private copies intact. */
internal suspend fun publishVerifiedReceive(
    state: TransferUiState.Completed,
    resolve: (String) -> File?,
    save: suspend (File, VerifiedItemUiState) -> ExportResult,
    onSaved: (String) -> Unit = {}
): ExportUiStatus {
    var saved = 0
    var lastName = ""
    for (item in state.verifiedItems) {
        val file = item.committedFileKey?.let(resolve)
        if (file == null || !file.isFile) {
            return ExportUiStatus.Failed("Verified file is unavailable for public saving")
        }
        when (val result = save(file, item)) {
            is ExportResult.Success -> {
                saved++
                lastName = result.destinationName
                onSaved(item.itemId)
            }
            is ExportResult.Failure -> {
                return ExportUiStatus.Failed(
                    "Saved $saved of ${state.verifiedItems.size} files. ${result.message}"
                )
            }
        }
    }
    return if (saved == 0) ExportUiStatus.Failed("No verified files were available to save")
    else ExportUiStatus.Success(if (saved == 1) lastName else "$saved files")
}
