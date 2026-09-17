package dev.glide.transport.lan.web

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

class GlideWebGatewayTest {

    private lateinit var quarantineDir: File
    private lateinit var testFile: File
    private lateinit var testFileBytes: ByteArray
    private lateinit var testFileSha256: String
    private lateinit var gateway: GlideWebGateway

    @Before
    fun setUp() {
        quarantineDir = File(System.getProperty("java.io.tmpdir"), "glide_test_quarantine_${UUID.randomUUID()}")
        quarantineDir.mkdirs()

        testFileBytes = ByteArray(128 * 1024) { (it % 251).toByte() }
        testFileSha256 = MessageDigest.getInstance("SHA-256").digest(testFileBytes)
            .joinToString("") { "%02x".format(it) }

        testFile = File(quarantineDir, "sample_doc.pdf")
        testFile.writeBytes(testFileBytes)

        val items = listOf(
            OfferedWebItem(
                itemId = "doc-123",
                fileName = "sample_doc.pdf",
                fileSize = testFileBytes.size.toLong(),
                file = testFile
            )
        )

        gateway = GlideWebGateway(
            host = "127.0.0.1",
            port = 0,
            offeredItems = items,
            deviceName = "Test Pixel",
            platform = "android",
            quarantineDir = quarantineDir,
            ttlSeconds = 120
        )
        gateway.start()
    }

    @After
    fun tearDown() {
        gateway.close()
        quarantineDir.deleteRecursively()
    }

    @Test
    fun assetDelivery_returnsHtmlCssJs_withStrictCspHeaders() {
        val baseUrl = "http://127.0.0.1:${gateway.boundPort}"

        // 1. GET /?token=...
        val conn = URL("$baseUrl/?token=${gateway.token}").openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            assertEquals(200, conn.responseCode)
            assertTrue(conn.contentType.contains("text/html"))
            assertTrue(conn.getHeaderField("Content-Security-Policy").contains("default-src 'self'"))
            assertEquals("nosniff", conn.getHeaderField("X-Content-Type-Options"))
            assertEquals("DENY", conn.getHeaderField("X-Frame-Options"))

            val body = conn.inputStream.readBytes().toString(Charsets.UTF_8)
            assertTrue(body.contains("Glide"))
            assertTrue(body.contains("Web Share"))

            val setCookie = conn.getHeaderField("Set-Cookie")
            assertNotNull(setCookie)
            assertTrue(setCookie.contains("glide_web_session=${gateway.token}"))
        } finally {
            conn.disconnect()
        }

        // 2. GET /style.css with cookie
        val cssConn = URL("$baseUrl/style.css").openConnection() as HttpURLConnection
        try {
            cssConn.setRequestProperty("Cookie", "glide_web_session=${gateway.token}")
            assertEquals(200, cssConn.responseCode)
            assertTrue(cssConn.contentType.contains("text/css"))
            val css = cssConn.inputStream.readBytes().toString(Charsets.UTF_8)
            assertTrue(css.contains("--bg-primary"))
        } finally {
            cssConn.disconnect()
        }

