package dev.glide.app.ui.selection

import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.SourceHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionUiTest {

    @Test
    fun formatFileSize_variousSizes_formattedAccurately() {
        assertEquals("0 B", formatFileSize(0L))
        assertEquals("512 B", formatFileSize(512L))
        assertEquals("1023 B", formatFileSize(1023L))
        assertEquals("1.0 KB", formatFileSize(1024L))
        assertEquals("1.5 KB", formatFileSize(1536L))
        assertEquals("10.0 MB", formatFileSize(10 * 1024 * 1024L))
        assertEquals("1.0 GB", formatFileSize(1024 * 1024 * 1024L))
    }

    @Test
    fun hashingState_progressFraction_computedCorrectly() {
        val zeroState = SelectionUiState.Hashing("file.txt", sizeBytes = 0L, bytesHashed = 0L)
        assertEquals(0f, zeroState.progressFraction, 0.001f)

        val halfState = SelectionUiState.Hashing("file.txt", sizeBytes = 1000L, bytesHashed = 500L)
        assertEquals(0.5f, halfState.progressFraction, 0.001f)

        val fullState = SelectionUiState.Hashing("file.txt", sizeBytes = 1000L, bytesHashed = 1000L)
        assertEquals(1.0f, fullState.progressFraction, 0.001f)

        val clampedState = SelectionUiState.Hashing("file.txt", sizeBytes = 1000L, bytesHashed = 1500L)
        assertEquals(1.0f, clampedState.progressFraction, 0.001f)
    }

    @Test
    fun readyState_propertiesAreAccurate() {
        val digest = Sha256Digest("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        val handle = SourceHandle("content://media/123")
        val state = SelectionUiState.Ready(
            displayName = "archive.zip",
            sizeBytes = 1048576L,
            mimeType = "application/zip",
            digest = digest,
            handle = handle
        )

        assertEquals("archive.zip", state.displayName)
        assertEquals(1048576L, state.sizeBytes)
        assertEquals("application/zip", state.mimeType)
        assertEquals(digest, state.digest)
        assertEquals(handle, state.handle)
    }

    @Test
    fun errorState_preservesMessageAndRecoverability() {
        val recoverableError = SelectionUiState.Error("File not found", isRecoverable = true)
        assertEquals("File not found", recoverableError.message)
        assertTrue(recoverableError.isRecoverable)

        val unrecoverableError = SelectionUiState.Error("Corrupt storage", isRecoverable = false)
        assertFalse(unrecoverableError.isRecoverable)
    }

    @Test
    fun stagingSourceState_preservesPropertiesAccurately() {
        val stagingState = SelectionUiState.StagingSource(
            displayName = "cloud_video.mp4",
            bytesStaged = 65536L,
            isCloudProvider = true
        )
        assertEquals("cloud_video.mp4", stagingState.displayName)
        assertEquals(65536L, stagingState.bytesStaged)
        assertTrue(stagingState.isCloudProvider)

        val localStaging = SelectionUiState.StagingSource(
            displayName = "stream.bin",
            bytesStaged = 1024L,
            isCloudProvider = false
        )
        assertEquals("stream.bin", localStaging.displayName)
        assertEquals(1024L, localStaging.bytesStaged)
        assertFalse(localStaging.isCloudProvider)
    }
}
