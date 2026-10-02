import XCTest
import CryptoKit
import Network
@testable import GlideCoreKit

private actor ConnectionBox {
    private var continuation: CheckedContinuation<NWConnection, Error>?
    private var connection: NWConnection?

    func setConnection(_ conn: NWConnection) {
        if let cont = continuation {
            continuation = nil
            cont.resume(returning: conn)
        } else {
            connection = conn
        }
    }

    func getConnection(timeoutSeconds: TimeInterval = 15.0) async throws -> NWConnection {
        if let conn = connection {
            connection = nil
            return conn
        }
        return try await withCheckedThrowingContinuation { cont in
            self.continuation = cont
            Task {
                try await Task.sleep(nanoseconds: UInt64(timeoutSeconds * 1_000_000_000))
                if self.continuation != nil {
                    self.continuation = nil
                    cont.resume(throwing: TransferError.timedOut)
                }
            }
        }
    }
}

final class GlideTransferEngineTests: XCTestCase {
    private var tempDir: URL!
    private var senderDir: URL!
    private var receiverDir: URL!
    private var destDir: URL!

    override func setUpWithError() throws {
        try super.setUpWithError()
        tempDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        senderDir = tempDir.appendingPathComponent("Sender", isDirectory: true)
        receiverDir = tempDir.appendingPathComponent("Receiver", isDirectory: true)
        destDir = receiverDir.appendingPathComponent("Downloads", isDirectory: true)

        try FileManager.default.createDirectory(at: senderDir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: receiverDir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: destDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if FileManager.default.fileExists(atPath: tempDir.path) {
            try? FileManager.default.removeItem(at: tempDir)
        }
        try super.tearDownWithError()
    }

    @discardableResult
    private func createTestFile(named name: String, size: Int, pattern: UInt8 = 0x5a) throws -> URL {
        let fileUrl = senderDir.appendingPathComponent(name)
        let data = Data(repeating: pattern, count: size)
        try data.write(to: fileUrl)
        return fileUrl
    }

    private func sha256Hex(for url: URL) throws -> String {
        let data = try Data(contentsOf: url)
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    // MARK: - Production Arrangement 1: Sender -> Receiver Listener

    func testReceiverHostedTransferSucceedsWithVerifiedHash() async throws {
        let identity = try KeychainIdentityManager.shared.getOrCreateTlsIdentity()
        let listener = GlideNetworkListener()
        let authority = LanInvitationAuthority()
        let box = ConnectionBox()

        _ = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<UInt16, Error>) in
            do {
                try listener.start(
                    port: 0,
                    tlsIdentity: identity,
                    onReady: { port in
                        continuation.resume(returning: port)
                    },
                    onFailure: { error in
                        continuation.resume(throwing: error)
                    },
                    onConnection: { conn in
                        Task { await box.setConnection(conn) }
                    }
                )
            } catch {
                continuation.resume(throwing: error)
            }
        }
        defer { listener.stop() }

        let (invitation, _) = try AppleInvitationManager.publish(
            listener: listener,
            authority: authority,
            deviceName: "TestReceiver",
            platform: "macos"
        )

        // Create 2 test files (small 4 KiB and larger 128 KiB to test multi-chunk streaming)
        let f1 = try createTestFile(named: "small.txt", size: 4096, pattern: 0xaa)
        let f2 = try createTestFile(named: "large.bin", size: 131072, pattern: 0xbb)
        let f1Sha = try sha256Hex(for: f1)
        let f2Sha = try sha256Hex(for: f2)

        let senderEngine = GlideTransferEngine(storageDirectory: senderDir)
        let receiverEngine = GlideTransferEngine(storageDirectory: receiverDir)

        let receiveTask = Task<Int, Error> {
            let conn = try await box.getConnection()
            return try await receiverEngine.receiveInboundConnection(
                connection: conn,
                authority: authority,
                destinationDirectory: self.destDir,
                localDeviceName: "TestReceiver",
                onConsent: { peer, count, bytes in
                    XCTAssertEqual(count, 2)
                    return true
                }
            )
        }

        let sentCount = try await senderEngine.sendFiles(
            fileUrls: [f1, f2],
            to: invitation,
            localDeviceName: "TestSender"
        )

        let receivedCount = try await receiveTask.value

        XCTAssertEqual(sentCount, 2)
        XCTAssertEqual(receivedCount, 2)

        // Verify destination files exist and match SHA-256
        let dest1 = destDir.appendingPathComponent("small.txt")
        let dest2 = destDir.appendingPathComponent("large.bin")
        XCTAssertTrue(FileManager.default.fileExists(atPath: dest1.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: dest2.path))

        XCTAssertEqual(try sha256Hex(for: dest1), f1Sha)
        XCTAssertEqual(try sha256Hex(for: dest2), f2Sha)

        // Verify persistent history recorded on both engines
        XCTAssertEqual(senderEngine.historyStore.allEntries().count, 1)
        XCTAssertEqual(senderEngine.historyStore.allEntries()[0].status, "completed")
        XCTAssertEqual(senderEngine.historyStore.allEntries()[0].fileCount, 2)

        XCTAssertEqual(receiverEngine.historyStore.allEntries().count, 1)
        XCTAssertEqual(receiverEngine.historyStore.allEntries()[0].status, "completed")
        XCTAssertEqual(receiverEngine.historyStore.allEntries()[0].fileCount, 2)
    }

