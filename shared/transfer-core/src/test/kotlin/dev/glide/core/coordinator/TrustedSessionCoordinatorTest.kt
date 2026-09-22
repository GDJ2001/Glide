package dev.glide.core.coordinator

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferFailure
import dev.glide.core.domain.TransferItem
import dev.glide.core.domain.TrustedPeerRecord
import dev.glide.core.fakes.FakeHistoryStore
import dev.glide.core.fakes.FakeReceiveStore
import dev.glide.core.fakes.FakeSourceAccess
import dev.glide.core.identity.PeerIdentityVerifier
import dev.glide.core.identity.PersistentIdentityStore
import dev.glide.core.identity.PersistentTrustManager
import dev.glide.core.identity.TrustedPeerStore
import dev.glide.core.ports.AdvertisingEvent
import dev.glide.core.ports.AuthenticatedChannelContext
import dev.glide.core.ports.AuthenticatedChannelContextProvider
import dev.glide.core.ports.BulkProgress
import dev.glide.core.ports.ConnectionEvent
import dev.glide.core.ports.ControlMessage
import dev.glide.core.ports.DiscoveryEvent
import dev.glide.core.ports.IncomingStream
import dev.glide.core.ports.TransferTransport
import dev.glide.core.protocol.Envelope
import dev.glide.core.protocol.MessageTypes
import dev.glide.core.protocol.ProtocolCodec
import dev.glide.core.resume.ResumeAuthorityLookup
import dev.glide.core.resume.ResumeReconnectPlan
import dev.glide.core.resume.ResumeReconnectState
import dev.glide.core.resume.ResumeRequestAuthority
import dev.glide.core.resume.ResumeSenderReadySource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID

class TrustedSessionCoordinatorTest {

    @Test
    fun identityV1_mutualProofCompletesBeforeOffer_andPreservesSequence() =
        runBlocking {
            withTimeout(10_000) {
                val sessionId =
                    SessionId(
                        UUID.randomUUID().toString()
                    )
                val binding =
                    "ab".repeat(32)
                val senderPeer =
                    PeerId("trusted-sender")
                val receiverPeer =
                    PeerId("trusted-receiver")

                val senderTransport =
                    TrustedControlTransport(
                        localPeerId = senderPeer,
                        sessionId = sessionId,
                        channelBindingHex = binding
                    )
                val receiverTransport =
                    TrustedControlTransport(
                        localPeerId = receiverPeer,
                        sessionId = sessionId,
                        channelBindingHex = binding
                    )
                senderTransport.remote =
                    receiverTransport
                receiverTransport.remote =
                    senderTransport

                val senderTrust =
                    newTrustManager()
                val receiverTrust =
                    newTrustManager()
                val senderVerified =
                    mutableListOf<String>()
                val receiverVerified =
                    mutableListOf<String>()

                val item =
                    TransferItem(
                        id =
                            ItemId(
                                UUID.randomUUID()
                                    .toString()
                            ),
                        name = "proof.txt",
                        mimeType = "text/plain",
                        sizeBytes = 4L
                    )
                val batch =
                    TransferBatch(
                        id =
                            BatchId(
                                UUID.randomUUID()
                                    .toString()
                            ),
                        items = listOf(item)
                    )
                val sourceMap =
                    mapOf(
                        item.id to
                            SourceHandle(
                                "source-proof"
                            )
                    )

                val sender =
                    TransferCoordinator(
                        sessionId = sessionId,
                        role = DeviceRole.Sender,
                        peerId = receiverPeer,
                        localDisplayName =
                            "Android Sender",
                        transport =
                            senderTransport,
                        sourceAccess =
                            FakeSourceAccess(),
                        receiveStore =
                            FakeReceiveStore(),
                        historyStore =
                            FakeHistoryStore(),
                        scope = this,
                        senderBatch = batch,
                        sourceMap = sourceMap,
                        persistentTrustManager =
                            senderTrust,
                        onVerifiedPeerIdentity = {
                            senderVerified +=
                                it.fingerprintHex
                        }
                    )
                val receiver =
                    TransferCoordinator(
                        sessionId = sessionId,
                        role = DeviceRole.Receiver,
                        peerId = senderPeer,
                        localDisplayName =
                            "Android Receiver",
                        transport =
                            receiverTransport,
                        sourceAccess =
                            FakeSourceAccess(),
                        receiveStore =
                            FakeReceiveStore(),
                        historyStore =
                            FakeHistoryStore(),
                        scope = this,
                        persistentTrustManager =
                            receiverTrust,
                        onVerifiedPeerIdentity = {
                            receiverVerified +=
                                it.fingerprintHex
                        }
                    )

                receiver.start()
                sender.start()

                while (receiver.state.value.phase !=
                    SessionPhase.AwaitingConsent
                ) {
                    delay(10)
                }

                assertEquals(
                    receiverTrust
                        .localFingerprintHex(),
                    sender
                        .authenticatedPeerFingerprintHex
                )
                assertEquals(
                    senderTrust
                        .localFingerprintHex(),
                    receiver
                        .authenticatedPeerFingerprintHex
                )
                assertEquals(
                    listOf(
                        receiverTrust
                            .localFingerprintHex()
                    ),
                    senderVerified
                )
                assertEquals(
                    listOf(
                        senderTrust
                            .localFingerprintHex()
                    ),
                    receiverVerified
                )

                val senderControls =
                    senderTransport.sentControls
                assertTrue(
                    senderControls.size >= 4
                )
                assertEquals(
                    listOf(
                        MessageTypes.HELLO,
                        MessageTypes.IDENTITY_CHALLENGE,
                        MessageTypes.IDENTITY_RESPONSE,
                        MessageTypes.OFFER_BEGIN
                    ),
                    senderControls
                        .take(4)
                        .map { it.type }
                )
                assertEquals(
                    listOf(1L, 2L, 3L, 4L),
                    senderControls
                        .take(4)
                        .map { it.seq }
                )

                val receiverControls =
                    receiverTransport.sentControls
                assertTrue(
                    receiverControls.size >= 3
                )
                assertEquals(
                    listOf(
                        MessageTypes.HELLO_ACK,
                        MessageTypes.IDENTITY_RESPONSE,
                        MessageTypes.IDENTITY_CHALLENGE
                    ),
                    receiverControls
                        .take(3)
                        .map { it.type }
                )
                assertEquals(
                    listOf(1L, 2L, 3L),
                    receiverControls
                        .take(3)
                        .map { it.seq }
                )

                sender.stop()
                receiver.stop()
            }
        }

