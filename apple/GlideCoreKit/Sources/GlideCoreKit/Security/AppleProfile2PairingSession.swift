import Foundation
import CryptoKit

public enum ApplePairSessionError: Error, LocalizedError, Sendable {
    case expired
    case busy
    case declined
    case wrongCode
    case identityChanged
    case invalidMessage
    case unreachable

    public var errorDescription: String? {
        switch self {
        case .expired: return "Code expired — get a new code"
        case .busy: return "This device is busy — try again"
        case .declined: return "Pairing declined"
        case .wrongCode: return "Incorrect code — check the other screen and try again"
        case .identityChanged: return "Identity changed — pair again"
        case .invalidMessage: return "Pairing failed — try again"
        case .unreachable: return "Couldn’t reach this device — check the local network"
        }
    }
}

/// Profile 2 pair-only sessions carry no file records. A peer is enrolled only
/// after its signature, key confirmation, and both user decisions are verified.
public enum AppleProfile2PairingSession {
    public typealias Approval = @Sendable (String) async -> Bool

    public static func initiateQr(
        invitation: LanPairV2Invitation,
        localDeviceName: String,
        platform: String,
        trustStore: PersistentTrustStore,
        approve: @escaping Approval
    ) async throws -> PersistentTrustedPeer {
        guard !invitation.isExpired(), let endpoint = invitation.endpoints.first,
              let port = UInt16(exactly: endpoint.port) else { throw ApplePairSessionError.expired }
        let channel = try await GlideNetworkClient().connect(
            host: endpoint.address, port: port,
            expectedSpkiSha256Hex: invitation.spkiSha256, timeoutSeconds: 10)
        defer { Task { await channel.close() } }
        guard channel.remoteSpkiSha256Hex == invitation.spkiSha256 else {
            throw ApplePairSessionError.identityChanged
        }
        return try await initiate(
            channel: channel, method: Profile2Constants.authMethodQrTokenV2,
            invitation: invitation, pin: nil,
            expectedPresence: invitation.presence, localDeviceName: localDeviceName,
            platform: platform, trustStore: trustStore, approve: approve)
    }

    public static func initiatePin(
        host: String, port: UInt16, expectedPresence: String?, pin: String,
        localDeviceName: String, platform: String,
        trustStore: PersistentTrustStore, approve: @escaping Approval
    ) async throws -> PersistentTrustedPeer {
        guard pin.utf8.count == 6 && pin.utf8.allSatisfy({ $0 >= 48 && $0 <= 57 }) else {
            throw ApplePairSessionError.wrongCode
        }
        let channel = try await GlideNetworkClient().connect(
            host: host, port: port, timeoutSeconds: 10)
        defer { Task { await channel.close() } }
        guard channel.remoteSpkiSha256Hex != nil else { throw ApplePairSessionError.identityChanged }
        return try await initiate(
            channel: channel, method: Profile2Constants.authMethodSpake2PinV1,
            invitation: nil, pin: pin, expectedPresence: expectedPresence,
            localDeviceName: localDeviceName, platform: platform,
            trustStore: trustStore, approve: approve)
    }

