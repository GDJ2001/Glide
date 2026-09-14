package dev.glide.data.export

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

class FakeMediaStoreExportAdapter : MediaStoreExportAdapter {
    val existingDownloadNames = mutableSetOf<String>()
    val insertedPending = mutableListOf<Pair<String, String>>()
    val completedUris = mutableSetOf<String>()
    val deletedUris = mutableSetOf<String>()
    val outputStreams = mutableMapOf<String, ByteArrayOutputStream>()

    var availableStorageBytes: Long = 100L * 1024L * 1024L // 100 MB default
    var shouldFailInsert: Boolean = false
    var shouldFailStream: Boolean = false
    var shouldFailComplete: Boolean = false
    var shouldThrowOnDelete: Boolean = false
    var shouldFailDelete: Boolean = false
    var streamWriteLimit: Int = Int.MAX_VALUE
    var uriCounter = 1

    override fun queryExistingDownloadNames(): Set<String> = existingDownloadNames.toSet()

    override fun insertPendingDownload(displayName: String, mimeType: String): String? {
        if (shouldFailInsert) return null
        insertedPending.add(displayName to mimeType)
        val uri = "content://media/external/downloads/${uriCounter++}"
        outputStreams[uri] = ByteArrayOutputStream()
        return uri
    }

    override fun openOutputStream(uriString: String): OutputStream? {
        if (shouldFailStream) return null
        val baos = outputStreams[uriString] ?: ByteArrayOutputStream().also { outputStreams[uriString] = it }
        return if (streamWriteLimit < Int.MAX_VALUE) {
            object : OutputStream() {
                private var bytesWritten = 0
                override fun write(b: Int) {
                    if (bytesWritten >= streamWriteLimit) {
                        throw IOException("ENOSPC: No space left on device")
                    }
                    baos.write(b)
                    bytesWritten++
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (bytesWritten + len > streamWriteLimit) {
                        val canWrite = (streamWriteLimit - bytesWritten).coerceAtLeast(0)
                        if (canWrite > 0) {
                            baos.write(b, off, canWrite)
                            bytesWritten += canWrite
                        }
                        throw IOException("ENOSPC: No space left on device")
                    }
                    baos.write(b, off, len)
                    bytesWritten += len
                }
            }
        } else {
            baos
        }
    }

    override fun markPendingComplete(uriString: String): Boolean {
        if (shouldFailComplete) return false
        completedUris.add(uriString)
        return true
    }

    override fun delete(uriString: String): Boolean {
        if (shouldThrowOnDelete) throw RuntimeException("Provider crashed or refused delete")
        if (shouldFailDelete) return false
        deletedUris.add(uriString)
        outputStreams.remove(uriString)
        return true
    }

    override fun getAvailableBytes(): Long = availableStorageBytes
}

class FileExporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeAdapter: FakeMediaStoreExportAdapter
    private lateinit var sourceFile: File

    @Before
    fun setup() {
        fakeAdapter = FakeMediaStoreExportAdapter()
        sourceFile = tempFolder.newFile("verified_private_copy.bin")
        FileOutputStream(sourceFile).use { fos ->
            fos.write("Verified payload bytes for Glide test".toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun `exportToDownloads happy path on API 29+`() = runTest {
        val exporter = AndroidFileExporter(
            adapter = fakeAdapter,
            sdkInt = 29
        )

        val result = exporter.exportToDownloads(
            sourceFile = sourceFile,
            displayName = "My Document.txt",
            mimeType = "text/plain"
        )

        assertTrue(result is ExportResult.Success)
        val success = result as ExportResult.Success
        assertEquals("My Document.txt", success.destinationName)
        assertEquals(sourceFile.length(), success.bytesExported)
        assertTrue(fakeAdapter.completedUris.contains(success.destinationUriString))
        assertFalse(fakeAdapter.deletedUris.contains(success.destinationUriString))

        // Verify content matched exactly
        val writtenBytes = fakeAdapter.outputStreams[success.destinationUriString]?.toByteArray()
        assertArrayEquals(sourceFile.readBytes(), writtenBytes)

        // Verify original verified private copy is completely intact
        assertTrue(sourceFile.exists())
        assertEquals("Verified payload bytes for Glide test", sourceFile.readText())
    }

    @Test
    fun `exportToDownloads safe duplicate naming resolution`() = runTest {
        fakeAdapter.existingDownloadNames.add("report.pdf")
        fakeAdapter.existingDownloadNames.add("report (1).pdf")

        val exporter = AndroidFileExporter(
            adapter = fakeAdapter,
            sdkInt = 37 // Android 17 / API 37
        )

        val result = exporter.exportToDownloads(
            sourceFile = sourceFile,
            displayName = "report.pdf",
            mimeType = "application/pdf"
        )

        assertTrue(result is ExportResult.Success)
        val success = result as ExportResult.Success
        assertEquals("report (2).pdf", success.destinationName)
    }

    @Test
    fun `exportToDownloads sanitizes Windows reserved names`() = runTest {
        val exporter = AndroidFileExporter(
            adapter = fakeAdapter,
            sdkInt = 34
        )

        val result = exporter.exportToDownloads(
            sourceFile = sourceFile,
            displayName = "CON.txt",
            mimeType = "text/plain"
        )

        assertTrue(result is ExportResult.Success)
        val success = result as ExportResult.Success
        assertEquals("_CON.txt", success.destinationName)
    }

    @Test
    fun `exportToDownloads preserves original private copy on write failure and deletes pending`() = runTest {
        fakeAdapter.shouldFailComplete = true // Fails when clearing IS_PENDING

        val exporter = AndroidFileExporter(
            adapter = fakeAdapter,
            sdkInt = 30
        )

        val result = exporter.exportToDownloads(
            sourceFile = sourceFile,
            displayName = "document.pdf",
            mimeType = "application/pdf"
        )

        assertTrue(result is ExportResult.Failure)
        val failure = result as ExportResult.Failure
        assertTrue(failure.partialDeleted)
        assertEquals(1, fakeAdapter.deletedUris.size)

        // CRITICAL: original verified private copy survives export failure!
        assertTrue(sourceFile.exists())
        assertEquals("Verified payload bytes for Glide test", sourceFile.readText())
    }

    @Test
    fun `exportToDownloads reports partial not deleted when provider refuses delete`() = runTest {
        fakeAdapter.shouldFailComplete = true
        fakeAdapter.shouldThrowOnDelete = true

        val exporter = AndroidFileExporter(
            adapter = fakeAdapter,
            sdkInt = 30
        )

        val result = exporter.exportToDownloads(
            sourceFile = sourceFile,
            displayName = "document.pdf"
        )

        assertTrue(result is ExportResult.Failure)
        val failure = result as ExportResult.Failure
        assertFalse(failure.partialDeleted) // Discloses provider refusal to delete

        // Original private copy remains completely intact
        assertTrue(sourceFile.exists())
    }

    @Test
    fun `exportToDownloads fails precheck on low storage space`() = runTest {
        fakeAdapter.availableStorageBytes = 5L // Only 5 bytes available, source is ~37 bytes

        val exporter = AndroidFileExporter(
            adapter = fakeAdapter,
            sdkInt = 34
        )

        val result = exporter.exportToDownloads(
            sourceFile = sourceFile,
            displayName = "large_archive.zip"
        )

        assertTrue(result is ExportResult.Failure)
        val failure = result as ExportResult.Failure
        assertTrue(failure.message.contains("Insufficient storage"))
        assertEquals(0, fakeAdapter.insertedPending.size) // Never even inserted pending
        assertTrue(sourceFile.exists())
    }

    @Test
    fun `exportToDownloads in-flight ENOSPC cleans up and preserves source`() = runTest {
        fakeAdapter.streamWriteLimit = 10 // Simulates disk running out after 10 bytes

        val exporter = AndroidFileExporter(
            adapter = fakeAdapter,
            sdkInt = 34
        )

        val result = exporter.exportToDownloads(
            sourceFile = sourceFile,
            displayName = "image.png"
        )

        assertTrue(result is ExportResult.Failure)
        val failure = result as ExportResult.Failure
        assertTrue(failure.partialDeleted)
        assertEquals(1, fakeAdapter.deletedUris.size)
        assertTrue(sourceFile.exists())
        assertEquals("Verified payload bytes for Glide test", sourceFile.readText())
    }

    @Test
    fun `exportToDownloads rejects direct Downloads on API 26-28`() = runTest {
        val exporter = AndroidFileExporter(
            adapter = fakeAdapter,
            sdkInt = 28 // API 28 Pie
        )

        val result = exporter.exportToDownloads(
            sourceFile = sourceFile,
            displayName = "legacy_test.txt"
        )

        assertTrue(result is ExportResult.Failure)
        val failure = result as ExportResult.Failure
        assertTrue(failure.message.contains("requires Android 10+"))
        assertEquals(0, fakeAdapter.insertedPending.size)
        assertTrue(sourceFile.exists())
    }

    @Test
    fun `exportToUri streams to custom SAF destination on all API versions`() = runTest {
        val targetUri = "content://com.android.externalstorage.documents/document/primary%3ATest.bin"
        val exporter = AndroidFileExporter(
            adapter = fakeAdapter,
            sdkInt = 26
        )

        val result = exporter.exportToUri(
            sourceFile = sourceFile,
            destinationUriString = targetUri,
            displayName = "custom:name.bin"
        )

        assertTrue(result is ExportResult.Success)
        val success = result as ExportResult.Success
        assertEquals("custom_name.bin", success.destinationName)
        assertEquals(sourceFile.length(), success.bytesExported)
        assertArrayEquals(sourceFile.readBytes(), fakeAdapter.outputStreams[targetUri]?.toByteArray())
        assertTrue(sourceFile.exists())
    }

    @Test
    fun `exportToUri cleans partial file on streaming failure`() = runTest {
        fakeAdapter.streamWriteLimit = 5
        var uriDeleteCalled = false
        val targetUri = "content://com.android.externalstorage.documents/document/123"

        val exporter = AndroidFileExporter(
            adapter = fakeAdapter,
            sdkInt = 26,
            uriDeleteHandler = {
                uriDeleteCalled = true
                true
            }
        )

        val result = exporter.exportToUri(
            sourceFile = sourceFile,
            destinationUriString = targetUri,
            displayName = "failing_doc.txt"
        )

        assertTrue(result is ExportResult.Failure)
        assertTrue(uriDeleteCalled)
        assertTrue(sourceFile.exists())
    }
}
