import Foundation
import Network
import CryptoKit

public enum TransferError: Error, Equatable, Sendable {
    case invalidFileCount(Int)
    case fileNotFound(String)
    case itemSizeExceeded(String, Int64)
    case batchSizeExceeded(Int64)
    case peerDisconnected
    case bootstrapRejected(String)
    case offerRejected
    case recipientRejected
    case verificationFailed(String)
    case protocolViolation(String)
    case cancelled
    case timedOut
    case busy
    case insufficientStorage
    case limitExceeded(String)
    case identityChanged
    case untrustedPeer
}

public struct TransferItemInfo: Sendable, Equatable {
    public let itemId: String
    public let name: String
    public let sizeBytes: Int64
    public let relativePath: String

    public init(itemId: String, name: String, sizeBytes: Int64, relativePath: String) {
        self.itemId = itemId
        self.name = name
        self.sizeBytes = sizeBytes
        self.relativePath = relativePath
    }
}

/// Profile 1 production transfer engine for Apple platforms (iOS, iPadOS, macOS).
/// Strictly complies with framing, quarantine staging, independent receiver SHA-256 verification,
/// atomic commits, and mutual consent.
public final class GlideTransferEngine: @unchecked Sendable {
    public static let shared = GlideTransferEngine()

    public static let maxItemBytes: Int64 = 68_719_476_736 // 64 GiB
    public static let maxBatchBytes: Int64 = 274_877_906_944 // 256 GiB
    public static let maxBatchFiles = 1_000
    public static let maxOfferPageItems = 32
    public static let streamChunkBytes = 65_520

    public let historyStore: PersistentHistoryStore
    public let trustStore: PersistentTrustStore
    private let sessionLock = NSLock()
    private var activeSessionId: String?

    public init(storageDirectory: URL? = nil) {
        self.historyStore = PersistentHistoryStore(storageDirectory: storageDirectory)
        self.trustStore = PersistentTrustStore(storageDirectory: storageDirectory)
    }

    private func tryAcquireSession(_ id: String) -> Bool {
        sessionLock.lock()
        defer { sessionLock.unlock() }
        if activeSessionId != nil { return false }
        activeSessionId = id
        return true
    }

    private func releaseSession(_ id: String) {
        sessionLock.lock()
        defer { sessionLock.unlock() }
        if activeSessionId == id {
            activeSessionId = nil
        }
    }

    // MARK: - Sender Outbound Flow (SendAsync -> remote Receiver listener)

    public func sendFiles(
        fileUrls: [URL],
        to invitation: LanInvitation,
        localDeviceName: String = "",
        onProgress: (@Sendable (Double, Int64, Int64, String) -> Void)? = nil
    ) async throws -> Int {
        guard !fileUrls.isEmpty else {
            throw TransferError.invalidFileCount(0)
        }
        guard fileUrls.count <= Self.maxBatchFiles else {
            throw TransferError.invalidFileCount(fileUrls.count)
        }

        var totalBatchBytes: Int64 = 0
        for url in fileUrls {
            guard FileManager.default.fileExists(atPath: url.path) else {
                throw TransferError.fileNotFound(url.path)
            }
            let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
            guard let sizeNum = attrs[.size] as? NSNumber else {
                throw TransferError.fileNotFound(url.path)
            }
            let size = sizeNum.int64Value
            guard size >= 0 && size <= Self.maxItemBytes else {
                throw TransferError.itemSizeExceeded(url.lastPathComponent, size)
            }
            let (nextTotal, overflow) = totalBatchBytes.addingReportingOverflow(size)
            guard !overflow && nextTotal <= Self.maxBatchBytes else {
                throw TransferError.batchSizeExceeded(nextTotal)
            }
            totalBatchBytes = nextTotal
        }

        guard !invitation.isExpired() else {
            throw TransferError.protocolViolation("Invitation has expired.")
        }
        guard invitation.invitationHash.count == 64 else {
            throw TransferError.protocolViolation("Invitation hash must be 64 characters.")
        }

        let client = GlideNetworkClient()
        var connectedChannel: LanRecordChannel?
        var lastError: Error?

        print("DEBUG: [sendFiles] Starting connect loop over \(invitation.endpoints.count) endpoints...")
        for endpoint in invitation.endpoints {
            do {
                print("DEBUG: [sendFiles] Trying to connect to \(endpoint.address):\(endpoint.port)...")
                connectedChannel = try await client.connect(
                    host: endpoint.address,
                    port: UInt16(endpoint.port),
                    expectedSpkiSha256Hex: invitation.spkiSha256,
                    timeoutSeconds: 3.0
                )
                print("DEBUG: [sendFiles] Connected to \(endpoint.address):\(endpoint.port) successfully!")
                break
            } catch {
                print("DEBUG: [sendFiles] Failed to connect to \(endpoint.address):\(endpoint.port): \(error)")
                lastError = error
            }
        }

        guard let channel = connectedChannel else {
            throw lastError ?? TransferError.peerDisconnected
        }

        return try await runSenderSession(
            channel: channel,
            fileUrls: fileUrls,
            totalBatchBytes: totalBatchBytes,
            invitation: invitation,
            localDeviceName: localDeviceName,
            isHostedSender: false,
            onConfirmRecipient: nil,
            onProgress: onProgress
        )
    }

    // MARK: - Receiver Inbound Flow from Invitation (ReceiveFromInvitationAsync -> hosted sender)

