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
     * Encodes [content] into an Android [Bitmap] suitable for UI display.
     */
    fun encodeToBitmap(
        content: String,
        size: Int,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE,
        margin: Int = DEFAULT_MARGIN,
        errorCorrectionLevel: ErrorCorrectionLevel = ErrorCorrectionLevel.M
    ): Bitmap {
        val bitMatrix = encodeToBitMatrix(content, size, size, margin, errorCorrectionLevel)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) foregroundColor else backgroundColor
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
