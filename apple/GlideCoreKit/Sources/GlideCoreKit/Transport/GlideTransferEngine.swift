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
    case busy
    case insufficientStorage
    case limitExceeded(String)
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

        guard invitation.invitationHash.count == 64 else {
            throw TransferError.protocolViolation("Invitation hash must be 64 characters.")
        }
        guard !invitation.isExpired() else {
            throw TransferError.protocolViolation("Invitation has expired.")
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
                    timeoutSeconds: 10.0
                )
                break
            } catch {
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
        guard invitation.invitationHash.count == 64 else {
            throw TransferError.protocolViolation("Invitation hash must be 64 characters.")
        }
        guard !invitation.isExpired() else {
            throw TransferError.protocolViolation("Invitation has expired.")
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
                    timeoutSeconds: 10.0
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

    // MARK: - Internal Sender Session Implementation

    private func runSenderSession(
        channel: LanRecordChannel,
        fileUrls: [URL],
        totalBatchBytes: Int64,
        invitation: LanInvitation? = nil,
        authority: LanInvitationAuthority? = nil,
        localDeviceName: String,
        isHostedSender: Bool,
        onConfirmRecipient: (@Sendable (String) async -> Bool)?,
        onProgress: (@Sendable (Double, Int64, Int64, String) -> Void)?
    ) async throws -> Int {
        var sessionId = UUID().uuidString.lowercased()
        let sequence = LanControlSequence()

        // 1. BOOTSTRAP
        if !isHostedSender {
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
        defer { releaseSession(sessionId) }

        // 2. HELLO / HELLO_ACK
        let name = localDeviceName.isEmpty ? "Apple Device" : localDeviceName
        let helloRecord = try await sequence.createRecord(
            type: LanMessageTypes.hello,
            sessionId: sessionId,
            body: [
                "supportedMajors": .array([.int(1)]),
                "requiredCapabilities": .array([.string("stream-v1"), .string("sha256")]),
                "optionalCapabilities": .array([.string("folder-v1")]),
                "role": .string("sender"),
                "ephemeralPeerId": .string(UUID().uuidString.lowercased()),
                "displayName": .string(name)
            ]
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
            preparedItems.AddOrAppend: do {
                preparedItems.append(PreparedItem(
                    itemId: UUID().uuidString.lowercased(),
                    url: url,
                    name: url.lastPathComponent,
                    sizeBytes: size
                ))
            }
        }

        let batchId = UUID().uuidString.lowercased()
        let pages = stride(from: 0, to: preparedItems.count, by: Self.maxOfferPageItems).map {
            Array(preparedItems[$0..<min($0 + Self.maxOfferPageItems, preparedItems.count)])
        }

        let offerBeginRecord = try await sequence.createRecord(
            type: LanMessageTypes.offerBegin,
            sessionId: sessionId,
            body: [
                "batchId": .string(batchId),
                "itemCount": .int(Int64(preparedItems.count)),
                "totalBytes": .string(String(totalBatchBytes)),
                "pageCount": .int(Int64(pages.count))
            ]
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
            let pageRecord = try await sequence.createRecord(
                type: LanMessageTypes.offerPage,
                sessionId: sessionId,
                body: [
                    "batchId": .string(batchId),
                    "pageIndex": .int(Int64(pageIndex)),
                    "items": .array(itemArray)
                ]
            )
            try await channel.writeRecord(pageRecord)
        }

        let offerEndRecord = try await sequence.createRecord(
            type: LanMessageTypes.offerEnd,
            sessionId: sessionId,
            body: ["batchId": .string(batchId)]
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
            let itemStartRecord = try await sequence.createRecord(
                type: LanMessageTypes.itemStart,
                sessionId: sessionId,
                body: [
                    "batchId": .string(batchId),
                    "itemId": .string(item.itemId),
                    "bulkRef": .string(bulkRef.uuidString.lowercased()),
                    "sizeBytes": .string(String(item.sizeBytes))
                ]
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

            let itemEndRecord = try await sequence.createRecord(
                type: LanMessageTypes.itemEnd,
                sessionId: sessionId,
                body: [
                    "batchId": .string(batchId),
                    "itemId": .string(item.itemId),
                    "actualBytes": .string(String(streamedBytes)),
                    "sha256": .string(computedDigest)
                ]
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
        let batchEndRecord = try await sequence.createRecord(
            type: LanMessageTypes.batchEnd,
            sessionId: sessionId,
            body: ["batchId": .string(batchId)]
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
        onConsent: @escaping @Sendable (String, Int, Int64) async -> Bool,
        onProgress: (@Sendable (Double, Int64, Int64, String) -> Void)?
    ) async throws -> Int {
        var sessionId = UUID().uuidString.lowercased()
        let sequence = LanControlSequence()

        // 1. BOOTSTRAP
        if isConnectingToHostedSender {
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

            let result = try auth.consume(bootstrap, expectedRole: "sender")
            let resultRecord = try LanRecord.bootstrapResult(jsonBytes: LanBootstrapCodec.encode(result))
            try await channel.writeRecord(resultRecord)
            guard result.status == "accepted" else {
                throw TransferError.bootstrapRejected(result.code ?? "REJECTED")
            }
        }

        defer { releaseSession(sessionId) }
        let sequence = LanControlSequence()

        // 2. HELLO / HELLO_ACK
        let hello = try await sequence.readEnvelope(from: channel, expectedType: LanMessageTypes.hello, sessionId: sessionId)
        let peerName = hello.body["displayName"]?.stringValue ?? "Peer Device"

        let name = localDeviceName.isEmpty ? "Apple Receiver" : localDeviceName
        let helloAckRecord = try await sequence.createRecord(
            type: LanMessageTypes.helloAck,
            sessionId: sessionId,
            body: [
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
        )
        try await channel.writeRecord(helloAckRecord)

        // 3. Paged OFFERS
        let offerBegin = try await sequence.readEnvelope(from: channel, expectedType: LanMessageTypes.offerBegin, sessionId: sessionId)
        guard let batchId = offerBegin.body["batchId"]?.stringValue,
              let declaredItemCount = offerBegin.body["itemCount"]?.intValue,
              let declaredTotalBytesStr = offerBegin.body["totalBytes"]?.stringValue,
              let declaredTotalBytes = Int64(declaredTotalBytesStr),
              let pageCount = offerBegin.body["pageCount"]?.intValue else {
            throw TransferError.protocolViolation("Malformed OFFER_BEGIN")
        }

        guard declaredItemCount <= Int64(Self.maxBatchFiles), declaredTotalBytes <= Self.maxBatchBytes else {
            let decisionRecord = try await sequence.createRecord(
                type: LanMessageTypes.offerDecision,
                sessionId: sessionId,
                body: ["batchId": .string(batchId), "accepted": .bool(false), "code": .string(LanWireErrorCodes.limitExceeded)]
            )
            try await channel.writeRecord(decisionRecord)
            throw TransferError.limitExceeded("Batch exceeded limits")
        }

        var items = [TransferItemInfo]()
        for pageIdx in 0..<pageCount {
            let pageEnvelope = try await sequence.readEnvelope(from: channel, expectedType: LanMessageTypes.offerPage, sessionId: sessionId)
            guard pageEnvelope.body["batchId"]?.stringValue == batchId,
                  pageEnvelope.body["pageIndex"]?.intValue == Int64(pageIdx),
                  let rawItems = pageEnvelope.body["items"]?.arrayValue else {
                throw TransferError.protocolViolation("Malformed OFFER_PAGE")
            }
            for raw in rawItems {
                guard let obj = raw.objectValue,
                      let itemId = obj["itemId"]?.stringValue,
                      let itemName = obj["name"]?.stringValue,
                      let sizeStr = obj["sizeBytes"]?.stringValue,
                      let sizeBytes = Int64(sizeStr) else {
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

        let offerEnd = try await sequence.readEnvelope(from: channel, expectedType: LanMessageTypes.offerEnd, sessionId: sessionId)
        guard offerEnd.body["batchId"]?.stringValue == batchId,
              items.count == Int(declaredItemCount) else {
            throw TransferError.protocolViolation("OFFER_END count mismatch")
        }

        // 4. CONSENT
        let consentGranted = await onConsent(peerName, items.count, declaredTotalBytes)
        let decisionRecord = try await sequence.createRecord(
            type: LanMessageTypes.offerDecision,
            sessionId: sessionId,
            body: [
                "batchId": .string(batchId),
                "accepted": .bool(consentGranted),
                "code": .string(consentGranted ? "" : LanWireErrorCodes.userRejected)
            ]
        )
        try await channel.writeRecord(decisionRecord)
        guard consentGranted else {
            await channel.close()
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

            let itemReadyRecord = try await sequence.createRecord(
                type: LanMessageTypes.itemReady,
                sessionId: sessionId,
                body: ["batchId": .string(batchId), "itemId": .string(item.itemId)]
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

                let itemResultRecord = try await sequence.createRecord(
                    type: LanMessageTypes.itemResult,
                    sessionId: sessionId,
                    body: [
                        "batchId": .string(batchId),
                        "itemId": .string(item.itemId),
                        "status": .string("verified"),
                        "sizeBytes": .string(String(item.sizeBytes)),
                        "sha256": .string(declaredSha)
                    ]
                )
                try await channel.writeRecord(itemResultRecord)
            } catch {
                stager.purge()
                let itemResultRecord = try await sequence.createRecord(
                    type: LanMessageTypes.itemResult,
                    sessionId: sessionId,
                    body: [
                        "batchId": .string(batchId),
                        "itemId": .string(item.itemId),
                        "status": .string("failed"),
                        "code": .string(LanWireErrorCodes.integrityMismatch)
                    ]
                )
                try await channel.writeRecord(itemResultRecord)
            }
        }

        // 6. BATCH_END / BATCH_RESULT
        let batchEnd = try await sequence.readEnvelope(from: channel, expectedType: LanMessageTypes.batchEnd, sessionId: sessionId)
        guard batchEnd.body["batchId"]?.stringValue == batchId else {
            throw TransferError.protocolViolation("Malformed BATCH_END")
        }

        let batchResultRecord = try await sequence.createRecord(
            type: LanMessageTypes.batchResult,
            sessionId: sessionId,
            body: [
                "batchId": .string(batchId),
                "status": .string("completed"),
                "verifiedCount": .int(Int64(verifiedCount)),
                "totalVerifiedBytes": .string(String(cumulativeBytes))
            ]
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
