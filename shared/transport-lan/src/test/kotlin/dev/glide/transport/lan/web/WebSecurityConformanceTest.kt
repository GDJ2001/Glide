package dev.glide.transport.lan.web

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class WebSecurityConformanceTest {

    private lateinit var quarantineDir: File
    private lateinit var testFile: File
    private lateinit var gateway: GlideWebGateway

    @Before
    fun setUp() {
        quarantineDir = File(System.getProperty("java.io.tmpdir"), "glide_web_sec_test_${UUID.randomUUID()}")
        quarantineDir.mkdirs()

        testFile = File(quarantineDir, "sample.txt")
        testFile.writeText("Security Conformance Sample Data")

        gateway = GlideWebGateway(
            host = "127.0.0.1",
            port = 0,
            offeredItems = listOf(OfferedWebItem(testFile)),
            deviceName = "Security Test Device",
            quarantineDir = quarantineDir
        )
        gateway.start()
    }

    @After
    fun tearDown() {
        gateway.close()
        quarantineDir.deleteRecursively()
    }

    @Test
    fun tokenReplay_subsequentQueryTokenAttempt_returns403Forbidden() {
        // 1. Initial legitimate onboarding request
        val firstConn = URL(gateway.onboardingUrl).openConnection() as HttpURLConnection
        try {
            assertEquals(200, firstConn.responseCode)
            val cookieHeader = firstConn.getHeaderField("Set-Cookie")
            assertNotNull(cookieHeader)
            assertTrue(cookieHeader.contains("glide_web_session="))
        } finally {
            firstConn.disconnect()
        }

        // 2. Token replay attempt by an attacker / another client without cookie
        val replayConn = URL(gateway.onboardingUrl).openConnection() as HttpURLConnection
        try {
            assertEquals(403, replayConn.responseCode)
        } finally {
            replayConn.disconnect()
        }
    }

    @Test
    fun securityHeaders_allEndpoints_containCspNoSniffDenyNoCache() {
        val endpoints = listOf("/", "/style.css", "/app.js", "/api/v1/manifest")

        for (ep in endpoints) {
            val url = "http://127.0.0.1:${gateway.boundPort}$ep"
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.setRequestProperty("Cookie", "glide_web_session=${gateway.token}")
                assertEquals(200, conn.responseCode)

                val csp = conn.getHeaderField("Content-Security-Policy")
                assertNotNull("CSP header must be present on $ep", csp)
                assertTrue(csp.contains("default-src 'self'"))
                assertTrue(csp.contains("frame-ancestors 'none'"))

                val noSniff = conn.getHeaderField("X-Content-Type-Options")
                assertEquals("nosniff", noSniff)

                val frameOptions = conn.getHeaderField("X-Frame-Options")
                assertEquals("DENY", frameOptions)

                val cacheControl = conn.getHeaderField("Cache-Control")
                assertNotNull("Cache-Control must be present on $ep", cacheControl)
                assertTrue(cacheControl.contains("no-store"))
            } finally {
                conn.disconnect()
            }
        }
    }

    @Test
    fun unauthorizedAccess_withoutTokenOrCookie_returns403Forbidden() {
        val endpoints = listOf(
            "/",
            "/index.html",
            "/api/v1/manifest",
            "/api/v1/download/sample-id"
        )

        for (ep in endpoints) {
            val url = "http://127.0.0.1:${gateway.boundPort}$ep"
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                assertEquals("Endpoint $ep must require authentication", 403, conn.responseCode)
            } finally {
                conn.disconnect()
            }
        }
    }

    @Test
    fun sessionCookie_issuedWithHttpOnlyAndSameSiteStrict() {
        val conn = URL(gateway.onboardingUrl).openConnection() as HttpURLConnection
        try {
            assertEquals(200, conn.responseCode)
            val cookie = conn.getHeaderField("Set-Cookie")
            assertNotNull("Set-Cookie header must be issued on initial onboarding", cookie)
            assertTrue("Cookie must contain glide_web_session", cookie.contains("glide_web_session="))
            assertTrue("Cookie must have HttpOnly", cookie.contains("HttpOnly"))
            assertTrue("Cookie must have SameSite=Strict", cookie.contains("SameSite=Strict"))
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun pathTraversalAttacks_areSanitizedAndBlocked() {
        // 1. Traversal download
        val downloadUrl = "http://127.0.0.1:${gateway.boundPort}/api/v1/download/../../etc/passwd"
        val downloadConn = URL(downloadUrl).openConnection() as HttpURLConnection
        try {
            downloadConn.setRequestProperty("Cookie", "glide_web_session=${gateway.token}")
            assertEquals(404, downloadConn.responseCode)
        } finally {
            downloadConn.disconnect()
        }

        // 2. Traversal upload
        val uploadBytes = "Traversal attack payload".toByteArray(Charsets.UTF_8)
        val uploadUrl = "http://127.0.0.1:${gateway.boundPort}/api/v1/upload"
        val uploadConn = URL(uploadUrl).openConnection() as HttpURLConnection
        try {
            uploadConn.requestMethod = "POST"
            uploadConn.doOutput = true
            uploadConn.setRequestProperty("Cookie", "glide_web_session=${gateway.token}")
            uploadConn.setRequestProperty("X-File-Name", java.net.URLEncoder.encode("../../../etc/shadow", "UTF-8"))
            uploadConn.setRequestProperty("Content-Type", "application/octet-stream")
            uploadConn.setFixedLengthStreamingMode(uploadBytes.size)

            uploadConn.outputStream.use { it.write(uploadBytes) }

            assertEquals(200, uploadConn.responseCode)
            val resp = uploadConn.inputStream.readBytes().toString(Charsets.UTF_8)
            assertTrue(resp.contains("\"status\":\"accepted\""))
            assertTrue(resp.contains("\"fileName\":\"etc_shadow\""))

            val sanitizedFile = File(quarantineDir, "etc_shadow")
            assertTrue("Sanitized leaf file must exist safely in quarantine", sanitizedFile.exists())
        } finally {
            uploadConn.disconnect()
        }
    }

    @Test
    fun serverBanner_isSuppressed() {
        val conn = URL(gateway.onboardingUrl).openConnection() as HttpURLConnection
        try {
            val serverHeader = conn.getHeaderField("Server")
            assertNull("Server header must be suppressed to avoid fingerprinting", serverHeader)
        } finally {
            conn.disconnect()
        }
    }
}
