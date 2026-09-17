import Foundation

/// Represents the operational state of the macOS menu bar companion.
public enum MacCompanionStatus: Equatable, Sendable {
    case idle
    case advertising(port: Int)
    case transferring(peerName: String, progress: Double, bytesTransferred: Int64, totalBytes: Int64)
    case paused
    case error(message: String)
}

/// Action identifiers triggered from macOS status menu items.
public enum MacMenuAction: Equatable, Sendable {
    case openDownloads
    case sendFiles
    case pauseReceiving
    case resumeReceiving
    case showHistory
    case toggleLaunchAtLogin
    case selectPeer(spkiFingerprint: String)
    case quit
}

/// Represents a rendered menu item for NSStatusItem contextual menu.
public struct MacMenuItem: Equatable, Sendable {
    public let id: String
    public let title: String
    public let isEnabled: Bool
    public let isSeparator: Bool
    public let action: MacMenuAction?

    public init(id: String, title: String, isEnabled: Bool = true, isSeparator: Bool = false, action: MacMenuAction? = nil) {
        self.id = id
        self.title = title
        self.isEnabled = isEnabled
        self.isSeparator = isSeparator
        self.action = action
    }

    public static var separator: MacMenuItem {
        MacMenuItem(id: UUID().uuidString, title: "", isEnabled: false, isSeparator: true, action: nil)
    }
}

/// Abstraction for the continuous LAN listener on macOS (port 42424).
public protocol IMacNetworkListenerProvider: Sendable {
    func start(port: Int) throws
    func stop()
    var isListening: Bool { get }
}

/// Default provider implementation for testing and headless environments.
public final class DefaultMacNetworkListenerProvider: IMacNetworkListenerProvider, @unchecked Sendable {
    private let lock = NSLock()
    private var _isListening: Bool = false

    public var isListening: Bool {
        lock.lock()
        defer { lock.unlock() }
        return _isListening
    }

    public init() {}

    public func start(port: Int) throws {
        lock.lock()
        defer { lock.unlock() }
        _isListening = true
    }

    public func stop() {
        lock.lock()
        defer { lock.unlock() }
        _isListening = false
    }
}

/// Coordinates macOS menu bar desktop companion lifecycle, NSStatusItem status,
/// continuous LAN listener on port 42424, and Bonjour presence synchronization.
/// Conforms to Section 6.2 of APPLE_PLATFORMS_SPECIFICATION.md (LSUIElement = true).
public final class MacCompanionCoordinator: @unchecked Sendable {
    public static let defaultPort: Int = 42424
    public static let bundleIdentifier: String = "dev.glide.desktop"

    private let listenerProvider: IMacNetworkListenerProvider
    private let lock = NSLock()

    private var _status: MacCompanionStatus = .idle
    private var _port: Int
    private var _openAtLogin: Bool = false
    private var _downloadsUrl: URL

    public var status: MacCompanionStatus {
        lock.lock()
        defer { lock.unlock() }
        return _status
    }

    public var port: Int {
        lock.lock()
        defer { lock.unlock() }
        return _port
    }

    public var isListening: Bool {
        return listenerProvider.isListening
    }

    public var openAtLogin: Bool {
        lock.lock()
        defer { lock.unlock() }
        return _openAtLogin
    }

    public var downloadsDirectoryUrl: URL {
        lock.lock()
        defer { lock.unlock() }
        return _downloadsUrl
    }

    public init(
        listenerProvider: IMacNetworkListenerProvider? = nil,
        port: Int = defaultPort,
        downloadsUrl: URL? = nil
    ) {
        self.listenerProvider = listenerProvider ?? DefaultMacNetworkListenerProvider()
        self._port = port
        self._downloadsUrl = downloadsUrl ?? (
            FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Downloads")
        )
    }

    /// Starts the continuous LAN listener on the configured port and sets state to advertising.
    public func startListener() throws {
        lock.lock()
        let targetPort = _port
        lock.unlock()

        try listenerProvider.start(port: targetPort)

        lock.lock()
        _status = .advertising(port: targetPort)
        lock.unlock()
    }

    /// Stops the continuous LAN listener and pauses receiving.
    public func stopListener() {
        listenerProvider.stop()

        lock.lock()
        _status = .paused
        lock.unlock()
    }

    /// Resets companion state back to idle.
    public func resetToIdle() {
        listenerProvider.stop()

        lock.lock()
        _status = .idle
        lock.unlock()
    }

    /// Sets transfer progress state for active incoming or outgoing transfer.
    public func setTransferring(peerName: String, bytesTransferred: Int64, totalBytes: Int64) {
        let fraction = totalBytes > 0 ? min(1.0, max(0.0, Double(bytesTransferred) / Double(totalBytes))) : 0.0
        lock.lock()
        _status = .transferring(
            peerName: peerName,
            progress: fraction,
            bytesTransferred: bytesTransferred,
            totalBytes: totalBytes
        )
        lock.unlock()
    }

