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

    func testPairV2InvitationRoundTripAndV1Rejection() throws {
        let nowSeconds = Int64(Date().timeIntervalSince1970)
        let v2 = LanPairV2Invitation(
            invitationId: "660e8400-e29b-41d4-a716-446655440001",
            presence: "fedcba9876543210fedcba9876543210",
            name: "Office Linux Workstation",
            platform: "linux",
            endpoints: [LanEndpoint(address: "192.168.1.25", port: 42424)],
            spkiSha256: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            token: InvitationCodec.toUnpaddedBase64Url(Data(repeating: 0x11, count: 32)),
            expiresAt: String(nowSeconds + 120),
            ttlSeconds: 120
        )

        let uri = try InvitationCodec.encodePairV2(v2)
        XCTAssertTrue(uri.hasPrefix("glide://pair/v2#"))

        // V1 decode must reject v2 URI with explicit "Update Glide to pair this device"
        XCTAssertThrowsError(try InvitationCodec.decode(uri)) { error in
            XCTAssertEqual(error as? InvitationError, InvitationError.unsupportedVersion("Update Glide to pair this device"))
        }

        let decoded = try InvitationCodec.decodePairV2(uri)
        XCTAssertEqual(decoded.v, 2)
        XCTAssertEqual(decoded.purpose, "pair-only")
        XCTAssertEqual(decoded.profile, 2)
        XCTAssertEqual(decoded.invitationId, v2.invitationId)

        let anyDecoded = try InvitationCodec.decodeAny(uri)
        XCTAssertEqual(anyDecoded, .pairOnlyV2(decoded))
    }

    func testGlideSpake2SessionAndPairV2Transcript() throws {
        XCTAssertEqual(GlideSpake2Session.boringSslCommit, GlideSpake2Session.pinnedBoringSslCommit)

        let alice = try GlideSpake2Session(
            role: .alice,
            myName: Data("glide-spake2-initiator-v1".utf8),
            theirName: Data("glide-spake2-responder-v1".utf8)
        )
        let bob = try GlideSpake2Session(
            role: .bob,
            myName: Data("glide-spake2-responder-v1".utf8),
            theirName: Data("glide-spake2-initiator-v1".utf8)
        )

        let pin = Data("042857".utf8)
        let msgA = try alice.generateMessage(password: pin)
        let msgB = try bob.generateMessage(password: pin)
        let secretA = try alice.processPeerMessage(msgB)
        let secretB = try bob.processPeerMessage(msgA)
        XCTAssertEqual(secretA.count, 64)
        XCTAssertEqual(secretA, secretB)

        let transcript = GlideSpake2Session.buildPairV2TranscriptBytes(
            method: "spake2-pin-v1",
            sessionId: "770e8400-e29b-41d4-a716-446655440002",
            initiatorNonceHex: "1111111111111111111111111111111111111111111111111111111111111111",
            responderNonceHex: "2222222222222222222222222222222222222222222222222222222222222222",
            initiatorFingerprintHex: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            initiatorPublicKeyDerHex: "3059301306072a8648ce3d020106082a8648ce3d0301070342000411",
            responderFingerprintHex: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            responderPublicKeyDerHex: "3059301306072a8648ce3d020106082a8648ce3d0301070342000422",
            responderPresenceHex: "fedcba9876543210fedcba9876543210",
            tlsServerSpkiSha256Hex: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            purpose: "PAIR_ONLY"
        )
        XCTAssertEqual(transcript.count, 606)
    }
}

