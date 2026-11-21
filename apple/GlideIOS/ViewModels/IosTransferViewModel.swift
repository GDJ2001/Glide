import Foundation
import SwiftUI
import Combine
import Network
import UIKit
import GlideCoreKit

public enum IosSendMethod: String, CaseIterable, Sendable {
    case quickShareQr = "Quick share with QR"
    case pairedDevice = "Paired device"
}

public enum IosPairStage: Equatable, Sendable {
    case idle
    case approvalRequired(String)
    case verifying
    case success(String)
    case failed(String)
}

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
    @Published public private(set) var isImportingPhotos = false
    private var photoImport: AppGroupProviderImport?
    private let stagedFiles = StagedFilesStore()
    private var stagingTask: Task<Void, Never>?
    private var pendingStagingOperations = 0

    @Published public var sendMethod: IosSendMethod = .quickShareQr
    @Published public var trustedPeers: [PersistentTrustedPeer] = []
    @Published public var selectedTrustedPeer: PersistentTrustedPeer?
    @Published public var selectedPeerEndpoint: String = ""
    @Published public private(set) var availablePeers: [BonjourPeer] = []

    @Published public var invitationInput: String = ""
    @Published public var pairLinkInput: String = ""
    @Published public var pairStage: IosPairStage = .idle
    @Published public var pairDiagnostic: String = ""
    private var pendingPairInvitation: LanPairV2Invitation?
    private var pairOnlyTask: Task<Void, Never>?
    private var pairAttemptId = UUID()

    @Published public var currentInvitationUri: String = ""
    @Published public var isListening: Bool = false
    @Published public var listeningPort: UInt16 = 42424

    // Active transfer
    @Published public var isTransferActive: Bool = false
    @Published public var transferProgress: Double = 0.0
    @Published public var transferStatusText: String = "Ready"
    @Published public var transferredBytesText: String = ""
    @Published public var currentFileName: String = ""
    @Published public var lastTransferResult: TransferFeedback?

    // SAS Mutual Authentication
    @Published public var isSasConfirmationVisible: Bool = false
    @Published public var sasCode: String = ""
    @Published public var sasPeerName: String = ""
    private var sasContinuation: CheckedContinuation<Bool, Never>?

    // Incoming offer
    @Published public var isIncomingOfferVisible: Bool = false
    @Published public var incomingOfferPeer: String = ""
    @Published public var incomingOfferSummary: String = ""
    @Published public var incomingOfferTotalBytes: Int64 = 0
    private var offerContinuation: CheckedContinuation<Bool, Never>?

    // Scanner
    @Published public var isScannerPresented: Bool = false

    // Settings
    @Published public var deviceName: String = "iPhone"

    // History
    @Published public var historyItems: [IosHistoryItem] = []

    private let engine = GlideTransferEngine.shared
    private let listener = GlideNetworkListener()
    private let lifecycle = IosLifecycleCoordinator()
    private let authority = LanInvitationAuthority()
    private var receivePresenceId = ""
    private var isForeground = true
    private lazy var peerBrowser = GlidePeerBrowser { [weak self] peers in
        Task { @MainActor in self?.availablePeers = peers }
    }

    public var receiveDirectory: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    public init() {
        refreshTrustedPeers()
        peerBrowser.start()
        generateLocalInvitation()
        importSharedFiles()
    }

    public func appDidBecomeActive() {
        isForeground = true
        lifecycle.endBackgroundTask()
        peerBrowser.start()
        if !listener.isListening { generateLocalInvitation() }
        importSharedFiles()
    }

    public func appDidEnterBackground() {
        isForeground = false
        peerBrowser.stop()
        if isTransferActive {
            _ = lifecycle.beginBackgroundTask(onExpiration: { [weak self] in
                Task { @MainActor in
                    self?.transferStatusText = "iOS paused the transfer. Reopen Glide to check the result."
                }
            })
        } else {
            authority.revokeActive()
            currentInvitationUri = ""
            receivePresenceId = ""
            listener.stop()
            isListening = false
        }
    }

    private func finishTransfer() {
        isTransferActive = false
        setTransferAwake(false)
        lifecycle.endBackgroundTask()
        if isForeground { generateLocalInvitation() }
        else {
            authority.revokeActive()
            currentInvitationUri = ""
            receivePresenceId = ""
            listener.stop()
            isListening = false
        }
    }

    private func setTransferAwake(_ awake: Bool) {
        lifecycle.setIdleTimerDisabled(awake)
        UIApplication.shared.isIdleTimerDisabled = awake
    }

    private func recordFailure(_ error: Error, peerName: String) {
        let kind: TransferResultKind
        let message: String
        switch error {
        case TransferError.offerRejected, TransferError.recipientRejected:
            kind = .declined
            message = "The receiver declined this transfer."
        case TransferError.cancelled, is CancellationError:
            kind = .cancelled
            message = "The transfer was cancelled."
        case TransferError.identityChanged, TransferError.untrustedPeer:
            kind = .failed
            message = "Device identity changed. Pair again before sending."
        case TransferError.busy:
            kind = .failed
            message = "This device is busy. Try again."
        case TransferError.peerDisconnected, NetworkClientError.connectionTimedOut:
            kind = .failed
            message = "Could not reach this device. Keep Glide open on both devices."
        default:
            kind = .failed
            message = "The transfer stopped. Try again."
        }
        transferStatusText = message
        lastTransferResult = TransferFeedback(
            kind: kind, peerName: peerName, message: message,
            technicalDetails: String(describing: error))
    }

    public func refreshTrustedPeers() {
        engine.trustStore.load()
        trustedPeers = engine.trustStore.allPeers().sorted { $0.lastSeenAtUnixMs > $1.lastSeenAtUnixMs }
        if selectedTrustedPeer == nil ||
            !trustedPeers.contains(where: { $0.fingerprintHex == selectedTrustedPeer?.fingerprintHex }) {
            selectedTrustedPeer = trustedPeers.first
        }
    }

    public func revokeTrustedPeer(_ peer: PersistentTrustedPeer) {
        try? engine.trustStore.revokePeer(fingerprintHex: peer.fingerprintHex)
        refreshTrustedPeers()
    }

    // MARK: - Pair-only QR/Link flow (Mobile: no 6-digit PIN page)

    public func submitPairOnlyInvitation(_ rawUri: String) {
        pairOnlyTask?.cancel()
        pairOnlyTask = nil
        pairAttemptId = UUID()
        let trimmed = rawUri.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        do {
            let parsed = try InvitationCodec.decodeAny(trimmed)
            guard case .pairOnlyV2(let inv) = parsed else {
                pairStage = .failed("This link sends files — open Receive to use it")
                pairDiagnostic = "Link purpose is not pair-only."
                return
            }
            if inv.isExpired() {
                pairStage = .failed("Code expired — get a new code")
                pairDiagnostic = "Pair-only invitation has expired."
                return
            }
            pendingPairInvitation = inv
            pairDiagnostic = inv.endpoints.map { "\($0.address):\($0.port)" }.joined(separator: ", ")
            pairStage = .approvalRequired(inv.name)
        } catch {
            pairStage = .failed("Code expired — get a new code")
            pairDiagnostic = error.localizedDescription
        }
    }

    public func confirmPairOnlyApproval(approved: Bool) {
        guard case .approvalRequired = pairStage else { return }
        guard approved, let inv = pendingPairInvitation else {
            resetPairOnlyState()
            return
        }
        pairStage = .verifying
        let attempt = pairAttemptId
        pairOnlyTask = Task { @MainActor in
            defer {
                if self.pairAttemptId == attempt {
                    self.pendingPairInvitation = nil
                    self.pairOnlyTask = nil
                }
            }
            do {
                let saved = try await AppleProfile2PairingSession.initiateQr(
                    invitation: inv, localDeviceName: deviceName, platform: "ios",
                    trustStore: engine.trustStore, approve: { _ in true })
                try Task.checkCancellation()
                guard self.pairAttemptId == attempt else { return }
                self.refreshTrustedPeers()
                self.pairStage = .success(saved.displayName)
                self.pairDiagnostic = ""
            } catch {
                guard !Task.isCancelled, self.pairAttemptId == attempt else { return }
                self.pairStage = .failed(error.localizedDescription)
                self.pairDiagnostic = "Pairing did not complete."
            }
        }
    }

    public func resetPairOnlyState() {
        pairOnlyTask?.cancel()
        pairOnlyTask = nil
        pairAttemptId = UUID()
        pendingPairInvitation = nil
        pairStage = .idle
        pairDiagnostic = ""
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

    public func importPhotos(providers: [NSItemProvider]) {
        guard !isTransferActive, !providers.isEmpty else { return }
        enqueueStaging {
            self.isImportingPhotos = true
            let remainingItems = ShareExtensionCoordinator.maxItemCount - self.stagedUrls.count
            let remainingBytes = ShareExtensionCoordinator.maxBatchSizeBytes - self.stagedSize
            let result: Result<[URL], Error> = await withCheckedContinuation { continuation in
                let importer = AppGroupProviderImport(providers: providers, maxItems: remainingItems,
                                                       maxBatchBytes: remainingBytes) { result in
                    continuation.resume(returning: result)
                }
                self.photoImport = importer
                importer.start()
            }
            self.photoImport = nil
            self.isImportingPhotos = false
            switch result {
            case .success(let urls):
                do { self.apply(try await self.stagedFiles.add(urls)) }
                catch {
                    self.transferStatusText = "Photos were queued but could not be selected. Reopen Glide to retry."
                }
            case .failure(let error):
                self.transferStatusText = error is CancellationError
                    ? "Photo import cancelled. Your earlier selection is unchanged."
                    : "Photos could not be added. Check available storage and the batch limits."
            }
        }
    }

    public func cancelPhotoImport() { photoImport?.cancel() }

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
        guard isForeground || isTransferActive else { return }
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
                                let (invitation, uri) = try AppleInvitationManager.publish(
                                    listener: self.listener,
                                    authority: self.authority,
                                    deviceName: self.deviceName,
                                    platform: "ios"
                                )
                                self.currentInvitationUri = uri
                                self.receivePresenceId = invitation.presence
                                self.listener.updateAdvertisement(
                                    name: self.deviceName,
                                    presenceTokenHex: invitation.presence,
                                    deviceType: BonjourService.deviceTypePhone,
                                    isPairReady: false)
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
                let (invitation, uri) = try AppleInvitationManager.publish(
                    listener: listener,
                    authority: authority,
                    deviceName: deviceName,
                    platform: "ios"
                )
                currentInvitationUri = uri
                receivePresenceId = invitation.presence
                listener.updateAdvertisement(
                    name: deviceName,
                    presenceTokenHex: invitation.presence,
                    deviceType: BonjourService.deviceTypePhone,
                    isPairReady: false)
                transferStatusText = "Ready to receive"
            }
        } catch {
            currentInvitationUri = ""
            transferStatusText = "Receiving setup unavailable: \(error.localizedDescription)"
        }
    }

    private func handleIncomingConnection(_ connection: NWConnection) {
        guard !isTransferActive else {
            Task { await GlideTransferEngine.rejectBusyConnection(connection) }
            return
        }
        isTransferActive = true
        lastTransferResult = nil
        transferProgress = 0.0
        transferStatusText = "Receiving from connected peer..."
        setTransferAwake(true)
        let docsDir = receiveDirectory

        Task {
            do {
                let channel = LanRecordChannel(connection: connection)
                let kind = try await channel.inspectSessionPreamble()
                let progress: @Sendable (Double, Int64, Int64, String) -> Void = {
                    [weak self] value, bytesDone, totalBytes, filename in
                    Task { @MainActor in
                        self?.transferProgress = value
                        self?.currentFileName = filename
                        self?.transferredBytesText = "\(ByteCountFormatter.string(fromByteCount: bytesDone, countStyle: .file)) / \(ByteCountFormatter.string(fromByteCount: totalBytes, countStyle: .file))"
                    }
                }
                let consent: @Sendable (String, Int, Int64) async -> Bool = {
                    [weak self] peer, count, totalBytes in
                    guard let self else { return false }
                    return await self.requestOfferConsent(peer: peer, count: count,
                                                          totalBytes: totalBytes)
                }
                let count: Int
                try FileManager.default.createDirectory(at: docsDir, withIntermediateDirectories: true)
                switch kind {
                case .profile1LanRecord:
                    count = try await engine.receiveInboundChannel(
                        channel: channel, authority: authority,
                        destinationDirectory: docsDir, localDeviceName: deviceName,
                        onConsent: consent, onProgress: progress)
                case .profile2Gld2:
                    let openData = try await channel.readProfile2MessageData()
                    let open = try JSONDecoder().decode(Profile2SessionOpen.self, from: openData)
                    guard open.v == 1, open.type == "SESSION_OPEN",
                          open.purpose == Profile2Constants.purposeTrustedSend else {
                        throw ApplePairSessionError.invalidMessage
                    }
                    count = try await engine.receiveTrustedInboundConnection(
                        channel: channel, open: open,
                        destinationDirectory: docsDir, localDeviceName: deviceName,
                        localPlatform: "ios",
                        currentPresenceId: receivePresenceId,
                        onConsent: consent, onProgress: progress)
                case .invalid:
                    throw ApplePairSessionError.invalidMessage
                }
                if count == 0 {
                    lastTransferResult = TransferFeedback(kind: .declined,
                        peerName: incomingOfferPeer, message: "You declined this transfer.")
                    transferStatusText = "Transfer declined"
                } else {
                    lastTransferResult = TransferFeedback(kind: .complete,
                        peerName: incomingOfferPeer, fileCount: count,
                        totalBytes: incomingOfferTotalBytes,
                        message: "\(count) file(s) verified and saved.")
                    transferStatusText = "Received \(count) file(s)!"
                }
                guard count > 0 else { finishTransfer(); return }
                historyItems.insert(IosHistoryItem(
                    id: UUID().uuidString,
                    timestamp: Date(),
                    direction: "Received",
                    peerName: "Peer Device",
                    fileCount: count,
                    totalBytes: incomingOfferTotalBytes,
                    status: "Completed"
                ), at: 0)
            } catch {
                recordFailure(error, peerName: incomingOfferPeer)
            }
            finishTransfer()
        }
    }

    public func sendStagedFiles() {
        guard !isTransferActive, !isStagingFiles, !stagedUrls.isEmpty else { return }

        if sendMethod == .pairedDevice {
            sendToPairedDevice()
            return
        }

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
        lastTransferResult = nil
        transferProgress = 0.0
        transferStatusText = "Connecting..."
        setTransferAwake(true)

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
                lastTransferResult = TransferFeedback(kind: .complete,
                    peerName: invitation.name, fileCount: sent,
                    totalBytes: stagedSize, message: "Files verified by the receiver.")
                historyItems.insert(IosHistoryItem(
                    id: UUID().uuidString,
                    timestamp: Date(),
                    direction: "Sent",
                    peerName: invitation.name,
                    fileCount: sent,
                    totalBytes: stagedSize,
                    status: "Completed"
                ), at: 0)

                apply(await stagedFiles.remove(stagedUrls))
            } catch {
                // Preserve stagedUrls on failure
                recordFailure(error, peerName: invitation.name)
            }

            finishTransfer()
        }
    }

    public func sendToPairedDevice() {
        guard !isTransferActive, !isStagingFiles, !stagedUrls.isEmpty, let peer = selectedTrustedPeer else { return }

        isTransferActive = true
        lastTransferResult = nil
        transferProgress = 0.0
        transferStatusText = "Finding \(peer.displayName)…"
        selectedTab = "activity"
        setTransferAwake(true)
        let previouslyDiscovered = availablePeers
        peerBrowser.start()

        Task {
            do {
                try await Task.sleep(nanoseconds: 1_000_000_000)
                if availablePeers.isEmpty && previouslyDiscovered.isEmpty {
                    try await Task.sleep(nanoseconds: 2_000_000_000)
                }
                let candidates = TrustedEndpointResolver.candidates(
                    for: peer, discovered: availablePeers + previouslyDiscovered,
                    manualAddress: selectedPeerEndpoint)
                guard !candidates.isEmpty else { throw TransferError.peerDisconnected }
                transferStatusText = "Connecting and verifying \(peer.displayName)…"
                let sent = try await engine.sendFilesToTrustedPeer(
                    fileUrls: stagedUrls,
                    peerFingerprintHex: peer.fingerprintHex,
                    candidates: candidates,
                    localDeviceName: deviceName,
                    localPlatform: "ios"
                ) { [weak self] progress, bytesDone, totalBytes, filename in
                    Task { @MainActor in
                        self?.transferProgress = progress
                        self?.currentFileName = filename
                        self?.transferredBytesText = "\(ByteCountFormatter.string(fromByteCount: bytesDone, countStyle: .file)) / \(ByteCountFormatter.string(fromByteCount: totalBytes, countStyle: .file))"
                    }
                }

                transferStatusText = "Successfully sent \(sent) file(s) to \(peer.displayName)!"
                lastTransferResult = TransferFeedback(kind: .complete,
                    peerName: peer.displayName, fileCount: sent,
                    totalBytes: stagedSize, message: "Files verified by the receiver.")
                historyItems.insert(IosHistoryItem(
                    id: UUID().uuidString,
                    timestamp: Date(),
                    direction: "Sent",
                    peerName: peer.displayName,
                    fileCount: sent,
                    totalBytes: stagedSize,
                    status: "Completed"
                ), at: 0)

                apply(await stagedFiles.remove(stagedUrls))
            } catch {
                recordFailure(error, peerName: peer.displayName)
            }

            finishTransfer()
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

        let docsDir = receiveDirectory
        do { try FileManager.default.createDirectory(at: docsDir, withIntermediateDirectories: true) }
        catch {
            transferStatusText = "Could not open the Glide receive folder."
            return
        }

        isTransferActive = true
        lastTransferResult = nil
        transferProgress = 0.0
        transferStatusText = "Connecting to sender..."
        setTransferAwake(true)

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

                if received == 0 {
                    lastTransferResult = TransferFeedback(kind: .declined,
                        peerName: incomingOfferPeer, message: "You declined this transfer.")
                    transferStatusText = "Transfer declined"
                } else {
                    lastTransferResult = TransferFeedback(kind: .complete,
                        peerName: incomingOfferPeer, fileCount: received,
                        totalBytes: incomingOfferTotalBytes,
                        message: "\(received) file(s) verified and saved.")
                    transferStatusText = "Received \(received) file(s)!"
                }
                guard received > 0 else { finishTransfer(); return }
                historyItems.insert(IosHistoryItem(
                    id: UUID().uuidString,
                    timestamp: Date(),
                    direction: "Received",
                    peerName: "Invitation Sender",
                    fileCount: received,
                    totalBytes: incomingOfferTotalBytes,
                    status: "Completed"
                ), at: 0)
            } catch {
                recordFailure(error, peerName: incomingOfferPeer)
            }

            finishTransfer()
        }
    }

    private func requestOfferConsent(peer: String, count: Int, totalBytes: Int64) async -> Bool {
        incomingOfferPeer = peer
        incomingOfferTotalBytes = totalBytes
        incomingOfferSummary = "\(count) file(s), \(ByteCountFormatter.string(fromByteCount: totalBytes, countStyle: .file))"
        isIncomingOfferVisible = true

        return await withCheckedContinuation { continuation in
            offerContinuation = continuation
        }
    }

    public func resolveOffer(accept: Bool) {
        isIncomingOfferVisible = false
        if accept { selectedTab = "activity" }
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
        let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix(InvitationCodec.pairV2UriPrefix) {
            selectedTab = "pair"
            pairLinkInput = trimmed
            submitPairOnlyInvitation(trimmed)
            return
        }
        invitationInput = trimmed
        if selectedTab == "receive" {
            receiveFromInvitation()
        }
    }
}