    private static func initiate(
        channel: LanRecordChannel, method: String, invitation: LanPairV2Invitation?,
        pin: String?, expectedPresence: String?, localDeviceName: String,
        platform: String, trustStore: PersistentTrustStore,
        approve: @escaping Approval
    ) async throws -> PersistentTrustedPeer {
        let sessionId = UUID().uuidString.lowercased()
        let initiatorNonce = randomBytes(32)
        let initiatorSpki = try KeychainIdentityManager.shared.getPublicKeySpkiData()
        let open = Profile2SessionOpen(
            sessionId: sessionId, purpose: Profile2Constants.purposePairOnly,
            authMethod: method, initiatorNonce: b64(initiatorNonce),
            initiatorDeviceName: localDeviceName, initiatorPlatform: platform,
            initiatorIdentityPublicKey: b64(initiatorSpki),
            expectedPresenceId: expectedPresence, invitationId: invitation?.invitationId)
        try await channel.writeGld2Preamble()
        try await channel.writeProfile2Message(open)
        let ack: Profile2SessionOpenAck = try await read(channel)
        guard ack.v == Profile2Constants.wireVersion, ack.type == "SESSION_OPEN_ACK",
              ack.sessionId == sessionId else { throw ApplePairSessionError.invalidMessage }
        guard ack.accepted else { throw mapError(ack.errorCode) }
        guard let nonceText = ack.responderNonce, let responderNonce = unb64(nonceText),
              responderNonce.count == 32, let keyText = ack.responderIdentityPublicKey,
              let responderSpki = unb64(keyText),
              let serverSpki = channel.remoteSpkiSha256Hex else {
            throw ApplePairSessionError.invalidMessage
        }
        if let expectedPresence, ack.advertisedPresenceId != expectedPresence {
            throw ApplePairSessionError.expired
        }
        let responderName = ack.responderDeviceName ?? "Glide device"
        let presence = ack.advertisedPresenceId ?? String(repeating: "0", count: 32)
        var sharedSecret: Data
        if method == Profile2Constants.authMethodSpake2PinV1 {
            guard let pin else { throw ApplePairSessionError.wrongCode }
            let names = GlideSpake2Session.buildRoleIdentifiers(
                sessionId: sessionId, initiatorNonce32: initiatorNonce,
                responderNonce32: responderNonce)
            let spake = try GlideSpake2Session(role: .alice,
                                               myName: names.aliceName, theirName: names.bobName)
            let publicValue = try spake.generateMessage(password: Data(pin.utf8))
            try await channel.writeProfile2Message(Profile2PairMsg(
                sessionId: sessionId, role: Profile2Constants.roleInitiator,
                spakePublicValue: b64(publicValue)))
            let peer: Profile2PairMsg = try await readPairMsg(
                channel, sessionId: sessionId, expectedRole: Profile2Constants.roleResponder)
            guard let value = peer.spakePublicValue, let peerValue = unb64(value) else {
                throw ApplePairSessionError.invalidMessage
            }
            sharedSecret = try spake.processPeerMessage(peerValue)
        } else {
            guard let invitation else { throw ApplePairSessionError.invalidMessage }
            try await channel.writeProfile2Message(Profile2PairMsg(
                sessionId: sessionId, role: Profile2Constants.roleInitiator,
                invitationHash: invitation.invitationHash))
            let peer: Profile2PairMsg = try await readPairMsg(
                channel, sessionId: sessionId, expectedRole: Profile2Constants.roleResponder)
            guard peer.invitationHash == invitation.invitationHash else {
                throw ApplePairSessionError.expired
            }
            sharedSecret = try GlideSpake2Session.deriveQrSharedSecret(
                tokenBase64Url: invitation.token, invitationHashHex: invitation.invitationHash)
        }
        defer { sharedSecret.resetBytes(in: 0..<sharedSecret.count) }
        let transcript = transcriptBytes(
            method: method, sessionId: sessionId, initiatorNonce: initiatorNonce,
            responderNonce: responderNonce, initiatorSpki: initiatorSpki,
            responderSpki: responderSpki, presence: presence, serverSpki: serverSpki)
        let hash = Data(SHA256.hash(data: transcript))
        let keys = GlideSpake2Session.deriveConfirmationKeys(
            sharedSecret: sharedSecret, transcriptHash32: hash)
        let myMac = GlideSpake2Session.computeConfirmationMac(
            key: keys.confirmI2RKey, transcriptHash32: hash)
        let mySig = try KeychainIdentityManager.shared.sign(data: transcript)
        try await channel.writeProfile2Message(Profile2PairConfirm(
            sessionId: sessionId, role: Profile2Constants.roleInitiator,
            keyConfirmationMac: b64(myMac), identitySignature: b64(mySig)))
        let peerConfirm: Profile2PairConfirm = try await read(channel)
        guard peerConfirm.v == 1, peerConfirm.type == "PAIR_CONFIRM",
              peerConfirm.sessionId == sessionId,
              peerConfirm.role == Profile2Constants.roleResponder,
              let peerMac = unb64(peerConfirm.keyConfirmationMac),
              HMAC<SHA256>.isValidAuthenticationCode(
                peerMac, authenticating: hash, using: keys.confirmR2IKey),
              let peerSig = unb64(peerConfirm.identitySignature),
              verifySignature(spki: responderSpki, data: transcript, signature: peerSig) else {
            throw ApplePairSessionError.wrongCode
        }
        let approved = await approve(responderName)
        try await channel.writeProfile2Message(Profile2PairApproval(
            sessionId: sessionId, role: Profile2Constants.roleInitiator, approved: approved))
        let peerApproval: Profile2PairApproval = try await read(channel)
        guard peerApproval.v == 1, peerApproval.type == "PAIR_APPROVAL",
              peerApproval.sessionId == sessionId,
              peerApproval.role == Profile2Constants.roleResponder else {
            throw ApplePairSessionError.invalidMessage
        }
        let result: Profile2PairResult = try await read(channel)
        guard result.v == 1, result.type == "PAIR_RESULT", result.sessionId == sessionId else {
            throw ApplePairSessionError.invalidMessage
        }
        guard approved && peerApproval.approved && result.paired else {
            throw mapError(result.errorCode)
        }
        let fingerprint = sha256Hex(responderSpki)
        try trustStore.enrollPeer(
            fingerprintHex: fingerprint, publicKeyDerHex: hex(responderSpki),
            displayName: responderName)
        guard let peer = trustStore.getPeer(fingerprintHex: fingerprint) else {
            throw ApplePairSessionError.invalidMessage
        }
        return peer
    }

