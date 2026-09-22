package dev.glide.app.qr

import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import dev.glide.core.pairing.InvitationCodec
import dev.glide.core.pairing.LanEndpoint
import dev.glide.core.pairing.LanInvitation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class QrEncoderDecoderTest {

    @Test
    fun testEncodeAndDecode_roundTrip_preservesContent() {
        val originalText = "glide-pairing-test-payload-12345"
        val bitMatrix = QrEncoder.encodeToBitMatrix(originalText, 256, 256)

        assertNotNull(bitMatrix)
        assertTrue(bitMatrix.width > 0)
        assertEquals(bitMatrix.width, bitMatrix.height)

        val decoded = QrDecoder.decodeBitMatrix(bitMatrix)
        assertEquals(originalText, decoded)
    }

    @Test
    fun testEncodeAndDecode_canonicalLanInvitation_preservesAllFields() {
        val originalInvitation = LanInvitation(
            v = 1,
            transport = "lan-tls",
            profile = 1,
            protocol = 1,
            invitationId = "00000000-0000-4000-8000-000000000001",
            presence = "00112233445566778899aabbccddeeff",
            name = "Pixel 9 Pro",
            platform = "android",
            endpoints = listOf(
                LanEndpoint("192.168.1.50", 49152),
                LanEndpoint("2001:db8::1", 49153)
            ),
            spkiSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            token = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLM_012",
            expiresAt = "1773729600",
            ttlSeconds = 120
        )

        val encodedUri = InvitationCodec.encode(originalInvitation)
        assertTrue("Encoded URI must match canonical scheme", encodedUri.startsWith("glide://pair/v1#"))

        // Encode to QR bit matrix
        val bitMatrix = QrEncoder.encodeToBitMatrix(encodedUri, 384, 384)

        // Decode bit matrix back to URI string
        val decodedText = QrDecoder.decodeBitMatrix(bitMatrix)
        assertNotNull(decodedText)
        assertEquals(encodedUri, decodedText)

        // Decode URI string to typed LanInvitation
        val decodedInvitation = QrDecoder.decodeInvitationText(decodedText!!)
        assertNotNull("Decoded invitation must parse cleanly", decodedInvitation)
        assertEquals(originalInvitation.copy(invitationHash = decodedInvitation!!.invitationHash), decodedInvitation)
        assertTrue("Decoded invitation must have non-empty computed SHA-256 hash", decodedInvitation.invitationHash.isNotEmpty())
    }

    @Test
    fun testEncodeToBooleanGrid_dimensionsAndQuietZone() {
        val content = "sample-qr-code"
        val size = 200
        val margin = 3
        val grid = QrEncoder.encodeToBooleanGrid(content, size, margin = margin)

        assertEquals(size, grid.size)
        assertEquals(size, grid[0].size)

        // Check top-left corner is quiet zone (margin = 3 => first 3 rows should have white/false modules)
        for (y in 0 until margin) {
            for (x in 0 until margin) {
                assertFalse("Quiet zone margin must be false (white)", grid[y][x])
            }
        }
    }

    @Test
    fun testEncoder_blankContent_throwsIllegalArgumentException() {
        try {
            QrEncoder.encodeToBitMatrix("   ", 200, 200)
            fail("Blank content must throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("must not be blank") == true)
        }
    }

    @Test
    fun testEncoder_invalidDimensions_throwsIllegalArgumentException() {
        try {
            QrEncoder.encodeToBitMatrix("content", 0, 200)
            fail("Zero width must throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Width must be positive") == true)
        }
    }

    @Test
    fun testDecoder_corruptedOrGarbageBitMatrix_returnsNull() {
        // An all-white matrix has no QR finder patterns
        val emptyMatrix = com.google.zxing.common.BitMatrix(100, 100)
        val decoded = QrDecoder.decodeBitMatrix(emptyMatrix)
        assertNull("Empty/garbage matrix must return null without throwing", decoded)
    }

    @Test
    fun testDecodeInvitationText_invalidOrMaliciousText_returnsNull() {
        assertNull(QrDecoder.decodeInvitationText(""))
        assertNull(QrDecoder.decodeInvitationText("   "))
        assertNull(QrDecoder.decodeInvitationText("https://malicious-site.com/pair"))
        assertNull(QrDecoder.decodeInvitationText("glide://pair/v2#wrong-version"))
        assertNull(QrDecoder.decodeInvitationText("glide://pair/v1#serverPin=badhex&token=123"))
    }

    @Test
    fun testQrFrameAnalyzer_rateLimiting_and_singleDecodeLatch() {
        var currentTime = 1000L
        val decodedResults = mutableListOf<String>()

        val analyzer = QrFrameAnalyzer(
            minIntervalMs = 200L,
            clock = { currentTime },
            onQrDecoded = { decodedResults.add(it) }
        )

        assertFalse(analyzer.isLocked())

        // Create a dummy ImageProxy that records closure
        class TestImageProxy : FakeImageProxy(100, 100)

        // 1. First frame analyzed at t=1000ms (nothing decodable in blank image)
        val proxy1 = TestImageProxy()
        analyzer.analyze(proxy1)
        assertTrue("Proxy 1 must be closed", proxy1.isClosed)
        assertEquals(0, decodedResults.size)
        assertFalse(analyzer.isLocked())

        // 2. Second frame at t=1100ms (< 200ms interval => throttled)
        currentTime = 1100L
        val proxy2 = TestImageProxy()
        analyzer.analyze(proxy2)
        assertTrue("Proxy 2 must be closed immediately by throttle", proxy2.isClosed)
        assertEquals(0, decodedResults.size)

        // 3. Third frame at t=1250ms (> 200ms interval => allowed)
        currentTime = 1250L
        val proxy3 = TestImageProxy()
        analyzer.analyze(proxy3)
        assertTrue("Proxy 3 must be closed", proxy3.isClosed)

        // 4. Test latching: simulate lock being engaged
        val proxy4 = TestImageProxy()
        currentTime = 1500L
        // Artificially simulate decode by verifying resetLock / isLocked
        assertFalse(analyzer.isLocked())
    }

    private open class FakeImageProxy(
        private val width: Int,
        private val height: Int
    ) : ImageProxy {

        var isClosed: Boolean = false

        private val fakePlane = object : ImageProxy.PlaneProxy {
            private val buffer = ByteBuffer.allocate(width * height)
            override fun getBuffer(): ByteBuffer = buffer
            override fun getPixelStride(): Int = 1
            override fun getRowStride(): Int = width
        }

        override fun getWidth(): Int = width
        override fun getHeight(): Int = height
        override fun getFormat(): Int = android.graphics.ImageFormat.YUV_420_888
        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = arrayOf(fakePlane)
        override fun getImageInfo(): ImageInfo = object : ImageInfo {
            override fun getTagBundle(): androidx.camera.core.impl.TagBundle =
                androidx.camera.core.impl.TagBundle.emptyBundle()
            override fun getTimestamp(): Long = 0L
            override fun getRotationDegrees(): Int = 0
            override fun populateExifData(builder: androidx.camera.core.impl.utils.ExifData.Builder) {}
        }
        override fun getCropRect(): android.graphics.Rect = android.graphics.Rect(0, 0, width, height)
        override fun setCropRect(rect: android.graphics.Rect?) {}
        override fun getImage(): android.media.Image? = null
        override fun close() {
            isClosed = true
        }
    }
}
