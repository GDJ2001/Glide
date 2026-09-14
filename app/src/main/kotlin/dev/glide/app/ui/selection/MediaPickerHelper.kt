package dev.glide.app.ui.selection

import android.os.Build
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

enum class PickerType {
    PhotosAndVideos,
    FilesAndDocuments,
    AudioOnly;
}

enum class PickerStrategy {
    SystemPhotoPicker,
    DocumentPickerFallback,
    DocumentPickerDirect;
}

object MediaPickerHelper {

    fun resolveVisualPickerStrategy(
        apiLevel: Int = Build.VERSION.SDK_INT,
        isPhotoPickerAvailable: Boolean = true
    ): PickerStrategy {
        return if (apiLevel >= Build.VERSION_CODES.TIRAMISU || isPhotoPickerAvailable) {
            PickerStrategy.SystemPhotoPicker
        } else {
            PickerStrategy.DocumentPickerFallback
        }
    }

    fun createVisualMediaRequest(): PickVisualMediaRequest {
        return PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
    }

    fun getDocumentMimeTypes(): Array<String> = arrayOf("*/*")

    fun getAudioMimeTypes(): Array<String> = arrayOf("audio/*")
}