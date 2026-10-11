import Foundation
import CryptoKit

/// Profile 1 identity-v1 proof used after a GLD2 TRUSTED_SEND open and HELLO_ACK.
/// Both parties sign the same length-prefixed transcript used by C# and Kotlin.
public enum AppleTrustedSessionAuthenticator {
    public static let capabilities = ["identity-v1", "sha256", "stream-v1"]

    public static func authenticateSender(
        channel: LanRecordChannel, sequence: LanControlSequence, sessionId: String,
        peer: PersistentTrustedPeer, negotiatedCapabilities: [String]
    ) async throws {
        let binding = try requireBinding(channel.remoteSpkiSha256Hex)
        let capHash = try capabilityHash(negotiatedCapabilities)
        let localKey = try KeychainIdentityManager.shared.getPublicKeySpkiData()
        let localFp = sha256Hex(localKey)
        let challenge = try await makeChallenge(
            sequence: sequence, sessionId: sessionId, verifierRole: "sender",
            signerRole: "receiver", verifierFp: localFp, binding: binding, capHash: capHash)
        try await channel.writeRecord(challenge.record)
        let response = try await sequence.readEnvelope(
            from: channel, expectedType: LanMessageTypes.identityResponse, sessionId: sessionId)
        try verifyResponse(response, challenge: challenge.envelope, sessionId: sessionId,
                           signerRole: "receiver", verifierRole: "sender", verifierFp: localFp,
                           binding: binding, capHash: capHash, expectedPeer: peer)

        let reciprocal = try await sequence.readEnvelope(
            from: channel, expectedType: LanMessageTypes.identityChallenge, sessionId: sessionId)
        try validateChallenge(reciprocal, verifierRole: "receiver", signerRole: "sender",
                              verifierFp: peer.fingerprintHex, binding: binding, capHash: capHash)
        try await respondToChallenge(reciprocal, channel: channel, sequence: sequence,
                                     sessionId: sessionId, signerRole: "sender",
                                     verifierRole: "receiver", localKey: localKey,
                                     binding: binding, capHash: capHash)
    }

    public static func authenticateReceiver(
        channel: LanRecordChannel, sequence: LanControlSequence, sessionId: String,
        peer: PersistentTrustedPeer, localServerSpkiHex: String,
        negotiatedCapabilities: [String]
    ) async throws {
        let binding = try requireBinding(localServerSpkiHex)
        let capHash = try capabilityHash(negotiatedCapabilities)
        let localKey = try KeychainIdentityManager.shared.getPublicKeySpkiData()
        let localFp = sha256Hex(localKey)
        let challenge = try await sequence.readEnvelope(
            from: channel, expectedType: LanMessageTypes.identityChallenge, sessionId: sessionId)
        try validateChallenge(challenge, verifierRole: "sender", signerRole: "receiver",
                              verifierFp: peer.fingerprintHex, binding: binding, capHash: capHash)
        try await respondToChallenge(challenge, channel: channel, sequence: sequence,
                                     sessionId: sessionId, signerRole: "receiver",
                                     verifierRole: "sender", localKey: localKey,
                                     binding: binding, capHash: capHash)

        let reciprocal = try await makeChallenge(
            sequence: sequence, sessionId: sessionId, verifierRole: "receiver",
            signerRole: "sender", verifierFp: localFp, binding: binding, capHash: capHash)
        try await channel.writeRecord(reciprocal.record)
        let response = try await sequence.readEnvelope(
            from: channel, expectedType: LanMessageTypes.identityResponse, sessionId: sessionId)
        try verifyResponse(response, challenge: reciprocal.envelope, sessionId: sessionId,
                           signerRole: "sender", verifierRole: "receiver", verifierFp: localFp,
                           binding: binding, capHash: capHash, expectedPeer: peer)
    }

