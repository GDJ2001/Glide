package dev.glide.transport.lan.tls

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import javax.net.ssl.SSLHandshakeException

class LanTlsLoopbackIntegrationTest {

    private val loopbackAddress = InetAddress.getByName("127.0.0.1")

    @Test
    fun testSuccessfulTls13AndAlpnHandshake(): Unit = runBlocking {
        val identity = LanLeaseIdentity.create(preferAndroidKeyStore = false)
        val listener = LanListener(
            identity = identity,
            bindAddress = loopbackAddress,
            port = 0,
            allowLoopbackForTesting = true
        )

        try {
            val connector = PinnedTlsConnector(
                allowLoopbackForTesting = true
            )

            val clientSocket = connector.connect(
                address = loopbackAddress,
                port = listener.localPort,
                expectedSpkiSha256Hex = identity.spkiSha256
            )

            val serverSocket = withTimeout(5_000) {
                listener.incomingSockets.receive()
            }

            assertNotNull(clientSocket)
            assertNotNull(serverSocket)

            // Verify TLS 1.3 protocol
            assertEquals("TLSv1.3", clientSocket.session.protocol)
            assertEquals("TLSv1.3", serverSocket.session.protocol)

            // Verify ALPN negotiation: glide-lan/1
            assertEquals("glide-lan/1", clientSocket.applicationProtocol)
            assertEquals("glide-lan/1", serverSocket.applicationProtocol)

            // Test bidirectional encrypted communication
            val testMessage = "Hello Glide LAN TLS 1.3".toByteArray(Charsets.UTF_8)
            clientSocket.outputStream.write(testMessage)
            clientSocket.outputStream.flush()

            val buffer = ByteArray(testMessage.size)
            var bytesRead = 0
            while (bytesRead < testMessage.size) {
                val r = serverSocket.inputStream.read(buffer, bytesRead, testMessage.size - bytesRead)
                if (r < 0) break
                bytesRead += r
            }
            assertEquals(testMessage.size, bytesRead)
            assertEquals("Hello Glide LAN TLS 1.3", String(buffer, Charsets.UTF_8))

            clientSocket.close()
            serverSocket.close()
        } finally {
            listener.close()
            identity.close()
        }
    }

    @Test
    fun testMismatchPinRejectsBeforeDataSent(): Unit = runBlocking {
        val serverIdentity = LanLeaseIdentity.create(preferAndroidKeyStore = false)
        val wrongIdentity = LanLeaseIdentity.create(preferAndroidKeyStore = false)

        val listener = LanListener(
            identity = serverIdentity,
            bindAddress = loopbackAddress,
            port = 0,
            allowLoopbackForTesting = true
        )

        try {
            val connector = PinnedTlsConnector(
                allowLoopbackForTesting = true
            )

            // Attempt connection with mismatched SPKI pin
            assertThrows(IOException::class.java) {
                connector.connect(
                    address = loopbackAddress,
                    port = listener.localPort,
                    expectedSpkiSha256Hex = wrongIdentity.spkiSha256
                )
            }
        } finally {
            listener.close()
            serverIdentity.close()
            wrongIdentity.close()
        }
    }

    @Test
    fun testProductionConnectorRejectsLoopback() {
        val connector = PinnedTlsConnector(
            allowLoopbackForTesting = false // Production setting
        )

        val identity = LanLeaseIdentity.create(preferAndroidKeyStore = false)

        // Must reject loopback address with SecurityException
        assertThrows(SecurityException::class.java) {
            connector.connect(
                address = loopbackAddress,
                port = 49152,
                expectedSpkiSha256Hex = identity.spkiSha256
            )
        }
    }

    @Test
    fun testRateLimiterBlocksAfterExcessiveFailures() {
        val rateLimiter = LanRateLimiter(
            maxFailuresPerAddress = 5,
            windowMillis = 60_000,
            cooldownMillis = 30_000
        )

        assertFalse(rateLimiter.isBlocked(loopbackAddress))

        for (i in 1..4) {
            rateLimiter.recordFailure(loopbackAddress)
            assertFalse(rateLimiter.isBlocked(loopbackAddress))
        }

        // 5th failure triggers cooldown
        rateLimiter.recordFailure(loopbackAddress)
        assertTrue(rateLimiter.isBlocked(loopbackAddress))
    }

    @Test
    fun testMismatchAlpnFailsHandshake(): Unit = runBlocking {
        val identity = LanLeaseIdentity.create(preferAndroidKeyStore = false)
        val listener = LanListener(
            identity = identity,
            bindAddress = loopbackAddress,
            port = 0,
            allowLoopbackForTesting = true
        )

        try {
            val trustManager = PinnedTrustManager(identity.spkiSha256)
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLSv1.3").apply {
                init(null, arrayOf(trustManager), java.security.SecureRandom())
            }

            val rawSocket = java.net.Socket()
            rawSocket.connect(java.net.InetSocketAddress(loopbackAddress, listener.localPort), 5_000)

            val sslSocket = sslContext.socketFactory.createSocket(
                rawSocket,
                loopbackAddress.hostAddress,
                listener.localPort,
                true
            ) as javax.net.ssl.SSLSocket

            sslSocket.useClientMode = true
            sslSocket.enabledProtocols = arrayOf("TLSv1.3")

            // Offer mismatched ALPN
            val params = sslSocket.sslParameters
            params.applicationProtocols = arrayOf("incompatible-proto/1")
            sslSocket.sslParameters = params
            sslSocket.soTimeout = 5_000

            // Either startHandshake fails or applicationProtocol is not glide-lan/1
            try {
                sslSocket.startHandshake()
                assertFalse("glide-lan/1" == sslSocket.applicationProtocol)
            } catch (e: Exception) {
                // Expected handshake or alert rejection
                assertTrue(e is IOException)
            } finally {
                try { sslSocket.close() } catch (_: Exception) {}
                try { rawSocket.close() } catch (_: Exception) {}
            }
        } finally {
            listener.close()
            identity.close()
        }
    }
}
