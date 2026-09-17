package dev.glide.data.source

import dev.glide.core.domain.ProtocolLimits
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FakeSafTreeQueryAdapter : SafTreeQueryAdapter {
    var treeDocId: String = "primary:Documents/MyFolder"
    var rootNode: SafDocumentNode? = SafDocumentNode("primary:Documents/MyFolder", "MyFolder", "vnd.android.document/directory", 0, true)
    val childrenMap = mutableMapOf<String, MutableList<SafDocumentNode>>()

    override fun getTreeDocumentId(treeUriString: String): String = treeDocId

    override fun queryDocument(treeUriString: String, documentId: String): SafDocumentNode? =
        if (documentId == treeDocId) rootNode else childrenMap.values.flatten().find { it.documentId == documentId }

    override fun queryChildren(treeUriString: String, parentDocumentId: String): List<SafDocumentNode> =
        childrenMap[parentDocumentId] ?: emptyList()

    override fun buildDocumentUri(treeUriString: String, documentId: String): String =
        "content://com.android.externalstorage.documents/tree/primary%3AMyFolder/document/$documentId"
}

class SafTreeCrawlerTest {

    private val fakeTreeUri = "content://com.android.externalstorage.documents/tree/primary%3AMyFolder"

    @Test
    fun crawl_flatAndNestedFiles_producesCorrectSafeRelativePaths() = runTest {
        val adapter = FakeSafTreeQueryAdapter()
        val rootId = adapter.treeDocId

        adapter.childrenMap[rootId] = mutableListOf(
            SafDocumentNode("doc_1", "file1.txt", "text/plain", 100L, false),
            SafDocumentNode("doc_dir", "sub", "vnd.android.document/directory", 0L, true)
        )
        adapter.childrenMap["doc_dir"] = mutableListOf(
            SafDocumentNode("doc_2", "file2.png", "image/png", 200L, false)
        )

        val crawler = SafTreeCrawler(adapter)
        val items = crawler.crawl(fakeTreeUri).toList()

        assertEquals(2, items.size)
        assertEquals("MyFolder/file1.txt", items[0].relativePath)
        assertEquals(100L, items[0].sizeBytes)
        assertEquals("file1.txt", items[0].displayName)

        assertEquals("MyFolder/sub/file2.png", items[1].relativePath)
        assertEquals(200L, items[1].sizeBytes)
        assertEquals("file2.png", items[1].displayName)
    }

    @Test
    fun crawl_cyclicDirectory_skipsLoopWithoutHanging() = runTest {
        val adapter = FakeSafTreeQueryAdapter()
        val rootId = adapter.treeDocId

        // doc_dir points back to root
        adapter.childrenMap[rootId] = mutableListOf(
            SafDocumentNode("doc_file", "valid.txt", "text/plain", 50L, false),
            SafDocumentNode("doc_dir", "loop", "vnd.android.document/directory", 0L, true)
        )
        adapter.childrenMap["doc_dir"] = mutableListOf(
            SafDocumentNode(rootId, "back_to_root", "vnd.android.document/directory", 0L, true),
            SafDocumentNode("doc_nested", "nested.txt", "text/plain", 75L, false)
        )

        val crawler = SafTreeCrawler(adapter)
        val items = crawler.crawl(fakeTreeUri).toList()

        assertEquals(2, items.size)
        assertEquals("MyFolder/valid.txt", items[0].relativePath)
        assertEquals("MyFolder/loop/nested.txt", items[1].relativePath)
    }

    @Test
    fun crawl_exceedsDepthLimit_throwsSafTraversalException() = runTest {
        val adapter = FakeSafTreeQueryAdapter()
        var currentParent = adapter.treeDocId

        // Build a chain of 17 directories (depth 17 > MAX_FOLDER_DEPTH 16)
        for (i in 1..18) {
            val dirId = "dir_$i"
            adapter.childrenMap[currentParent] = mutableListOf(
                SafDocumentNode(dirId, "level$i", "vnd.android.document/directory", 0L, true)
            )
            currentParent = dirId
        }

        val crawler = SafTreeCrawler(adapter)
        try {
            crawler.crawl(fakeTreeUri).toList()
            fail("Expected SafTraversalException for exceeding depth limit")
        } catch (e: SafTraversalException) {
            assertTrue(e.message?.contains("recursion depth exceeded") == true)
        }
    }

    @Test
    fun crawl_exceedsFileLimit_throwsSafTraversalException() = runTest {
        val adapter = FakeSafTreeQueryAdapter()
        val rootId = adapter.treeDocId

        val fileList = mutableListOf<SafDocumentNode>()
        for (i in 1..(ProtocolLimits.MAX_FOLDER_FILES + 1)) {
            fileList.add(SafDocumentNode("file_$i", "f$i.txt", "text/plain", 10L, false))
        }
        adapter.childrenMap[rootId] = fileList

        val crawler = SafTreeCrawler(adapter)
        try {
            crawler.crawl(fakeTreeUri).toList()
            fail("Expected SafTraversalException for exceeding file limit")
        } catch (e: SafTraversalException) {
            assertTrue(e.message?.contains("File count exceeded") == true)
        }
    }

    @Test
    fun crawl_exceedsDirectoryLimit_throwsSafTraversalException() = runTest {
        val adapter = FakeSafTreeQueryAdapter()
        val rootId = adapter.treeDocId

        val dirList = mutableListOf<SafDocumentNode>()
        for (i in 1..(ProtocolLimits.MAX_FOLDER_DIRECTORIES + 1)) {
            dirList.add(SafDocumentNode("dir_$i", "d$i", "vnd.android.document/directory", 0L, true))
        }
        adapter.childrenMap[rootId] = dirList

        val crawler = SafTreeCrawler(adapter)
        try {
            crawler.crawl(fakeTreeUri).toList()
            fail("Expected SafTraversalException for exceeding directory limit")
        } catch (e: SafTraversalException) {
            assertTrue(e.message?.contains("Directory count exceeded") == true)
        }
    }
}
