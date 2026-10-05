package dev.glide.transport.lan

import java.io.IOException

/**
 * Typed failure taxonomy for Android LAN TLS connections.
 */
sealed interface LanConnectionFailure {
    val message: String

    data class OffLinkDestination(
        override val message: String = "Destination is not on the same local subnet"
    ) : LanConnectionFailure

    data class Timeout(
        override val message: String = "Connection timed out"
    ) : LanConnectionFailure

    data class AuthenticationFailed(
        override val message: String = "Cryptographic identity verification failed"
    ) : LanConnectionFailure

    data class NetworkUnavailable(
        override val message: String = "No eligible local network available"
    ) : LanConnectionFailure

    data class Other(
        override val message: String
    ) : LanConnectionFailure
}

/**
 * Exception carrying a typed [LanConnectionFailure].
 */
class LanConnectionException(
    val failure: LanConnectionFailure,
    cause: Throwable? = null
) : IOException(failure.message, cause)
