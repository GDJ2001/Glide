import Foundation
import SwiftUI
import Combine
import GlideCoreKit

public struct IosHistoryItem: Identifiable, Sendable {
    public let id: String
    public let timestamp: Date
    public let direction: String
    public let peerName: String
    public let fileCount: Int
    public let totalBytes: Int64
    public let status: String

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
public final class IosTransferViewModel: ObservableObject {
    @Published public var selectedTab: String = "send"
    @Published public var stagedUrls: [URL] = []
    @Published public var stagedSize: Int64 = 0
    @Published public private(set) var stagedSizes: [URL: Int64] = [:]
    @Published public private(set) var isStagingFiles = false
    private let stagedFiles = StagedFilesStore()
    private var stagingTask: Task<Void, Never>?
    private var pendingStagingOperations = 0
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

    // Scanner
    @Published public var isScannerPresented: Bool = false

    // Settings
    @Published public var deviceName: String = "iPhone"
    @Published public var saveToPhotos: Bool = true

    // History
    @Published public var historyItems: [IosHistoryItem] = []

    private let engine = GlideTransferEngine.shared
    private let listener = GlideNetworkListener()
    private let lifecycle = IosLifecycleCoordinator()

    public init() {
        generateLocalInvitation()
        importSharedFiles()
    }

    /// The share extension stages files in the common App Group; no transfer starts here.
    public func importSharedFiles() {
        guard !isTransferActive else { return }
        enqueueStaging {
            do {
                let snapshot = try await self.stagedFiles.importSharedFiles()
                let added = snapshot.urls.contains { !self.stagedUrls.contains($0) }
                self.apply(snapshot)
                if added { self.selectedTab = "send" }
            } catch {
                self.transferStatusText = "Shared files could not be opened. Check access and the 1,000-file batch limit."
            }
        }
    }

    public func stageFiles(urls: [URL]) {
        guard !isTransferActive else { return }
        enqueueStaging {
            do { self.apply(try await self.stagedFiles.add(urls)) }
            catch {
                self.transferStatusText = "Files could not be added. Choose accessible files within the batch limits."
            }
        }
    }

    public func removeStagedFile(url: URL) {
        removeStagedFiles(urls: [url])
    }

    public func clearStaged() {
        guard !isTransferActive else { return }
        enqueueStaging { self.apply(await self.stagedFiles.remove(self.stagedUrls)) }
    }

    public func removeStagedFiles(urls: [URL]) {
        guard !isTransferActive else { return }
        enqueueStaging { self.apply(await self.stagedFiles.remove(urls)) }
    }

    private func enqueueStaging(_ operation: @escaping @MainActor () async -> Void) {
        let preceding = stagingTask
        pendingStagingOperations += 1
        isStagingFiles = true
        stagingTask = Task {
            await preceding?.value
            await operation()
            pendingStagingOperations -= 1
            if pendingStagingOperations == 0 {
                isStagingFiles = false
                stagingTask = nil
            }
        }
    }

    private func apply(_ snapshot: StagedFilesStore.Snapshot) {
        stagedUrls = snapshot.urls
        stagedSizes = snapshot.sizes
        stagedSize = snapshot.totalBytes
        if snapshot.removalFailed {
            transferStatusText = "Some shared files could not be removed. Try removing them again."
        }
    }

    public func generateLocalInvitation() {
        // A pairing invitation must be bound to a running TLS listener's certificate
        // and reachable address. Never publish a placeholder QR from UI state.
        currentInvitationUri = ""
        transferStatusText = "Receiving setup is unavailable until a TLS listener identity is configured."
    }

    public func sendStagedFiles() {
        guard !isTransferActive, !isStagingFiles, !stagedUrls.isEmpty else { return }
        guard !invitationInput.isEmpty else {
            transferStatusText = "Please enter or scan peer invitation QR code"
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
        lifecycle.setIdleTimerDisabled(true)

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

                transferStatusText = "Successfully sent \(sent) file(s)!"
                historyItems.insert(IosHistoryItem(
                    id: UUID().uuidString,
                    timestamp: Date(),
                    direction: "Sent",
                    peerName: "Peer Device",
                    fileCount: sent,
                    totalBytes: stagedSize,
                    status: "Completed"
                ), at: 0)

                apply(await stagedFiles.remove(stagedUrls))
            } catch {
                transferStatusText = "Transfer failed: \(error.localizedDescription)"
                historyItems.insert(IosHistoryItem(
                    id: UUID().uuidString,
                    timestamp: Date(),
                    direction: "Sent",
                    peerName: "Peer Device",
                    fileCount: stagedUrls.count,
                    totalBytes: stagedSize,
                    status: "Failed"
                ), at: 0)
            }

            isTransferActive = false
            lifecycle.setIdleTimerDisabled(false)
        }
    }

    public func receiveFromInvitation() {
        guard !isTransferActive, !isStagingFiles, !invitationInput.isEmpty else { return }

        let invitation: LanInvitation
        do {
            invitation = try InvitationCodec.decode(invitationInput.trimmingCharacters(in: .whitespacesAndNewlines))
        } catch {
            transferStatusText = "Invalid invitation: \(error.localizedDescription)"
            return
        }

        let docsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]

        isTransferActive = true
        transferProgress = 0.0
        transferStatusText = "Connecting to sender..."
        lifecycle.setIdleTimerDisabled(true)

        Task {
            do {
                let received = try await engine.receiveFromInvitation(
                    invitation: invitation,
                    destinationDirectory: docsDir,
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
                historyItems.insert(IosHistoryItem(
                    id: UUID().uuidString,
                    timestamp: Date(),
                    direction: "Received",
                    peerName: "Invitation Sender",
                    fileCount: received,
                    totalBytes: 0,
                    status: "Completed"
                ), at: 0)
            } catch {
                transferStatusText = "Receive failed: \(error.localizedDescription)"
            }

            isTransferActive = false
            lifecycle.setIdleTimerDisabled(false)
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

    public func handleScannedCode(_ code: String) {
        isScannerPresented = false
        invitationInput = code
        if selectedTab == "receive" {
            receiveFromInvitation()
        }
    }
}
