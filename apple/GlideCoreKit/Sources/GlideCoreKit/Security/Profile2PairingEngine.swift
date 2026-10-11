import Foundation
import CryptoKit

public enum Profile2Constants {
    public static let wireVersion = 2
    public static let purposePairOnly = "PAIR_ONLY"
    public static let purposeTrustedSend = "TRUSTED_SEND"
    public static let authMethodSpake2PinV1 = "spake2-pin-v1"
    public static let authMethodQrTokenV2 = "qr-token-v2"
    public static let authMethodIdentityV1 = "identity-v1"
    public static let roleInitiator = "initiator"
    public static let roleResponder = "responder"
    public static let defaultPairWindowSeconds = 120
    public static let maxPinAttemptsPerWindow = 3
    public static let maxControlPayloadBytes = 16 * 1024
    public static let gld2Magic = Data("GLD2".utf8)
}

public struct Profile2SessionOpen: Codable, Equatable, Sendable {
    public let v: Int
    public let type: String
    public let sessionId: String
    public let purpose: String
    public let authMethod: String
    public let initiatorNonce: String
    public let initiatorDeviceName: String
    public let initiatorPlatform: String
    public let initiatorIdentityPublicKey: String
    public let expectedPresenceId: String?
    public let expectedPeerIdentityFingerprint: String?
    public let invitationId: String?

    public init(
        v: Int = Profile2Constants.wireVersion,
        type: String = "SESSION_OPEN",
        sessionId: String,
        purpose: String,
        authMethod: String,
        initiatorNonce: String,
        initiatorDeviceName: String,
        initiatorPlatform: String,
        initiatorIdentityPublicKey: String,
        expectedPresenceId: String? = nil,
        expectedPeerIdentityFingerprint: String? = nil,
        invitationId: String? = nil
    ) {
        self.v = v
        self.type = type
        self.sessionId = sessionId
        self.purpose = purpose
        self.authMethod = authMethod
        self.initiatorNonce = initiatorNonce
        self.initiatorDeviceName = initiatorDeviceName
        self.initiatorPlatform = initiatorPlatform
        self.initiatorIdentityPublicKey = initiatorIdentityPublicKey
        self.expectedPresenceId = expectedPresenceId
        self.expectedPeerIdentityFingerprint = expectedPeerIdentityFingerprint
        self.invitationId = invitationId
    }
}

public struct Profile2SessionOpenAck: Codable, Equatable, Sendable {
    public let v: Int
    public let type: String
    public let sessionId: String
    public let accepted: Bool
    public let errorCode: String?
    public let message: String?
    public let responderNonce: String?
    public let responderDeviceName: String?
    public let responderPlatform: String?
    public let responderIdentityPublicKey: String?
    public let advertisedPresenceId: String?

    public init(
        v: Int = Profile2Constants.wireVersion,
        type: String = "SESSION_OPEN_ACK",
        sessionId: String,
        accepted: Bool,
        errorCode: String? = nil,
        message: String? = nil,
        responderNonce: String? = nil,
        responderDeviceName: String? = nil,
        responderPlatform: String? = nil,
        responderIdentityPublicKey: String? = nil,
        advertisedPresenceId: String? = nil
    ) {
        self.v = v
        self.type = type
        self.sessionId = sessionId
        self.accepted = accepted
        self.errorCode = errorCode
        self.message = message
        self.responderNonce = responderNonce
        self.responderDeviceName = responderDeviceName
        self.responderPlatform = responderPlatform
        self.responderIdentityPublicKey = responderIdentityPublicKey
        self.advertisedPresenceId = advertisedPresenceId
    }
}

public struct Profile2PairMsg: Codable, Equatable, Sendable {
    public let v: Int
    public let type: String
    public let sessionId: String
    public let role: String
    public let spakePublicValue: String?
    public let invitationHash: String?

    public init(
        v: Int = Profile2Constants.wireVersion,
        type: String = "PAIR_MSG",
        sessionId: String,
        role: String,
        spakePublicValue: String? = nil,
        invitationHash: String? = nil
    ) {
        self.v = v
        self.type = type
        self.sessionId = sessionId
        self.role = role
        self.spakePublicValue = spakePublicValue
        self.invitationHash = invitationHash
    }
}

public struct Profile2PairConfirm: Codable, Equatable, Sendable {
    public let v: Int
    public let type: String
    public let sessionId: String
    public let role: String
    public let keyConfirmationMac: String
    public let identitySignature: String

    public init(
        v: Int = Profile2Constants.wireVersion,
        type: String = "PAIR_CONFIRM",
        sessionId: String,
        role: String,
        keyConfirmationMac: String,
        identitySignature: String
    ) {
        self.v = v
        self.type = type
        self.sessionId = sessionId
        self.role = role
        self.keyConfirmationMac = keyConfirmationMac
        self.identitySignature = identitySignature
    }
}

public struct Profile2PairApproval: Codable, Equatable, Sendable {
    public let v: Int
    public let type: String
    public let sessionId: String
    public let role: String
    public let approved: Bool
    public let reason: String?

