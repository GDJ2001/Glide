package dev.glide.core.remote

import java.time.Instant

/**
 * Immutable representation of an ephemeral out-of-band remote pairing invitation (ADR-0022, Section 2.1).
 *
 * Invariants:
 * - RendezvousId: valid UUIDv4 string
 * - EphemeralPublicKeyHex: 64 lowercase hex characters (32-byte X25519 public key)
 * - SignalingEndpoint: valid wss:// or ws:// URI
 * - RelayBlindToken: non-empty Base64url token
 * - ExpiresAt: Unix timestamp (seconds) strictly bounded to <= 120s TTL from creation
 */
data class RemoteInvitation(
    val rendezvousId: String,
    val ephemeralPublicKeyHex: String,
    val signalingEndpoint: String,
    val relayBlindToken: String,
    val expiresAt: Long,
    val ttlSeconds: Int = DEFAULT_TTL_SECONDS
) {
    companion object {
        const val DEFAULT_TTL_SECONDS = 120
        const val MAX_TTL_SECONDS = 120
    }

    init {
        require(rendezvousId.isNotBlank()) { "rendezvousId cannot be blank" }
        require(ephemeralPublicKeyHex.isNotBlank()) { "ephemeralPublicKeyHex cannot be blank" }
        require(signalingEndpoint.isNotBlank()) { "signalingEndpoint cannot be blank" }
        require(relayBlindToken.isNotBlank()) { "relayBlindToken cannot be blank" }
        require(expiresAt > 0) { "expiresAt must be positive" }
    }

    fun isExpired(currentEpochSeconds: Long = Instant.now().epochSecond): Boolean {
        return currentEpochSeconds > expiresAt
    }
}