    public func receiveFromInvitation(
        invitation: LanInvitation,
        destinationDirectory: URL,
        localDeviceName: String = "",
        onConsent: @escaping @Sendable (String, Int, Int64) async -> Bool,
        onProgress: (@Sendable (Double, Int64, Int64, String) -> Void)? = nil
    ) async throws -> Int {
        guard !invitation.isExpired() else {
            throw TransferError.protocolViolation("Invitation has expired.")
        }
        guard invitation.invitationHash.count == 64 else {
            throw TransferError.protocolViolation("Invitation hash must be 64 characters.")
        }

        let client = GlideNetworkClient()
        var connectedChannel: LanRecordChannel?
        var lastError: Error?

        for endpoint in invitation.endpoints {
            do {
                connectedChannel = try await client.connect(
                    host: endpoint.address,
                    port: UInt16(endpoint.port),
                    expectedSpkiSha256Hex: invitation.spkiSha256,
                    timeoutSeconds: 3.0
                )
                break
            } catch {
                lastError = error
            }
        }

        guard let channel = connectedChannel else {
            throw lastError ?? TransferError.peerDisconnected
        }

        return try await runReceiverSession(
            channel: channel,
            invitation: invitation,
            authority: nil,
            destinationDirectory: destinationDirectory,
            localDeviceName: localDeviceName,
            isConnectingToHostedSender: true,
            onConsent: onConsent,
            onProgress: onProgress
        )
    }

    // MARK: - Listener Inbound Receive Dispatch (Accepted socket on receiver listener)

    public func receiveInboundConnection(
        connection: NWConnection,
        authority: LanInvitationAuthority,
        destinationDirectory: URL,
        localDeviceName: String = "",
        onConsent: @escaping @Sendable (String, Int, Int64) async -> Bool,
        onProgress: (@Sendable (Double, Int64, Int64, String) -> Void)? = nil
    ) async throws -> Int {
        let channel = LanRecordChannel(connection: connection)
        return try await runReceiverSession(
            channel: channel,
            invitation: nil,
            authority: authority,
            destinationDirectory: destinationDirectory,
            localDeviceName: localDeviceName,
            isConnectingToHostedSender: false,
            onConsent: onConsent,
            onProgress: onProgress
        )
    }

    // MARK: - Listener Hosted Send Dispatch (Accepted socket on hosted sender listener)

    public func sendHostedInboundConnection(
        connection: NWConnection,
        authority: LanInvitationAuthority,
        fileUrls: [URL],
        localDeviceName: String = "",
        onConfirmRecipient: @escaping @Sendable (String) async -> Bool,
        onProgress: (@Sendable (Double, Int64, Int64, String) -> Void)? = nil
    ) async throws -> Int {
        var totalBatchBytes: Int64 = 0
        for url in fileUrls {
            let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
            guard let sizeNum = attrs[.size] as? NSNumber else {
                throw TransferError.fileNotFound(url.path)
            }
            let size = sizeNum.int64Value
            let (nextTotal, overflow) = totalBatchBytes.addingReportingOverflow(size)
            guard !overflow && nextTotal <= Self.maxBatchBytes else {
                throw TransferError.batchSizeExceeded(nextTotal)
            }
            totalBatchBytes = nextTotal
        }

        let channel = LanRecordChannel(connection: connection)
        return try await runSenderSession(
            channel: channel,
            fileUrls: fileUrls,
            totalBatchBytes: totalBatchBytes,
            invitation: nil,
            authority: authority,
            localDeviceName: localDeviceName,
            isHostedSender: true,
            onConfirmRecipient: onConfirmRecipient,
            onProgress: onProgress
        )
    }

    // MARK: - Trusted Reconnect Outbound Flow (TRUSTED_SEND -> saved peer without fresh invitation)

