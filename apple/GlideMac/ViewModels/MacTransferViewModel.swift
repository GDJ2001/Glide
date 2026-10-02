import Foundation
import SwiftUI
import Combine
import Network
import GlideCoreKit

#if os(macOS)
import AppKit
#endif

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
    @Published public var selectedSidebarTab: String = "send"
    @Published public var stagedUrls: [URL] = []
    @Published public var stagedSize: Int64 = 0
    @Published public var invitationInput: String = ""
    @Published public var currentInvitationUri: String = ""
    @Published public var isListening: Bool = false
    @Published public var listeningPort: UInt16 = 42424

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
        generateLocalInvitation()
    }

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
                    peerName: "Peer Device",
                    fileCount: sent,
                    totalBytes: stagedSize,
                    status: "Completed",
                    destinationUrl: nil
                ), at: 0)

                clearStaged()
            } catch {
                transferStatusText = "Send failed: \(error.localizedDescription)"
                historyItems.insert(MacHistoryItem(
                    id: UUID().uuidString,
                    timestamp: Date(),
                    direction: "Sent",
                    peerName: "Peer Device",
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
