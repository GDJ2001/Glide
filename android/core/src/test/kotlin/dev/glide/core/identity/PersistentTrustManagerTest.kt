package dev.glide.core.identity

import dev.glide.core.domain.TrustedPeerRecord
import dev.glide.core.protocol.IdentityResponseDto
import dev.glide.core.protocol.IdentityResumeTranscript
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class PersistentTrustManagerTest {

    @Test
    fun initialize_restoredTrustWithoutLocalKey_revokesAllAndCreatesNewIdentity() =
        runBlocking {
            val peers =
                FakeTrustedPeerStore(
                    listOf(samplePeer())
                )
            val identity =
                FakePersistentIdentityStore(
                    initialIdentity = false
                )
            val manager =
                PersistentTrustManager(
                    identityStore = identity,
                    trustedPeerStore = peers,
                    nowMs = { 5_000L }
                )

            val result = manager.initialize()

            assertTrue(
                result is
                    LocalIdentityInitialization.ResetAfterKeyLoss
            )
            result as
                LocalIdentityInitialization.ResetAfterKeyLoss
            assertEquals(1, result.revokedPeerCount)
            assertTrue(identity.hasIdentity())
            assertEquals(0, peers.count())
            assertEquals(
                identity.getFingerprint(),
                result.localFingerprintHex
            )
        }

    @Test
    fun initialize_existingIdentity_preservesTrustedPeers() =
        runBlocking {
            val peers =
                FakeTrustedPeerStore(
                    listOf(samplePeer())
                )
            val identity =
                FakePersistentIdentityStore(
                    initialIdentity = true
                )
            val manager =
                PersistentTrustManager(
                    identityStore = identity,
                    trustedPeerStore = peers
                )

            val result = manager.initialize()

            assertTrue(
                result is
                    LocalIdentityInitialization.Ready
            )
            assertEquals(1, peers.count())
        }

    @Test
    fun enrollVerifiedPeer_duplicateRefreshesInsteadOfDuplicating() =
        runBlocking {
            var now = 10_000L
            val peers = FakeTrustedPeerStore()
            val manager =
                PersistentTrustManager(
                    identityStore =
                        FakePersistentIdentityStore(true),
                    trustedPeerStore = peers,
                    nowMs = { now }
                )
            val verified =
                VerifiedPeerIdentity(
                    fingerprintHex =
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    publicKeyDerHex = "3059",
                    displayName = "Pixel"
                )

            val first =
                manager.enrollVerifiedPeer(
                    verifiedPeer = verified,
                    userApproved = true
                )
            assertTrue(
                first is PeerEnrollmentResult.Enrolled
            )
            assertEquals(1, peers.count())

            now = 20_000L
            val second =
                manager.enrollVerifiedPeer(
                    verifiedPeer =
                        VerifiedPeerIdentity(
                            fingerprintHex =
                                verified.fingerprintHex,
                            publicKeyDerHex =
                                verified.publicKeyDerHex,
                            displayName =
                                "Pixel Renamed"
                        ),
                    userApproved = true
                )

            assertTrue(
                second is
                    PeerEnrollmentResult.AlreadyEnrolled
            )
            assertEquals(1, peers.count())
            val stored =
                peers.getByFingerprint(
                    verified.fingerprintHex
                )!!
            assertEquals(
                "Pixel Renamed",
                stored.displayName
            )
            assertEquals(10_000L, stored.pairedAtMs)
            assertEquals(20_000L, stored.lastSeenAtMs)
        }

    @Test
    fun enrollVerifiedPeer_declined_doesNotPersist() =
        runBlocking {
            val peers = FakeTrustedPeerStore()
            val manager =
                PersistentTrustManager(
                    FakePersistentIdentityStore(true),
                    peers
                )
            val result =
                manager.enrollVerifiedPeer(
                    VerifiedPeerIdentity(
                        fingerprintHex =
                            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        publicKeyDerHex = "3059",
                        displayName = "Pixel"
                    ),
                    userApproved = false
                )

            assertTrue(
                result is PeerEnrollmentResult.Declined
            )
            assertEquals(0, peers.count())
        }

    @Test
    fun rotateLocalIdentity_revokesTrustAndChangesFingerprint() =
        runBlocking {
            val identity =
                FakePersistentIdentityStore(true)
            val originalFingerprint =
                identity.getFingerprint()
            val peers =
                FakeTrustedPeerStore(
                    listOf(samplePeer())
                )
            val manager =
                PersistentTrustManager(
                    identity,
                    peers
                )

            val result =
                manager.rotateLocalIdentity()

            assertEquals(1, result.revokedPeerCount)
            assertEquals(0, peers.count())
            assertNotEquals(
                originalFingerprint,
                result.localFingerprintHex
            )
        }

    @Test
    fun identityProofVerifier_acceptsBoundProofAndRejectsWrongChannel() {
        val signer = generateKeyPair()
        val verifier = generateKeyPair()
        val signerFingerprint =
            PeerIdentityVerifier.deriveFingerprint(
                signer.public.encoded
            )
        val verifierFingerprint =
            PeerIdentityVerifier.deriveFingerprint(
                verifier.public.encoded
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
        val response =
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
                        signer.public.encoded.toHex()
                    },
                signatureHex =
                    with(PeerIdentityVerifier) {
                        signature.toHex()
                    }
            )

        val verified =
            IdentityProofVerifier.verify(
                context = context,
                response = response,
                displayName = "Sender"
            )
        assertEquals(
            signerFingerprint,
            verified.fingerprintHex
        )

        val wrongChannel =
            context.copy(
                channelBindingHex =
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            )
        var failed = false
        try {
            IdentityProofVerifier.verify(
                context = wrongChannel,
                response = response,
                displayName = "Sender"
            )
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(
            "Proof from another channel must fail",
            failed
        )
    }

    private fun samplePeer() =
        TrustedPeerRecord(
            peerFingerprintHex =
                "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd",
            publicKeyDerHex = "3059",
            displayName = "Existing peer",
            pairedAtMs = 1_000L,
            lastSeenAtMs = 2_000L
        )

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
}

private class FakePersistentIdentityStore(
    initialIdentity: Boolean
) : PersistentIdentityStore {

    private var keyPair: KeyPair? =
        if (initialIdentity) {
            generate()
        } else {
            null
        }

    override fun hasIdentity(): Boolean =
        keyPair != null

    override fun getOrCreatePublicKeyDer(): ByteArray =
        getOrCreate().public.encoded

    override fun getFingerprint(): String =
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

    private fun getOrCreate(): KeyPair {
        val existing = keyPair
        if (existing != null) {
            return existing
        }

        return generate().also {
            keyPair = it
        }
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

private class FakeTrustedPeerStore(
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
