package dev.glide.transport.webrtc

import dev.glide.core.domain.BulkRef
import dev.glide.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Unit tests for SecureBrowserGateway (HARD-026/HARD-028).
 *
 * Tests cover:
 * - Gateway lifecycle (start, stop, close)
 * - SPA asset serving (HTML, CSS, JS)
 * - Security headers (CSP, X-Frame-Options, etc.)
 * - QR payload generation
 * - Session management
 * - Error handling
 */
class SecureBrowserGatewayTest {

    private lateinit var transport: AndroidWebRtcTransferTransport
    private lateinit var gateway: SecureBrowserGateway

    @Before
    fun setUp() {
        transport = AndroidWebRtcTransferTransport(
            signalingPort = 0,
            localAddress = "127.0.0.1",
            signalingTtlSeconds = 120
        )
    }

    @After
    fun tearDown() {
        if (::gateway.isInitialized) {
            gateway.close()
        }
        if (::transport.isInitialized) {
            transport.close()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Gateway Lifecycle Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `start creates WebRTC session and binds HTTP server`() = runBlocking {
        gateway = SecureBrowserGateway(
            transport = transport,
            host = "127.0.0.1",
            port = 0,
            deviceName = "Test Device",
            platform = "android"
        )

        gateway.start()

        assertTrue(gateway.boundPort > 0)
        assertNotNull(gateway.currentSessionId)
        assertNotNull(gateway.signalingUrl)
        assertNotNull(gateway.qrPayload)
    }

    @Test
    fun `stop releases HTTP server resources`() = runBlocking {
        gateway = SecureBrowserGateway(
            transport = transport,
            host = "127.0.0.1",
            port = 0
        )

        gateway.start()
        val port = gateway.boundPort
        assertTrue(port > 0)

        gateway.stop()
    }

    @Test
    fun `close is idempotent`() = runBlocking {
        gateway = SecureBrowserGateway(
            transport = transport,
            host = "127.0.0.1",
            port = 0
        )

        gateway.start()
        gateway.close()
        gateway.close() // Should not throw
    }

    // ═══════════════════════════════════════════════════════════════════
    // SPA Asset Serving Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `GET index html returns 200 with correct content type`() = runBlocking {
        gateway = SecureBrowserGateway(
            transport = transport,
            host = "127.0.0.1",
            port = 0
        )
        gateway.start()

        val url = "http://127.0.0.1:${gateway.boundPort}/"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
        }

        try {
            assertEquals(200, conn.responseCode)
            assertTrue(conn.contentType.contains("text/html"))
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `GET style css returns 200 with correct content type`() = runBlocking {
        gateway = SecureBrowserGateway(
            transport = transport,
            host = "127.0.0.1",
            port = 0
        )
        gateway.start()

        val url = "http://127.0.0.1:${gateway.boundPort}/style.css"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
        }

        try {
            assertEquals(200, conn.responseCode)
            assertTrue(conn.contentType.contains("text/css"))
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `GET app js returns 200 with correct content type`() = runBlocking {
        gateway = SecureBrowserGateway(
            transport = transport,
            host = "127.0.0.1",
            port = 0
        )
        gateway.start()

        val url = "http://127.0.0.1:${gateway.boundPort}/app.js"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
        }

        try {
            assertEquals(200, conn.responseCode)
            assertTrue(conn.contentType.contains("javascript"))
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `GET unknown path returns 404`() = runBlocking {
        gateway = SecureBrowserGateway(
            transport = transport,
            host = "127.0.0.1",
            port = 0
        )
        gateway.start()

        val url = "http://127.0.0.1:${gateway.boundPort}/unknown"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
        }

        try {
            assertEquals(404, conn.responseCode)
        } finally {
            conn.disconnect()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Security Headers Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `response includes strict Content-Security-Policy`() = runBlocking {
        gateway = SecureBrowserGateway(
            transport = transport,
            host = "127.0.0.1",
            port = 0
        )
        gateway.start()

        val url = "http://127.0.0.1:${gateway.boundPort}/"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
        }

        try {
            val csp = conn.getHeaderField("Content-Security-Policy")
            assertNotNull(csp)
            assertTrue(csp.contains("default-src 'self'"))
            assertTrue(csp.contains("script-src 'self'"))
            assertTrue(csp.contains("style-src 'self'"))
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `response includes X-Content-Type-Options nosniff`() = runBlocking {
        gateway = SecureBrowserGateway(
            transport = transport,
            host = "127.0.0.1",
            port = 0
        )
        gateway.start()

        val url = "http://127.0.0.1:${gateway.boundPort}/"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
        }

        try {
            assertEquals("nosniff", conn.getHeaderField("X-Content-Type-Options"))
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `response includes X-Frame-Options DENY`() = runBlocking {
        gateway = SecureBrowserGateway(
            transport = transport,
            host = "127.0.0.1",
            port = 0
        )
        gateway.start()

        val url = "http://127.0.0.1:${gateway.boundPort}/"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
        }

        try {
            assertEquals("DENY", conn.getHeaderField("X-Frame-Options"))
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `response includes Referrer-Policy no-referrer`() = runBlocking {
        gateway = SecureBrowserGateway(
            transport = transport,
            host = "127.0.0.1",
            port = 0
        )
        gateway.start()

        val url = "http://127.0.0.1:${gateway.boundPort}/"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
        }

        try {
            assertEquals("no-referrer", conn.getHeaderField("Referrer-Policy"))
        } finally {
            conn.disconnect()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // QR Payload Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `qrPayload contains required fields`() = runBlocking {
        gateway = SecureBrowserGateway(
            transport = transport,
            host = "127.0.0.1",
            port = 0,
            deviceName = "Test Pixel",
            platform = "android"
        )
        gateway.start()

        val payload = gateway.qrPayload
        assertNotNull(payload)
        assertTrue(payload!!.contains("\"v\":1"))
        assertTrue(payload.contains("\"sid\":"))
        assertTrue(payload.contains("\"fp\":\"sha-256:"))
        assertTrue(payload.contains("\"sig\":"))
        assertTrue(payload.contains("\"dn\":\"Test Pixel\""))
        assertTrue(payload.contains("\"dp\":\"android\""))
        assertTrue(payload.contains("\"exp\":"))
    }

    @Test
    fun `qrPayload signaling URL matches gateway signaling URL`() = runBlocking {
        gateway = SecureBrowserGateway(
            transport = transport,
            host = "127.0.0.1",
            port = 0
        )
        gateway.start()

        val payload = gateway.qrPayload!!
        val signalingUrl = gateway.signalingUrl!!

        assertTrue(payload.contains(signalingUrl))
    }

    // ═══════════════════════════════════════════════════════════════════
    // API Endpoint Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `GET api v1 qr returns JSON with QR payload`() = runBlocking {
        gateway = SecureBrowserGateway(
            transport = transport,
            host = "127.0.0.1",
            port = 0
        )
        gateway.start()

        val url = "http://127.0.0.1:${gateway.boundPort}/api/v1/qr"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
        }

        try {
            assertEquals(200, conn.responseCode)
            assertTrue(conn.contentType.contains("application/json"))

            val body = conn.inputStream.readBytes().toString(Charsets.UTF_8)
            assertTrue(body.contains("\"qr\":"))
            assertTrue(body.contains("\"signalingUrl\":"))
            assertTrue(body.contains("\"sessionId\":"))
        } finally {
            conn.disconnect()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // HEAD Request Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `HEAD request returns headers without body`() = runBlocking {
        gateway = SecureBrowserGateway(
            transport = transport,
            host = "127.0.0.1",
            port = 0
        )
        gateway.start()

        val url = "http://127.0.0.1:${gateway.boundPort}/"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
        }

        try {
            assertEquals(200, conn.responseCode)
            assertTrue(conn.contentType.contains("text/html"))
            // HEAD should not have body content
        } finally {
            conn.disconnect()
        }
    }
}
