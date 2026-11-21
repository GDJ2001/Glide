import Foundation

/// Bonjour supplies routing hints. A discovered name is never treated as an identity;
/// the trusted session authenticates the selected saved key before offering files.
public final class GlidePeerBrowser: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    private let browser = NetServiceBrowser()
    private var services = Set<NetService>()
    private var peers: [NetService: BonjourPeer] = [:]
    private let onChange: ([BonjourPeer]) -> Void

    public init(onChange: @escaping ([BonjourPeer]) -> Void) {
        self.onChange = onChange
        super.init()
        browser.delegate = self
    }

    public func start() {
        stop()
        browser.searchForServices(ofType: "_glide._tcp.", inDomain: "local.")
    }

    public func stop() {
        browser.stop()
        for service in services { service.stop() }
        services.removeAll()
        peers.removeAll()
        onChange([])
    }

    public func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService,
                                  moreComing: Bool) {
        services.insert(service)
        service.delegate = self
        service.resolve(withTimeout: 4)
    }

    public func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService,
                                  moreComing: Bool) {
        services.remove(service)
        peers.removeValue(forKey: service)
        onChange(Array(peers.values))
    }

    public func netServiceDidResolveAddress(_ service: NetService) {
        guard let rawHost = service.hostName, service.port > 0,
              let txt = service.txtRecordData() else { return }
        let host = rawHost.trimmingCharacters(in: CharacterSet(charactersIn: "."))
        let values = NetService.dictionary(fromTXTRecord: txt).compactMapValues {
            String(data: $0, encoding: .utf8)
        }
        guard let peer = try? BonjourService.parseTxtRecord(
            values, endpoint: "\(host):\(service.port)") else { return }
        peers[service] = peer
        onChange(Array(peers.values))
    }

    public func netService(_ service: NetService, didNotResolve errorDict: [String: NSNumber]) {
        peers.removeValue(forKey: service)
        onChange(Array(peers.values))
    }
}