    @Test
    fun resumeV1_enrolledReconnect_reachesExactAcceptedPrefixWithoutOrdinaryOffer() =
        runBlocking {
            withTimeout(10_000) {
                val activeSession =
                    SessionId(
                        UUID.randomUUID()
                            .toString()
                    )
                val originalSession =
                    SessionId(
                        UUID.randomUUID()
                            .toString()
                    )
                val itemId =
                    ItemId(
                        UUID.randomUUID()
                            .toString()
                    )
                val binding =
                    "34".repeat(32)

                val senderIdentity =
                    TestPersistentIdentityStore()
                val receiverIdentity =
                    TestPersistentIdentityStore()
                val senderFingerprint =
                    senderIdentity
                        .getFingerprint()
                val receiverFingerprint =
                    receiverIdentity
                        .getFingerprint()

                fun peerRecord(
                    identity:
                        TestPersistentIdentityStore,
                    name: String
                ) =
                    TrustedPeerRecord(
                        peerFingerprintHex =
                            identity
                                .getFingerprint(),
                        publicKeyDerHex =
                            with(
                                PeerIdentityVerifier
                            ) {
                                identity
                                    .getOrCreatePublicKeyDer()
                                    .toHex()
                            },
                        displayName = name,
                        pairedAtMs = 1L,
                        lastSeenAtMs = 1L
                    )

                val senderTrust =
                    newTrustManager(
                        initialPeers =
                            listOf(
                                peerRecord(
                                    receiverIdentity,
                                    "Receiver"
                                )
                            ),
                        identity =
                            senderIdentity
                    )
                val receiverTrust =
                    newTrustManager(
                        initialPeers =
                            listOf(
                                peerRecord(
                                    senderIdentity,
                                    "Sender"
                                )
                            ),
                        identity =
                            receiverIdentity
                    )

                val senderPeer =
                    PeerId(
                        "resume-sender"
                    )
                val receiverPeer =
                    PeerId(
                        "resume-receiver"
                    )
                val senderTransport =
                    TrustedControlTransport(
                        senderPeer,
                        activeSession,
                        binding
                    )
                val receiverTransport =
                    TrustedControlTransport(
                        receiverPeer,
                        activeSession,
                        binding
                    )
                senderTransport.remote =
                    receiverTransport
                receiverTransport.remote =
                    senderTransport

                val root =
                    "56".repeat(32)
                val whole =
                    "78".repeat(32)
                val prefixChunks = 2L
                val prefixBytes =
                    2L * 1024 * 1024
                val totalBytes =
                    5L * 1024 * 1024

                val sender =
                    TransferCoordinator(
                        sessionId =
                            activeSession,
                        role =
                            DeviceRole.Sender,
                        peerId =
                            receiverPeer,
                        transport =
                            senderTransport,
                        sourceAccess =
                            FakeSourceAccess(),
                        receiveStore =
                            FakeReceiveStore(),
                        historyStore =
                            FakeHistoryStore(),
                        scope = this,
                        persistentTrustManager =
                            senderTrust,
                        expectedPeerFingerprintHex =
                            receiverFingerprint,
                        resumeReconnectPlan =
                            ResumeReconnectPlan
                                .Sender(
                                    originalSessionId =
                                        originalSession,
                                    itemId =
                                        itemId,
                                    expectedPeerFingerprintHex =
                                        receiverFingerprint
                                ),
                        loadSenderResumeAuthority = {
                            requestedSession,
                            requestedItem,
                            peerFingerprint ->
                            assertEquals(
                                originalSession,
                                requestedSession
                            )
                            assertEquals(
                                itemId,
                                requestedItem
                            )
                            assertEquals(
                                receiverFingerprint,
                                peerFingerprint
                            )
                            ResumeAuthorityLookup.Ready(
                                    ResumeSenderReadySource(
                                        authority =
                                            ResumeRequestAuthority
                                                .SenderSourceAuthority(
                                                    originalSessionId =
                                                        originalSession,
                                                    itemId =
                                                        itemId,
                                                    chunkSize =
                                                        1024 * 1024,
                                                    totalSizeBytes =
                                                        totalBytes,
                                                    chunkRootHashHex =
                                                        root
                                                ),
                                        sourceHandle =
                                            SourceHandle(
                                                "resume-source"
                                            ),
                                        wholeFileSha256Hex =
                                            whole
                                    )
                                )
                        },
                        enableTrueResumeCapability =
                            true
                    )

                val receiver =
                    TransferCoordinator(
                        sessionId =
                            activeSession,
                        role =
                            DeviceRole.Receiver,
                        peerId =
                            senderPeer,
                        transport =
                            receiverTransport,
                        sourceAccess =
                            FakeSourceAccess(),
                        receiveStore =
                            FakeReceiveStore(),
                        historyStore =
                            FakeHistoryStore(),
                        scope = this,
                        persistentTrustManager =
                            receiverTrust,
                        expectedPeerFingerprintHex =
                            senderFingerprint,
                        resumeReconnectPlan =
                            ResumeReconnectPlan
                                .Receiver(
                                    originalSessionId =
                                        originalSession,
                                    itemId =
                                        itemId,
                                    expectedPeerFingerprintHex =
                                        senderFingerprint
                                ),
                        loadReceiverResumeAuthority = {
                            requestedSession,
                            requestedItem,
                            peerFingerprint ->
                            assertEquals(
                                originalSession,
                                requestedSession
                            )
                            assertEquals(
                                itemId,
                                requestedItem
                            )
                            assertEquals(
                                senderFingerprint,
                                peerFingerprint
                            )
                            ResumeAuthorityLookup.Ready(
                                    ResumeRequestAuthority
                                        .ReceiverJournal(
                                            originalSessionId =
                                                originalSession,
                                            itemId =
                                                itemId,
                                            verifiedChunkCount =
                                                prefixChunks,
                                            contiguousByteOffset =
                                                prefixBytes,
                                            chunkSize =
                                                1024 * 1024,
                                            totalSizeBytes =
                                                totalBytes,
                                            chunkRootHashHex =
                                                root
                                        )
                                )
                        },
                        enableTrueResumeCapability =
                            true
                    )

                receiver.start()
                sender.start()

                while (
                    sender
                        .resumeReconnectState
                        .value !is
                        ResumeReconnectState
                            .SenderAccepted ||
                    receiver
                        .resumeReconnectState
                        .value !is
                        ResumeReconnectState
                            .ReceiverAccepted
                ) {
                    delay(10)
                }

                val senderAccepted =
                    sender
                        .resumeReconnectState
                        .value as
                        ResumeReconnectState
                            .SenderAccepted
                val receiverAccepted =
                    receiver
                        .resumeReconnectState
                        .value as
                        ResumeReconnectState
                            .ReceiverAccepted

                assertEquals(
                    prefixChunks,
                    senderAccepted
                        .startChunkIndex
                )
                assertEquals(
                    prefixBytes,
                    senderAccepted
                        .startByteOffset
                )
                assertEquals(
                    prefixChunks,
                    receiverAccepted
                        .startChunkIndex
                )
                assertEquals(
                    prefixBytes,
                    receiverAccepted
                        .startByteOffset
                )

                assertTrue(
                    receiverTransport
                        .sentControls
                        .any {
                            it.type ==
                                MessageTypes
                                    .RESUME_REQUEST
                        }
                )
                assertTrue(
                    senderTransport
                        .sentControls
                        .any {
                            it.type ==
                                MessageTypes
                                    .RESUME_ACCEPT
                        }
                )
                assertFalse(
                    senderTransport
                        .sentControls
                        .any {
                            it.type ==
                                MessageTypes
                                    .OFFER_BEGIN
                        }
                )
                assertFalse(
                    receiverTransport
                        .sentControls
                        .any {
                            it.type ==
                                MessageTypes
                                    .OFFER_DECISION
                        }
                )

                val receiverSequence =
                    receiverTransport
                        .sentControls
                        .map {
                            it.seq
                        }
                assertEquals(
                    receiverSequence
                        .indices
                        .map {
                            it.toLong() + 1L
                        },
                    receiverSequence
                )

                val senderSequence =
                    senderTransport
                        .sentControls
                        .map {
                            it.seq
                        }
                assertEquals(
                    senderSequence
                        .indices
                        .map {
                            it.toLong() + 1L
                        },
                    senderSequence
                )

                sender.stop()
                receiver.stop()
            }
        }

