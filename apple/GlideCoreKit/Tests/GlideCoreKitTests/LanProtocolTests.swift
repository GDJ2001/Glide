import XCTest
@testable import GlideCoreKit

final class LanProtocolTests: XCTestCase {
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

    // MARK: - Envelope Encoding & Decoding

    func testEnvelopeEncodeDecodeRoundTrip() throws {
        let sessionId = UUID().uuidString.lowercased()
        let messageId = UUID().uuidString.lowercased()
        let envelope = try LanEnvelope(
            v: 1,
            type: LanMessageTypes.hello,
            sessionId: sessionId,
            messageId: messageId,
            seq: 1,
            body: [
                "displayName": .string("MacBook Air"),
                "supportedMajors": .array([.int(1)]),
                "capabilities": .array([.string("stream-v1"), .string("sha256")])
            ],
            extensions: [
                "customExt": .string("test-value")
            ]
        )

        let encoded = try LanProtocolCodec.encode(envelope)
        XCTAssertLessThanOrEqual(encoded.count, LanProtocolCodec.maxControlBytes)

        let decoded = try LanProtocolCodec.decode(encoded)
        XCTAssertEqual(decoded.v, 1)
        XCTAssertEqual(decoded.type, LanMessageTypes.hello)
        XCTAssertEqual(decoded.sessionId, sessionId)
        XCTAssertEqual(decoded.messageId, messageId)
        XCTAssertEqual(decoded.seq, 1)
        XCTAssertEqual(decoded.body["displayName"]?.stringValue, "MacBook Air")
        XCTAssertEqual(decoded.body["supportedMajors"]?.arrayValue?.first?.intValue, 1)
        XCTAssertEqual(decoded.extensions?["customExt"]?.stringValue, "test-value")
    }

    func testProhibitsUtf8BOM() throws {
        let validJson = """
        {"v":1,"type":"HELLO","sessionId":"\(UUID().uuidString.lowercased())","messageId":"\(UUID().uuidString.lowercased())","seq":1,"body":{}}
        """.data(using: .utf8)!

        var bomData = Data([0xEF, 0xBB, 0xBF])
        bomData.append(validJson)

        XCTAssertThrowsError(try LanProtocolCodec.decode(bomData)) { error in
            guard case LanProtocolError.malformedMessage(let msg) = error else {
                XCTFail("Expected malformedMessage with UTF-8 BOM, got: \(error)")
                return
            }
            XCTAssertTrue(msg.contains("BOM"))
        }
    }

    func testEnforces16KiBMaxControlBytes() throws {
        let hugePayload = Data(repeating: 0x41, count: LanProtocolCodec.maxControlBytes + 1)
        XCTAssertThrowsError(try LanProtocolCodec.decode(hugePayload)) { error in
            guard case LanProtocolError.limitExceeded = error else {
                XCTFail("Expected limitExceeded, got: \(error)")
                return
            }
        }
    }

    func testUnknownMessageTypeThrows() {
        let sessionId = UUID().uuidString.lowercased()
        XCTAssertThrowsError(try LanEnvelope(
            v: 1,
            type: "UNSUPPORTED_TYPE",
            sessionId: sessionId,
            seq: 1,
            body: [:]
        )) { error in
            guard case LanProtocolError.unsupportedProtocol = error else {
                XCTFail("Expected unsupportedProtocol, got: \(error)")
                return
            }
        }
    }

    func testInvalidSessionIdThrows() {
        XCTAssertThrowsError(try LanEnvelope(
            v: 1,
            type: LanMessageTypes.hello,
            sessionId: "not-a-valid-uuid",
            seq: 1,
            body: [:]
        )) { error in
            guard case LanProtocolError.invalidSessionId = error else {
                XCTFail("Expected invalidSessionId, got: \(error)")
                return
            }
        }
    }

    func testInvalidSeqThrows() {
        let sessionId = UUID().uuidString.lowercased()
        XCTAssertThrowsError(try LanEnvelope(
            v: 1,
            type: LanMessageTypes.hello,
            sessionId: sessionId,
            seq: 0,
            body: [:]
        )) { error in
            guard case LanProtocolError.invalidSeq = error else {
                XCTFail("Expected invalidSeq, got: \(error)")
                return
            }
        }
    }

