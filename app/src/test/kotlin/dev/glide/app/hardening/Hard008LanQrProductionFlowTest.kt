package dev.glide.app.hardening

import dev.glide.app.config.LanFeatureConfig
import dev.glide.app.lan.LanReceiverRuntimeState
import dev.glide.app.lan.ProductionLanController
import dev.glide.core.domain.PeerDevice
import dev.glide.core.pairing.LanEndpoint
import dev.glide.core.transport.lan.LanBootstrapFailureCode
import dev.glide.core.transport.lan.LanBootstrapMessage
import dev.glide.transport.lan.network.LanNetworkScope
import dev.glide.transport.lan.network.LanNetworkState
import dev.glide.transport.lan.network.SubnetPrefix
import dev.glide.transport.lan.pairing.AuthorityClock
import dev.glide.transport.lan.pairing.InvitationAuthority
import java.net.InetAddress
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HARD-008 production-flow tests.
 *
 * These tests use the actual LAN TLS listener/connector/bootstrap implementation over loopback.
 * Loopback is enabled only by [ProductionLanController.forTesting]; production remains on-link only.
 */
class Hard008LanQrProductionFlowTest {

    private val placeholderSpki =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @After
    fun tearDown() {
        LanFeatureConfig.reset()
    }

    @Test
    fun liveReceiver_mintsQrFromBoundListenerAndRealLeasePin_thenPinnedBootstrapConnects() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val receiver = controller()
        val sender = controller()
        val receiverConnected = CompletableDeferred<PeerDevice>()
        val senderConnected = CompletableDeferred<PeerDevice>()

