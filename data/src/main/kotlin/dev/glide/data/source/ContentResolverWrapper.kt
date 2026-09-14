package dev.glide.data.source

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

/**
 * Metadata query result from a content provider query.
 */
data class SourceQueryMetadata(
    val displayName: String?,
    val sizeBytes: Long?
)

/**
 * Abstraction for Android ContentResolver interactions to enable deterministic unit testing.
 */
interface ContentResolverWrapper {
    fun queryMetadata(uriString: String): SourceQueryMetadata?
    fun openInputStream(uriString: String): InputStream?
    fun getType(uriString: String): String?
    fun takePersistableUriPermission(uriString: String, modeFlags: Int)
}

/**
 * Production implementation of [ContentResolverWrapper] backed by [ContentResolver].
 */
class AndroidContentResolverWrapper(
    private val contentResolver: ContentResolver
) : ContentResolverWrapper {

    override fun queryMetadata(uriString: String): SourceQueryMetadata? {
        val uri = try {
            Uri.parse(uriString)
        } catch (_: Exception) {
            return null
        }

        var name: String? = null
        var size: Long? = null

        try {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)

                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        name = cursor.getString(nameIdx)
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        } catch (_: SecurityException) {
            return null
        } catch (_: Exception) {
            // Cursor query failed, allow fallback check below
        }

        // Secondary size lookup if cursor did not return a valid non-negative size
        if (size == null || size < 0) {
            try {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    val len = afd.length
                    if (len >= 0) {
                        size = len
                    }
                }
            } catch (_: Exception) {
                // Secondary check failed
            }
        }

        return SourceQueryMetadata(displayName = name, sizeBytes = size)
    }

    override fun openInputStream(uriString: String): InputStream? {
        val uri = try {
            Uri.parse(uriString)
        } catch (_: Exception) {
            return null
        }
        return contentResolver.openInputStream(uri)
    }

    override fun getType(uriString: String): String? {
        val uri = try {
            Uri.parse(uriString)
        } catch (_: Exception) {
            return null
        }
        return contentResolver.getType(uri)
    }

    override fun takePersistableUriPermission(uriString: String, modeFlags: Int) {
        val uri = try {
            Uri.parse(uriString)
        } catch (_: Exception) {
            return
        }
        try {
            contentResolver.takePersistableUriPermission(uri, modeFlags)
        } catch (_: SecurityException) {
            // Transient or non-persistable grants allowed without failure
        } catch (_: Exception) {
            // Provider edge case
        }
    }
}