    public func sendFilesToTrustedPeer(
        fileUrls: [URL],
        peerFingerprintHex: String,
        endpoints: [LanEndpoint],
        expectedPresenceId: String? = nil,
        localDeviceName: String = "",
        onProgress: (@Sendable (Double, Int64, Int64, String) -> Void)? = nil
    ) async throws -> Int {
        guard !fileUrls.isEmpty else {
            throw TransferError.invalidFileCount(0)
        }
        guard fileUrls.count <= Self.maxBatchFiles else {
            throw TransferError.invalidFileCount(fileUrls.count)
        }
        guard let enrolledPeer = trustStore.getPeer(fingerprintHex: peerFingerprintHex) else {
            throw TransferError.identityChanged
        }
        guard !endpoints.isEmpty else {
            throw TransferError.peerDisconnected
        }

        var totalBatchBytes: Int64 = 0
        for url in fileUrls {
            guard FileManager.default.fileExists(atPath: url.path) else {
                throw TransferError.fileNotFound(url.path)
            }
            let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
            guard let sizeNum = attrs[.size] as? NSNumber else {
                throw TransferError.fileNotFound(url.path)
            }
            let size = sizeNum.int64Value
            guard size >= 0 && size <= Self.maxItemBytes else {
                throw TransferError.itemSizeExceeded(url.lastPathComponent, size)
            }
            let (nextTotal, overflow) = totalBatchBytes.addingReportingOverflow(size)
            guard !overflow && nextTotal <= Self.maxBatchBytes else {
                throw TransferError.batchSizeExceeded(nextTotal)
            }
            totalBatchBytes = nextTotal
        }

        let client = GlideNetworkClient()
        var connectedChannel: LanRecordChannel?
        var lastError: Error?

        for endpoint in endpoints {
            do {
                connectedChannel = try await client.connect(
                    host: endpoint.address,
                    port: UInt16(endpoint.port),
                    expectedSpkiSha256Hex: nil,
                    timeoutSeconds: 3.0
                )
                break
            } catch {
                lastError = error
            }
        }

        guard let channel = connectedChannel else {
            throw lastError ?? TransferError.peerDisconnected
        }

        let sessionId = UUID().uuidString.lowercased()
        let localSpki = try KeychainIdentityManager.shared.getPublicKeySpkiData()
        var nonceBytes = [UInt8](repeating: 0, count: 32)
        for i in 0..<32 { nonceBytes[i] = UInt8.random(in: 0...255) }

        let openMsg = Profile2SessionOpen(
            sessionId: sessionId,
            purpose: Profile2Constants.purposeTrustedSend,
            authMethod: Profile2Constants.authMethodIdentityV1,
            initiatorNonce: InvitationCodec.toUnpaddedBase64Url(Data(nonceBytes)),
            initiatorDeviceName: localDeviceName.isEmpty ? "Apple Device" : localDeviceName,
            initiatorPlatform: "macos",
            initiatorIdentityPublicKey: InvitationCodec.toUnpaddedBase64Url(localSpki),
            expectedPresenceId: expectedPresenceId,
            expectedPeerIdentityFingerprint: enrolledPeer.fingerprintHex
        )

        try await channel.writeGld2Preamble()
        try await channel.writeProfile2Message(openMsg)

        let ackData = try await channel.readProfile2MessageData()
        let ack = try JSONDecoder().decode(Profile2SessionOpenAck.self, from: ackData)
        guard ack.sessionId == sessionId else {
            await channel.close()
            throw TransferError.protocolViolation("SessionId mismatch in SESSION_OPEN_ACK")
        }
        if !ack.accepted {
            await channel.close()
            if ack.errorCode == "BUSY" {
                throw TransferError.busy
            }
            if ack.errorCode == "IDENTITY_CHANGED" || ack.errorCode == "UNTRUSTED_PEER" || ack.errorCode == "AUTH_FAILED" {
                throw TransferError.identityChanged
            }
            throw TransferError.bootstrapRejected(ack.errorCode ?? "REJECTED")
        }

        guard let respKeyB64 = ack.responderIdentityPublicKey,
              let respKeyDer = try? InvitationCodec.fromUnpaddedBase64Url(respKeyB64) else {
            await channel.close()
            throw TransferError.identityChanged
        }
        let respFp = SHA256.hash(data: respKeyDer).map { String(format: "%02x", $0) }.joined()
        let respHex = respKeyDer.map { String(format: "%02x", $0) }.joined()
        guard respFp == enrolledPeer.fingerprintHex && respHex == enrolledPeer.publicKeyDerHex else {
            await channel.close()
            throw TransferError.identityChanged
        }

        return try await runSenderSession(
            channel: channel,
            fileUrls: fileUrls,
            totalBatchBytes: totalBatchBytes,
            invitation: nil,
            authority: nil,
            localDeviceName: localDeviceName,
            isHostedSender: false,
            preEstablishedTrustedSessionId: sessionId,
            onConfirmRecipient: nil,
            onProgress: onProgress
        )
    }

    // MARK: - Trusted Reconnect Inbound Receive Dispatch (TRUSTED_SEND on listener)

    public func receiveTrustedInboundConnection(
        channel: LanRecordChannel,
        open: Profile2SessionOpen,
        destinationDirectory: URL,
        localDeviceName: String = "",
        onConsent: @escaping @Sendable (String, Int, Int64) async -> Bool,
        onProgress: (@Sendable (Double, Int64, Int64, String) -> Void)? = nil
    ) async throws -> Int {
        guard open.purpose == Profile2Constants.purposeTrustedSend,
              open.authMethod == Profile2Constants.authMethodIdentityV1 else {
            let ack = Profile2SessionOpenAck(
                sessionId: open.sessionId,
                accepted: false,
                errorCode: "PROTOCOL_ORDER",
                message: "Invalid session purpose for TRUSTED_SEND",
                responderDeviceName: localDeviceName.isEmpty ? "Apple Receiver" : localDeviceName
            )
            try? await channel.writeProfile2Message(ack)
            await channel.close()
            throw TransferError.protocolViolation("Invalid session purpose for TRUSTED_SEND")
        }

        guard let initSpki = try? InvitationCodec.fromUnpaddedBase64Url(open.initiatorIdentityPublicKey) else {
            let ack = Profile2SessionOpenAck(
                sessionId: open.sessionId,
                accepted: false,
                errorCode: "AUTH_FAILED",
                message: "Identity changed — pair again",
                responderDeviceName: localDeviceName.isEmpty ? "Apple Receiver" : localDeviceName
            )
            try? await channel.writeProfile2Message(ack)
            await channel.close()
            throw TransferError.untrustedPeer
        }

        let initFp = SHA256.hash(data: initSpki).map { String(format: "%02x", $0) }.joined()
        let initHex = initSpki.map { String(format: "%02x", $0) }.joined()
        guard let enrolledSender = trustStore.getPeer(fingerprintHex: initFp),
              enrolledSender.publicKeyDerHex == initHex else {
            let ack = Profile2SessionOpenAck(
                sessionId: open.sessionId,
                accepted: false,
                errorCode: "UNTRUSTED_PEER",
                message: "Identity changed — pair again",
                responderDeviceName: localDeviceName.isEmpty ? "Apple Receiver" : localDeviceName
            )
            try? await channel.writeProfile2Message(ack)
            await channel.close()
            throw TransferError.untrustedPeer
        }

        let localSpki = try KeychainIdentityManager.shared.getPublicKeySpkiData()
        var nonceBytes = [UInt8](repeating: 0, count: 32)
        for i in 0..<32 { nonceBytes[i] = UInt8.random(in: 0...255) }

        let ack = Profile2SessionOpenAck(
            sessionId: open.sessionId,
            accepted: true,
            responderNonce: InvitationCodec.toUnpaddedBase64Url(Data(nonceBytes)),
            responderDeviceName: localDeviceName.isEmpty ? "Apple Receiver" : localDeviceName,
            responderPlatform: "macos",
            responderIdentityPublicKey: InvitationCodec.toUnpaddedBase64Url(localSpki)
        )
        try await channel.writeProfile2Message(ack)

        return try await runReceiverSession(
            channel: channel,
            invitation: nil,
            authority: nil,
            destinationDirectory: destinationDirectory,
            localDeviceName: localDeviceName,
            isConnectingToHostedSender: false,
            preEstablishedTrustedSessionId: open.sessionId.lowercased(),
            onConsent: onConsent,
            onProgress: onProgress
        )
    }

