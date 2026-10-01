import Foundation
import Security

/// Creates a short-lived self-signed server certificate for the persistent
/// Keychain P-256 key. Glide authenticates the peer by the invitation's SPKI
/// pin, so reissuing this certificate does not change the device identity.
enum LocalTlsCertificate {
    enum CertificateError: Error {
        case publicKeyUnavailable
        case signingFailed
        case invalidCertificate
    }

    static func create(privateKey: SecKey) throws -> SecCertificate {
        guard let publicKey = SecKeyCopyPublicKey(privateKey) else {
            throw CertificateError.publicKeyUnavailable
        }

        let signatureAlgorithm = sequence([
            oid([0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03, 0x02]) // ecdsa-with-SHA256
        ])
        let name = sequence([
            set([sequence([
                oid([0x55, 0x04, 0x03]), // commonName
                primitive(0x0c, Data("Glide Local".utf8))
            ])])
        ])
        let now = Date()
        let validity = sequence([
            generalizedTime(now.addingTimeInterval(-86_400)),
            generalizedTime(now.addingTimeInterval(365 * 86_400))
        ])
        var serial = [UInt8](repeating: 0, count: 16)
        let randomStatus = serial.withUnsafeMutableBytes { bytes in
            SecRandomCopyBytes(kSecRandomDefault, bytes.count, bytes.baseAddress!)
        }
        guard randomStatus == errSecSuccess else { throw CertificateError.invalidCertificate }
        serial[0] &= 0x7f
        if serial.allSatisfy({ $0 == 0 }) { serial[0] = 1 }

        // A TLS leaf must be a non-CA signing certificate with serverAuth use.
        let extensions = primitive(0xa3, sequence([
            extensionValue([0x55, 0x1d, 0x13], critical: true,
                           value: sequence([])), // basicConstraints: CA=false
            extensionValue([0x55, 0x1d, 0x0f], critical: true,
                           value: primitive(0x03, Data([0x07, 0x80]))), // digitalSignature
            extensionValue([0x55, 0x1d, 0x25], critical: false,
                           value: sequence([
                               oid([0x2b, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x01])
                           ])) // serverAuth
        ]))

        let tbs = try sequence([
            primitive(0xa0, integer(Data([0x02]))), // X.509 v3
            integer(Data(serial)),
            signatureAlgorithm,
            name,
            validity,
            name,
            AppleCertificateSpki.der(from: publicKey),
            extensions
        ])

        var signingError: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(
            privateKey, .ecdsaSignatureMessageX962SHA256,
            tbs as CFData, &signingError) as Data? else {
            _ = signingError?.takeRetainedValue()
            throw CertificateError.signingFailed
        }
        let certificateDer = sequence([
            tbs,
            signatureAlgorithm,
            primitive(0x03, Data([0]) + signature)
        ])
        guard let certificate = SecCertificateCreateWithData(nil, certificateDer as CFData) else {
            throw CertificateError.invalidCertificate
        }
        return certificate
    }

    private static func extensionValue(_ id: [UInt8], critical: Bool, value: Data) -> Data {
        var components = [oid(id)]
        if critical { components.append(primitive(0x01, Data([0xff]))) }
        components.append(primitive(0x04, value))
        return sequence(components)
    }

    private static func generalizedTime(_ date: Date) -> Data {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyyMMddHHmmss'Z'"
        return primitive(0x18, Data(formatter.string(from: date).utf8))
    }

    private static func oid(_ body: [UInt8]) -> Data { primitive(0x06, Data(body)) }
    private static func integer(_ body: Data) -> Data { primitive(0x02, body) }
    private static func set(_ values: [Data]) -> Data { primitive(0x31, joined(values)) }
    private static func sequence(_ values: [Data]) -> Data { primitive(0x30, joined(values)) }

    private static func joined(_ values: [Data]) -> Data {
        var result = Data()
        for value in values { result.append(value) }
        return result
    }

    private static func primitive(_ tag: UInt8, _ body: Data) -> Data {
        var result = Data([tag])
        if body.count < 128 {
            result.append(UInt8(body.count))
        } else if body.count < 256 {
            result.append(contentsOf: [0x81, UInt8(body.count)])
        } else {
            result.append(contentsOf: [0x82, UInt8(body.count >> 8), UInt8(body.count & 0xff)])
        }
        result.append(body)
        return result
    }
}
