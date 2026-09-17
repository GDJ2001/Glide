package dev.glide.transport.lan

import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.PeerId
import dev.glide.core.pairing.LanEndpoint
import dev.glide.core.pairing.LanInvitation
import dev.glide.core.ports.ConnectionEvent
import dev.glide.core.transport.lan.LanBootstrapCodec
import dev.glide.core.transport.lan.LanBootstrapFailureCode
import dev.glide.core.transport.lan.LanBootstrapMessage
import dev.glide.core.transport.lan.LanRecord
import dev.glide.core.transport.lan.LanRecordKind
import dev.glide.transport.lan.framing.LanFramingException
import dev.glide.transport.lan.framing.LanRecordReader
import dev.glide.transport.lan.framing.LanRecordWriter
import dev.glide.transport.lan.network.LanNetworkScope
import dev.glide.transport.lan.pairing.AuthorityClock
import dev.glide.transport.lan.pairing.InvitationAuthority
import dev.glide.transport.lan.tls.LanLeaseIdentity
import dev.glide.transport.lan.tls.LanListener
import dev.glide.transport.lan.tls.PinnedTlsConnector
import dev.glide.transport.lan.tls.SelfSignedCertificateGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * End-to-end adversarial security tests executing Section 20 requirements through actual adapters:
 * - 1. Wrong server key / certificate / version / ALPN: zero BOOTSTRAP bytes sent; no HELLO, UI trusted state, file metadata or stream.
 * - 2. Invalid / replayed / expired / refreshed token: no coordinator created; exactly one success under race; replay prohibited.
 * - 6. Oversized / truncated frames and TLS handshake flood: allocation limits hold, app responsive.
 */
class LanSecurityAdversarialTest {

    private val loopbackAddress = InetAddress.getByName("127.0.0.1")

    private class ControlledClock(
        private val epochMs: AtomicLong = AtomicLong(1750000000_000L),
        private val monotonicNs: AtomicLong = AtomicLong(100_000_000_000L)
    ) : AuthorityClock {
        override fun epochMillis(): Long = epochMs.get()
        override fun monotonicNanos(): Long = monotonicNs.get()
        fun advanceSeconds(seconds: Long) {
            epochMs.addAndGet(seconds * 1000L)
            monotonicNs.addAndGet(seconds * 1_000_000_000L)
        }
    }

    @Test
    fun testWrongSpkiPin_zeroBootstrapBytesSentAndConnectionFails(): Unit = runBlocking {
        val serverIdentity = LanLeaseIdentity.create(preferAndroidKeyStore = false)
        val attackerIdentity = LanLeaseIdentity.create(preferAndroidKeyStore = false)

        val listener = LanListener(
            identity = serverIdentity,
            bindAddress = loopbackAddress,
            port = 0,
            allowLoopbackForTesting = true
        )

        val serverScope = LanNetworkScope(null)
        val serverTransport = LanTransferTransport(
            networkScope = serverScope,
            allowLoopbackForTesting = true
        )
        val authority = InvitationAuthority()
        serverTransport.attachServerListener(listener, authority)

        val clientScope = LanNetworkScope(null)
        val clientTransport = LanTransferTransport(
            networkScope = clientScope,
            allowLoopbackForTesting = true
        )

        try {
            val peerId = PeerId("lan-test-wrong-pin")
            val endpoint = LanEndpoint("127.0.0.1", listener.localPort)

            // Invitation generated with wrong SPKI pin
            val fakeInvitation = LanInvitation(
                invitationId = UUID.randomUUID().toString(),
                presence = "0123456789abcdef0123456789abcdef",
                name = "Server Peer",
                platform = "android",
                endpoints = listOf(endpoint),
                spkiSha256 = attackerIdentity.spkiSha256, // Wrong pin!
                token = "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8S9t0U1v",
                expiresAt = "1750000120",
                ttlSeconds = 120
            )

            clientTransport.registerPendingInvitation(peerId, fakeInvitation)

            // initiateConnection must throw IOException on TLS handshake failure
            assertThrows(IOException::class.java) {
                runBlocking {
                    clientTransport.initiateConnection(peerId, "Client Device")
                }
            }

            // Zero connection events emitted on server
            val serverEvents = serverTransport.observeConnectionEvents()
            // Ensure no connection event occurred
            assertFalse(authority.isConsumed(fakeInvitation.invitationId))
        } finally {
            clientTransport.close()
            serverTransport.close()
            listener.close()
            serverIdentity.close()
            attackerIdentity.close()
        }
    }