        try {
            assertTrue(
                receiver.startReceiver(owner, "HARD-008 Receiver") { _, peer ->
                    receiverConnected.complete(peer)
                }
            )

            val ready = withTimeout(10_000) {
                receiver.receiverState
                    .filterIsInstance<LanReceiverRuntimeState.Ready>()
                    .first()
            }
            val invitation = ready.invitation

            assertEquals("HARD-008 Receiver", invitation.name)
            assertEquals(1, invitation.endpoints.size)
            assertEquals("127.0.0.1", invitation.endpoints.single().address)
            assertTrue(invitation.endpoints.single().port in 1..65535)
            assertNotEquals(4242, invitation.endpoints.single().port)
            assertEquals(64, invitation.spkiSha256.length)
            assertNotEquals(placeholderSpki, invitation.spkiSha256)
            assertTrue(invitation.invitationHash.length == 64)
            assertTrue(invitation.token.length == 43)

            assertTrue(
                sender.startSender(
                    ownerScope = owner,
                    invitation = invitation,
                    localDeviceName = "HARD-008 Sender",
                    onConnected = { _, peer -> senderConnected.complete(peer) },
                    onFailure = { reason ->
                        if (!senderConnected.isCompleted) {
                            senderConnected.completeExceptionally(
                                AssertionError("LAN sender failed: $reason")
                            )
                        }
                    }
                )
            )

            withTimeout(10_000) {
                senderConnected.await()
                receiverConnected.await()
            }

            assertTrue(receiver.receiverState.value is LanReceiverRuntimeState.Connected)
        } finally {
            sender.stopAll()
            receiver.stopAll()
            owner.cancel()
        }
    }

    @Test
    fun wrongPin_failsBeforeAuthenticatedReceiverConnection() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val receiver = controller()
        val sender = controller()
        val receiverConnected = CompletableDeferred<PeerDevice>()
        val senderFailure = CompletableDeferred<String>()

        try {
            assertTrue(
                receiver.startReceiver(owner, "Pinned Receiver") { _, peer ->
                    receiverConnected.complete(peer)
                }
            )
            val ready = withTimeout(10_000) {
                receiver.receiverState
                    .filterIsInstance<LanReceiverRuntimeState.Ready>()
                    .first()
            }

            val wrongPinInvitation = ready.invitation.copy(
                spkiSha256 = "0".repeat(64)
            )

            assertTrue(
                sender.startSender(
                    ownerScope = owner,
                    invitation = wrongPinInvitation,
                    localDeviceName = "Wrong Pin Sender",
                    onConnected = { _, _ ->
                        senderFailure.complete("unexpected connection")
                    },
                    onFailure = { reason -> senderFailure.complete(reason) }
                )
            )

            val failure = withTimeout(10_000) { senderFailure.await() }
            assertFalse(failure == "unexpected connection")
            assertFalse(receiverConnected.isCompleted)
        } finally {
            sender.stopAll()
            receiver.stopAll()
            owner.cancel()
        }
    }

    @Test
    fun listenerBindFailure_surfacesRetryableRuntimeError_andMintsNoInvitation() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = ProductionLanController.forTesting(
            networkScopeFactory = ::availableLoopbackScope,
            listenerFactory = { _, _, _ ->
                throw java.io.IOException("forced bind failure")
            }
        )

        try {
            assertTrue(
                controller.startReceiver(owner, "Bind Failure Receiver") { _, _ ->
                    throw AssertionError("Connection callback must not fire")
                }
            )

            val error = withTimeout(5_000) {
                controller.receiverState
                    .filterIsInstance<LanReceiverRuntimeState.Error>()
                    .first()
            }
            assertTrue(error.canRetry)
            assertTrue(error.message.contains("forced bind failure"))
        } finally {
            controller.stopAll()
            owner.cancel()
        }
    }

    @Test
    fun invitationAuthority_consumesExactlyOnce_andRejectsReplayAndExpiry() {
        var epochMs = 1_800_000_000_000L
        var monotonicNs = 5_000_000_000L
        val clock = object : AuthorityClock {
            override fun epochMillis(): Long = epochMs
            override fun monotonicNanos(): Long = monotonicNs
        }
        val authority = InvitationAuthority(clock = clock)
        val invitation = authority.mintInvitation(
            endpoints = listOf(LanEndpoint("127.0.0.1", 42424)),
            spkiSha256 = "a".repeat(64),
            deviceName = "Replay Receiver",
            ttlSeconds = 2,
            networkGeneration = 7L
        )
        val bootstrap = LanBootstrapMessage(
            v = 1,
            invitationId = invitation.invitationId,
            token = invitation.token,
            invitationHash = invitation.invitationHash,
            sessionId = UUID.randomUUID().toString(),
            role = LanBootstrapMessage.ROLE_SENDER
        )

        val first = authority.consume(
            message = bootstrap,
            currentNetworkGeneration = 7L,
            isBusy = false
        )
        assertTrue(first.isAccepted)

        val replay = authority.consume(
            message = bootstrap,
            currentNetworkGeneration = 7L,
            isBusy = false
        )
        assertFalse(replay.isAccepted)
        assertEquals(LanBootstrapFailureCode.INVITATION_USED, replay.code)

        val expiringAuthority = InvitationAuthority(clock = clock)
        val expiring = expiringAuthority.mintInvitation(
            endpoints = listOf(LanEndpoint("127.0.0.1", 42425)),
            spkiSha256 = "b".repeat(64),
            deviceName = "Expiry Receiver",
            ttlSeconds = 1,
            networkGeneration = 9L
        )
        monotonicNs += 1_000_000_000L
        epochMs += 1_000L
        val expired = expiringAuthority.consume(
            message = LanBootstrapMessage(
                v = 1,
                invitationId = expiring.invitationId,
                token = expiring.token,
                invitationHash = expiring.invitationHash,
                sessionId = UUID.randomUUID().toString(),
                role = LanBootstrapMessage.ROLE_SENDER
            ),
            currentNetworkGeneration = 9L,
            isBusy = false
        )
        assertFalse(expired.isAccepted)
        assertEquals(LanBootstrapFailureCode.INVITATION_EXPIRED, expired.code)
    }

    private fun controller(): ProductionLanController =
        ProductionLanController.forTesting(
            networkScopeFactory = ::availableLoopbackScope
        )

    private fun availableLoopbackScope(): LanNetworkScope {
        val address = InetAddress.getByName("127.0.0.1")
        return LanNetworkScope(null).also { scope ->
            scope.updateNetworkStateForTesting(
                LanNetworkState.Available(
                    network = null,
                    localAddresses = listOf(address),
                    subnets = listOf(SubnetPrefix(address, 8)),
                    generation = 1L
                )
            )
        }
    }
}
