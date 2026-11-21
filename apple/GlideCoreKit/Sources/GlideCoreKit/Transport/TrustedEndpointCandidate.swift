import Foundation

public struct TrustedEndpointCandidate: Equatable, Sendable {
    public let endpoint: LanEndpoint
    public let presenceId: String?

    public init(endpoint: LanEndpoint, presenceId: String? = nil) {
        self.endpoint = endpoint
        self.presenceId = presenceId
    }
}

/// Discovery only supplies candidate routes. Authentication in the transfer engine
/// decides whether any route belongs to the selected saved identity.
public enum TrustedEndpointResolver {
    public static let maximumCandidates = 8

    public static func candidates(for peer: PersistentTrustedPeer,
                                  discovered: [BonjourPeer],
                                  manualAddress: String = "") -> [TrustedEndpointCandidate] {
        var result: [TrustedEndpointCandidate] = []
        var seen = Set<String>()
        let eligible = discovered.filter(\.supportsTrustedReconnect)
        let ordered = eligible.filter { $0.name == peer.displayName } +
            eligible.filter { $0.name != peer.displayName }
        for item in ordered {
            guard let endpoint = parse(item.endpoint), seen.insert(item.endpoint).inserted else { continue }
            result.append(TrustedEndpointCandidate(endpoint: endpoint, presenceId: item.presenceToken))
            if result.count == maximumCandidates { return result }
        }
        if let endpoint = parse(manualAddress), seen.insert(manualAddress).inserted,
           result.count < maximumCandidates {
            result.append(TrustedEndpointCandidate(endpoint: endpoint))
        }
        return result
    }

    public static func parse(_ raw: String) -> LanEndpoint? {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty, value.count <= 255,
              let separator = value.lastIndex(of: ":") else { return nil }
        let host = String(value[..<separator])
        let portText = String(value[value.index(after: separator)...])
        guard !host.isEmpty, host.utf8.allSatisfy({ $0 >= 33 && $0 <= 126 }),
              !host.contains("/"), !host.contains("@"), !host.contains("#"),
              isLocalHost(host),
              let port = UInt16(portText), port > 0 else { return nil }
        return LanEndpoint(address: host, port: Int(port))
    }

    private static func isLocalHost(_ host: String) -> Bool {
        let lower = host.lowercased()
        if lower == "localhost" || lower.hasSuffix(".local") { return true }
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        let octets = parts.compactMap { UInt8($0) }
        guard octets.count == 4 else { return false }
        return octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 172 && (16...31).contains(octets[1])) ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 169 && octets[1] == 254)
    }
}