    @Test
    fun expectedTrustedPeer_identityDowngradeFailsBeforeOffer() =
        runBlocking {
            withTimeout(10_000) {
                val sessionId =
                    SessionId(
                        UUID.randomUUID().toString()
                    )
                val binding =
                    "cd".repeat(32)
                val senderPeer =
                    PeerId("strict-sender")
                val receiverPeer =
                    PeerId("legacy-receiver")

                val senderTransport =
                    TrustedControlTransport(
                        senderPeer,
                        sessionId,
                        binding
                    )
                val receiverTransport =
                    TrustedControlTransport(
                        receiverPeer,
                        sessionId,
                        binding
                    )
                senderTransport.remote =
                    receiverTransport
                receiverTransport.remote =
                    senderTransport

                val expectedReceiverIdentity =
                    TestPersistentIdentityStore()
                val expectedFingerprint =
                    expectedReceiverIdentity
                        .getFingerprint()
                val senderTrust =
                    newTrustManager(
                        listOf(
                            TrustedPeerRecord(
                                peerFingerprintHex =
                                    expectedFingerprint,
                                publicKeyDerHex =
                                    with(
                                        PeerIdentityVerifier
                                    ) {
                                        expectedReceiverIdentity
                                            .getOrCreatePublicKeyDer()
                                            .toHex()
                                    },
                                displayName =
                                    "Expected receiver",
                                pairedAtMs = 1L,
                                lastSeenAtMs = 1L
                            )
                        )
                    )

                val item =
                    TransferItem(
                        id =
                            ItemId(
                                UUID.randomUUID()
                                    .toString()
                            ),
                        name = "blocked.txt",
                        mimeType = "text/plain",
                        sizeBytes = 1L
                    )
                val batch =
                    TransferBatch(
                        id =
                            BatchId(
                                UUID.randomUUID()
                                    .toString()
                            ),
                        items = listOf(item)
                    )

                val sender =
                    TransferCoordinator(
                        sessionId = sessionId,
                        role = DeviceRole.Sender,
                        peerId = receiverPeer,
                        localDisplayName =
                            "Strict Sender",
                        transport =
                            senderTransport,
                        sourceAccess =
                            FakeSourceAccess(),
                        receiveStore =
                            FakeReceiveStore(),
                        historyStore =
                            FakeHistoryStore(),
                        scope = this,
                        senderBatch = batch,
                        sourceMap =
                            mapOf(
                                item.id to
                                    SourceHandle(
                                        "strict-source"
                                    )
                            ),
                        persistentTrustManager =
                            senderTrust,
                        expectedPeerFingerprintHex =
                            expectedFingerprint
                    )
                val receiver =
                    TransferCoordinator(
                        sessionId = sessionId,
                        role = DeviceRole.Receiver,
                        peerId = senderPeer,
                        localDisplayName =
                            "Legacy Receiver",
                        transport =
                            receiverTransport,
                        sourceAccess =
                            FakeSourceAccess(),
                        receiveStore =
                            FakeReceiveStore(),
                        historyStore =
                            FakeHistoryStore(),
                        scope = this
                    )

                receiver.start()
                sender.start()

                while (!sender.state.value.isTerminal) {
                    delay(10)
                }

                assertTrue(
                    sender.state.value.failure is
                        TransferFailure.AuthenticationMismatch
                )
                assertFalse(
                    senderTransport.sentControls
                        .any {
                            it.type ==
                                MessageTypes.OFFER_BEGIN
                        }
                )
                assertEquals(
                    null,
                    sender
                        .authenticatedPeerFingerprintHex
                )

                sender.stop()
                receiver.stop()
            }
        }

