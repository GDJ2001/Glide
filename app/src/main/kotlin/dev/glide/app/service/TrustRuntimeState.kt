package dev.glide.app.service

import dev.glide.core.domain.TrustedPeerRecord

data class PendingTrustEnrollment(
    val fingerprintHex: String,
    val displayName: String
)

data class TrustRuntimeState(
    val isInitialized: Boolean = false,
    val localFingerprintHex: String? = null,
    val trustedPeers: List<TrustedPeerRecord> = emptyList(),
    val pendingEnrollment: PendingTrustEnrollment? = null,
    val securityNotice: String? = null,
    val errorMessage: String? = null
)
