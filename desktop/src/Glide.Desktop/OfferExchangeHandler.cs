using System.Text;
using System.Text.Json.Nodes;
using Glide.Core.Models;
using Glide.Core.Storage;
using Glide.Desktop.Diagnostics;

namespace Glide.Desktop;

internal sealed record ApprovedSenderBatchPlan(
    BatchId BatchId,
    IReadOnlyList<PreparedSendItem> Items);

internal sealed record ApprovedReceiverBatchPlan(
    string BatchId,
    IReadOnlyList<DesktopOfferItem> Items,
    long TotalBytes);

/// <summary>
/// Handles paginated OFFER_BEGIN / OFFER_PAGE / OFFER_END / OFFER_DECISION negotiation,
/// destination storage preflight, and user/headless consent evaluation.
/// </summary>
internal sealed class OfferExchangeHandler
{
    private const int MaxOfferPageItems = 32;
    private const int SafeOfferPagePayloadBytes = 14_000;

    private readonly DesktopTimeoutPolicy _timeoutPolicy;
    private readonly DesktopTransferIoOptions _ioOptions;

    public OfferExchangeHandler(DesktopTimeoutPolicy timeoutPolicy, DesktopTransferIoOptions ioOptions)
    {
        _timeoutPolicy = timeoutPolicy ?? throw new ArgumentNullException(nameof(timeoutPolicy));
        _ioOptions = ioOptions ?? throw new ArgumentNullException(nameof(ioOptions));
    }

    public async Task<(ApprovedSenderBatchPlan? Plan, DesktopTransferResult? EarlyResult)> ExchangeSenderOfferAsync(
        AuthenticatedSessionContext session,
        IReadOnlyList<DesktopTransferFile> files,
        long totalBytes,
        IDesktopTransferInteraction interaction,
        IDesktopTransferAttemptDiagnostics diag,
        CancellationToken cancellationToken)
    {
        var preparedItems = new List<PreparedSendItem>(files.Count);
        foreach (DesktopTransferFile file in files)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var info = new FileInfo(file.FilePath);
            preparedItems.Add(
                new PreparedSendItem(
                    new ItemId(Guid.NewGuid().ToString("D").ToLowerInvariant()),
                    file,
                    info.Length,
                    info.LastWriteTimeUtc));
        }

        var batchId = new BatchId(Guid.NewGuid().ToString("D").ToLowerInvariant());
        List<JsonArray> offerPages = BuildOfferPages(preparedItems);

        await session.Channel.WriteEnvelopeAsync(
            session.Sequence.Create(
                MessageTypes.OfferBegin,
                session.SessionId,
                new JsonObject
                {
                    ["batchId"] = batchId.Value,
                    ["itemCount"] = (long)preparedItems.Count,
                    ["totalBytes"] = totalBytes.ToString(),
                    ["pageCount"] = (long)offerPages.Count
                }),
            cancellationToken).ConfigureAwait(false);

        for (int pageIndex = 0; pageIndex < offerPages.Count; pageIndex++)
        {
            await session.Channel.WriteEnvelopeAsync(
                session.Sequence.Create(
                    MessageTypes.OfferPage,
                    session.SessionId,
                    new JsonObject
                    {
                        ["batchId"] = batchId.Value,
                        ["pageIndex"] = pageIndex,
                        ["items"] = offerPages[pageIndex]
                    }),
                cancellationToken).ConfigureAwait(false);
        }

        await session.Channel.WriteEnvelopeAsync(
            session.Sequence.Create(
                MessageTypes.OfferEnd,
                session.SessionId,
                new JsonObject
                {
                    ["batchId"] = batchId.Value
                }),
            cancellationToken).ConfigureAwait(false);

        interaction.OnStatus(
            DesktopTransferDirection.Send,
            "Waiting for peer approval...");
        interaction.OnPhase(DesktopTransferDirection.Send, DesktopTransferPhase.AwaitingConsent);
        diag.OnPhaseChanged(DesktopTransferPhase.AwaitingConsent);

        Envelope offerDecision = await session.Sequence.ReadExpectedAsync(
            session.Channel,
            MessageTypes.OfferDecision,
            session.SessionId,
            cancellationToken,
            timeout: _timeoutPolicy.HumanApprovalTimeout).ConfigureAwait(false);
        RequireExactString(offerDecision, "batchId", batchId.Value);