    @Test
    fun identityResponseFromOldChallenge_isRejectedBeforeOffer() =
        runBlocking {
            withTimeout(10_000) {
                val binding =
                    "ef".repeat(32)
                val senderIdentity =
                    TestPersistentIdentityStore()
                val receiverIdentity =
                    TestPersistentIdentityStore()
                val senderTrust =
                    newTrustManager(
                        identity =
                            senderIdentity
                    )
                val receiverTrust =
                    newTrustManager(
                        identity =
                            receiverIdentity
                    )

                val firstSession =
                    SessionId(
                        UUID.randomUUID()
                            .toString()
                    )
                val firstSenderTransport =
                    TrustedControlTransport(
                        PeerId("first-sender"),
                        firstSession,
                        binding
                    )
                val firstReceiverTransport =
                    TrustedControlTransport(
                        PeerId("first-receiver"),
                        firstSession,
                        binding
                    )
                firstSenderTransport.remote =
                    firstReceiverTransport
                firstReceiverTransport.remote =
                    firstSenderTransport

                val firstItem =
                    TransferItem(
                        id =
                            ItemId(
                                UUID.randomUUID()
                                    .toString()
                            ),
                        name = "first.txt",
                        mimeType = "text/plain",
                        sizeBytes = 1L
                    )
                val firstBatch =
                    TransferBatch(
                        id =
                            BatchId(
                                UUID.randomUUID()
                                    .toString()
                            ),
                        items = listOf(firstItem)
                    )
                val firstSender =
                    TransferCoordinator(
                        sessionId = firstSession,
                        role = DeviceRole.Sender,
                        peerId =
                            firstReceiverTransport
                                .localPeerId,
                        transport =
                            firstSenderTransport,
                        sourceAccess =
                            FakeSourceAccess(),
                        receiveStore =
                            FakeReceiveStore(),
                        historyStore =
                            FakeHistoryStore(),
                        scope = this,
                        senderBatch = firstBatch,
                        sourceMap =
                            mapOf(
                                firstItem.id to
                                    SourceHandle(
                                        "first-source"
                                    )
                            ),
                        persistentTrustManager =
                            senderTrust
                    )
                val firstReceiver =
                    TransferCoordinator(
                        sessionId = firstSession,
                        role = DeviceRole.Receiver,
                        peerId =
                            firstSenderTransport
                                .localPeerId,
                        transport =
                            firstReceiverTransport,
                        sourceAccess =
                            FakeSourceAccess(),
                        receiveStore =
                            FakeReceiveStore(),
                        historyStore =
                            FakeHistoryStore(),
                        scope = this,
                        persistentTrustManager =
                            receiverTrust
                    )

                firstReceiver.start()
                firstSender.start()

                while (firstReceiver.state.value.phase !=
                    SessionPhase.AwaitingConsent
                ) {
                    delay(10)
                }

                val staleResponse =
                    firstReceiverTransport
                        .sentControls
                        .first {
                            it.type ==
                                MessageTypes
                                    .IDENTITY_RESPONSE
                        }

                firstSender.stop()
                firstReceiver.stop()

                val secondSession =
                    SessionId(
                        UUID.randomUUID()
                            .toString()
                    )
                val secondSenderTransport =
                    TrustedControlTransport(
                        PeerId("second-sender"),
                        secondSession,
                        binding
                    )
                val secondReceiverTransport =
                    TrustedControlTransport(
                        PeerId("second-receiver"),
                        secondSession,
                        binding
                    )
                secondSenderTransport.remote =
                    secondReceiverTransport
                secondReceiverTransport.remote =
                    secondSenderTransport
                secondReceiverTransport
                    .rewriteOutgoing = {
                        envelope ->
                        if (envelope.type ==
                            MessageTypes
                                .IDENTITY_RESPONSE
                        ) {
                            envelope.copy(
                                body =
                                    staleResponse
                                        .body
                            )
                        } else {
                            envelope
                        }
                    }

                val secondItem =
                    TransferItem(
                        id =
                            ItemId(
                                UUID.randomUUID()
                                    .toString()
                            ),
                        name = "second.txt",
                        mimeType = "text/plain",
                        sizeBytes = 1L
                    )
                val secondBatch =
                    TransferBatch(
                        id =
                            BatchId(
                                UUID.randomUUID()
                                    .toString()
                            ),
                        items = listOf(secondItem)
                    )
                val secondSender =
                    TransferCoordinator(
                        sessionId = secondSession,
                        role = DeviceRole.Sender,
                        peerId =
                            secondReceiverTransport
                                .localPeerId,
                        transport =
                            secondSenderTransport,
                        sourceAccess =
                            FakeSourceAccess(),
                        receiveStore =
                            FakeReceiveStore(),
                        historyStore =
                            FakeHistoryStore(),
                        scope = this,
                        senderBatch =
                            secondBatch,
                        sourceMap =
                            mapOf(
                                secondItem.id to
                                    SourceHandle(
                                        "second-source"
                                    )
                            ),
                        persistentTrustManager =
                            senderTrust
                    )
                val secondReceiver =
                    TransferCoordinator(
                        sessionId = secondSession,
                        role = DeviceRole.Receiver,
                        peerId =
                            secondSenderTransport
                                .localPeerId,
                        transport =
                            secondReceiverTransport,
                        sourceAccess =
                            FakeSourceAccess(),
                        receiveStore =
                            FakeReceiveStore(),
                        historyStore =
                            FakeHistoryStore(),
                        scope = this,
                        persistentTrustManager =
                            receiverTrust
                    )

                secondReceiver.start()
                secondSender.start()

                while (!secondSender
                        .state.value
                        .isTerminal
                ) {
                    delay(10)
                }

                assertTrue(
                    secondSender
                        .state.value
                        .failure is
                        TransferFailure
                            .AuthenticationMismatch
                )
                assertFalse(
                    secondSenderTransport
                        .sentControls
                        .any {
                            it.type ==
                                MessageTypes
                                    .OFFER_BEGIN
                        }
                )
                assertEquals(
                    null,
                    secondSender
                        .authenticatedPeerFingerprintHex
                )

                secondSender.stop()
                secondReceiver.stop()
            }
        }

