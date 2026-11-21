import Foundation
import XCTest
@testable import GlideCoreKit

final class TrustedEndpointResolverTests: XCTestCase {
    private let presenceA = "0123456789abcdef0123456789abcdef"
    private let presenceB = "fedcba9876543210fedcba9876543210"

    private func savedPeer() throws -> PersistentTrustedPeer {
        try PersistentTrustedPeer(
            fingerprintHex: String(repeating: "a", count: 64),
            publicKeyDerHex: "aabb", displayName: "ubuntu")
    }

    func testDiscoveryKeepsDuplicateNamesAsDistinctCandidates() throws {
        let peers = [
            BonjourPeer(name: "ubuntu", endpoint: "192.168.1.20:45001",
                        presenceToken: presenceA, supportsTrustedReconnect: true),
            BonjourPeer(name: "ubuntu", endpoint: "192.168.1.21:45002",
                        presenceToken: presenceB, supportsTrustedReconnect: true),
            BonjourPeer(name: "old", endpoint: "192.168.1.22:42424",
                        presenceToken: presenceA, supportsTrustedReconnect: false)
        ]
        let candidates = TrustedEndpointResolver.candidates(for: try savedPeer(), discovered: peers)
        XCTAssertEqual(candidates.count, 2)
        XCTAssertEqual(candidates.map(\.endpoint.port), [45001, 45002])
        XCTAssertEqual(candidates.map(\.presenceId), [presenceA, presenceB])
    }

    func testManualAddressIsOnlyFallbackAndInvalidAddressIsIgnored() throws {
        let peer = try savedPeer()
        XCTAssertTrue(TrustedEndpointResolver.candidates(
            for: peer, discovered: [], manualAddress: "example.com:0").isEmpty)
        XCTAssertNil(TrustedEndpointResolver.parse("example.com:42424"))
        let candidates = TrustedEndpointResolver.candidates(
            for: peer, discovered: [], manualAddress: "192.168.1.33:42424")
        XCTAssertEqual(candidates.count, 1)
        XCTAssertNil(candidates[0].presenceId)
        XCTAssertEqual(candidates[0].endpoint.address, "192.168.1.33")
    }

    func testCandidateBudgetIsBounded() throws {
        let peers = (1...20).map {
            BonjourPeer(name: "ubuntu", endpoint: "192.168.1.\($0):42424",
                        presenceToken: presenceA, supportsTrustedReconnect: true)
        }
        XCTAssertEqual(TrustedEndpointResolver.candidates(
            for: try savedPeer(), discovered: peers).count,
            TrustedEndpointResolver.maximumCandidates)
    }

    func testCorruptTrustFileCannotBeSilentlyReplaced() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("trusted_peers.json")
        let original = Data("not valid JSON".utf8)
        try original.write(to: file)
        let store = PersistentTrustStore(storageDirectory: directory)
        XCTAssertThrowsError(try store.enrollPeer(
            fingerprintHex: String(repeating: "a", count: 64),
            publicKeyDerHex: "aabb", displayName: "Example"))
        XCTAssertEqual(try Data(contentsOf: file), original)
    }
}
