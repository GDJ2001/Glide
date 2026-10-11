import Foundation
import CryptoKit
import CGlideSpake2

public enum Spake2Role: Int32, Sendable {
    case alice = 0
    case bob = 1
}

public enum GlideSpake2Error: Error, Equatable, Sendable {
    case contextCreationFailed
    case generateMessageFailed
    case processPeerMessageFailed
    case invalidInputLength
}

/// Swift wrapper around the pinned BoringSSL SPAKE2 C ABI (`CGlideSpake2`,
/// commit 3fed4f207543470b3d639db6ed40737e30308b51) and Profile 2 transcript/key-confirmation helpers.
public final class GlideSpake2Session {
    public static let pinnedBoringSslCommit = "3fed4f207543470b3d639db6ed40737e30308b51"
    public static let messageSize = 32
    public static let sharedSecretSize = 64

    private var ctx: OpaquePointer?

    public static var boringSslCommit: String {
        guard let cStr = glide_spake2_boringssl_commit() else { return "" }
        return String(cString: cStr)
    }

    public init(role: Spake2Role, myName: Data, theirName: Data) throws {
        let pointer: OpaquePointer? = myName.withUnsafeBytes { myRaw in
            theirName.withUnsafeBytes { theirRaw in
                let myPtr = myRaw.bindMemory(to: UInt8.self).baseAddress
                let theirPtr = theirRaw.bindMemory(to: UInt8.self).baseAddress
                return glide_spake2_ctx_new(role.rawValue, myPtr, myName.count, theirPtr, theirName.count)
            }
        }
        guard let validCtx = pointer else {
            throw GlideSpake2Error.contextCreationFailed
        }
        self.ctx = validCtx
    }

    deinit {
        if let validCtx = ctx {
            glide_spake2_ctx_free(validCtx)
            ctx = nil
        }
    }

    public func generateMessage(password: Data) throws -> Data {
        guard let validCtx = ctx, !password.isEmpty else {
            throw GlideSpake2Error.generateMessageFailed
        }
        var outMsg = Data(count: Self.messageSize)
        var outLen: Int = 0
        let rc = outMsg.withUnsafeMutableBytes { outRaw in
            password.withUnsafeBytes { pwdRaw in
                let outPtr = outRaw.bindMemory(to: UInt8.self).baseAddress
                let pwdPtr = pwdRaw.bindMemory(to: UInt8.self).baseAddress
                return glide_spake2_generate_msg(validCtx, outPtr, &outLen, Self.messageSize, pwdPtr, password.count)
            }
        }
        guard rc == 1 && outLen == Self.messageSize else {
            throw GlideSpake2Error.generateMessageFailed
        }
        return outMsg
    }

    public func processPeerMessage(_ peerMessage: Data) throws -> Data {
        guard let validCtx = ctx, peerMessage.count == Self.messageSize else {
            throw GlideSpake2Error.processPeerMessageFailed
        }
        var outKey = Data(count: Self.sharedSecretSize)
        var outLen: Int = 0
        let rc = outKey.withUnsafeMutableBytes { outRaw in
            peerMessage.withUnsafeBytes { peerRaw in
                let outPtr = outRaw.bindMemory(to: UInt8.self).baseAddress
                let peerPtr = peerRaw.bindMemory(to: UInt8.self).baseAddress
                return glide_spake2_process_msg(validCtx, outPtr, &outLen, Self.sharedSecretSize, peerPtr, peerMessage.count)
            }
        }
        guard rc == 1 && outLen == Self.sharedSecretSize else {
            throw GlideSpake2Error.processPeerMessageFailed
        }
        return outKey
    }

    public static func buildRoleIdentifiers(
        sessionId: String,
        initiatorNonce32: Data,
        responderNonce32: Data
    ) -> (aliceName: Data, bobName: Data) {
        var alice = Data("glide-pair-v2:initiator:\(sessionId.lowercased()):".utf8)
        alice.append(initiatorNonce32)
        var bob = Data("glide-pair-v2:responder:\(sessionId.lowercased()):".utf8)
        bob.append(responderNonce32)
        return (alice, bob)
    }

    public static func buildPairV2TranscriptBytes(
        method: String = "spake2-pin-v1",
        sessionId: String,
        initiatorNonceHex: String,
        responderNonceHex: String,
        initiatorFingerprintHex: String,
        initiatorPublicKeyDerHex: String,
        responderFingerprintHex: String,
        responderPublicKeyDerHex: String,
        responderPresenceHex: String,
        tlsServerSpkiSha256Hex: String,
        purpose: String = "PAIR_ONLY"
    ) -> Data {
        var out = Data()
        out.reserveCapacity(640)

        func writeString(_ value: String) {
            let bytes = Array(value.utf8)
            var len = UInt32(bytes.count).bigEndian
            withUnsafeBytes(of: &len) { out.append(contentsOf: $0) }
            out.append(contentsOf: bytes)
        }

        out.append(contentsOf: "GLIDE-PAIR-V2".utf8)
        out.append(0)
        writeString(method)
        writeString(sessionId.lowercased())
        writeString("initiator")
        writeString("responder")
        writeString(initiatorNonceHex.lowercased())
        writeString(responderNonceHex.lowercased())
        writeString(initiatorFingerprintHex.lowercased())
        writeString(initiatorPublicKeyDerHex.lowercased())
        writeString(responderFingerprintHex.lowercased())
        writeString(responderPublicKeyDerHex.lowercased())
        writeString(responderPresenceHex.lowercased())
        writeString(tlsServerSpkiSha256Hex.lowercased())
        writeString(purpose)
        return out
    }

    public static func deriveConfirmationKeys(
        sharedSecret: Data,
        transcriptHash32: Data
    ) -> (confirmI2RKey: SymmetricKey, confirmR2IKey: SymmetricKey) {
        let prk = HKDF<SHA256>.extract(inputKeyMaterial: SymmetricKey(data: sharedSecret), salt: transcriptHash32)
        let i2r = HKDF<SHA256>.expand(
            pseudoRandomKey: prk,
            info: Data("glide-pair-v2-confirm-i2r".utf8),
            outputByteCount: 32
        )
        let r2i = HKDF<SHA256>.expand(
            pseudoRandomKey: prk,
            info: Data("glide-pair-v2-confirm-r2i".utf8),
            outputByteCount: 32
        )
        return (i2r, r2i)
    }

    public static func computeConfirmationMac(
        key: SymmetricKey,
        transcriptHash32: Data
    ) -> Data {
        let mac = HMAC<SHA256>.authenticationCode(for: transcriptHash32, using: key)
        return Data(mac)
    }

    public static func deriveQrSharedSecret(tokenBase64Url: String, invitationHashHex: String) throws -> Data {
        guard let tokenBytes = InvitationCodec.fromUnpaddedBase64Url(tokenBase64Url) else {
            throw InvitationError.invalidToken
        }
        let salt = Data(SHA256.hash(data: Data(invitationHashHex.lowercased().utf8)))
        let prk = HKDF<SHA256>.extract(inputKeyMaterial: SymmetricKey(data: tokenBytes), salt: salt)
        let expanded = HKDF<SHA256>.expand(
            pseudoRandomKey: prk,
            info: Data("glide-pair-v2-qr-secret".utf8),
            outputByteCount: 64
        )
        return expanded.withUnsafeBytes { Data($0) }
    }
}