    @Test
    fun expectedTrustedPeer_revokedBeforeHandshake_isRejectedBeforeOffer() =
        runBlocking {
            withTimeout(10_000) {
                val sessionId =
                    SessionId(
                        UUID.randomUUID()
                            .toString()
                    )
                val binding =
                    "12".repeat(32)
                val senderPeer =
                    PeerId("revoked-sender")
                val receiverPeer =
                    PeerId("revoked-receiver")
                val receiverIdentity =
                    TestPersistentIdentityStore()
                val receiverFingerprint =
                    receiverIdentity
                        .getFingerprint()
                val senderTrust =
                    newTrustManager(
                        initialPeers =
                            listOf(
                                TrustedPeerRecord(
                                    peerFingerprintHex =
                                        receiverFingerprint,
                                    publicKeyDerHex =
                                        with(
                                            PeerIdentityVerifier
                                        ) {
                                            receiverIdentity
                                                .getOrCreatePublicKeyDer()
                                                .toHex()
                                        },
                                    displayName =
                                        "Revoked receiver",
                                    pairedAtMs = 1L,
                                    lastSeenAtMs = 1L
                                )
                            )
                    )
                senderTrust.revokePeer(
                    receiverFingerprint
                )
                val receiverTrust =
                    newTrustManager(
                        identity =
                            receiverIdentity
                    )

                val senderTransport =
                    TrustedControlTransport(
                        senderPeer,
                        sessionId,
                        binding
                    )
                val receiverTransport =
                    TrustedControlTransport(
                        receiverPeer,
                        sessionId,
                        binding
                    )
                senderTransport.remote =
                    receiverTransport
                receiverTransport.remote =
                    senderTransport

                val item =
                    TransferItem(
                        id =
                            ItemId(
                                UUID.randomUUID()
                                    .toString()
                            ),
                        name = "revoked.txt",
                        mimeType = "text/plain",
                        sizeBytes = 1L
                    )
                val batch =
                    TransferBatch(
                        id =
                            BatchId(
                                UUID.randomUUID()
                                    .toString()
                            ),
                        items = listOf(item)
                    )
                val sender =
                    TransferCoordinator(
                        sessionId = sessionId,
                        role = DeviceRole.Sender,
                        peerId = receiverPeer,
                        transport = senderTransport,
                        sourceAccess =
                            FakeSourceAccess(),
                        receiveStore =
                            FakeReceiveStore(),
                        historyStore =
                            FakeHistoryStore(),
                        scope = this,
                        senderBatch = batch,
                        sourceMap =
                            mapOf(
                                item.id to
                                    SourceHandle(
                                        "revoked-source"
                                    )
                            ),
                        persistentTrustManager =
                            senderTrust,
                        expectedPeerFingerprintHex =
                            receiverFingerprint
                    )
                val receiver =
                    TransferCoordinator(
                        sessionId = sessionId,
                        role = DeviceRole.Receiver,
                        peerId = senderPeer,
                        transport =
                            receiverTransport,
                        sourceAccess =
                            FakeSourceAccess(),
                        receiveStore =
                            FakeReceiveStore(),
                        historyStore =
                            FakeHistoryStore(),
                        scope = this,
                        persistentTrustManager =
                            receiverTrust
                    )

                receiver.start()
                sender.start()

                while (!sender.state.value.isTerminal) {
                    delay(10)
                }

                assertTrue(
                    sender.state.value.failure is
                        TransferFailure.AuthenticationMismatch
                )
                assertFalse(
                    senderTransport.sentControls
                        .any {
                            it.type ==
                                MessageTypes.OFFER_BEGIN
                        }
                )
                assertEquals(
                    null,
                    sender
                        .authenticatedPeerFingerprintHex
                )

                sender.stop()
                receiver.stop()
            }
        }

