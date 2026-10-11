import XCTest
@testable import GlideCoreKit

final class MacDropTargetCoordinatorTests: XCTestCase {
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

    func testValidateDropThrowsOnEmptyList() {
        XCTAssertThrowsError(try MacDropTargetCoordinator.validateDrop(fileUrls: [])) { error in
            XCTAssertEqual(error as? MacDropTargetCoordinator.DropError, MacDropTargetCoordinator.DropError.emptyDrop)
        }
    }

    func testValidateDropThrowsOnMissingFile() {
        let missing = tempDir.appendingPathComponent("missing_file.bin")
        XCTAssertThrowsError(try MacDropTargetCoordinator.validateDrop(fileUrls: [missing])) { error in
            guard case MacDropTargetCoordinator.DropError.fileNotFound = error else {
                XCTFail("Expected fileNotFound but got \(error)")
                return
            }
        }
    }

    func testPrepareDropBatchWithValidFiles() throws {
        let file1 = tempDir.appendingPathComponent("document.pdf")
        let file2 = tempDir.appendingPathComponent("notes.txt")

        try "SAMPLE PDF DATA".write(to: file1, atomically: true, encoding: .utf8)
        try "NOTE TEXT".write(to: file2, atomically: true, encoding: .utf8)

        let batch = try MacDropTargetCoordinator.prepareDropBatch(from: [file1, file2])

        XCTAssertEqual(batch.itemCount, 2)
        XCTAssertEqual(batch.items[0].name, "document.pdf")
        XCTAssertEqual(batch.items[0].mimeType, "application/pdf")
        XCTAssertEqual(batch.items[1].name, "notes.txt")
        XCTAssertEqual(batch.items[1].mimeType, "text/plain")
        XCTAssertGreaterThan(batch.totalSizeBytes, 0)
    }

    func testDirectoryTraversalAndDsStoreFiltering() throws {
        let subDir = tempDir.appendingPathComponent("ProjectFolder")
        try FileManager.default.createDirectory(at: subDir, withIntermediateDirectories: true)

        let validFile = subDir.appendingPathComponent("source.swift")
        let dsStore = subDir.appendingPathComponent(".DS_Store")
        let resourceFork = subDir.appendingPathComponent("._source.swift")

        try "let a = 1".write(to: validFile, atomically: true, encoding: .utf8)
        try "BINARY DS_STORE DATA".write(to: dsStore, atomically: true, encoding: .utf8)
        try "RESOURCE FORK".write(to: resourceFork, atomically: true, encoding: .utf8)

        let batch = try MacDropTargetCoordinator.prepareDropBatch(from: [subDir])

        // Only validFile should be included, .DS_Store and ._source.swift must be filtered out
        XCTAssertEqual(batch.itemCount, 1)
        XCTAssertEqual(batch.items[0].name, "source.swift")
    }

    func testShouldIgnoreFilename() {
        XCTAssertTrue(MacDropTargetCoordinator.shouldIgnoreFilename(".DS_Store"))
        XCTAssertTrue(MacDropTargetCoordinator.shouldIgnoreFilename("Thumbs.db"))
        XCTAssertTrue(MacDropTargetCoordinator.shouldIgnoreFilename("__MACOSX"))
        XCTAssertTrue(MacDropTargetCoordinator.shouldIgnoreFilename(".Spotlight-V100"))
        XCTAssertTrue(MacDropTargetCoordinator.shouldIgnoreFilename(".Trashes"))
        XCTAssertTrue(MacDropTargetCoordinator.shouldIgnoreFilename("._image.png"))

        XCTAssertFalse(MacDropTargetCoordinator.shouldIgnoreFilename("archive.zip"))
        XCTAssertFalse(MacDropTargetCoordinator.shouldIgnoreFilename("presentation.key"))
        XCTAssertFalse(MacDropTargetCoordinator.shouldIgnoreFilename("photo.heic"))
    }

    func testSelectPeerAndInitiateTransfer() throws {
        let coordinator = MacDropTargetCoordinator()
        XCTAssertNil(coordinator.selectedPeer)

        let peer = BonjourPeer(
            name: "MacBook Pro M3",
            endpoint: "192.168.1.120:42424",
            presenceToken: "0123456789abcdef0123456789abcdef"
        )

        let file = tempDir.appendingPathComponent("test.txt")
        try "HELLO".write(to: file, atomically: true, encoding: .utf8)
        let batch = try MacDropTargetCoordinator.prepareDropBatch(from: [file])

        // Transfer fails without selected peer
        XCTAssertThrowsError(try coordinator.initiateTransfer(batch: batch)) { error in
            XCTAssertEqual(error as? MacDropTargetCoordinator.DropError, MacDropTargetCoordinator.DropError.noPeerSelected)
        }

        // Select peer and succeed
        coordinator.selectPeer(peer)
        XCTAssertEqual(coordinator.selectedPeer, peer)

        let request = try coordinator.initiateTransfer(batch: batch)
        XCTAssertEqual(request.peer, peer)
        XCTAssertEqual(request.batch.itemCount, 1)
        XCTAssertFalse(request.sessionId.isEmpty)
    }

    func testBoundedLimitsConstants() {
        XCTAssertEqual(MacDropTargetCoordinator.maxItemSizeBytes, 68_719_476_736)   // 64 GiB
        XCTAssertEqual(MacDropTargetCoordinator.maxBatchSizeBytes, 274_877_906_944) // 256 GiB
        XCTAssertEqual(MacDropTargetCoordinator.maxItemCount, 10_000)
    }
}
