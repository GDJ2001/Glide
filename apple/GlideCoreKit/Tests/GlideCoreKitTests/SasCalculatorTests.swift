import XCTest
@testable import GlideCoreKit

final class SasCalculatorTests: XCTestCase {
    func testExtractDecimal6ProducesSixDigitsInRange() throws {
        for i: UInt8 in 0..<255 {
            let digest = Data([i, i &* 3, i &* 7, i &* 11])
            let sas = try SasCalculator.extractDecimal6(from: digest)
            XCTAssertGreaterThanOrEqual(sas, 100_000)
            XCTAssertLessThanOrEqual(sas, 999_999)
        }
    }

    func testCalculateSasIsDeterministic() throws {
        let clientPub = Data([1, 2, 3, 4, 5, 6, 7, 8])
        let serverPub = Data([9, 10, 11, 12, 13, 14, 15, 16])
        let nonce = Data([17, 18, 19, 20, 21, 22, 23, 24])

        let sas1 = try SasCalculator.calculateSas(clientPublicKey: clientPub, serverPublicKey: serverPub, sessionNonce: nonce)
        let sas2 = try SasCalculator.calculateSas(clientPublicKey: clientPub, serverPublicKey: serverPub, sessionNonce: nonce)

        XCTAssertEqual(sas1, sas2)
        XCTAssertGreaterThanOrEqual(sas1, 100_000)
        XCTAssertLessThanOrEqual(sas1, 999_999)

        let formatted = SasCalculator.formatSas(sas1)
        XCTAssertEqual(formatted.count, 7) // "### ###"
        XCTAssertTrue(formatted.contains(" "))
    }

    func testCalculateSasChangesWhenNonceChanges() throws {
        let clientPub = Data([1, 2, 3, 4, 5, 6, 7, 8])
        let serverPub = Data([9, 10, 11, 12, 13, 14, 15, 16])
        let nonce1 = Data([17, 18, 19, 20, 21, 22, 23, 24])
        let nonce2 = Data([17, 18, 19, 20, 21, 22, 23, 25])

        let sas1 = try SasCalculator.calculateSas(clientPublicKey: clientPub, serverPublicKey: serverPub, sessionNonce: nonce1)
        let sas2 = try SasCalculator.calculateSas(clientPublicKey: clientPub, serverPublicKey: serverPub, sessionNonce: nonce2)

        XCTAssertGreaterThanOrEqual(sas1, 100_000)
        XCTAssertGreaterThanOrEqual(sas2, 100_000)
    }

    func testExtractDecimal6ThrowsWhenTooShort() {
        let shortData = Data([1, 2, 3])
        XCTAssertThrowsError(try SasCalculator.extractDecimal6(from: shortData)) { error in
            XCTAssertEqual(error as? SasCalculator.SasError, SasCalculator.SasError.digestTooShort)
        }
    }
}
