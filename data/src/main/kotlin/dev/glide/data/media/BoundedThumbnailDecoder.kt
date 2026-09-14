package dev.glide.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Result of a successful bounded thumbnail decoding operation.
 */
data class ThumbnailResult(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val byteCount: Long
)

/**
 * Domain exceptions for bounded thumbnail decoding failures.
 */
sealed class ThumbnailDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class FileNotFound(message: String) : ThumbnailDecodeException(message)
    class InvalidImage(message: String) : ThumbnailDecodeException(message)
    class DecompressionBombDetected(val width: Int, val height: Int, message: String) : ThumbnailDecodeException(message)
    class MemoryBudgetExceeded(val requiredBytes: Long, val maxBudget: Long, message: String) : ThumbnailDecodeException(message)
    class DecodeFailure(message: String, cause: Throwable? = null) : ThumbnailDecodeException(message, cause)
}

/**
 * Safe, bounded thumbnail decoder.
 *
 * Adheres strictly to:
 * - `GLIDE_PERFORMANCE_PLAN.md`: Bounded memory (<= 32 MiB total heap growth, <= 4 MiB per thumbnail).
 * - `GLIDE_THREAT_MODEL.md` Threat T14: Untrusted images get bounded post-verification thumbnail decoding;
 *   rejection of decompression bombs and oversized raw dimensions before decoding pixel buffers into heap.
 * - `PERFORMANCE_CHECKLIST.md`: Zero main-thread I/O; strictly executed on [ioDispatcher].
 */
object BoundedThumbnailDecoder {

    const val DEFAULT_MAX_DIMENSION: Int = 512
    const val DEFAULT_MAX_BYTE_BUDGET: Long = 4 * 1024 * 1024L // 4 MiB
    const val MAX_UNCOMPRESSED_RAW_DIMENSION: Int = 16_384 // 16K ceiling
    const val MAX_RAW_TOTAL_PIXELS: Long = 67_108_864L // 64 Megapixels ceiling

    /**
     * Decodes an image from [file] into a bounded, downsampled [Bitmap] strictly conforming to
     * [maxDimension] and [maxByteBudget].
     *
     * Runs off the main thread on [ioDispatcher].
     */
    suspend fun decodeThumbnail(
        file: File,
        maxDimension: Int = DEFAULT_MAX_DIMENSION,
        maxByteBudget: Long = DEFAULT_MAX_BYTE_BUDGET,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): Result<ThumbnailResult> = withContext(ioDispatcher) {
        if (!file.exists() || !file.isFile) {
            return@withContext Result.failure(
                ThumbnailDecodeException.FileNotFound("File not found: ${file.absolutePath}")
            )
        }

        try {
            // Step 1: Pre-flight dimension extraction without allocating pixel memory
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)

            val rawWidth = options.outWidth
            val rawHeight = options.outHeight

            if (rawWidth <= 0 || rawHeight <= 0) {
                return@withContext Result.failure(
                    ThumbnailDecodeException.InvalidImage("Invalid or unreadable image dimensions: ${rawWidth}x${rawHeight}")
                )
            }

            // Step 2: Guard against decompression bombs (Threat T14)
            if (rawWidth > MAX_UNCOMPRESSED_RAW_DIMENSION ||
                rawHeight > MAX_UNCOMPRESSED_RAW_DIMENSION ||
                (rawWidth.toLong() * rawHeight.toLong()) > MAX_RAW_TOTAL_PIXELS
            ) {
                return@withContext Result.failure(
                    ThumbnailDecodeException.DecompressionBombDetected(
                        rawWidth,
                        rawHeight,
                        "Image raw dimensions exceed safety limits (${rawWidth}x${rawHeight})"
                    )
                )
            }

            // Step 3: Compute optimal power-of-2 inSampleSize
            val sampleSize = calculateInSampleSize(
                rawWidth = rawWidth,
                rawHeight = rawHeight,
                reqWidth = maxDimension,
                reqHeight = maxDimension,
                maxByteBudget = maxByteBudget
            )

            // Step 4: Decode downsampled bitmap
            val decodeOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
                ?: return@withContext Result.failure(
                    ThumbnailDecodeException.DecodeFailure("BitmapFactory failed to decode file: ${file.name}")
                )

            val byteCount = bitmap.allocationByteCount.toLong()
            if (byteCount > maxByteBudget) {
                bitmap.recycle()
                return@withContext Result.failure(
                    ThumbnailDecodeException.MemoryBudgetExceeded(
                        requiredBytes = byteCount,
                        maxBudget = maxByteBudget,
                        message = "Decoded bitmap ($byteCount bytes) exceeded budget ($maxByteBudget bytes)"
                    )
                )
            }

            Result.success(
                ThumbnailResult(
                    bitmap = bitmap,
                    width = bitmap.width,
                    height = bitmap.height,
                    byteCount = byteCount
                )
            )
        } catch (oom: OutOfMemoryError) {
            Result.failure(
                ThumbnailDecodeException.DecodeFailure("OutOfMemoryError during thumbnail decode", oom)
            )
        } catch (t: Throwable) {
            Result.failure(
                ThumbnailDecodeException.DecodeFailure("Unexpected error during thumbnail decode: ${t.message}", t)
            )
        }
    }

    /**
     * Calculates the minimal power-of-two [inSampleSize] that guarantees both dimensions are <= [reqWidth] / [reqHeight]
     * and the uncompressed ARGB_8888 byte footprint does not exceed [maxByteBudget].
     */
    fun calculateInSampleSize(
        rawWidth: Int,
        rawHeight: Int,
        reqWidth: Int,
        reqHeight: Int,
        maxByteBudget: Long = DEFAULT_MAX_BYTE_BUDGET
    ): Int {
        var inSampleSize = 1

        // Dimension bound: double inSampleSize while either dimension exceeds target bounds
        while ((rawHeight / inSampleSize) > reqHeight || (rawWidth / inSampleSize) > reqWidth) {
            inSampleSize *= 2
        }

        // Memory budget bound (ARGB_8888 uses 4 bytes per pixel)
        var estimatedBytes = ((rawWidth / inSampleSize).toLong() * (rawHeight / inSampleSize).toLong()) * 4L
        while (estimatedBytes > maxByteBudget && inSampleSize < 1024) {
            inSampleSize *= 2
            estimatedBytes = ((rawWidth / inSampleSize).toLong() * (rawHeight / inSampleSize).toLong()) * 4L
        }

        return inSampleSize.coerceAtLeast(1)
    }
}
