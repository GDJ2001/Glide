package dev.glide.app.hardening

import dev.glide.app.config.LanFeatureConfig
import dev.glide.app.lan.LanReceiverRuntimeState
import dev.glide.app.lan.ProductionLanController
import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import dev.glide.core.pairing.LanEndpoint
import dev.glide.core.pairing.LanInvitation
import dev.glide.core.transport.lan.LanBootstrapFailureCode
import dev.glide.core.transport.lan.LanBootstrapMessage
import dev.glide.transport.lan.network.LanNetworkScope
import dev.glide.transport.lan.network.LanNetworkState
import dev.glide.transport.lan.network.SubnetPrefix
import dev.glide.transport.lan.pairing.AuthorityClock
import dev.glide.transport.lan.pairing.InvitationAuthority
import dev.glide.transport.lan.tls.LanLeaseIdentity
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
        assertEquals(LanBootstrapFailureCode.INVITATION_USED.name, replay.code)

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
        assertEquals(LanBootstrapFailureCode.INVITATION_EXPIRED.name, expired.code)
    }

    @Test
    fun trustedHotspotReceiver_withFailingNsd_stillMintsQrAndConnects() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val failingNsdWrapper = object : dev.glide.transport.lan.discovery.NsdClientWrapper {
            override fun registerService(
                serviceName: String,
                serviceType: String,
                port: Int,
                attributes: Map<String, String>,
                callback: dev.glide.transport.lan.discovery.NsdRegistrationCallback
            ) {
                callback.onRegistrationFailed(-1)
            }
            override fun unregisterService(callback: dev.glide.transport.lan.discovery.NsdRegistrationCallback) {}
            override fun discoverServices(
                serviceType: String,
                callback: dev.glide.transport.lan.discovery.NsdDiscoveryCallback
            ) {}
            override fun stopServiceDiscovery(callback: dev.glide.transport.lan.discovery.NsdDiscoveryCallback) {}
            override fun resolveService(
                service: dev.glide.transport.lan.discovery.NsdDiscoveredService,
                callback: dev.glide.transport.lan.discovery.NsdResolveCallback
            ) {}
        }
        val receiver = ProductionLanController.forTesting(
            networkScopeFactory = ::availableLoopbackScope,
            transportFactory = { networkScope, scope ->
                val failingAdapter = dev.glide.transport.lan.discovery.NsdDiscoveryAdapter(
                    clientWrapper = failingNsdWrapper,
                    networkScope = networkScope,
                    allowLoopbackForTesting = true,
                    coroutineScope = scope
                )
                dev.glide.transport.lan.LanTransferTransport(
                    networkScope = networkScope,
                    allowLoopbackForTesting = true,
                    coroutineScope = scope,
                    customNsdDiscoveryAdapter = failingAdapter
                )
            }
        )
        val identity = dev.glide.data.identity.JvmDeviceIdentityKeyStore()
        identity.getFingerprint()
        val emptyPeerStore = object : dev.glide.core.identity.TrustedPeerStore {
            override fun observeAll(): kotlinx.coroutines.flow.Flow<List<dev.glide.core.domain.TrustedPeerRecord>> =
                kotlinx.coroutines.flow.flowOf(emptyList())
            override suspend fun getAll(): List<dev.glide.core.domain.TrustedPeerRecord> = emptyList()
            override suspend fun getByFingerprint(fingerprintHex: String): dev.glide.core.domain.TrustedPeerRecord? = null
            override suspend fun upsert(peer: dev.glide.core.domain.TrustedPeerRecord) {}
            override suspend fun updateLastSeen(fingerprintHex: String, lastSeenAtMs: Long) {}
            override suspend fun revoke(fingerprintHex: String) {}
            override suspend fun revokeAll() {}
            override suspend fun count(): Int = 0
        }
        val trustManager = dev.glide.core.identity.PersistentTrustManager(
            identityStore = identity,
            trustedPeerStore = emptyPeerStore
        )
        receiver.configureTrustedReceive(trustManager)

        val sender = controller()
        val receiverConnected = CompletableDeferred<PeerDevice>()
        val senderConnected = CompletableDeferred<PeerDevice>()
        try {
            assertTrue(
                receiver.startReceiver(owner, "Legacy Hotspot Receiver") { _, peer ->
                    receiverConnected.complete(peer)
                }
            )
            val ready = withTimeout(10_000) {
                receiver.receiverState
                    .filterIsInstance<LanReceiverRuntimeState.Ready>()
                    .first()
            }
            assertTrue(
                sender.startSender(
                    ownerScope = owner,
                    invitation = ready.invitation,
                    localDeviceName = "Sender",
                    onConnected = { _, peer -> senderConnected.complete(peer) },
                    onFailure = { reason ->
                        if (!senderConnected.isCompleted) {
                            senderConnected.completeExceptionally(AssertionError("Sender failed: $reason"))
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
    fun leaseRotation_defersWhileInvitationOrConnectionIsActiveAndUsesSafeDefaults() {
        assertTrue(ProductionLanController.RECEIVER_LEASE_TTL_MS >= 10 * 60_000L)
        assertTrue(ProductionLanController.REUSE_LEASE_MARGIN_MS >= 150_000L)

        val identity = LanLeaseIdentity.create(
            ttlMillis = ProductionLanController.RECEIVER_LEASE_TTL_MS,
            preferAndroidKeyStore = false
        )
        val activeInvite = InvitationAuthority().mintInvitation(
            endpoints = listOf(LanEndpoint("192.168.43.1", 49622)),
            spkiSha256 = identity.spkiSha256,
            deviceName = "Glide Device"
        )
        val readyState = LanReceiverRuntimeState.Ready(
            invitation = activeInvite,
            endpointsSummary = "192.168.43.1:49622"
        )
        val invExpiresMs = activeInvite.expiresAt.toLong() * 1_000L

        assertTrue(
            ProductionLanController.shouldDeferLeaseRotation(
                state = readyState,
                identity = identity,
                nowEpochMs = invExpiresMs - 25_000L
            )
        )
        assertFalse(
            ProductionLanController.shouldDeferLeaseRotation(
                state = readyState,
                identity = identity,
                nowEpochMs = invExpiresMs + 5_000L
            )
        )
        assertTrue(
            ProductionLanController.shouldDeferLeaseRotation(
                state = LanReceiverRuntimeState.Connected(PeerDevice(PeerId("p1"), "Peer")),
                identity = identity,
                nowEpochMs = identity.createdAtEpochMs + ProductionLanController.RECEIVER_LEASE_TTL_MS
            )
        )
    }

    @Test
    fun trustedLanReceiverAutoStart_skipsDuringActiveSenderOrPairing() {
        val peerId = PeerId("lan-peer-1")
        val peer = PeerDevice(peerId, "Glide Peer")
        assertTrue(
            dev.glide.app.shouldAutoStartTrustedLanReceiver(
                hasTrustedPeers = true,
                lanReceiverState = LanReceiverRuntimeState.Idle,
                isSessionActive = false,
                pairingState = dev.glide.app.ui.pairing.PairingUiState.Idle
            )
        )
        assertFalse(
            dev.glide.app.shouldAutoStartTrustedLanReceiver(
                hasTrustedPeers = true,
                lanReceiverState = LanReceiverRuntimeState.Idle,
                isSessionActive = false,
                pairingState = dev.glide.app.ui.pairing.PairingUiState.Connecting(
                    peerId = peerId,
                    peerName = "Glide Peer"
                )
            )
        )
        assertFalse(
            dev.glide.app.shouldAutoStartTrustedLanReceiver(
                hasTrustedPeers = true,
                lanReceiverState = LanReceiverRuntimeState.Idle,
                isSessionActive = false,
                pairingState = dev.glide.app.ui.pairing.PairingUiState.Verifying(
                    peerId = peerId,
                    peerName = "Glide Peer",
                    authDigits = "123456"
                )
            )
        )
        assertFalse(
            dev.glide.app.shouldAutoStartTrustedLanReceiver(
                hasTrustedPeers = true,
                lanReceiverState = LanReceiverRuntimeState.Idle,
                isSessionActive = false,
                pairingState = dev.glide.app.ui.pairing.PairingUiState.Connected(peer)
            )
        )
        assertFalse(
            dev.glide.app.shouldAutoStartTrustedLanReceiver(
                hasTrustedPeers = true,
                lanReceiverState = LanReceiverRuntimeState.Error("Temporarily offline"),
                isSessionActive = false,
                pairingState = dev.glide.app.ui.pairing.PairingUiState.Idle
            )
        )
    }

    @Test
    fun trustedHotspotReceiver_withNullNetworkAndFailingNsd_connectsTrustedSenderViaSubnetBeacon() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val failingNsdWrapper = object : dev.glide.transport.lan.discovery.NsdClientWrapper {
            override fun registerService(
                serviceName: String,
                serviceType: String,
                port: Int,
                attributes: Map<String, String>,
                callback: dev.glide.transport.lan.discovery.NsdRegistrationCallback
            ) {
                callback.onRegistrationFailed(-1)
            }
            override fun unregisterService(callback: dev.glide.transport.lan.discovery.NsdRegistrationCallback) {}
            override fun discoverServices(
                serviceType: String,
                callback: dev.glide.transport.lan.discovery.NsdDiscoveryCallback
            ) {}
            override fun stopServiceDiscovery(callback: dev.glide.transport.lan.discovery.NsdDiscoveryCallback) {}
            override fun resolveService(
                service: dev.glide.transport.lan.discovery.NsdDiscoveredService,
                callback: dev.glide.transport.lan.discovery.NsdResolveCallback
            ) {}
        }
        fun transportWithFailingNsd(networkScope: LanNetworkScope, scope: CoroutineScope) =
            dev.glide.transport.lan.LanTransferTransport(
                networkScope = networkScope,
                allowLoopbackForTesting = true,
                coroutineScope = scope,
                customNsdDiscoveryAdapter = dev.glide.transport.lan.discovery.NsdDiscoveryAdapter(
                    clientWrapper = failingNsdWrapper,
                    networkScope = networkScope,
                    allowLoopbackForTesting = true,
                    coroutineScope = scope
                )
            )
        val receiver = ProductionLanController.forTesting(
            networkScopeFactory = ::availableLoopbackScope,
            transportFactory = ::transportWithFailingNsd
        )
        val sender = ProductionLanController.forTesting(
            networkScopeFactory = ::availableLoopbackScope,
            transportFactory = ::transportWithFailingNsd
        )
        val receiverId = dev.glide.data.identity.JvmDeviceIdentityKeyStore()
        val senderId = dev.glide.data.identity.JvmDeviceIdentityKeyStore()
        var receiverTrustedPeer: dev.glide.core.domain.TrustedPeerRecord? = null
        var senderTrustedPeer: dev.glide.core.domain.TrustedPeerRecord? = null
        fun mutableStore(getter: () -> dev.glide.core.domain.TrustedPeerRecord?) = object : dev.glide.core.identity.TrustedPeerStore {
            override fun observeAll(): kotlinx.coroutines.flow.Flow<List<dev.glide.core.domain.TrustedPeerRecord>> =
                kotlinx.coroutines.flow.flowOf(listOfNotNull(getter()))
            override suspend fun getAll(): List<dev.glide.core.domain.TrustedPeerRecord> = listOfNotNull(getter())
            override suspend fun getByFingerprint(fingerprintHex: String): dev.glide.core.domain.TrustedPeerRecord? =
                getter()?.takeIf { it.peerFingerprintHex == fingerprintHex }
            override suspend fun upsert(peer: dev.glide.core.domain.TrustedPeerRecord) {}
            override suspend fun updateLastSeen(fingerprintHex: String, lastSeenAtMs: Long) {}
            override suspend fun revoke(fingerprintHex: String) {}
            override suspend fun revokeAll() {}
            override suspend fun count(): Int = if (getter() == null) 0 else 1
        }
        val receiverTrust = dev.glide.core.identity.PersistentTrustManager(receiverId, mutableStore { receiverTrustedPeer }).also { it.initialize() }
        val senderTrust = dev.glide.core.identity.PersistentTrustManager(senderId, mutableStore { senderTrustedPeer }).also { it.initialize() }
        val receiverFp = receiverTrust.localFingerprintHex()
        val senderFp = senderTrust.localFingerprintHex()
        val receiverRecord = dev.glide.core.domain.TrustedPeerRecord(
            peerFingerprintHex = receiverFp,
            publicKeyDerHex = receiverTrust.localPublicKeyDer().joinToString("") { "%02x".format(it) },
            displayName = "Hotspot Host",
            pairedAtMs = 1L,
            lastSeenAtMs = 1L
        )
        val senderRecord = dev.glide.core.domain.TrustedPeerRecord(
            peerFingerprintHex = senderFp,
            publicKeyDerHex = senderTrust.localPublicKeyDer().joinToString("") { "%02x".format(it) },
            displayName = "Hotspot Client",
            pairedAtMs = 1L,
            lastSeenAtMs = 1L
        )
        receiverTrustedPeer = senderRecord
        senderTrustedPeer = receiverRecord
        receiver.configureTrustedReceive(receiverTrust)

        val receiverConnected = CompletableDeferred<PeerDevice>()
        val senderConnected = CompletableDeferred<PeerDevice>()
        try {
            assertTrue(receiver.startReceiver(owner, "Hotspot Host") { _, peer -> receiverConnected.complete(peer) })
            withTimeout(10_000) {
                receiver.receiverState.filterIsInstance<LanReceiverRuntimeState.Ready>().first()
            }
            val discovered = sender.probeTrustedReconnectCandidates(timeoutMs = 1_500L)
            assertTrue("Expected beacon candidate from Hotspot Host", discovered.any { it.displayName == "Hotspot Host" })
            val lanPeerId = dev.glide.app.service.TransferRuntime.lanTrustedPeerId(receiverFp)
            assertEquals(64, lanPeerId.value.length)
            val testRuntime = dev.glide.app.service.TransferRuntime.forTesting(persistentTrustManager = senderTrust)
            testRuntime.refreshPersistentTrustState()
            assertEquals(receiverFp, testRuntime.resolveTrustedLanPeerFingerprint(lanPeerId))
            assertTrue(
                sender.startTrustedSender(
                    ownerScope = owner,
                    trustedPeer = receiverRecord,
                    trustManager = senderTrust,
                    localDeviceName = "Hotspot Client",
                    onConnected = { _, peer -> senderConnected.complete(peer) },
                    onFailure = { failure ->
                        if (!senderConnected.isCompleted) {
                            senderConnected.completeExceptionally(AssertionError("Trusted sender failed: ${failure.message}"))
                        }
                    }
                )
            )
            withTimeout(10_000) {
                assertEquals("Hotspot Host", senderConnected.await().displayName)
                assertEquals("Hotspot Client", receiverConnected.await().displayName)
            }
        } finally {
            sender.stopAll()
            receiver.stopAll()
            owner.cancel()
        }
    }

    @Test
    fun detachServiceScope_resetsConnectedPairingStateForTrustedAutoRestart() {
        val runtime = dev.glide.app.service.TransferRuntime.forTesting()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val peer = PeerDevice(PeerId("trusted-peer-1"), "Glide Device")
        try {
            runtime.attachServiceScope(scope)
            runtime.setPairingStateForTesting(dev.glide.app.ui.pairing.PairingUiState.Connected(peer))
            assertFalse(
                dev.glide.app.shouldAutoStartTrustedLanReceiver(
                    hasTrustedPeers = true,
                    lanReceiverState = LanReceiverRuntimeState.Idle,
                    isSessionActive = false,
                    pairingState = runtime.pairingState.value
                )
            )
            runtime.detachServiceScope()
            assertEquals(dev.glide.app.ui.pairing.PairingUiState.Idle, runtime.pairingState.value)
            assertTrue(
                dev.glide.app.shouldAutoStartTrustedLanReceiver(
                    hasTrustedPeers = true,
                    lanReceiverState = LanReceiverRuntimeState.Idle,
                    isSessionActive = false,
                    pairingState = runtime.pairingState.value
                )
            )
        } finally {
            scope.cancel()
        }
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
