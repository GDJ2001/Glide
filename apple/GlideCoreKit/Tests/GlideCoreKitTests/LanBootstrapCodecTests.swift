import XCTest
@testable import GlideCoreKit

final class LanBootstrapCodecTests: XCTestCase {
    private func message() throws -> LanBootstrapMessage {
        try LanBootstrapMessage(
            invitationId: "8a32a67e-071a-4d23-92b4-3ec9fef39db3",
            token: InvitationCodec.toUnpaddedBase64Url(Data(repeating: 0x44, count: 32)),
            invitationHash: String(repeating: "a", count: 64),
            sessionId: "00cd3e5b-e285-4780-8559-61af174e3d9c"
        )
    }

    func testBootstrapRoundTripAndRedaction() throws {
        let original = try message()
        let decoded = try LanBootstrapCodec.decodeBootstrap(LanBootstrapCodec.encode(original))
        XCTAssertEqual(decoded, original)
        XCTAssertFalse(original.description.contains(original.token))
    }

    func testResultRoundTrip() throws {
        let accepted = try LanBootstrapResult.accepted(sessionId: message().sessionId)
        XCTAssertEqual(try LanBootstrapCodec.decodeResult(LanBootstrapCodec.encode(accepted)), accepted)
        let rejected = try LanBootstrapResult.rejected(code: "INVITATION_USED")
        XCTAssertEqual(try LanBootstrapCodec.decodeResult(LanBootstrapCodec.encode(rejected)), rejected)
    }

    func testDuplicateAndUnknownFieldsFailClosed() throws {
        let valid = String(decoding: try LanBootstrapCodec.encode(message()), as: UTF8.self)
        let duplicate = valid.replacingOccurrences(of: "\"role\":\"sender\"",
                                                     with: "\"role\":\"sender\",\"role\":\"sender\"")
        XCTAssertNotEqual(duplicate, valid)
        XCTAssertThrowsError(try LanBootstrapCodec.decodeBootstrap(Data(duplicate.utf8)))
        let unknown = valid.dropLast() + ",\"extra\":true}"
        XCTAssertThrowsError(try LanBootstrapCodec.decodeBootstrap(Data(unknown.utf8)))
    }
}
