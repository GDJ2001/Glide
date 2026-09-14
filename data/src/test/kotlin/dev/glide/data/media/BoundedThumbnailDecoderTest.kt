package dev.glide.data.media

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BoundedThumbnailDecoderTest {

    @Test
    fun calculateInSampleSize_smallerThanTarget_returnsOne() {
        val sampleSize = BoundedThumbnailDecoder.calculateInSampleSize(
            rawWidth = 256,
            rawHeight = 256,
            reqWidth = 512,
            reqHeight = 512
        )
        assertEquals(1, sampleSize)
    }

    @Test
    fun calculateInSampleSize_twiceTarget_returnsTwo() {
        val sampleSize = BoundedThumbnailDecoder.calculateInSampleSize(
            rawWidth = 1024,
            rawHeight = 1024,
            reqWidth = 512,
            reqHeight = 512
        )
        assertEquals(2, sampleSize)
    }

    @Test
    fun calculateInSampleSize_eightTimesTarget_returnsEight() {
        val sampleSize = BoundedThumbnailDecoder.calculateInSampleSize(
            rawWidth = 4096,
            rawHeight = 4096,
            reqWidth = 512,
            reqHeight = 512
        )
        assertEquals(8, sampleSize)
    }

    @Test
    fun calculateInSampleSize_asymmetricDimensions_boundsBothAxes() {
        val sampleSize = BoundedThumbnailDecoder.calculateInSampleSize(
            rawWidth = 4096,
            rawHeight = 1024,
            reqWidth = 512,
            reqHeight = 512
        )
        // 4096 / 8 = 512, 1024 / 8 = 128 (both <= 512)
        assertEquals(8, sampleSize)
    }

    @Test
    fun calculateInSampleSize_tightMemoryBudget_increasesDownsampling() {
        // Raw 1024x1024 = 1,048,576 pixels * 4 = 4 MiB
        // Budget = 512 KiB (524,288 bytes)
        val tightBudget = 512 * 1024L
        val sampleSize = BoundedThumbnailDecoder.calculateInSampleSize(
            rawWidth = 1024,
            rawHeight = 1024,
            reqWidth = 1024, // Req dimension allows 1x
            reqHeight = 1024,
            maxByteBudget = tightBudget
        )
        // 1024 / 4 = 256x256 * 4 = 262,144 bytes <= 512 KiB
        assertTrue("Sample size must increase to satisfy tight byte budget", sampleSize >= 4)
    }

    @Test
    fun decodeThumbnail_fileNotFound_returnsFileNotFoundException() = runTest {
        val nonExistentFile = File("non_existent_image_12345.jpg")
        val result = BoundedThumbnailDecoder.decodeThumbnail(nonExistentFile)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ThumbnailDecodeException.FileNotFound)
    }

    @Test
    fun constants_conformToSecurityAndPerformanceBudgets() {
        // Memory budget <= 4 MiB per thumbnail (GLIDE_PERFORMANCE_PLAN)
        assertEquals(4 * 1024 * 1024L, BoundedThumbnailDecoder.DEFAULT_MAX_BYTE_BUDGET)
        // Default max dimension 512px
        assertEquals(512, BoundedThumbnailDecoder.DEFAULT_MAX_DIMENSION)
        // Max raw dimension 16,384px (T14 defense against decompression bombs)
        assertEquals(16_384, BoundedThumbnailDecoder.MAX_UNCOMPRESSED_RAW_DIMENSION)
        // Max total raw pixels 64 Megapixels
        assertEquals(67_108_864L, BoundedThumbnailDecoder.MAX_RAW_TOTAL_PIXELS)
    }
}
