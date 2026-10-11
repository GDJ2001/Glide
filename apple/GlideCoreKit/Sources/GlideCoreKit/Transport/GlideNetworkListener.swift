import Foundation
import Network
import Security

public enum GlideNetworkListenerError: Error {
    case missingTlsIdentity
    case noBoundPort
}

/// Supervises continuous or on-demand TLS 1.3 socket listeners on Apple platforms.
/// Integrates Bonjour advertising (_glide._tcp) and connection dispatching.
public final class GlideNetworkListener: @unchecked Sendable {
    public private(set) var boundPort: UInt16 = 0
    public private(set) var isListening: Bool = false
    public private(set) var tlsIdentity: SecIdentity?

    private var listener: NWListener?
    private let queue = DispatchQueue(label: "dev.glide.network.listener", qos: .userInitiated)
    private var onConnectionHandler: ((NWConnection) -> Void)?

    public init() {}

    /// Refreshes the public availability record without replacing the active
    /// TLS listener or changing its bound port. No PIN, token, or key is published.
    public func updateAdvertisement(
        name: String, presenceTokenHex: String,
        deviceType: String = BonjourService.deviceTypeDesktop,
        isPairReady: Bool
    ) {
        guard let listener, isListening else { return }
        let values = BonjourService.buildTxtRecord(
            name: name, presenceTokenHex: presenceTokenHex,
            deviceType: deviceType, isPairReady: isPairReady)
        listener.service = NWListener.Service(
            name: name, type: BonjourService.serviceType,
            domain: BonjourService.serviceDomain,
            txtRecord: NWTXTRecord(values))
    }

    /// Starts the TLS 1.3 listener on the preferred port, optionally advertising via Bonjour.
    public func start(
        port: UInt16 = NetworkTransportConfig.defaultPort,
        bonjourName: String? = nil,
        presenceTokenHex: String? = nil,
        deviceType: String = BonjourService.deviceTypeDesktop,
        isPairReady: Bool = false,
        supportsTrustedReconnect: Bool = true,
        tlsIdentity: SecIdentity? = nil,
        onReady: ((UInt16) -> Void)? = nil,
        onFailure: ((Error) -> Void)? = nil,
        onConnection: @escaping (NWConnection) -> Void
    ) throws {
        stop()

        guard let tlsIdentity else { throw GlideNetworkListenerError.missingTlsIdentity }

        self.onConnectionHandler = onConnection
        self.tlsIdentity = tlsIdentity

        let parameters = try GlideTlsParameters.createServerParameters(identity: tlsIdentity)
        let listener: NWListener
        if port == 0 {
            listener = try NWListener(using: parameters)
        } else if let endpointPort = NWEndpoint.Port(rawValue: port) {
            listener = try NWListener(using: parameters, on: endpointPort)
        } else {
            listener = try NWListener(using: parameters)
        }

        if let name = bonjourName,
           let presence = presenceTokenHex {
            let txtDict = BonjourService.buildTxtRecord(
                name: name,
                presenceTokenHex: presence,
                deviceType: deviceType,
                isPairReady: isPairReady,
                supportsTrustedReconnect: supportsTrustedReconnect
            )
            let txtRecord = NWTXTRecord(txtDict)
            listener.service = NWListener.Service(
                name: name,
                type: BonjourService.serviceType,
                domain: BonjourService.serviceDomain,
                txtRecord: txtRecord
            )
        }

        listener.stateUpdateHandler = { [weak self] state in
            guard let self = self else { return }
            guard self.listener === listener else { return }
            switch state {
            case .ready:
                guard let port = listener.port?.rawValue, port > 0 else {
                    self.isListening = false
                    onFailure?(GlideNetworkListenerError.noBoundPort)
                    return
                }
                self.boundPort = port
                self.isListening = true
                onReady?(port)
            case .failed(let error):
                self.isListening = false
                self.boundPort = 0
                onFailure?(error)
            case .waiting(let error):
                self.isListening = false
                self.boundPort = 0
                onFailure?(error)
            case .cancelled:
                self.isListening = false
                self.boundPort = 0
            default:
                break
            }
        }

        listener.newConnectionHandler = { [weak self] connection in
            guard let self = self else { return }
            guard self.listener === listener else { connection.cancel(); return }
            print("DEBUG: [Listener] newConnectionHandler received connection")
            connection.stateUpdateHandler = { state in
                print("DEBUG: [InboundConnection] state=\(state)")
            }
            connection.start(queue: self.queue)
            self.onConnectionHandler?(connection)
        }

        listener.start(queue: queue)
        self.listener = listener
    }

    /// Stops the listener and Bonjour advertising.
    public func stop() {
        listener?.cancel()
        listener = nil
        isListening = false
        boundPort = 0
        tlsIdentity = nil
        onConnectionHandler = nil
    }

    deinit {
        stop()
    }
}
