package dev.glide.app.transfer

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.SessionId
import dev.glide.core.pairing.InvitationCodec
import dev.glide.core.pairing.LanEndpoint
import dev.glide.core.ports.ConnectionEvent
import dev.glide.core.ports.StreamExpectation
import dev.glide.transport.lan.LanTransferTransport
import dev.glide.transport.lan.network.LanNetworkScope
import dev.glide.transport.lan.pairing.InvitationAuthority
import dev.glide.transport.lan.tls.LanLeaseIdentity
import dev.glide.transport.lan.tls.LanListener
import dev.glide.transport.lan.tls.PinnedTlsConnector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID
import javax.net.ssl.SSLHandshakeException

/**
 * On-device instrumentation smoke test verifying secure LAN pairing, pinned TLS 1.3 handshake,
 * QR invitation parsing, and framed file streaming directly on Android platform hardware (LAN-013, M02).
 *
 * Runs on real Android API 29+ devices/emulators exercising:
 * - Real Android KeyStore EC P-256 lease key derivation.
 * - Loopback TLS 1.3 server listener and outbound client connector.
 * - SPKI SHA-256 leaf pin validation.
 * - Production QR invitation URI encoding/decoding (`glide://pair/v1#...`).
 * - Atomic single-use token consumption and replay rejection.
 * - Full framed control message and file byte stream exchange.
 * - Zero-byte file transfer handling.
 * - SHA-256 payload digest verification.
 */
@RunWith(AndroidJUnit4::class)
class LanDeviceTransferSmokeTest {

    private val testSessionId = SessionId(UUID.randomUUID().toString())
    private val testItemId = ItemId(UUID.randomUUID().toString())

