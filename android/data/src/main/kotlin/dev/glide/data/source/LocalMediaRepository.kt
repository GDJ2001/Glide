package dev.glide.data.source

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class LocalMediaKind {
    PHOTO, VIDEO, AUDIO
}

data class LocalMediaEntry(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val dateModifiedSec: Long
)

class LocalMediaRepository(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun listRecent(kind: LocalMediaKind, limit: Int = 200): List<LocalMediaEntry> =
        withContext(Dispatchers.IO) {
            val (collection, defaultMime) = when (kind) {
                LocalMediaKind.PHOTO -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "image/*"
                LocalMediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "video/*"
                LocalMediaKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "audio/*"
            }
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_MODIFIED
            )
            val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            val result = mutableListOf<LocalMediaEntry>()
            runCatching {
                resolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val mimeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    val dateIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    while (cursor.moveToNext() && result.size < limit) {
                        if (idIdx < 0) continue
                        val id = cursor.getLong(idIdx)
                        val name = (if (nameIdx >= 0) cursor.getString(nameIdx) else null) ?: "media_$id"
                        val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                        if (size <= 0L) continue
                        val mime = (if (mimeIdx >= 0) cursor.getString(mimeIdx) else null)
                            ?.takeIf { it.isNotBlank() } ?: defaultMime
                        val modified = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                        val uri = ContentUris.withAppendedId(collection, id)
                        result += LocalMediaEntry(uri, name, size, mime, modified)
                    }
                }
            }
            result
        }
}
