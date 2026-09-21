package dev.glide.core.resume

import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.TrustedPeerRecord
import dev.glide.core.identity.PeerEnrollmentResult
import dev.glide.core.identity.PeerIdentityVerifier
import dev.glide.core.identity.PersistentIdentityStore
import dev.glide.core.identity.PersistentTrustManager
import dev.glide.core.identity.TrustedPeerStore
import dev.glide.core.identity.VerifiedPeerIdentity
import dev.glide.core.ports.AuthenticatedChannelContext
import dev.glide.core.protocol.NegotiationConstants
import dev.glide.core.protocol.ResumeRejectCodes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class ResumeRequestAuthorityTest {

    private val activeSession =
        SessionId(
            "11111111-1111-4111-8111-111111111111"
        )
    private val originalSession =
        SessionId(
            "22222222-2222-4222-8222-222222222222"
        )
    private val itemId =
        ItemId(
            "33333333-3333-4333-8333-333333333333"
        )
    private val channelBinding =
        "ab".repeat(32)
    private val capabilities =
        setOf(
            NegotiationConstants
                .CAPABILITY_STREAM_V1,
            NegotiationConstants
                .CAPABILITY_SHA256,
            NegotiationConstants
                .CAPABILITY_PERSISTENT_IDENTITY_V1,
            NegotiationConstants
                .CAPABILITY_TRUE_RESUME_V1
        )
    private val rootHash =
        "cd".repeat(32)

    @Test
    fun validEnrolledRequest_verifiesAndAcceptsExactPrefix() =
        runBlocking {
            val fixture =
                trustFixture()
            val request =
                ResumeRequestAuthority
                    .createRequest(
                        activeSessionId =
                            activeSession,
                        channelContext =
                            channelContext(),
                        negotiatedCapabilities =
                            capabilities,
                        trustManager =
                            fixture.receiverManager,
                        responderFingerprintHex =
                            fixture.senderFingerprint,
                        journal =
                            receiverJournal()
                    )

            val verified =
                ResumeRequestAuthority
                    .verifyRequest(
                        activeSessionId =
                            activeSession,
                        channelContext =
                            channelContext(),
                        negotiatedCapabilities =
                            capabilities,
                        trustManager =
                            fixture.senderManager,
                        authenticatedRequesterFingerprintHex =
                            fixture
                                .receiverFingerprint,
                        request = request,
                        sourceAuthority =
                            senderAuthority()
                    )

            assertEquals(
                2L,
                verified.startChunkIndex
            )
            assertEquals(
                2L * 1024 * 1024,
                verified.startByteOffset
            )
            assertEquals(
                fixture.receiverFingerprint,
                verified
                    .requesterFingerprintHex
            )

            val accept =
                ResumeRequestAuthority
                    .createAccept(
                        requestMessageId =
                            "44444444-4444-4444-8444-444444444444",
                        verified = verified
                    )
            assertEquals(
                "44444444-4444-4444-8444-444444444444",
                accept.requestMessageId
            )
            assertEquals(
                2L,
                accept.startChunkIndex
            )
            assertEquals(
                "2097152",
                accept.startByteOffset
            )
        }

    @Test
    fun requestBoundToDifferentChannel_isRejectedUnauthorized() =
        runBlocking {
            val fixture =
                trustFixture()
            val request =
                ResumeRequestAuthority
                    .createRequest(
                        activeSession,
                        channelContext(),
                        capabilities,
                        fixture.receiverManager,
                        fixture.senderFingerprint,
                        receiverJournal()
                    )

            assertRejected(
                ResumeRejectCodes
                    .UNAUTHORIZED
            ) {
                ResumeRequestAuthority
                    .verifyRequest(
                        activeSession,
                        AuthenticatedChannelContext(
                            activeSession,
                            "ef".repeat(32)
                        ),
                        capabilities,
                        fixture.senderManager,
                        fixture.receiverFingerprint,
                        request,
                        senderAuthority()
                    )
            }
        }

    @Test
    fun capabilityDowngrade_isRejectedBeforeSignatureUse() =
        runBlocking {
            val fixture =
                trustFixture()
            val request =
                ResumeRequestAuthority
                    .createRequest(
                        activeSession,
                        channelContext(),
                        capabilities,
                        fixture.receiverManager,
                        fixture.senderFingerprint,
                        receiverJournal()
                    )

            assertRejected(
                ResumeRejectCodes
                    .CAPABILITY_MISMATCH
            ) {
                ResumeRequestAuthority
                    .verifyRequest(
                        activeSession,
                        channelContext(),
                        capabilities -
                            NegotiationConstants
                                .CAPABILITY_TRUE_RESUME_V1,
                        fixture.senderManager,
                        fixture.receiverFingerprint,
                        request,
                        senderAuthority()
                    )
            }
        }

    @Test
    fun revokedRequester_isRejectedUnauthorized() =
        runBlocking {
            val fixture =
                trustFixture()
            val request =
                ResumeRequestAuthority
                    .createRequest(
                        activeSession,
                        channelContext(),
                        capabilities,
                        fixture.receiverManager,
                        fixture.senderFingerprint,
                        receiverJournal()
                    )

            fixture.senderManager
                .revokePeer(
                    fixture.receiverFingerprint
                )

            assertRejected(
                ResumeRejectCodes
                    .UNAUTHORIZED
            ) {
                ResumeRequestAuthority
                    .verifyRequest(
                        activeSession,
                        channelContext(),
                        capabilities,
                        fixture.senderManager,
                        fixture.receiverFingerprint,
                        request,
                        senderAuthority()
                    )
            }
        }

    @Test
    fun changedSourceRoot_isRejectedFileChanged() =
        runBlocking {
            val fixture =
                trustFixture()
            val request =
                ResumeRequestAuthority
                    .createRequest(
                        activeSession,
                        channelContext(),
                        capabilities,
                        fixture.receiverManager,
                        fixture.senderFingerprint,
                        receiverJournal()
                    )

            assertRejected(
                ResumeRejectCodes
                    .FILE_CHANGED
            ) {
                ResumeRequestAuthority
                    .verifyRequest(
                        activeSession,
                        channelContext(),
                        capabilities,
                        fixture.senderManager,
                        fixture.receiverFingerprint,
                        request,
                        senderAuthority()
                            .copy(
                                chunkRootHashHex =
                                    "01".repeat(32)
                            )
                    )
            }
        }

    @Test
    fun tamperedSignature_isRejectedUnauthorized() =
        runBlocking {
            val fixture =
                trustFixture()
            val request =
                ResumeRequestAuthority
                    .createRequest(
                        activeSession,
                        channelContext(),
                        capabilities,
                        fixture.receiverManager,
                        fixture.senderFingerprint,
                        receiverJournal()
                    )
            val tampered =
                request.copy(
                    signatureHex =
                        if (request.signatureHex
                                .startsWith("00")
                        ) {
                            "01" +
                                request.signatureHex
                                    .drop(2)
                        } else {
                            "00" +
                                request.signatureHex
                                    .drop(2)
                        }
                )

            assertRejected(
                ResumeRejectCodes
                    .UNAUTHORIZED
            ) {
                ResumeRequestAuthority
                    .verifyRequest(
                        activeSession,
                        channelContext(),
                        capabilities,
                        fixture.senderManager,
                        fixture.receiverFingerprint,
                        tampered,
                        senderAuthority()
                    )
            }
        }

    @Test
    fun tamperedOffsetMath_isRejectedInvalidOffset() =
        runBlocking {
            val fixture =
                trustFixture()
            val request =
                ResumeRequestAuthority
                    .createRequest(
                        activeSession,
                        channelContext(),
                        capabilities,
                        fixture.receiverManager,
                        fixture.senderFingerprint,
                        receiverJournal()
                    )
                    .copy(
                        verifiedChunkCount =
                            3L
                    )

            assertRejected(
                ResumeRejectCodes
                    .INVALID_OFFSET
            ) {
                ResumeRequestAuthority
                    .verifyRequest(
                        activeSession,
                        channelContext(),
                        capabilities,
                        fixture.senderManager,
                        fixture.receiverFingerprint,
                        request,
                        senderAuthority()
                    )
            }
        }

    private fun receiverJournal() =
        ResumeRequestAuthority
            .ReceiverJournal(
                originalSessionId =
                    originalSession,
                itemId = itemId,
                verifiedChunkCount =
                    2L,
                contiguousByteOffset =
                    2L * 1024 * 1024,
                chunkSize =
                    1024 * 1024,
                totalSizeBytes =
                    5L * 1024 * 1024,
                chunkRootHashHex =
                    rootHash
            )

    private fun senderAuthority() =
        ResumeRequestAuthority
            .SenderSourceAuthority(
                originalSessionId =
                    originalSession,
                itemId = itemId,
                chunkSize =
                    1024 * 1024,
                totalSizeBytes =
                    5L * 1024 * 1024,
                chunkRootHashHex =
                    rootHash
            )

    private fun channelContext() =
        AuthenticatedChannelContext(
            sessionId =
                activeSession,
            channelBindingHex =
                channelBinding
        )

    private suspend fun trustFixture():
        TrustFixture {
        val senderIdentity =
            FakeIdentityStore()
        val receiverIdentity =
            FakeIdentityStore()
        val senderStore =
            FakePeerStore()
        val receiverStore =
            FakePeerStore()
        val senderManager =
            PersistentTrustManager(
                senderIdentity,
                senderStore
            )
        val receiverManager =
            PersistentTrustManager(
                receiverIdentity,
                receiverStore
            )
        senderManager.initialize()
        receiverManager.initialize()

        val senderFingerprint =
            senderManager
                .localFingerprintHex()
        val receiverFingerprint =
            receiverManager
                .localFingerprintHex()

        assertTrue(
            receiverManager
                .enrollVerifiedPeer(
                    verifiedPeer(
                        senderManager,
                        "Sender"
                    ),
                    userApproved = true
                ) is
                PeerEnrollmentResult.Enrolled
        )
        assertTrue(
            senderManager
                .enrollVerifiedPeer(
                    verifiedPeer(
                        receiverManager,
                        "Receiver"
                    ),
                    userApproved = true
                ) is
                PeerEnrollmentResult.Enrolled
        )

        return TrustFixture(
            senderManager,
            receiverManager,
            senderFingerprint,
            receiverFingerprint
        )
    }

    private fun verifiedPeer(
        manager: PersistentTrustManager,
        name: String
    ): VerifiedPeerIdentity =
        VerifiedPeerIdentity(
            fingerprintHex =
                manager.localFingerprintHex(),
            publicKeyDerHex =
                with(PeerIdentityVerifier) {
                    manager
                        .localPublicKeyDer()
                        .toHex()
                },
            displayName = name
        )

    private suspend fun assertRejected(
        code: String,
        block: suspend () -> Unit
    ) {
        try {
            block()
            fail(
                "Expected resume rejection $code"
            )
        } catch (
            error:
                ResumeRequestAuthority.Rejected
        ) {
            assertEquals(
                code,
                error.code
            )
        }
    }

    private data class TrustFixture(
        val senderManager:
            PersistentTrustManager,
        val receiverManager:
            PersistentTrustManager,
        val senderFingerprint: String,
        val receiverFingerprint: String
    )

    private class FakeIdentityStore :
        PersistentIdentityStore {

        private var keyPair:
            KeyPair? = null

        override fun hasIdentity():
            Boolean =
            keyPair != null

        override fun getOrCreatePublicKeyDer():
            ByteArray =
            getOrCreate()
                .public
                .encoded

        override fun getFingerprint():
            String =
            PeerIdentityVerifier
                .deriveFingerprint(
                    getOrCreatePublicKeyDer()
                )

        override fun signTranscript(
            transcript: ByteArray
        ): ByteArray =
            PeerIdentityVerifier
                .signTranscript(
                    getOrCreate().private,
                    transcript
                )

        override fun deleteIdentity() {
            keyPair = null
        }

        private fun getOrCreate():
            KeyPair {
            val existing =
                keyPair
            if (existing != null) {
                return existing
            }
            return KeyPairGenerator
                .getInstance("EC")
                .apply {
                    initialize(
                        ECGenParameterSpec(
                            "secp256r1"
                        )
                    )
                }
                .generateKeyPair()
                .also {
                    keyPair = it
                }
        }
    }

    private class FakePeerStore :
        TrustedPeerStore {

        private val peers =
            MutableStateFlow<
                List<TrustedPeerRecord>
            >(emptyList())

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
                            peer
                                .peerFingerprintHex
                    } + peer
        }

        override suspend fun updateLastSeen(
            fingerprintHex: String,
            lastSeenAtMs: Long
        ) {
            peers.value =
                peers.value.map {
                    if (it.peerFingerprintHex ==
                        fingerprintHex
                    ) {
                        it.copy(
                            lastSeenAtMs =
                                lastSeenAtMs
                        )
                    } else {
                        it
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

        override suspend fun count():
            Int =
            peers.value.size
    }
}
