package dev.glide.data.source

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight off-main-thread store for user-pinned quick-preview SAF folders and recently
 * selected local file URIs so the Send picker offers instant quick previews without broad file
 * permissions.
 */
class QuickSelectionPrefsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun getPinnedFolders(): List<String> = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY_PINNED_FOLDERS, null) ?: return@withContext emptyList()
        runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val value = array.optString(i).trim()
                    if (value.isNotEmpty() && !contains(value)) add(value)
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun addPinnedFolder(treeUriString: String): Unit = withContext(Dispatchers.IO) {
        val trimmed = treeUriString.trim()
        if (trimmed.isEmpty()) return@withContext
        val current = getPinnedFolders().toMutableList()
        current.remove(trimmed)
        current.add(0, trimmed)
        val array = JSONArray()
        current.take(MAX_PINNED_FOLDERS).forEach { array.put(it) }
        prefs.edit().putString(KEY_PINNED_FOLDERS, array.toString()).apply()
    }

    suspend fun removePinnedFolder(treeUriString: String): Unit = withContext(Dispatchers.IO) {
        val current = getPinnedFolders().filterNot { it == treeUriString.trim() }
        val array = JSONArray()
        current.forEach { array.put(it) }
        prefs.edit().putString(KEY_PINNED_FOLDERS, array.toString()).apply()
    }

    suspend fun getRecentSelected(): List<LocalMediaEntry> = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY_RECENT_FILES, null) ?: return@withContext emptyList()
        runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val uriStr = obj.optString("uri").trim()
                    val name = obj.optString("name").trim()
                    if (uriStr.isEmpty() || name.isEmpty()) continue
                    add(
                        LocalMediaEntry(
                            uri = Uri.parse(uriStr),
                            displayName = name,
                            sizeBytes = obj.optLong("size", 0L),
                            mimeType = obj.optString("mime").ifBlank { "application/octet-stream" },
                            dateModifiedSec = obj.optLong("ts", 0L),
                            bucketName = obj.optString("bucket").takeIf { it.isNotBlank() },
                            durationMs = obj.optLong("duration", 0L),
                            sourceLabel = obj.optString("source").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun recordSelected(entries: List<LocalMediaEntry>): Unit = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext
        val existing = getRecentSelected().toMutableList()
        for (entry in entries) {
            existing.removeAll { it.uri == entry.uri }
            existing.add(0, entry)
        }
        val array = JSONArray()
        existing.take(MAX_RECENT_FILES).forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("uri", entry.uri.toString())
                    put("name", entry.displayName)
                    put("size", entry.sizeBytes)
                    put("mime", entry.mimeType)
                    put("ts", entry.dateModifiedSec)
                    if (!entry.bucketName.isNullOrBlank()) put("bucket", entry.bucketName)
                    if (entry.durationMs > 0L) put("duration", entry.durationMs)
                    if (!entry.sourceLabel.isNullOrBlank()) put("source", entry.sourceLabel)
                }
            )
        }
        prefs.edit().putString(KEY_RECENT_FILES, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "glide_quick_selection"
        private const val KEY_PINNED_FOLDERS = "pinned_folders_v1"
        private const val KEY_RECENT_FILES = "recent_files_v1"
        private const val MAX_PINNED_FOLDERS = 12
        private const val MAX_RECENT_FILES = 50
    }
}
