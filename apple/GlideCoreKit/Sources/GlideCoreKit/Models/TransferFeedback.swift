import Foundation

public enum TransferResultKind: String, Equatable, Sendable {
    case complete
    case failed
    case declined
    case cancelled

    public var title: String {
        switch self {
        case .complete: return "Transfer complete"
        case .failed: return "Transfer failed"
        case .declined: return "Transfer declined"
        case .cancelled: return "Transfer cancelled"
        }
    }
}

public struct TransferFeedback: Equatable, Sendable {
    public let kind: TransferResultKind
    public let peerName: String
    public let fileCount: Int
    public let totalBytes: Int64
    public let message: String
    public let technicalDetails: String?

    public init(kind: TransferResultKind, peerName: String, fileCount: Int = 0,
                totalBytes: Int64 = 0, message: String = "",
                technicalDetails: String? = nil) {
        self.kind = kind
        self.peerName = peerName
        self.fileCount = fileCount
        self.totalBytes = totalBytes
        self.message = message
        self.technicalDetails = technicalDetails
    }
}