    public static func respond(
        channel: LanRecordChannel, open: Profile2SessionOpen,
        window: PairReadyWindowAuthority?, isBusy: Bool,
        localDeviceName: String, platform: String,
        trustStore: PersistentTrustStore, approve: @escaping Approval
    ) async throws -> PersistentTrustedPeer {
        guard open.v == 1, open.type == "SESSION_OPEN",
              open.purpose == Profile2Constants.purposePairOnly,
              open.authMethod == Profile2Constants.authMethodSpake2PinV1 ||
              open.authMethod == Profile2Constants.authMethodQrTokenV2 else {
            try await rejectOpen(channel, id: open.sessionId, code: "PROTOCOL_ORDER")
            throw ApplePairSessionError.invalidMessage
        }
        guard !isBusy else {
            try await rejectOpen(channel, id: open.sessionId, code: "BUSY")
            throw ApplePairSessionError.busy
        }
        guard let window, window.isActive(),
              open.expectedPresenceId == nil || open.expectedPresenceId == window.presenceId else {
            try await rejectOpen(channel, id: open.sessionId, code: "EXPIRED")
            throw ApplePairSessionError.expired
        }
        guard let initiatorNonce = unb64(open.initiatorNonce),
              initiatorNonce.count == 32,
              let initiatorSpki = unb64(open.initiatorIdentityPublicKey) else {
            try await rejectOpen(channel, id: open.sessionId, code: "AUTH_FAILED")
            throw ApplePairSessionError.invalidMessage
        }
        let responderNonce = randomBytes(32)
        let responderSpki = try KeychainIdentityManager.shared.getPublicKeySpkiData()
        let serverSpki = try KeychainIdentityManager.shared.getTlsCertificateSpkiSha256Hex()
        try await channel.writeProfile2Message(Profile2SessionOpenAck(
            sessionId: open.sessionId, accepted: true,
            responderNonce: b64(responderNonce), responderDeviceName: localDeviceName,
            responderPlatform: platform,
            responderIdentityPublicKey: b64(responderSpki),
            advertisedPresenceId: window.presenceId))
        let peer: Profile2PairMsg = try await readPairMsg(
            channel, sessionId: open.sessionId, expectedRole: Profile2Constants.roleInitiator)
        var sharedSecret: Data
        if open.authMethod == Profile2Constants.authMethodSpake2PinV1 {
            guard let pin = window.copyPinData(), let peerValueText = peer.spakePublicValue,
                  let peerValue = unb64(peerValueText) else {
                try await rejectPair(channel, id: open.sessionId, code: "EXPIRED")
                throw ApplePairSessionError.expired
            }
            let names = GlideSpake2Session.buildRoleIdentifiers(
                sessionId: open.sessionId, initiatorNonce32: initiatorNonce,
                responderNonce32: responderNonce)
            let spake = try GlideSpake2Session(role: .bob,
                                               myName: names.bobName, theirName: names.aliceName)
            let publicValue = try spake.generateMessage(password: pin)
            sharedSecret = try spake.processPeerMessage(peerValue)
            try await channel.writeProfile2Message(Profile2PairMsg(
                sessionId: open.sessionId, role: Profile2Constants.roleResponder,
                spakePublicValue: b64(publicValue)))
        } else {
            guard open.invitationId == window.qrInvitation.invitationId,
                  peer.invitationHash == window.qrInvitation.invitationHash else {
                try await rejectPair(channel, id: open.sessionId, code: "EXPIRED")
                throw ApplePairSessionError.expired
            }
            sharedSecret = try GlideSpake2Session.deriveQrSharedSecret(
                tokenBase64Url: window.qrInvitation.token,
                invitationHashHex: window.qrInvitation.invitationHash)
            try await channel.writeProfile2Message(Profile2PairMsg(
                sessionId: open.sessionId, role: Profile2Constants.roleResponder,
                invitationHash: window.qrInvitation.invitationHash))
        }
        defer { sharedSecret.resetBytes(in: 0..<sharedSecret.count) }
        let transcript = transcriptBytes(
            method: open.authMethod, sessionId: open.sessionId,
            initiatorNonce: initiatorNonce, responderNonce: responderNonce,
            initiatorSpki: initiatorSpki, responderSpki: responderSpki,
            presence: window.presenceId, serverSpki: serverSpki)
        let hash = Data(SHA256.hash(data: transcript))
        let keys = GlideSpake2Session.deriveConfirmationKeys(
            sharedSecret: sharedSecret, transcriptHash32: hash)
        let initiatorConfirm: Profile2PairConfirm = try await read(channel)
        guard initiatorConfirm.v == 1, initiatorConfirm.type == "PAIR_CONFIRM",
              initiatorConfirm.sessionId == open.sessionId,
              initiatorConfirm.role == Profile2Constants.roleInitiator,
              let peerMac = unb64(initiatorConfirm.keyConfirmationMac),
              HMAC<SHA256>.isValidAuthenticationCode(
                peerMac, authenticating: hash, using: keys.confirmI2RKey),
              let signature = unb64(initiatorConfirm.identitySignature),
              verifySignature(spki: initiatorSpki, data: transcript, signature: signature) else {
            let exhausted = open.authMethod == Profile2Constants.authMethodSpake2PinV1
                && window.recordFailedPinAttempt()
            try await rejectPair(channel, id: open.sessionId,
                                 code: exhausted ? "EXPIRED" : "AUTH_FAILED")
            throw exhausted ? ApplePairSessionError.expired : ApplePairSessionError.wrongCode
        }
        let myMac = GlideSpake2Session.computeConfirmationMac(
            key: keys.confirmR2IKey, transcriptHash32: hash)
        let mySig = try KeychainIdentityManager.shared.sign(data: transcript)
        try await channel.writeProfile2Message(Profile2PairConfirm(
            sessionId: open.sessionId, role: Profile2Constants.roleResponder,
            keyConfirmationMac: b64(myMac), identitySignature: b64(mySig)))
        let responderApproved = await approve(open.initiatorDeviceName)
        let initiatorApproval: Profile2PairApproval = try await read(channel)
        guard initiatorApproval.v == 1, initiatorApproval.type == "PAIR_APPROVAL",
              initiatorApproval.sessionId == open.sessionId,
              initiatorApproval.role == Profile2Constants.roleInitiator else {
            throw ApplePairSessionError.invalidMessage
        }
        try await channel.writeProfile2Message(Profile2PairApproval(
            sessionId: open.sessionId, role: Profile2Constants.roleResponder,
            approved: responderApproved))
        guard initiatorApproval.approved && responderApproved else {
            try await rejectPair(channel, id: open.sessionId, code: "DECLINED")
            throw ApplePairSessionError.declined
        }
        guard window.consumeSuccess() else {
            try await rejectPair(channel, id: open.sessionId, code: "EXPIRED")
            throw ApplePairSessionError.expired
        }
        let fingerprint = sha256Hex(initiatorSpki)
        try trustStore.enrollPeer(
            fingerprintHex: fingerprint, publicKeyDerHex: hex(initiatorSpki),
            displayName: open.initiatorDeviceName)
        try await channel.writeProfile2Message(Profile2PairResult(
            sessionId: open.sessionId, paired: true))
        guard let saved = trustStore.getPeer(fingerprintHex: fingerprint) else {
            throw ApplePairSessionError.invalidMessage
        }
        return saved
    }

