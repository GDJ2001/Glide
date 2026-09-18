using System.Diagnostics;
using System.Security.Cryptography;
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
/// HARD-011 deliberately centralizes the currently-shipped desktop session semantics without
/// attempting the protocol repairs owned by HARD-012/HARD-013/HARD-014. In particular, the
/// existing one-page offer, current sequence values, direct/unpaired SAS construction, and
/// pre-send whole-file digest behavior are preserved here so later hardening tasks have one
/// implementation to repair rather than two divergent copies.
/// </summary>
public sealed class DesktopTransferEngine
{
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

    private static async Task<DesktopTransferResult> RunSenderSessionAsync(
        TlsSocketConnection connection,
        DesktopSendRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken)
    {
        await using var channel = new LanRecordChannel(connection.SslStream);
        var sessionId = NewSessionId();

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
        var result = await channel.ReadBootstrapResultAsync(cancellationToken);
        if (!result.IsAccepted)
        {
            return DesktopTransferResult.RejectedByPeer(
                $"Pairing bootstrap rejected by peer: {result.Code}");
        }

        interaction.OnInvitationConsumed(DesktopTransferDirection.Send);

        string localName = string.IsNullOrWhiteSpace(request.LocalDeviceName)
            ? Environment.MachineName
            : request.LocalDeviceName;

        var helloBody = new JsonObject
        {
            ["supportedMajors"] = new JsonArray(1),
            ["requiredCapabilities"] = new JsonArray(),
            ["optionalCapabilities"] = new JsonArray("folder-v1"),
            ["role"] = "sender",
            ["ephemeralPeerId"] = Guid.NewGuid().ToString("D").ToLowerInvariant(),
            ["displayName"] = localName
        };

        await channel.WriteEnvelopeAsync(
            new Envelope(
                1,
                MessageTypes.Hello,
                sessionId,
                NewMessageId(),
                1,
                helloBody),
            cancellationToken);

        var helloAck = await channel.ReadEnvelopeAsync(cancellationToken);
        string peerName = helloAck.Body["displayName"]?.ToString() ?? request.Host;
        interaction.OnPeerIdentified(DesktopTransferDirection.Send, peerName);

        var batchId = new BatchId(Guid.NewGuid().ToString("D").ToLowerInvariant());
        long totalBytes = request.Files.Sum(static file => file.SizeBytes);

        var offerBeginBody = new JsonObject
        {
            ["batchId"] = batchId.Value,
            ["itemCount"] = request.Files.Count,
            ["totalBytes"] = totalBytes.ToString(),
            ["pageCount"] = 1
        };

        await channel.WriteEnvelopeAsync(
            new Envelope(
                1,
                MessageTypes.OfferBegin,
                sessionId,
                NewMessageId(),
                2,
                offerBeginBody),
            cancellationToken);

        var itemsArray = new JsonArray();
        var itemMap =
            new Dictionary<string, (DesktopTransferFile File, Sha256Digest Digest)>();

        foreach (var file in request.Files)
        {
            cancellationToken.ThrowIfCancellationRequested();

            var itemId = new ItemId(Guid.NewGuid().ToString("D").ToLowerInvariant());

            // Preserved current behavior. HARD-014 replaces this with bounded streaming hashing.
            byte[] bytes = await File.ReadAllBytesAsync(file.FilePath, cancellationToken);
            var digest = new Sha256Digest(
                Convert.ToHexStringLower(SHA256.HashData(bytes)));

            itemMap[itemId.Value] = (file, digest);
            itemsArray.Add(
                new JsonObject
                {
                    ["itemId"] = itemId.Value,
                    ["name"] = file.Name,
                    ["mime"] = "application/octet-stream",
                    ["sizeBytes"] = file.SizeBytes.ToString(),
                    ["relativePath"] = file.Name
                });
        }

        await channel.WriteEnvelopeAsync(
            new Envelope(
                1,
                MessageTypes.OfferPage,
                sessionId,
                NewMessageId(),
                3,
                new JsonObject
                {
                    ["batchId"] = batchId.Value,
                    ["pageIndex"] = 0,
                    ["items"] = itemsArray
                }),
            cancellationToken);

        await channel.WriteEnvelopeAsync(
            new Envelope(
                1,
                MessageTypes.OfferEnd,
                sessionId,
                NewMessageId(),
                4,
                new JsonObject { ["batchId"] = batchId.Value }),
            cancellationToken);

        interaction.OnStatus(
            DesktopTransferDirection.Send,
            "Waiting for peer approval...");

        var offerDecision = await channel.ReadEnvelopeAsync(cancellationToken);
        bool accepted = offerDecision.Body["accepted"]?.GetValue<bool>() ?? false;
        if (!accepted)
        {
            return DesktopTransferResult.RejectedByPeer(
                "Peer rejected the transfer offer.");
        }

        long cumulativeTransferred = 0;
        int verifiedCount = 0;
        long verifiedBytes = 0;

        foreach (var (itemId, entry) in itemMap)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var file = entry.File;

            interaction.OnItemStatus(
                DesktopTransferDirection.Send,
                file.Name,
                "Sending");

            string bulkRef = Guid.NewGuid().ToString("D").ToLowerInvariant();
            await channel.WriteEnvelopeAsync(
                new Envelope(
                    1,
                    MessageTypes.ItemStart,
                    sessionId,
                    NewMessageId(),
                    5,
                    new JsonObject
                    {
                        ["batchId"] = batchId.Value,
                        ["itemId"] = itemId,
                        ["bulkRef"] = bulkRef,
                        ["sizeBytes"] = file.SizeBytes.ToString()
                    }),
                cancellationToken);

            await channel.ReadEnvelopeAsync(cancellationToken); // ITEM_READY

            using (var stream = new FileStream(
                file.FilePath,
                FileMode.Open,
                FileAccess.Read,
                FileShare.Read,
                65536,
                useAsync: true))
            {
                byte[] buffer = new byte[65520];
                int read;
                while ((read = await stream.ReadAsync(
                    buffer.AsMemory(0, buffer.Length),
                    cancellationToken)) > 0)
                {
                    await channel.WriteDataChunkAsync(
                        bulkRef,
                        buffer.AsMemory(0, read),
                        cancellationToken);

                    cumulativeTransferred += read;
                    interaction.OnProgress(
                        new DesktopTransferProgress(
                            DesktopTransferDirection.Send,
                            peerName,
                            file.Name,
                            cumulativeTransferred,
                            totalBytes));
                }

                byte[] extra = new byte[1];
                int extraRead = await stream.ReadAsync(
                    extra.AsMemory(0, 1),
                    cancellationToken);
                if (extraRead > 0)
                {
                    throw new InvalidOperationException(
                        $"File {file.Name} grew while streaming.");
                }
            }

            await channel.WriteStreamEndAsync(
                bulkRef,
                file.SizeBytes,
                cancellationToken);

            await channel.WriteEnvelopeAsync(
                new Envelope(
                    1,
                    MessageTypes.ItemEnd,
                    sessionId,
                    NewMessageId(),
                    6,
                    new JsonObject
                    {
                        ["batchId"] = batchId.Value,
                        ["itemId"] = itemId,
                        ["actualBytes"] = file.SizeBytes.ToString(),
                        ["sha256"] = entry.Digest.Value
                    }),
                cancellationToken);

            var itemResult = await channel.ReadEnvelopeAsync(cancellationToken);
            bool verified = string.Equals(
                itemResult.Body["status"]?.ToString(),
                "verified",
                StringComparison.OrdinalIgnoreCase);

            interaction.OnItemStatus(
                DesktopTransferDirection.Send,
                file.Name,
                verified ? "Verified" : "Failed");

            if (verified)
            {
                verifiedCount++;
                verifiedBytes += file.SizeBytes;
                interaction.OnHistory(
                    new DesktopHistoryEntry(
                        DesktopTransferDirection.Send,
                        file.Name,
                        file.SizeBytes,
                        file.FilePath,
                        peerName,
                        DateTimeOffset.Now));
            }
        }

