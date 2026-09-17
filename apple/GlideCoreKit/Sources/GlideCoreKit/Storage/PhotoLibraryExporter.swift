import Foundation

/// Classification of media files eligible for automatic Apple Photos Library export.
public enum PhotoMediaType: Equatable, Sendable {
    case image
    case video
    case nonMedia
}

/// Identifies media files eligible for Photos Library export (Section 7.2 of APPLE_PLATFORMS_SPECIFICATION.md).
public enum PhotoLibraryExporter {
    private static let imageExtensions: Set<String> = [
        "jpg", "jpeg", "png", "heic", "heif", "gif", "webp", "tiff", "bmp", "raw", "dng"
    ]

    private static let videoExtensions: Set<String> = [
        "mp4", "mov", "m4v", "avi", "mkv", "webm"
    ]

    /// Classifies an incoming filename into its corresponding media type.
    public static func classify(filename: String) -> PhotoMediaType {
        let ext = (filename as NSString).pathExtension.lowercased()
        if imageExtensions.contains(ext) {
            return .image
        }
        if videoExtensions.contains(ext) {
            return .video
        }
        return .nonMedia
    }

    /// Determines whether the file should be considered for direct export to Photos library.
    public static func isMediaFile(filename: String) -> Bool {
        return classify(filename: filename) != .nonMedia
    }
}
