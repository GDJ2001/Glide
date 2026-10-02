import XCTest
@testable import GlideCoreKit

final class AppleUiSmokeTests: XCTestCase {
    private var tempDir: URL!

    override func setUpWithError() throws {
        try super.setUpWithError()
        tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("AppleUiSmokeTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if FileManager.default.fileExists(atPath: tempDir.path) {
            try? FileManager.default.removeItem(at: tempDir)
        }
        try super.tearDownWithError()
    }

    // 1. Send View & File Staging Smoke Test
    func testSendFlowAndFileStaging() throws {
        let fileA = tempDir.appendingPathComponent("test1.txt")
        let fileB = tempDir.appendingPathComponent("test2.bin")
        try "Test file A contents".write(to: fileA, atomically: true, encoding: .utf8)
        try Data(repeating: 0x42, count: 1024).write(to: fileB)

        let store = StagedFilesStore()
        store.stage(fileA)
        store.stage(fileB)

        let staged = store.stagedUrls
        XCTAssertEqual(staged.count, 2)
        XCTAssertTrue(staged.contains(fileA))
        XCTAssertTrue(staged.contains(fileB))
        XCTAssertGreaterThan(store.totalSizeBytes, 1000)

        // Clear staged files
        store.removeAll()
        XCTAssertEqual(store.stagedUrls.count, 0)
        XCTAssertEqual(store.totalSizeBytes, 0)
    }

    // 2. Receive View & Invitation State Smoke Test
    func testReceiveFlowAndInvitationState() throws {
        let auth = OneTimeTokenAuthority()
        let token = auth.issueToken(ttlSeconds: 120)
        XCTAssertNotNil(token)
        XCTAssertEqual(token?.tokenBytes.count, 16)

        let dummySpki = Data(repeating: 0xAA, count: 32)
        let inv = InvitationCodec.format(token: token!.tokenBytes, spkiSha256: dummySpki, port: 42424)
        XCTAssertTrue(inv.starts(with: "glide://pair/v1#"))

        // Decode invitation
        let decoded = try InvitationCodec.parse(inv)
        XCTAssertEqual(decoded.port, 42424)
        XCTAssertEqual(decoded.spkiSha256, dummySpki)
    }

    // 3. Pair View & Invalid Invitation Handling
    func testPairFlowAndErrorHandling() {
        // Invalid prefix
        XCTAssertThrowsError(try InvitationCodec.parse("http://example.com")) { error in
            XCTAssertEqual(error as? InvitationCodec.InvitationError, .invalidPrefix)
        }

        // Corrupted token hex
        XCTAssertThrowsError(try InvitationCodec.parse("glide://pair/v1#p=42424&s=aabbcc&t=not-hex")) { error in
            XCTAssertNotNil(error)
        }
    }

    // 4. Activity & History Model Smoke Test
    func testActivityAndHistoryFormatting() {
        let date = Date()
        let bytes: Int64 = 1024 * 1024 * 5 // 5 MB
        let formatted = ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
        XCTAssertFalse(formatted.isEmpty)

        let dateFormatter = DateFormatter()
        dateFormatter.dateStyle = .medium
        dateFormatter.timeStyle = .short
        let dateStr = dateFormatter.string(from: date)
        XCTAssertFalse(dateStr.isEmpty)
    }

    // 5. Settings & Theme Tokens Smoke Test
    func testSettingsAndThemeTokens() {
        XCTAssertEqual(GlideTheme.brandBlueHex, "#0D6EFD")
        XCTAssertEqual(GlideTheme.brandSuccessHex, "#198754")
        XCTAssertEqual(GlideTheme.cardCornerRadius, 16.0)
        XCTAssertEqual(GlideTheme.buttonCornerRadius, 12.0)
    }

    // 6. SAS Mutual Authentication Smoke Test
    func testConsentAndSasComparison() throws {
        let clientKey = Data(repeating: 0x01, count: 32)
        let serverKey = Data(repeating: 0x02, count: 32)
        let sessionNonce = Data(repeating: 0x03, count: 32)

        let sasA = try SasCalculator.calculateSas(clientPublicKey: clientKey, serverPublicKey: serverKey, sessionNonce: sessionNonce)
        let sasB = try SasCalculator.calculateSas(clientPublicKey: clientKey, serverPublicKey: serverKey, sessionNonce: sessionNonce)

        let formattedA = String(format: "%06d", sasA)
        let formattedB = String(format: "%06d", sasB)

        XCTAssertEqual(formattedA.count, 6)
        XCTAssertEqual(formattedA, formattedB)
        XCTAssertTrue(formattedA.allSatisfy { $0.isNumber })
    }

    // 7. Share Extension Handoff Smoke Test
    func testShareExtensionHandoff() throws {
        let shareFile = tempDir.appendingPathComponent("shared_doc.txt")
        try "Shared document from another application".write(to: shareFile, atomically: true, encoding: .utf8)

        let batch = try ShareExtensionCoordinator.prepareBatch(from: [shareFile])
        XCTAssertEqual(batch.itemCount, 1)
        XCTAssertEqual(batch.items[0].name, "shared_doc.txt")
        XCTAssertEqual(batch.items[0].mimeType, "text/plain")
        XCTAssertGreaterThan(batch.totalSizeBytes, 0)
    }

    // 8. Mac Drop Target Coordinator Smoke Test
    func testMacDropTargetCoordinator() throws {
        let dropped = tempDir.appendingPathComponent("dropped_item.png")
        try "PNG FAKE DATA".write(to: dropped, atomically: true, encoding: .utf8)

        let validated = try MacDropTargetCoordinator.validateDrop(fileUrls: [dropped])
        XCTAssertEqual(validated.count, 1)
        XCTAssertEqual(validated[0].lastPathComponent, "dropped_item.png")
    }
}
