using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Glide.Core.Codec;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Core.Transport.Lan;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;

namespace Glide.Desktop;

/// <summary>
/// Shared desktop application/session engine for CLI and WPF.
///
/// HARD-011 centralized the desktop session implementation. HARD-012 aligned the authenticated
/// native LAN profile. HARD-013 makes the control session strict: independent monotonic direction
/// sequences, paged/validated offers, exact correlation, protocol deadlines, and truthful receipts.
/// HARD-014 still owns replacing pre-send whole-file digest reads with bounded streaming hashing.
/// </summary>
public sealed class DesktopTransferEngine
{
    private const int MaxOfferPageItems = 32;
    private const int MaxOfferMetadataBytes = 1024 * 1024;
    private static readonly HashSet<string> RequiredCapabilities =
        new(StringComparer.Ordinal) { "stream-v1", "sha256" };

    private readonly DesktopProtocolDeadlines _deadlines;

    public DesktopTransferEngine(DesktopProtocolDeadlines? deadlines = null)
    {
        _deadlines = deadlines ?? DesktopProtocolDeadlines.Default;
    }
    public async Task<DesktopTransferResult> SendAsync(
        DesktopSendRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        ArgumentNullException.ThrowIfNull(interaction);

        if (request.Files.Count == 0)
        {
            return DesktopTransferResult.Failed("No files were selected for transfer.");
        }

        foreach (var file in request.Files)
        {
            if (!File.Exists(file.FilePath))
            {
                return DesktopTransferResult.Failed($"File not found: '{file.FilePath}'.");
            }
        }

        try
        {
            if (request.Invitation == null)
            {
                return DesktopTransferResult.Failed(
                    "Authenticated QR/pairing invitation is required for LAN sending.");
            }

            string host = request.Host;
            int port = request.Port;
            if (request.Invitation.IsExpired())
            {
                return DesktopTransferResult.Failed(
                    "Pairing invitation has expired. Request a new pairing code.");
            }

            var endpoint = request.Invitation.Endpoints.First();
            host = endpoint.Address;
            port = endpoint.Port;

            interaction.OnStatus(
                DesktopTransferDirection.Send,
                $"Connecting to {host}:{port} over TLS 1.3...");

            var client = new TlsSocketClient();
            await using var connection = await client.ConnectAsync(
                host,
                port,
                expectedSpkiSha256: request.Invitation.SpkiSha256,
                cancellationToken);

            return await RunSenderSessionAsync(
                connection,
                request,
                interaction,
                cancellationToken);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            interaction.OnStatus(DesktopTransferDirection.Send, "Transfer cancelled.");
            return DesktopTransferResult.CancelledByUser("Transfer cancelled.");
        }
        catch (Exception ex)
        {
            interaction.OnStatus(DesktopTransferDirection.Send, $"Send failed: {ex.Message}");
            return DesktopTransferResult.Failed($"Send failed: {ex.Message}");
        }
    }

    public async Task<DesktopTransferResult> ReceiveAsync(
        TlsSocketConnection connection,
        DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(connection);
        ArgumentNullException.ThrowIfNull(request);
        ArgumentNullException.ThrowIfNull(interaction);

        try
        {
            return await RunReceiverSessionAsync(
                connection,
                request,
                interaction,
                cancellationToken);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            interaction.OnStatus(DesktopTransferDirection.Receive, "Transfer cancelled.");
            return DesktopTransferResult.CancelledByUser("Transfer cancelled.");
        }
        catch (Exception ex)
        {
            interaction.OnStatus(DesktopTransferDirection.Receive, $"Receive failed: {ex.Message}");
            return DesktopTransferResult.Failed($"Receive failed: {ex.Message}");
        }
    }