    public static func capabilityHash(_ capabilities: [String]) throws -> String {
        let sorted = Array(Set(capabilities)).sorted()
        guard sorted.allSatisfy({ !$0.isEmpty && $0.utf8.count <= 64 &&
            $0.utf8.allSatisfy({ ($0 >= 97 && $0 <= 122) || ($0 >= 48 && $0 <= 57) || $0 == 45 }) }) else {
            throw ApplePairSessionError.identityChanged
        }
        var bytes = Data("GLIDE-CAPS-V1".utf8)
        bytes.append(0)
        appendUInt32(UInt32(sorted.count), to: &bytes)
        for capability in sorted { appendString(capability, to: &bytes) }
        return sha256Hex(bytes)
    }

    public static func identityProofBytes(
        sessionId: String, challengeMessageId: String, signerRole: String,
        verifierRole: String, signerFp: String, verifierFp: String,
        challengeNonce: String, binding: String, capHash: String
    ) throws -> Data {
        guard LanEnvelope.isValidUuidV4(sessionId),
              LanEnvelope.isValidUuidV4(challengeMessageId),
              signerRole != verifierRole,
              ["sender", "receiver"].contains(signerRole),
              ["sender", "receiver"].contains(verifierRole),
              [signerFp, verifierFp, challengeNonce, binding, capHash].allSatisfy(validHex64) else {
            throw ApplePairSessionError.identityChanged
        }
        var bytes = Data("GLIDE-SIGN-V1".utf8)
        bytes.append(0)
        bytes.append(1)
        for text in ["identity-v1", "tls-server-spki-sha256-v1", sessionId,
                     challengeMessageId, signerRole, verifierRole, signerFp,
                     verifierFp, challengeNonce, binding, capHash] {
            appendString(text, to: &bytes)
        }
        return bytes
    }

    private static func makeChallenge(
        sequence: LanControlSequence, sessionId: String, verifierRole: String,
        signerRole: String, verifierFp: String, binding: String, capHash: String
    ) async throws -> (record: LanRecord, envelope: LanEnvelope) {
        let nonce = hex(Data((0..<32).map { _ in UInt8.random(in: 0...255) }))
        let record = try await sequence.createRecord(
            type: LanMessageTypes.identityChallenge, sessionId: sessionId,
            body: ["profile": .string("identity-v1"),
                   "challengeNonceHex": .string(nonce),
                   "verifierFingerprintHex": .string(verifierFp),
                   "verifierRole": .string(verifierRole),
                   "signerRole": .string(signerRole),
                   "channelBindingHex": .string(binding),
                   "capabilityHashHex": .string(capHash)])
        guard case .control(let payload) = record else { throw ApplePairSessionError.invalidMessage }
        return (record, try LanProtocolCodec.decode(payload))
    }

    private static func respondToChallenge(
        _ challenge: LanEnvelope, channel: LanRecordChannel,
        sequence: LanControlSequence, sessionId: String,
        signerRole: String, verifierRole: String, localKey: Data,
        binding: String, capHash: String
    ) async throws {
        let signerFp = sha256Hex(localKey)
        let verifierFp = try field(challenge, "verifierFingerprintHex")
        let nonce = try field(challenge, "challengeNonceHex")
        let proof = try identityProofBytes(
            sessionId: sessionId, challengeMessageId: challenge.messageId,
            signerRole: signerRole, verifierRole: verifierRole,
            signerFp: signerFp, verifierFp: verifierFp,
            challengeNonce: nonce, binding: binding, capHash: capHash)
        let signature = try KeychainIdentityManager.shared.sign(data: proof)
        let record = try await sequence.createRecord(
            type: LanMessageTypes.identityResponse, sessionId: sessionId,
            body: ["profile": .string("identity-v1"),
                   "challengeMessageId": .string(challenge.messageId),
                   "challengeNonceHex": .string(nonce),
                   "signerFingerprintHex": .string(signerFp),
                   "publicKeyDerHex": .string(hex(localKey)),
                   "signatureHex": .string(hex(signature))])
        try await channel.writeRecord(record)
    }

