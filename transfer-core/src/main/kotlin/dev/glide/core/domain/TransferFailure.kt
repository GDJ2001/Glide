package dev.glide.core.domain

enum class FailureCategory {
    Prerequisite,
    Environment,
    Link,
    Consent,
    Security,
    Lifecycle,
    Storage,
    IO,
    Integrity,
    Compatibility,
    User,
    System,
    Protocol,
    Capacity,
    ReceiptAmbiguity
}

sealed class TransferFailure(
    val code: String,
    val category: FailureCategory,
    val isRecoverable: Boolean,
    val message: String
) {
    data class PermissionDenied(val detail: String = "Permission denied") :
        TransferFailure("PERMISSION_DENIED", FailureCategory.Prerequisite, true, detail)

    data class DiscoveryUnavailable(val detail: String = "Discovery unavailable") :
        TransferFailure("DISCOVERY_UNAVAILABLE", FailureCategory.Environment, true, detail)

    data class PeerLost(val detail: String = "Peer device lost") :
        TransferFailure("PEER_LOST", FailureCategory.Link, true, detail)

    data class ConnectionRejected(val detail: String = "Connection rejected by peer") :
        TransferFailure("CONNECTION_REJECTED", FailureCategory.Consent, false, detail)

    data class UserRejected(val detail: String = "User declined the offer") :
        TransferFailure("USER_REJECTED", FailureCategory.Consent, false, detail)

    data class AuthenticationMismatch(val detail: String = "Authentication codes do not match") :
        TransferFailure("AUTHENTICATION_FAILED", FailureCategory.Security, false, detail)

    data class Timeout(val phase: String, val detail: String = "Operation timed out during $phase") :
        TransferFailure("TIMEOUT", FailureCategory.Lifecycle, true, detail)

    data class SourceUnavailable(val detail: String = "Source file unavailable") :
        TransferFailure("SOURCE_UNAVAILABLE", FailureCategory.Storage, true, detail)

    data class DestinationUnavailable(val detail: String = "Destination storage unavailable") :
        TransferFailure("DESTINATION_UNAVAILABLE", FailureCategory.Storage, true, detail)

    data class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) :
        TransferFailure(
            "INSUFFICIENT_STORAGE",
            FailureCategory.Storage,
            true,
            "Insufficient space: required $requiredBytes bytes, available $availableBytes bytes"
        )

    data class ReadFailure(val detail: String = "Error reading source stream") :
        TransferFailure("READ_FAILURE", FailureCategory.IO, true, detail)

    data class WriteFailure(val detail: String = "Error writing destination stream") :
        TransferFailure("WRITE_FAILURE", FailureCategory.IO, true, detail)

    data class TransferInterrupted(val detail: String = "Connection interrupted during transfer") :
        TransferFailure("TRANSFER_INTERRUPTED", FailureCategory.Link, true, detail)

    data class IntegrityMismatch(val expected: Sha256Digest, val actual: Sha256Digest) :
        TransferFailure("INTEGRITY_MISMATCH", FailureCategory.Integrity, false, "Digest mismatch")

    data class UnsupportedProtocol(val detail: String = "Unsupported protocol version or capability") :
        TransferFailure("UNSUPPORTED_PROTOCOL", FailureCategory.Compatibility, false, detail)

    data class UserCancelled(val detail: String = "Cancelled by user") :
        TransferFailure("USER_CANCELLED", FailureCategory.User, false, detail)

    data class SystemCancelled(val detail: String = "Cancelled by system") :
        TransferFailure("SYSTEM_CANCELLED", FailureCategory.System, true, detail)

    data class MalformedMessage(val detail: String = "Malformed protocol message") :
        TransferFailure("MALFORMED_MESSAGE", FailureCategory.Protocol, false, detail)

    data class ProtocolOrder(val detail: String = "Protocol order violation") :
        TransferFailure("PROTOCOL_ORDER", FailureCategory.Protocol, false, detail)

    data class LimitExceeded(val detail: String = "Protocol limit exceeded") :
        TransferFailure("LIMIT_EXCEEDED", FailureCategory.Protocol, false, detail)

    data class Busy(val detail: String = "Active session in progress") :
        TransferFailure("BUSY", FailureCategory.Capacity, true, detail)

    data class DeliveryUnconfirmed(val detail: String = "Receipt missing after transfer completion") :
        TransferFailure("DELIVERY_UNCONFIRMED", FailureCategory.ReceiptAmbiguity, true, detail)

    data class ExportFailure(val detail: String = "Error exporting received file") :
        TransferFailure("EXPORT_FAILURE", FailureCategory.Storage, true, detail)

    data class Unknown(val detail: String = "An unexpected error occurred") :
        TransferFailure("UNKNOWN_ERROR", FailureCategory.System, false, detail)
}