    private fun newTrustManager(
        initialPeers: List<TrustedPeerRecord> =
            emptyList(),
        identity: TestPersistentIdentityStore =
            TestPersistentIdentityStore()
    ): PersistentTrustManager =
        PersistentTrustManager(
            identityStore =
                identity,
            trustedPeerStore =
                TestTrustedPeerStore(
                    initialPeers
                ),
            nowMs = { 10_000L }
        )

    private class TrustedControlTransport(
        val localPeerId: PeerId,
        private val sessionId: SessionId,
        private val channelBindingHex: String
    ) : TransferTransport,
        AuthenticatedChannelContextProvider {

        lateinit var remote:
            TrustedControlTransport

        private val controls =
            Channel<ControlMessage>(
                Channel.UNLIMITED
            )
        val sentControls =
            mutableListOf<Envelope>()
        var rewriteOutgoing:
            ((Envelope) -> Envelope)? = null

        override fun authenticatedChannelContext(
            peerId: PeerId
        ): AuthenticatedChannelContext =
            AuthenticatedChannelContext(
                sessionId,
                channelBindingHex
            )

        override suspend fun startDiscovery(
            serviceId: String
        ): Flow<DiscoveryEvent> =
            emptyFlow()

        override suspend fun stopDiscovery() {}

        override suspend fun startAdvertising(
            serviceId: String,
            localDeviceName: String
        ): Flow<AdvertisingEvent> =
            emptyFlow()

        override suspend fun stopAdvertising() {}

        override suspend fun initiateConnection(
            peerId: PeerId,
            localDeviceName: String
        ) {}

        override suspend fun acceptConnection(
            peerId: PeerId
        ) {}

        override suspend fun rejectConnection(
            peerId: PeerId
        ) {}

        override fun observeConnectionEvents():
            Flow<ConnectionEvent> =
            emptyFlow()

        override suspend fun sendControl(
            peerId: PeerId,
            payload: ByteArray
        ) {
            val original =
                ProtocolCodec.decode(
                    payload
                )
            sentControls += original

            val delivered =
                rewriteOutgoing
                    ?.invoke(original)
                    ?: original

            remote.controls.send(
                ControlMessage(
                    localPeerId,
                    ProtocolCodec.encode(
                        delivered
                    )
                )
            )
        }

        override fun observeControl():
            Flow<ControlMessage> =
            controls.receiveAsFlow()

        override fun observeIncomingStream():
            Flow<IncomingStream> =
            emptyFlow()

        override fun observeBulkProgress():
            Flow<BulkProgress> =
            emptyFlow()

        override suspend fun cancelPayload(
            bulkRef: BulkRef
        ) {}

        override suspend fun disconnect(
            peerId: PeerId
        ) {}
    }