        await channel.WriteEnvelopeAsync(
            new Envelope(
                1,
                MessageTypes.BatchEnd,
                sessionId,
                NewMessageId(),
                7,
                new JsonObject { ["batchId"] = batchId.Value }),
            cancellationToken);

        var batchResult = await channel.ReadEnvelopeAsync(cancellationToken);
        if (int.TryParse(
            batchResult.Body["verifiedCount"]?.ToString(),
            out int peerVerifiedCount))
        {
            verifiedCount = peerVerifiedCount;
        }

        if (long.TryParse(
            batchResult.Body["totalVerifiedBytes"]?.ToString(),
            out long peerVerifiedBytes))
        {
            verifiedBytes = peerVerifiedBytes;
        }

        string message =
            $"Transfer complete: peer verified {verifiedCount} item(s) ({verifiedBytes} bytes).";
        interaction.OnStatus(DesktopTransferDirection.Send, message);
        return DesktopTransferResult.Completed(
            message,
            verifiedCount,
            verifiedBytes);
    }

    private static async Task<DesktopTransferResult> RunReceiverSessionAsync(
        TlsSocketConnection connection,
        DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken)
    {
        await using var channel = new LanRecordChannel(connection.SslStream);

        var firstRecord = await channel.ReadRecordAsync(cancellationToken);
        if (firstRecord == null)
        {
            return DesktopTransferResult.Failed(
                "Peer disconnected before sending the first record.");
        }

        Envelope hello;
        if (firstRecord is LanRecord.Bootstrap bootstrapRecord)
        {
            var bootstrap = LanBootstrapCodec.DecodeBootstrap(
                bootstrapRecord.JsonBytes);

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
            hello = await channel.ReadEnvelopeAsync(cancellationToken);
        }
        else if (firstRecord is LanRecord.Control)
        {
            return DesktopTransferResult.RejectedByPeer(
                "Unauthenticated direct LAN sessions are disabled; a fresh QR/pairing invitation is required.");
        }
        else
        {
            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                $"Unexpected first record kind: {firstRecord.Kind}");
        }

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
            new Envelope(
                1,
                MessageTypes.HelloAck,
                hello.SessionId,
                NewMessageId(),
                2,
                new JsonObject
                {
                    ["selectedMajor"] = 1,
                    ["capabilities"] = new JsonArray("lan-v1", "folder-v1"),
                    ["ephemeralPeerId"] =
                        Guid.NewGuid().ToString("D").ToLowerInvariant(),
                    ["displayName"] = localName,
                    ["limits"] = limits
                }),
            cancellationToken);

        var offerBegin = await channel.ReadEnvelopeAsync(cancellationToken);
        int itemCount =
            offerBegin.Body["itemCount"]?.GetValue<int>() ?? 0;
        long totalBytes = long.Parse(
            offerBegin.Body["totalBytes"]?.ToString() ?? "0");

        var offerPage = await channel.ReadEnvelopeAsync(cancellationToken);
        var items = new List<DesktopOfferItem>();
        if (offerPage.Body["items"] is JsonArray itemArray)
        {
            foreach (var node in itemArray)
            {
                if (node is not JsonObject itemObject)
                {
                    continue;
                }

                string itemId = itemObject["itemId"]!.ToString();
                string name = itemObject["name"]!.ToString();
                long size = long.Parse(itemObject["sizeBytes"]!.ToString());
                string relativePath =
                    itemObject.TryGetPropertyValue(
                        "relativePath",
                        out var relative) &&
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

        await channel.ReadEnvelopeAsync(cancellationToken); // OFFER_END

        string batchId = offerBegin.Body["batchId"]!.ToString();
        bool offerAccepted = await interaction.ConfirmOfferAsync(
            new DesktopOffer(
                batchId,
                peerName,
                items,
                totalBytes),
            cancellationToken);

        var decisionBody = new JsonObject
        {
            ["batchId"] = batchId,
            ["accepted"] = offerAccepted
        };
        if (!offerAccepted)
        {
            decisionBody["code"] = "USER_REJECTED";
        }

        await channel.WriteEnvelopeAsync(
            new Envelope(
                1,
                MessageTypes.OfferDecision,
                hello.SessionId,
                NewMessageId(),
                3,
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

        foreach (var item in items)
        {
            cancellationToken.ThrowIfCancellationRequested();
            interaction.OnItemStatus(
                DesktopTransferDirection.Receive,
                item.Name,
                "Receiving");

            var itemStart = await channel.ReadEnvelopeAsync(cancellationToken);
            string bulkRef =
                itemStart.Body["bulkRef"]?.ToString() ??
                Guid.NewGuid().ToString("D").ToLowerInvariant();

            await channel.WriteEnvelopeAsync(
                new Envelope(
                    1,
                    MessageTypes.ItemReady,
                    hello.SessionId,
                    NewMessageId(),
                    4,
                    new JsonObject
                    {
                        ["batchId"] = batchId,
                        ["itemId"] = item.ItemId
                    }),
                cancellationToken);

            string quarantinePath = request.StagingStore.GetQuarantinePath(
                hello.SessionId,
                new ItemId(item.ItemId));
            string? quarantineDirectory =
                Path.GetDirectoryName(quarantinePath);
            if (quarantineDirectory != null &&
                !Directory.Exists(quarantineDirectory))
            {
                Directory.CreateDirectory(quarantineDirectory);
            }

            byte[] hash;
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
                long receivedBytes = 0;
                while (true)
                {
                    var record =
                        await channel.ReadRecordAsync(cancellationToken);
                    if (record == null)
                    {
                        throw new EndOfStreamException(
                            "Connection terminated during payload transfer.");
                    }

                    if (record is LanRecord.Data dataRecord)
                    {
                        if (!string.Equals(
                            dataRecord.BulkRef.Value,
                            bulkRef,
                            StringComparison.OrdinalIgnoreCase))
                        {
                            throw new ProtocolViolationException(
                                WireErrorCodes.MalformedMessage,
                                "Unmatched bulkRef in DATA record.");
                        }

                        await fileStream.WriteAsync(
                            dataRecord.Chunk.AsMemory(),
                            cancellationToken);
                        sha256.AppendData(dataRecord.Chunk);
                        receivedBytes += dataRecord.Chunk.Length;
                        cumulativeTransferred += dataRecord.Chunk.Length;

                        interaction.OnProgress(
                            new DesktopTransferProgress(
                                DesktopTransferDirection.Receive,
                                peerName,
                                item.Name,
                                cumulativeTransferred,
                                totalBytes));
                    }
                    else if (record is LanRecord.StreamEnd streamEnd)
                    {
                        if (!string.Equals(
                            streamEnd.BulkRef.Value,
                            bulkRef,
                            StringComparison.OrdinalIgnoreCase))
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
                                $"STREAM_END actualBytes {streamEnd.ActualBytes} " +
                                $"does not match expected {item.SizeBytes}.");
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

            var itemEnd = await channel.ReadEnvelopeAsync(cancellationToken);
            string declaredSha =
                itemEnd.Body["sha256"]!.ToString();
            string actualSha = Convert.ToHexStringLower(hash);

            if (!string.Equals(
                actualSha,
                declaredSha,
                StringComparison.OrdinalIgnoreCase))
            {
                request.StagingStore.PurgeQuarantine(
                    hello.SessionId,
                    new ItemId(item.ItemId));

                await channel.WriteEnvelopeAsync(
                    new Envelope(
                        1,
                        MessageTypes.ItemResult,
                        hello.SessionId,
                        NewMessageId(),
                        5,
                        new JsonObject
                        {
                            ["batchId"] = batchId,
                            ["itemId"] = item.ItemId,
                            ["status"] = "failed",
                            ["code"] = "INTEGRITY_MISMATCH"
                        }),
                    cancellationToken);

                interaction.OnItemStatus(
                    DesktopTransferDirection.Receive,
                    item.Name,
                    "Failed");
                continue;
            }

            string targetPath = Path.Combine(
                request.StagingStore.DestinationRoot,
                item.RelativePath.Replace(
                    '/',
                    Path.DirectorySeparatorChar));
            string finalPath =
                QuarantineStagingStore.ResolveCollision(targetPath);

            string? targetDirectory =
                Path.GetDirectoryName(finalPath);
            if (targetDirectory != null &&
                !Directory.Exists(targetDirectory))
            {
                Directory.CreateDirectory(targetDirectory);
            }

            File.Move(quarantinePath, finalPath, overwrite: false);

            verifiedCount++;
            verifiedBytes += item.SizeBytes;

            await channel.WriteEnvelopeAsync(
                new Envelope(
                    1,
                    MessageTypes.ItemResult,
                    hello.SessionId,
                    NewMessageId(),
                    6,
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

        await channel.ReadEnvelopeAsync(cancellationToken); // BATCH_END

        await channel.WriteEnvelopeAsync(
            new Envelope(
                1,
                MessageTypes.BatchResult,
                hello.SessionId,
                NewMessageId(),
                7,
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

    private static SessionId NewSessionId() =>
        new(Guid.NewGuid().ToString("D").ToLowerInvariant());

    private static MessageId NewMessageId() =>
        new(Guid.NewGuid().ToString("D").ToLowerInvariant());
}
