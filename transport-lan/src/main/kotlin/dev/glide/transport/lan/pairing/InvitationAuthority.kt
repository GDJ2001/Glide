package dev.glide.transport.lan.pairing

import dev.glide.core.pairing.InvitationCodec
import dev.glide.core.pairing.LanEndpoint
import dev.glide.core.pairing.LanInvitation
import dev.glide.core.transport.lan.LanBootstrapFailureCode
import dev.glide.core.transport.lan.LanBootstrapMessage
import dev.glide.core.transport.lan.LanBootstrapResult
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Clock abstraction for deterministic time and monotonic bounds testing (LAN-011, Section 8, Section 19).
 */
interface AuthorityClock {
    fun epochMillis(): Long
    fun monotonicNanos(): Long

    companion object {
        val SYSTEM = object : AuthorityClock {
            override fun epochMillis(): Long = System.currentTimeMillis()
            override fun monotonicNanos(): Long = System.nanoTime()
        }
    }
}

/**
 * In-memory authority for LAN QR pairing invitations and atomic bootstrap consumption (LAN-011, Section 8).
 *
 * Enforces:
 * - CSPRNG token (32 bytes) and presence (16 bytes).
 * - Exact decoded JSON byte hash (SHA-256) matching.
 * - Monotonic expiry boundary (120-second default, unaffected by wall-clock rollback).
 * - Atomic single-use consumption under synchronized lock.
 * - Revocation on refresh, stop receive, network change, or process reset.
 * - Replay rejection: consumed tokens/invitations cannot be revived or unconsumed.
 */