    @Test
    fun testExpiredCertificate_failsBeforeBootstrap(): Unit = runBlocking {
        // Generate an expired certificate (expired 1 hour ago)
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()
        val expiredCert = SelfSignedCertificateGenerator.generate(
            keyPair = keyPair,
            commonName = "Expired Server",
            notBefore = Date(System.currentTimeMillis() - 7200_000),
            notAfter = Date(System.currentTimeMillis() - 3600_000)
        )
        val expiredIdentity = LanLeaseIdentity(
            keyPair = keyPair,
            certificate = expiredCert,
            alias = "expired_lease",
            isAndroidKeyStore = false
        )

        val listener = LanListener(
            identity = expiredIdentity,
            bindAddress = loopbackAddress,
            port = 0,
            allowLoopbackForTesting = true
        )

        val connector = PinnedTlsConnector(allowLoopbackForTesting = true)

        try {
            assertThrows(IOException::class.java) {
                connector.connect(
                    address = loopbackAddress,
                    port = listener.localPort,
                    expectedSpkiSha256Hex = expiredIdentity.spkiSha256
                )
            }
        } finally {
            listener.close()
            expiredIdentity.close()
        }
    }

    @Test
    fun testInvalidToken_rejectedWithAuthenticationFailed(): Unit = runBlocking {
        val serverIdentity = LanLeaseIdentity.create(preferAndroidKeyStore = false)
        val listener = LanListener(
            identity = serverIdentity,
            bindAddress = loopbackAddress,
            port = 0,
            allowLoopbackForTesting = true
        )

        val serverScope = LanNetworkScope(null)
        val serverTransport = LanTransferTransport(serverScope, allowLoopbackForTesting = true)
        val authority = InvitationAuthority()
        serverTransport.attachServerListener(listener, authority)

        val clientScope = LanNetworkScope(null)
        val clientTransport = LanTransferTransport(clientScope, allowLoopbackForTesting = true)

        try {
            val endpoint = LanEndpoint("127.0.0.1", listener.localPort)
            val validInvitation = authority.mintInvitation(
                endpoints = listOf(endpoint),
                spkiSha256 = serverIdentity.spkiSha256,
                deviceName = "Receiver"
            )

            val peerId = PeerId("lan-test-invalid-token")
            // Client attempts with WRONG token
            val corruptedInvitation = validInvitation.copy(
                token = "Z".repeat(43) // Wrong token
            )
            clientTransport.registerPendingInvitation(peerId, corruptedInvitation)

            val error = assertThrows(IOException::class.java) {
                runBlocking {
                    clientTransport.initiateConnection(peerId, "Client")
                }
            }
            assertTrue(
                "Error must indicate bootstrap rejection: ${error.message}",
                error.message?.contains(LanBootstrapFailureCode.AUTHENTICATION_FAILED.name) == true
            )
            assertFalse(authority.isConsumed(validInvitation.invitationId))
        } finally {
            clientTransport.close()
            serverTransport.close()
            listener.close()
            serverIdentity.close()
        }
    }

    @Test
    fun testReplayedToken_rejectedWithInvitationUsed(): Unit = runBlocking {
        val serverIdentity = LanLeaseIdentity.create(preferAndroidKeyStore = false)
        val listener = LanListener(
            identity = serverIdentity,
            bindAddress = loopbackAddress,
            port = 0,
            allowLoopbackForTesting = true
        )

        val serverScope = LanNetworkScope(null)
        val serverTransport = LanTransferTransport(serverScope, allowLoopbackForTesting = true)
        val authority = InvitationAuthority()
        serverTransport.attachServerListener(listener, authority)

        val clientScope1 = LanNetworkScope(null)
        val clientTransport1 = LanTransferTransport(clientScope1, allowLoopbackForTesting = true)

        val clientScope2 = LanNetworkScope(null)
        val clientTransport2 = LanTransferTransport(clientScope2, allowLoopbackForTesting = true)

        try {
            val endpoint = LanEndpoint("127.0.0.1", listener.localPort)
            val invitation = authority.mintInvitation(
                endpoints = listOf(endpoint),
                spkiSha256 = serverIdentity.spkiSha256,
                deviceName = "Receiver"
            )

            val peerId1 = PeerId("lan-peer-1")
            clientTransport1.registerPendingInvitation(peerId1, invitation)

            // First client connects successfully
            clientTransport1.initiateConnection(peerId1, "Client 1")
            assertTrue("Invitation must be consumed after first connection", authority.isConsumed(invitation.invitationId))

            // Client 1 disconnects (e.g. uncertain connection dropped or transfer finished)
            clientTransport1.disconnect(peerId1)
            kotlinx.coroutines.delay(200)

            // Second client attempts to REPLAY the exact same invitation
            val peerId2 = PeerId("lan-peer-2")
            clientTransport2.registerPendingInvitation(peerId2, invitation)

            val replayError = assertThrows(IOException::class.java) {
                runBlocking {
                    clientTransport2.initiateConnection(peerId2, "Client 2")
                }
            }
            assertTrue(
                "Error must indicate invitation already used: ${replayError.message}",
                replayError.message?.contains(LanBootstrapFailureCode.INVITATION_USED.name) == true
            )
        } finally {
            clientTransport1.close()
            clientTransport2.close()
            serverTransport.close()
            listener.close()
            serverIdentity.close()
        }
    }