    // MARK: - Internal Sender Session Implementation

    private func runSenderSession(
        channel: LanRecordChannel,
        fileUrls: [URL],
        totalBatchBytes: Int64,
        invitation: LanInvitation? = nil,
        authority: LanInvitationAuthority? = nil,
        localDeviceName: String,
        isHostedSender: Bool,
        preEstablishedTrustedSessionId: String? = nil,
        onConfirmRecipient: (@Sendable (String) async -> Bool)?,
        onProgress: (@Sendable (Double, Int64, Int64, String) -> Void)?
    ) async throws -> Int {
        var sessionId = preEstablishedTrustedSessionId ?? UUID().uuidString.lowercased()
        let sequence = LanControlSequence()

        print("DEBUG: [runSenderSession] Entered: isHostedSender=\(isHostedSender), sessionId=\(sessionId)")
        // 1. BOOTSTRAP
        if let trustedSessionId = preEstablishedTrustedSessionId {
            sessionId = trustedSessionId
            guard tryAcquireSession(sessionId) else {
                await channel.close()
                throw TransferError.busy
            }
        } else if !isHostedSender {
            guard tryAcquireSession(sessionId) else {
                await channel.close()
                throw TransferError.busy
            }
            guard let inv = invitation else { throw TransferError.protocolViolation("Missing invitation") }
            let bootstrap = try LanBootstrapMessage(
                invitationId: inv.invitationId,
                token: inv.token,
                invitationHash: inv.invitationHash,
                sessionId: sessionId,
                role: "sender"
            )
            print("DEBUG: [runSenderSession] Encoded bootstrap message, writing bootstrapRecord...")
            let bootstrapRecord = try LanRecord.bootstrap(jsonBytes: LanBootstrapCodec.encode(bootstrap))
            try await channel.writeRecord(bootstrapRecord)
            print("DEBUG: [runSenderSession] Wrote bootstrapRecord, awaiting bootstrapResult...")

            let resultRecord = try await channel.readRecord()
            print("DEBUG: [runSenderSession] Read resultRecord: \(resultRecord.kind)")
            guard case .bootstrapResult(let jsonBytes) = resultRecord else {
                throw TransferError.protocolViolation("Expected BOOTSTRAP_RESULT")
            }
            let bootstrapResult = try LanBootstrapCodec.decodeResult(jsonBytes)
            guard bootstrapResult.status == "accepted" else {
                throw TransferError.bootstrapRejected(bootstrapResult.code ?? "REJECTED")
            }
            guard bootstrapResult.sessionId == sessionId else {
                throw TransferError.protocolViolation("SessionId mismatch in bootstrap result")
            }
        } else {
            guard let auth = authority else { throw TransferError.protocolViolation("Missing authority") }
            let firstRecord = try await channel.readRecord()
            guard case .bootstrap(let jsonBytes) = firstRecord else {
                throw TransferError.protocolViolation("Expected BOOTSTRAP as first record")
            }
            let bootstrap = try LanBootstrapCodec.decodeBootstrap(jsonBytes)
            sessionId = bootstrap.sessionId.lowercased()
            guard tryAcquireSession(sessionId) else {
                let busyResult = try LanBootstrapResult.rejected(code: "BUSY")
                let record = try LanRecord.bootstrapResult(jsonBytes: LanBootstrapCodec.encode(busyResult))
                try await channel.writeRecord(record)
                await channel.close()
                throw TransferError.busy
            }
            let result = try auth.consume(bootstrap, expectedRole: "receiver")
            let resultRecord = try LanRecord.bootstrapResult(jsonBytes: LanBootstrapCodec.encode(result))
            try await channel.writeRecord(resultRecord)
            guard result.status == "accepted" else {
                throw TransferError.bootstrapRejected(result.code ?? "REJECTED")
            }
        }
        defer {
            releaseSession(sessionId)
            channel.close()
        }

        // 2. HELLO / HELLO_ACK
        let name = localDeviceName.isEmpty ? "Apple Device" : localDeviceName
        let helloBody: [String: AnySendable] = [
            "supportedMajors": .array([.int(1)]),
            "requiredCapabilities": .array([.string("stream-v1"), .string("sha256")]),
            "optionalCapabilities": .array([.string("folder-v1")]),
            "role": .string("sender"),
            "ephemeralPeerId": .string(UUID().uuidString.lowercased()),
            "displayName": .string(name)
        ]
        let helloRecord = try await sequence.createRecord(
            type: LanMessageTypes.hello,
            sessionId: sessionId,
            body: helloBody
        )
        try await channel.writeRecord(helloRecord)

        let helloAck = try await sequence.readEnvelope(from: channel, expectedType: LanMessageTypes.helloAck, sessionId: sessionId)
        let peerName = helloAck.body["displayName"]?.stringValue ?? "Peer Device"

        if isHostedSender, let onConfirm = onConfirmRecipient {
            let confirmed = await onConfirm(peerName)
            guard confirmed else {
                throw TransferError.recipientRejected
            }
        }

        // 3. Paged OFFERS
        struct PreparedItem {
            let itemId: String
            let url: URL
            let name: String
            let sizeBytes: Int64
        }
        var preparedItems = [PreparedItem]()
        for url in fileUrls {
            let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
            let size = (attrs[.size] as? NSNumber)?.int64Value ?? 0
            preparedItems.append(PreparedItem(
                itemId: UUID().uuidString.lowercased(),
                url: url,
                name: url.lastPathComponent,
                sizeBytes: size
            ))
        }

        let batchId = UUID().uuidString.lowercased()
        let pages = stride(from: 0, to: preparedItems.count, by: Self.maxOfferPageItems).map {
            Array(preparedItems[$0..<min($0 + Self.maxOfferPageItems, preparedItems.count)])
        }

        let offerBeginBody: [String: AnySendable] = [
            "batchId": .string(batchId),
            "itemCount": .int(Int64(preparedItems.count)),
            "totalBytes": .string(String(totalBatchBytes)),
            "pageCount": .int(Int64(pages.count))
        ]
        let offerBeginRecord = try await sequence.createRecord(
            type: LanMessageTypes.offerBegin,
            sessionId: sessionId,
            body: offerBeginBody
        )
        try await channel.writeRecord(offerBeginRecord)

        for (pageIndex, pageItems) in pages.enumerated() {
            let itemArray: [AnySendable] = pageItems.map { item in
                .object([
                    "itemId": .string(item.itemId),
                    "name": .string(item.name),
                    "sizeBytes": .string(String(item.sizeBytes)),
                    "relativePath": .string(item.name)
                ])
            }
            let pageBody: [String: AnySendable] = [
                "batchId": .string(batchId),
                "pageIndex": .int(Int64(pageIndex)),
                "items": .array(itemArray)
            ]
            let pageRecord = try await sequence.createRecord(
                type: LanMessageTypes.offerPage,
                sessionId: sessionId,
                body: pageBody
            )
            try await channel.writeRecord(pageRecord)
        }

        let offerEndBody: [String: AnySendable] = ["batchId": .string(batchId)]
        let offerEndRecord = try await sequence.createRecord(
            type: LanMessageTypes.offerEnd,
            sessionId: sessionId,
            body: offerEndBody
        )
        try await channel.writeRecord(offerEndRecord)

        // 4. OFFER_DECISION
        let decisionEnvelope = try await sequence.readEnvelope(from: channel, expectedType: LanMessageTypes.offerDecision, sessionId: sessionId)
        guard decisionEnvelope.body["batchId"]?.stringValue == batchId else {
            throw TransferError.protocolViolation("BatchId mismatch in OFFER_DECISION")
        }
        guard decisionEnvelope.body["accepted"]?.boolValue == true else {
            throw TransferError.offerRejected
        }

        // 5. Stream Items
        var cumulativeBytes: Int64 = 0
        var verifiedCount = 0

        for item in preparedItems {
            let bulkRef = UUID()
            let itemStartBody: [String: AnySendable] = [
                "batchId": .string(batchId),
                "itemId": .string(item.itemId),
                "bulkRef": .string(bulkRef.uuidString.lowercased()),
                "sizeBytes": .string(String(item.sizeBytes))
            ]
            let itemStartRecord = try await sequence.createRecord(
                type: LanMessageTypes.itemStart,
                sessionId: sessionId,
                body: itemStartBody
            )
            try await channel.writeRecord(itemStartRecord)

            let itemReady = try await sequence.readEnvelope(from: channel, expectedType: LanMessageTypes.itemReady, sessionId: sessionId)
            guard itemReady.body["batchId"]?.stringValue == batchId &&
                  itemReady.body["itemId"]?.stringValue == item.itemId else {
                throw TransferError.protocolViolation("ItemReady mismatch")
            }

            var hasher = SHA256()
            let handle = try FileHandle(forReadingFrom: item.url)
            defer { try? handle.close() }

            var streamedBytes: Int64 = 0
            while true {
                let chunk = try handle.read(upToCount: Self.streamChunkBytes) ?? Data()
                if chunk.isEmpty { break }
                hasher.update(data: chunk)
                streamedBytes += Int64(chunk.count)
                cumulativeBytes += Int64(chunk.count)

                let dataRecord = try LanRecord.data(bulkRef: bulkRef, chunk: chunk)
                try await channel.writeRecord(dataRecord)

                let progressFraction = totalBatchBytes > 0 ? Double(cumulativeBytes) / Double(totalBatchBytes) : 1.0
                onProgress?(progressFraction, cumulativeBytes, totalBatchBytes, item.name)
            }

            let computedDigest = hasher.finalize().map { String(format: "%02x", $0) }.joined()
            let streamEndRecord = try LanRecord.streamEnd(bulkRef: bulkRef, actualBytes: UInt64(streamedBytes))
            try await channel.writeRecord(streamEndRecord)

            let itemEndBody: [String: AnySendable] = [
                "batchId": .string(batchId),
                "itemId": .string(item.itemId),
                "actualBytes": .string(String(streamedBytes)),
                "sha256": .string(computedDigest)
            ]
            let itemEndRecord = try await sequence.createRecord(
                type: LanMessageTypes.itemEnd,
                sessionId: sessionId,
                body: itemEndBody
            )
            try await channel.writeRecord(itemEndRecord)

            let itemResult = try await sequence.readEnvelope(from: channel, expectedType: LanMessageTypes.itemResult, sessionId: sessionId)
            guard itemResult.body["batchId"]?.stringValue == batchId &&
                  itemResult.body["itemId"]?.stringValue == item.itemId else {
                throw TransferError.protocolViolation("ItemResult mismatch")
            }
            if itemResult.body["status"]?.stringValue == "verified" {
                verifiedCount += 1
            }
        }

        // 6. BATCH_END / BATCH_RESULT
        let batchEndBody: [String: AnySendable] = ["batchId": .string(batchId)]
        let batchEndRecord = try await sequence.createRecord(
            type: LanMessageTypes.batchEnd,
            sessionId: sessionId,
            body: batchEndBody
        )
        try await channel.writeRecord(batchEndRecord)

        let terminal = try await sequence.readEnvelopeOneOf(
            from: channel,
            expectedTypes: [LanMessageTypes.batchResult, LanMessageTypes.error],
            sessionId: sessionId
        )
        if terminal.type == LanMessageTypes.error {
            throw TransferError.verificationFailed(terminal.body["detail"]?.stringValue ?? "Remote error")
        }

        guard terminal.body["batchId"]?.stringValue == batchId,
              terminal.body["status"]?.stringValue == "completed",
              terminal.body["verifiedCount"]?.intValue == Int64(verifiedCount) else {
            throw TransferError.verificationFailed("BatchResult verification totals mismatch")
        }

        await channel.close()
        try? historyStore.addEntry(PersistentHistoryEntry(
            direction: "send",
            peerName: peerName,
            fileCount: verifiedCount,
            totalBytes: cumulativeBytes,
            status: "completed",
            destinationPath: nil
        ))
        return verifiedCount
    }

