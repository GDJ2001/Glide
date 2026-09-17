import XCTest
@testable import GlideCoreKit

final class InvitationCodecTests: XCTestCase {
    private func makeValidInvitation(ttlSeconds: Int = 120) -> LanInvitation {
        LanInvitation(
            v: 1,
            transport: "lan",
            profile: 1,
            protocolVersion: 1,
            invitationId: "8a32a67e-071a-4d23-92b4-3ec9fef39db3",
            presence: "abc123ff",
            name: "MacBook Pro",
            platform: "macos",
            endpoints: [LanEndpoint(address: "192.168.1.100", port: 42424)],
            spkiSha256: String(repeating: "f", count: 64),
            token: "validAuthToken456",
            expiresAt: Int64(Date().timeIntervalSince1970) + Int64(ttlSeconds),
            ttlSeconds: ttlSeconds
        )
    }

    func testInvitationEncodeDecodeRoundTrip() throws {
        let invitation = makeValidInvitation()
        let uri = try InvitationCodec.encode(invitation)

        XCTAssertTrue(uri.hasPrefix("glide://pair/v1#"))
        XCTAssertLessThanOrEqual(uri.count, InvitationCodec.maxUriChars)

        let decoded = try InvitationCodec.decode(uri)
        XCTAssertEqual(decoded.invitationId, invitation.invitationId)
        XCTAssertEqual(decoded.name, invitation.name)
        XCTAssertEqual(decoded.platform, "macos")
        XCTAssertEqual(decoded.spkiSha256, invitation.spkiSha256)
        XCTAssertEqual(decoded.endpoints, invitation.endpoints)
    }

    func testInvitationExpiredThrows() throws {
        let expired = makeValidInvitation(ttlSeconds: -10)
        let uri = try InvitationCodec.encode(expired)

        XCTAssertThrowsError(try InvitationCodec.decode(uri)) { error in
            XCTAssertEqual(error as? InvitationError, InvitationError.invitationExpired)
        }
    }

    func testInvalidPrefixThrows() {
        let badUri = "glide://invalid/v1#abc"
        XCTAssertThrowsError(try InvitationCodec.decode(badUri)) { error in
            XCTAssertEqual(error as? InvitationError, InvitationError.invalidUriPrefix)
        }
    }

    func testInvalidSpkiLengthThrows() {
        let badInvitation = LanInvitation(
            invitationId: "8a32a67e-071a-4d23-92b4-3ec9fef39db3",
            presence: "presence",
            name: "Device",
            platform: "ios",
            endpoints: [LanEndpoint(address: "10.0.0.1", port: 42424)],
            spkiSha256: "tooShort",
            token: "token",
            expiresAt: Int64(Date().timeIntervalSince1970) + 120
        )

        XCTAssertThrowsError(try InvitationCodec.encode(badInvitation)) { error in
            XCTAssertEqual(error as? InvitationError, InvitationError.invalidSpkiHashLength(actual: 8))
        }
    }
}
