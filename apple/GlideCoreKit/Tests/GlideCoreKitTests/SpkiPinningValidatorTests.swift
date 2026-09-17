import XCTest
import CryptoKit
@testable import GlideCoreKit

final class SpkiPinningValidatorTests: XCTestCase {
    func testComputeSpkiSha256HexMatchesExpected() throws {
        let dummySpki = "Glide Test Public Key Data".data(using: .utf8)!
        let expectedDigest = SHA256.hash(data: dummySpki).map { String(format: "%02x", $0) }.joined()

        let computed = try SpkiPinningValidator.computeSpkiSha256Hex(from: dummySpki)
        XCTAssertEqual(computed, expectedDigest)
        XCTAssertEqual(computed.count, 64)
    }

    func testVerifySucceedsForMatchingHash() throws {
        let spkiData = Data([0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08])
        let expectedHash = try SpkiPinningValidator.computeSpkiSha256Hex(from: spkiData)

        let isValid = try SpkiPinningValidator.verify(spkiData: spkiData, expectedSha256Hex: expectedHash)
        XCTAssertTrue(isValid)

        // Case-insensitivity check
        let upperHash = expectedHash.uppercased()
        let isUpperValid = try SpkiPinningValidator.verify(spkiData: spkiData, expectedSha256Hex: upperHash)
        XCTAssertTrue(isUpperValid)
    }

    func testVerifyFailsForMismatchedHash() throws {
        let spkiData = Data([0x01, 0x02, 0x03, 0x04])
        let wrongHash = String(repeating: "0", count: 64)

        let isValid = try SpkiPinningValidator.verify(spkiData: spkiData, expectedSha256Hex: wrongHash)
        XCTAssertFalse(isValid)
    }

    func testVerifyThrowsOnInvalidHashLength() {
        let spkiData = Data([0x01, 0x02])
        XCTAssertThrowsError(try SpkiPinningValidator.verify(spkiData: spkiData, expectedSha256Hex: "abcd")) { error in
            XCTAssertEqual(error as? SpkiPinningValidator.PinningError, SpkiPinningValidator.PinningError.invalidExpectedHashLength(actual: 4))
        }
    }

    func testVerifyThrowsOnMalformedHex() {
        let spkiData = Data([0x01, 0x02])
        let nonHex = String(repeating: "z", count: 64)
        XCTAssertThrowsError(try SpkiPinningValidator.verify(spkiData: spkiData, expectedSha256Hex: nonHex)) { error in
            XCTAssertEqual(error as? SpkiPinningValidator.PinningError, SpkiPinningValidator.PinningError.malformedHexHash)
        }
    }
}
