package dev.glide.data.source

import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.TransferFailure
import dev.glide.core.ports.SourceAccessException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream

class FakeContentResolverWrapper : ContentResolverWrapper {
    var metadataResult: SourceQueryMetadata? = null
    var streamProvider: (() -> InputStream)? = null
    var mimeType: String? = null
    val persistedPermissions = mutableListOf<Pair<String, Int>>()

    override fun queryMetadata(uriString: String): SourceQueryMetadata? = metadataResult

    override fun openInputStream(uriString: String): InputStream? {
        return streamProvider?.invoke()
    }

    override fun getType(uriString: String): String? = mimeType

    override fun takePersistableUriPermission(uriString: String, modeFlags: Int) {
        persistedPermissions.add(uriString to modeFlags)
    }
}

class AndroidSourceAccessTest {

    private val validUri = "content://com.android.providers.media/documents/document/123"
    private val handle = SourceHandle(validUri)

    @Test
    fun validateSource_validContentUri_returnsSanitizedMetadata() = runTest {
        val fakeResolver = FakeContentResolverWrapper().apply {
            metadataResult = SourceQueryMetadata(displayName = "photo.jpg", sizeBytes = 2048L)
            mimeType = "image/jpeg"
            streamProvider = { ByteArrayInputStream(ByteArray(2048)) }
        }
        val sourceAccess = AndroidSourceAccess(fakeResolver)

        val metadata = sourceAccess.validateSource(handle)

        assertEquals("photo.jpg", metadata.name)
        assertEquals("image/jpeg", metadata.mimeType)
        assertEquals(2048L, metadata.sizeBytes)
        assertEquals(1, fakeResolver.persistedPermissions.size)
    }

    @Test
    fun validateSource_unknownSize_throwsSourceUnavailable() = runTest {
        val fakeResolver = FakeContentResolverWrapper().apply {
            metadataResult = SourceQueryMetadata(displayName = "stream.bin", sizeBytes = null)
            mimeType = "application/octet-stream"
            streamProvider = { ByteArrayInputStream(ByteArray(10)) }
        }
        val sourceAccess = AndroidSourceAccess(fakeResolver)

        try {
            sourceAccess.validateSource(handle)
            fail("Expected SourceAccessException for null size")
        } catch (e: SourceAccessException) {
            assertTrue(e.failure is TransferFailure.SourceUnavailable)
            assertTrue(e.message!!.contains("Unknown source size is unsupported"))
        }
    }