    @Test
    fun testSecureLanTransferOverDeviceTlsLoopback(): Unit = runBlocking {
        assumeTrue("Requires Android API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loopbackAddress = InetAddress.getByName("127.0.0.1")

        val receiverScope = LanNetworkScope(context)
        val senderScope = LanNetworkScope(context)

        val receiverTransport = LanTransferTransport(
            networkScope = receiverScope,
            allowLoopbackForTesting = true
        )
        val senderTransport = LanTransferTransport(
            networkScope = senderScope,
            allowLoopbackForTesting = true
        )

        // 1. Generate real Android KeyStore EC P-256 lease identity
        val receiverIdentity = LanLeaseIdentity.create(preferAndroidKeyStore = true)
        assertNotNull(receiverIdentity.spkiSha256)
        assertEquals(64, receiverIdentity.spkiSha256.length)

        val listener = LanListener(
            identity = receiverIdentity,
            networkScope = receiverScope,
            bindAddress = loopbackAddress,
            port = 0,
            allowLoopbackForTesting = true
        )
        val authority = InvitationAuthority()

        try {
            receiverTransport.attachServerListener(listener, authority)

            // 2. Mint invitation and encode to production QR URI
            val receiverHost = loopbackAddress.hostAddress ?: "127.0.0.1"
            val mintedInvitation = authority.mintInvitation(
                endpoints = listOf(LanEndpoint(receiverHost, listener.localPort)),
                spkiSha256 = receiverIdentity.spkiSha256,
                deviceName = "Android Receiver Device"
            )

            val qrUri = InvitationCodec.encode(mintedInvitation)
            assertTrue(qrUri.startsWith("glide://pair/v1#"))

            // 3. Sender decodes using production parser
            val decodedInvitation = InvitationCodec.decode(qrUri)
            assertEquals(mintedInvitation.invitationId, decodedInvitation.invitationId)
            assertEquals(receiverIdentity.spkiSha256, decodedInvitation.spkiSha256)
            assertEquals(mintedInvitation.token, decodedInvitation.token)

            val serverPeerId = PeerId("lan-server-${UUID.randomUUID()}")
            senderTransport.registerPendingInvitation(serverPeerId, decodedInvitation)

            // 4. Setup connection event collectors
            val clientConnectionDeferred = CompletableDeferred<ConnectionEvent>()
            val clientJob = launch {
                clientConnectionDeferred.complete(
                    senderTransport.observeConnectionEvents().first { it is ConnectionEvent.Connected }
                )
            }

            val serverConnectionDeferred = CompletableDeferred<ConnectionEvent>()
            val serverJob = launch {
                serverConnectionDeferred.complete(
                    receiverTransport.observeConnectionEvents().first { it is ConnectionEvent.Connected }
                )
            }

            // 5. Connect and complete TLS 1.3 + bootstrap handshake
            senderTransport.initiateConnection(serverPeerId, "Android Sender Device")

            val clientEvent = withTimeout(10_000) { clientConnectionDeferred.await() }
            assertTrue(clientEvent is ConnectionEvent.Connected)

            val serverEvent = withTimeout(10_000) { serverConnectionDeferred.await() }
            assertTrue(serverEvent is ConnectionEvent.Connected)

            clientJob.cancel()
            serverJob.cancel()

            // 6. Send control message (V1 payload)
            val testControlPayload = "HELLO_ANDROID_DEVICE_LAN".toByteArray(Charsets.UTF_8)
            val receivedControlDeferred = CompletableDeferred<ByteArray>()
            val controlJob = launch {
                val ctrl = receiverTransport.observeControl().first()
                receivedControlDeferred.complete(ctrl.payload)
            }

            senderTransport.sendControl(serverPeerId, testControlPayload)
            val receivedControl = withTimeout(5_000) { receivedControlDeferred.await() }
            assertArrayEquals(testControlPayload, receivedControl)
            controlJob.cancel()

            // 7. Stream file payload (16 KiB pseudo-random content)
            val testPayload = ByteArray(16 * 1024) { (it % 251).toByte() }
            val sha256 = MessageDigest.getInstance("SHA-256")
            val expectedDigest = sha256.digest(testPayload)

            val bulkRef = senderTransport.prepareStream { ByteArrayInputStream(testPayload) }
            assertNotNull(bulkRef)

            // Register expectation on receiver
            receiverTransport.registerExpectedStream(
                StreamExpectation(
                    sessionId = testSessionId,
                    itemId = testItemId,
                    bulkRef = bulkRef!!,
                    sizeBytes = testPayload.size.toLong()
                )
            )

            // Launch receiver stream collector
            val receivedStreamDeferred = CompletableDeferred<ByteArray>()
            val receiverStreamJob = launch {
                val incoming = receiverTransport.observeIncomingStream().first { it.bulkRef == bulkRef }
                val receivedData = incoming.stream.readBytes()
                receivedStreamDeferred.complete(receivedData)
            }

            // Sender sends stream
            val progressList = senderTransport.sendStream(serverPeerId, bulkRef).toList()
            assertTrue(progressList.isNotEmpty())
            assertTrue(progressList.last().isComplete)
            assertEquals(testPayload.size.toLong(), progressList.last().bytesTransferred)

            val receivedBytes = withTimeout(10_000) { receivedStreamDeferred.await() }
            assertArrayEquals(testPayload, receivedBytes)

            // Verify SHA-256 digest match
            val receivedDigest = MessageDigest.getInstance("SHA-256").digest(receivedBytes)
            assertArrayEquals(expectedDigest, receivedDigest)

            receiverStreamJob.cancel()

            // 8. Zero-byte file transfer
            val zeroBytes = ByteArray(0)
            val zeroBulkRef = senderTransport.prepareStream { ByteArrayInputStream(zeroBytes) }!!
            receiverTransport.registerExpectedStream(
                StreamExpectation(
                    sessionId = testSessionId,
                    itemId = ItemId(UUID.randomUUID().toString()),
                    bulkRef = zeroBulkRef,
                    sizeBytes = 0L
                )
            )

            val zeroStreamDeferred = CompletableDeferred<ByteArray>()
            val zeroStreamJob = launch {
                val incoming = receiverTransport.observeIncomingStream().first { it.bulkRef == zeroBulkRef }
                val receivedData = incoming.stream.readBytes()
                zeroStreamDeferred.complete(receivedData)
            }

            val zeroProgress = senderTransport.sendStream(serverPeerId, zeroBulkRef).toList()
            assertTrue(zeroProgress.last().isComplete)
            assertEquals(0L, zeroProgress.last().bytesTransferred)

            val receivedZeroBytes = withTimeout(5_000) { zeroStreamDeferred.await() }
            assertEquals(0, receivedZeroBytes.size)
            zeroStreamJob.cancel()

        } finally {
            senderTransport.close()
            receiverTransport.close()
            listener.close()
            receiverIdentity.close()
        }
    }

    @Test
    fun testReplayedInvitationRejectedOnDevice(): Unit = runBlocking {
        assumeTrue("Requires Android API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loopbackAddress = InetAddress.getByName("127.0.0.1")

        val receiverScope = LanNetworkScope(context)
        val senderScope = LanNetworkScope(context)

        val receiverTransport = LanTransferTransport(networkScope = receiverScope, allowLoopbackForTesting = true)
        val senderTransport1 = LanTransferTransport(networkScope = senderScope, allowLoopbackForTesting = true)
        val senderTransport2 = LanTransferTransport(networkScope = senderScope, allowLoopbackForTesting = true)

        val identity = LanLeaseIdentity.create(preferAndroidKeyStore = true)
        val listener = LanListener(
            identity = identity,
            networkScope = receiverScope,
            bindAddress = loopbackAddress,
            port = 0,
            allowLoopbackForTesting = true
        )
        val authority = InvitationAuthority()

        try {
            receiverTransport.attachServerListener(listener, authority)

            val invitation = authority.mintInvitation(
                endpoints = listOf(LanEndpoint(loopbackAddress.hostAddress ?: "127.0.0.1", listener.localPort)),
                spkiSha256 = identity.spkiSha256,
                deviceName = "Receiver Device"
            )

            val peerId1 = PeerId("lan-peer-1")
            val peerId2 = PeerId("lan-peer-2")

            senderTransport1.registerPendingInvitation(peerId1, invitation)
            senderTransport2.registerPendingInvitation(peerId2, invitation)

            // First connection consumes invitation
            senderTransport1.initiateConnection(peerId1, "First Client")
            senderTransport1.disconnect(peerId1)

            // Second connection with same invitation must be rejected (single-use)
            try {
                senderTransport2.initiateConnection(peerId2, "Replay Client")
                fail("Expected IOException due to rejected replayed invitation")
            } catch (e: IOException) {
                // Expected rejection: token already consumed
                assertTrue(e.message?.contains("LAN bootstrap rejected") == true || e.message?.contains("stream closed") == true)
            }
        } finally {
            senderTransport1.close()
            senderTransport2.close()
            receiverTransport.close()
            listener.close()
            identity.close()
        }
    }

    @Test
    fun testWrongPinFailsTlsHandshakeOnDevice(): Unit = runBlocking {
        assumeTrue("Requires Android API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loopbackAddress = InetAddress.getByName("127.0.0.1")

        val receiverScope = LanNetworkScope(context)
        val senderScope = LanNetworkScope(context)

        val receiverTransport = LanTransferTransport(networkScope = receiverScope, allowLoopbackForTesting = true)
        val senderTransport = LanTransferTransport(networkScope = senderScope, allowLoopbackForTesting = true)

        val identity = LanLeaseIdentity.create(preferAndroidKeyStore = true)
        val listener = LanListener(
            identity = identity,
            networkScope = receiverScope,
            bindAddress = loopbackAddress,
            port = 0,
            allowLoopbackForTesting = true
        )
        val authority = InvitationAuthority()

        try {
            receiverTransport.attachServerListener(listener, authority)

            val wrongPin = "0".repeat(64) // Tampered PIN
            val tamperedInvitation = authority.mintInvitation(
                endpoints = listOf(LanEndpoint(loopbackAddress.hostAddress ?: "127.0.0.1", listener.localPort)),
                spkiSha256 = identity.spkiSha256,
                deviceName = "Receiver Device"
            ).copy(spkiSha256 = wrongPin)

            val peerId = PeerId("lan-tampered-peer")
            senderTransport.registerPendingInvitation(peerId, tamperedInvitation)

            try {
                senderTransport.initiateConnection(peerId, "Adversarial Client")
                fail("Expected SSLHandshakeException or IOException due to wrong SPKI PIN")
            } catch (e: Exception) {
                // Verification passes if SSLHandshakeException or general IOException occurs due to pin mismatch
                assertTrue(
                    e is SSLHandshakeException ||
                    e is IOException ||
                    e.cause is SSLHandshakeException
                )
            }
        } finally {
            senderTransport.close()
            receiverTransport.close()
            listener.close()
            identity.close()
        }
    }
}
