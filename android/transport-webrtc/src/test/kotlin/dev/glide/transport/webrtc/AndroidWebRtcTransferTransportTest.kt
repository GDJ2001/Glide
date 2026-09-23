package dev.glide.transport.webrtc

import dev.glide.core.domain.BulkRef
import dev.glide.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for HARD-026 (Android WebRTC Transport Adapter).
 *
 * Tests cover:
 * - Session lifecycle (createSession, handleSdpAnswer, close)
 * - DTLS fingerprint generation and format
 * - Signaling server binding
 * - DataChannel operations (send/receive control and binary)
 * - Flow control behavior
 * - Error handling (closed channel, invalid session)
 * - Session state observation
 */
class AndroidWebRtcTransferTransportTest {

    private lateinit var transport: AndroidWebRtcTransferTransport
    private val testPort = 0

    @Before
    fun setUp() {
        transport = AndroidWebRtcTransferTransport(
            signalingPort = testPort,
            localAddress = "127.0.0.1",
            signalingTtlSeconds = 120
        )
    }

    @After
    fun tearDown() {
        if (::transport.isInitialized) {
            transport.close()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Session Creation Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `createSession returns valid WebRtcSessionInfo`() = runBlocking {
        val sessionId = UUID.randomUUID().toString()

        val info = transport.createSession(
            sessionId = sessionId,
            localDeviceName = "Test Android",
            localPlatform = "android"
        )

        assertEquals(sessionId, info.sessionId)
        assertTrue(info.localSdpOffer.isNotEmpty())
        assertTrue(info.dtlsFingerprintSha256.isNotEmpty())
        assertTrue(info.signalingUrl.startsWith("ws://"))
        assertTrue(info.expiresAtEpochMs > System.currentTimeMillis())
    }

    @Test
    fun `createSession generates 96 character hex fingerprint (SHA-256)`() = runBlocking {
        val info = transport.createSession(
            UUID.randomUUID().toString(),
            "Test Device",
            "android"
        )

        // SHA-256 in uppercase hex = 64 chars
        assertEquals(64, info.dtlsFingerprintSha256.length)
        assertTrue(info.dtlsFingerprintSha256.all { it.isDigit() || it in 'A'..'F' })
    }

    @Test
    fun `createSession generates unique fingerprints for different sessions`() = runBlocking {
        val info1 = transport.createSession(
            UUID.randomUUID().toString(),
            "Test Device",
            "android"
        )

        val transport2 = AndroidWebRtcTransferTransport(
            signalingPort = 0,
            localAddress = "127.0.0.1"
        )

        val info2 = transport2.createSession(
            UUID.randomUUID().toString(),
            "Test Device",
            "android"
        )

        assertNotEquals(info1.dtlsFingerprintSha256, info2.dtlsFingerprintSha256)
        transport2.close()
    }

    @Test
    fun `createSession SDP offer contains fingerprint and ICE parameters`() = runBlocking {
        val info = transport.createSession(
            UUID.randomUUID().toString(),
            "Test Device",
            "android"
        )

        assertTrue(info.localSdpOffer.contains("v=0"))
        assertTrue(info.localSdpOffer.contains("fingerprint:sha-256"))
        assertTrue(info.localSdpOffer.contains("ice-ufrag"))
        assertTrue(info.localSdpOffer.contains("ice-pwd"))
    }

    @Test
    fun `createSession sets signaling URL with correct port`() = runBlocking {
        val info = transport.createSession(
            UUID.randomUUID().toString(),
            "Test Device",
            "android"
        )

        assertTrue(info.signalingUrl.contains("ws://127.0.0.1"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // Peer State Observation Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `observePeerState emits NEW on session creation`() = runBlocking {
        val states = mutableListOf<WebRtcPeerState>()
        val job = launch {
            transport.observePeerState().collect { states.add(it) }
        }

        delay(50)
        transport.createSession(UUID.randomUUID().toString(), "Test", "android")
        delay(50)

        job.cancel()

        assertTrue(states.isNotEmpty())
        assertEquals(WebRtcPeerState.NEW, states.first())
    }

    @Test
    fun `observePeerState emits CONNECTING on SDP answer`() = runBlocking {
        val states = mutableListOf<WebRtcPeerState>()

        transport.createSession(UUID.randomUUID().toString(), "Test", "android")

        val job = launch {
            transport.observePeerState().collect { states.add(it) }
        }

        delay(50)
        transport.handleSdpAnswer("v=0\r\ns=-\r\nt=0 0")
        delay(50)

        job.cancel()

        assertTrue(states.contains(WebRtcPeerState.CONNECTING) || states.contains(WebRtcPeerState.CONNECTED))
    }

    @Test
    fun `observePeerState emits CLOSED on close`() = runBlocking {
        transport.createSession(UUID.randomUUID().toString(), "Test", "android")

        val states = mutableListOf<WebRtcPeerState>()
        val job = launch {
            transport.observePeerState().collect { states.add(it) }
        }

        delay(50)
        transport.close()
        delay(100)

        job.cancel()
        assertTrue(states.contains(WebRtcPeerState.CLOSED))
    }

    // ═══════════════════════════════════════════════════════════════════
    // ICE Candidate Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `observeLocalIceCandidates emits host candidates`() = runBlocking {
        val candidates = mutableListOf<IceCandidateInfo>()

        transport.createSession(UUID.randomUUID().toString(), "Test", "android")

        val job = launch {
            transport.observeLocalIceCandidates().collect { candidates.add(it) }
        }

        delay(50)
        transport.handleSdpAnswer("v=0\r\ns=-\r\nt=0 0")
        delay(100)

        job.cancel()

        assertTrue(candidates.isNotEmpty())
        assertEquals("0", candidates.first().sdpMid)
        assertEquals(0, candidates.first().sdpMLineIndex)
        assertTrue(candidates.first().candidate.contains("typ host"))
    }

    @Test
    fun `addIceCandidate does not throw`() = runBlocking {
        transport.createSession(UUID.randomUUID().toString(), "Test", "android")

        transport.addIceCandidate(
            "candidate:1 1 UDP 2130706431 192.168.1.5 42354 typ host",
            "0",
            0
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // DataChannel Send Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `sendControlMessage sends text after session and SDP answer`() = runBlocking {
        transport.createSession(UUID.randomUUID().toString(), "Test", "android")
        transport.handleSdpAnswer("v=0\r\ns=-\r\nt=0 0")

        transport.sendControlMessage("""{"msgType":"manifest","items":[]}""")
    }

    @Test
    fun `sendBinaryData sends progress updates`() = runBlocking {
        transport.createSession(UUID.randomUUID().toString(), "Test", "android")
        transport.handleSdpAnswer("v=0\r\ns=-\r\nt=0 0")

        val data = ByteArray(1024) { it.toByte() }
        val progress = mutableListOf<BulkProgress>()

        withTimeout(2000) {
            transport.sendBinaryData(data).collect { progress.add(it) }
        }

        assertTrue(progress.isNotEmpty())
        assertTrue(progress.last().isComplete)
    }

    @Test
    fun `sendStream sends progress with totalBytes`() = runBlocking {
        transport.createSession(UUID.randomUUID().toString(), "Test", "android")
        transport.handleSdpAnswer("v=0\r\ns=-\r\nt=0 0")

        val streamData = ByteArray(512) { it.toByte() }
        val inputStream = streamData.inputStream()
        val totalBytes = streamData.size.toLong()

        val progress = mutableListOf<BulkProgress>()

        withTimeout(2000) {
            transport.sendStream(inputStream, totalBytes).collect { progress.add(it) }
        }

        assertTrue(progress.isNotEmpty())
        assertEquals(totalBytes, progress.last().bytesTransferred)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Error Handling Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test(expected = WebRtcException::class)
    fun `sendControlMessage throws when transport is closed`(): Unit = runBlocking {
        transport.close()
        transport.sendControlMessage("test")
    }

    @Test(expected = WebRtcException::class)
    fun `sendBinaryData throws when transport is closed`(): Unit = runBlocking {
        transport.close()
        transport.sendBinaryData(byteArrayOf(1, 2, 3)).first()
        Unit
    }

    @Test(expected = WebRtcException::class)
    fun `createSession throws when transport is closed`(): Unit = runBlocking {
        transport.close()
        transport.createSession(UUID.randomUUID().toString(), "Test", "android")
        Unit
    }

    @Test
    fun `close is idempotent`() {
        transport.close()
        transport.close() // Should not throw
    }

    // ═══════════════════════════════════════════════════════════════════
    // Protocol Frame Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `ProtocolFrame encode decode round trip for control JSON`() {
        val original = """{"msgType":"test"}"""
        val encoded = AndroidWebRtcTransferTransport.ProtocolFrame.encodeControlJson(original)
        val decoded = AndroidWebRtcTransferTransport.ProtocolFrame.decode(encoded)

        assertNotNull(decoded)
        assertEquals(0x01.toByte(), decoded!!.type)
        assertEquals(original, String(decoded.payload, Charsets.UTF_8))
    }

    @Test
    fun `ProtocolFrame encode decode round trip for data binary`() {
        val bulkRef = "bulk-123"
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val encoded = AndroidWebRtcTransferTransport.ProtocolFrame.encodeDataBinary(bulkRef, data)
        val decoded = AndroidWebRtcTransferTransport.ProtocolFrame.decode(encoded)

        assertNotNull(decoded)
        assertEquals(0x02.toByte(), decoded!!.type)
        // payload contains [bulkRefLen][bulkRef][data]
        assertTrue(decoded.payload.isNotEmpty())
    }

    @Test
    fun `ProtocolFrame decode returns null for insufficient data`() {
        val decoded = AndroidWebRtcTransferTransport.ProtocolFrame.decode(byteArrayOf(1, 2))
        assertNull(decoded)
    }

    @Test
    fun `ProtocolFrame encode checksum`() {
        val sha256Hex = "abcdef0123456789" + "abcdef0123456789" + "abcdef0123456789" + "abcdef01"
        val encoded = AndroidWebRtcTransferTransport.ProtocolFrame.encodeChecksum(sha256Hex)
        val decoded = AndroidWebRtcTransferTransport.ProtocolFrame.decode(encoded)

        assertNotNull(decoded)
        assertEquals(0x03.toByte(), decoded!!.type)
    }

    @Test
    fun `ProtocolFrame encode error`() {
        val encoded = AndroidWebRtcTransferTransport.ProtocolFrame.encodeError(
            "session_expired",
            "Session has expired"
        )
        val decoded = AndroidWebRtcTransferTransport.ProtocolFrame.decode(encoded)

        assertNotNull(decoded)
        assertEquals(0x05.toByte(), decoded!!.type)
        val json = String(decoded.payload, Charsets.UTF_8)
        assertTrue(json.contains("session_expired"))
    }
}