    public init(
        v: Int = Profile2Constants.wireVersion,
        type: String = "PAIR_APPROVAL",
        sessionId: String,
        role: String,
        approved: Bool,
        reason: String? = nil
    ) {
        self.v = v
        self.type = type
        self.sessionId = sessionId
        self.role = role
        self.approved = approved
        self.reason = reason
    }
}

public struct Profile2PairResult: Codable, Equatable, Sendable {
    public let v: Int
    public let type: String
    public let sessionId: String
    public let paired: Bool
    public let errorCode: String?
    public let message: String?

    public init(
        v: Int = Profile2Constants.wireVersion,
        type: String = "PAIR_RESULT",
        sessionId: String,
        paired: Bool,
        errorCode: String? = nil,
        message: String? = nil
    ) {
        self.v = v
        self.type = type
        self.sessionId = sessionId
        self.paired = paired
        self.errorCode = errorCode
        self.message = message
    }
}

/// Manages a 2-minute "Ready to pair" window on a macOS target, holding the ephemeral 6-digit PIN,
/// the matching one-use `glide://pair/v2#...` QR/link invitation, one-use consumption, and the 3-attempt failure ceiling.
public final class PairReadyWindowAuthority: @unchecked Sendable {
    private let lock = NSLock()
    private var pinAsciiBytes: [UInt8]
    private var failedAttempts: Int = 0
    private var consumed: Bool = false
    private var cancelled: Bool = false

    public let presenceId: String
    public let formattedPinDisplay: String
    public let qrInvitation: LanPairV2Invitation
    public let qrInvitationUri: String
    public let expiresAt: Date

    public init(
        presenceId: String,
        deviceName: String,
        platform: String = "macos",
        endpoints: [LanEndpoint],
        serverSpkiSha256Hex: String,
        ttlSeconds: Int = Profile2Constants.defaultPairWindowSeconds,
        fixedPinForTest: String? = nil
    ) throws {
        self.presenceId = presenceId.lowercased()
        let clampedTtl = max(1, min(ttlSeconds, Profile2Constants.defaultPairWindowSeconds))
        self.expiresAt = Date().addingTimeInterval(TimeInterval(clampedTtl))

        let rawPin = fixedPinForTest ?? String(format: "%06d", Int.random(in: 0...999_999))
        guard rawPin.count == 6, rawPin.allSatisfy({ $0.isASCII && $0.isNumber }) else {
            throw InvitationError.invalidSchema
        }
        self.pinAsciiBytes = Array(rawPin.utf8)
        let prefix3 = rawPin.prefix(3)
        let suffix3 = rawPin.suffix(3)
        self.formattedPinDisplay = "\(prefix3) \(suffix3)"

        var tokenBytes = [UInt8](repeating: 0, count: 32)
        for i in 0..<32 {
            tokenBytes[i] = UInt8.random(in: 0...255)
        }
        let token = InvitationCodec.toUnpaddedBase64Url(Data(tokenBytes))
        let expEpoch = String(Int64(self.expiresAt.timeIntervalSince1970))
        let draft = LanPairV2Invitation(
            invitationId: UUID().uuidString.lowercased(),
            presence: self.presenceId,
            name: deviceName,
            platform: platform,
            endpoints: endpoints,
            spkiSha256: serverSpkiSha256Hex,
            token: token,
            expiresAt: expEpoch,
            ttlSeconds: clampedTtl
        )
        let uri = try InvitationCodec.encodePairV2(draft)
        self.qrInvitation = try InvitationCodec.decodePairV2(uri, currentEpochSeconds: Int64(Date().timeIntervalSince1970))
        self.qrInvitationUri = uri
    }

    public func isActive(now: Date = Date()) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if cancelled || consumed || failedAttempts >= Profile2Constants.maxPinAttemptsPerWindow {
            return false
        }
        return now <= expiresAt
    }

    public func copyPinData(now: Date = Date()) -> Data? {
        lock.lock()
        defer { lock.unlock() }
        if cancelled || consumed || failedAttempts >= Profile2Constants.maxPinAttemptsPerWindow || now > expiresAt {
            return nil
        }
        return Data(pinAsciiBytes)
    }

    @discardableResult
    public func recordFailedPinAttempt() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        failedAttempts += 1
        if failedAttempts >= Profile2Constants.maxPinAttemptsPerWindow {
            zeroPinLocked()
            return true
        }
        return false
    }

    @discardableResult
    public func consumeSuccess(now: Date = Date()) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if cancelled || consumed || failedAttempts >= Profile2Constants.maxPinAttemptsPerWindow || now > expiresAt {
            return false
        }
        consumed = true
        zeroPinLocked()
        return true
    }

    public func cancel() {
        lock.lock()
        defer { lock.unlock() }
        cancelled = true
        zeroPinLocked()
    }

    private func zeroPinLocked() {
        for i in pinAsciiBytes.indices {
            pinAsciiBytes[i] = 0
        }
    }
}
