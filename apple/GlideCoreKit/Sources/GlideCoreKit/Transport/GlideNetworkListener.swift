import Foundation
import Network
import Security

public enum GlideNetworkListenerError: Error {
    case missingTlsIdentity
}

/// Supervises continuous or on-demand TLS 1.3 socket listeners on Apple platforms.
/// Integrates Bonjour advertising (_glide._tcp) and connection dispatching.
public final class GlideNetworkListener: @unchecked Sendable {
    public private(set) var boundPort: UInt16 = 0
    public private(set) var isListening: Bool = false

    private var listener: NWListener?
    private let queue = DispatchQueue(label: "dev.glide.network.listener", qos: .userInitiated)
    private var onConnectionHandler: ((NWConnection) -> Void)?

    public init() {}

    /// Starts the TLS 1.3 listener on the preferred port, optionally advertising via Bonjour.
    public func start(
        port: UInt16 = NetworkTransportConfig.defaultPort,
        bonjourName: String? = nil,
        spkiSha256Hex: String? = nil,
        presenceTokenHex: String? = nil,
        tlsIdentity: SecIdentity? = nil,
        onConnection: @escaping (NWConnection) -> Void
    ) throws {
        stop()

        guard let tlsIdentity else { throw GlideNetworkListenerError.missingTlsIdentity }

        self.onConnectionHandler = onConnection

        let parameters = try GlideTlsParameters.createServerParameters(identity: tlsIdentity)
        let endpointPort = NWEndpoint.Port(rawValue: port) ?? NWEndpoint.Port(rawValue: 0)!

        let listener = try NWListener(using: parameters, on: endpointPort)

        if let name = bonjourName,
           let spki = spkiSha256Hex,
           let presence = presenceTokenHex {
            let txtDict = BonjourService.buildTxtRecord(
                name: name,
                spkiSha256Hex: spki,
                presenceTokenHex: presence
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
            switch state {
            case .ready:
                self.isListening = true
                self.boundPort = listener.port?.rawValue ?? 0
            case .failed, .cancelled:
                self.isListening = false
            default:
                break
            }
        }

        listener.newConnectionHandler = { [weak self] connection in
            guard let self = self else { return }
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
    }

    deinit {
        stop()
    }
}