    private async Task<DesktopTransferResult> RunSenderSessionAsync(
        TlsSocketConnection connection,
        DesktopSendRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken)
    {
        await using var channel = new LanRecordChannel(connection.SslStream);
        var sessionId = NewSessionId();
        var protocol = new DesktopSessionProtocol(sessionId);

        var invitation = request.Invitation
            ?? throw new ProtocolViolationException(
                WireErrorCodes.AuthenticationFailed,
                "Authenticated pairing invitation is required.");

        var bootstrap = new LanBootstrapMessage(
            invitationId: invitation.InvitationId,
            token: invitation.Token,
            invitationHash: invitation.InvitationHash,
            sessionId: sessionId.Value,
            role: LanBootstrapMessage.RoleSender);

        await channel.WriteBootstrapAsync(bootstrap, cancellationToken);
        LanBootstrapResult bootstrapResult =
            await DesktopSessionProtocol.WithDeadlineAsync(
                ct => channel.ReadBootstrapResultAsync(ct),
                _deadlines.Bootstrap,
                "BOOTSTRAP_RESULT",
                cancellationToken);
        if (!bootstrapResult.IsAccepted)
        {
            return DesktopTransferResult.RejectedByPeer(
                $"Pairing bootstrap rejected by peer: {bootstrapResult.Code}");
        }

        interaction.OnInvitationConsumed(DesktopTransferDirection.Send);

        string localName = string.IsNullOrWhiteSpace(request.LocalDeviceName)
            ? Environment.MachineName
            : request.LocalDeviceName;

        await channel.WriteEnvelopeAsync(
            protocol.Create(
                MessageTypes.Hello,
                new JsonObject
                {
                    ["supportedMajors"] = new JsonArray(1),
                    ["requiredCapabilities"] =
                        new JsonArray("stream-v1", "sha256"),
                    ["optionalCapabilities"] = new JsonArray(),
                    ["role"] = "sender",
                    ["ephemeralPeerId"] =
                        Guid.NewGuid().ToString("D").ToLowerInvariant(),
                    ["displayName"] = localName
                }),
            cancellationToken);

        Envelope helloAck = await protocol.ReadExpectedAsync(
            channel,
            MessageTypes.HelloAck,
            _deadlines.Hello,
            cancellationToken);
        ValidateHelloAckCapabilities(helloAck);

        string peerName =
            helloAck.Body["displayName"]?.ToString() ?? request.Host;
        interaction.OnPeerIdentified(
            DesktopTransferDirection.Send,
            peerName);

        JsonObject peerLimits =
            helloAck.Body["limits"] as JsonObject
            ?? throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                "HELLO_ACK limits are missing.");
        int peerMaxItems =
            peerLimits["maxItems"]?.GetValue<int>()
            ?? throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                "HELLO_ACK maxItems is missing.");
        long peerMaxItemBytes = long.Parse(
            peerLimits["maxItemBytes"]?.ToString() ?? "0");
        long peerMaxBatchBytes = long.Parse(
            peerLimits["maxBatchBytes"]?.ToString() ?? "0");
        int peerMaxControlBytes =
            peerLimits["maxControlBytes"]?.GetValue<int>()
            ?? ProtocolCatalog.MaxControlBytes;

        var batchId =
            new BatchId(Guid.NewGuid().ToString("D").ToLowerInvariant());
        long totalBytes = checked(
            request.Files.Sum(static file => file.SizeBytes));

        if (request.Files.Count > peerMaxItems ||
            totalBytes > peerMaxBatchBytes ||
            request.Files.Any(file => file.SizeBytes > peerMaxItemBytes))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.LimitExceeded,
                "Transfer offer exceeds the receiver's negotiated limits.");
        }

        var offeredItems =
            new List<(string ItemId, DesktopTransferFile File, Sha256Digest Digest, JsonObject Metadata)>(
                request.Files.Count);

        foreach (DesktopTransferFile file in request.Files)
        {
            cancellationToken.ThrowIfCancellationRequested();

            var itemId =
                new ItemId(Guid.NewGuid().ToString("D").ToLowerInvariant());

            // HARD-014 replaces this pre-send whole-file read with bounded streaming hashing.
            byte[] bytes =
                await File.ReadAllBytesAsync(file.FilePath, cancellationToken);
            var digest = new Sha256Digest(
                Convert.ToHexStringLower(SHA256.HashData(bytes)));

            var metadata = new JsonObject
            {
                ["itemId"] = itemId.Value,
                ["name"] = file.Name,
                ["mime"] = "application/octet-stream",
                ["sizeBytes"] = file.SizeBytes.ToString(),
                ["relativePath"] = file.Name
            };
            offeredItems.Add((itemId.Value, file, digest, metadata));
        }

        List<List<JsonObject>> pages = BuildOfferPages(
            sessionId,
            batchId.Value,
            offeredItems.Select(item => item.Metadata).ToList(),
            peerMaxControlBytes);

        await channel.WriteEnvelopeAsync(
            protocol.Create(
                MessageTypes.OfferBegin,
                new JsonObject
                {
                    ["batchId"] = batchId.Value,
                    ["itemCount"] = offeredItems.Count,
                    ["totalBytes"] = totalBytes.ToString(),
                    ["pageCount"] = pages.Count
                }),
            cancellationToken);

        for (int pageIndex = 0; pageIndex < pages.Count; pageIndex++)
        {
            var itemsArray = new JsonArray();
            foreach (JsonObject item in pages[pageIndex])
            {
                itemsArray.Add(CloneObject(item));
            }

            await channel.WriteEnvelopeAsync(
                protocol.Create(
                    MessageTypes.OfferPage,
                    new JsonObject
                    {
                        ["batchId"] = batchId.Value,
                        ["pageIndex"] = pageIndex,
                        ["items"] = itemsArray
                    }),
                cancellationToken);
        }

        await channel.WriteEnvelopeAsync(
            protocol.Create(
                MessageTypes.OfferEnd,
                new JsonObject { ["batchId"] = batchId.Value }),
            cancellationToken);

        interaction.OnStatus(
            DesktopTransferDirection.Send,
            "Waiting for peer approval...");

        Envelope offerDecision = await protocol.ReadExpectedAsync(
            channel,
            MessageTypes.OfferDecision,
            _deadlines.OfferDecision,
            cancellationToken,
            expectedBatchId: batchId.Value);

        bool accepted =
            offerDecision.Body["accepted"]?.GetValue<bool>() ?? false;
        if (!accepted)
        {
            return DesktopTransferResult.RejectedByPeer(
                "Peer rejected the transfer offer.");
        }

        long cumulativeTransferred = 0;
        int verifiedCount = 0;
        long verifiedBytes = 0;

        foreach (var entry in offeredItems)
        {
            cancellationToken.ThrowIfCancellationRequested();

            interaction.OnItemStatus(
                DesktopTransferDirection.Send,
                entry.File.Name,
                "Sending");

            string bulkRef =
                Guid.NewGuid().ToString("D").ToLowerInvariant();

            await channel.WriteEnvelopeAsync(
                protocol.Create(
                    MessageTypes.ItemStart,
                    new JsonObject
                    {
                        ["batchId"] = batchId.Value,
                        ["itemId"] = entry.ItemId,
                        ["bulkRef"] = bulkRef,
                        ["sizeBytes"] = entry.File.SizeBytes.ToString()
                    }),
                cancellationToken);

            await protocol.ReadExpectedAsync(
                channel,
                MessageTypes.ItemReady,
                _deadlines.ItemReady,
                cancellationToken,
                batchId.Value,
                entry.ItemId);

            using (var stream = new FileStream(
                entry.File.FilePath,
                FileMode.Open,
                FileAccess.Read,
                FileShare.Read,
                65536,
                useAsync: true))
            {
                byte[] buffer = new byte[65520];
                long streamedBytes = 0;
                int read;
                while ((read = await stream.ReadAsync(
                    buffer.AsMemory(0, buffer.Length),
                    cancellationToken)) > 0)
                {
                    await channel.WriteDataChunkAsync(
                        bulkRef,
                        buffer.AsMemory(0, read),
                        cancellationToken);

                    streamedBytes += read;
                    cumulativeTransferred += read;
                    interaction.OnProgress(
                        new DesktopTransferProgress(
                            DesktopTransferDirection.Send,
                            peerName,
                            entry.File.Name,
                            cumulativeTransferred,
                            totalBytes));
                }

                if (streamedBytes != entry.File.SizeBytes)
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.SourceUnavailable,
                        $"File '{entry.File.Name}' changed length while streaming.");
                }

                byte[] extra = new byte[1];
                int extraRead = await stream.ReadAsync(
                    extra.AsMemory(0, 1),
                    cancellationToken);
                if (extraRead > 0)
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.SourceUnavailable,
                        $"File '{entry.File.Name}' grew while streaming.");
                }
            }

            await channel.WriteStreamEndAsync(
                bulkRef,
                entry.File.SizeBytes,
                cancellationToken);

            await channel.WriteEnvelopeAsync(
                protocol.Create(
                    MessageTypes.ItemEnd,
                    new JsonObject
                    {
                        ["batchId"] = batchId.Value,
                        ["itemId"] = entry.ItemId,
                        ["actualBytes"] = entry.File.SizeBytes.ToString(),
                        ["sha256"] = entry.Digest.Value
                    }),
                cancellationToken);

            Envelope itemResult = await protocol.ReadExpectedAsync(
                channel,
                MessageTypes.ItemResult,
                _deadlines.ItemReceipt,
                cancellationToken,
                batchId.Value,
                entry.ItemId);

            string resultStatus =
                itemResult.Body["status"]?.ToString() ?? string.Empty;
            if (!string.Equals(
                    resultStatus,
                    "verified",
                    StringComparison.Ordinal))
            {
                string code =
                    itemResult.Body["code"]?.ToString()
                    ?? WireErrorCodes.TransferInterrupted;
                interaction.OnItemStatus(
                    DesktopTransferDirection.Send,
                    entry.File.Name,
                    "Failed");
                return DesktopTransferResult.Failed(
                    $"Peer failed '{entry.File.Name}': {code}.");
            }

            long receiptSize = long.Parse(
                itemResult.Body["sizeBytes"]?.ToString() ?? "-1");
            string receiptSha =
                itemResult.Body["sha256"]?.ToString() ?? string.Empty;
            if (receiptSize != entry.File.SizeBytes ||
                !string.Equals(
                    receiptSha,
                    entry.Digest.Value,
                    StringComparison.Ordinal))
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.IntegrityMismatch,
                    $"ITEM_RESULT receipt for '{entry.File.Name}' does not match the sent size/digest.");
            }

            verifiedCount++;
            verifiedBytes += entry.File.SizeBytes;
            interaction.OnItemStatus(
                DesktopTransferDirection.Send,
                entry.File.Name,
                "Verified");
            interaction.OnHistory(
                new DesktopHistoryEntry(
                    DesktopTransferDirection.Send,
                    entry.File.Name,
                    entry.File.SizeBytes,
                    entry.File.FilePath,
                    peerName,
                    DateTimeOffset.Now));
        }

        if (verifiedCount != offeredItems.Count ||
            verifiedBytes != totalBytes)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                "Sender cannot terminate a batch before every accepted item is verified.");
        }

        await channel.WriteEnvelopeAsync(
            protocol.Create(
                MessageTypes.BatchEnd,
                new JsonObject { ["batchId"] = batchId.Value }),
            cancellationToken);

        Envelope batchResult = await protocol.ReadExpectedAsync(
            channel,
            MessageTypes.BatchResult,
            _deadlines.BatchResult,
            cancellationToken,
            expectedBatchId: batchId.Value);

        int peerVerifiedCount =
            batchResult.Body["verifiedCount"]?.GetValue<int>() ?? -1;
        long peerVerifiedBytes = long.Parse(
            batchResult.Body["totalVerifiedBytes"]?.ToString() ?? "-1");

        if (peerVerifiedCount != offeredItems.Count ||
            peerVerifiedBytes != totalBytes)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.IntegrityMismatch,
                "BATCH_RESULT does not match the already-verified accepted offer.");
        }

        string message =
            $"Transfer complete: peer verified {peerVerifiedCount} item(s) ({peerVerifiedBytes} bytes).";
        interaction.OnStatus(DesktopTransferDirection.Send, message);
        return DesktopTransferResult.Completed(
            message,
            peerVerifiedCount,
            peerVerifiedBytes);
    }

    private async Task<DesktopTransferResult> RunReceiverSessionAsync(
        TlsSocketConnection connection,
        DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken)
    {
        await using var channel = new LanRecordChannel(connection.SslStream);

        LanRecord? firstRecord = await DesktopSessionProtocol.WithDeadlineAsync(
            ct => channel.ReadRecordAsync(ct),
            _deadlines.Bootstrap,
            "BOOTSTRAP",
            cancellationToken);
        if (firstRecord == null)
        {
            return DesktopTransferResult.Failed(
                "Peer disconnected before sending the first record.");
        }

        if (firstRecord is not LanRecord.Bootstrap bootstrapRecord)
        {
            if (firstRecord is LanRecord.Control)
            {
                return DesktopTransferResult.RejectedByPeer(
                    "Unauthenticated direct LAN sessions are disabled; a fresh QR/pairing invitation is required.");
            }

            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                $"Unexpected first record kind: {firstRecord.Kind}");
        }

        LanBootstrapMessage bootstrap =
            LanBootstrapCodec.DecodeBootstrap(bootstrapRecord.JsonBytes);
        LanBootstrapResult bootstrapResult =
            request.InvitationAuthority?.Consume(bootstrap)
            ?? LanBootstrapResult.Rejected(
                LanBootstrapFailureCode.AUTHENTICATION_FAILED);

        await channel.WriteBootstrapResultAsync(
            bootstrapResult,
            cancellationToken);
        if (!bootstrapResult.IsAccepted)
        {
            return DesktopTransferResult.RejectedByPeer(
                $"Inbound pairing bootstrap rejected: {bootstrapResult.Code}.");
        }

        interaction.OnInvitationConsumed(DesktopTransferDirection.Receive);

        var sessionId = new SessionId(bootstrap.SessionId);
        var protocol = new DesktopSessionProtocol(sessionId);

        Envelope hello = await protocol.ReadExpectedAsync(
            channel,
            MessageTypes.Hello,
            _deadlines.Hello,
            cancellationToken);
        ValidateSenderHello(hello);

        string peerName =
            hello.Body["displayName"]?.ToString() ?? "Remote Peer";
        interaction.OnPeerIdentified(
            DesktopTransferDirection.Receive,
            peerName);

        string localName = string.IsNullOrWhiteSpace(request.LocalDeviceName)
            ? Environment.MachineName
            : request.LocalDeviceName;

        var limits = new JsonObject
        {
            ["maxItems"] = 1000,
            ["maxItemBytes"] = ProtocolCatalog.MaxItemBytes.ToString(),
            ["maxBatchBytes"] = ProtocolCatalog.MaxBatchBytes.ToString(),
            ["maxControlBytes"] = ProtocolCatalog.MaxControlBytes
        };

        await channel.WriteEnvelopeAsync(
            protocol.Create(
                MessageTypes.HelloAck,
                new JsonObject
                {
                    ["selectedMajor"] = 1,
                    ["capabilities"] =
                        new JsonArray("stream-v1", "sha256"),
                    ["ephemeralPeerId"] =
                        Guid.NewGuid().ToString("D").ToLowerInvariant(),
                    ["displayName"] = localName,
                    ["limits"] = limits
                }),
            cancellationToken);

        Envelope offerBegin = await protocol.ReadExpectedAsync(
            channel,
            MessageTypes.OfferBegin,
            _deadlines.OfferPageGap,
            cancellationToken);

        string batchId =
            offerBegin.Body["batchId"]?.ToString()
            ?? throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                "OFFER_BEGIN batchId missing.");
        int expectedItemCount =
            offerBegin.Body["itemCount"]?.GetValue<int>() ?? 0;
        long expectedTotalBytes = long.Parse(
            offerBegin.Body["totalBytes"]?.ToString() ?? "0");
        int pageCount =
            offerBegin.Body["pageCount"]?.GetValue<int>() ?? 0;

        var items = new List<DesktopOfferItem>(expectedItemCount);
        var itemIds = new HashSet<string>(StringComparer.Ordinal);
        long assembledBytes = 0;
        int metadataBytes = 0;

        for (int expectedPageIndex = 0;
             expectedPageIndex < pageCount;
             expectedPageIndex++)
        {
            Envelope offerPage = await protocol.ReadExpectedAsync(
                channel,
                MessageTypes.OfferPage,
                _deadlines.OfferPageGap,
                cancellationToken,
                expectedBatchId: batchId);

            int actualPageIndex =
                offerPage.Body["pageIndex"]?.GetValue<int>() ?? -1;
            if (actualPageIndex != expectedPageIndex)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.ProtocolOrder,
                    $"Expected OFFER_PAGE index {expectedPageIndex} but received {actualPageIndex}.");
            }

            if (offerPage.Body["items"] is not JsonArray itemArray)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.MalformedMessage,
                    "OFFER_PAGE items are missing.");
            }

            metadataBytes = checked(
                metadataBytes +
                Encoding.UTF8.GetByteCount(itemArray.ToJsonString()));
            if (metadataBytes > MaxOfferMetadataBytes)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.LimitExceeded,
                    $"Offer metadata exceeds {MaxOfferMetadataBytes} bytes.");
            }

            foreach (JsonNode? node in itemArray)
            {
                if (node is not JsonObject itemObject)
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.MalformedMessage,
                        "OFFER_PAGE item must be an object.");
                }

                string itemId = itemObject["itemId"]!.ToString();
                if (!itemIds.Add(itemId))
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.MalformedMessage,
                        $"Duplicate itemId '{itemId}' in offer.");
                }

                string name = itemObject["name"]!.ToString();
                long size =
                    long.Parse(itemObject["sizeBytes"]!.ToString());
                assembledBytes = checked(assembledBytes + size);
                string relativePath =
                    itemObject.TryGetPropertyValue(
                        "relativePath",
                        out JsonNode? relative) &&
                    relative != null
                        ? relative.ToString()
                        : name;

                items.Add(
                    new DesktopOfferItem(
                        itemId,
                        name,
                        size,
                        relativePath));
            }
        }

        await protocol.ReadExpectedAsync(
            channel,
            MessageTypes.OfferEnd,
            _deadlines.OfferPageGap,
            cancellationToken,
            expectedBatchId: batchId);

        if (items.Count != expectedItemCount ||
            assembledBytes != expectedTotalBytes)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"Offer assembly mismatch: expected {expectedItemCount} items/{expectedTotalBytes} bytes, assembled {items.Count}/{assembledBytes}.");
        }

        // Consent is requested only after every page/count/size/identifier invariant is valid.
        bool offerAccepted = await interaction.ConfirmOfferAsync(
            new DesktopOffer(
                batchId,
                peerName,
                items,
                expectedTotalBytes),
            cancellationToken);

        var decisionBody = new JsonObject
        {
            ["batchId"] = batchId,
            ["accepted"] = offerAccepted
        };
        if (!offerAccepted)
        {
            decisionBody["code"] = WireErrorCodes.UserRejected;
        }

        await channel.WriteEnvelopeAsync(
            protocol.Create(
                MessageTypes.OfferDecision,
                decisionBody),
            cancellationToken);

        if (!offerAccepted)
        {
            return DesktopTransferResult.RejectedByPeer(
                "Transfer offer was rejected.");
        }

        long cumulativeTransferred = 0;
        int verifiedCount = 0;
        long verifiedBytes = 0;
        var bulkRefs = new HashSet<string>(StringComparer.Ordinal);

        foreach (DesktopOfferItem item in items)
        {
            cancellationToken.ThrowIfCancellationRequested();
            interaction.OnItemStatus(
                DesktopTransferDirection.Receive,
                item.Name,
                "Receiving");

            Envelope itemStart = await protocol.ReadExpectedAsync(
                channel,
                MessageTypes.ItemStart,
                _deadlines.ItemReady,
                cancellationToken,
                batchId,
                item.ItemId);

            long announcedSize =
                long.Parse(itemStart.Body["sizeBytes"]?.ToString() ?? "-1");
            if (announcedSize != item.SizeBytes)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.ProtocolOrder,
                    $"ITEM_START size for '{item.Name}' does not match the accepted offer.");
            }

            string bulkRef =
                itemStart.Body["bulkRef"]?.ToString()
                ?? throw new ProtocolViolationException(
                    WireErrorCodes.MalformedMessage,
                    "ITEM_START bulkRef missing.");
            if (!bulkRefs.Add(bulkRef))
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.ProtocolOrder,
                    $"bulkRef '{bulkRef}' was reused in the same session.");
            }

            await channel.WriteEnvelopeAsync(
                protocol.Create(
                    MessageTypes.ItemReady,
                    new JsonObject
                    {
                        ["batchId"] = batchId,
                        ["itemId"] = item.ItemId
                    }),
                cancellationToken);

            string quarantinePath = request.StagingStore.GetQuarantinePath(
                sessionId,
                new ItemId(item.ItemId));
            string? quarantineDirectory =
                Path.GetDirectoryName(quarantinePath);
            if (quarantineDirectory != null &&
                !Directory.Exists(quarantineDirectory))
            {
                Directory.CreateDirectory(quarantineDirectory);
            }

            byte[] hash;
            long receivedBytes = 0;
            using (var fileStream = new FileStream(
                quarantinePath,
                FileMode.Create,
                FileAccess.Write,
                FileShare.None,
                65536,
                useAsync: true))
            using (var sha256 =
                IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
            {
                bool firstBulkRecord = true;
                while (true)
                {
                    TimeSpan progressDeadline = firstBulkRecord
                        ? _deadlines.FirstBulkBytes
                        : _deadlines.BulkProgress;
                    LanRecord? record =
                        await DesktopSessionProtocol.WithDeadlineAsync(
                            ct => channel.ReadRecordAsync(ct),
                            progressDeadline,
                            firstBulkRecord
                                ? "first bulk record"
                                : "bulk stream progress",
                            cancellationToken);
                    if (record == null)
                    {
                        throw new EndOfStreamException(
                            "Connection terminated during payload transfer.");
                    }

                    if (record is LanRecord.Data dataRecord)
                    {
                        firstBulkRecord = false;
                        if (!string.Equals(
                            dataRecord.BulkRef.Value,
                            bulkRef,
                            StringComparison.Ordinal))
                        {
                            throw new ProtocolViolationException(
                                WireErrorCodes.MalformedMessage,
                                "Unmatched bulkRef in DATA record.");
                        }

                        receivedBytes = checked(
                            receivedBytes + dataRecord.Chunk.Length);
                        if (receivedBytes > item.SizeBytes)
                        {
                            throw new ProtocolViolationException(
                                WireErrorCodes.IntegrityMismatch,
                                $"Received more bytes than offered for '{item.Name}'.");
                        }

                        await fileStream.WriteAsync(
                            dataRecord.Chunk.AsMemory(),
                            cancellationToken);
                        sha256.AppendData(dataRecord.Chunk);
                        cumulativeTransferred += dataRecord.Chunk.Length;

                        interaction.OnProgress(
                            new DesktopTransferProgress(
                                DesktopTransferDirection.Receive,
                                peerName,
                                item.Name,
                                cumulativeTransferred,
                                expectedTotalBytes));
                    }
                    else if (record is LanRecord.StreamEnd streamEnd)
                    {
                        if (!string.Equals(
                            streamEnd.BulkRef.Value,
                            bulkRef,
                            StringComparison.Ordinal))
                        {
                            throw new ProtocolViolationException(
                                WireErrorCodes.MalformedMessage,
                                "Unmatched bulkRef in STREAM_END record.");
                        }

                        if (streamEnd.ActualBytes != receivedBytes ||
                            receivedBytes != item.SizeBytes)
                        {
                            throw new ProtocolViolationException(
                                WireErrorCodes.IntegrityMismatch,
                                $"STREAM_END actualBytes {streamEnd.ActualBytes} does not match expected {item.SizeBytes}.");
                        }
                        break;
                    }
                    else
                    {
                        throw new ProtocolViolationException(
                            WireErrorCodes.ProtocolOrder,
                            $"Unexpected record kind during data stream: {record.Kind}");
                    }
                }

                hash = sha256.GetHashAndReset();
            }

            Envelope itemEnd = await protocol.ReadExpectedAsync(
                channel,
                MessageTypes.ItemEnd,
                _deadlines.ItemReceipt,
                cancellationToken,
                batchId,
                item.ItemId);

            long declaredBytes =
                long.Parse(itemEnd.Body["actualBytes"]?.ToString() ?? "-1");
            string declaredSha =
                itemEnd.Body["sha256"]?.ToString() ?? string.Empty;
            string actualSha = Convert.ToHexStringLower(hash);

            if (declaredBytes != item.SizeBytes ||
                !string.Equals(
                    actualSha,
                    declaredSha,
                    StringComparison.Ordinal))
            {
                request.StagingStore.PurgeQuarantine(
                    sessionId,
                    new ItemId(item.ItemId));

                await channel.WriteEnvelopeAsync(
                    protocol.Create(
                        MessageTypes.ItemResult,
                        new JsonObject
                        {
                            ["batchId"] = batchId,
                            ["itemId"] = item.ItemId,
                            ["status"] = "failed",
                            ["code"] = WireErrorCodes.IntegrityMismatch
                        }),
                    cancellationToken);

                interaction.OnItemStatus(
                    DesktopTransferDirection.Receive,
                    item.Name,
                    "Failed");
                return DesktopTransferResult.Failed(
                    $"Integrity verification failed for '{item.Name}'.");
            }

            string targetPath = Path.Combine(
                request.StagingStore.DestinationRoot,
                item.RelativePath.Replace(
                    '/',
                    Path.DirectorySeparatorChar));
            string finalPath =
                QuarantineStagingStore.ResolveCollision(targetPath);

            string? targetDirectory = Path.GetDirectoryName(finalPath);
            if (targetDirectory != null &&
                !Directory.Exists(targetDirectory))
            {
                Directory.CreateDirectory(targetDirectory);
            }

            File.Move(quarantinePath, finalPath, overwrite: false);
            verifiedCount++;
            verifiedBytes += item.SizeBytes;

            await channel.WriteEnvelopeAsync(
                protocol.Create(
                    MessageTypes.ItemResult,
                    new JsonObject
                    {
                        ["batchId"] = batchId,
                        ["itemId"] = item.ItemId,
                        ["status"] = "verified",
                        ["sizeBytes"] = item.SizeBytes.ToString(),
                        ["sha256"] = actualSha
                    }),
                cancellationToken);

            interaction.OnItemStatus(
                DesktopTransferDirection.Receive,
                item.Name,
                "Verified");
            interaction.OnHistory(
                new DesktopHistoryEntry(
                    DesktopTransferDirection.Receive,
                    item.Name,
                    item.SizeBytes,
                    finalPath,
                    peerName,
                    DateTimeOffset.Now));
        }

        if (verifiedCount != items.Count ||
            verifiedBytes != expectedTotalBytes)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                "Receiver cannot complete a batch with unverified accepted items.");
        }

        await protocol.ReadExpectedAsync(
            channel,
            MessageTypes.BatchEnd,
            _deadlines.BatchResult,
            cancellationToken,
            expectedBatchId: batchId);

        await channel.WriteEnvelopeAsync(
            protocol.Create(
                MessageTypes.BatchResult,
                new JsonObject
                {
                    ["batchId"] = batchId,
                    ["status"] = "completed",
                    ["verifiedCount"] = verifiedCount,
                    ["totalVerifiedBytes"] = verifiedBytes.ToString()
                }),
            cancellationToken);

        string message =
            $"Transfer complete: {verifiedCount} file(s) safely saved ({verifiedBytes} bytes).";
        interaction.OnStatus(DesktopTransferDirection.Receive, message);
        return DesktopTransferResult.Completed(
            message,
            verifiedCount,
            verifiedBytes);
    }

    private static void ValidateSenderHello(Envelope hello)
    {
        if (!string.Equals(
                hello.Body["role"]?.ToString(),
                "sender",
                StringComparison.Ordinal))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                "HELLO role must be sender for an inbound transfer.");
        }

        if (hello.Body["supportedMajors"] is not JsonArray majors ||
            !majors.Any(node => node?.ToString() == "1"))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.UnsupportedProtocol,
                "Peer did not offer protocol major 1.");
        }

        ValidateRequiredCapabilities(
            hello.Body["requiredCapabilities"] as JsonArray,
            "HELLO");
    }

    private static void ValidateHelloAckCapabilities(Envelope helloAck)
    {
        ValidateRequiredCapabilities(
            helloAck.Body["capabilities"] as JsonArray,
            "HELLO_ACK");
    }

    private static void ValidateRequiredCapabilities(
        JsonArray? capabilities,
        string messageType)
    {
        if (capabilities == null)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.UnsupportedProtocol,
                $"{messageType} capabilities are missing.");
        }

        var values = capabilities
            .Select(node => node?.ToString() ?? string.Empty)
            .ToHashSet(StringComparer.Ordinal);
        if (!RequiredCapabilities.IsSubsetOf(values))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.UnsupportedProtocol,
                $"{messageType} does not include required capabilities stream-v1 and sha256.");
        }
    }

    private static List<List<JsonObject>> BuildOfferPages(
        SessionId sessionId,
        string batchId,
        IReadOnlyList<JsonObject> items,
        int maxControlBytes)
    {
        var pages = new List<List<JsonObject>>();
        var current = new List<JsonObject>();
        int metadataBytes = 0;

        foreach (JsonObject item in items)
        {
            metadataBytes = checked(
                metadataBytes +
                Encoding.UTF8.GetByteCount(item.ToJsonString()));
            if (metadataBytes > MaxOfferMetadataBytes)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.LimitExceeded,
                    $"Offer metadata exceeds {MaxOfferMetadataBytes} bytes.");
            }

            var candidate = current
                .Select(CloneObject)
                .ToList();
            candidate.Add(CloneObject(item));

            bool candidateFits =
                candidate.Count <= MaxOfferPageItems &&
                OfferPageFits(
                    sessionId,
                    batchId,
                    pages.Count,
                    candidate,
                    maxControlBytes);

            if (!candidateFits)
            {
                if (current.Count == 0)
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.LimitExceeded,
                        "A single offer item cannot fit the negotiated control-message limit.");
                }

                pages.Add(current);
                current = [CloneObject(item)];

                if (!OfferPageFits(
                        sessionId,
                        batchId,
                        pages.Count,
                        current,
                        maxControlBytes))
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.LimitExceeded,
                        "A single offer item cannot fit the negotiated control-message limit.");
                }
            }
            else
            {
                current = candidate;
            }
        }

        if (current.Count > 0)
        {
            pages.Add(current);
        }

        if (pages.Count == 0)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                "Empty transfer offers are not valid.");
        }

        return pages;
    }

    private static bool OfferPageFits(
        SessionId sessionId,
        string batchId,
        int pageIndex,
        IReadOnlyList<JsonObject> items,
        int maxControlBytes)
    {
        var array = new JsonArray();
        foreach (JsonObject item in items)
        {
            array.Add(CloneObject(item));
        }

        try
        {
            byte[] bytes = ProtocolCodec.Encode(
                new Envelope(
                    1,
                    MessageTypes.OfferPage,
                    sessionId,
                    NewMessageId(),
                    1,
                    new JsonObject
                    {
                        ["batchId"] = batchId,
                        ["pageIndex"] = pageIndex,
                        ["items"] = array
                    }));
            return bytes.Length <= maxControlBytes;
        }
        catch (ProtocolViolationException ex)
            when (ex.FailureCode == WireErrorCodes.LimitExceeded)
        {
            return false;
        }
    }

    private static JsonObject CloneObject(JsonObject value) =>
        JsonNode.Parse(value.ToJsonString()) as JsonObject
        ?? throw new InvalidOperationException("Failed to clone offer metadata.");

    private static SessionId NewSessionId() =>
        new(Guid.NewGuid().ToString("D").ToLowerInvariant());

    private static MessageId NewMessageId() =>
        new(Guid.NewGuid().ToString("D").ToLowerInvariant());
}
