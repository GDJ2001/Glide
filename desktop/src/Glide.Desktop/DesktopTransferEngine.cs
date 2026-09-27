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
/// LAN profile 1 is invitation-authenticated and server-pinned. Control envelopes use independent
/// strictly monotonic sequences per direction. Offers are paged at the cross-platform 32-item
/// ceiling, all identifiers are correlated exactly, control/data reads have deadlines, and file
/// hashing is performed incrementally while bytes are streamed.
/// </summary>
public sealed class DesktopTransferEngine : IDesktopTransferExecutor
{
    private const int MaxOfferPageItems = 32;
    private const int SafeOfferPagePayloadBytes = 14_000;
    private const int StreamBufferBytes = 65_520;

    private sealed record PreparedSendItem(
        ItemId ItemId,
        DesktopTransferFile File,
        long DeclaredSize,
        DateTime LastWriteTimeUtc);

    public async Task<DesktopTransferResult> SendAsync(
        DesktopSendRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        ArgumentNullException.ThrowIfNull(interaction);

        if (cancellationToken.IsCancellationRequested)
        {
            return DesktopTransferResult.CancelledByUser("Transfer cancelled.");
        }

        if (request.Invitation == null)
        {
            return DesktopTransferResult.Failed(
                "Authenticated pairing invitation required. Scan or paste a current Glide pairing invitation before sending.");
        }

        if (request.Invitation.InvitationHash.Length != 64)
        {
            return DesktopTransferResult.Failed(
                "Pairing invitation is not canonical. Scan or paste the encoded pairing invitation again.");
        }

        if (request.Files.Count == 0)
        {
            return DesktopTransferResult.Failed("No files were selected for transfer.");
        }

        if (request.Files.Count > 1_000)
        {
            return DesktopTransferResult.Failed(
                "A transfer batch cannot contain more than 1,000 items.");
        }

        long totalBytes = 0;
        foreach (DesktopTransferFile file in request.Files)
        {
            if (!File.Exists(file.FilePath))
            {
                return DesktopTransferResult.Failed(
                    $"File not found: '{file.FilePath}'.");
            }

            long size = file.SizeBytes;
            if (size < 0 || size > ProtocolCatalog.MaxItemBytes)
            {
                return DesktopTransferResult.Failed(
                    $"File '{file.Name}' exceeds the protocol item-size limit.");
            }

            try
            {
                totalBytes = checked(totalBytes + size);
            }
            catch (OverflowException)
            {
                return DesktopTransferResult.Failed(
                    "Selected files exceed the protocol batch-size limit.");
            }

            if (totalBytes > ProtocolCatalog.MaxBatchBytes)
            {
                return DesktopTransferResult.Failed(
                    "Selected files exceed the protocol batch-size limit.");
            }
        }

        try
        {
            LanInvitation invitation = request.Invitation;
            if (invitation.IsExpired())
            {
                return DesktopTransferResult.Failed(
                    "Pairing invitation has expired. Request a new pairing code.");
            }

            var client = new TlsSocketClient();
            TlsSocketConnection? connectedSocket = null;
            Exception? lastConnectException = null;

            foreach (LanEndpoint endpoint in invitation.Endpoints)
            {
                string host = endpoint.Address;
                int port = endpoint.Port;

                interaction.OnStatus(
                    DesktopTransferDirection.Send,
                    $"Connecting to {host}:{port} over TLS 1.3...");

                try
                {
                    connectedSocket = await client.ConnectAsync(
                        host,
                        port,
                        expectedSpkiSha256: invitation.SpkiSha256,
                        cancellationToken);
                    break;
                }
                catch (Exception ex) when (ex is not OperationCanceledException)
                {
                    lastConnectException = ex;
                }
            }

            if (connectedSocket == null)
            {
                throw lastConnectException ?? new InvalidOperationException("Unable to connect to any endpoint in pairing invitation.");
            }

            await using TlsSocketConnection connection = connectedSocket;

            return await RunSenderSessionAsync(
                connection,
                request,
                totalBytes,
                interaction,
                cancellationToken);
        }
        catch (OperationCanceledException)
            when (cancellationToken.IsCancellationRequested)
        {
            interaction.OnStatus(
                DesktopTransferDirection.Send,
                "Transfer cancelled.");
            return DesktopTransferResult.CancelledByUser(
                "Transfer cancelled.");
        }
        catch (Exception ex)
        {
            interaction.OnStatus(
                DesktopTransferDirection.Send,
                $"Send failed: {ex.Message}");
            return DesktopTransferResult.Failed(
                $"Send failed: {ex.Message}");
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
        catch (OperationCanceledException)
            when (cancellationToken.IsCancellationRequested)
        {
            interaction.OnStatus(
                DesktopTransferDirection.Receive,
                "Transfer cancelled.");
            return DesktopTransferResult.CancelledByUser(
                "Transfer cancelled.");
        }
        catch (Exception ex)
        {
            interaction.OnStatus(
                DesktopTransferDirection.Receive,
                $"Receive failed: {ex.Message}");
            return DesktopTransferResult.Failed(
                $"Receive failed: {ex.Message}");
        }
    }

    public async Task<DesktopTransferResult> SendHostedAsync(
        TlsSocketConnection connection,
        DesktopSendRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(connection);
        ArgumentNullException.ThrowIfNull(request);
        ArgumentNullException.ThrowIfNull(interaction);

        if (request.HostedInvitationAuthority == null)
        {
            return DesktopTransferResult.Failed(
                "A hosted pairing authority is required for this transfer.");
        }

        if (request.Invitation == null || request.Invitation.InvitationHash.Length != 64)
        {
            return DesktopTransferResult.Failed(
                "The displayed pairing code is no longer valid. Refresh it and try again.");
        }

        if (request.Files.Count == 0 || request.Files.Count > 1_000)
        {
            return DesktopTransferResult.Failed("Select between 1 and 1,000 files before pairing.");
        }

        long totalBytes = 0;
        foreach (DesktopTransferFile file in request.Files)
        {
            if (!File.Exists(file.FilePath))
            {
                return DesktopTransferResult.Failed($"File not found: '{file.FilePath}'.");
            }

            long size = file.SizeBytes;
            if (size < 0 || size > ProtocolCatalog.MaxItemBytes)
            {
                return DesktopTransferResult.Failed(
                    $"File '{file.Name}' exceeds the protocol item-size limit.");
            }

            try
            {
                totalBytes = checked(totalBytes + size);
            }
            catch (OverflowException)
            {
                return DesktopTransferResult.Failed(
                    "Selected files exceed the protocol batch-size limit.");
            }

            if (totalBytes > ProtocolCatalog.MaxBatchBytes)
            {
                return DesktopTransferResult.Failed(
                    "Selected files exceed the protocol batch-size limit.");
            }
        }

        try
        {
            return await RunSenderSessionAsync(
                connection,
                request,
                totalBytes,
                interaction,
                cancellationToken,
                request.HostedInvitationAuthority);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            return DesktopTransferResult.CancelledByUser("Transfer cancelled.");
        }
        catch (Exception ex)
        {
            interaction.OnStatus(DesktopTransferDirection.Send, $"Send failed: {ex.Message}");
            return DesktopTransferResult.Failed($"Send failed: {ex.Message}");
        }
    }

    private static async Task<DesktopTransferResult> RunSenderSessionAsync(
        TlsSocketConnection connection,
        DesktopSendRequest request,
        long totalBytes,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken,
        LanInvitationAuthority? hostedInvitationAuthority = null)
    {
        await using var channel =
            new LanRecordChannel(connection.SslStream);
        var sequence = new DesktopControlSequence();
        SessionId sessionId;

        LanInvitation invitation = request.Invitation
            ?? throw new InvalidOperationException(
                "Authenticated pairing invitation is required.");

        LanBootstrapResult bootstrapResult;
        if (hostedInvitationAuthority == null)
        {
            sessionId = NewSessionId();
            var bootstrap = new LanBootstrapMessage(
                invitationId: invitation.InvitationId,
                token: invitation.Token,
                invitationHash: invitation.InvitationHash,
                sessionId: sessionId.Value,
                role: LanBootstrapMessage.RoleSender);

            await channel.WriteBootstrapAsync(bootstrap, cancellationToken);
            bootstrapResult = await DesktopControlSequence.ReadBootstrapResultAsync(
                channel,
                cancellationToken);
        }
        else
        {
            LanRecord? firstRecord = await DesktopControlSequence.ReadRecordAsync(
                channel,
                cancellationToken);
            if (firstRecord is not LanRecord.Bootstrap bootstrapRecord)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.ProtocolOrder,
                    "Authenticated BOOTSTRAP must be the first LAN record.");
            }

            LanBootstrapMessage bootstrap = LanBootstrapCodec.DecodeBootstrap(
                bootstrapRecord.JsonBytes);
            bootstrapResult = hostedInvitationAuthority.Consume(
                bootstrap,
                LanBootstrapMessage.RoleReceiver);
            await channel.WriteBootstrapResultAsync(bootstrapResult, cancellationToken);
            if (!bootstrapResult.IsAccepted)
            {
                return DesktopTransferResult.RejectedByPeer(
                    $"Pairing bootstrap rejected: {bootstrapResult.Code}");
            }

            sessionId = new SessionId(bootstrap.SessionId);
        }

