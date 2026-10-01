import Foundation

public enum TransferError: Error, Equatable, Sendable {
    case invalidFileCount(Int)
    case fileNotFound(String)
    case itemSizeExceeded(String, Int64)
    case batchSizeExceeded(Int64)
    case peerDisconnected
    case bootstrapRejected(String)
    case offerRejected
    case verificationFailed(String)
    case protocolViolation(String)
    case cancelled
}

/// Apple transfer boundary. The former implementation omitted the authenticated
/// HELLO/OFFER/ITEM_RESULT sequence, ignored consent, and compared a receiver
/// digest with itself. Neither direction may transfer bytes or report completion
/// until the full Profile 1 runtime replaces it.
public final class GlideTransferEngine: Sendable {
    public static let shared = GlideTransferEngine()

    public init() {}

    public func sendFiles(
        fileUrls: [URL],
        to invitation: LanInvitation,
        localDeviceName: String = "",
        onProgress: (@Sendable (Double, Int64, Int64, String) -> Void)? = nil
    ) async throws -> Int {
        throw TransferError.protocolViolation(
            "Apple transfers are unavailable until authenticated Profile 1 is implemented."
        )
    }

    public func receiveFromInvitation(
        invitation: LanInvitation,
        destinationDirectory: URL,
        onConsent: @escaping @Sendable (String, Int, Int64) async -> Bool,
        onProgress: (@Sendable (Double, Int64, Int64, String) -> Void)? = nil
    ) async throws -> Int {
        throw TransferError.protocolViolation(
            "Apple transfers are unavailable until authenticated Profile 1 is implemented."
        )
    }
}