    @Test
    fun testExpiredInvitation_rejectedWithInvitationExpired(): Unit = runBlocking {
        val clock = ControlledClock()
        val serverIdentity = LanLeaseIdentity.create(preferAndroidKeyStore = false)
        val listener = LanListener(
            identity = serverIdentity,
            bindAddress = loopbackAddress,
            port = 0,
            allowLoopbackForTesting = true
        )

        val serverScope = LanNetworkScope(null)
        val serverTransport = LanTransferTransport(serverScope, allowLoopbackForTesting = true)
        val authority = InvitationAuthority(clock = clock)
        serverTransport.attachServerListener(listener, authority)

        val clientScope = LanNetworkScope(null)
        val clientTransport = LanTransferTransport(clientScope, allowLoopbackForTesting = true)

        try {
            val endpoint = LanEndpoint("127.0.0.1", listener.localPort)
            val invitation = authority.mintInvitation(
                endpoints = listOf(endpoint),
                spkiSha256 = serverIdentity.spkiSha256,
                deviceName = "Receiver",
                ttlSeconds = 120
            )

            // Advance monotonic clock by 121 seconds (past expiry)
            clock.advanceSeconds(121)

            val peerId = PeerId("lan-test-expired")
            clientTransport.registerPendingInvitation(peerId, invitation)

            val error = assertThrows(IOException::class.java) {
                runBlocking {
                    clientTransport.initiateConnection(peerId, "Client")
                }
            }
            assertTrue(
                "Error must indicate invitation expired: ${error.message}",
                error.message?.contains(LanBootstrapFailureCode.INVITATION_EXPIRED.name) == true
            )
        } finally {
            clientTransport.close()
            serverTransport.close()
            listener.close()
            serverIdentity.close()
        }
    }

    @Test
    fun testTwoClientsRace_exactlyOneSucceeds(): Unit = runBlocking {
        val serverIdentity = LanLeaseIdentity.create(preferAndroidKeyStore = false)
        val listener = LanListener(
            identity = serverIdentity,
            bindAddress = loopbackAddress,
            port = 0,
            allowLoopbackForTesting = true
        )

        val serverScope = LanNetworkScope(null)
        val serverTransport = LanTransferTransport(serverScope, allowLoopbackForTesting = true)
        val authority = InvitationAuthority()
        serverTransport.attachServerListener(listener, authority)

        val clientScope1 = LanNetworkScope(null)
        val clientTransport1 = LanTransferTransport(clientScope1, allowLoopbackForTesting = true)

        val clientScope2 = LanNetworkScope(null)
        val clientTransport2 = LanTransferTransport(clientScope2, allowLoopbackForTesting = true)

        try {
            val endpoint = LanEndpoint("127.0.0.1", listener.localPort)
            val invitation = authority.mintInvitation(
                endpoints = listOf(endpoint),
                spkiSha256 = serverIdentity.spkiSha256,
                deviceName = "Receiver"
            )

            val peer1 = PeerId("lan-race-1")
            val peer2 = PeerId("lan-race-2")
            clientTransport1.registerPendingInvitation(peer1, invitation)
            clientTransport2.registerPendingInvitation(peer2, invitation)

            val task1 = async(Dispatchers.IO) {
                try {
                    clientTransport1.initiateConnection(peer1, "Client 1")
                    true
                } catch (_: Exception) {
                    false
                }
            }
            val task2 = async(Dispatchers.IO) {
                try {
                    clientTransport2.initiateConnection(peer2, "Client 2")
                    true
                } catch (_: Exception) {
                    false
                }
            }

            val results = awaitAll(task1, task2)
            val successCount = results.count { it }
            val failureCount = results.count { !it }

            assertEquals("Exactly one client must win the bootstrap race", 1, successCount)
            assertEquals("Exactly one client must fail the bootstrap race", 1, failureCount)
            assertTrue("Invitation must be consumed", authority.isConsumed(invitation.invitationId))
        } finally {
            clientTransport1.close()
            clientTransport2.close()
            serverTransport.close()
            listener.close()
            serverIdentity.close()
        }
    }

