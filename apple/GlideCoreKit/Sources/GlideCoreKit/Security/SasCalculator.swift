import Foundation
import CryptoKit

/// Canonical Short Authentication String (SAS) calculation engine.
/// Conforms to LAN-006 Section 8, ADR-0020, and cross-platform golden vectors.
public enum SasCalculator {
    public enum SasError: Error, Equatable, Sendable {
        case digestTooShort
        case emptyInput(String)
    }

    /// Extracts a 6-digit decimal authentication code from a cryptographic digest.
    /// Returns an integer guaranteed to be in the range [100,000 .. 999,999].
    public static func extractDecimal6(from digest: Data) throws -> Int {
        guard digest.count >= 4 else {
            throw SasError.digestTooShort
        }

        let val = digest.subdata(in: 0..<4).withUnsafeBytes { ptr in
            UInt32(bigEndian: ptr.load(as: UInt32.self))
        }

        let decimalCode = Int((val % 900_000) + 100_000)
        return decimalCode
    }

    /// Computes the mutual SAS decimal code from client public key, server public key, and session nonce.
    public static func calculateSas(
        clientPublicKey: Data,
        serverPublicKey: Data,
        sessionNonce: Data
    ) throws -> Int {
        guard !clientPublicKey.isEmpty else {
            throw SasError.emptyInput("clientPublicKey")
        }
        guard !serverPublicKey.isEmpty else {
            throw SasError.emptyInput("serverPublicKey")
        }
        guard !sessionNonce.isEmpty else {
            throw SasError.emptyInput("sessionNonce")
        }

        var combined = Data()
        combined.append(clientPublicKey)
        combined.append(serverPublicKey)
        combined.append(sessionNonce)

        let digest = SHA256.hash(data: combined)
        let digestData = Data(digest)
        return try extractDecimal6(from: digestData)
    }

    /// Formats an integer SAS code as a two-part 6-digit string: `### ###` (e.g. `492 815`).
    public static func formatSas(_ code: Int) -> String {
        let normalized = max(100_000, min(999_999, code))
        let part1 = normalized / 1_000
        let part2 = normalized % 1_000
        return String(format: "%03d %03d", part1, part2)
    }
}