    private class TestPersistentIdentityStore :
        PersistentIdentityStore {

        private var keyPair: KeyPair =
            generate()

        override fun hasIdentity():
            Boolean = true

        override fun getOrCreatePublicKeyDer():
            ByteArray =
            keyPair.public.encoded

        override fun getFingerprint():
            String =
            PeerIdentityVerifier
                .deriveFingerprint(
                    keyPair.public.encoded
                )

        override fun signTranscript(
            transcript: ByteArray
        ): ByteArray =
            PeerIdentityVerifier
                .signTranscript(
                    keyPair.private,
                    transcript
                )

        override fun deleteIdentity() {
            keyPair = generate()
        }

        private fun generate(): KeyPair =
            KeyPairGenerator
                .getInstance("EC")
                .apply {
                    initialize(
                        ECGenParameterSpec(
                            "secp256r1"
                        )
                    )
                }
                .generateKeyPair()
    }

    private class TestTrustedPeerStore(
        initialPeers:
            List<TrustedPeerRecord>
    ) : TrustedPeerStore {

        private val peers =
            MutableStateFlow(
                initialPeers
            )

        override fun observeAll():
            Flow<List<TrustedPeerRecord>> =
            peers

        override suspend fun getAll():
            List<TrustedPeerRecord> =
            peers.value

        override suspend fun getByFingerprint(
            fingerprintHex: String
        ): TrustedPeerRecord? =
            peers.value.firstOrNull {
                it.peerFingerprintHex ==
                    fingerprintHex
            }

        override suspend fun upsert(
            peer: TrustedPeerRecord
        ) {
            peers.value =
                peers.value
                    .filterNot {
                        it.peerFingerprintHex ==
                            peer.peerFingerprintHex
                    } + peer
        }

        override suspend fun updateLastSeen(
            fingerprintHex: String,
            lastSeenAtMs: Long
        ) {
            peers.value =
                peers.value.map { peer ->
                    if (peer.peerFingerprintHex ==
                        fingerprintHex
                    ) {
                        peer.copy(
                            lastSeenAtMs =
                                lastSeenAtMs
                        )
                    } else {
                        peer
                    }
                }
        }

        override suspend fun revoke(
            fingerprintHex: String
        ) {
            peers.value =
                peers.value.filterNot {
                    it.peerFingerprintHex ==
                        fingerprintHex
                }
        }

        override suspend fun revokeAll() {
            peers.value =
                emptyList()
        }

        override suspend fun count(): Int =
            peers.value.size
    }
}