    private static func read<T: Decodable>(_ channel: LanRecordChannel) async throws -> T {
        let data = try await channel.readProfile2MessageData()
        if let result = try? JSONDecoder().decode(Profile2PairResult.self, from: data),
           result.type == "PAIR_RESULT", !result.paired { throw mapError(result.errorCode) }
        return try JSONDecoder().decode(T.self, from: data)
    }

    private static func readPairMsg(_ channel: LanRecordChannel,
                                    sessionId: String,
                                    expectedRole: String) async throws -> Profile2PairMsg {
        let data = try await channel.readProfile2MessageData()
        if let result = try? JSONDecoder().decode(Profile2PairResult.self, from: data),
           result.type == "PAIR_RESULT", !result.paired { throw mapError(result.errorCode) }
        let msg = try JSONDecoder().decode(Profile2PairMsg.self, from: data)
        guard msg.v == 1, msg.type == "PAIR_MSG", msg.sessionId == sessionId,
              msg.role == expectedRole else {
            throw ApplePairSessionError.invalidMessage
        }
        return msg
    }

    private static func rejectOpen(_ channel: LanRecordChannel, id: String,
                                   code: String) async throws {
        try await channel.writeProfile2Message(Profile2SessionOpenAck(
            sessionId: id, accepted: false, errorCode: code))
    }

