import XCTest
@testable import GlideCoreKit

final class InvitationCodecTests: XCTestCase {
    private func makeValidInvitation(expired: Bool = false) -> LanInvitation {
        let nowSeconds = Int64(Date().timeIntervalSince1970)
        let expirySeconds = nowSeconds + (expired ? -10 : 120)
        return LanInvitation(
            v: 1,
            transport: "lan-tls",
            profile: 1,
            protocolVersion: 1,
            invitationId: "8a32a67e-071a-4d23-92b4-3ec9fef39db3",
            presence: String(repeating: "a", count: 32),
            name: "MacBook Pro",
            platform: "macos",
            endpoints: [LanEndpoint(address: "192.168.1.100", port: 42424)],
            spkiSha256: String(repeating: "f", count: 64),
            token: InvitationCodec.toUnpaddedBase64Url(Data(repeating: 0x2a, count: 32)),
            expiresAt: String(expirySeconds),
            ttlSeconds: 120
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
        XCTAssertEqual(decoded.transport, "lan-tls")
        XCTAssertFalse(decoded.invitationHash.isEmpty)
        XCTAssertEqual(decoded.invitationHash.count, 64)
    }

    func testInvitationExpiredThrows() throws {
        let expired = makeValidInvitation(expired: true)
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
            presence: String(repeating: "a", count: 32),
            name: "Device",
            platform: "ios",
            endpoints: [LanEndpoint(address: "10.0.0.1", port: 42424)],
            spkiSha256: "tooShort",
            token: "0123456789012345678901234567890123456789012",
            expiresAt: "1727712000000"
        )

        XCTAssertThrowsError(try InvitationCodec.encode(badInvitation)) { error in
            XCTAssertEqual(error as? InvitationError, InvitationError.invalidSpkiHashLength(actual: 8))
        }
    }

    func testInvalidTransportThrows() {
        let badInvitation = LanInvitation(
            transport: "invalid-transport",
            invitationId: "8a32a67e-071a-4d23-92b4-3ec9fef39db3",
            presence: String(repeating: "a", count: 32),
            name: "Device",
            platform: "ios",
            endpoints: [LanEndpoint(address: "10.0.0.1", port: 42424)],
            spkiSha256: String(repeating: "f", count: 64),
            token: "0123456789012345678901234567890123456789012",
            expiresAt: "1727712000000"
        )

        XCTAssertThrowsError(try InvitationCodec.encode(badInvitation)) { error in
            XCTAssertEqual(error as? InvitationError, InvitationError.unsupportedTransport("invalid-transport"))
        }
    }

    func testPublishedInvitationIsOneUseAndRejectsWrongToken() throws {
        let authority = LanInvitationAuthority()
        let invitation = try authority.publish(makeValidInvitation())
        let now = Int64(Date().timeIntervalSince1970)

        XCTAssertEqual(authority.claim(
            invitationId: invitation.invitationId,
            token: String(repeating: "A", count: 43),
            invitationHash: invitation.invitationHash,
            role: "sender", nowEpochSeconds: now), .authenticationFailed)
        XCTAssertEqual(authority.claim(
            invitationId: invitation.invitationId,
            token: invitation.token,
            invitationHash: invitation.invitationHash,
            role: "sender", nowEpochSeconds: now), .accepted)
        XCTAssertEqual(authority.claim(
            invitationId: invitation.invitationId,
            token: invitation.token,
            invitationHash: invitation.invitationHash,
            role: "sender", nowEpochSeconds: now), .used)
    }
}
