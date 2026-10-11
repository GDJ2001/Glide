import Foundation
import SwiftUI
import Combine
import Network
import GlideCoreKit

#if os(macOS)
import AppKit
#endif

public enum MacSendMethod: String, CaseIterable, Sendable {
    case quickShareQr = "Quick share with QR"
    case pairedDevice = "Paired device"
}

public enum MacPairingPhase: Equatable, Sendable {
    case idle
    case scanning
    case peersFound
    case empty
    case targetReady
    case initiatorPinEntry
    case manualEntry
    case useLink
    case targetApproval
    case verifying
    case success
    case failed
}

public struct MacHistoryItem: Identifiable, Sendable {
    public let id: String
    public let timestamp: Date
    public let direction: String
    public let peerName: String
    public let fileCount: Int
    public let totalBytes: Int64
    public let status: String
    public let destinationUrl: URL?

    public var formattedSize: String {
        ByteCountFormatter.string(fromByteCount: totalBytes, countStyle: .file)
    }

    public var formattedDate: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: timestamp)
    }
}

@MainActor
public final class MacTransferViewModel: ObservableObject {
    @Published public var selectedSidebarTab: String = "send" {
        didSet {
            if oldValue == "pair" && selectedSidebarTab != "pair" {
                cancelPairing()
            }
        }
    }
    @Published public var stagedUrls: [URL] = []
    @Published public var stagedSize: Int64 = 0
    @Published public var sendMethod: MacSendMethod = .quickShareQr
    @Published public var invitationInput: String = ""
    @Published public var currentInvitationUri: String = ""
    @Published public var isListening: Bool = false
    @Published public var listeningPort: UInt16 = 42424

    // Trusted peers & discovery
    @Published public var trustedPeers: [PersistentTrustedPeer] = []
    @Published public var selectedTrustedPeer: PersistentTrustedPeer?
    @Published public var selectedPeerEndpoint: String = ""
    @Published public var discoveredPairPeers: [BonjourPeer] = []
    @Published public var selectedPairPeer: BonjourPeer?

    // Pair-only state machine
    @Published public var pairingPhase: MacPairingPhase = .idle
    @Published public var pairingCodeDisplay: String = ""
    @Published public var pairingExpirySeconds: Int = 0
    @Published public var pairingQrUri: String = ""
    @Published public var isPairQrVisible: Bool = false
    @Published public var pairingPeerName: String = ""
    @Published public var pairingPinInput: String = ""
    @Published public var pairingManualEndpointInput: String = ""
    @Published public var pairingLinkInput: String = ""
    @Published public var pairingMessage: String = ""
    @Published public var pairingDiagnostic: String = ""
    @Published public var pairingRecoveryAction: String = "Try again"

    private var activePairWindow: PairReadyWindowAuthority?
    private var pairTimer: Timer?
    private var pairApprovalContinuation: CheckedContinuation<Bool, Never>?

    // Active transfer
    @Published public var isTransferActive: Bool = false
    @Published public var transferProgress: Double = 0.0
    @Published public var transferStatusText: String = "Ready"
    @Published public var transferredBytesText: String = ""
    @Published public var currentFileName: String = ""

    // SAS Mutual Authentication
    @Published public var isSasConfirmationVisible: Bool = false
    @Published public var sasCode: String = ""
    @Published public var sasPeerName: String = ""
    private var sasContinuation: CheckedContinuation<Bool, Never>?

    // Incoming offer
    @Published public var isIncomingOfferVisible: Bool = false
    @Published public var incomingOfferPeer: String = ""
    @Published public var incomingOfferSummary: String = ""
    private var offerContinuation: CheckedContinuation<Bool, Never>?