        // 3. GET /app.js with cookie
        val jsConn = URL("$baseUrl/app.js").openConnection() as HttpURLConnection
        try {
            jsConn.setRequestProperty("Cookie", "glide_web_session=${gateway.token}")
            assertEquals(200, jsConn.responseCode)
            assertTrue(jsConn.contentType.contains("application/javascript"))
            val js = jsConn.inputStream.readBytes().toString(Charsets.UTF_8)
            assertTrue(js.contains("loadManifest"))
        } finally {
            jsConn.disconnect()
        }
    }

    @Test
    fun tokenAuthentication_rejectsUnauthorized_andAcceptsValidToken() {
        val baseUrl = "http://127.0.0.1:${gateway.boundPort}"

        // Missing token -> 403
        val noTokenConn = URL("$baseUrl/api/v1/manifest").openConnection() as HttpURLConnection
        try {
            assertEquals(403, noTokenConn.responseCode)
        } finally {
            noTokenConn.disconnect()
        }

        // Invalid token -> 403
        val badTokenConn = URL("$baseUrl/api/v1/manifest?token=deadbeef").openConnection() as HttpURLConnection
        try {
            assertEquals(403, badTokenConn.responseCode)
        } finally {
            badTokenConn.disconnect()
        }

        // Valid token -> 200 with manifest
        val goodConn = URL("$baseUrl/api/v1/manifest?token=${gateway.token}").openConnection() as HttpURLConnection
        try {
            assertEquals(200, goodConn.responseCode)
            val json = goodConn.inputStream.readBytes().toString(Charsets.UTF_8)
            assertTrue(json.contains("Test Pixel"))
            assertTrue(json.contains("sample_doc.pdf"))
            assertTrue(json.contains("doc-123"))
        } finally {
            goodConn.disconnect()
        }
    }

    @Test
    fun downloadItem_streamsAccurateBytes_withContentDisposition() {
        val downloadUrl = "http://127.0.0.1:${gateway.boundPort}/api/v1/download/doc-123"
        val conn = URL(downloadUrl).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Cookie", "glide_web_session=${gateway.token}")
            assertEquals(200, conn.responseCode)
            assertEquals(testFileBytes.size.toLong(), conn.contentLengthLong)
            val disposition = conn.getHeaderField("Content-Disposition")
            assertTrue(disposition.contains("filename=\"sample_doc.pdf\""))
            assertTrue(disposition.contains("filename*=UTF-8''sample_doc.pdf"))
            assertEquals("bytes", conn.getHeaderField("Accept-Ranges"))

            val downloadedBytes = conn.inputStream.readBytes()
            assertEquals(testFileBytes.size, downloadedBytes.size)

            val downloadedSha = MessageDigest.getInstance("SHA-256").digest(downloadedBytes)
                .joinToString("") { "%02x".format(it) }
            assertEquals(testFileSha256, downloadedSha)
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun downloadItem_supportsRangeRequest_partialContent206() {
        val downloadUrl = "http://127.0.0.1:${gateway.boundPort}/api/v1/download/doc-123"

        // 1. Explicit range: bytes=100-199 (100 bytes)
        val conn1 = URL(downloadUrl).openConnection() as HttpURLConnection
        try {
            conn1.setRequestProperty("Cookie", "glide_web_session=${gateway.token}")
            conn1.setRequestProperty("Range", "bytes=100-199")
            assertEquals(206, conn1.responseCode)
            assertEquals("bytes 100-199/${testFileBytes.size}", conn1.getHeaderField("Content-Range"))
            assertEquals(100L, conn1.contentLengthLong)
            val bytes1 = conn1.inputStream.readBytes()
            assertEquals(100, bytes1.size)
            for (i in 0 until 100) {
                assertEquals(testFileBytes[100 + i], bytes1[i])
            }
        } finally {
            conn1.disconnect()
        }

        // 2. Open-ended range: bytes=1000-
        val conn2 = URL(downloadUrl).openConnection() as HttpURLConnection
        try {
            conn2.setRequestProperty("Cookie", "glide_web_session=${gateway.token}")
            conn2.setRequestProperty("Range", "bytes=1000-")
            assertEquals(206, conn2.responseCode)
            val expectedLen = testFileBytes.size - 1000
            assertEquals("bytes 1000-${testFileBytes.size - 1}/${testFileBytes.size}", conn2.getHeaderField("Content-Range"))
            assertEquals(expectedLen.toLong(), conn2.contentLengthLong)
            val bytes2 = conn2.inputStream.readBytes()
            assertEquals(expectedLen, bytes2.size)
            for (i in 0 until expectedLen) {
                assertEquals(testFileBytes[1000 + i], bytes2[i])
            }
        } finally {
            conn2.disconnect()
        }

        // 3. Suffix range: bytes=-50 (last 50 bytes)
        val conn3 = URL(downloadUrl).openConnection() as HttpURLConnection
        try {
            conn3.setRequestProperty("Cookie", "glide_web_session=${gateway.token}")
            conn3.setRequestProperty("Range", "bytes=-50")
            assertEquals(206, conn3.responseCode)
            val start = testFileBytes.size - 50
            assertEquals("bytes $start-${testFileBytes.size - 1}/${testFileBytes.size}", conn3.getHeaderField("Content-Range"))
            assertEquals(50L, conn3.contentLengthLong)
            val bytes3 = conn3.inputStream.readBytes()
            assertEquals(50, bytes3.size)
            for (i in 0 until 50) {
                assertEquals(testFileBytes[start + i], bytes3[i])
            }
        } finally {
            conn3.disconnect()
        }
    }

    @Test
    fun downloadItem_invalidRange_returns416() {
        val downloadUrl = "http://127.0.0.1:${gateway.boundPort}/api/v1/download/doc-123"
        val conn = URL(downloadUrl).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Cookie", "glide_web_session=${gateway.token}")
            conn.setRequestProperty("Range", "bytes=500000-600000")
            assertEquals(416, conn.responseCode)
            assertEquals("bytes */${testFileBytes.size}", conn.getHeaderField("Content-Range"))
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun downloadItem_headMethod_returnsHeadersWithoutBody() {
        val downloadUrl = "http://127.0.0.1:${gateway.boundPort}/api/v1/download/doc-123"
        val conn = URL(downloadUrl).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "HEAD"
            conn.setRequestProperty("Cookie", "glide_web_session=${gateway.token}")
            assertEquals(200, conn.responseCode)
            assertEquals(testFileBytes.size.toLong(), conn.contentLengthLong)
            assertEquals("bytes", conn.getHeaderField("Accept-Ranges"))
            assertTrue(conn.getHeaderField("Content-Disposition").contains("filename=\"sample_doc.pdf\""))

            val body = conn.inputStream.readBytes()
            assertEquals(0, body.size)
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun downloadItem_unicodeFilename_includesRfc6266Disposition() {
        val unicodeName = "写真_📷_photo.png"
        val unicodeFile = File(quarantineDir, "unicode.png")
        unicodeFile.writeBytes(byteArrayOf(1, 2, 3, 4))

        val unicodeGateway = GlideWebGateway(
            host = "127.0.0.1",
            port = 0,
            offeredItems = listOf(
                OfferedWebItem(
                    itemId = "unicode-1",
                    fileName = unicodeName,
                    fileSize = 4L,
                    file = unicodeFile
                )
            ),
            deviceName = "Unicode Device",
            quarantineDir = quarantineDir
        )
        unicodeGateway.start()
        try {
            val url = "http://127.0.0.1:${unicodeGateway.boundPort}/api/v1/download/unicode-1?token=${unicodeGateway.token}"
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                assertEquals(200, conn.responseCode)
                val disposition = conn.getHeaderField("Content-Disposition")
                assertTrue(disposition.contains("filename="))
                assertTrue(disposition.contains("filename*=UTF-8''"))
                assertTrue(disposition.contains(java.net.URLEncoder.encode(unicodeName, "UTF-8").replace("+", "%20")))
            } finally {
                conn.disconnect()
            }
        } finally {
            unicodeGateway.close()
        }
    }

    @Test
    fun uploadItem_stagesInQuarantine_andVerifiesSha256() {
        val uploadBytes = "Hello from Android Web Browser!".toByteArray(Charsets.UTF_8)
        val expectedSha = MessageDigest.getInstance("SHA-256").digest(uploadBytes)
            .joinToString("") { "%02x".format(it) }

        val uploadUrl = "http://127.0.0.1:${gateway.boundPort}/api/v1/upload"
        val conn = URL(uploadUrl).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Cookie", "glide_web_session=${gateway.token}")
            conn.setRequestProperty("X-File-Name", "notes.txt")
            conn.setRequestProperty("Content-Type", "text/plain")
            conn.setFixedLengthStreamingMode(uploadBytes.size)

            conn.outputStream.use { it.write(uploadBytes) }

            assertEquals(200, conn.responseCode)
            val resp = conn.inputStream.readBytes().toString(Charsets.UTF_8)
            assertTrue(resp.contains("\"status\":\"ok\""))
            assertTrue(resp.contains("\"fileName\":\"notes.txt\""))
            assertTrue(resp.contains(expectedSha))

            val savedFile = File(quarantineDir, "notes.txt")
            assertTrue("Uploaded file must exist in quarantine", savedFile.exists())
            assertEquals(uploadBytes.size.toLong(), savedFile.length())
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun expiredToken_returnsForbidden() {
        val expiredGateway = GlideWebGateway(
            host = "127.0.0.1",
            port = 0,
            offeredItems = emptyList(),
            deviceName = "Expired Device",
            ttlSeconds = -10 // expired immediately
        )
        expiredGateway.start()
        try {
            val url = "http://127.0.0.1:${expiredGateway.boundPort}/?token=${expiredGateway.token}"
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                assertEquals(403, conn.responseCode)
            } finally {
                conn.disconnect()
            }
        } finally {
            expiredGateway.close()
        }
    }
}