    // MARK: - Production Arrangement 2: Receiver -> Sender Hosted Listener

    func testSenderHostedTransferSucceedsWithRecipientConfirmation() async throws {
        let identity = try KeychainIdentityManager.shared.getOrCreateTlsIdentity()
        let listener = GlideNetworkListener()
        let authority = LanInvitationAuthority()
        let box = ConnectionBox()

        _ = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<UInt16, Error>) in
            do {
                try listener.start(
                    port: 0,
                    tlsIdentity: identity,
                    onReady: { port in
                        continuation.resume(returning: port)
                    },
                    onFailure: { error in
                        continuation.resume(throwing: error)
                    },
                    onConnection: { conn in
                        Task { await box.setConnection(conn) }
                    }
                )
            } catch {
                continuation.resume(throwing: error)
            }
        }
        defer { listener.stop() }

        let (invitation, _) = try AppleInvitationManager.publish(
            listener: listener,
            authority: authority,
            deviceName: "HostedSender",
            platform: "macos"
        )

        let testFile = try createTestFile(named: "hosted_share.pdf", size: 65536, pattern: 0xcc)
        let expectedSha = try sha256Hex(for: testFile)

        let senderEngine = GlideTransferEngine(storageDirectory: senderDir)
        let receiverEngine = GlideTransferEngine(storageDirectory: receiverDir)

        let hostedSendTask = Task<Int, Error> {
            let conn = try await box.getConnection()
            return try await senderEngine.sendHostedInboundConnection(
                connection: conn,
                authority: authority,
                fileUrls: [testFile],
                localDeviceName: "HostedSender",
                onConfirmRecipient: { recipient in
                    return true
                }
            )
        }

        let receivedCount = try await receiverEngine.receiveFromInvitation(
            invitation: invitation,
            destinationDirectory: destDir,
            localDeviceName: "TestReceiver",
            onConsent: { _, count, _ in
                XCTAssertEqual(count, 1)
                return true
            }
        )

        let sentCount = try await hostedSendTask.value

        XCTAssertEqual(sentCount, 1)
        XCTAssertEqual(receivedCount, 1)

        let committedFile = destDir.appendingPathComponent("hosted_share.pdf")
        XCTAssertTrue(FileManager.default.fileExists(atPath: committedFile.path))
        XCTAssertEqual(try sha256Hex(for: committedFile), expectedSha)
    }

    // MARK: - Multi-Page Offer Transfer (>32 Items)

    func testMultiPageOfferTransfer40Files() async throws {
        let identity = try KeychainIdentityManager.shared.getOrCreateTlsIdentity()
        let listener = GlideNetworkListener()
        let authority = LanInvitationAuthority()
        let box = ConnectionBox()

        _ = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<UInt16, Error>) in
            do {
                try listener.start(
                    port: 0,
                    tlsIdentity: identity,
                    onReady: { port in
                        continuation.resume(returning: port)
                    },
                    onFailure: { error in
                        continuation.resume(throwing: error)
                    },
                    onConnection: { conn in
                        Task { await box.setConnection(conn) }
                    }
                )
            } catch {
                continuation.resume(throwing: error)
            }
        }
        defer { listener.stop() }

        let (invitation, _) = try AppleInvitationManager.publish(
            listener: listener,
            authority: authority,
            deviceName: "TestReceiver",
            platform: "macos"
        )

        var fileUrls = [URL]()
        for i in 1...40 {
            let file = try createTestFile(named: "doc_\(i).dat", size: 1024, pattern: UInt8(i % 256))
            fileUrls.append(file)
        }

        let senderEngine = GlideTransferEngine(storageDirectory: senderDir)
        let receiverEngine = GlideTransferEngine(storageDirectory: receiverDir)

        let receiveTask = Task<Int, Error> {
            let conn = try await box.getConnection()
            return try await receiverEngine.receiveInboundConnection(
                connection: conn,
                authority: authority,
                destinationDirectory: self.destDir,
                localDeviceName: "TestReceiver",
                onConsent: { _, count, _ in
                    XCTAssertEqual(count, 40)
                    return true
                }
            )
        }

        let sentCount = try await senderEngine.sendFiles(
            fileUrls: fileUrls,
            to: invitation,
            localDeviceName: "TestSender"
        )

        let receivedCount = try await receiveTask.value

        XCTAssertEqual(sentCount, 40)
        XCTAssertEqual(receivedCount, 40)
    }

    // MARK: - Consent Rejection

    func testExplicitConsentRejectionAbortsTransfer() async throws {
        print("DEBUG: [ConsentRejection] 1. Getting TLS identity...")
        let identity = try KeychainIdentityManager.shared.getOrCreateTlsIdentity()
        print("DEBUG: [ConsentRejection] 2. Got identity, starting listener on ephemeral port...")
        let listener = GlideNetworkListener()
        let authority = LanInvitationAuthority()
        let box = ConnectionBox()

        _ = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<UInt16, Error>) in
            do {
                try listener.start(
                    port: 0,
                    tlsIdentity: identity,
                    onReady: { port in
                        print("DEBUG: [ConsentRejection] 3. Listener onReady on port \(port)")
                        continuation.resume(returning: port)
                    },
                    onFailure: { error in
                        print("DEBUG: [ConsentRejection] 3! Listener onFailure: \(error)")
                        continuation.resume(throwing: error)
                    },
                    onConnection: { conn in
                        print("DEBUG: [ConsentRejection] 3* Listener onConnection received!")
                        Task { await box.setConnection(conn) }
                    }
                )
            } catch {
                print("DEBUG: [ConsentRejection] 3! Listener start threw: \(error)")
                continuation.resume(throwing: error)
            }
        }
        defer { listener.stop() }

        print("DEBUG: [ConsentRejection] 4. Publishing invitation...")
        let (invitation, _) = try AppleInvitationManager.publish(
            listener: listener,
            authority: authority,
            deviceName: "TestReceiver",
            platform: "macos"
        )
        print("DEBUG: [ConsentRejection] 5. Published with endpoints: \(invitation.endpoints.map { "\($0.address):\($0.port)" })")

        let testFile = try createTestFile(named: "rejected.txt", size: 1024)

        let senderEngine = GlideTransferEngine(storageDirectory: senderDir)
        let receiverEngine = GlideTransferEngine(storageDirectory: receiverDir)

        let receiveTask = Task<Int, Error> {
            print("DEBUG: [ConsentRejection] 6a. ReceiveTask awaiting box.getConnection()...")
            let conn = try await box.getConnection()
            print("DEBUG: [ConsentRejection] 6b. ReceiveTask got connection, dispatching receiveInboundConnection...")
            return try await receiverEngine.receiveInboundConnection(
                connection: conn,
                authority: authority,
                destinationDirectory: self.destDir,
                localDeviceName: "TestReceiver",
                onConsent: { _, _, _ in
                    print("DEBUG: [ConsentRejection] 6c. onConsent called -> returning FALSE (rejection)")
                    return false // User explicitly refuses offer
                }
            )
        }

        print("DEBUG: [ConsentRejection] 7. Sender calling sendFiles...")
        do {
            _ = try await senderEngine.sendFiles(
                fileUrls: [testFile],
                to: invitation,
                localDeviceName: "TestSender"
            )
            XCTFail("Sender should have thrown offerRejected")
        } catch {
            print("DEBUG: [ConsentRejection] 8. Sender caught error: \(error)")
            guard case TransferError.offerRejected = error else {
                XCTFail("Expected TransferError.offerRejected, got: \(error)")
                return
            }
        }

        print("DEBUG: [ConsentRejection] 9. Awaiting receiveTask.value...")
        let receivedCount = try await receiveTask.value
        print("DEBUG: [ConsentRejection] 10. receiveTask returned count: \(receivedCount)")
        XCTAssertEqual(receivedCount, 0)

        // Ensure rejected file was never committed to destination
        let destFile = destDir.appendingPathComponent("rejected.txt")
        XCTAssertFalse(FileManager.default.fileExists(atPath: destFile.path))
    }

    // MARK: - Batch Limits Enforcement

    func testRejectsBatchExceeding1000Files() async {
        let senderEngine = GlideTransferEngine(storageDirectory: senderDir)
        let dummyUrls = (1...1001).map { URL(fileURLWithPath: "/dummy/file_\($0).txt") }
        let dummyInvitation = LanInvitation(
            invitationId: UUID().uuidString.lowercased(),
            presence: String(repeating: "a", count: 32),
            name: "Dummy",
            platform: "macos",
            endpoints: [LanEndpoint(address: "127.0.0.1", port: 42424)],
            spkiSha256: String(repeating: "f", count: 64),
            token: "dGVzdC10b2tlbg",
            expiresAt: String(Int64(Date().timeIntervalSince1970) + 120),
            ttlSeconds: 120
        )

        do {
            _ = try await senderEngine.sendFiles(fileUrls: dummyUrls, to: dummyInvitation)
            XCTFail("Expected invalidFileCount(1001)")
        } catch {
            guard case TransferError.invalidFileCount(let count) = error else {
                XCTFail("Expected invalidFileCount, got: \(error)")
                return
            }
            XCTAssertEqual(count, 1001)
        }
    }

    func testRejectsEmptyBatch() async {
        let senderEngine = GlideTransferEngine(storageDirectory: senderDir)
        let dummyInvitation = LanInvitation(
            invitationId: UUID().uuidString.lowercased(),
            presence: String(repeating: "a", count: 32),
            name: "Dummy",
            platform: "macos",
            endpoints: [LanEndpoint(address: "127.0.0.1", port: 42424)],
            spkiSha256: String(repeating: "f", count: 64),
            token: "dGVzdC10b2tlbg",
            expiresAt: String(Int64(Date().timeIntervalSince1970) + 120),
            ttlSeconds: 120
        )

        do {
            _ = try await senderEngine.sendFiles(fileUrls: [], to: dummyInvitation)
            XCTFail("Expected invalidFileCount(0)")
        } catch {
            guard case TransferError.invalidFileCount(let count) = error else {
                XCTFail("Expected invalidFileCount, got: \(error)")
                return
            }
            XCTAssertEqual(count, 0)
        }
    }

    // MARK: - Expired Invitation Handling

    func testRejectsExpiredInvitation() async throws {
        let senderEngine = GlideTransferEngine(storageDirectory: senderDir)
        let testFile = try createTestFile(named: "valid.txt", size: 100)

        let expiredInvitation = LanInvitation(
            invitationId: UUID().uuidString.lowercased(),
            presence: String(repeating: "a", count: 32),
            name: "Dummy",
            platform: "macos",
            endpoints: [LanEndpoint(address: "127.0.0.1", port: 42424)],
            spkiSha256: String(repeating: "f", count: 64),
            token: "dGVzdC10b2tlbg",
            expiresAt: String(Int64(Date().timeIntervalSince1970) - 10), // Expired 10s ago
            ttlSeconds: 120
        )

        do {
            _ = try await senderEngine.sendFiles(fileUrls: [testFile], to: expiredInvitation)
            XCTFail("Expected protocolViolation for expired invitation")
        } catch {
            guard case TransferError.protocolViolation(let msg) = error else {
                XCTFail("Expected protocolViolation, got: \(error)")
                return
            }
            XCTAssertTrue(msg.contains("expired"))
        }
    }
}