    // Settings
    @Published public var deviceName: String = Host.current().localizedName ?? "Mac"
    @Published public var downloadDirectory: URL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]

    // History
    @Published public var historyItems: [MacHistoryItem] = []

    private let engine = GlideTransferEngine.shared
    private let listener = GlideNetworkListener()
    private let authority = LanInvitationAuthority()

    public init() {
        refreshTrustedPeers()
        generateLocalInvitation()
    }

    public var pairingExpiryText: String {
        guard pairingExpirySeconds > 0 else { return "" }
        return String(format: "Expires in %d:%02d", pairingExpirySeconds / 60, pairingExpirySeconds % 60)
    }

    public func refreshTrustedPeers() {
        engine.trustStore.load()
        trustedPeers = engine.trustStore.allPeers().sorted { $0.lastSeenAtUnixMs > $1.lastSeenAtUnixMs }
        if selectedTrustedPeer == nil {
            selectedTrustedPeer = trustedPeers.first
        }
    }

    public func revokeTrustedPeer(_ peer: PersistentTrustedPeer) {
        try? engine.trustStore.revokePeer(fingerprintHex: peer.fingerprintHex)
        refreshTrustedPeers()
    }

    // MARK: - Pair-only actions

    public func beginFindDevices() {
        cancelPairWindowOnly()
        selectedPairPeer = nil
        pairingPhase = .scanning
        pairingMessage = "Looking for Glide devices…"
        pairingDiagnostic = ""
        pairingRecoveryAction = "Try again"

        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard self.pairingPhase == .scanning else { return }
            if self.discoveredPairPeers.isEmpty {
                self.pairingPhase = .empty
                self.pairingMessage = "Open Glide on the other computer and choose Ready to pair."
            } else {
                self.pairingPhase = .peersFound
                self.pairingMessage = ""
            }
        }
    }

    public func startReadyToPair() {
        cancelPairWindowOnly()
        selectedPairPeer = nil
        isPairQrVisible = false

        do {
            if !listener.isListening {
                generateLocalInvitation()
            }
            let identity = try KeychainIdentityManager.shared.getOrCreateTlsIdentity()
            let spkiHex = try AppleCertificateSpki.extractSpkiSha256Hex(from: identity)
            let endpoints = AppleInvitationManager.localEndpoints(port: listeningPort)
            var presenceBytes = [UInt8](repeating: 0, count: 16)
            for i in 0..<16 { presenceBytes[i] = UInt8.random(in: 0...255) }
            let presenceHex = presenceBytes.map { String(format: "%02x", $0) }.joined()

            let window = try PairReadyWindowAuthority(
                presenceId: presenceHex,
                deviceName: deviceName,
                platform: "macos",
                endpoints: endpoints.isEmpty ? [LanEndpoint(address: "192.168.1.10", port: Int(listeningPort))] : endpoints,
                serverSpkiSha256Hex: spkiHex
            )
            activePairWindow = window
            pairingCodeDisplay = window.formattedPinDisplay
            pairingExpirySeconds = Profile2Constants.defaultPairWindowSeconds
            pairingQrUri = window.qrInvitationUri
            pairingMessage = "Enter this code on the other computer."
            pairingDiagnostic = window.qrInvitation.endpoints.map { "\($0.address):\($0.port)" }.joined(separator: ", ")
            pairingPhase = .targetReady

            pairTimer?.invalidate()
            pairTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
                Task { @MainActor in
                    self?.tickPairWindow()
                }
            }
        } catch {
            failPairing(
                message: "Couldn’t reach this device — check the local network",
                diagnostic: error.localizedDescription,
                action: "Try again"
            )
        }
    }

    private func tickPairWindow() {
        guard let window = activePairWindow else { return }
        if !window.isActive() {
            cancelPairWindowOnly()
            failPairing(
                message: "Code expired — get a new code",
                diagnostic: "Ready window expired or failed attempt limit reached.",
                action: "Get a new code"
            )
            return
        }
        let remaining = max(0, Int(window.expiresAt.timeIntervalSinceNow.rounded(.up)))
        pairingExpirySeconds = remaining
    }

    public func selectPairPeer(_ peer: BonjourPeer) {
        selectedPairPeer = peer
        pairingPeerName = peer.name
        pairingPinInput = ""
        pairingPhase = .initiatorPinEntry
        pairingMessage = ""
        pairingDiagnostic = peer.endpoint
    }

    public func showManualPairEntry() {
        cancelPairWindowOnly()
        selectedPairPeer = nil
        pairingPinInput = ""
        pairingPhase = .manualEntry
        pairingMessage = ""
        pairingDiagnostic = ""
    }

    public func showPairLinkEntry() {
        cancelPairWindowOnly()
        selectedPairPeer = nil
        pairingLinkInput = ""
        pairingPhase = .useLink
        pairingMessage = ""
        pairingDiagnostic = ""
    }

    public func submitInitiatorPinPairing() {
        let cleanPin = pairingPinInput.filter { $0.isNumber }
        guard cleanPin.count == 6 else { return }
        let endpoint = selectedPairPeer?.endpoint ?? pairingManualEndpointInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !endpoint.isEmpty else {
            failPairing(
                message: "Couldn’t reach this device — check the local network",
                diagnostic: "Enter a reachable local-network IPv4 address and port.",
                action: "Try again"
            )
            return
        }
        pairingPhase = .verifying
        pairingMessage = "Verifying…"
    }

    public func submitPairLink() {
        let trimmed = pairingLinkInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        do {
            let parsed = try InvitationCodec.decodeAny(trimmed)
            guard case .pairOnlyV2(let inv) = parsed else {
                failPairing(
                    message: "This link sends files — open Receive to use it",
                    diagnostic: "Link purpose is not pair-only.",
                    action: "Try again"
                )
                return
            }
            pairingPeerName = inv.name
            pairingPhase = .verifying
            pairingMessage = "Verifying…"
        } catch {
            failPairing(
                message: "Code expired — get a new code",
                diagnostic: error.localizedDescription,
                action: "Get a new code"
            )
        }
    }

    public func approveIncomingPair(accept: Bool) {
        if accept {
            pairingPhase = .verifying
            pairingMessage = "Verifying…"
        }
        pairApprovalContinuation?.resume(returning: accept)
        pairApprovalContinuation = nil
    }

    public func executePairRecovery() {
        if pairingRecoveryAction == "Get a new code" {
            startReadyToPair()
        } else {
            beginFindDevices()
        }
    }

    public func cancelPairing() {
        cancelPairWindowOnly()
        pairApprovalContinuation?.resume(returning: false)
        pairApprovalContinuation = nil
        selectedPairPeer = nil
        isPairQrVisible = false
        pairingPhase = .idle
        pairingMessage = ""
        pairingDiagnostic = ""
    }

    private func cancelPairWindowOnly() {
        pairTimer?.invalidate()
        pairTimer = nil
        activePairWindow?.cancel()
        activePairWindow = nil
    }

    private func failPairing(message: String, diagnostic: String, action: String) {
        pairingPhase = .failed
        pairingMessage = message
        pairingDiagnostic = diagnostic
        pairingRecoveryAction = action
    }

    // MARK: - Staging & Transfer

    public func stageUrls(_ urls: [URL]) {
        for url in urls {
            if !stagedUrls.contains(url) {
                stagedUrls.append(url)
            }
        }
        recalculateStagedSize()
    }

    public func removeStagedUrl(_ url: URL) {
        stagedUrls.removeAll { $0 == url }
        recalculateStagedSize()
    }

    public func clearStaged() {
        stagedUrls.removeAll()
        stagedSize = 0
    }

    private func recalculateStagedSize() {
        var total: Int64 = 0
        for url in stagedUrls {
            if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
               let size = attrs[.size] as? NSNumber {
                total += size.int64Value
            }
        }
        stagedSize = total
    }

    public func generateLocalInvitation() {
        do {
            let identity = try KeychainIdentityManager.shared.getOrCreateTlsIdentity()
            if !listener.isListening {
                try listener.start(
                    port: 0,
                    tlsIdentity: identity,
                    onReady: { [weak self] port in
                        Task { @MainActor in
                            guard let self = self else { return }
                            self.listeningPort = port
                            self.isListening = true
                            do {
                                let (_, uri) = try AppleInvitationManager.publish(
                                    listener: self.listener,
                                    authority: self.authority,
                                    deviceName: self.deviceName,
                                    platform: "macos"
                                )
                                self.currentInvitationUri = uri
                                self.transferStatusText = "Ready to receive"
                            } catch {
                                self.transferStatusText = "Could not publish invitation: \(error.localizedDescription)"
                            }
                        }
                    },
                    onFailure: { [weak self] error in
                        Task { @MainActor in
                            self?.isListening = false
                            self?.transferStatusText = "Listener error: \(error.localizedDescription)"
                        }
                    },
                    onConnection: { [weak self] connection in
                        Task { @MainActor in
                            self?.handleIncomingConnection(connection)
                        }
                    }
                )
            } else {
                let (_, uri) = try AppleInvitationManager.publish(
                    listener: listener,
                    authority: authority,
                    deviceName: deviceName,
                    platform: "macos"
                )
                currentInvitationUri = uri
                transferStatusText = "Ready to receive"
            }
        } catch {
            currentInvitationUri = ""
            transferStatusText = "Receiving setup unavailable: \(error.localizedDescription)"
        }
    }

    private func handleIncomingConnection(_ connection: NWConnection) {
        guard !isTransferActive else {
            connection.cancel()
            return
        }
        isTransferActive = true
        transferProgress = 0.0
        transferStatusText = "Receiving from connected peer..."
        updateDockBadge(text: "↓")

        Task {
            do {
                let count = try await engine.receiveInboundConnection(
                    connection: connection,
                    authority: authority,
                    destinationDirectory: downloadDirectory,
                    localDeviceName: deviceName,
                    onConsent: { [weak self] peer, count, totalBytes in
                        guard let self = self else { return false }
                        return await self.requestOfferConsent(peer: peer, count: count, totalBytes: totalBytes)
                    },
                    onProgress: { [weak self] progress, bytesDone, totalBytes, filename in
                        Task { @MainActor in
                            self?.transferProgress = progress
                            self?.currentFileName = filename
                            self?.transferredBytesText = "\(ByteCountFormatter.string(fromByteCount: bytesDone, countStyle: .file)) / \(ByteCountFormatter.string(fromByteCount: totalBytes, countStyle: .file))"
                        }
                    }
                )
                transferStatusText = "Received \(count) file(s)!"
                historyItems.insert(MacHistoryItem(
                    id: UUID().uuidString,
                    timestamp: Date(),
                    direction: "Received",
                    peerName: "Peer Device",
                    fileCount: count,
                    totalBytes: 0,
                    status: "Completed",
                    destinationUrl: downloadDirectory
                ), at: 0)
            } catch {
                transferStatusText = "Receive failed: \(error.localizedDescription)"
            }
            isTransferActive = false
            updateDockBadge(text: nil)
            generateLocalInvitation()
        }
    }

    public func sendStagedFiles() {
        guard !stagedUrls.isEmpty else { return }

        if sendMethod == .pairedDevice {
            sendToPairedDevice()
            return
        }

        guard !invitationInput.isEmpty else {
            transferStatusText = "Please enter peer invitation URI"
            return
        }

        let invitation: LanInvitation
        do {
            invitation = try InvitationCodec.decode(invitationInput.trimmingCharacters(in: .whitespacesAndNewlines))
        } catch {
            transferStatusText = "Invalid invitation: \(error.localizedDescription)"
            return
        }

        isTransferActive = true
        transferProgress = 0.0
        transferStatusText = "Connecting..."
        updateDockBadge(text: "↑")

        Task {
            do {
                let sent = try await engine.sendFiles(
                    fileUrls: stagedUrls,
                    to: invitation,
                    localDeviceName: deviceName
                ) { [weak self] progress, bytesDone, totalBytes, filename in
                    Task { @MainActor in
                        self?.transferProgress = progress
                        self?.currentFileName = filename
                        self?.transferredBytesText = "\(ByteCountFormatter.string(fromByteCount: bytesDone, countStyle: .file)) / \(ByteCountFormatter.string(fromByteCount: totalBytes, countStyle: .file))"
                    }
                }

                transferStatusText = "Sent \(sent) file(s) successfully!"
                historyItems.insert(MacHistoryItem(
                    id: UUID().uuidString,
                    timestamp: Date(),
                    direction: "Sent",
                    peerName: invitation.name,
                    fileCount: sent,
                    totalBytes: stagedSize,
                    status: "Completed",
                    destinationUrl: nil
                ), at: 0)

                clearStaged()
            } catch {
                // Preserve stagedUrls on failure
                transferStatusText = "Send failed: \(error.localizedDescription)"
                historyItems.insert(MacHistoryItem(
                    id: UUID().uuidString,
                    timestamp: Date(),
                    direction: "Sent",
                    peerName: invitation.name,
                    fileCount: stagedUrls.count,
                    totalBytes: stagedSize,
                    status: "Failed",
                    destinationUrl: nil
                ), at: 0)
            }

            isTransferActive = false
            updateDockBadge(text: nil)
        }
    }

    public func sendToPairedDevice() {
        guard !stagedUrls.isEmpty, let peer = selectedTrustedPeer else { return }
        let parts = selectedPeerEndpoint.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: ":")
        let host = parts.first.map(String.init) ?? ""
        let port = parts.count > 1 ? (Int(parts[1]) ?? Int(NetworkTransportConfig.defaultPort)) : Int(NetworkTransportConfig.defaultPort)
        guard !host.isEmpty else {
            transferStatusText = "Couldn’t reach this device — check the local network"
            return
        }

        isTransferActive = true
        transferProgress = 0.0
        transferStatusText = "Verifying \(peer.displayName)…"
        updateDockBadge(text: "↑")

        Task {
            do {
                let sent = try await engine.sendFilesToTrustedPeer(
                    fileUrls: stagedUrls,
                    peerFingerprintHex: peer.fingerprintHex,
                    endpoints: [LanEndpoint(address: host, port: port)],
                    localDeviceName: deviceName
                ) { [weak self] progress, bytesDone, totalBytes, filename in
                    Task { @MainActor in
                        self?.transferProgress = progress
                        self?.currentFileName = filename
                        self?.transferredBytesText = "\(ByteCountFormatter.string(fromByteCount: bytesDone, countStyle: .file)) / \(ByteCountFormatter.string(fromByteCount: totalBytes, countStyle: .file))"
                    }
                }

                transferStatusText = "Sent \(sent) file(s) to \(peer.displayName)!"
                historyItems.insert(MacHistoryItem(
                    id: UUID().uuidString,
                    timestamp: Date(),
                    direction: "Sent",
                    peerName: peer.displayName,
                    fileCount: sent,
                    totalBytes: stagedSize,
                    status: "Completed",
                    destinationUrl: nil
                ), at: 0)

                clearStaged()
            } catch TransferError.identityChanged, TransferError.untrustedPeer {
                // Preserve stagedUrls on failure
                transferStatusText = "Identity changed — pair again"
            } catch TransferError.busy {
                transferStatusText = "This device is busy — try again"
            } catch {
                transferStatusText = "Couldn’t reach this device — check the local network"
            }

            isTransferActive = false
            updateDockBadge(text: nil)
        }
    }

    public func receiveFromInvitation() {
        guard !invitationInput.isEmpty else { return }

        let invitation: LanInvitation
        do {
            invitation = try InvitationCodec.decode(invitationInput.trimmingCharacters(in: .whitespacesAndNewlines))
        } catch {
            transferStatusText = "Invalid invitation: \(error.localizedDescription)"
            return
        }

        isTransferActive = true
        transferProgress = 0.0
        transferStatusText = "Receiving from invitation..."
        updateDockBadge(text: "↓")

        Task {
            do {
                let received = try await engine.receiveFromInvitation(
                    invitation: invitation,
                    destinationDirectory: downloadDirectory,
                    onConsent: { [weak self] peer, count, totalBytes in
                        guard let self = self else { return false }
                        return await self.requestOfferConsent(peer: peer, count: count, totalBytes: totalBytes)
                    }
                ) { [weak self] progress, bytesDone, totalBytes, filename in
                    Task { @MainActor in
                        self?.transferProgress = progress
                        self?.currentFileName = filename
                        self?.transferredBytesText = "\(ByteCountFormatter.string(fromByteCount: bytesDone, countStyle: .file)) / \(ByteCountFormatter.string(fromByteCount: totalBytes, countStyle: .file))"
                    }
                }

                transferStatusText = "Received \(received) file(s)!"
                historyItems.insert(MacHistoryItem(
                    id: UUID().uuidString,
                    timestamp: Date(),
                    direction: "Received",
                    peerName: "Invitation Sender",
                    fileCount: received,
                    totalBytes: 0,
                    status: "Completed",
                    destinationUrl: downloadDirectory
                ), at: 0)
            } catch {
                transferStatusText = "Receive failed: \(error.localizedDescription)"
            }

            isTransferActive = false
            updateDockBadge(text: nil)
        }
    }

    private func requestOfferConsent(peer: String, count: Int, totalBytes: Int64) async -> Bool {
        incomingOfferPeer = peer
        incomingOfferSummary = "\(count) file(s), \(ByteCountFormatter.string(fromByteCount: totalBytes, countStyle: .file))"
        isIncomingOfferVisible = true

        return await withCheckedContinuation { continuation in
            offerContinuation = continuation
        }
    }

    public func resolveOffer(accept: Bool) {
        isIncomingOfferVisible = false
        offerContinuation?.resume(returning: accept)
        offerContinuation = nil
    }

    public func resolveSas(accept: Bool) {
        isSasConfirmationVisible = false
        sasContinuation?.resume(returning: accept)
        sasContinuation = nil
    }

    public func toggleListener() {
        if isListening {
            listener.stop()
            isListening = false
            currentInvitationUri = ""
            transferStatusText = "Listener stopped"
        } else {
            generateLocalInvitation()
        }
    }

    public func revealInFinder(url: URL) {
        #if os(macOS)
        NSWorkspace.shared.activateFileViewerSelecting([url])
        #endif
    }

    public func openDownloadsDirectory() {
        #if os(macOS)
        NSWorkspace.shared.open(downloadDirectory)
        #endif
    }

    private func updateDockBadge(text: String?) {
        #if os(macOS)
        NSApp.dockTile.badgeLabel = text
        #endif
    }
}