class InvitationAuthority(
    private val clock: AuthorityClock = AuthorityClock.SYSTEM,
    private val secureRandom: SecureRandom = SecureRandom()
) {

    private data class ActiveInvitation(
        val invitation: LanInvitation,
        val tokenHash: ByteArray,
        val networkGeneration: Long,
        val monotonicExpiresAtNs: Long,
        var isRevoked: Boolean = false
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ActiveInvitation) return false
            return invitation.invitationId == other.invitation.invitationId
        }

        override fun hashCode(): Int = invitation.invitationId.hashCode()
    }

    private data class ConsumedRecord(
        val sessionId: String,
        val consumedAtNs: Long
    )

    private var activeInvitation: ActiveInvitation? = null
    private val consumedInvitations = mutableMapOf<String, ConsumedRecord>()

    /**
     * Mints a fresh, high-entropy [LanInvitation] bound to the current network generation.
     * Revokes any previously active invitation.
     */
    @Synchronized
    fun mintInvitation(
        endpoints: List<LanEndpoint>,
        spkiSha256: String,
        deviceName: String,
        platform: String = "android",
        ttlSeconds: Int = DEFAULT_TTL_SECONDS,
        networkGeneration: Long = 0L
    ): LanInvitation {
        require(endpoints.isNotEmpty()) { "At least one endpoint is required to mint invitation" }
        require(spkiSha256.length == 64) { "spkiSha256 must be 64 lowercase hex characters" }

        // Revoke existing active invitation immediately
        activeInvitation?.isRevoked = true

        val invitationId = UUID.randomUUID().toString()

        // 16 random bytes for presence (32 hex characters)
        val presenceBytes = ByteArray(16).apply { secureRandom.nextBytes(this) }
        val presence = presenceBytes.joinToString("") { "%02x".format(it) }

        // 32 independently generated random bytes for token (43 base64url characters)
        val tokenBytes = ByteArray(32).apply { secureRandom.nextBytes(this) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)

        val nowEpochMs = clock.epochMillis()
        val expiresAtEpochSec = (nowEpochMs + ttlSeconds * 1000L) / 1000L

        // Initial invitation before hash calculation
        val draftInvitation = LanInvitation(
            v = 1,
            transport = "lan-tls",
            profile = 1,
            protocol = 1,
            invitationId = invitationId,
            presence = presence,
            name = deviceName,
            platform = platform,
            endpoints = endpoints,
            spkiSha256 = spkiSha256.lowercase(),
            token = token,
            expiresAt = expiresAtEpochSec.toString(),
            ttlSeconds = ttlSeconds,
            invitationHash = ""
        )

        // Compute canonical invitationHash
        val invitationHash = InvitationCodec.computeInvitationHash(draftInvitation)
        val finalInvitation = draftInvitation.copy(invitationHash = invitationHash)

        // Store SHA-256 of token to avoid keeping plaintext token in authority
        val md = MessageDigest.getInstance("SHA-256")
        val tokenHash = md.digest(token.toByteArray(StandardCharsets.UTF_8))

        val monotonicExpiresAtNs = clock.monotonicNanos() + ttlSeconds * 1_000_000_000L

        activeInvitation = ActiveInvitation(
            invitation = finalInvitation,
            tokenHash = tokenHash,
            networkGeneration = networkGeneration,
            monotonicExpiresAtNs = monotonicExpiresAtNs
        )

        return finalInvitation
    }

    /**
     * Atomically consumes the invitation if the incoming [message] matches the active invitation,
     * network generation, role, token hash, and invitation hash.
     *
     * Exactly one valid attempt succeeds. Replays, expired tokens, or revoked invitations are rejected.
     */
    @Synchronized
    fun consume(
        message: LanBootstrapMessage,
        currentNetworkGeneration: Long,
        isBusy: Boolean
    ): LanBootstrapResult {
        // 1. Busy check: if server has an active transfer, return BUSY
        if (isBusy) {
            return LanBootstrapResult.rejected(LanBootstrapFailureCode.BUSY)
        }

        // 2. Profile / role validation
        if (message.v != 1 || message.role != LanBootstrapMessage.ROLE_SENDER) {
            return LanBootstrapResult.rejected(LanBootstrapFailureCode.UNSUPPORTED_PROFILE)
        }

        // 3. Check if this invitation was ALREADY consumed
        if (consumedInvitations.containsKey(message.invitationId)) {
            return LanBootstrapResult.rejected(LanBootstrapFailureCode.INVITATION_USED)
        }

        val active = activeInvitation
        // 4. Verify invitation exists and matches ID
        if (active == null || active.invitation.invitationId != message.invitationId) {
            return LanBootstrapResult.rejected(LanBootstrapFailureCode.AUTHENTICATION_FAILED)
        }

        // 5. Check if revoked
        if (active.isRevoked) {
            return LanBootstrapResult.rejected(LanBootstrapFailureCode.AUTHENTICATION_FAILED)
        }

        // 6. Check network generation binding
        if (currentNetworkGeneration != active.networkGeneration) {
            return LanBootstrapResult.rejected(LanBootstrapFailureCode.AUTHENTICATION_FAILED)
        }

        // 7. Check monotonic expiry boundary
        if (clock.monotonicNanos() >= active.monotonicExpiresAtNs) {
            active.isRevoked = true
            return LanBootstrapResult.rejected(LanBootstrapFailureCode.INVITATION_EXPIRED)
        }

        // 8. Constant-time token hash comparison
        val md = MessageDigest.getInstance("SHA-256")
        val candidateTokenHash = md.digest(message.token.toByteArray(StandardCharsets.UTF_8))
        if (!MessageDigest.isEqual(candidateTokenHash, active.tokenHash)) {
            return LanBootstrapResult.rejected(LanBootstrapFailureCode.AUTHENTICATION_FAILED)
        }

        // 9. Constant-time invitationHash comparison
        val candidateInvHash = message.invitationHash.lowercase().toByteArray(StandardCharsets.US_ASCII)
        val expectedInvHash = active.invitation.invitationHash.lowercase().toByteArray(StandardCharsets.US_ASCII)
        if (!MessageDigest.isEqual(candidateInvHash, expectedInvHash)) {
            return LanBootstrapResult.rejected(LanBootstrapFailureCode.AUTHENTICATION_FAILED)
        }

        // 10. Atomic consumption
        consumedInvitations[active.invitation.invitationId] = ConsumedRecord(
            sessionId = message.sessionId,
            consumedAtNs = clock.monotonicNanos()
        )
        activeInvitation = null // consumed, no longer active

        return LanBootstrapResult.accepted(sessionId = message.sessionId)
    }

    /**
     * Revokes any active invitation (e.g. on Stop Receive, network change, or process reset).
     */
    @Synchronized
    fun revokeAll() {
        activeInvitation?.isRevoked = true
        activeInvitation = null
    }

    /**
     * Revokes the current active invitation.
     */
    @Synchronized
    fun revokeActiveInvitation() {
        revokeAll()
    }

    @Synchronized
    fun isConsumed(invitationId: String): Boolean = consumedInvitations.containsKey(invitationId)

    @Synchronized
    fun getActiveInvitation(): LanInvitation? = activeInvitation?.takeIf { !it.isRevoked }?.invitation

    companion object {
        const val DEFAULT_TTL_SECONDS = 120
    }
}