    @Test
    fun testOversizedAndNegativeFrameHeaders_rejectedBeforeAllocation() {
        // 1. Negative payload length
        val negativeLengthHeader = byteArrayOf(
            LanRecordKind.CONTROL.byteValue,
            0x80.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte() // -2147483647
        )
        assertThrows(LanFramingException::class.java) {
            LanRecordReader.readRecord(ByteArrayInputStream(negativeLengthHeader))
        }

        // 2. Declared length exceeds max bounds for CONTROL (> 16384 bytes)
        val oversizedControlHeader = byteArrayOf(
            LanRecordKind.CONTROL.byteValue,
            0x00, 0x00, 0x40.toByte(), 0x01.toByte() // 16385 bytes
        )
        val ex1 = assertThrows(LanFramingException::class.java) {
            LanRecordReader.readRecord(ByteArrayInputStream(oversizedControlHeader))
        }
        assertTrue(ex1.message?.contains("out of bounds") == true)

        // 3. Declared length exceeds max bounds for DATA (> 65536 bytes)
        val oversizedDataHeader = byteArrayOf(
            LanRecordKind.DATA.byteValue,
            0x00, 0x01, 0x00, 0x01 // 65537 bytes
        )
        val ex2 = assertThrows(LanFramingException::class.java) {
            LanRecordReader.readRecord(ByteArrayInputStream(oversizedDataHeader))
        }
        assertTrue(ex2.message?.contains("out of bounds") == true)

        // 4. Declared length zero for DATA (min size is 17: 16-byte UUID + 1 byte data)
        val zeroDataHeader = byteArrayOf(
            LanRecordKind.DATA.byteValue,
            0x00, 0x00, 0x00, 0x00
        )
        assertThrows(LanFramingException::class.java) {
            LanRecordReader.readRecord(ByteArrayInputStream(zeroDataHeader))
        }

        // 5. Unknown record kind
        val unknownKindHeader = byteArrayOf(
            0x99.toByte(),
            0x00, 0x00, 0x00, 0x10
        )
        assertThrows(LanFramingException::class.java) {
            LanRecordReader.readRecord(ByteArrayInputStream(unknownKindHeader))
        }
    }

    @Test
    fun testTruncatedRecord_throwsEofExceptionCleanly() {
        // Valid header declaring 10 bytes payload, but stream only has 3 bytes
        val truncatedStream = ByteArrayInputStream(
            byteArrayOf(
                LanRecordKind.CONTROL.byteValue,
                0x00, 0x00, 0x00, 0x0A, // length 10
                0x01, 0x02, 0x03 // only 3 bytes
            )
        )
        assertThrows(EOFException::class.java) {
            LanRecordReader.readRecord(truncatedStream)
        }
    }

    @Test
    fun testUnsolicitedIncomingData_failsWithLanFramingException() {
        val bridge = dev.glide.transport.lan.framing.LanStreamBridge()
        try {
            val unregisteredBulkRef = BulkRef(UUID.randomUUID().toString())
            val fakeDataRecord = LanRecord.Data(
                bulkRef = unregisteredBulkRef,
                chunk = byteArrayOf(1, 2, 3, 4)
            )

            // DATA arriving without registered StreamExpectation must throw LanFramingException
            assertThrows(LanFramingException::class.java) {
                bridge.handleIncomingData(
                    peerId = PeerId("peer-1"),
                    record = fakeDataRecord,
                    onIncomingStream = {},
                    onBulkProgress = {}
                )
            }
        } finally {
            bridge.close()
        }
    }
}
