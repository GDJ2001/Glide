package dev.glide.transport.lan.pairing

import android.os.Build
import androidx.annotation.RequiresApi
import dev.glide.core.domain.TrustedPeerRecord
import dev.glide.core.identity.IdentityProofVerifier
import dev.glide.core.identity.PeerEnrollmentResult
import dev.glide.core.identity.PersistentTrustManager
import dev.glide.core.identity.VerifiedPeerIdentity
import dev.glide.core.pairing.LanPairV2Invitation
import dev.glide.core.pairing.Profile2Constants
import dev.glide.core.pairing.Profile2Crypto
import dev.glide.core.pairing.Profile2Message
import dev.glide.core.pairing.Profile2WireCodec
import dev.glide.transport.lan.tls.PinnedTlsConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

data class Profile2AndroidPairOutcome(
    val succeeded: Boolean,
    val peer: TrustedPeerRecord? = null,
    val errorCode: String? = null,
    val userMessage: String
)

/**
 * Executes a Profile 2 pair-only QR/link handshake (`qr-token-v2`) from Android to a desktop peer.
 * Never sends or accepts file offers on this session.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class Profile2PairingClient(
    private val connector: PinnedTlsConnector
) {
    private val secureRandom = SecureRandom()

    suspend fun runPairOnlyWithQrAsync(
        invitation: LanPairV2Invitation,
        localDeviceName: String,
        trustManager: PersistentTrustManager,
        approvePeer: suspend (VerifiedPeerIdentity) -> Boolean,
        onVerifying: (() -> Unit)? = null
    ): Profile2AndroidPairOutcome = withContext(Dispatchers.IO) {
        val nowSeconds = System.currentTimeMillis() / 1000L
        val expiresAtSeconds = invitation.expiresAt.toLongOrNull() ?: 0L
        if (nowSeconds > expiresAtSeconds) {
            return@withContext Profile2AndroidPairOutcome(
                succeeded = false,
                errorCode = Profile2Constants.ERROR_EXPIRED,
                userMessage = "Code expired — get a new code"
            )
        }

        val endpoint = invitation.endpoints.firstOrNull()
            ?: return@withContext Profile2AndroidPairOutcome(
                succeeded = false,
                errorCode = "INVALID_ENDPOINT",
                userMessage = "Couldn’t reach this device — check the local network"
            )

        val sslSocket = try {
            val address = InetAddress.getByName(endpoint.address)
            connector.connect(
                address = address,
                port = endpoint.port,
                expectedSpkiSha256Hex = invitation.spkiSha256
            )
        } catch (_: Exception) {
            return@withContext Profile2AndroidPairOutcome(
                succeeded = false,
                errorCode = "UNREACHABLE",
                userMessage = "Couldn’t reach this device — check the local network"
            )
        }

        sslSocket.use { socket ->
            socket.soTimeout = 30_000
            val input = socket.inputStream
            val output = socket.outputStream

            try {
                // 1. Write 4-byte GLD2 preamble
                output.write(Profile2Constants.GLD2_MAGIC_BYTES)
                output.flush()

                val sessionId = UUID.randomUUID().toString().lowercase()
                val initiatorNonce = ByteArray(32).also { secureRandom.nextBytes(it) }
                val initiatorSpki = trustManager.localPublicKeyDer()

                val open = Profile2Message.SessionOpen(
                    sessionId = sessionId,
                    purpose = Profile2Constants.PURPOSE_PAIR_ONLY,
                    authMethod = Profile2Constants.AUTH_METHOD_QR_TOKEN_V2,
                    initiatorNonce = toBase64Url(initiatorNonce),
                    initiatorDeviceName = localDeviceName,
                    initiatorPlatform = "android",
                    initiatorIdentityPublicKey = toBase64Url(initiatorSpki),
                    expectedPresenceId = invitation.presence,
                    invitationId = invitation.invitationId
                )
                Profile2WireCodec.writeMessage(output, open)

                val ack = Profile2WireCodec.readMessage(input) as? Profile2Message.SessionOpenAck
                    ?: return@withContext Profile2AndroidPairOutcome(
                        succeeded = false,
                        errorCode = Profile2Constants.ERROR_PROTOCOL_ORDER,
                        userMessage = "Pairing failed"
                    )

                if (!ack.accepted) {
                    return@withContext Profile2AndroidPairOutcome(
                        succeeded = false,
                        errorCode = ack.errorCode ?: Profile2Constants.ERROR_AUTH_FAILED,
                        userMessage = mapErrorToUserCopy(ack.errorCode, ack.responderDeviceName ?: invitation.name, ack.message)
                    )
                }

                val responderNonce = fromBase64Url(ack.responderNonce ?: "")
                val responderSpki = fromBase64Url(ack.responderIdentityPublicKey ?: "")
                val responderName = ack.responderDeviceName ?: invitation.name
                val advertisedPresenceId = ack.advertisedPresenceId ?: invitation.presence

                Profile2WireCodec.writeMessage(
                    output,
                    Profile2Message.PairMsg(
                        sessionId = sessionId,
                        role = Profile2Constants.ROLE_INITIATOR,
                        invitationHash = invitation.invitationHash
                    )
                )

                val peerMsgRaw = Profile2WireCodec.readMessage(input)
                if (peerMsgRaw is Profile2Message.PairResult) {
                    return@withContext Profile2AndroidPairOutcome(
                        succeeded = false,
                        errorCode = peerMsgRaw.errorCode ?: Profile2Constants.ERROR_AUTH_FAILED,
                        userMessage = mapErrorToUserCopy(peerMsgRaw.errorCode, responderName, peerMsgRaw.message)
                    )
                }

                val peerPairMsg = peerMsgRaw as? Profile2Message.PairMsg
                if (peerPairMsg == null || peerPairMsg.invitationHash != invitation.invitationHash) {
                    return@withContext Profile2AndroidPairOutcome(
                        succeeded = false,
                        errorCode = Profile2Constants.ERROR_EXPIRED,
                        userMessage = "Code expired — get a new code"
                    )
                }

                onVerifying?.invoke()

                val qrSecret = Profile2Crypto.deriveQrSharedSecret(invitation.token, invitation.invitationHash)
                val transcriptBytes = Profile2Crypto.buildPairV2TranscriptBytes(
                    sessionId = sessionId,
                    initiatorNonce32 = initiatorNonce,
                    responderNonce32 = responderNonce,
                    initiatorIdentitySpki = initiatorSpki,
                    responderIdentitySpki = responderSpki,
                    advertisedPresenceIdHex = advertisedPresenceId,
                    serverTlsSpkiSha256Hex = invitation.spkiSha256,
                    purpose = Profile2Constants.PURPOSE_PAIR_ONLY,
                    method = Profile2Constants.AUTH_METHOD_QR_TOKEN_V2
                )
                val transcriptHash = Profile2Crypto.sha256(transcriptBytes)
                val (confirmI2RKey, confirmR2IKey) = Profile2Crypto.deriveConfirmationKeys(qrSecret, transcriptHash)
                qrSecret.fill(0)

                try {
                    val myMac = Profile2Crypto.computeConfirmationMac(confirmI2RKey, transcriptHash)
                    val mySig = trustManager.signTranscript(transcriptBytes)

                    Profile2WireCodec.writeMessage(
                        output,
                        Profile2Message.PairConfirm(
                            sessionId = sessionId,
                            role = Profile2Constants.ROLE_INITIATOR,
                            keyConfirmationMac = toBase64Url(myMac),
                            identitySignature = toBase64Url(mySig)
                        )
                    )

                    val nextMsg = Profile2WireCodec.readMessage(input)
                    if (nextMsg is Profile2Message.PairResult) {
                        return@withContext Profile2AndroidPairOutcome(
                            succeeded = false,
                            errorCode = nextMsg.errorCode ?: Profile2Constants.ERROR_AUTH_FAILED,
                            userMessage = mapErrorToUserCopy(nextMsg.errorCode, responderName, nextMsg.message)
                        )
                    }

                    val peerConfirm = nextMsg as? Profile2Message.PairConfirm
                        ?: return@withContext Profile2AndroidPairOutcome(
                            succeeded = false,
                            errorCode = Profile2Constants.ERROR_PROTOCOL_ORDER,
                            userMessage = "Pairing failed"
                        )

                    val peerMac = fromBase64Url(peerConfirm.keyConfirmationMac)
                    val expectedPeerMac = Profile2Crypto.computeConfirmationMac(confirmR2IKey, transcriptHash)
                    if (!MessageDigest.isEqual(peerMac, expectedPeerMac)) {
                        return@withContext Profile2AndroidPairOutcome(
                            succeeded = false,
                            errorCode = Profile2Constants.ERROR_AUTH_FAILED,
                            userMessage = "Code expired — get a new code"
                        )
                    }

                    val peerSig = fromBase64Url(peerConfirm.identitySignature)
                    val verifiedPeer = try {
                        IdentityProofVerifier.verifyPairV2Signature(
                            publicKeyDer = responderSpki,
                            transcript = transcriptBytes,
                            signatureBytes = peerSig,
                            displayName = responderName
                        )
                    } catch (_: Exception) {
                        return@withContext Profile2AndroidPairOutcome(
                            succeeded = false,
                            errorCode = Profile2Constants.ERROR_AUTH_FAILED,
                            userMessage = "Identity changed — pair again"
                        )
                    }

                    val approved = approvePeer(verifiedPeer)
                    Profile2WireCodec.writeMessage(
                        output,
                        Profile2Message.PairApproval(
                            sessionId = sessionId,
                            role = Profile2Constants.ROLE_INITIATOR,
                            approved = approved,
                            reason = if (approved) null else Profile2Constants.ERROR_DECLINED
                        )
                    )

                    val respApprovalMsg = Profile2WireCodec.readMessage(input)
                    if (respApprovalMsg is Profile2Message.PairResult) {
                        return@withContext Profile2AndroidPairOutcome(
                            succeeded = false,
                            errorCode = respApprovalMsg.errorCode ?: Profile2Constants.ERROR_DECLINED,
                            userMessage = mapErrorToUserCopy(respApprovalMsg.errorCode, responderName, respApprovalMsg.message)
                        )
                    }
                    val respApproval = respApprovalMsg as? Profile2Message.PairApproval
                        ?: return@withContext Profile2AndroidPairOutcome(
                            succeeded = false,
                            errorCode = Profile2Constants.ERROR_PROTOCOL_ORDER,
                            userMessage = "Pairing failed"
                        )

                    val finalResult = Profile2WireCodec.readMessage(input) as? Profile2Message.PairResult
                    if (finalResult == null || !finalResult.paired || !approved || !respApproval.approved) {
                        val code = finalResult?.errorCode ?: Profile2Constants.ERROR_DECLINED
                        return@withContext Profile2AndroidPairOutcome(
                            succeeded = false,
                            errorCode = code,
                            userMessage = mapErrorToUserCopy(code, responderName, finalResult?.message)
                        )
                    }

                    return@withContext when (val enrollment = trustManager.enrollVerifiedPeer(verifiedPeer, userApproved = true)) {
                        is PeerEnrollmentResult.Enrolled -> Profile2AndroidPairOutcome(
                            succeeded = true,
                            peer = enrollment.peer,
                            userMessage = "Paired with ${enrollment.peer.displayName}"
                        )
                        is PeerEnrollmentResult.AlreadyEnrolled -> Profile2AndroidPairOutcome(
                            succeeded = true,
                            peer = enrollment.peer,
                            userMessage = "Paired with ${enrollment.peer.displayName}"
                        )
                        PeerEnrollmentResult.Declined -> Profile2AndroidPairOutcome(
                            succeeded = false,
                            errorCode = Profile2Constants.ERROR_DECLINED,
                            userMessage = "Pairing declined"
                        )
                    }
                } finally {
                    confirmI2RKey.fill(0)
                    confirmR2IKey.fill(0)
                }
            } catch (_: Exception) {
                return@withContext Profile2AndroidPairOutcome(
                    succeeded = false,
                    errorCode = "UNREACHABLE",
                    userMessage = "Couldn’t reach this device — check the local network"
                )
            }
        }
    }

    companion object {
        fun mapErrorToUserCopy(errorCode: String?, peerName: String? = null, fallback: String? = null): String {
            return when (errorCode) {
                Profile2Constants.ERROR_EXPIRED -> "Code expired — get a new code"
                Profile2Constants.ERROR_BUSY -> "This device is busy — try again"
                Profile2Constants.ERROR_IDENTITY_CHANGED -> "Identity changed — pair again"
                Profile2Constants.ERROR_UNSUPPORTED_VERSION -> "Update Glide to pair this device"
                Profile2Constants.ERROR_DECLINED -> if (peerName.isNullOrBlank()) "Pairing declined" else "$peerName declined pairing"
                else -> fallback?.takeIf { it.isNotBlank() } ?: "Couldn’t reach this device — check the local network"
            }
        }

        private fun toBase64Url(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun fromBase64Url(value: String): ByteArray =
            Base64.getUrlDecoder().decode(value)
    }
}
