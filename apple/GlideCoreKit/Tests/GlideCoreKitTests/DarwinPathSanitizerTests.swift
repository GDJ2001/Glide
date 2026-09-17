import XCTest
@testable import GlideCoreKit

final class DarwinPathSanitizerTests: XCTestCase {
    func testSanitizeStripsDirectoryTraversal() {
        let dirty = "../../etc/passwd"
        let clean = DarwinPathSanitizer.sanitizeFilename(dirty)
        XCTAssertEqual(clean, "etcpasswd")
        XCTAssertFalse(clean.contains("/"))
        XCTAssertFalse(clean.contains(".."))
    }

    func testSanitizeStripsColonsAndSlashes() {
        let dirty = "my:bad/file\\name.txt"
        let clean = DarwinPathSanitizer.sanitizeFilename(dirty)
        XCTAssertEqual(clean, "mybadfilename.txt")
    }

    func testSanitizeEmptyFallback() {
        let dirty = "   ..//:   "
        let clean = DarwinPathSanitizer.sanitizeFilename(dirty)
        XCTAssertEqual(clean, DarwinPathSanitizer.defaultFallbackName)
    }

    func testTruncatePreservesExtension() {
        let longBase = String(repeating: "a", count: 300) + ".png"
        let truncated = DarwinPathSanitizer.truncateToMaxBytes(longBase, maxBytes: 255)

        XCTAssertLessThanOrEqual(truncated.utf8.count, 255)
        XCTAssertTrue(truncated.hasSuffix(".png"))
    }

    func testResolveCollisionIncrementsSuffix() {
        let existing: Set<String> = ["photo.jpg", "photo (1).jpg", "photo (2).jpg"]
        let resolved = DarwinPathSanitizer.resolveCollision(filename: "photo.jpg", existingNames: existing)
        XCTAssertEqual(resolved, "photo (3).jpg")
    }

    func testResolveCollisionUnusedName() {
        let existing: Set<String> = ["photo.jpg"]
        let resolved = DarwinPathSanitizer.resolveCollision(filename: "video.mp4", existingNames: existing)
        XCTAssertEqual(resolved, "video.mp4")
    }
}
