import Foundation
import CryptoKit
import Darwin
import Security

public enum AppleInvitationError: Error, Equatable, Sendable {
    case listenerUnavailable
    case certificateUnavailable
    case noReachableAddress
    case randomGenerationFailed
}

/// Publishes a one-use invitation only for an already-ready TLS listener.
/// The pin comes from the certificate in that listener's SecIdentity.
public enum AppleInvitationManager {
    public static func publish(
        listener: GlideNetworkListener,
        authority: LanInvitationAuthority,
        deviceName: String,
        platform: String
    ) throws -> (invitation: LanInvitation, uri: String) {
        guard listener.isListening, listener.boundPort > 0,
              let identity = listener.tlsIdentity else {
            throw AppleInvitationError.listenerUnavailable
        }
        var certificate: SecCertificate?
        guard SecIdentityCopyCertificate(identity, &certificate) == errSecSuccess,
              let certificate else {
            throw AppleInvitationError.certificateUnavailable
        }

        let addresses = localIPv4Addresses()
        guard !addresses.isEmpty else { throw AppleInvitationError.noReachableAddress }
        let spki = try AppleCertificateSpki.der(from: certificate)
        let fingerprint = SHA256.hash(data: spki)
            .map { String(format: "%02x", $0) }.joined()
        let presence = try randomBytes(16)
            .map { String(format: "%02x", $0) }.joined()
        let token = InvitationCodec.toUnpaddedBase64Url(try randomBytes(32))
        let endpoints = addresses.prefix(4).map {
            LanEndpoint(address: $0, port: Int(listener.boundPort))
        }

        let invitation = LanInvitation(
            invitationId: UUID().uuidString.lowercased(),
            presence: presence,
            name: deviceName.trimmingCharacters(in: .whitespacesAndNewlines),
            platform: platform,
            endpoints: endpoints,
            spkiSha256: fingerprint,
            token: token,
            expiresAt: String(Int64(Date().timeIntervalSince1970) + 120),
            ttlSeconds: 120
        )
        let published = try authority.publish(invitation)
        return (published, try InvitationCodec.encode(published))
    }

    private static func randomBytes(_ count: Int) throws -> Data {
        var data = Data(count: count)
        let result = data.withUnsafeMutableBytes { bytes in
            SecRandomCopyBytes(kSecRandomDefault, count, bytes.baseAddress!)
        }
        guard result == errSecSuccess else { throw AppleInvitationError.randomGenerationFailed }
        return data
    }

    private static func localIPv4Addresses() -> [String] {
        var first: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&first) == 0, let first else { return [] }
        defer { freeifaddrs(first) }

        var result: [String] = []
        var seen = Set<String>()
        var current: UnsafeMutablePointer<ifaddrs>? = first
        while let entry = current {
            let interface = entry.pointee
            defer { current = interface.ifa_next }
            guard (interface.ifa_flags & UInt32(IFF_UP)) != 0,
                  (interface.ifa_flags & UInt32(IFF_LOOPBACK)) == 0,
                  let address = interface.ifa_addr,
                  address.pointee.sa_family == UInt8(AF_INET) else { continue }

            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let status = getnameinfo(address, socklen_t(address.pointee.sa_len),
                                     &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            guard status == 0 else { continue }
            let ip = String(cString: host)
            guard ip != "0.0.0.0", !ip.hasPrefix("169.254."),
                  !ip.hasPrefix("127."), seen.insert(ip).inserted else { continue }
            result.append(ip)
        }
        if !result.contains("127.0.0.1") {
            // Include loopback for local test environments, simulators, and CI runners
            result.append("127.0.0.1")
        }
        return result
    }
}
