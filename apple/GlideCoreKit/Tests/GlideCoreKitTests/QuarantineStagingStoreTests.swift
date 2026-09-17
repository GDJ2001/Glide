import XCTest
import CryptoKit
@testable import GlideCoreKit

final class QuarantineStagingStoreTests: XCTestCase {
    private var tempBaseDir: URL!

    override func setUpWithError() throws {
        try super.setUpWithError()
        tempBaseDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: tempBaseDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if FileManager.default.fileExists(atPath: tempBaseDir.path) {
            try? FileManager.default.removeItem(at: tempBaseDir)
        }
        try super.tearDownWithError()
    }

    func testChunkStreamingAndCommitSucceeds() throws {
        let store = QuarantineStagingStore(baseDirectory: tempBaseDir, sessionId: "session-1", itemId: "item-1")
        try store.open()

        let chunk1 = "Hello ".data(using: .utf8)!
        let chunk2 = "Glide ".data(using: .utf8)!
        let chunk3 = "Quarantine!".data(using: .utf8)!

        try store.writeChunk(chunk1)
        try store.writeChunk(chunk2)
        try store.writeChunk(chunk3)

        // Compute expected SHA-256 for "Hello Glide Quarantine!"
        let expectedDigest = SHA256.hash(data: "Hello Glide Quarantine!".data(using: .utf8)!)
        let expectedHex = expectedDigest.map { String(format: "%02x", $0) }.joined()

        let destDir = tempBaseDir.appendingPathComponent("Downloads")
        let committedUrl = try store.finalizeAndCommit(
            expectedSha256Hex: expectedHex,
            destinationDirectory: destDir,
            rawFilename: "document.txt"
        )

        XCTAssertTrue(FileManager.default.fileExists(atPath: committedUrl.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.quarantineUrl.path))

        let readData = try Data(contentsOf: committedUrl)
        XCTAssertEqual(String(data: readData, encoding: .utf8), "Hello Glide Quarantine!")
    }

    func testDigestMismatchPurgesQuarantineFile() throws {
        let store = QuarantineStagingStore(baseDirectory: tempBaseDir, sessionId: "session-2", itemId: "item-2")
        try store.open()

        let chunk = "Corrupted data bytes".data(using: .utf8)!
        try store.writeChunk(chunk)

        let wrongHex = String(repeating: "f", count: 64)
        let destDir = tempBaseDir.appendingPathComponent("Downloads")

        XCTAssertThrowsError(try store.finalizeAndCommit(
            expectedSha256Hex: wrongHex,
            destinationDirectory: destDir,
            rawFilename: "corrupt.bin"
        )) { error in
            guard case QuarantineError.digestMismatch = error else {
                XCTFail("Expected digestMismatch error but got \(error)")
                return
            }
        }

        // Strict Invariant: Quarantine file must be deleted immediately on mismatch
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.quarantineUrl.path))
    }

    func testPurgeExplicitlyDeletesQuarantineFile() throws {
        let store = QuarantineStagingStore(baseDirectory: tempBaseDir, sessionId: "session-3", itemId: "item-3")
        try store.open()

        try store.writeChunk("Sample data".data(using: .utf8)!)
        XCTAssertTrue(FileManager.default.fileExists(atPath: store.quarantineUrl.path))

        store.purge()
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.quarantineUrl.path))
    }

    func testCommitResolvesFilenameCollisions() throws {
        let destDir = tempBaseDir.appendingPathComponent("Downloads")
        try FileManager.default.createDirectory(at: destDir, withIntermediateDirectories: true)

        // Pre-create existing file
        let existingFile = destDir.appendingPathComponent("report.pdf")
        try "Original Report".write(to: existingFile, atomically: true, encoding: .utf8)

        let store = QuarantineStagingStore(baseDirectory: tempBaseDir, sessionId: "session-4", itemId: "item-4")
        try store.open()

        let content = "New Report".data(using: .utf8)!
        try store.writeChunk(content)

        let hex = SHA256.hash(data: content).map { String(format: "%02x", $0) }.joined()

        let committedUrl = try store.finalizeAndCommit(
            expectedSha256Hex: hex,
            destinationDirectory: destDir,
            rawFilename: "report.pdf"
        )

        XCTAssertEqual(committedUrl.lastPathComponent, "report (1).pdf")
        XCTAssertTrue(FileManager.default.fileExists(atPath: existingFile.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: committedUrl.path))
    }
}
