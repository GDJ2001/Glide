package dev.glide.app.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap

/**
 * Pure offline QR code encoder (LAN-017, Section 8, Section 9, ADR-0018).
 *
 * Encodes pairing invitations and payloads locally without internet access,
 * remote model downloads, or telemetry.
 */
object QrEncoder {

    const val DEFAULT_MARGIN = 2

    /**
     * Encodes [content] into a ZXing [BitMatrix] with UTF-8 encoding, ErrorCorrectionLevel.M,
     * and a high-contrast quiet-zone margin.
     */
    fun encodeToBitMatrix(
        content: String,
        width: Int,
        height: Int,
        margin: Int = DEFAULT_MARGIN,
        errorCorrectionLevel: ErrorCorrectionLevel = ErrorCorrectionLevel.M
    ): BitMatrix {
        require(content.isNotBlank()) { "Content to encode in QR code must not be blank" }
        require(width > 0) { "Width must be positive: $width" }
        require(height > 0) { "Height must be positive: $height" }

        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.ERROR_CORRECTION, errorCorrectionLevel)
            put(EncodeHintType.MARGIN, margin)
        }

        return QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, width, height, hints)
    }

    /**
     * Encodes [content] into a 2D boolean array where true indicates a dark module.
     * Useful for pure JVM unit testing and custom canvas rendering.
     */
    fun encodeToBooleanGrid(
        content: String,
        size: Int,
        margin: Int = DEFAULT_MARGIN,
        errorCorrectionLevel: ErrorCorrectionLevel = ErrorCorrectionLevel.M
    ): Array<BooleanArray> {
        val bitMatrix = encodeToBitMatrix(content, size, size, margin, errorCorrectionLevel)
        val w = bitMatrix.width
        val h = bitMatrix.height
        val grid = Array(h) { BooleanArray(w) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                grid[y][x] = bitMatrix.get(x, y)
            }
        }
        return grid
    }

    /**
     * Encodes [content] into an Android [Bitmap] with exact integer pixel-per-module scaling
     * and zero uneven border padding so small-screen and dense QRs never suffer nearest-neighbor aliasing.
     */
    fun encodeToBitmap(
        content: String,
        size: Int,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE,
        margin: Int = DEFAULT_MARGIN,
        errorCorrectionLevel: ErrorCorrectionLevel = ErrorCorrectionLevel.L
    ): Bitmap {
        val safeForeground = if ((foregroundColor ushr 24) == 0) Color.BLACK else foregroundColor
        val safeBackground = if ((backgroundColor ushr 24) == 0) Color.WHITE else backgroundColor
        val bitMatrix = encodeToBitMatrix(content, 1, 1, margin, errorCorrectionLevel)
        val matrixWidth = bitMatrix.width
        val matrixHeight = bitMatrix.height
        val scale = (size / maxOf(matrixWidth, matrixHeight)).coerceAtLeast(4)
        val width = matrixWidth * scale
        val height = matrixHeight * scale
        val pixels = IntArray(width * height)

        for (y in 0 until matrixHeight) {
            val rowPixelStart = (y * scale) * width
            for (x in 0 until matrixWidth) {
                val color = if (bitMatrix.get(x, y)) safeForeground else safeBackground
                val colStart = rowPixelStart + x * scale
                for (dy in 0 until scale) {
                    val destStart = colStart + dy * width
                    for (dx in 0 until scale) {
                        pixels[destStart + dx] = color
                    }
                }
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
