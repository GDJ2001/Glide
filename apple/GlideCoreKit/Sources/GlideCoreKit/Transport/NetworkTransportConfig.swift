import Foundation

/// Configuration parameters and wire bounds for Apple Network.framework transport.
/// Conforms to Section 3 of APPLE_PLATFORMS_SPECIFICATION.md and ADR-0020.
public struct NetworkTransportConfig: Equatable, Sendable {
    public static let defaultPort: UInt16 = 42424
    public static let serviceType = "_glide._tcp"
    public static let serviceDomain = "local."

    public static let minTlsVersion = "TLSv1.3"
    public static let maxTlsVersion = "TLSv1.3"

    public static let keepaliveIdleSeconds = 10
    public static let enableKeepalive = true
    public static let includePeerToPeer = true

    public static let maxControlPayloadBytes = 16384 // 16 KiB
    public static let maxDataChunkBytes = 65536      // 64 KiB
    public static let maxRecordPayloadBytes = 65536  // 64 KiB

    public let port: UInt16
    public let tlsVersion: String
    public let isPeerToPeerEnabled: Bool
    public let keepaliveIdle: Int

    public init(
        port: UInt16 = defaultPort,
        tlsVersion: String = minTlsVersion,
        isPeerToPeerEnabled: Bool = includePeerToPeer,
        keepaliveIdle: Int = keepaliveIdleSeconds
    ) {
        self.port = port
        self.tlsVersion = tlsVersion
        self.isPeerToPeerEnabled = isPeerToPeerEnabled
        self.keepaliveIdle = keepaliveIdle
    }

    /// Validates that transport configuration meets strict security invariants.
    public func validate() throws {
        guard tlsVersion == Self.minTlsVersion else {
            throw ConfigError.invalidTlsVersion(tlsVersion)
        }
        guard port > 0 else {
            throw ConfigError.invalidPort(port)
        }
    }

    public enum ConfigError: Error, Equatable, Sendable {
        case invalidTlsVersion(String)
        case invalidPort(UInt16)
    }
}