    private static func rejectPair(_ channel: LanRecordChannel, id: String,
                                   code: String) async throws {
        try await channel.writeProfile2Message(Profile2PairResult(
            sessionId: id, paired: false, errorCode: code))
    }

    private static func mapError(_ code: String?) -> ApplePairSessionError {
        switch code {
        case "EXPIRED": return .expired
        case "BUSY": return .busy
        case "DECLINED": return .declined
        case "IDENTITY_CHANGED", "UNTRUSTED_PEER": return .identityChanged
        case "AUTH_FAILED": return .wrongCode
        default: return .invalidMessage
        }
    }

    private static func transcriptBytes(
        method: String, sessionId: String, initiatorNonce: Data,
        responderNonce: Data, initiatorSpki: Data, responderSpki: Data,
        presence: String, serverSpki: String
    ) -> Data {
        GlideSpake2Session.buildPairV2TranscriptBytes(
            method: method, sessionId: sessionId,
            initiatorNonceHex: hex(initiatorNonce), responderNonceHex: hex(responderNonce),
            initiatorFingerprintHex: sha256Hex(initiatorSpki),
            initiatorPublicKeyDerHex: hex(initiatorSpki),
            responderFingerprintHex: sha256Hex(responderSpki),
            responderPublicKeyDerHex: hex(responderSpki),
            responderPresenceHex: presence, tlsServerSpkiSha256Hex: serverSpki)
    }

    private static func verifySignature(spki: Data, data: Data, signature: Data) -> Bool {
        guard let key = try? P256.Signing.PublicKey(derRepresentation: spki),
              let sig = try? P256.Signing.ECDSASignature(derRepresentation: signature) else {
            return false
        }
        return key.isValidSignature(sig, for: data)
    }

    private static func randomBytes(_ count: Int) -> Data {
        Data((0..<count).map { _ in UInt8.random(in: .min ... .max) })
    }
    private static func b64(_ data: Data) -> String {
        InvitationCodec.toUnpaddedBase64Url(data)
    }
    private static func unb64(_ text: String) -> Data? {
        InvitationCodec.fromUnpaddedBase64Url(text)
    }
    private static func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }
    private static func sha256Hex(_ data: Data) -> String {
        hex(Data(SHA256.hash(data: data)))
    }
}
