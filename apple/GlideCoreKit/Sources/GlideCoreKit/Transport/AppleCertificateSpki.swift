import Foundation
import Security

/// Reconstructs DER SubjectPublicKeyInfo for a P-256 certificate key. Apple's
/// SecKeyCopyExternalRepresentation returns only the ANSI X9.63 point, not SPKI;
/// hashing the raw point is incompatible with Kotlin/C# certificate pinning.
public enum AppleCertificateSpki {
    public enum SpkiError: Error { case unsupportedKey, exportFailed }

    private static let p256Header: [UInt8] = [
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86,
        0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a,
        0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03,
        0x42, 0x00
    ]

    public static func der(from certificate: SecCertificate) throws -> Data {
        guard let key = SecCertificateCopyKey(certificate) else {
            throw SpkiError.unsupportedKey
        }
        return try der(from: key)
    }

    public static func der(from key: SecKey) throws -> Data {
        var error: Unmanaged<CFError>?
        guard let point = SecKeyCopyExternalRepresentation(key, &error) as Data? else {
            throw SpkiError.exportFailed
        }
        guard point.count == 65 && point.first == 0x04 else {
            throw SpkiError.unsupportedKey
        }
        return Data(p256Header) + point
    }
}
