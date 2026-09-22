package dev.glide.app.ui.selection

import androidx.activity.result.contract.ActivityResultContracts
import dev.glide.core.domain.BatchId
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SourceHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MediaPickerHelperTest {

    private fun createItem(
        id: String = UUID.randomUUID().toString(),
        uri: String = "content://media/external/video/1001",
        name: String = "large_vacation_video.mp4",
        sizeBytes: Long = 2_684_354_560L, // 2.5 GiB
        mimeType: String = "video/mp4",
        isAvailable: Boolean = true,
        errorMessage: String? = null
    ): SelectedFileItem = SelectedFileItem(
        id = ItemId(id),
        handle = SourceHandle(uri),
        displayName = name,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        isAvailable = isAvailable,
        errorMessage = errorMessage
    )

    @Test
    fun resolveVisualPickerStrategy_api26WithoutPhotoPicker_resolvesFallback() {
        val strategy = MediaPickerHelper.resolveVisualPickerStrategy(
            apiLevel = 26,
            isPhotoPickerAvailable = false
        )
        assertEquals(PickerStrategy.DocumentPickerFallback, strategy)
    }

    @Test
    fun resolveVisualPickerStrategy_api28WithoutPhotoPicker_resolvesFallback() {
        val strategy = MediaPickerHelper.resolveVisualPickerStrategy(
            apiLevel = 28,
            isPhotoPickerAvailable = false
        )
        assertEquals(PickerStrategy.DocumentPickerFallback, strategy)
    }

    @Test
    fun resolveVisualPickerStrategy_api30WithGmsBackport_resolvesSystemPhotoPicker() {
        val strategy = MediaPickerHelper.resolveVisualPickerStrategy(
            apiLevel = 30,
            isPhotoPickerAvailable = true
        )
        assertEquals(PickerStrategy.SystemPhotoPicker, strategy)
    }

    @Test
    fun resolveVisualPickerStrategy_modernApi33Plus_resolvesSystemPhotoPicker() {
        val strategy33 = MediaPickerHelper.resolveVisualPickerStrategy(
            apiLevel = 33,
            isPhotoPickerAvailable = true
        )
        assertEquals(PickerStrategy.SystemPhotoPicker, strategy33)

        val strategy37 = MediaPickerHelper.resolveVisualPickerStrategy(
            apiLevel = 37,
            isPhotoPickerAvailable = true
        )
        assertEquals(PickerStrategy.SystemPhotoPicker, strategy37)
    }

    @Test
    fun pickerContracts_mimeTypesAndVisualRequests_configuredCorrectly() {
        val docMimes = MediaPickerHelper.getDocumentMimeTypes()
        assertEquals(1, docMimes.size)
        assertEquals("*/*", docMimes[0])

        val audioMimes = MediaPickerHelper.getAudioMimeTypes()
        assertEquals(1, audioMimes.size)
        assertEquals("audio/*", audioMimes[0])

        val visualRequest = MediaPickerHelper.createVisualMediaRequest()
        assertNotNull(visualRequest)
        assertEquals(
            ActivityResultContracts.PickVisualMedia.ImageAndVideo,
            visualRequest.mediaType
        )
    }

    @Test
    fun videoOver1GB_largeSizesHandledWithout32BitOverflow() {
        val onePointFiveGb = 1_610_612_736L // 1.5 GiB
        val twoPointFiveGb = 2_684_354_560L // 2.5 GiB (> Int.MAX_VALUE 2,147,483,647)
        val fourGb = 4_294_967_296L         // 4.0 GiB

        // Verify human-readable formatting
        assertEquals("1.5 GB", formatFileSize(onePointFiveGb))
        assertEquals("2.5 GB", formatFileSize(twoPointFiveGb))
        assertEquals("4.0 GB", formatFileSize(fourGb))

        // Single large video item
        val largeVideoItem = createItem(
            name = "4k_recording_drone.mp4",
            sizeBytes = twoPointFiveGb,
            mimeType = "video/mp4"
        )
        val state = SelectionUiState.BatchReady(items = listOf(largeVideoItem))

        assertEquals(1, state.itemCount)
        assertEquals(1, state.validItemCount)
        assertEquals(twoPointFiveGb, state.totalBytes)
        assertFalse(state.hasUnavailableItems)
        assertTrue(state.canProceed)

        // Conversion to domain transfer batch preserves exact 64-bit size
        val batch = state.toTransferBatch()
        assertEquals(1, batch.items.size)
        assertEquals(twoPointFiveGb, batch.items[0].sizeBytes)
        assertEquals(twoPointFiveGb, batch.totalBytes)
    }

    @Test
    fun videoOver1GB_mixedBatchCalculatesAccurateTotalWithoutTruncation() {
        val video1 = createItem(
            name = "video1.mp4",
            sizeBytes = 2_684_354_560L // 2.5 GiB
        )
        val video2 = createItem(
            name = "video2.mp4",
            sizeBytes = 1_610_612_736L // 1.5 GiB
        )
        val photo = createItem(
            name = "raw_photo.dng",
            sizeBytes = 52_428_800L, // 50 MiB
            mimeType = "image/x-adobe-dng"
        )
        val document = createItem(
            name = "presentation.pdf",
            sizeBytes = 10_485_760L, // 10 MiB
            mimeType = "application/pdf"
        )

        val expectedTotal = 2_684_354_560L + 1_610_612_736L + 52_428_800L + 10_485_760L
        val state = SelectionUiState.BatchReady(items = listOf(video1, video2, photo, document))

        assertEquals(4, state.itemCount)
        assertEquals(4, state.validItemCount)
        assertEquals(expectedTotal, state.totalBytes)
        assertTrue(state.canProceed)

        val batch = state.toTransferBatch()
        assertEquals(4, batch.items.size)
        assertEquals(expectedTotal, batch.totalBytes)
    }

    @Test
    fun revokedGrant_detectedAndBlocksProceedUntilRemoved() {
        val validVideo = createItem(
            name = "valid_clip.mp4",
            sizeBytes = 100_000_000L,
            isAvailable = true
        )
        val revokedItem = createItem(
            name = "revoked_camera_roll.mp4",
            sizeBytes = 0L,
            isAvailable = false,
            errorMessage = "Access denied or permission revoked: SecurityException"
        )

        val state = SelectionUiState.BatchReady(items = listOf(validVideo, revokedItem))

        assertEquals(2, state.itemCount)
        assertEquals(1, state.validItemCount)
        assertEquals(100_000_000L, state.totalBytes)
        assertTrue(state.hasUnavailableItems)
        assertFalse(state.canProceed)

        // Attempting to proceed throws IllegalStateException
        assertThrows(IllegalStateException::class.java) {
            state.toTransferBatch()
        }

        // Removing the revoked item heals the batch immediately
        val healedItems = state.items.filterNot { it.id == revokedItem.id }
        val healedState = SelectionUiState.BatchReady(items = healedItems)

        assertEquals(1, healedState.itemCount)
        assertEquals(1, healedState.validItemCount)
        assertFalse(healedState.hasUnavailableItems)
        assertTrue(healedState.canProceed)
    }
}