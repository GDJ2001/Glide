import Foundation
import Network
import Security
import CryptoKit

/// Factory for building Apple Network.framework NWParameters configured for Glide TLS 1.3 LAN pairing.
public enum GlideTlsParameters {
    public enum TlsIdentityError: Error { case invalidIdentity }

    /// Creates TLS parameters for an outbound client connection, enforcing TLS 1.3 and pinning
    /// the server's certificate against `expectedSpkiSha256` (when provided) while capturing the remote SPKI SHA-256.
    public static func createClientParameters(
        expectedSpkiSha256: String? = nil,
        onRemoteSpkiSha256Captured: (@Sendable (String) -> Void)? = nil
    ) -> NWParameters {
        let tlsOptions = NWProtocolTLS.Options()
        let secOptions = tlsOptions.securityProtocolOptions

        sec_protocol_options_set_min_tls_protocol_version(secOptions, .TLSv13)
        sec_protocol_options_set_max_tls_protocol_version(secOptions, .TLSv13)

        // Custom certificate verification block for SPKI SHA-256 pinning or channel binding capture
        let cleanExpectedHash = expectedSpkiSha256?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        sec_protocol_options_set_verify_block(
            secOptions,
            { (_, secTrust, completion) in
                let serverTrust = sec_trust_copy_ref(secTrust).takeRetainedValue()

                // Evaluate certificate SPKI pin using SecTrustCopyCertificateChain
                let certificates = SecTrustCopyCertificateChain(serverTrust) as? [SecCertificate]
                guard let certificate = certificates?.first else {
                    completion(false)
                    return
                }

                guard let spkiData = try? AppleCertificateSpki.der(from: certificate) else {
                    completion(false)
                    return
                }

                let actualSha256Hex = SHA256.hash(data: spkiData).map { String(format: "%02x", $0) }.joined()
                onRemoteSpkiSha256Captured?(actualSha256Hex)

                if let expected = cleanExpectedHash, !expected.isEmpty {
                    do {
                        let isValid = try SpkiPinningValidator.verify(
                            spkiData: spkiData,
                            expectedSha256Hex: expected
                        )
                        completion(isValid)
                    } catch {
                        completion(false)
                    }
                } else {
                    completion(!actualSha256Hex.isEmpty)
                }
            },
            DispatchQueue.global(qos: .userInitiated)
        )

        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.enableKeepalive = NetworkTransportConfig.enableKeepalive
        tcpOptions.keepaliveIdle = NetworkTransportConfig.keepaliveIdleSeconds

        let parameters = NWParameters(tls: tlsOptions, tcp: tcpOptions)
        // Standard LAN sockets and loopback testing operate over standard TCP/IP interfaces
        parameters.includePeerToPeer = false
        return parameters
    }

    /// Creates TLS parameters for an inbound server listener using an identity.
    public static func createServerParameters(identity: SecIdentity) throws -> NWParameters {
        let tlsOptions = NWProtocolTLS.Options()
        let secOptions = tlsOptions.securityProtocolOptions

        sec_protocol_options_set_min_tls_protocol_version(secOptions, .TLSv13)
        sec_protocol_options_set_max_tls_protocol_version(secOptions, .TLSv13)

        guard let secIdentity = sec_identity_create(identity) else {
            throw TlsIdentityError.invalidIdentity
        }
        sec_protocol_options_set_local_identity(secOptions, secIdentity)

        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.enableKeepalive = NetworkTransportConfig.enableKeepalive
        tcpOptions.keepaliveIdle = NetworkTransportConfig.keepaliveIdleSeconds

        let parameters = NWParameters(tls: tlsOptions, tcp: tcpOptions)
        // Standard LAN sockets and loopback testing operate over standard TCP/IP interfaces
        parameters.includePeerToPeer = false
        return parameters
    }
}
