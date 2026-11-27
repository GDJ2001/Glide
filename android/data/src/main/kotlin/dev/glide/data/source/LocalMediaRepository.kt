package dev.glide.data.source

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.util.Locale

enum class LocalMediaKind {
    RECENT, PHOTO, VIDEO, AUDIO, DOCUMENT, WORK
}

data class LocalMediaEntry(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val dateModifiedSec: Long,
    val bucketName: String? = null,
    val durationMs: Long = 0L,
    val sourceLabel: String? = null
)

class LocalMediaRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val quickPrefs = QuickSelectionPrefsStore(appContext)

    suspend fun listRecent(kind: LocalMediaKind, limit: Int = 200): List<LocalMediaEntry> =
        withContext(Dispatchers.IO) {
            when (kind) {
                LocalMediaKind.PHOTO -> mergeWithRecents(
                    queryMediaCollection(
                        collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        defaultMime = "image/*",
                        includeDuration = false,
                        limit = limit
                    ),
                    recentFilter = { it.mimeType.startsWith("image/") }
                ).take(limit)

                LocalMediaKind.VIDEO -> mergeWithRecents(
                    queryMediaCollection(
                        collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        defaultMime = "video/*",
                        includeDuration = true,
                        limit = limit
                    ),
                    recentFilter = { it.mimeType.startsWith("video/") }
                ).take(limit)

                LocalMediaKind.AUDIO -> mergeWithRecents(
                    queryMediaCollection(
                        collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        defaultMime = "audio/*",
                        includeDuration = true,
                        limit = limit
                    ),
                    recentFilter = { it.mimeType.startsWith("audio/") }
                ).take(limit)

                LocalMediaKind.DOCUMENT -> listDocumentsAndPinned(workOnly = false, limit = limit)
                LocalMediaKind.WORK -> listDocumentsAndPinned(workOnly = true, limit = limit)
                LocalMediaKind.RECENT -> listAllRecent(limit = limit)
            }
        }

    suspend fun loadThumbnail(entry: LocalMediaEntry, widthPx: Int = 240, heightPx: Int = 240): Bitmap? =
        withContext(Dispatchers.IO) {
            val isImage = entry.mimeType.startsWith("image/", ignoreCase = true) ||
                hasExtension(entry.displayName, IMAGE_EXTENSIONS)
            val isVideo = entry.mimeType.startsWith("video/", ignoreCase = true) ||
                hasExtension(entry.displayName, VIDEO_EXTENSIONS)
            if (!isImage && !isVideo) return@withContext null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    resolver.loadThumbnail(entry.uri, Size(widthPx, heightPx), null)
                }.getOrNull()?.let { return@withContext it }
            }
            if (!isImage) return@withContext null

            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(entry.uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sampleSize = 1
                while ((bounds.outWidth / sampleSize) > widthPx * 2 &&
                    (bounds.outHeight / sampleSize) > heightPx * 2
                ) {
                    sampleSize *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                resolver.openInputStream(entry.uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            }.getOrNull()
        }

    private suspend fun listAllRecent(limit: Int): List<LocalMediaEntry> {
        val perBucket = (limit / 4).coerceIn(20, 60)
        val combined = buildList {
            addAll(quickPrefs.getRecentSelected())
            addAll(queryDownloadsAndFiles(workOnly = false, limit = perBucket))
            addAll(queryMediaCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*", false, perBucket))
            addAll(queryMediaCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*", true, perBucket))
            addAll(queryMediaCollection(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio/*", true, perBucket / 2))
            addAll(crawlPinnedFolders(maxPerFolder = 40))
        }
        return combined
            .distinctBy { it.uri.toString() }
            .sortedByDescending { it.dateModifiedSec }
            .take(limit)
    }

    private suspend fun listDocumentsAndPinned(workOnly: Boolean, limit: Int): List<LocalMediaEntry> {
        val downloads = queryDownloadsAndFiles(workOnly = workOnly, limit = limit)
        val pinned = crawlPinnedFolders(maxPerFolder = 80).filter { entry ->
            if (workOnly) isWorkFile(entry) else isDocumentOrArchiveFile(entry)
        }
        val recents = quickPrefs.getRecentSelected().filter { entry ->
            if (workOnly) isWorkFile(entry) else isDocumentOrArchiveFile(entry)
        }
        return (recents + downloads + pinned)
            .distinctBy { it.uri.toString() }
            .sortedByDescending { it.dateModifiedSec }
            .take(limit)
    }

    private suspend fun mergeWithRecents(
        primary: List<LocalMediaEntry>,
        recentFilter: (LocalMediaEntry) -> Boolean
    ): List<LocalMediaEntry> {
        val recents = quickPrefs.getRecentSelected().filter(recentFilter)
        val pinned = crawlPinnedFolders(maxPerFolder = 40).filter(recentFilter)
        return (recents + primary + pinned)
            .distinctBy { it.uri.toString() }
            .sortedByDescending { it.dateModifiedSec }
    }

    private suspend fun crawlPinnedFolders(maxPerFolder: Int): List<LocalMediaEntry> {
        val folders = quickPrefs.getPinnedFolders()
        if (folders.isEmpty()) return emptyList()
        val crawler = SafTreeCrawler(resolver)
        val nowSec = System.currentTimeMillis() / 1000L
        return buildList {
            for (folderUri in folders) {
                runCatching {
                    val items = crawler.crawl(folderUri).take(maxPerFolder).toList()
                    items.forEach { item ->
                        if (item.sizeBytes > 0L) {
                            val folderName = item.relativePath
                                .substringBeforeLast('/', "")
                                .substringAfterLast('/', "")
                                .ifBlank { "Pinned folder" }
                            add(
                                LocalMediaEntry(
                                    uri = Uri.parse(item.documentUriString),
                                    displayName = item.displayName,
                                    sizeBytes = item.sizeBytes,
                                    mimeType = item.mimeType.ifBlank { "application/octet-stream" },
                                    dateModifiedSec = nowSec,
                                    bucketName = folderName,
                                    sourceLabel = "Pinned folder"
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun queryDownloadsAndFiles(workOnly: Boolean, limit: Int): List<LocalMediaEntry> {
        val collections = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            }
            add(MediaStore.Files.getContentUri("external"))
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
        )
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        val results = mutableListOf<LocalMediaEntry>()
        for (collection in collections) {
            runCatching {
                resolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val mimeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    val dateIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    val bucketIdx = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                    var scanned = 0
                    while (cursor.moveToNext() && results.size < limit && scanned < limit * 4) {
                        scanned++
                        if (idIdx < 0) continue
                        val id = cursor.getLong(idIdx)
                        val name = (if (nameIdx >= 0) cursor.getString(nameIdx) else null) ?: "file_$id"
                        val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                        if (size <= 0L) continue
                        val mime = (if (mimeIdx >= 0) cursor.getString(mimeIdx) else null)
                            ?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                        val bucket = if (bucketIdx >= 0) cursor.getString(bucketIdx) else null
                        val entry = LocalMediaEntry(
                            uri = ContentUris.withAppendedId(collection, id),
                            displayName = name,
                            sizeBytes = size,
                            mimeType = mime,
                            dateModifiedSec = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L,
                            bucketName = bucket ?: "Downloads",
                            sourceLabel = if (collection != MediaStore.Files.getContentUri("external")) "Downloads" else "Files"
                        )
                        val matches = if (workOnly) isWorkFile(entry) else isDocumentOrArchiveFile(entry)
                        if (matches) {
                            results += entry
                        }
                    }
                }
            }
        }
        return results
    }

    private fun queryMediaCollection(
        collection: Uri,
        defaultMime: String,
        includeDuration: Boolean,
        limit: Int
    ): List<LocalMediaEntry> {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            if (includeDuration) add(MediaStore.MediaColumns.DURATION)
        }.toTypedArray()
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        val result = mutableListOf<LocalMediaEntry>()
        runCatching {
            resolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val mimeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val dateIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val bucketIdx = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val durIdx = if (includeDuration) cursor.getColumnIndex(MediaStore.MediaColumns.DURATION) else -1
                while (cursor.moveToNext() && result.size < limit) {
                    if (idIdx < 0) continue
                    val id = cursor.getLong(idIdx)
                    val name = (if (nameIdx >= 0) cursor.getString(nameIdx) else null) ?: "media_$id"
                    val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                    if (size <= 0L) continue
                    val mime = (if (mimeIdx >= 0) cursor.getString(mimeIdx) else null)
                        ?.takeIf { it.isNotBlank() } ?: defaultMime
                    val modified = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                    val bucket = if (bucketIdx >= 0) cursor.getString(bucketIdx) else null
                    val duration = if (durIdx >= 0) cursor.getLong(durIdx) else 0L
                    val uri = ContentUris.withAppendedId(collection, id)
                    result += LocalMediaEntry(
                        uri = uri,
                        displayName = name,
                        sizeBytes = size,
                        mimeType = mime,
                        dateModifiedSec = modified,
                        bucketName = bucket,
                        durationMs = duration,
                        sourceLabel = bucket
                    )
                }
            }
        }
        return result
    }

    private companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")
        private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "mov", "webm", "avi", "3gp")
        private val DOC_EXTENSIONS = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "csv", "md", "rtf", "odt", "ods", "odp",
            "json", "xml", "yaml", "yml", "epub", "srt", "vtt", "log"
        )
        private val WORK_EXTENSIONS = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "csv", "txt", "md", "rtf", "odt", "ods", "odp",
            "json", "xml", "yaml", "yml", "zip", "7z", "rar", "tar", "gz"
        )
        private val ARCHIVE_EXTENSIONS = setOf("zip", "7z", "rar", "tar", "gz", "apk", "xapk")

        private fun ext(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        private fun hasExtension(name: String, allowed: Set<String>): Boolean = ext(name) in allowed

        private fun isDocumentOrArchiveFile(entry: LocalMediaEntry): Boolean {
            val e = ext(entry.displayName)
            if (e in DOC_EXTENSIONS || e in ARCHIVE_EXTENSIONS) return true
            val m = entry.mimeType.lowercase(Locale.ROOT)
            return m == "application/pdf" ||
                m.startsWith("text/") ||
                m.contains("word") ||
                m.contains("excel") ||
                m.contains("spreadsheet") ||
                m.contains("powerpoint") ||
                m.contains("presentation") ||
                m.contains("opendocument") ||
                m == "application/zip" ||
                m == "application/json"
        }

        private fun isWorkFile(entry: LocalMediaEntry): Boolean {
            val e = ext(entry.displayName)
            if (e in WORK_EXTENSIONS) return true
            val m = entry.mimeType.lowercase(Locale.ROOT)
            return m == "application/pdf" ||
                m == "text/csv" ||
                m == "text/plain" ||
                m == "application/json" ||
                m.contains("word") ||
                m.contains("excel") ||
                m.contains("spreadsheet") ||
                m.contains("powerpoint") ||
                m.contains("presentation") ||
                m.contains("opendocument")
        }
    }
}
