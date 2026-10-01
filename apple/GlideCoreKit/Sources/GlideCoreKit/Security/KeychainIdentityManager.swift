import Foundation
import Security
import CryptoKit

public enum KeychainIdentityError: Error, Equatable, Sendable {
    case keyGenerationFailed(Int)
    case keyNotFound
    case keyExportFailed(String)
    case signingFailed(String)
}

/// Manages local device cryptographic identity keys using Apple Keychain Services.
/// Generates and preserves hardware-backed or Secure Enclave-backed P-256 keys.
public final class KeychainIdentityManager: @unchecked Sendable {
    public static let shared = KeychainIdentityManager()
    private static let keyTag = "dev.glide.identity.key.p256"

    private let lock = NSLock()
    private var cachedKey: SecKey?

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
            kSecReturnRef as String: true
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
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: tag
            ]
        ]

        var error: Unmanaged<CFError>?
        guard let newKey = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            let errorDesc = error?.takeRetainedValue().localizedDescription ?? "Unknown error"
            throw KeychainIdentityError.keyExportFailed(errorDesc)
        }

        self.cachedKey = newKey
        return newKey
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