    // MARK: - Internal Receiver Session Implementation

    private func runReceiverSession(
        channel: LanRecordChannel,
        invitation: LanInvitation? = nil,
        authority: LanInvitationAuthority? = nil,
        destinationDirectory: URL,
        localDeviceName: String,
        isConnectingToHostedSender: Bool,
        preEstablishedTrustedSessionId: String? = nil,
        onConsent: @escaping @Sendable (String, Int, Int64) async -> Bool,
        onProgress: (@Sendable (Double, Int64, Int64, String) -> Void)?
    ) async throws -> Int {
        var sessionId = preEstablishedTrustedSessionId ?? UUID().uuidString.lowercased()
        let sequence = LanControlSequence()

        // 1. BOOTSTRAP
        if let trustedSessionId = preEstablishedTrustedSessionId {
            sessionId = trustedSessionId
            guard tryAcquireSession(sessionId) else {
                await channel.close()
                throw TransferError.busy
            }
        } else if isConnectingToHostedSender {
            guard let inv = invitation else { throw TransferError.protocolViolation("Missing invitation") }
            guard tryAcquireSession(sessionId) else {
                await channel.close()
                throw TransferError.busy
            }
            let bootstrap = try LanBootstrapMessage(
                invitationId: inv.invitationId,
                token: inv.token,
                invitationHash: inv.invitationHash,
                sessionId: sessionId,
                role: "receiver"
            )
            let bootstrapRecord = try LanRecord.bootstrap(jsonBytes: LanBootstrapCodec.encode(bootstrap))
            try await channel.writeRecord(bootstrapRecord)

            let resultRecord = try await channel.readRecord()
            guard case .bootstrapResult(let jsonBytes) = resultRecord else {
                throw TransferError.protocolViolation("Expected BOOTSTRAP_RESULT")
            }
            let bootstrapResult = try LanBootstrapCodec.decodeResult(jsonBytes)
            guard bootstrapResult.status == "accepted" else {
                throw TransferError.bootstrapRejected(bootstrapResult.code ?? "REJECTED")
            }
        } else {
            guard let auth = authority else { throw TransferError.protocolViolation("Missing authority") }
            print("DEBUG: [runReceiverSession] Waiting for firstRecord (BOOTSTRAP)...")
            let firstRecord = try await channel.readRecord()
            print("DEBUG: [runReceiverSession] Got firstRecord: \(firstRecord.kind)")
            guard case .bootstrap(let jsonBytes) = firstRecord else {
                throw TransferError.protocolViolation("Expected BOOTSTRAP as first record")
            }
            let bootstrap = try LanBootstrapCodec.decodeBootstrap(jsonBytes)
            sessionId = bootstrap.sessionId.lowercased()
            guard tryAcquireSession(sessionId) else {
                let busyResult = try LanBootstrapResult.rejected(code: "BUSY")
                let record = try LanRecord.bootstrapResult(jsonBytes: LanBootstrapCodec.encode(busyResult))
                try await channel.writeRecord(record)
                await channel.close()
                throw TransferError.busy
            }

            let result = try auth.consume(bootstrap, expectedRole: "sender")
            let resultRecord = try LanRecord.bootstrapResult(jsonBytes: LanBootstrapCodec.encode(result))
            try await channel.writeRecord(resultRecord)
            guard result.status == "accepted" else {
                throw TransferError.bootstrapRejected(result.code ?? "REJECTED")
            }
        }

        defer {
            releaseSession(sessionId)
            channel.close()
        }

        // 2. HELLO / HELLO_ACK
        let hello = try await sequence.readEnvelope(from: channel, expectedType: LanMessageTypes.hello, sessionId: sessionId)
        let peerName = hello.body["displayName"]?.stringValue ?? "Peer Device"

        let name = localDeviceName.isEmpty ? "Apple Receiver" : localDeviceName
        let helloAckBody: [String: AnySendable] = [
            "selectedMajor": .int(1),
            "capabilities": .array([.string("stream-v1"), .string("sha256")]),
            "role": .string("receiver"),
            "ephemeralPeerId": .string(UUID().uuidString.lowercased()),
            "displayName": .string(name),
            "limits": .object([
                "maxItems": .int(1000),
                "maxItemBytes": .string(String(Self.maxItemBytes)),
                "maxBatchBytes": .string(String(Self.maxBatchBytes)),
                "maxControlBytes": .int(16384)
            ])
        ]
        let helloAckRecord = try await sequence.createRecord(
            type: LanMessageTypes.helloAck,
            sessionId: sessionId,
            body: helloAckBody
        )
        try await channel.writeRecord(helloAckRecord)

        // 3. Paged OFFERS
        let offerBegin = try await sequence.readEnvelope(from: channel, expectedType: LanMessageTypes.offerBegin, sessionId: sessionId)
        print("DEBUG: [runReceiverSession] Received OFFER_BEGIN: \(offerBegin.body)")
        guard let batchId = offerBegin.body["batchId"]?.stringValue,
              let declaredItemCount = offerBegin.body["itemCount"]?.intValue,
              let declaredTotalBytes = offerBegin.body["totalBytes"]?.intValue,
              let pageCount = offerBegin.body["pageCount"]?.intValue else {
            print("DEBUG: [runReceiverSession] Malformed OFFER_BEGIN body: \(offerBegin.body)")
            throw TransferError.protocolViolation("Malformed OFFER_BEGIN")
        }

        guard declaredItemCount <= Int64(Self.maxBatchFiles), declaredTotalBytes <= Self.maxBatchBytes else {
            let limitBody: [String: AnySendable] = [
                "batchId": .string(batchId),
                "accepted": .bool(false),
                "code": .string(LanWireErrorCodes.limitExceeded)
            ]
            let decisionRecord = try await sequence.createRecord(
                type: LanMessageTypes.offerDecision,
                sessionId: sessionId,
                body: limitBody
            )
            try await channel.writeRecord(decisionRecord)
            throw TransferError.limitExceeded("Batch exceeded limits")
        }

        var items = [TransferItemInfo]()
        for pageIdx in 0..<pageCount {
            print("DEBUG: [runReceiverSession] Reading OFFER_PAGE \(pageIdx)/\(pageCount)...")
            let pageEnvelope = try await sequence.readEnvelope(from: channel, expectedType: LanMessageTypes.offerPage, sessionId: sessionId)
            guard pageEnvelope.body["batchId"]?.stringValue == batchId,
                  pageEnvelope.body["pageIndex"]?.intValue == Int64(pageIdx),
                  let rawItems = pageEnvelope.body["items"]?.arrayValue else {
                print("DEBUG: [runReceiverSession] Malformed OFFER_PAGE: \(pageEnvelope.body)")
                throw TransferError.protocolViolation("Malformed OFFER_PAGE")
            }
            for raw in rawItems {
                guard let obj = raw.objectValue,
                      let itemId = obj["itemId"]?.stringValue,
                      let itemName = obj["name"]?.stringValue,
                      let sizeBytes = obj["sizeBytes"]?.intValue else {
                    print("DEBUG: [runReceiverSession] Malformed item in OFFER_PAGE: \(raw)")
                    throw TransferError.protocolViolation("Malformed item in OFFER_PAGE")
                }
                guard DarwinPathSanitizer.isSafeFilename(itemName) else {
                    throw TransferError.protocolViolation("Unsafe filename: \(itemName)")
                }
                let relPath = obj["relativePath"]?.stringValue ?? itemName
                guard DarwinPathSanitizer.isSafeRelativePath(relPath) else {
                    throw TransferError.protocolViolation("Unsafe relative path: \(relPath)")
                }
                items.append(TransferItemInfo(itemId: itemId, name: itemName, sizeBytes: sizeBytes, relativePath: relPath))
            }
        }

        print("DEBUG: [runReceiverSession] Reading OFFER_END...")
        let offerEnd = try await sequence.readEnvelope(from: channel, expectedType: LanMessageTypes.offerEnd, sessionId: sessionId)
        guard offerEnd.body["batchId"]?.stringValue == batchId,
              items.count == Int(declaredItemCount) else {
            print("DEBUG: [runReceiverSession] OFFER_END mismatch: items=\(items.count), declared=\(declaredItemCount)")
            throw TransferError.protocolViolation("OFFER_END count mismatch")
        }

        // 4. CONSENT
        print("DEBUG: [runReceiverSession] Asking user consent: items=\(items.count), totalBytes=\(declaredTotalBytes)...")
        let consentGranted = await onConsent(peerName, items.count, declaredTotalBytes)
        print("DEBUG: [runReceiverSession] Consent result=\(consentGranted), sending OFFER_DECISION...")
        let consentBody: [String: AnySendable] = [
            "batchId": .string(batchId),
            "accepted": .bool(consentGranted),
            "code": .string(consentGranted ? "" : LanWireErrorCodes.userRejected)
        ]
        let decisionRecord = try await sequence.createRecord(
            type: LanMessageTypes.offerDecision,
            sessionId: sessionId,
            body: consentBody
        )
        try await channel.writeRecord(decisionRecord)
        guard consentGranted else {
            channel.close()
            return 0
        }

        // 5. Receive Items
        var cumulativeBytes: Int64 = 0
        var verifiedCount = 0

        for item in items {
            let itemStart = try await sequence.readEnvelope(from: channel, expectedType: LanMessageTypes.itemStart, sessionId: sessionId)
            guard itemStart.body["batchId"]?.stringValue == batchId,
                  itemStart.body["itemId"]?.stringValue == item.itemId,
                  let bulkRefStr = itemStart.body["bulkRef"]?.stringValue,
                  let bulkRef = UUID(uuidString: bulkRefStr) else {
                throw TransferError.protocolViolation("Malformed ITEM_START")
            }

            let readyBody: [String: AnySendable] = [
                "batchId": .string(batchId),
                "itemId": .string(item.itemId)
            ]
            let itemReadyRecord = try await sequence.createRecord(
                type: LanMessageTypes.itemReady,
                sessionId: sessionId,
                body: readyBody
            )
            try await channel.writeRecord(itemReadyRecord)

            let stager = QuarantineStagingStore(baseDirectory: destinationDirectory, sessionId: sessionId, itemId: item.itemId)
            try stager.open()

            var itemReceivedBytes: Int64 = 0
            while true {
                let record = try await channel.readRecord()
                switch record {
                case .data(let dataRef, let chunk):
                    guard dataRef == bulkRef else {
                        stager.purge()
                        throw TransferError.protocolViolation("BulkRef mismatch in DATA record")
                    }
                    try stager.writeChunk(chunk)
                    itemReceivedBytes += Int64(chunk.count)
                    cumulativeBytes += Int64(chunk.count)
                    let fraction = declaredTotalBytes > 0 ? Double(cumulativeBytes) / Double(declaredTotalBytes) : 1.0
                    onProgress?(fraction, cumulativeBytes, declaredTotalBytes, item.name)

                case .streamEnd(let endRef, let actualBytes):
                    guard endRef == bulkRef else {
                        stager.purge()
                        throw TransferError.protocolViolation("BulkRef mismatch in STREAM_END record")
                    }
                    guard Int64(actualBytes) == itemReceivedBytes && itemReceivedBytes == item.sizeBytes else {
                        stager.purge()
                        throw TransferError.protocolViolation("STREAM_END byte count mismatch")
                    }
                    break

                default:
                    stager.purge()
                    throw TransferError.protocolViolation("Unexpected record during data stream")
                }

                if case .streamEnd = record {
                    break
                }
            }

            let itemEnd = try await sequence.readEnvelope(from: channel, expectedType: LanMessageTypes.itemEnd, sessionId: sessionId)
            guard itemEnd.body["batchId"]?.stringValue == batchId,
                  itemEnd.body["itemId"]?.stringValue == item.itemId,
                  let declaredSha = itemEnd.body["sha256"]?.stringValue else {
                stager.purge()
                throw TransferError.protocolViolation("Malformed ITEM_END")
            }

            do {
                _ = try stager.finalizeAndCommit(
                    expectedSha256Hex: declaredSha,
                    expectedLengthBytes: item.sizeBytes,
                    destinationDirectory: destinationDirectory,
                    rawFilename: item.name
                )
                verifiedCount += 1

                let verifiedBody: [String: AnySendable] = [
                    "batchId": .string(batchId),
                    "itemId": .string(item.itemId),
                    "status": .string("verified"),
                    "sizeBytes": .string(String(item.sizeBytes)),
                    "sha256": .string(declaredSha)
                ]
                let itemResultRecord = try await sequence.createRecord(
                    type: LanMessageTypes.itemResult,
                    sessionId: sessionId,
                    body: verifiedBody
                )
                try await channel.writeRecord(itemResultRecord)
            } catch {
                stager.purge()
                let failureBody: [String: AnySendable] = [
                    "batchId": .string(batchId),
                    "itemId": .string(item.itemId),
                    "status": .string("failed"),
                    "code": .string(LanWireErrorCodes.integrityMismatch)
                ]
                let itemResultRecord = try await sequence.createRecord(
                    type: LanMessageTypes.itemResult,
                    sessionId: sessionId,
                    body: failureBody
                )
                try await channel.writeRecord(itemResultRecord)
            }
        }

        // 6. BATCH_END / BATCH_RESULT
        let batchEnd = try await sequence.readEnvelope(from: channel, expectedType: LanMessageTypes.batchEnd, sessionId: sessionId)
        guard batchEnd.body["batchId"]?.stringValue == batchId else {
            throw TransferError.protocolViolation("Malformed BATCH_END")
        }

        let batchResultBody: [String: AnySendable] = [
            "batchId": .string(batchId),
            "status": .string("completed"),
            "verifiedCount": .int(Int64(verifiedCount)),
            "totalVerifiedBytes": .string(String(cumulativeBytes))
        ]
        let batchResultRecord = try await sequence.createRecord(
            type: LanMessageTypes.batchResult,
            sessionId: sessionId,
            body: batchResultBody
        )
        try await channel.writeRecord(batchResultRecord)

        await channel.close()
        try? historyStore.addEntry(PersistentHistoryEntry(
            direction: "receive",
            peerName: peerName,
            fileCount: verifiedCount,
            totalBytes: cumulativeBytes,
            status: "completed",
            destinationPath: destinationDirectory.path
        ))
        return verifiedCount
    }
}
