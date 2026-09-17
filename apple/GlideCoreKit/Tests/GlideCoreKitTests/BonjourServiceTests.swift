import XCTest
@testable import GlideCoreKit

final class BonjourServiceTests: XCTestCase {
    func testBuildTxtRecordContainsAllStandardKeys() {
        let name = "MacBook Pro"
        let spki = String(repeating: "a", count: 64)
        let presence = "12345678"

        let record = BonjourService.buildTxtRecord(
            name: name,
            spkiSha256Hex: spki,
            presenceTokenHex: presence,
            version: 1
        )

        XCTAssertEqual(record["v"], "1")
        XCTAssertEqual(record["name"], name)
        XCTAssertEqual(record["spki"], spki)
        XCTAssertEqual(record["presence"], presence)
    }

    func testParseTxtRecordSucceedsForValidDictionary() throws {
        let name = "iPhone 16"
        let spki = String(repeating: "b", count: 64)
        let presence = "deadbeef"
        let endpoint = "192.168.1.150:42424"

        let dict = [
            "v": "1",
            "name": name,
            "spki": spki,
            "presence": presence
        ]

        let peer = try BonjourService.parseTxtRecord(dict, endpoint: endpoint)

        XCTAssertEqual(peer.name, name)
        XCTAssertEqual(peer.endpoint, endpoint)
        XCTAssertEqual(peer.spkiSha256, spki)
        XCTAssertEqual(peer.presenceToken, presence)
        XCTAssertEqual(peer.protocolVersion, 1)
    }

    func testParseTxtRecordThrowsOnMissingKey() {
        let dict = [
            "v": "1",
            "name": "iPad Pro"
            // Missing spki and presence
        ]

        XCTAssertThrowsError(try BonjourService.parseTxtRecord(dict, endpoint: "10.0.0.2:42424")) { error in
            XCTAssertEqual(error as? BonjourService.BonjourError, BonjourService.BonjourError.missingKey("spki"))
        }
    }

    func testParseTxtRecordThrowsOnInvalidSpkiLength() {
        let dict = [
            "v": "1",
            "name": "iPad Pro",
            "spki": "short",
            "presence": "abcd"
        ]

        XCTAssertThrowsError(try BonjourService.parseTxtRecord(dict, endpoint: "10.0.0.2:42424")) { error in
            XCTAssertEqual(error as? BonjourService.BonjourError, BonjourService.BonjourError.invalidSpkiLength(actual: 5))
        }
    }
}
