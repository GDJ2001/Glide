import XCTest
@testable import GlideCoreKit

final class ShareExtensionCoordinatorTests: XCTestCase {
    private var tempDir: URL!

    override func setUpWithError() throws {
        try super.setUpWithError()
        tempDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if FileManager.default.fileExists(atPath: tempDir.path) {
            try? FileManager.default.removeItem(at: tempDir)
        }
        try super.tearDownWithError()
    }

    func testPrepareBatchSucceedsForValidFiles() throws {
        let file1 = tempDir.appendingPathComponent("image.png")
        let file2 = tempDir.appendingPathComponent("document.pdf")

        try "PNG DATA".write(to: file1, atomically: true, encoding: .utf8)
        try "PDF CONTENT DATA".write(to: file2, atomically: true, encoding: .utf8)

        let batch = try ShareExtensionCoordinator.prepareBatch(from: [file1, file2])

        XCTAssertEqual(batch.itemCount, 2)
        XCTAssertEqual(batch.items[0].name, "image.png")
        XCTAssertEqual(batch.items[0].mimeType, "image/png")
        XCTAssertEqual(batch.items[1].name, "document.pdf")
        XCTAssertEqual(batch.items[1].mimeType, "application/pdf")
        XCTAssertGreaterThan(batch.totalSizeBytes, 0)
    }

    func testPrepareBatchThrowsOnEmptyList() {
        XCTAssertThrowsError(try ShareExtensionCoordinator.prepareBatch(from: [])) { error in
            XCTAssertEqual(error as? ShareExtensionCoordinator.ShareError, ShareExtensionCoordinator.ShareError.emptyBatch)
        }
    }

    func testPrepareBatchThrowsOnMissingFile() {
        let missing = tempDir.appendingPathComponent("nonexistent.txt")
        XCTAssertThrowsError(try ShareExtensionCoordinator.prepareBatch(from: [missing])) { error in
            guard case ShareExtensionCoordinator.ShareError.fileNotFound = error else {
                XCTFail("Expected fileNotFound error but got \(error)")
                return
            }
        }
    }

    func testMimeTypeDerivation() {
        XCTAssertEqual(ShareExtensionCoordinator.mimeTypeForExtension("jpg"), "image/jpeg")
        XCTAssertEqual(ShareExtensionCoordinator.mimeTypeForExtension("mp4"), "video/mp4")
        XCTAssertEqual(ShareExtensionCoordinator.mimeTypeForExtension("zip"), "application/zip")
        XCTAssertEqual(ShareExtensionCoordinator.mimeTypeForExtension("unknown"), "application/octet-stream")
    }

    func testPrepareBatchRejectsSymbolicLink() throws {
        let source = tempDir.appendingPathComponent("source.txt")
        let link = tempDir.appendingPathComponent("link.txt")
        try "data".write(to: source, atomically: true, encoding: .utf8)
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: source)

        XCTAssertThrowsError(try ShareExtensionCoordinator.prepareBatch(from: [link])) { error in
            XCTAssertEqual(error as? ShareExtensionCoordinator.ShareError,
                           .unsupportedFile("link.txt"))
        }
    }
}
