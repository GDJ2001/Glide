package dev.glide.app.ingress

import android.content.Intent
import dev.glide.core.domain.ProtocolLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIngressParserTest {

    @Test
    fun parseFromData_actionSend_validContentUri_returnsSuccess() {
        val result = ShareIngressParser.parseFromData(
            action = Intent.ACTION_SEND,
            mimeType = "image/jpeg",
            textExtra = null,
            rawUriStrings = listOf("content://media/external/images/media/42")
        )

        assertTrue("Expected Success, got $result", result is ShareIngressResult.Success)
        val success = result as ShareIngressResult.Success
        assertEquals(1, success.uriStrings.size)
        assertEquals("content://media/external/images/media/42", success.uriStrings[0])
    }

    @Test
    fun parseFromData_actionSendMultiple_multipleContentUris_returnsSuccess() {
        val uris = listOf(
            "content://media/external/images/media/1",
            "content://media/external/images/media/2",
            "content://media/external/images/media/3"
        )
        val result = ShareIngressParser.parseFromData(
            action = Intent.ACTION_SEND_MULTIPLE,
            mimeType = "image/*",
            textExtra = null,
            rawUriStrings = uris
        )

        assertTrue(result is ShareIngressResult.Success)
        val success = result as ShareIngressResult.Success
        assertEquals(3, success.uriStrings.size)
        assertEquals(uris, success.uriStrings)
    }

    @Test
    fun parseFromData_actionSendMultiple_duplicateUris_deduplicatesCorrectly() {
        val urisWithDuplicates = listOf(
            "content://media/external/images/media/1",
            "content://media/external/images/media/2",
            "content://media/external/images/media/1"
        )
        val result = ShareIngressParser.parseFromData(
            action = Intent.ACTION_SEND_MULTIPLE,
            mimeType = "image/*",
            textExtra = null,
            rawUriStrings = urisWithDuplicates
        )

        assertTrue(result is ShareIngressResult.Success)
        val success = result as ShareIngressResult.Success
        assertEquals(2, success.uriStrings.size)
        assertEquals(
            listOf("content://media/external/images/media/1", "content://media/external/images/media/2"),
            success.uriStrings
        )
    }

    @Test
    fun parseFromData_fileScheme_returnsInvalidScheme() {
        val result = ShareIngressParser.parseFromData(
            action = Intent.ACTION_SEND,
            mimeType = "application/pdf",
            textExtra = null,
            rawUriStrings = listOf("file:///sdcard/Download/confidential.pdf")
        )

        assertTrue("Expected InvalidScheme, got $result", result is ShareIngressResult.InvalidScheme)
        val invalid = result as ShareIngressResult.InvalidScheme
        assertEquals("file:///sdcard/Download/confidential.pdf", invalid.uriString)
        assertEquals("file", invalid.scheme)
    }

    @Test
    fun parseFromData_httpScheme_returnsInvalidScheme() {
        val result = ShareIngressParser.parseFromData(
            action = Intent.ACTION_SEND,
            mimeType = "text/html",
            textExtra = null,
            rawUriStrings = listOf("http://example.com/exploit.apk")
        )

        assertTrue(result is ShareIngressResult.InvalidScheme)
        val invalid = result as ShareIngressResult.InvalidScheme
        assertEquals("http", invalid.scheme)
    }

    @Test
    fun parseFromData_httpsScheme_returnsInvalidScheme() {
        val result = ShareIngressParser.parseFromData(
            action = Intent.ACTION_SEND,
            mimeType = "application/octet-stream",
            textExtra = null,
            rawUriStrings = listOf("https://evil.site/payload.bin")
        )

        assertTrue(result is ShareIngressResult.InvalidScheme)
        val invalid = result as ShareIngressResult.InvalidScheme
        assertEquals("https", invalid.scheme)
    }

    @Test
    fun parseFromData_textShareWithNoStream_returnsUnsupportedType() {
        val result = ShareIngressParser.parseFromData(
            action = Intent.ACTION_SEND,
            mimeType = "text/plain",
            textExtra = "Check out this website: https://glide.dev",
            rawUriStrings = emptyList()
        )

        assertTrue("Expected UnsupportedType, got $result", result is ShareIngressResult.UnsupportedType)
        val unsupported = result as ShareIngressResult.UnsupportedType
        assertTrue(unsupported.message.contains("Text and link sharing is not supported"))
    }

    @Test
    fun parseFromData_emptyStreamAndNoText_returnsError() {
        val result = ShareIngressParser.parseFromData(
            action = Intent.ACTION_SEND,
            mimeType = null,
            textExtra = null,
            rawUriStrings = emptyList()
        )

        assertTrue(result is ShareIngressResult.Error)
        val error = result as ShareIngressResult.Error
        assertEquals("No file stream provided in share intent.", error.message)
    }

    @Test
    fun parseFromData_exceedsBatchLimit_returnsBatchLimitExceeded() {
        val count = ProtocolLimits.MAX_ITEMS_PER_BATCH + 5
        val hugeList = (1..count).map { "content://media/external/images/media/$it" }
        val result = ShareIngressParser.parseFromData(
            action = Intent.ACTION_SEND_MULTIPLE,
            mimeType = "image/*",
            textExtra = null,
            rawUriStrings = hugeList
        )

        assertTrue("Expected BatchLimitExceeded, got $result", result is ShareIngressResult.BatchLimitExceeded)
        val limit = result as ShareIngressResult.BatchLimitExceeded
        assertEquals(count, limit.count)
        assertEquals(ProtocolLimits.MAX_ITEMS_PER_BATCH, limit.max)
    }

    @Test
    fun parseFromData_exactlyMaxBatchLimit_returnsSuccess() {
        val maxList = (1..ProtocolLimits.MAX_ITEMS_PER_BATCH).map { "content://media/external/images/media/$it" }
        val result = ShareIngressParser.parseFromData(
            action = Intent.ACTION_SEND_MULTIPLE,
            mimeType = "image/*",
            textExtra = null,
            rawUriStrings = maxList
        )

        assertTrue(result is ShareIngressResult.Success)
        val success = result as ShareIngressResult.Success
        assertEquals(ProtocolLimits.MAX_ITEMS_PER_BATCH, success.uriStrings.size)
    }

    @Test
    fun parseFromData_unrelatedAction_returnsIgnored() {
        val result = ShareIngressParser.parseFromData(
            action = Intent.ACTION_VIEW,
            mimeType = "image/jpeg",
            textExtra = null,
            rawUriStrings = listOf("content://media/external/images/1")
        )

        assertEquals(ShareIngressResult.Ignored, result)
    }

    @Test
    fun parseFromData_nullAction_returnsIgnored() {
        val result = ShareIngressParser.parseFromData(
            action = null,
            mimeType = null,
            textExtra = null,
            rawUriStrings = emptyList()
        )

        assertEquals(ShareIngressResult.Ignored, result)
    }

    @Test
    fun parse_nullIntent_returnsIgnored() {
        val result = ShareIngressParser.parse(null)
        assertEquals(ShareIngressResult.Ignored, result)
    }

    @Test
    fun parseFromData_uppercaseFileScheme_returnsInvalidScheme() {
        val result = ShareIngressParser.parseFromData(
            action = Intent.ACTION_SEND,
            mimeType = "application/pdf",
            textExtra = null,
            rawUriStrings = listOf("FILE:///sdcard/secret.pdf")
        )

        assertTrue(result is ShareIngressResult.InvalidScheme)
        val invalid = result as ShareIngressResult.InvalidScheme
        assertEquals("file", invalid.scheme)
    }

    @Test
    fun parseFromData_uppercaseHttpScheme_returnsInvalidScheme() {
        val result = ShareIngressParser.parseFromData(
            action = Intent.ACTION_SEND,
            mimeType = "text/html",
            textExtra = null,
            rawUriStrings = listOf("HTTP://malicious.domain/evil")
        )

        assertTrue(result is ShareIngressResult.InvalidScheme)
        val invalid = result as ShareIngressResult.InvalidScheme
        assertEquals("http", invalid.scheme)
    }
}
