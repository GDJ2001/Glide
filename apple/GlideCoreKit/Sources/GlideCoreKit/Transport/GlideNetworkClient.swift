import Foundation
import Network

public enum NetworkClientError: Error, Equatable, Sendable {
    case connectionFailed(String)
    case connectionTimedOut
    case invalidHost(String)
    case invalidPort(UInt16)
}

/// Outbound TLS 1.3 client connection factory with certificate pinning.
public final class GlideNetworkClient: Sendable {
    public init() {}

    /// Connects to a remote peer endpoint over TLS 1.3, pinning the peer's certificate SPKI hash.
    public func connect(
        host: String,
        port: UInt16,
        expectedSpkiSha256Hex: String,
        timeoutSeconds: TimeInterval = 10.0
    ) async throws -> LanRecordChannel {
        guard let endpointPort = NWEndpoint.Port(rawValue: port) else {
            throw NetworkClientError.invalidPort(port)
        }
        let endpointHost = NWEndpoint.Host(host)

        let parameters = GlideTlsParameters.createClientParameters(expectedSpkiSha256: expectedSpkiSha256Hex)
        let connection = NWConnection(host: endpointHost, port: endpointPort, using: parameters)

        let queue = DispatchQueue(label: "dev.glide.network.client", qos: .userInitiated)

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let hasResumed = ManagedAtomicBool(false)

            connection.stateUpdateHandler = { state in
                print("DEBUG: [ClientConnection] state=\(state)")
                switch state {
                case .ready:
                    if hasResumed.compareAndSwap(expected: false, new: true) {
                        continuation.resume(returning: ())
                    }
                case .failed(let error):
                    if hasResumed.compareAndSwap(expected: false, new: true) {
                        continuation.resume(throwing: NetworkClientError.connectionFailed(error.localizedDescription))
                    }
                case .cancelled:
                    if hasResumed.compareAndSwap(expected: false, new: true) {
                        continuation.resume(throwing: NetworkClientError.connectionFailed("Connection cancelled"))
                    }
                default:
                    break
                }
            }

            connection.start(queue: queue)

            // Timeout watchdog
            queue.asyncAfter(deadline: .now() + timeoutSeconds) {
                if hasResumed.compareAndSwap(expected: false, new: true) {
                    connection.cancel()
                    continuation.resume(throwing: NetworkClientError.connectionTimedOut)
                }
            }
        }

        return LanRecordChannel(connection: connection)
    }
}

/// Lightweight thread-safe boolean for one-shot continuations.
private final class ManagedAtomicBool: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Bool

    init(_ initial: Bool) {
        self.value = initial
    }

    func compareAndSwap(expected: Bool, new: Bool) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if value == expected {
            value = new
            return true
        }
        return false
    }
}
