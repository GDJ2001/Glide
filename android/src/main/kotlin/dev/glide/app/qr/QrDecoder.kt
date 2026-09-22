package dev.glide.app.qr

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import dev.glide.core.pairing.InvitationCodec
import dev.glide.core.pairing.LanInvitation
import java.util.EnumMap

/**
 * Pure offline QR code decoder (LAN-017, Section 8, Section 9, ADR-0018).
 *
 * Enforces:
 * - Restriction exclusively to BarcodeFormat.QR_CODE.
 * - Zero cloud, zero external model downloads, zero telemetry.
 * - Deterministic ImageProxy lifecycle: closed in finally on every analyzed frame.
 * - Support for both camera frames and clipboard/paste invitation decoding.
 */
object QrDecoder {

    private val decodeHints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
        put(DecodeHintType.CHARACTER_SET, "UTF-8")
        put(DecodeHintType.POSSIBLE_FORMATS, listOf(com.google.zxing.BarcodeFormat.QR_CODE))
    }

    /**
     * Decodes a QR code string from an arbitrary [LuminanceSource].
     * Returns null if no valid QR code is found or if parsing fails.
     */
    fun decodeLuminance(source: LuminanceSource): String? {
        return try {
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val result = QRCodeReader().decode(binaryBitmap, decodeHints)
            result.text
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decodes a QR code directly from a [BitMatrix].
     * Useful for pure JVM unit tests without Android runtime dependencies.
     */
    fun decodeBitMatrix(bitMatrix: BitMatrix): String? {
        val source = BitMatrixLuminanceSource(bitMatrix)
        return decodeLuminance(source)
    }

    /**
     * Decodes a QR code from an in-memory Android [Bitmap].
     */
    fun decodeBitmap(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        return decodeLuminance(source)
    }

    /**
     * Decodes a QR code from a CameraX [ImageProxy] frame.
     *
     * Invariant: [ImageProxy.close] is guaranteed to be called in the finally block.
     */
    fun decodeImageProxy(imageProxy: ImageProxy): String? {
        try {
            val plane = imageProxy.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            val rowStride = plane.rowStride
            val width = imageProxy.width
            val height = imageProxy.height
            val rotation = imageProxy.imageInfo.rotationDegrees

            val (rotatedData, rotatedWidth, rotatedHeight) = rotateYuv(
                data = data,
                rowStride = rowStride,
                width = width,
                height = height,
                rotation = rotation
            )

            val source = PlanarYUVLuminanceSource(
                rotatedData,
                rotatedWidth,
                rotatedHeight,
                0,
                0,
                rotatedWidth,
                rotatedHeight,
                false
            )

            return decodeLuminance(source)
        } catch (_: Exception) {
            return null
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Validates and parses raw text (from camera QR decode or clipboard paste) into a
     * typed [LanInvitation] via canonical [InvitationCodec].
     */
    fun decodeInvitationText(rawText: String): LanInvitation? {
        if (rawText.isBlank()) return null
        return try {
            InvitationCodec.decode(rawText.trim())
        } catch (_: Exception) {
            null
        }
    }

    private fun rotateYuv(
        data: ByteArray,
        rowStride: Int,
        width: Int,
        height: Int,
        rotation: Int
    ): Triple<ByteArray, Int, Int> {
        return when (rotation) {
            90 -> {
                val rotated = ByteArray(width * height)
                for (y in 0 until height) {
                    val rowOffset = y * rowStride
                    for (x in 0 until width) {
                        rotated[x * height + (height - y - 1)] = data[rowOffset + x]
                    }
                }
                Triple(rotated, height, width)
            }
            180 -> {
                val rotated = ByteArray(width * height)
                for (y in 0 until height) {
                    val rowOffset = y * rowStride
                    val targetRow = (height - y - 1) * width
                    for (x in 0 until width) {
                        rotated[targetRow + (width - x - 1)] = data[rowOffset + x]
                    }
                }
                Triple(rotated, width, height)
            }
            270 -> {
                val rotated = ByteArray(width * height)
                for (y in 0 until height) {
                    val rowOffset = y * rowStride
                    for (x in 0 until width) {
                        rotated[(width - x - 1) * height + y] = data[rowOffset + x]
                    }
                }
                Triple(rotated, height, width)
            }
            else -> {
                if (rowStride == width) {
                    Triple(data, width, height)
                } else {
                    val unstrided = ByteArray(width * height)
                    for (y in 0 until height) {
                        System.arraycopy(data, y * rowStride, unstrided, y * width, width)
                    }
                    Triple(unstrided, width, height)
                }
            }
        }
    }

    private class BitMatrixLuminanceSource(private val bitMatrix: BitMatrix) :
        LuminanceSource(bitMatrix.width, bitMatrix.height) {

        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val r = row ?: ByteArray(width)
            for (x in 0 until width) {
                r[x] = if (bitMatrix.get(x, y)) 0.toByte() else 255.toByte()
            }
            return r
        }

        override fun getMatrix(): ByteArray {
            val matrix = ByteArray(width * height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    matrix[offset + x] = if (bitMatrix.get(x, y)) 0.toByte() else 255.toByte()
                }
            }
            return matrix
        }
    }
}
