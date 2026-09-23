package dev.glide.data.source

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.folder.SafeRelativePath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.ArrayDeque

/**
 * Metadata node retrieved from SAF tree traversal.
 */
data class SafDocumentNode(
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val isDirectory: Boolean
)

/**
 * A verified file discovered during SAF directory traversal.
 */
data class CrawledDocumentItem(
    val documentUriString: String,
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val relativePath: String
) {
    val documentUri: Uri?
        get() = try {
            Uri.parse(documentUriString)
        } catch (_: Exception) {
            null
        }
}

/**
 * Thrown when SAF tree traversal violates security or capacity limits.
 */
class SafTraversalException(message: String) : RuntimeException(message)

/**
 * Abstraction for querying SAF tree documents and children via ContentResolver.
 */
interface SafTreeQueryAdapter {
    fun getTreeDocumentId(treeUriString: String): String
    fun queryDocument(treeUriString: String, documentId: String): SafDocumentNode?
    fun queryChildren(treeUriString: String, parentDocumentId: String): List<SafDocumentNode>
    fun buildDocumentUri(treeUriString: String, documentId: String): String
}

/**
 * Production implementation of [SafTreeQueryAdapter] using Android [DocumentsContract] and [ContentResolver].
 */
class DefaultSafTreeQueryAdapter(
    private val contentResolver: ContentResolver
) : SafTreeQueryAdapter {

    override fun getTreeDocumentId(treeUriString: String): String {
        return DocumentsContract.getTreeDocumentId(Uri.parse(treeUriString))
    }

    override fun queryDocument(treeUriString: String, documentId: String): SafDocumentNode? {
        val treeUri = try { Uri.parse(treeUriString) } catch (_: Exception) { return null }
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        return try {
            contentResolver.query(docUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                    val id = if (idIdx >= 0) cursor.getString(idIdx) else documentId
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "unknown" else "unknown"
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) ?: "application/octet-stream" else "application/octet-stream"
                    val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR

                    SafDocumentNode(id, name, mime, size, isDir)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun queryChildren(treeUriString: String, parentDocumentId: String): List<SafDocumentNode> {
        val treeUri = try { Uri.parse(treeUriString) } catch (_: Exception) { return emptyList() }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val result = mutableListOf<SafDocumentNode>()
        try {
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                while (cursor.moveToNext()) {
                    val id = if (idIdx >= 0) cursor.getString(idIdx) else continue
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "unnamed" else "unnamed"
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) ?: "application/octet-stream" else "application/octet-stream"
                    val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR

                    result.add(SafDocumentNode(id, name, mime, size, isDir))
                }
            }
        } catch (_: Exception) {
            // Cursor failure: return whatever was collected
        }
        return result
    }

    override fun buildDocumentUri(treeUriString: String, documentId: String): String {
        val treeUri = Uri.parse(treeUriString)
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString()
    }
}

/**
 * High-performance SAF directory crawler using direct ContentResolver child document queries.
 *
 * Enforces ADR-0014 and SAFE_FOLDER_TRANSFER_SPECIFICATION requirements:
 * 1. Low-level DocumentsContract child queries bypassing DocumentFile.listFiles().
 * 2. Loop detection and cycle prevention via visited documentId set.
 * 3. Bounded recursion depth <= 16, files <= 10,000, and directories <= 1,000.
 * 4. Validation of relative paths via [SafeRelativePath].
 * 5. Memory bounded streaming via buffered Flow.
 */
class SafTreeCrawler(
    private val adapter: SafTreeQueryAdapter,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(contentResolver: ContentResolver) : this(
        adapter = DefaultSafTreeQueryAdapter(contentResolver)
    )

    fun crawl(treeUri: Uri, overrideRootName: String? = null): Flow<CrawledDocumentItem> =
        crawl(treeUri.toString(), overrideRootName)

    /**
     * Crawls the SAF directory tree rooted at [treeUriString] and emits [CrawledDocumentItem] entries.
     */
    fun crawl(treeUriString: String, overrideRootName: String? = null): Flow<CrawledDocumentItem> = flow {
        val rootDocId = adapter.getTreeDocumentId(treeUriString)
        val rootNode = adapter.queryDocument(treeUriString, rootDocId)
        val rawRootName = overrideRootName
            ?: rootNode?.displayName?.takeIf { it.isNotBlank() }
            ?: rootDocId.substringAfterLast(':').substringAfterLast('/').ifBlank { "Folder" }

        val sanitizedRootName = FilenameSanitizer.sanitize(rawRootName)

        val visitedDocIds = HashSet<String>()
        visitedDocIds.add(rootDocId)

        var totalFiles = 0
        var totalDirectories = 0

        data class DirectoryQueueItem(
            val documentId: String,
            val currentRelativeDir: String,
            val depth: Int
        )

        val queue = ArrayDeque<DirectoryQueueItem>()
        queue.add(DirectoryQueueItem(rootDocId, sanitizedRootName, 0))

        while (!queue.isEmpty()) {
            val current = queue.removeFirst()

            val children = adapter.queryChildren(treeUriString, current.documentId)
            for (child in children) {
                if (child.isDirectory) {
                    if (visitedDocIds.contains(child.documentId)) {
                        // Cyclic link detected — skip to avoid infinite loop
                        continue
                    }
                    totalDirectories++
                    if (totalDirectories > ProtocolLimits.MAX_FOLDER_DIRECTORIES) {
                        throw SafTraversalException(
                            "Directory count exceeded maximum allowed limit of ${ProtocolLimits.MAX_FOLDER_DIRECTORIES}"
                        )
                    }

                    val nextDepth = current.depth + 1
                    if (nextDepth > ProtocolLimits.MAX_FOLDER_DEPTH) {
                        throw SafTraversalException(
                            "Folder recursion depth exceeded maximum allowed limit of ${ProtocolLimits.MAX_FOLDER_DEPTH} (at depth $nextDepth)"
                        )
                    }

                    visitedDocIds.add(child.documentId)
                    val sanitizedDirName = FilenameSanitizer.sanitize(child.displayName)
                    val nextRelativeDir = "${current.currentRelativeDir}/$sanitizedDirName"
                    queue.add(DirectoryQueueItem(child.documentId, nextRelativeDir, nextDepth))
                } else {
                    totalFiles++
                    if (totalFiles > ProtocolLimits.MAX_FOLDER_FILES) {
                        throw SafTraversalException(
                            "File count exceeded maximum allowed limit of ${ProtocolLimits.MAX_FOLDER_FILES}"
                        )
                    }

                    val sanitizedFileName = FilenameSanitizer.sanitize(child.displayName)
                    val candidateRelativePath = "${current.currentRelativeDir}/$sanitizedFileName"

                    val safePathResult = SafeRelativePath.parse(candidateRelativePath)
                    if (safePathResult.isFailure) {
                        // Skip items that violate SafeRelativePath rules
                        continue
                    }

                    val docUriString = adapter.buildDocumentUri(treeUriString, child.documentId)
                    emit(
                        CrawledDocumentItem(
                            documentUriString = docUriString,
                            documentId = child.documentId,
                            displayName = sanitizedFileName,
                            mimeType = child.mimeType,
                            sizeBytes = child.sizeBytes,
                            relativePath = safePathResult.getOrThrow().normalizedPath
                        )
                    )
                }
            }
        }
    }.buffer(64).flowOn(ioDispatcher)
}
