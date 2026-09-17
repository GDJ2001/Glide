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
            assertTrue(conn.getHeaderField("Content-Disposition").contains("filename=\"sample_doc.pdf\""))

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