        bool accepted = offerDecision.Body["accepted"]?.GetValue<bool>() ?? false;
        if (!accepted)
        {
            string code = offerDecision.Body["code"]?.ToString() ?? WireErrorCodes.UserRejected;
            if (code == WireErrorCodes.InsufficientStorage)
            {
                return (null, DesktopTransferResult.Failed(
                    "The receiving device cannot save these files. Ask its user to choose a writable folder in Settings or free space, then try again.",
                    TransferErrorCategory.Storage));
            }
            return (null, DesktopTransferResult.RejectedByPeer(
                $"Peer rejected the transfer offer ({code})."));
        }

        diag.OnConsentCompleted();
        interaction.OnPhase(DesktopTransferDirection.Send, DesktopTransferPhase.Transferring);
        diag.OnPhaseChanged(DesktopTransferPhase.Transferring);

        return (new ApprovedSenderBatchPlan(batchId, preparedItems), null);
    }

    public async Task<(ApprovedReceiverBatchPlan? Plan, DesktopTransferResult? EarlyResult)> ExchangeReceiverOfferAsync(
        AuthenticatedSessionContext session,
        DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction,
        IDesktopTransferAttemptDiagnostics diag,
        CancellationToken cancellationToken)
    {
        Envelope offerBegin = await session.Sequence.ReadExpectedAsync(
            session.Channel,
            MessageTypes.OfferBegin,
            session.SessionId,
            cancellationToken).ConfigureAwait(false);

        string batchId = offerBegin.Body["batchId"]!.ToString();
        int itemCount = checked((int)long.Parse(offerBegin.Body["itemCount"]!.ToString()));
        long totalBytes = long.Parse(offerBegin.Body["totalBytes"]!.ToString());
        int pageCount = checked((int)long.Parse(offerBegin.Body["pageCount"]!.ToString()));

        diag.SetDeclaredTotalBytes(totalBytes);
        diag.SetAttemptMetadata(new DesktopAttemptMetadata(ItemCount: itemCount));

        var items = new List<DesktopOfferItem>(itemCount);
        var itemIds = new HashSet<string>(StringComparer.Ordinal);
        long offeredBytes = 0;

        for (int pageIndex = 0; pageIndex < pageCount; pageIndex++)
        {
            Envelope offerPage = await session.Sequence.ReadExpectedAsync(
                session.Channel,
                MessageTypes.OfferPage,
                session.SessionId,
                cancellationToken).ConfigureAwait(false);
            RequireExactString(offerPage, "batchId", batchId);

            int actualPageIndex = checked((int)long.Parse(offerPage.Body["pageIndex"]!.ToString()));
            if (actualPageIndex != pageIndex)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.ProtocolOrder,
                    $"Expected OFFER_PAGE index {pageIndex}, received {actualPageIndex}.");
            }

            JsonArray itemArray = offerPage.Body["items"] as JsonArray
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

                string itemId = itemObject["itemId"]!.ToString();
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
                    !session.NegotiatedCapabilities.Contains(ProtocolCapabilities.ResumeV1))
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.UnsupportedProtocol,
                        "OFFER_PAGE carried resume metadata without negotiated resume-v1.");
                }

                string name = itemObject["name"]!.ToString();
                long size = long.Parse(itemObject["sizeBytes"]!.ToString());
                string rawRelativePath = itemObject["relativePath"]?.ToString() ?? name;
                string relativePath = WindowsPathSanitizer.SanitizeRelativePath(rawRelativePath);

                offeredBytes = checked(offeredBytes + size);
                items.Add(new DesktopOfferItem(itemId, name, size, relativePath));
            }
        }

        Envelope offerEnd = await session.Sequence.ReadExpectedAsync(
            session.Channel,
            MessageTypes.OfferEnd,
            session.SessionId,
            cancellationToken).ConfigureAwait(false);
        RequireExactString(offerEnd, "batchId", batchId);

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

        bool discardStorage = _ioOptions.DiscardStorageForBenchmark;
        long maxItemBytes = items.Count > 0 ? items.Max(static item => item.SizeBytes) : 0;
        bool hasStorage = discardStorage ||
            request.StagingStore.HasSufficientFreeSpaceForBatch(totalBytes, maxItemBytes);
        if (hasStorage && !discardStorage)
        {
            try
            {
                await request.StagingStore.VerifyDestinationWritableAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException)
            {
                hasStorage = false;
            }
        }

        interaction.OnPhase(DesktopTransferDirection.Receive, DesktopTransferPhase.AwaitingConsent);
        diag.OnPhaseChanged(DesktopTransferPhase.AwaitingConsent);

        bool offerAccepted;
        using (CancellationTokenSource approvalTimeoutCts =
               _timeoutPolicy.CreateTimeoutTokenSource(
                   _timeoutPolicy.HumanApprovalTimeout,
                   cancellationToken))
        {
            try
            {
                offerAccepted =
                    hasStorage &&
                    await interaction.ConfirmOfferAsync(
                        new DesktopOffer(
                            batchId,
                            session.PeerName,
                            items,
                            totalBytes,
                            AuthenticatedPeerFingerprintHex: session.AuthenticatedPeerFingerprintHex),
                        approvalTimeoutCts.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.Timeout,
                    "Timed out waiting for local approval of the incoming transfer offer.");
            }
        }

        var decisionBody = new JsonObject
        {
            ["batchId"] = batchId,
            ["accepted"] = offerAccepted
        };
        if (!offerAccepted)
        {
            decisionBody["code"] = hasStorage
                ? WireErrorCodes.UserRejected
                : WireErrorCodes.InsufficientStorage;
        }

        await session.Channel.WriteEnvelopeAsync(
            session.Sequence.Create(
                MessageTypes.OfferDecision,
                session.SessionId,
                decisionBody),
            cancellationToken).ConfigureAwait(false);

        if (!offerAccepted)
        {
            return (null, hasStorage
                ? DesktopTransferResult.RejectedByPeer("Transfer offer was rejected.")
                : DesktopTransferResult.Failed(
                    "The receiving folder is unavailable, read-only, or short of space. Choose a writable folder in Settings and try again.",
                    TransferErrorCategory.Storage));
        }

        diag.OnConsentCompleted();
        interaction.OnPhase(DesktopTransferDirection.Receive, DesktopTransferPhase.Transferring);
        diag.OnPhaseChanged(DesktopTransferPhase.Transferring);

        return (new ApprovedReceiverBatchPlan(batchId, items, totalBytes), null);
    }

    public static List<JsonArray> BuildOfferPages(IReadOnlyList<PreparedSendItem> items)
    {
        var pages = new List<JsonArray>();
        var current = new JsonArray();
        int estimatedBytes = 256;

        foreach (PreparedSendItem item in items)
        {
            int itemEstimate = EstimateOfferItemBytes(item);
            if (current.Count > 0 &&
                (current.Count >= MaxOfferPageItems ||
                 estimatedBytes + itemEstimate > SafeOfferPagePayloadBytes))
            {
                pages.Add(current);
                current = new JsonArray();
                estimatedBytes = 256;
            }

            current.Add(CreateOfferItem(item));
            estimatedBytes += itemEstimate;
        }

        if (current.Count > 0)
        {
            pages.Add(current);
        }

        if (pages.Count == 0 || pages.Count > 1_000)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.LimitExceeded,
                $"Offer page count {pages.Count} is outside the protocol limit.");
        }

        return pages;
    }

    private static JsonObject CreateOfferItem(PreparedSendItem item) =>
        new()
        {
            ["itemId"] = item.ItemId.Value,
            ["name"] = item.File.Name,
            ["mime"] = "application/octet-stream",
            ["sizeBytes"] = item.DeclaredSize.ToString(),
            ["relativePath"] = item.File.TransferRelativePath
        };

    private static int EstimateOfferItemBytes(PreparedSendItem item)
    {
        const int JsonOverhead = 128;
        return JsonOverhead +
               Encoding.UTF8.GetByteCount(item.File.TransferRelativePath) * 2 +
               item.DeclaredSize.ToString().Length;
    }

    internal static void RequireExactString(Envelope envelope, string key, string expected)
    {
        string actual = envelope.Body[key]?.ToString()
            ?? throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"{envelope.Type} is missing '{key}'.");

        if (!string.Equals(actual, expected, StringComparison.Ordinal))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                $"{envelope.Type} '{key}' value '{actual}' does not match expected '{expected}'.");
        }
    }

    internal static void RequireExactLong(Envelope envelope, string key, long expected)
    {
        string value = envelope.Body[key]?.ToString()
            ?? throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"{envelope.Type} is missing '{key}'.");

        if (!long.TryParse(value, out long actual) || actual != expected)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                $"{envelope.Type} '{key}' value '{value}' does not match expected '{expected}'.");
        }
    }
}
