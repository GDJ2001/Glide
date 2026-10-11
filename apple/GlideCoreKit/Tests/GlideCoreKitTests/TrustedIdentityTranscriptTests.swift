import Foundation
import CryptoKit
import XCTest
@testable import GlideCoreKit

final class TrustedIdentityTranscriptTests: XCTestCase {
    func testSharedIdentityV1TranscriptMatchesDesktopAndAndroid() throws {
        var folder = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        var fixture: URL?
        for _ in 0..<8 {
            let candidate = folder.appendingPathComponent("assets/protocol/fixtures/identity_resume_v1_vectors.json")
            if FileManager.default.fileExists(atPath: candidate.path) { fixture = candidate; break }
            folder.deleteLastPathComponent()
        }
        let data = try Data(contentsOf: XCTUnwrap(fixture))
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let cap = try XCTUnwrap(root["capabilityVector"] as? [String: Any])
        let capabilities = try XCTUnwrap(cap["capabilities"] as? [String])
        XCTAssertEqual(try AppleTrustedSessionAuthenticator.capabilityHash(capabilities),
                       cap["sha256Hex"] as? String)

        let vector = try XCTUnwrap(root["identityVector"] as? [String: String])
        func value(_ name: String) throws -> String { try XCTUnwrap(vector[name]) }
        let proof = try AppleTrustedSessionAuthenticator.identityProofBytes(
            sessionId: value("activeSessionId"),
            challengeMessageId: value("challengeMessageId"),
            signerRole: value("signerRole"), verifierRole: value("verifierRole"),
            signerFp: value("signerFingerprintHex"),
            verifierFp: value("verifierFingerprintHex"),
            challengeNonce: value("challengeNonceHex"),
            binding: value("channelBindingHex"), capHash: value("capabilityHashHex"))
        let hex = proof.map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(hex, vector["transcriptHex"])
        let digest = Data(SHA256.hash(data: proof)).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(digest, vector["transcriptSha256Hex"])
    }
}
