import Foundation

/// Represents a single file item queued for sharing from the iOS Share Sheet extension.
public struct ShareItem: Equatable, Sendable {
    public let name: String
    public let sizeBytes: Int64
    public let mimeType: String
    public let sourceUrl: URL

    public init(name: String, sizeBytes: Int64, mimeType: String, sourceUrl: URL) {
        self.name = name
        self.sizeBytes = sizeBytes
        self.mimeType = mimeType
        self.sourceUrl = sourceUrl
    }
}

/// Represents an assembled batch of files queued for outbound transmission.
public struct ShareBatch: Equatable, Sendable {
    public let items: [ShareItem]
    public let totalSizeBytes: Int64
    public var itemCount: Int { items.count }

    public init(items: [ShareItem]) {
        self.items = items
        self.totalSizeBytes = items.reduce(0) { $0 + $1.sizeBytes }
    }
}

/// Coordinates file preparation and validation for the iOS Share Sheet extension.
/// Conforms to Section 6.1 of APPLE_PLATFORMS_SPECIFICATION.md and Protocol V1 batch limits.
public enum ShareExtensionCoordinator {
    // Protocol V1 Bounded Limits
    public static let maxItemSizeBytes: Int64 = 68_719_476_736   // 64 GiB
    public static let maxBatchSizeBytes: Int64 = 274_877_906_944 // 256 GiB
    public static let maxItemCount: Int = 10_000

    public enum ShareError: Error, Equatable, Sendable {
        case emptyBatch
        case tooManyItems(actual: Int, max: Int)
        case itemTooLarge(name: String, size: Int64, max: Int64)
        case batchTooLarge(totalSize: Int64, max: Int64)
        case fileNotFound(String)
    }

    /// Prepares and validates a `ShareBatch` from a collection of file URLs.
    public static func prepareBatch(from fileUrls: [URL]) throws -> ShareBatch {
        guard !fileUrls.isEmpty else {
            throw ShareError.emptyBatch
        }
        guard fileUrls.count <= maxItemCount else {
            throw ShareError.tooManyItems(actual: fileUrls.count, max: maxItemCount)
        }

        var items: [ShareItem] = []
        var runningTotal: Int64 = 0

        for url in fileUrls {
            let filename = url.lastPathComponent
            guard FileManager.default.fileExists(atPath: url.path) else {
                throw ShareError.fileNotFound(url.path)
            }

            let attrs = try? FileManager.default.attributesOfItem(atPath: url.path)
            let fileSize = (attrs?[.size] as? NSNumber)?.int64Value ?? 0

            guard fileSize <= maxItemSizeBytes else {
                throw ShareError.itemTooLarge(name: filename, size: fileSize, max: maxItemSizeBytes)
            }

            runningTotal += fileSize
            guard runningTotal <= maxBatchSizeBytes else {
                throw ShareError.batchTooLarge(totalSize: runningTotal, max: maxBatchSizeBytes)
            }

            let mimeType = mimeTypeForExtension((filename as NSString).pathExtension)
            items.append(ShareItem(name: filename, sizeBytes: fileSize, mimeType: mimeType, sourceUrl: url))
        }

        return ShareBatch(items: items)
    }

    /// Derives standard MIME type based on file extension.
    public static func mimeTypeForExtension(_ ext: String) -> String {
        switch ext.lowercased() {
        case "jpg", "jpeg": return "image/jpeg"
        case "png": return "image/png"
        case "heic": return "image/heic"
        case "gif": return "image/gif"
        case "mp4": return "video/mp4"
        case "mov": return "video/quicktime"
        case "pdf": return "application/pdf"
        case "zip": return "application/zip"
        case "txt": return "text/plain"
        case "json": return "application/json"
        default: return "application/octet-stream"
        }
    }
}
