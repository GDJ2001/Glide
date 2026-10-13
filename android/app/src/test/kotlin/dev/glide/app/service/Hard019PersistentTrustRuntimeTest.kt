package dev.glide.app.service

import dev.glide.core.domain.TrustedPeerRecord
import dev.glide.core.identity.IdentityProofVerificationContext
import dev.glide.core.identity.IdentityProofVerifier
import dev.glide.core.identity.PersistentTrustManager
import dev.glide.core.identity.TrustedPeerStore
import dev.glide.core.identity.VerifiedPeerIdentity
import dev.glide.core.identity.PeerIdentityVerifier
import dev.glide.core.protocol.IdentityResponseDto
import dev.glide.core.protocol.IdentityResumeTranscript
import dev.glide.data.identity.JvmDeviceIdentityKeyStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

@OptIn(ExperimentalCoroutinesApi::class)
class Hard019PersistentTrustRuntimeTest {

    @Test
    fun trustManagement_worksWithoutTransferServiceScope() =
        runTest {
            val identity =
                JvmDeviceIdentityKeyStore()
            val originalFingerprint =
                identity.getFingerprint()
            val peers =
                RuntimeTrustedPeerStore(
                    listOf(samplePeer())
                )
            val runtime =
                TransferRuntime.forTesting(
                    persistentTrustManager =
                        PersistentTrustManager(
                            identity,
                            peers,
                            nowMs = { 5_000L }
                        )
                )

            assertTrue(
                runtime.refreshPersistentTrustState()
            )
            assertEquals(
                1,
                runtime.trustState.value
                    .trustedPeers.size
            )

            assertTrue(
                runtime.revokeTrustedPeerNow(
                    samplePeer().peerFingerprintHex
                )
            )
            assertEquals(0, peers.count())
            assertTrue(
                runtime.trustState.value
                    .trustedPeers.isEmpty()
            )

            peers.upsert(samplePeer())
            assertTrue(
                runtime.rotateLocalIdentityNow()
            )
            assertEquals(0, peers.count())
            assertNotEquals(
                originalFingerprint,
                runtime.trustState.value
                    .localFingerprintHex
            )
        }

    @Test
    fun stoppingServiceTrustObservation_doesNotReportFailure() =
        runTest {
            val runtime =
                TransferRuntime.forTesting(
                    persistentTrustManager =
                        PersistentTrustManager(
                            JvmDeviceIdentityKeyStore(),
                            RuntimeTrustedPeerStore()
                        )
                )

            runtime.attachServiceScope(this)
            advanceUntilIdle()
            assertTrue(runtime.trustState.value.isInitialized)

            runtime.detachServiceScope()?.join()
            advanceUntilIdle()
            assertNull(runtime.trustState.value.errorMessage)
        }

    @Test
    fun verifiedEnrollment_requiresExplicitApproval() =
        runTest {
            val identity =
                JvmDeviceIdentityKeyStore()
            identity.getFingerprint()
            val peers =
                RuntimeTrustedPeerStore()
            val runtime =
                TransferRuntime.forTesting(
                    persistentTrustManager =
                        PersistentTrustManager(
                            identity,
                            peers,
                            nowMs = { 10_000L }
                        )
                )

            runtime.attachServiceScope(this)
            advanceUntilIdle()

            val verified =
                createVerifiedPeer(
                    verifierFingerprint =
                        identity.getFingerprint()
                )
            assertTrue(
                runtime.stageVerifiedPeerForEnrollment(
                    verified
                )
            )
            assertEquals(
                verified.fingerprintHex,
                runtime.trustState.value
                    .pendingEnrollment
                    ?.fingerprintHex
            )
            assertEquals(0, peers.count())

            assertFalse(
                runtime.dispatch(
                    TransferRuntimeCommand
                        .ApprovePeerEnrollment(
                            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                        )
                )
            )
            assertEquals(0, peers.count())
            assertTrue(
                runtime.trustState.value
                    .pendingEnrollment != null
            )

            assertTrue(
                runtime.dispatch(
                    TransferRuntimeCommand
                        .ApprovePeerEnrollment(
                            verified.fingerprintHex
                        )
                )
            )
            advanceUntilIdle()

            assertEquals(1, peers.count())
            assertNull(
                runtime.trustState.value
                    .pendingEnrollment
            )
            assertEquals(
                verified.fingerprintHex,
                runtime.trustState.value
                    .trustedPeers
                    .single()
                    .peerFingerprintHex
            )

            runtime.detachServiceScope()
        }