        if (!bootstrapResult.IsAccepted)
        {
            return DesktopTransferResult.RejectedByPeer(
                $"Pairing bootstrap rejected by peer: {bootstrapResult.Code}");
        }

        if (!string.Equals(
                bootstrapResult.SessionId,
                sessionId.Value,
                StringComparison.Ordinal))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.AuthenticationFailed,
                "Accepted bootstrap returned a different sessionId.");
        }

        interaction.OnInvitationConsumed(
            DesktopTransferDirection.Send);

        string localName =
            string.IsNullOrWhiteSpace(request.LocalDeviceName)
                ? Environment.MachineName
                : request.LocalDeviceName;

        DesktopPersistentTrustManager? trustManager =
            request.TrustManager;
        if (request.ExpectedPeerFingerprintHex != null &&
            trustManager == null)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.AuthenticationFailed,
                "A trusted peer was requested but no persistent trust manager is available.");
        }

        trustManager?.Initialize();

        JsonArray optionalCapabilities =
            trustManager == null
                ? new JsonArray(
                    ProtocolCapabilities.FolderV1)
                : new JsonArray(
                    ProtocolCapabilities.FolderV1,
                    ProtocolCapabilities.IdentityV1);

        await channel.WriteEnvelopeAsync(
            sequence.Create(
                MessageTypes.Hello,
                sessionId,
                new JsonObject
                {
                    ["supportedMajors"] = new JsonArray(1),
                    ["requiredCapabilities"] =
                        new JsonArray(
                            ProtocolCapabilities.StreamV1,
                            ProtocolCapabilities.Sha256),
                    ["optionalCapabilities"] =
                        optionalCapabilities,
                    ["role"] = "sender",
                    ["ephemeralPeerId"] =
                        Guid.NewGuid()
                            .ToString("D")
                            .ToLowerInvariant(),
                    ["displayName"] = localName
                }),
            cancellationToken);

        Envelope helloAck =
            await sequence.ReadExpectedAsync(
                channel,
                MessageTypes.HelloAck,
                sessionId,
                cancellationToken);

        HashSet<string> negotiatedCapabilities =
            ValidateHelloAckCapabilities(
                helloAck,
                identityOffered:
                    trustManager != null,
                requireIdentity:
                    request.ExpectedPeerFingerprintHex != null);

        string peerName =
            helloAck.Body["displayName"]?.ToString()
            ?? invitation.Name;
        interaction.OnPeerIdentified(
            DesktopTransferDirection.Send,
            peerName);

        if (hostedInvitationAuthority != null)
        {
            interaction.OnStatus(
                DesktopTransferDirection.Send,
                $"{peerName} connected. Confirm the receiving device before sending.");
            if (!await interaction.ConfirmRecipientAsync(peerName, cancellationToken))
            {
                return DesktopTransferResult.RejectedByPeer(
                    "You did not approve the connected receiving device.");
            }
        }

        if (negotiatedCapabilities.Contains(
                ProtocolCapabilities.IdentityV1))
        {
            if (trustManager == null)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.AuthenticationFailed,
                    "Peer selected identity-v1 when no local persistent identity authority was offered.");
            }

            DesktopAuthenticatedPeer authenticated =
                await DesktopTrustedSessionAuthenticator
                    .AuthenticateSenderAsync(
                        channel,
                        sequence,
                        connection,
                        sessionId,
                        negotiatedCapabilities,
                        peerName,
                        trustManager,
                        request.ExpectedPeerFingerprintHex,
                        interaction,
                        cancellationToken);

            interaction.OnStatus(
                DesktopTransferDirection.Send,
                authenticated.IsEnrolled
                    ? $"Trusted identity authenticated: {authenticated.FingerprintHex[..12]}..."
                    : $"Peer identity cryptographically verified: {authenticated.FingerprintHex[..12]}...");
        }

        var preparedItems =
            new List<PreparedSendItem>(request.Files.Count);
        foreach (DesktopTransferFile file in request.Files)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var info = new FileInfo(file.FilePath);
            preparedItems.Add(
                new PreparedSendItem(
                    new ItemId(
                        Guid.NewGuid()
                            .ToString("D")
                            .ToLowerInvariant()),
                    file,
                    info.Length,
                    info.LastWriteTimeUtc));
        }

        var batchId = new BatchId(
            Guid.NewGuid()
                .ToString("D")
                .ToLowerInvariant());
        List<JsonArray> offerPages =
            BuildOfferPages(preparedItems);

        await channel.WriteEnvelopeAsync(
            sequence.Create(
                MessageTypes.OfferBegin,
                sessionId,
                new JsonObject
                {
                    ["batchId"] = batchId.Value,
                    ["itemCount"] = (long)preparedItems.Count,
                    ["totalBytes"] = totalBytes.ToString(),
                    ["pageCount"] = (long)offerPages.Count
                }),
            cancellationToken);

        for (int pageIndex = 0;
             pageIndex < offerPages.Count;
             pageIndex++)
        {
            await channel.WriteEnvelopeAsync(
                sequence.Create(
                    MessageTypes.OfferPage,
                    sessionId,
                    new JsonObject
                    {
                        ["batchId"] = batchId.Value,
                        ["pageIndex"] = pageIndex,
                        ["items"] = offerPages[pageIndex]
                    }),
                cancellationToken);
        }

        await channel.WriteEnvelopeAsync(
            sequence.Create(
                MessageTypes.OfferEnd,
                sessionId,
                new JsonObject
                {
                    ["batchId"] = batchId.Value
                }),
            cancellationToken);

        interaction.OnStatus(
            DesktopTransferDirection.Send,
            "Waiting for peer approval...");

        Envelope offerDecision =
            await sequence.ReadExpectedAsync(
                channel,
                MessageTypes.OfferDecision,
                sessionId,
                cancellationToken);
        RequireExactString(
            offerDecision,
            "batchId",
            batchId.Value);

        bool accepted =
            offerDecision.Body["accepted"]?.GetValue<bool>()
            ?? false;
        if (!accepted)
        {
            string code =
                offerDecision.Body["code"]?.ToString()
                ?? WireErrorCodes.UserRejected;
            return DesktopTransferResult.RejectedByPeer(
                $"Peer rejected the transfer offer ({code}).");
        }

        long cumulativeTransferred = 0;
        int verifiedCount = 0;
        long verifiedBytes = 0;
        int failedCount = 0;

        foreach (PreparedSendItem prepared in preparedItems)
        {
            cancellationToken.ThrowIfCancellationRequested();
            DesktopTransferFile file = prepared.File;

            interaction.OnItemStatus(
                DesktopTransferDirection.Send,
                file.Name,
                "Sending");

            var bulkRef = new BulkRef(
                Guid.NewGuid()
                    .ToString("D")
                    .ToLowerInvariant());

            await channel.WriteEnvelopeAsync(
                sequence.Create(
                    MessageTypes.ItemStart,
                    sessionId,
                    new JsonObject
                    {
                        ["batchId"] = batchId.Value,
                        ["itemId"] = prepared.ItemId.Value,
                        ["bulkRef"] = bulkRef.Value,
                        ["sizeBytes"] =
                            prepared.DeclaredSize.ToString()
                    }),
                cancellationToken);

            Envelope itemReady =
                await sequence.ReadExpectedAsync(
                    channel,
                    MessageTypes.ItemReady,
                    sessionId,
                    cancellationToken);
            RequireExactString(
                itemReady,
                "batchId",
                batchId.Value);
            RequireExactString(
                itemReady,
                "itemId",
                prepared.ItemId.Value);

            long actualBytes = 0;
            using var hash =
                IncrementalHash.CreateHash(
                    HashAlgorithmName.SHA256);
            await using var stream =
                new FileStream(
                    file.FilePath,
                    FileMode.Open,
                    FileAccess.Read,
                    FileShare.Read,
                    StreamBufferBytes,
                    useAsync: true);

            byte[] buffer =
                new byte[StreamBufferBytes];
            int read;
            while ((read = await stream.ReadAsync(
                buffer.AsMemory(0, buffer.Length),
                cancellationToken)) > 0)
            {
                actualBytes = checked(
                    actualBytes + read);
                if (actualBytes >
                    prepared.DeclaredSize)
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.SourceUnavailable,
                        $"Source '{file.Name}' grew while it was being transferred.");
                }

                hash.AppendData(
                    buffer,
                    0,
                    read);
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

            byte[] digestBytes =
                hash.GetHashAndReset();

            if (actualBytes != prepared.DeclaredSize)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.SourceUnavailable,
                    $"Source '{file.Name}' changed size while it was being transferred.");
            }

            var afterTransfer = new FileInfo(
                file.FilePath);
            if (afterTransfer.Length !=
                    prepared.DeclaredSize ||
                afterTransfer.LastWriteTimeUtc !=
                    prepared.LastWriteTimeUtc)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.SourceUnavailable,
                    $"Source '{file.Name}' changed during transfer.");
            }

            string digest =
                Convert.ToHexStringLower(
                    digestBytes);

            await channel.WriteStreamEndAsync(
                bulkRef,
                actualBytes,
                cancellationToken);

            await channel.WriteEnvelopeAsync(
                sequence.Create(
                    MessageTypes.ItemEnd,
                    sessionId,
                    new JsonObject
                    {
                        ["batchId"] = batchId.Value,
                        ["itemId"] = prepared.ItemId.Value,
                        ["actualBytes"] =
                            actualBytes.ToString(),
                        ["sha256"] = digest
                    }),
                cancellationToken);

            Envelope itemResult =
                await sequence.ReadExpectedAsync(
                    channel,
                    MessageTypes.ItemResult,
                    sessionId,
                    cancellationToken);
            RequireExactString(
                itemResult,
                "batchId",
                batchId.Value);
            RequireExactString(
                itemResult,
                "itemId",
                prepared.ItemId.Value);

            bool verified = string.Equals(
                itemResult.Body["status"]?.ToString(),
                "verified",
                StringComparison.Ordinal);
            if (verified)
            {
                RequireExactLong(
                    itemResult,
                    "sizeBytes",
                    actualBytes);
                RequireExactString(
                    itemResult,
                    "sha256",
                    digest);

                verifiedCount++;
                verifiedBytes += actualBytes;
                interaction.OnItemStatus(
                    DesktopTransferDirection.Send,
                    file.Name,
                    "Verified");
                interaction.OnHistory(
                    new DesktopHistoryEntry(
                        DesktopTransferDirection.Send,
                        file.Name,
                        actualBytes,
                        file.FilePath,
                        peerName,
                        DateTimeOffset.Now));
            }
            else
            {
                failedCount++;
                interaction.OnItemStatus(
                    DesktopTransferDirection.Send,
                    file.Name,
                    "Failed");
            }
        }

        await channel.WriteEnvelopeAsync(
            sequence.Create(
                MessageTypes.BatchEnd,
                sessionId,
                new JsonObject
                {
                    ["batchId"] = batchId.Value
                }),
            cancellationToken);

        Envelope terminal =
            await sequence.ReadExpectedOneOfAsync(
                channel,
                [MessageTypes.BatchResult, MessageTypes.Error],
                sessionId,
                cancellationToken);

        if (terminal.Type == MessageTypes.Error)
        {
            string detail =
                terminal.Body["detail"]?.ToString()
                ?? "Peer reported a terminal transfer error.";
            string message =
                $"Transfer failed after verification: {detail}";
            interaction.OnStatus(
                DesktopTransferDirection.Send,
                message);
            return DesktopTransferResult.Failed(
                message);
        }

        RequireExactString(
            terminal,
            "batchId",
            batchId.Value);

        int peerVerifiedCount =
            checked((int)long.Parse(
                terminal.Body["verifiedCount"]!
                    .ToString()));
        long peerVerifiedBytes = long.Parse(
            terminal.Body["totalVerifiedBytes"]!
                .ToString());

        if (peerVerifiedCount != verifiedCount ||
            peerVerifiedBytes != verifiedBytes)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                "BATCH_RESULT verification totals disagree with ITEM_RESULT outcomes.");
        }

        if (failedCount != 0 ||
            verifiedCount != preparedItems.Count ||
            verifiedBytes != totalBytes)
        {
            string partialMessage =
                $"Transfer ended with {failedCount} failed item(s); " +
                $"{verifiedCount}/{preparedItems.Count} item(s) were verified.";
            interaction.OnStatus(
                DesktopTransferDirection.Send,
                partialMessage);
            return DesktopTransferResult.Failed(
                partialMessage);
        }

        string successMessage =
            $"Transfer complete: peer verified {verifiedCount} item(s) ({verifiedBytes} bytes).";
        interaction.OnStatus(
            DesktopTransferDirection.Send,
            successMessage);
        return DesktopTransferResult.Completed(
            successMessage,
            verifiedCount,
            verifiedBytes);
    }

    private static async Task<DesktopTransferResult> RunReceiverSessionAsync(
        TlsSocketConnection connection,
        DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken)
    {
        await using var channel =
            new LanRecordChannel(connection.SslStream);

        LanRecord? firstRecord =
            await DesktopControlSequence.ReadRecordAsync(
                channel,
                cancellationToken);
        if (firstRecord == null)
        {
            return DesktopTransferResult.Failed(
                "Peer disconnected before sending the first record.");
        }

        if (firstRecord is not
            LanRecord.Bootstrap bootstrapRecord)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                "Authenticated BOOTSTRAP must be the first LAN record.");
        }

        LanBootstrapMessage bootstrap =
            LanBootstrapCodec.DecodeBootstrap(
                bootstrapRecord.JsonBytes);
        LanBootstrapResult bootstrapResult =
            request.InvitationAuthority.Consume(
                bootstrap);

        await channel.WriteBootstrapResultAsync(
            bootstrapResult,
            cancellationToken);

        if (!bootstrapResult.IsAccepted)
        {
            return DesktopTransferResult.RejectedByPeer(
                $"Inbound pairing bootstrap rejected: {bootstrapResult.Code}.");
        }

        var sessionId =
            new SessionId(bootstrap.SessionId);
        var sequence =
            new DesktopControlSequence();

        try
        {
            interaction.OnInvitationConsumed(
                DesktopTransferDirection.Receive);

            Envelope hello =
                await sequence.ReadExpectedAsync(
                    channel,
                    MessageTypes.Hello,
                    sessionId,
                    cancellationToken);

            if (!string.Equals(
                    hello.Body["role"]?.ToString(),
                    "sender",
                    StringComparison.Ordinal))
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.ProtocolOrder,
                    "Inbound transfer HELLO must use sender role.");
            }

            DesktopPersistentTrustManager? trustManager =
                request.TrustManager;
            if (request.ExpectedPeerFingerprintHex != null &&
                trustManager == null)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.AuthenticationFailed,
                    "A trusted peer was required but no persistent trust manager is available.");
            }

            trustManager?.Initialize();

            HashSet<string> negotiatedCapabilities =
                NegotiateHelloCapabilities(
                    hello,
                    allowIdentity:
                        trustManager != null);

            if (request.ExpectedPeerFingerprintHex != null &&
                !negotiatedCapabilities.Contains(
                    ProtocolCapabilities.IdentityV1))
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.AuthenticationFailed,
                    "Known trusted peer attempted a capability downgrade that omitted identity-v1.");
            }

            string peerName =
                hello.Body["displayName"]?.ToString()
                ?? "Remote Peer";
            interaction.OnPeerIdentified(
                DesktopTransferDirection.Receive,
                peerName);

            string localName =
                string.IsNullOrWhiteSpace(
                    request.LocalDeviceName)
                    ? Environment.MachineName
                    : request.LocalDeviceName;

            await channel.WriteEnvelopeAsync(
                sequence.Create(
                    MessageTypes.HelloAck,
                    sessionId,
                    new JsonObject
                    {
                        ["selectedMajor"] = 1,
                        ["capabilities"] =
                            new JsonArray(
                                negotiatedCapabilities
                                    .OrderBy(
                                        static capability =>
                                            capability,
                                        StringComparer.Ordinal)
                                    .Select(
                                        static capability =>
                                            JsonValue.Create(capability))
                                    .ToArray()),
                        ["ephemeralPeerId"] =
                            Guid.NewGuid()
                                .ToString("D")
                                .ToLowerInvariant(),
                        ["displayName"] = localName,
                        ["limits"] = new JsonObject
                        {
                            ["maxItems"] = 1000,
                            ["maxItemBytes"] =
                                ProtocolCatalog.MaxItemBytes
                                    .ToString(),
                            ["maxBatchBytes"] =
                                ProtocolCatalog.MaxBatchBytes
                                    .ToString(),
                            ["maxControlBytes"] =
                                ProtocolCatalog.MaxControlBytes
                        }
                    }),
                cancellationToken);

            string? authenticatedPeerFingerprint = null;
            if (negotiatedCapabilities.Contains(
                    ProtocolCapabilities.IdentityV1))
            {
                if (trustManager == null)
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.AuthenticationFailed,
                        "identity-v1 was negotiated without a local persistent trust authority.");
                }

                DesktopAuthenticatedPeer authenticated =
                    await DesktopTrustedSessionAuthenticator
                        .AuthenticateReceiverAsync(
                            channel,
                            sequence,
                            connection,
                            sessionId,
                            negotiatedCapabilities,
                            peerName,
                            trustManager,
                            request.ExpectedPeerFingerprintHex,
                            interaction,
                            cancellationToken);
                authenticatedPeerFingerprint =
                    authenticated.FingerprintHex;

                interaction.OnStatus(
                    DesktopTransferDirection.Receive,
                    authenticated.IsEnrolled
                        ? $"Trusted identity authenticated: {authenticated.FingerprintHex[..12]}..."
                        : $"Peer identity cryptographically verified: {authenticated.FingerprintHex[..12]}...");
            }

            Envelope offerBegin =
                await sequence.ReadExpectedAsync(
                    channel,
                    MessageTypes.OfferBegin,
                    sessionId,
                    cancellationToken);

            string batchId =
                offerBegin.Body["batchId"]!
                    .ToString();
            int itemCount =
                checked((int)long.Parse(
                    offerBegin.Body["itemCount"]!
                        .ToString()));
            long totalBytes = long.Parse(
                offerBegin.Body["totalBytes"]!
                    .ToString());
            int pageCount =
                checked((int)long.Parse(
                    offerBegin.Body["pageCount"]!
                        .ToString()));

            var items =
                new List<DesktopOfferItem>(
                    itemCount);
            var itemIds =
                new HashSet<string>(
                    StringComparer.Ordinal);
            long offeredBytes = 0;

            for (int pageIndex = 0;
                 pageIndex < pageCount;
                 pageIndex++)
            {
                Envelope offerPage =
                    await sequence.ReadExpectedAsync(
                        channel,
                        MessageTypes.OfferPage,
                        sessionId,
                        cancellationToken);
                RequireExactString(
                    offerPage,
                    "batchId",
                    batchId);

                int actualPageIndex =
                    checked((int)long.Parse(
                        offerPage.Body["pageIndex"]!
                            .ToString()));
                if (actualPageIndex != pageIndex)
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.ProtocolOrder,
                        $"Expected OFFER_PAGE index {pageIndex}, received {actualPageIndex}.");
                }

                JsonArray itemArray =
                    offerPage.Body["items"]
                    as JsonArray
                    ?? throw new ProtocolViolationException(
                        WireErrorCodes.MalformedMessage,
                        "OFFER_PAGE items must be an array.");

                foreach (JsonNode? node in itemArray)
                {
                    if (node is not JsonObject itemObject)
                    {
                        throw new ProtocolViolationException(
                            WireErrorCodes.MalformedMessage,
                            "OFFER_PAGE item must be an object.");
                    }

                    string itemId =
                        itemObject["itemId"]!
                            .ToString();
                    if (!itemIds.Add(itemId))
                    {
                        throw new ProtocolViolationException(
                            WireErrorCodes.MalformedMessage,
                            $"Duplicate offer itemId '{itemId}'.");
                    }

                    bool carriesResumeMetadata =
                        itemObject["rootHash"] != null ||
                        itemObject["chunkSize"] != null;
                    if (carriesResumeMetadata &&
                        !negotiatedCapabilities.Contains(
                            ProtocolCapabilities.ResumeV1))
                    {
                        throw new ProtocolViolationException(
                            WireErrorCodes.UnsupportedProtocol,
                            "OFFER_PAGE carried resume metadata without negotiated resume-v1.");
                    }

                    string name =
                        itemObject["name"]!
                            .ToString();
                    long size = long.Parse(
                        itemObject["sizeBytes"]!
                            .ToString());
                    string rawRelativePath =
                        itemObject[
                            "relativePath"]?
                            .ToString()
                        ?? name;
                    string relativePath =
                        WindowsPathSanitizer
                            .SanitizeRelativePath(
                                rawRelativePath);

                    offeredBytes =
                        checked(offeredBytes + size);
                    items.Add(
                        new DesktopOfferItem(
                            itemId,
                            name,
                            size,
                            relativePath));
                }
            }

            Envelope offerEnd =
                await sequence.ReadExpectedAsync(
                    channel,
                    MessageTypes.OfferEnd,
                    sessionId,
                    cancellationToken);
            RequireExactString(
                offerEnd,
                "batchId",
                batchId);

            if (items.Count != itemCount)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.ProtocolOrder,
                    $"Offer declared {itemCount} item(s) but supplied {items.Count}.");
            }

            if (offeredBytes != totalBytes)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.ProtocolOrder,
                    $"Offer declared {totalBytes} bytes but item metadata totals {offeredBytes}.");
            }

            bool hasStorage =
                request.StagingStore
                    .GetAvailableFreeSpaceBytes()
                >= totalBytes;

            bool offerAccepted =
                hasStorage &&
                await interaction.ConfirmOfferAsync(
                    new DesktopOffer(
                        batchId,
                        peerName,
                        items,
                        totalBytes,
                        AuthenticatedPeerFingerprintHex:
                            authenticatedPeerFingerprint),
                    cancellationToken);

            var decisionBody =
                new JsonObject
                {
                    ["batchId"] = batchId,
                    ["accepted"] = offerAccepted
                };
            if (!offerAccepted)
            {
                decisionBody["code"] =
                    hasStorage
                        ? WireErrorCodes.UserRejected
                        : WireErrorCodes.InsufficientStorage;
            }

            await channel.WriteEnvelopeAsync(
                sequence.Create(
                    MessageTypes.OfferDecision,
                    sessionId,
                    decisionBody),
                cancellationToken);

            if (!offerAccepted)
            {
                return DesktopTransferResult.RejectedByPeer(
                    hasStorage
                        ? "Transfer offer was rejected."
                        : "Transfer offer rejected because there is insufficient staging storage.");
            }

            long cumulativeTransferred = 0;
            int verifiedCount = 0;
            long verifiedBytes = 0;
            int failedCount = 0;

            foreach (DesktopOfferItem item in items)
            {
                cancellationToken.ThrowIfCancellationRequested();
                interaction.OnItemStatus(
                    DesktopTransferDirection.Receive,
                    item.Name,
                    "Receiving");

                Envelope itemStart =
                    await sequence.ReadExpectedAsync(
                        channel,
                        MessageTypes.ItemStart,
                        sessionId,
                        cancellationToken);
                RequireExactString(
                    itemStart,
                    "batchId",
                    batchId);
                RequireExactString(
                    itemStart,
                    "itemId",
                    item.ItemId);
                RequireExactLong(
                    itemStart,
                    "sizeBytes",
                    item.SizeBytes);

                string bulkRefText =
                    itemStart.Body["bulkRef"]!
                        .ToString();
                var bulkRef =
                    new BulkRef(bulkRefText);

                await channel.WriteEnvelopeAsync(
                    sequence.Create(
                        MessageTypes.ItemReady,
                        sessionId,
                        new JsonObject
                        {
                            ["batchId"] = batchId,
                            ["itemId"] = item.ItemId
                        }),
                    cancellationToken);

                var itemId =
                    new ItemId(item.ItemId);
                string quarantinePath =
                    request.StagingStore
                        .GetQuarantinePath(
                            sessionId,
                            itemId);
                string? quarantineDirectory =
                    Path.GetDirectoryName(
                        quarantinePath);
                if (quarantineDirectory != null)
                {
                    Directory.CreateDirectory(
                        quarantineDirectory);
                }

                byte[] hash;
                long receivedBytes = 0;
                try
                {
                    await using var fileStream =
                        new FileStream(
                            quarantinePath,
                            FileMode.Create,
                            FileAccess.Write,
                            FileShare.None,
                            65_536,
                            useAsync: true);
                    using var sha256 =
                        IncrementalHash.CreateHash(
                            HashAlgorithmName.SHA256);

                    while (true)
                    {
                        LanRecord? record =
                            await DesktopControlSequence
                                .ReadRecordAsync(
                                    channel,
                                    cancellationToken);
                        if (record == null)
                        {
                            throw new EndOfStreamException(
                                "Connection terminated during payload transfer.");
                        }

                        if (record is
                            LanRecord.Data dataRecord)
                        {
                            if (!string.Equals(
                                    dataRecord.BulkRef.Value,
                                    bulkRef.Value,
                                    StringComparison.Ordinal))
                            {
                                throw new ProtocolViolationException(
                                    WireErrorCodes.MalformedMessage,
                                    "Unmatched bulkRef in DATA record.");
                            }

                            receivedBytes =
                                checked(
                                    receivedBytes +
                                    dataRecord.Chunk.Length);
                            if (receivedBytes >
                                item.SizeBytes)
                            {
                                throw new ProtocolViolationException(
                                    WireErrorCodes.LimitExceeded,
                                    $"Item '{item.Name}' exceeded its declared size.");
                            }

                            await fileStream.WriteAsync(
                                dataRecord.Chunk.AsMemory(),
                                cancellationToken);
                            sha256.AppendData(
                                dataRecord.Chunk);

                            cumulativeTransferred +=
                                dataRecord.Chunk.Length;
                            interaction.OnProgress(
                                new DesktopTransferProgress(
                                    DesktopTransferDirection.Receive,
                                    peerName,
                                    item.Name,
                                    cumulativeTransferred,
                                    totalBytes));
                        }
                        else if (record is
                            LanRecord.StreamEnd streamEnd)
                        {
                            if (!string.Equals(
                                    streamEnd.BulkRef.Value,
                                    bulkRef.Value,
                                    StringComparison.Ordinal))
                            {
                                throw new ProtocolViolationException(
                                    WireErrorCodes.MalformedMessage,
                                    "Unmatched bulkRef in STREAM_END record.");
                            }

                            if (streamEnd.ActualBytes !=
                                    receivedBytes ||
                                receivedBytes !=
                                    item.SizeBytes)
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
                                $"Unexpected record kind during data stream: {record.Kind}.");
                        }
                    }

                    await fileStream.FlushAsync(
                        cancellationToken);
                    hash =
                        sha256.GetHashAndReset();
                }
                catch
                {
                    request.StagingStore
                        .PurgeQuarantine(
                            sessionId,
                            itemId);
                    throw;
                }

                Envelope itemEnd =
                    await sequence.ReadExpectedAsync(
                        channel,
                        MessageTypes.ItemEnd,
                        sessionId,
                        cancellationToken);
                RequireExactString(
                    itemEnd,
                    "batchId",
                    batchId);
                RequireExactString(
                    itemEnd,
                    "itemId",
                    item.ItemId);
                RequireExactLong(
                    itemEnd,
                    "actualBytes",
                    receivedBytes);

                string declaredSha =
                    itemEnd.Body["sha256"]!
                        .ToString();
                string actualSha =
                    Convert.ToHexStringLower(
                        hash);

                if (!string.Equals(
                        actualSha,
                        declaredSha,
                        StringComparison.Ordinal))
                {
                    request.StagingStore
                        .PurgeQuarantine(
                            sessionId,
                            itemId);

                    await channel.WriteEnvelopeAsync(
                        sequence.Create(
                            MessageTypes.ItemResult,
                            sessionId,
                            new JsonObject
                            {
                                ["batchId"] = batchId,
                                ["itemId"] = item.ItemId,
                                ["status"] = "failed",
                                ["code"] =
                                    WireErrorCodes
                                        .IntegrityMismatch
                            }),
                        cancellationToken);

                    failedCount++;
                    interaction.OnItemStatus(
                        DesktopTransferDirection.Receive,
                        item.Name,
                        "Failed");
                    continue;
                }

                string finalPath =
                    request.StagingStore
                        .CommitQuarantineFile(
                            sessionId,
                            itemId,
                            item.RelativePath);

                verifiedCount++;
                verifiedBytes +=
                    item.SizeBytes;

                await channel.WriteEnvelopeAsync(
                    sequence.Create(
                        MessageTypes.ItemResult,
                        sessionId,
                        new JsonObject
                        {
                            ["batchId"] = batchId,
                            ["itemId"] = item.ItemId,
                            ["status"] = "verified",
                            ["sizeBytes"] =
                                item.SizeBytes.ToString(),
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

            Envelope batchEnd =
                await sequence.ReadExpectedAsync(
                    channel,
                    MessageTypes.BatchEnd,
                    sessionId,
                    cancellationToken);
            RequireExactString(
                batchEnd,
                "batchId",
                batchId);

            if (verifiedCount == 0 &&
                failedCount > 0)
            {
                await channel.WriteEnvelopeAsync(
                    sequence.Create(
                        MessageTypes.Error,
                        sessionId,
                        new JsonObject
                        {
                            ["code"] =
                                WireErrorCodes
                                    .IntegrityMismatch,
                            ["batchId"] = batchId,
                            ["detail"] =
                                "No offered item passed integrity verification."
                        }),
                    cancellationToken);

                string failedMessage =
                    $"Transfer failed: all {failedCount} item(s) failed verification.";
                interaction.OnStatus(
                    DesktopTransferDirection.Receive,
                    failedMessage);
                return DesktopTransferResult.Failed(
                    failedMessage);
            }

            await channel.WriteEnvelopeAsync(
                sequence.Create(
                    MessageTypes.BatchResult,
                    sessionId,
                    new JsonObject
                    {
                        ["batchId"] = batchId,
                        ["status"] = "completed",
                        ["verifiedCount"] =
                            verifiedCount,
                        ["totalVerifiedBytes"] =
                            verifiedBytes.ToString()
                    }),
                cancellationToken);

            if (failedCount > 0)
            {
                string partialMessage =
                    $"Transfer ended with {failedCount} failed item(s); " +
                    $"{verifiedCount}/{items.Count} item(s) were safely saved.";
                interaction.OnStatus(
                    DesktopTransferDirection.Receive,
                    partialMessage);
                return DesktopTransferResult.Failed(
                    partialMessage);
            }

            string message =
                $"Transfer complete: {verifiedCount} file(s) safely saved ({verifiedBytes} bytes).";
            interaction.OnStatus(
                DesktopTransferDirection.Receive,
                message);
            return DesktopTransferResult.Completed(
                message,
                verifiedCount,
                verifiedBytes);
        }
        catch
        {
            request.StagingStore.PurgeSession(
                sessionId);
            throw;
        }
    }

    private static HashSet<string> NegotiateHelloCapabilities(
        Envelope hello,
        bool allowIdentity)
    {
        HashSet<string> required =
            ReadCapabilitySet(
                hello.Body["requiredCapabilities"],
                "HELLO requiredCapabilities");
        if (!required.Contains(
                ProtocolCapabilities.StreamV1) ||
            !required.Contains(
                ProtocolCapabilities.Sha256))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.UnsupportedProtocol,
                "HELLO must require stream-v1 and sha256.");
        }

        HashSet<string> optional =
            ReadCapabilitySet(
                hello.Body["optionalCapabilities"],
                "HELLO optionalCapabilities");

        ProtocolCapabilities.ValidateDependencies(
            optional);

        var negotiated =
            new HashSet<string>(
                ProtocolCapabilities.Required,
                StringComparer.Ordinal);

        foreach (string capability in optional)
        {
            if (ProtocolCapabilities.ProductionOptional.Contains(
                    capability) ||
                (allowIdentity &&
                 string.Equals(
                     capability,
                     ProtocolCapabilities.IdentityV1,
                     StringComparison.Ordinal)))
            {
                negotiated.Add(capability);
            }
        }

        ProtocolCapabilities.ValidateDependencies(
            negotiated);
        return negotiated;
    }

    private static HashSet<string> ValidateHelloAckCapabilities(
        Envelope helloAck,
        bool identityOffered,
        bool requireIdentity)
    {
        if (long.Parse(helloAck.Body["selectedMajor"]?.ToString() ?? "-1") != 1)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.UnsupportedProtocol,
                "HELLO_ACK selectedMajor must be 1.");
        }

        HashSet<string> capabilities =
            ReadCapabilitySet(
                helloAck.Body["capabilities"],
                "HELLO_ACK capabilities");

        if (!capabilities.Contains(
                ProtocolCapabilities.StreamV1) ||
            !capabilities.Contains(
                ProtocolCapabilities.Sha256))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.UnsupportedProtocol,
                "HELLO_ACK must include stream-v1 and sha256.");
        }

        var allowed =
            new HashSet<string>(
                ProtocolCapabilities.Required,
                StringComparer.Ordinal);
        allowed.UnionWith(
            ProtocolCapabilities.ProductionOptional);
        if (identityOffered)
        {
            allowed.Add(
                ProtocolCapabilities.IdentityV1);
        }

        if (capabilities.Except(
                allowed,
                StringComparer.Ordinal).Any())
        {
            throw new ProtocolViolationException(
                WireErrorCodes.UnsupportedProtocol,
                "HELLO_ACK selected an unsupported or unoffered capability.");
        }

        if (requireIdentity &&
            !capabilities.Contains(
                ProtocolCapabilities.IdentityV1))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.AuthenticationFailed,
                "Known trusted peer omitted identity-v1 from HELLO_ACK.");
        }

        ProtocolCapabilities.ValidateDependencies(
            capabilities);
        return capabilities;
    }

    private static HashSet<string> ReadCapabilitySet(
        JsonNode? node,
        string fieldName)
    {
        if (node is not JsonArray array)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"{fieldName} must be an array.");
        }

        var result =
            new HashSet<string>(
                StringComparer.Ordinal);

        foreach (JsonNode? item in array)
        {
            if (item is not JsonValue value ||
                value.GetValue<object>() is not string capability ||
                string.IsNullOrWhiteSpace(capability))
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.MalformedMessage,
                    $"{fieldName} must contain only non-empty strings.");
            }

            if (!result.Add(capability))
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.MalformedMessage,
                    $"{fieldName} contains duplicate capability '{capability}'.");
            }
        }

        return result;
    }

    private static List<JsonArray> BuildOfferPages(
        IReadOnlyList<PreparedSendItem> items)
    {
        var pages = new List<JsonArray>();
        var current = new JsonArray();
        int estimatedBytes = 256;

        foreach (PreparedSendItem item in items)
        {
            int itemEstimate =
                EstimateOfferItemBytes(item);
            if (current.Count > 0 &&
                (current.Count >= MaxOfferPageItems ||
                 estimatedBytes + itemEstimate >
                 SafeOfferPagePayloadBytes))
            {
                pages.Add(current);
                current = new JsonArray();
                estimatedBytes = 256;
            }

            current.Add(
                CreateOfferItem(item));
            estimatedBytes += itemEstimate;
        }

        if (current.Count > 0)
        {
            pages.Add(current);
        }

        if (pages.Count == 0 ||
            pages.Count > 1_000)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.LimitExceeded,
                $"Offer page count {pages.Count} is outside the protocol limit.");
        }

        return pages;
    }

    private static JsonObject CreateOfferItem(
        PreparedSendItem item) =>
        new()
        {
            ["itemId"] = item.ItemId.Value,
            ["name"] = item.File.Name,
            ["mime"] =
                "application/octet-stream",
            ["sizeBytes"] =
                item.DeclaredSize.ToString(),
            ["relativePath"] =
                item.File.TransferRelativePath
        };

    private static int EstimateOfferItemBytes(
        PreparedSendItem item)
    {
        const int JsonOverhead = 128;
        return JsonOverhead +
               Encoding.UTF8.GetByteCount(
                   item.File.TransferRelativePath) * 2 +
               item.DeclaredSize
                   .ToString()
                   .Length;
    }

    private static void RequireExactString(
        Envelope envelope,
        string key,
        string expected)
    {
        string actual =
            envelope.Body[key]?.ToString()
            ?? throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"{envelope.Type} is missing '{key}'.");

        if (!string.Equals(
                actual,
                expected,
                StringComparison.Ordinal))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                $"{envelope.Type} '{key}' value '{actual}' does not match expected '{expected}'.");
        }
    }

    private static void RequireExactLong(
        Envelope envelope,
        string key,
        long expected)
    {
        string value =
            envelope.Body[key]?.ToString()
            ?? throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"{envelope.Type} is missing '{key}'.");

        if (!long.TryParse(
                value,
                out long actual) ||
            actual != expected)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                $"{envelope.Type} '{key}' value '{value}' does not match expected '{expected}'.");
        }
    }

    private static SessionId NewSessionId() =>
        new(
            Guid.NewGuid()
                .ToString("D")
                .ToLowerInvariant());
}
