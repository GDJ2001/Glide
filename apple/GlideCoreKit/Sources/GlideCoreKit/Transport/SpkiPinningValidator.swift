import Foundation
import CryptoKit

/// Validates TLS peer certificates against SubjectPublicKeyInfo (SPKI) SHA-256 fingerprints.
/// Conforms to Section 3.2 of APPLE_PLATFORMS_SPECIFICATION.md and ADR-0020.
public enum SpkiPinningValidator {
    public enum PinningError: Error, Equatable, Sendable {
        case emptySpkiData
        case invalidExpectedHashLength(actual: Int)
        case malformedHexHash
    }

    /// Computes the lowercase hex-encoded SHA-256 digest of raw SubjectPublicKeyInfo data.
    public static func computeSpkiSha256Hex(from spkiData: Data) throws -> String {
        guard !spkiData.isEmpty else {
            throw PinningError.emptySpkiData
        }

        let digest = SHA256.hash(data: spkiData)
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    /// Constant-time verification of computed SPKI SHA-256 against expected hash from out-of-band invitation.
    public static func verify(spkiData: Data, expectedSha256Hex: String) throws -> Bool {
        let expected = expectedSha256Hex.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard expected.count == 64 else {
            throw PinningError.invalidExpectedHashLength(actual: expected.count)
        }

        // Verify valid hex characters
        guard expected.allSatisfy({ "0123456789abcdef".contains($0) }) else {
            throw PinningError.malformedHexHash
        }

        let computed = try computeSpkiSha256Hex(from: spkiData)
        return constantTimeCompare(computed, expected)
    }

    /// Performs a constant-time string comparison to prevent timing attacks.
    private static func constantTimeCompare(_ a: String, _ b: String) -> Bool {
        guard a.utf8.count == b.utf8.count else { return false }
        var result: UInt8 = 0
        for (byteA, byteB) in zip(a.utf8, b.utf8) {
            result |= byteA ^ byteB
        }
        return result == 0
    }
}
