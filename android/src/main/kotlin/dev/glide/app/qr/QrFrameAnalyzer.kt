package dev.glide.app.qr

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * CameraX [ImageAnalysis.Analyzer] for offline local QR decoding (LAN-017, Section 8, Section 9).
 *
 * Invariants:
 * - Rate limited by [minIntervalMs] (default 200ms = max 5 frames/sec) to conserve battery and CPU.
 * - Single-decode latch: once a valid QR payload is decoded, further frame analysis is suppressed
 *   so repeated camera frames cannot trigger multiple connection attempts.
 * - Deterministic ImageProxy lifecycle: [ImageProxy.close] is guaranteed to be called for every frame.
 */
class QrFrameAnalyzer(
    private val minIntervalMs: Long = 200L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onQrDecoded: (String) -> Unit
) : ImageAnalysis.Analyzer {

    @Volatile
    private var isLocked: Boolean = false

    @Volatile
    private var lastAnalyzedMs: Long = 0L

    override fun analyze(imageProxy: ImageProxy) {
        val now = clock()

        // If locked or within throttle window, immediately release the frame without decoding
        if (isLocked || (now - lastAnalyzedMs < minIntervalMs)) {
            imageProxy.close()
            return
        }

        lastAnalyzedMs = now

        // decodeImageProxy deterministically closes imageProxy in its finally block
        val decoded = QrDecoder.decodeImageProxy(imageProxy)

        if (decoded != null && !isLocked) {
            isLocked = true
            onQrDecoded(decoded)
        }
    }

    /**
     * Checks if the analyzer is currently locked following a successful decode.
     */
    fun isLocked(): Boolean = isLocked

    /**
     * Resets the decode latch (e.g. if the user dismisses an error or explicitly taps retry).
     */
    fun resetLock() {
        isLocked = false
    }
}