    @Test
    fun rejectedEnrollment_neverPersistsPeer() =
        runTest {
            val identity =
                JvmDeviceIdentityKeyStore()
            identity.getFingerprint()
            val peers =
                RuntimeTrustedPeerStore()
            val runtime =
                TransferRuntime.forTesting(
                    persistentTrustManager =
                        PersistentTrustManager(
                            identity,
                            peers
                        )
                )
            runtime.attachServiceScope(this)
            advanceUntilIdle()

            val verified =
                createVerifiedPeer(
                    verifierFingerprint =
                        identity.getFingerprint()
                )
            assertTrue(
                runtime.stageVerifiedPeerForEnrollment(
                    verified
                )
            )
            assertTrue(
                runtime.dispatch(
                    TransferRuntimeCommand
                        .RejectPeerEnrollment(
                            verified.fingerprintHex
                        )
                )
            )
            advanceUntilIdle()

            assertEquals(0, peers.count())
            assertNull(
                runtime.trustState.value
                    .pendingEnrollment
            )

            runtime.detachServiceScope()
        }

    private fun createVerifiedPeer(
        verifierFingerprint: String
    ): VerifiedPeerIdentity {
        val signer = generateKeyPair()
        val signerFingerprint =
            PeerIdentityVerifier.deriveFingerprint(
                signer.public.encoded
            )
        val capabilities =
            setOf(
                "stream-v1",
                "sha256",
                "folder-v1",
                "identity-v1"
            )
        val context =
            IdentityProofVerificationContext(
                activeSessionId =
                    "11111111-1111-4111-8111-111111111111",
                challengeMessageId =
                    "22222222-2222-4222-8222-222222222222",
                signerRole = "sender",
                verifierRole = "receiver",
                verifierFingerprintHex =
                    verifierFingerprint,
                challengeNonceHex =
                    "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
                channelBindingHex =
                    "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100",
                negotiatedCapabilities =
                    capabilities
            )
        val transcript =
            IdentityResumeTranscript
                .identityProofBytes(
                    activeSessionId =
                        context.activeSessionId,
                    challengeMessageId =
                        context.challengeMessageId,
                    signerRole =
                        context.signerRole,
                    verifierRole =
                        context.verifierRole,
                    signerFingerprintHex =
                        signerFingerprint,
                    verifierFingerprintHex =
                        context.verifierFingerprintHex,
                    challengeNonceHex =
                        context.challengeNonceHex,
                    channelBindingHex =
                        context.channelBindingHex,
                    capabilityHashHex =
                        IdentityResumeTranscript
                            .capabilityHashHex(
                                capabilities
                            )
                )
        val signature =
            PeerIdentityVerifier
                .signTranscript(
                    signer.private,
                    transcript
                )

        return IdentityProofVerifier.verify(
            context = context,
            response =
                IdentityResponseDto(
                    profile =
                        IdentityResumeTranscript
                            .IDENTITY_PROFILE,
                    challengeMessageId =
                        context.challengeMessageId,
                    challengeNonceHex =
                        context.challengeNonceHex,
                    signerFingerprintHex =
                        signerFingerprint,
                    publicKeyDerHex =
                        with(PeerIdentityVerifier) {
                            signer.public.encoded
                                .toHex()
                        },
                    signatureHex =
                        with(PeerIdentityVerifier) {
                            signature.toHex()
                        }
                ),
            displayName = "Verified peer"
        )
    }

    private fun generateKeyPair(): KeyPair =
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

    private fun samplePeer() =
        TrustedPeerRecord(
            peerFingerprintHex =
                "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd",
            publicKeyDerHex = "3059",
            displayName = "Existing peer",
            pairedAtMs = 1_000L,
            lastSeenAtMs = 2_000L
        )
}

private class RuntimeTrustedPeerStore(
    initialPeers: List<TrustedPeerRecord> =
        emptyList()
) : TrustedPeerStore {

    private val peers =
        MutableStateFlow(initialPeers)

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
        peers.value = emptyList()
    }

    override suspend fun count(): Int =
        peers.value.size
}