    /// Signals that active transfer completed successfully, resuming advertising state.
    public func completeTransfer() {
        lock.lock()
        let currentPort = _port
        let listening = listenerProvider.isListening
        _status = listening ? .advertising(port: currentPort) : .idle
        lock.unlock()
    }

    /// Signals an error in the transfer or transport engine.
    public func setError(message: String) {
        lock.lock()
        _status = .error(message: message)
        lock.unlock()
    }

    /// Toggles or updates the open-at-login preference.
    public func setOpenAtLogin(_ enabled: Bool) {
        lock.lock()
        defer { lock.unlock() }
        _openAtLogin = enabled
    }

    /// Sets custom downloads target directory.
    public func setDownloadsDirectoryUrl(_ url: URL) {
        lock.lock()
        defer { lock.unlock() }
        _downloadsUrl = url
    }

    /// Derives the macOS menu bar icon status title string.
    public var statusItemTitle: String {
        switch status {
        case .idle:
            return "Glide"
        case .advertising:
            return "Glide"
        case .transferring(_, let progress, _, _):
            let pct = Int(progress * 100)
            return "Glide (\(pct)%)"
        case .paused:
            return "Glide (Paused)"
        case .error:
            return "Glide (!)"
        }
    }

    /// Derives the macOS menu bar status item tooltip string.
    public var statusItemToolTip: String {
        switch status {
        case .idle:
            return "Glide: Idle"
        case .advertising(let p):
            return "Glide: Listening on port \(p)"
        case .transferring(let peer, let progress, let transferred, let total):
            let pct = Int(progress * 100)
            return "Glide: Transferring with \(peer) (\(pct)% - \(transferred)/\(total) bytes)"
        case .paused:
            return "Glide: Receiving Paused"
        case .error(let msg):
            return "Glide Error: \(msg)"
        }
    }

    /// Generates structured menu items for the NSStatusItem dropdown.
    public func generateMenuItems(discoveredPeers: [BonjourPeer] = []) -> [MacMenuItem] {
        var items: [MacMenuItem] = []

        // 1. Header status
        switch status {
        case .idle:
            items.append(MacMenuItem(id: "status", title: "Glide: Ready", isEnabled: false))
        case .advertising(let p):
            items.append(MacMenuItem(id: "status", title: "Glide: Listening on :\(p)", isEnabled: false))
        case .transferring(let peer, let progress, _, _):
            let pct = Int(progress * 100)
            items.append(MacMenuItem(id: "status", title: "Transferring with \(peer) (\(pct)%)", isEnabled: false))
        case .paused:
            items.append(MacMenuItem(id: "status", title: "Glide: Receiving Paused", isEnabled: false))
        case .error(let msg):
            items.append(MacMenuItem(id: "status", title: "Error: \(msg)", isEnabled: false))
        }

        items.append(MacMenuItem.separator)

        // 2. Peer discovery section
        if discoveredPeers.isEmpty {
            items.append(MacMenuItem(id: "no_peers", title: "No Nearby Devices Discovered", isEnabled: false))
        } else {
            items.append(MacMenuItem(id: "peers_header", title: "Nearby Devices (\(discoveredPeers.count)):", isEnabled: false))
            for peer in discoveredPeers {
                let presenceSuffix = !peer.presenceToken.isEmpty ? " (Ready)" : ""
                let title = "  • \(peer.name)\(presenceSuffix)"
                items.append(MacMenuItem(
                    id: "peer_\(peer.spkiSha256)",
                    title: title,
                    isEnabled: true,
                    action: .selectPeer(spkiFingerprint: peer.spkiSha256)
                ))
            }
        }

        items.append(MacMenuItem.separator)

        // 3. Actions
        items.append(MacMenuItem(id: "send_files", title: "Send Files...", isEnabled: true, action: .sendFiles))
        items.append(MacMenuItem(id: "open_downloads", title: "Open Downloads Folder", isEnabled: true, action: .openDownloads))
        items.append(MacMenuItem(id: "show_history", title: "Transfer History...", isEnabled: true, action: .showHistory))

        items.append(MacMenuItem.separator)

        // 4. State control
        if isListening {
            items.append(MacMenuItem(id: "pause_rec", title: "Pause Receiving", isEnabled: true, action: .pauseReceiving))
        } else {
            items.append(MacMenuItem(id: "resume_rec", title: "Resume Receiving", isEnabled: true, action: .resumeReceiving))
        }

        let loginTitle = openAtLogin ? "✓ Launch at Login" : "  Launch at Login"
        items.append(MacMenuItem(id: "launch_at_login", title: loginTitle, isEnabled: true, action: .toggleLaunchAtLogin))

        items.append(MacMenuItem.separator)

        // 5. Termination
        items.append(MacMenuItem(id: "quit", title: "Quit Glide", isEnabled: true, action: .quit))

        return items
    }
}
