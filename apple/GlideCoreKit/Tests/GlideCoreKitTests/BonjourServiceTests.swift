import XCTest
@testable import GlideCoreKit

final class BonjourServiceTests: XCTestCase {
    func testBuildTxtRecordContainsAllCanonicalKeysAndOmitsSpki() {
        let name = "MacBook Pro"
        let presence = "0123456789abcdef0123456789abcdef"

        let record = BonjourService.buildTxtRecord(
            name: name,
            presenceTokenHex: presence,
            deviceType: BonjourService.deviceTypeDesktop,
            isPairReady: true,
            supportsTrustedReconnect: true
        )

        XCTAssertEqual(record["v"], "1")
        XCTAssertEqual(record["tv"], "1")
        XCTAssertEqual(record["q"], "1")
        XCTAssertEqual(record["p"], presence)
        XCTAssertEqual(record["dn"], name)
        XCTAssertEqual(record["dt"], "desktop")
        XCTAssertEqual(record["pair"], "1")
        XCTAssertEqual(record["tr"], "1")
        XCTAssertNil(record["spki"])
    }

    func testParseTxtRecordSucceedsForCanonicalDictionary() throws {
        let name = "iPhone 16"
        let presence = "fedcba9876543210fedcba9876543210"
        let endpoint = "192.168.1.150:42424"

        let dict = [
            "v": "1",
            "tv": "1",
            "q": "1",
            "p": presence,
            "dn": name,
            "dt": "phone",
            "pair": "1",
            "tr": "1"
        ]

        let peer = try BonjourService.parseTxtRecord(dict, endpoint: endpoint)

        XCTAssertEqual(peer.name, name)
        XCTAssertEqual(peer.endpoint, endpoint)
        XCTAssertEqual(peer.presenceToken, presence)
        XCTAssertEqual(peer.deviceType, "phone")
        XCTAssertEqual(peer.protocolVersion, 1)
        XCTAssertEqual(peer.recordProfileVersion, 1)
        XCTAssertTrue(peer.isPairReady)
        XCTAssertTrue(peer.supportsTrustedReconnect)
    }

    func testParseTxtRecordThrowsOnMissingKey() {
        let dict = [
            "v": "1",
            "tv": "1",
            "q": "1",
            "dn": "iPad Pro"
        ]

        XCTAssertThrowsError(try BonjourService.parseTxtRecord(dict, endpoint: "10.0.0.2:42424")) { error in
            XCTAssertEqual(error as? BonjourService.BonjourError, BonjourService.BonjourError.missingKey("p"))
        }
    }

    func testParseTxtRecordRejectsForbiddenKeys() {
        let dict = [
            "v": "1",
            "tv": "1",
            "q": "1",
            "p": "0123456789abcdef0123456789abcdef",
            "dn": "iPad Pro",
            "dt": "tablet",
            "spki": String(repeating: "a", count: 64)
        ]

        XCTAssertThrowsError(try BonjourService.parseTxtRecord(dict, endpoint: "10.0.0.2:42424")) { error in
            XCTAssertEqual(error as? BonjourService.BonjourError, BonjourService.BonjourError.forbiddenKey("spki"))
        }
    }
}
