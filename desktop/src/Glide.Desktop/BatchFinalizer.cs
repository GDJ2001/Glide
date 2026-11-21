using System.Diagnostics;
using System.Text.Json.Nodes;
using Glide.Core.Models;
using Glide.Core.Storage;
using Glide.Desktop.Diagnostics;

namespace Glide.Desktop;

/// <summary>
/// Finalizes item and batch verification, atomic quarantine publication with
/// <see cref="DesktopTimeoutPolicy.LocalStorageProgressTimeout"/>, ITEM_RESULT / BATCH_END / BATCH_RESULT
/// acknowledgement exchange, and transfer history recording.
/// </summary>
internal sealed class BatchFinalizer
{
    private readonly DesktopTimeoutPolicy _timeoutPolicy;
    private readonly DesktopTransferIoOptions _ioOptions;

    public BatchFinalizer(DesktopTimeoutPolicy timeoutPolicy, DesktopTransferIoOptions ioOptions)
    {
        _timeoutPolicy = timeoutPolicy ?? throw new ArgumentNullException(nameof(timeoutPolicy));
        _ioOptions = ioOptions ?? throw new ArgumentNullException(nameof(ioOptions));
    }

    public async Task<(bool Verified, DesktopTransferResult? UncertainAbortResult)> FinalizeSenderItemAsync(
        AuthenticatedSessionContext session,
        BatchId batchId,
        PreparedSendItem prepared,
        BulkRef bulkRef,
        long actualBytes,
        byte[] digestBytes,
        int verifiedCountSoFar,
        long verifiedBytesSoFar,
        IDesktopTransferInteraction interaction,
        IDesktopTransferAttemptDiagnostics diag,
        CancellationToken cancellationToken)
    {
        DesktopTransferFile file = prepared.File;
        if (actualBytes != prepared.DeclaredSize)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.SourceUnavailable,
                $"Source '{file.Name}' changed size while it was being transferred.");
        }

        var afterTransfer = new FileInfo(file.FilePath);
        if (afterTransfer.Length != prepared.DeclaredSize ||
            afterTransfer.LastWriteTimeUtc != prepared.LastWriteTimeUtc)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.SourceUnavailable,
                $"Source '{file.Name}' changed during transfer.");
        }

        string digest = Convert.ToHexStringLower(digestBytes);

        await session.Channel.WriteStreamEndAsync(
            bulkRef,
            actualBytes,
            cancellationToken).ConfigureAwait(false);

        await session.Channel.WriteEnvelopeAsync(
            session.Sequence.Create(
                MessageTypes.ItemEnd,
                session.SessionId,
                new JsonObject
                {
                    ["batchId"] = batchId.Value,
                    ["itemId"] = prepared.ItemId.Value,
                    ["actualBytes"] = actualBytes.ToString(),
                    ["sha256"] = digest
                }),
            cancellationToken).ConfigureAwait(false);

        interaction.OnStatus(
            DesktopTransferDirection.Send,
            "Waiting for receiver confirmation…");
        interaction.OnPhase(DesktopTransferDirection.Send, DesktopTransferPhase.WaitingForConfirmation);
        diag.OnPhaseChanged(DesktopTransferPhase.WaitingForConfirmation);

        TimeSpan itemFinalizationDeadline = _timeoutPolicy.CalculateFinalizationDeadline(actualBytes);
        Envelope itemResult;
        try
        {
            itemResult = await session.Sequence.ReadExpectedAsync(
                session.Channel,
                MessageTypes.ItemResult,
                session.SessionId,
                cancellationToken,
                timeout: itemFinalizationDeadline).ConfigureAwait(false);
        }
        catch (Exception ackError) when (!cancellationToken.IsCancellationRequested && IsUncertainAckFailure(ackError))
        {
            string uncertainMessage =
                $"Confirmation uncertain for '{file.Name}': the connection closed after all bytes and digest were sent, " +
                "while waiting for final receiver confirmation. The receiving device may have already verified and saved the file; " +
                "check the receiver before resending.";
            interaction.OnStatus(DesktopTransferDirection.Send, uncertainMessage);
            return (false, DesktopTransferResult.Failed(
                uncertainMessage,
                TransferErrorCategory.Network,
                verifiedCountSoFar,
                verifiedBytesSoFar));
        }

        OfferExchangeHandler.RequireExactString(itemResult, "batchId", batchId.Value);
        OfferExchangeHandler.RequireExactString(itemResult, "itemId", prepared.ItemId.Value);

        bool verified = string.Equals(
            itemResult.Body["status"]?.ToString(),
            "verified",
            StringComparison.Ordinal);
        if (verified)
        {
            OfferExchangeHandler.RequireExactLong(itemResult, "sizeBytes", actualBytes);
            OfferExchangeHandler.RequireExactString(itemResult, "sha256", digest);

            diag.OnItemVerified(actualBytes);
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
                    session.PeerName,
                    DateTimeOffset.Now));
            return (true, null);
        }

        interaction.OnItemStatus(
            DesktopTransferDirection.Send,
            file.Name,
            "Failed");
        return (false, null);
    }

    public async Task<DesktopTransferResult> FinalizeSenderBatchAsync(
        AuthenticatedSessionContext session,
        ApprovedSenderBatchPlan plan,
        long totalBytes,
        int verifiedCount,
        long verifiedBytes,
        int failedCount,
        IDesktopTransferInteraction interaction,
        IDesktopTransferAttemptDiagnostics diag,
        CancellationToken cancellationToken)
    {
        long finalAckStart = Stopwatch.GetTimestamp();
        await session.Channel.WriteEnvelopeAsync(
            session.Sequence.Create(
                MessageTypes.BatchEnd,
                session.SessionId,
                new JsonObject
                {
                    ["batchId"] = plan.BatchId.Value
                }),
            cancellationToken).ConfigureAwait(false);

        interaction.OnStatus(
            DesktopTransferDirection.Send,
            "Waiting for receiver confirmation…");
        interaction.OnPhase(DesktopTransferDirection.Send, DesktopTransferPhase.WaitingForConfirmation);
        diag.OnPhaseChanged(DesktopTransferPhase.WaitingForConfirmation);

        TimeSpan batchFinalizationDeadline = _timeoutPolicy.CalculateFinalizationDeadline(totalBytes);
        Envelope terminal;
        try
        {
            terminal = await session.Sequence.ReadExpectedOneOfAsync(
                session.Channel,
                [MessageTypes.BatchResult, MessageTypes.Error],
                session.SessionId,
                cancellationToken,
                timeout: batchFinalizationDeadline).ConfigureAwait(false);
        }
        catch (Exception ackError) when (!cancellationToken.IsCancellationRequested && IsUncertainAckFailure(ackError))
        {
            diag.RecordFinalAcknowledgementDurationTicks(Stopwatch.GetTimestamp() - finalAckStart);
            string uncertainBatchMessage =
                $"Confirmation uncertain: the connection closed while waiting for final batch acknowledgment " +
                $"after {verifiedCount} item(s) were verified ({verifiedBytes} bytes). " +
                "The receiving device may have already saved the files; check the receiver before resending.";
            interaction.OnStatus(DesktopTransferDirection.Send, uncertainBatchMessage);
            return DesktopTransferResult.Failed(
                uncertainBatchMessage,
                TransferErrorCategory.Network,
                verifiedCount,
                verifiedBytes);
        }
        diag.RecordFinalAcknowledgementDurationTicks(Stopwatch.GetTimestamp() - finalAckStart);

        if (terminal.Type == MessageTypes.Error)
        {
            string detail = terminal.Body["detail"]?.ToString()
                ?? "Peer reported a terminal transfer error.";
            string message = $"Transfer failed after verification: {detail}";
            interaction.OnStatus(DesktopTransferDirection.Send, message);
            return DesktopTransferResult.Failed(
                message,
                TransferErrorCategory.Protocol,
                verifiedCount,
                verifiedBytes);
        }

        OfferExchangeHandler.RequireExactString(terminal, "batchId", plan.BatchId.Value);

        int peerVerifiedCount = checked((int)long.Parse(terminal.Body["verifiedCount"]!.ToString()));
        long peerVerifiedBytes = long.Parse(terminal.Body["totalVerifiedBytes"]!.ToString());

        if (peerVerifiedCount != verifiedCount || peerVerifiedBytes != verifiedBytes)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                "BATCH_RESULT verification totals disagree with ITEM_RESULT outcomes.");
        }

        if (failedCount != 0 ||
            verifiedCount != plan.Items.Count ||
            verifiedBytes != totalBytes)
        {
            string partialMessage =
                $"Transfer ended with {failedCount} failed item(s); " +
                $"{verifiedCount}/{plan.Items.Count} item(s) were verified.";
            interaction.OnStatus(DesktopTransferDirection.Send, partialMessage);
            return DesktopTransferResult.Failed(
                partialMessage,
                TransferErrorCategory.Security,
                verifiedCount,
                verifiedBytes);
        }

        string successMessage =
            $"Transfer complete: peer verified {verifiedCount} item(s) ({verifiedBytes} bytes).";
        interaction.OnStatus(DesktopTransferDirection.Send, successMessage);
        return DesktopTransferResult.Completed(
            successMessage,
            verifiedCount,
            verifiedBytes);
    }

    public async Task<bool> FinalizeReceiverItemAsync(
        AuthenticatedSessionContext session,
        string batchId,
        DesktopOfferItem item,
        ItemId itemId,
        long receivedBytes,
        byte[] hash,
        bool discardStorage,
        DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction,
        IDesktopTransferAttemptDiagnostics diag,
        CancellationToken cancellationToken)
    {
        interaction.OnPhase(DesktopTransferDirection.Receive, DesktopTransferPhase.Verifying);
        diag.OnPhaseChanged(DesktopTransferPhase.Verifying);

        Envelope itemEnd = await session.Sequence.ReadExpectedAsync(
            session.Channel,
            MessageTypes.ItemEnd,
            session.SessionId,
            cancellationToken).ConfigureAwait(false);
        OfferExchangeHandler.RequireExactString(itemEnd, "batchId", batchId);
        OfferExchangeHandler.RequireExactString(itemEnd, "itemId", item.ItemId);
        OfferExchangeHandler.RequireExactLong(itemEnd, "actualBytes", receivedBytes);

        string declaredSha = itemEnd.Body["sha256"]!.ToString();
        string actualSha = Convert.ToHexStringLower(hash);

        if (!string.Equals(actualSha, declaredSha, StringComparison.Ordinal))
        {
            if (!discardStorage)
            {
                request.StagingStore.PurgeQuarantine(session.SessionId, itemId);
            }

            await session.Channel.WriteEnvelopeAsync(
                session.Sequence.Create(
                    MessageTypes.ItemResult,
                    session.SessionId,
                    new JsonObject
                    {
                        ["batchId"] = batchId,
                        ["itemId"] = item.ItemId,
                        ["status"] = "failed",
                        ["code"] = WireErrorCodes.IntegrityMismatch
                    }),
                cancellationToken).ConfigureAwait(false);

            interaction.OnItemStatus(
                DesktopTransferDirection.Receive,
                item.Name,
                "Failed");
            return false;
        }

        string finalPath;
        if (discardStorage)
        {
            interaction.OnPhase(DesktopTransferDirection.Receive, DesktopTransferPhase.Saving);
            diag.OnPhaseChanged(DesktopTransferPhase.Saving);
            finalPath = Path.Combine(request.StagingStore.DestinationRoot, item.RelativePath);
        }
        else
        {
            var verifiedDigest = new Sha256Digest(actualSha);
            request.StagingStore.RecordVerifiedQuarantineDigest(
                session.SessionId,
                itemId,
                verifiedDigest,
                receivedBytes);

            QuarantineCommitResult commitResult;
            try
            {
                commitResult = await request.StagingStore
                    .CommitVerifiedQuarantineFileWithMetricsAsync(
                        session.SessionId,
                        itemId,
                        item.RelativePath,
                        verifiedDigest,
                        receivedBytes,
                        cancellationToken,
                        onBeforePublish: () =>
                        {
                            interaction.OnPhase(DesktopTransferDirection.Receive, DesktopTransferPhase.Saving);
                            diag.OnPhaseChanged(DesktopTransferPhase.Saving);
                        },
                        storageProgressTimeout: _timeoutPolicy.LocalStorageProgressTimeout,
                        preVerifiedByExclusiveStreamingHandle: true).ConfigureAwait(false);
            }
            catch (TimeoutException timeoutEx)
            {
                interaction.OnStatus(
                    DesktopTransferDirection.Receive,
                    "Local verification or saving made no I/O progress for 60 seconds; waiting for operating-system cleanup before releasing staging files.");
                throw new IOException(timeoutEx.Message, timeoutEx);
            }

            finalPath = commitResult.CommittedPath;
            diag.AddVerificationRead(receivedBytes, commitResult.VerificationReadStopwatchTicks);
            diag.AddCommit(commitResult.CommitStopwatchTicks);
        }

        diag.OnItemVerified(item.SizeBytes);

        await session.Channel.WriteEnvelopeAsync(
            session.Sequence.Create(
                MessageTypes.ItemResult,
                session.SessionId,
                new JsonObject
                {
                    ["batchId"] = batchId,
                    ["itemId"] = item.ItemId,
                    ["status"] = "verified",
                    ["sizeBytes"] = item.SizeBytes.ToString(),
                    ["sha256"] = actualSha
                }),
            cancellationToken).ConfigureAwait(false);

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
                session.PeerName,
                DateTimeOffset.Now));
        return true;
    }

    public async Task<DesktopTransferResult> FinalizeReceiverBatchAsync(
        AuthenticatedSessionContext session,
        ApprovedReceiverBatchPlan plan,
        int verifiedCount,
        long verifiedBytes,
        int failedCount,
        bool discardStorage,
        DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction,
        IDesktopTransferAttemptDiagnostics diag,
        CancellationToken cancellationToken)
    {
        Envelope batchEnd = await session.Sequence.ReadExpectedAsync(
            session.Channel,
            MessageTypes.BatchEnd,
            session.SessionId,
            cancellationToken).ConfigureAwait(false);
        OfferExchangeHandler.RequireExactString(batchEnd, "batchId", plan.BatchId);

        if (verifiedCount == 0 && failedCount > 0)
        {
            await session.Channel.WriteEnvelopeAsync(
                session.Sequence.Create(
                    MessageTypes.Error,
                    session.SessionId,
                    new JsonObject
                    {
                        ["code"] = WireErrorCodes.IntegrityMismatch,
                        ["batchId"] = plan.BatchId,
                        ["detail"] = "No offered item passed integrity verification."
                    }),
                cancellationToken).ConfigureAwait(false);

            string failedMessage =
                $"Transfer failed: all {failedCount} item(s) failed verification.";
            interaction.OnStatus(DesktopTransferDirection.Receive, failedMessage);
            return DesktopTransferResult.Failed(
                failedMessage,
                TransferErrorCategory.Security);
        }

        if (!discardStorage)
        {
            diag.SetAttemptMetadata(new DesktopAttemptMetadata(
                CommitStrategy: request.StagingStore.LastCommitStrategy.ToString()));
        }

        long finalAckStart = Stopwatch.GetTimestamp();
        await session.Channel.WriteEnvelopeAsync(
            session.Sequence.Create(
                MessageTypes.BatchResult,
                session.SessionId,
                new JsonObject
                {
                    ["batchId"] = plan.BatchId,
                    ["status"] = "completed",
                    ["verifiedCount"] = verifiedCount,
                    ["totalVerifiedBytes"] = verifiedBytes.ToString()
                }),
            cancellationToken).ConfigureAwait(false);
        diag.RecordFinalAcknowledgementDurationTicks(Stopwatch.GetTimestamp() - finalAckStart);

        if (failedCount > 0)
        {
            string partialMessage =
                $"Transfer ended with {failedCount} failed item(s); " +
                $"{verifiedCount}/{plan.Items.Count} item(s) were safely saved.";
            interaction.OnStatus(DesktopTransferDirection.Receive, partialMessage);
            if (!discardStorage)
            {
                request.StagingStore.PurgeSession(session.SessionId);
            }
            return DesktopTransferResult.Failed(
                partialMessage,
                TransferErrorCategory.Security,
                verifiedCount,
                verifiedBytes);
        }

        string message =
            $"Transfer complete: {verifiedCount} file(s) safely saved ({verifiedBytes} bytes).";
        interaction.OnStatus(DesktopTransferDirection.Receive, message);
        if (!discardStorage)
        {
            request.StagingStore.PurgeSession(session.SessionId);
        }
        return DesktopTransferResult.Completed(
            message,
            verifiedCount,
            verifiedBytes);
    }

    private static bool IsUncertainAckFailure(Exception error) =>
        error is EndOfStreamException
            or IOException
            or System.Net.Sockets.SocketException
            or TimeoutException;
}