    @Test
    fun validateSource_unknownSize_withStager_stagesAndReturnsValidMetadata() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("glide_asa_test").toFile()
        try {
            val payload = ByteArray(4096) { 7 }
            val fakeResolver = FakeContentResolverWrapper().apply {
                metadataResult = SourceQueryMetadata(displayName = "cloud_doc.pdf", sizeBytes = null)
                mimeType = "application/pdf"
                streamProvider = { ByteArrayInputStream(payload) }
            }
            val stager = BoundedSourceStager(
                stagingDir = tempDir,
                resolverWrapper = fakeResolver,
                spaceProvider = { 1024 * 1024 * 1024L }
            )
            val sourceAccess = AndroidSourceAccess(fakeResolver, stager = stager)

            var reportedBytes = 0L
            val metadata = sourceAccess.validateSource(handle) { reportedBytes = it }

            assertEquals("cloud_doc.pdf", metadata.name)
            assertEquals("application/pdf", metadata.mimeType)
            assertEquals(4096L, metadata.sizeBytes)
            assertEquals(4096L, reportedBytes)

            // Stream opens from staged file
            val stream = sourceAccess.openSource(handle)
            assertEquals(4096, stream.readBytes().size)
            stream.close()

            sourceAccess.cleanupAllStaged()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun validateSource_oversizedFile_throwsLimitExceeded() = runTest {
        val oversizedBytes = ProtocolLimits.MAX_ITEM_BYTES + 1L
        val fakeResolver = FakeContentResolverWrapper().apply {
            metadataResult = SourceQueryMetadata(displayName = "huge.iso", sizeBytes = oversizedBytes)
            mimeType = "application/octet-stream"
            streamProvider = { ByteArrayInputStream(ByteArray(0)) }
        }
        val sourceAccess = AndroidSourceAccess(fakeResolver)

        try {
            sourceAccess.validateSource(handle)
            fail("Expected SourceAccessException for >64GiB file")
        } catch (e: SourceAccessException) {
            assertTrue(e.failure is TransferFailure.LimitExceeded)
            assertTrue(e.message!!.contains("exceeds maximum limit"))
        }
    }

    @Test
    fun validateSource_revokedPermission_throwsSourceUnavailable() = runTest {
        val fakeResolver = FakeContentResolverWrapper().apply {
            metadataResult = SourceQueryMetadata(displayName = "secret.pdf", sizeBytes = 100L)
            mimeType = "application/pdf"
            streamProvider = { throw SecurityException("Permission Denial") }
        }
        val sourceAccess = AndroidSourceAccess(fakeResolver)

        try {
            sourceAccess.validateSource(handle)
            fail("Expected SourceAccessException on revoked permission")
        } catch (e: SourceAccessException) {
            assertTrue(e.failure is TransferFailure.SourceUnavailable)
            assertTrue(e.message!!.contains("Access denied or permission revoked"))
        }
    }

    @Test
    fun validateSource_deletedFile_throwsSourceUnavailable() = runTest {
        val fakeResolver = FakeContentResolverWrapper().apply {
            metadataResult = SourceQueryMetadata(displayName = "missing.txt", sizeBytes = 100L)
            mimeType = "text/plain"
            streamProvider = { throw FileNotFoundException("File not found on disk") }
        }
        val sourceAccess = AndroidSourceAccess(fakeResolver)

        try {
            sourceAccess.validateSource(handle)
            fail("Expected SourceAccessException for deleted file")
        } catch (e: SourceAccessException) {
            assertTrue(e.failure is TransferFailure.SourceUnavailable)
            assertTrue(e.message!!.contains("Source file not found or deleted"))
        }
    }

    @Test
    fun validateSource_unsupportedScheme_throwsSourceUnavailable() = runTest {
        val fakeResolver = FakeContentResolverWrapper()
        val sourceAccess = AndroidSourceAccess(fakeResolver)

        try {
            sourceAccess.validateSource(SourceHandle("http://evil.com/payload.apk"))
            fail("Expected SourceAccessException for non-content scheme")
        } catch (e: SourceAccessException) {
            assertTrue(e.failure is TransferFailure.SourceUnavailable)
            assertTrue(e.message!!.contains("Unsupported URI scheme"))
        }
    }

    @Test
    fun validateSource_sanitizesBidiAndPathTraversal() = runTest {
        val dangerousName = "../../sub\\folder/\u202Ereversed.doc"
        val fakeResolver = FakeContentResolverWrapper().apply {
            metadataResult = SourceQueryMetadata(displayName = dangerousName, sizeBytes = 500L)
            mimeType = "application/msword"
            streamProvider = { ByteArrayInputStream(ByteArray(500)) }
        }
        val sourceAccess = AndroidSourceAccess(fakeResolver)

        val metadata = sourceAccess.validateSource(handle)
        // Should not contain traversal characters (../ or ..\) or bidi controls
        assertTrue(!metadata.name.contains("../"))
        assertTrue(!metadata.name.contains("..\\"))
        assertTrue(!metadata.name.contains("\u202E"))
    }

    @Test
    fun openSource_validHandle_returnsStream() = runTest {
        val content = "Open source test payload".toByteArray(Charsets.UTF_8)
        val fakeResolver = FakeContentResolverWrapper().apply {
            streamProvider = { ByteArrayInputStream(content) }
        }
        val sourceAccess = AndroidSourceAccess(fakeResolver)

        val stream = sourceAccess.openSource(handle)
        assertNotNull(stream)
        val readBytes = stream.readBytes()
        assertEquals(content.decodeToString(), readBytes.decodeToString())
    }

    @Test
    fun openSource_revokedHandle_throwsSourceUnavailable() = runTest {
        val fakeResolver = FakeContentResolverWrapper().apply {
            streamProvider = { throw SecurityException("Grant revoked") }
        }
        val sourceAccess = AndroidSourceAccess(fakeResolver)

        try {
            sourceAccess.openSource(handle)
            fail("Expected SourceAccessException on revoked stream")
        } catch (e: SourceAccessException) {
            assertTrue(e.failure is TransferFailure.SourceUnavailable)
            assertTrue(e.message!!.contains("Permission revoked"))
        }
    }
}
