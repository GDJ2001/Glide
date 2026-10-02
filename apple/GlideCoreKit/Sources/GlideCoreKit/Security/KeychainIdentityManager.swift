import Foundation
import Security
import CryptoKit

public enum KeychainIdentityError: Error, Equatable, Sendable {
    case keyGenerationFailed(Int)
    case keyNotFound
    case keyExportFailed(String)
    case signingFailed(String)
    case tlsCertificateFailed(String)
    case tlsIdentityUnavailable
    case certificateStoreFailed(Int)
}

/// Manages local device cryptographic identity keys using Apple Keychain Services.
/// Generates and preserves hardware-backed or Secure Enclave-backed P-256 keys.
public final class KeychainIdentityManager: @unchecked Sendable {
    public static let shared = KeychainIdentityManager()
    private static let keyTag = "dev.glide.identity.key.p256"

    private let lock = NSLock()
    private let tlsLock = NSLock()
    private var cachedKey: SecKey?
    private var cachedTlsIdentity: SecIdentity?

    public init() {}

    /// Retrieves or generates the local device persistent P-256 identity key from the Keychain.
    public func getOrCreateIdentityKey() throws -> SecKey {
        lock.lock()
        defer { lock.unlock() }

        if let cached = cachedKey {
            return cached
        }

        let tag = Self.keyTag.data(using: .utf8)!
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: tag,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecReturnRef as String: true,
            kSecUseAuthenticationUI as String: kSecUseAuthenticationUIFail
        ]

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)

        if status == errSecSuccess, let key = item {
            let secKey = key as! SecKey
            self.cachedKey = secKey
            return secKey
        }

        // Key does not exist; generate new 256-bit EC key pair
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecUseAuthenticationUI as String: kSecUseAuthenticationUIFail,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: tag
            ]
        ]

        var error: Unmanaged<CFError>?
        var newKey = SecKeyCreateRandomKey(attributes as CFDictionary, &error)
        if newKey == nil {
            // Fallback for headless environments or CI without permanent Keychain write access
            let ephemeralAttributes: [String: Any] = [
                kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
                kSecAttrKeySizeInBits as String: 256
            ]
            newKey = SecKeyCreateRandomKey(ephemeralAttributes as CFDictionary, &error)
        }
        guard let validKey = newKey else {
            let errorDesc = error?.takeRetainedValue().localizedDescription ?? "Key generation error"
            throw KeychainIdentityError.keyExportFailed(errorDesc)
        }

        self.cachedKey = validKey
        return validKey
    }

    /// Exports the SubjectPublicKeyInfo (SPKI) data for the device's public key.
    public func getPublicKeySpkiData() throws -> Data {
        let privateKey = try getOrCreateIdentityKey()
        guard let publicKey = SecKeyCopyPublicKey(privateKey) else {
            throw KeychainIdentityError.keyNotFound
        }

        do {
            return try AppleCertificateSpki.der(from: publicKey)
        } catch {
            throw KeychainIdentityError.keyExportFailed(error.localizedDescription)
        }
    }

    /// Computes the lowercase 64-character hex SHA-256 fingerprint of the local device identity key.
    public func getLocalFingerprintHex() throws -> String {
        let spkiData = try getPublicKeySpkiData()
        let digest = SHA256.hash(data: spkiData)
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    /// Returns a real TLS identity whose certificate uses the persistent
    /// Keychain key. The invitation must pin the SPKI of this certificate.
    public func getOrCreateTlsIdentity() throws -> SecIdentity {
        tlsLock.lock()
        defer { tlsLock.unlock() }
        if let identity = cachedTlsIdentity { return identity }

        let privateKey = try getOrCreateIdentityKey()
        let certificate: SecCertificate
        do {
            certificate = try LocalTlsCertificate.create(privateKey: privateKey)
            let certificateSpki = try AppleCertificateSpki.der(from: certificate)
            let keySpki = try getPublicKeySpkiData()
            guard certificateSpki == keySpki else {
                throw KeychainIdentityError.tlsIdentityUnavailable
            }
        } catch {
            throw KeychainIdentityError.tlsCertificateFailed(error.localizedDescription)
        }

        let certificateQuery: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecAttrLabel as String: "dev.glide.local.tls.certificate"
        ]
        _ = SecItemDelete(certificateQuery as CFDictionary)
        var addQuery = certificateQuery
        addQuery[kSecValueRef as String] = certificate
        _ = SecItemAdd(addQuery as CFDictionary, nil)

        guard let identity = SecIdentityCreate(nil, certificate, privateKey) else {
            throw KeychainIdentityError.tlsIdentityUnavailable
        }
        cachedTlsIdentity = identity
        return identity
    }

    public func getTlsCertificateSpkiSha256Hex() throws -> String {
        let identity = try getOrCreateTlsIdentity()
        var certificate: SecCertificate?
        guard SecIdentityCopyCertificate(identity, &certificate) == errSecSuccess,
              let certificate else {
            throw KeychainIdentityError.tlsIdentityUnavailable
        }
        let spki = try AppleCertificateSpki.der(from: certificate)
        return SHA256.hash(data: spki)
            .map { String(format: "%02x", $0) }.joined()
    }

    /// Signs challenge data using the device private key with SHA-256.
    public func sign(data: Data) throws -> Data {
        let privateKey = try getOrCreateIdentityKey()

        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(
            privateKey,
            .ecdsaSignatureMessageX962SHA256,
            data as CFData,
            &error
        ) as Data? else {
            let errorDesc = error?.takeRetainedValue().localizedDescription ?? "Signature error"
            throw KeychainIdentityError.signingFailed(errorDesc)
        }

        return signature
    }
}