    private static func validateChallenge(
        _ challenge: LanEnvelope, verifierRole: String, signerRole: String,
        verifierFp: String, binding: String, capHash: String
    ) throws {
        guard challenge.type == LanMessageTypes.identityChallenge,
              try field(challenge, "profile") == "identity-v1",
              try field(challenge, "verifierRole") == verifierRole,
              try field(challenge, "signerRole") == signerRole,
              try field(challenge, "verifierFingerprintHex") == verifierFp,
              try field(challenge, "channelBindingHex") == binding,
              try field(challenge, "capabilityHashHex") == capHash,
              try validHex64(field(challenge, "challengeNonceHex")) else {
            throw ApplePairSessionError.identityChanged
        }
    }

    private static func verifyResponse(
        _ response: LanEnvelope, challenge: LanEnvelope, sessionId: String,
        signerRole: String, verifierRole: String, verifierFp: String,
        binding: String, capHash: String, expectedPeer: PersistentTrustedPeer
    ) throws {
        let keyHex = try field(response, "publicKeyDerHex")
        let signatureHex = try field(response, "signatureHex")
        guard response.type == LanMessageTypes.identityResponse,
              try field(response, "profile") == "identity-v1",
              try field(response, "challengeMessageId") == challenge.messageId,
              try field(response, "challengeNonceHex") == field(challenge, "challengeNonceHex"),
              try field(response, "signerFingerprintHex") == expectedPeer.fingerprintHex,
              keyHex == expectedPeer.publicKeyDerHex,
              let keyData = unhex(keyHex), let signatureData = unhex(signatureHex),
              sha256Hex(keyData) == expectedPeer.fingerprintHex,
              let key = try? P256.Signing.PublicKey(derRepresentation: keyData),
              let signature = try? P256.Signing.ECDSASignature(derRepresentation: signatureData) else {
            throw ApplePairSessionError.identityChanged
        }
        let proof = try identityProofBytes(
            sessionId: sessionId, challengeMessageId: challenge.messageId,
            signerRole: signerRole, verifierRole: verifierRole,
            signerFp: expectedPeer.fingerprintHex, verifierFp: verifierFp,
            challengeNonce: field(challenge, "challengeNonceHex"),
            binding: binding, capHash: capHash)
        guard key.isValidSignature(signature, for: proof) else {
            throw ApplePairSessionError.identityChanged
        }
    }

    private static func field(_ envelope: LanEnvelope, _ name: String) throws -> String {
        guard case .string(let value)? = envelope.body[name] else {
            throw ApplePairSessionError.identityChanged
        }
        return value
    }
    private static func requireBinding(_ value: String?) throws -> String {
        guard let value, validHex64(value), value.contains(where: { $0 != "0" }) else {
            throw ApplePairSessionError.identityChanged
        }
        return value
    }
    private static func validHex64(_ value: String) -> Bool {
        value.count == 64 && value.utf8.allSatisfy({ ($0 >= 48 && $0 <= 57) || ($0 >= 97 && $0 <= 102) })
    }
    private static func appendUInt32(_ value: UInt32, to data: inout Data) {
        data.append(contentsOf: [UInt8(value >> 24), UInt8((value >> 16) & 255),
                                 UInt8((value >> 8) & 255), UInt8(value & 255)])
    }
    private static func appendString(_ value: String, to data: inout Data) {
        let utf8 = Data(value.utf8)
        appendUInt32(UInt32(utf8.count), to: &data)
        data.append(utf8)
    }
    private static func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }
    private static func sha256Hex(_ data: Data) -> String {
        hex(Data(SHA256.hash(data: data)))
    }
    private static func unhex(_ value: String) -> Data? {
        guard value.count.isMultiple(of: 2) else { return nil }
        var bytes = [UInt8]()
        bytes.reserveCapacity(value.count / 2)
        var index = value.startIndex
        while index < value.endIndex {
            let next = value.index(index, offsetBy: 2)
            guard let byte = UInt8(value[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        return Data(bytes)
    }
}