    // MARK: - LanControlSequence Tests

    func testLanControlSequenceMonotonicOutbound() async throws {
        let sequence = LanControlSequence()
        let sessionId = UUID().uuidString.lowercased()

        let rec1 = try await sequence.createRecord(type: LanMessageTypes.hello, sessionId: sessionId, body: [:])
        let rec2 = try await sequence.createRecord(type: LanMessageTypes.ping, sessionId: sessionId, body: [:])

        guard case .control(let p1) = rec1, case .control(let p2) = rec2 else {
            XCTFail("Expected control records")
            return
        }

        let env1 = try LanProtocolCodec.decode(p1)
        let env2 = try LanProtocolCodec.decode(p2)

        XCTAssertEqual(env1.seq, 1)
        XCTAssertEqual(env2.seq, 2)
    }

    // MARK: - PersistentTrustStore Tests

    func testPersistentTrustStoreEnrollQueryRevoke() throws {
        let store = PersistentTrustStore(storageDirectory: tempDir)
        let fp = String(repeating: "a", count: 64)
        let pubDer = "3059301306072a8648ce3d020106082a8648ce3d03010703420004"

        XCTAssertFalse(store.isTrusted(fingerprintHex: fp))

        try store.enrollPeer(fingerprintHex: fp, publicKeyDerHex: pubDer, displayName: "Test Linux Host")
        XCTAssertTrue(store.isTrusted(fingerprintHex: fp))

        let peer = store.getPeer(fingerprintHex: fp)
        XCTAssertNotNil(peer)
        XCTAssertEqual(peer?.displayName, "Test Linux Host")
        XCTAssertEqual(peer?.publicKeyDerHex, pubDer)

        // Verify file permissions (0o600)
        let fileUrl = tempDir.appendingPathComponent("trusted_peers.json")
        XCTAssertTrue(FileManager.default.fileExists(atPath: fileUrl.path))
        let attrs = try FileManager.default.attributesOfItem(atPath: fileUrl.path)
        let perms = (attrs[.posixPermissions] as? NSNumber)?.intValue ?? 0
        XCTAssertEqual(perms & 0o777, 0o600)

        // Reload store from disk
        let reloaded = PersistentTrustStore(storageDirectory: tempDir)
        XCTAssertTrue(reloaded.isTrusted(fingerprintHex: fp))

        // Revoke
        try reloaded.revokePeer(fingerprintHex: fp)
        XCTAssertFalse(reloaded.isTrusted(fingerprintHex: fp))
    }

    func testPersistentTrustStoreRejectsInvalidFingerprint() {
        let store = PersistentTrustStore(storageDirectory: tempDir)
        XCTAssertThrowsError(try store.enrollPeer(
            fingerprintHex: "too-short",
            publicKeyDerHex: "aabb",
            displayName: "Invalid"
        ))
    }

    // MARK: - PersistentHistoryStore Tests

    func testPersistentHistoryStoreAddQueryCap() throws {
        let store = PersistentHistoryStore(storageDirectory: tempDir)
        XCTAssertTrue(store.allEntries().isEmpty)

        let entry1 = PersistentHistoryEntry(
            direction: "receive",
            peerName: "Windows Companion",
            fileCount: 3,
            totalBytes: 1048576,
            status: "completed",
            destinationPath: "/Downloads"
        )
        try store.addEntry(entry1)

        let entries = store.allEntries()
        XCTAssertEqual(entries.count, 1)
        XCTAssertEqual(entries[0].peerName, "Windows Companion")
        XCTAssertEqual(entries[0].fileCount, 3)

        // Verify file permissions (0o600)
        let fileUrl = tempDir.appendingPathComponent("history.json")
        XCTAssertTrue(FileManager.default.fileExists(atPath: fileUrl.path))
        let attrs = try FileManager.default.attributesOfItem(atPath: fileUrl.path)
        let perms = (attrs[.posixPermissions] as? NSNumber)?.intValue ?? 0
        XCTAssertEqual(perms & 0o777, 0o600)

        // Reload from disk
        let reloaded = PersistentHistoryStore(storageDirectory: tempDir)
        XCTAssertEqual(reloaded.allEntries().count, 1)

        try reloaded.clearAll()
        XCTAssertTrue(reloaded.allEntries().isEmpty)
    }
}
