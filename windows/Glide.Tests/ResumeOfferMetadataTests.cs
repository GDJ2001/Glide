using System.Text.Json.Nodes;
using Glide.Core.Codec;
using Glide.Core.Models;

namespace Glide.Tests;

public sealed class ResumeOfferMetadataTests
{
    private static readonly SessionId Session =
        new("11111111-1111-4111-8111-111111111111");
    private const string MessageIdValue =
        "22222222-2222-4222-8222-222222222222";
    private const string BatchId =
        "33333333-3333-4333-8333-333333333333";
    private const string ItemId =
        "44444444-4444-4444-8444-444444444444";
    private const string Root =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    [Fact]
    public void ResumeMetadata_ValidPair_RoundTrips()
    {
        Envelope decoded =
            RoundTrip(
                ValidOfferItem(
                    rootHash: Root,
                    chunkSize: 1_048_576));

        JsonObject item =
            decoded.Body["items"]!
                .AsArray()[0]!
                .AsObject();

        Assert.Equal(
            Root,
            item["rootHash"]!
                .ToString());
        Assert.Equal(
            "1048576",
            item["chunkSize"]!
                .ToString());
    }

    [Fact]
    public void ResumeMetadata_RootWithoutChunkSize_IsRejected()
    {
        Assert.Throws<ProtocolViolationException>(
            () =>
                RoundTrip(
                    ValidOfferItem(
                        rootHash: Root,
                        chunkSize: null)));
    }

    [Fact]
    public void ResumeMetadata_UppercaseRoot_IsRejected()
    {
        Assert.Throws<ProtocolViolationException>(
            () =>
                RoundTrip(
                    ValidOfferItem(
                        rootHash:
                            Root.ToUpperInvariant(),
                        chunkSize: 1024)));
    }

    [Fact]
    public void ResumeMetadata_OversizedChunk_IsRejected()
    {
        Assert.Throws<ProtocolViolationException>(
            () =>
                RoundTrip(
                    ValidOfferItem(
                        rootHash: Root,
                        chunkSize:
                            16 * 1024 * 1024 +
                            1)));
    }

    private static JsonObject ValidOfferItem(
        string? rootHash,
        int? chunkSize)
    {
        var item =
            new JsonObject
            {
                ["itemId"] = ItemId,
                ["name"] = "resume.bin",
                ["mime"] =
                    "application/octet-stream",
                ["sizeBytes"] = "1048576"
            };

        if (rootHash != null)
        {
            item["rootHash"] =
                rootHash;
        }

        if (chunkSize != null)
        {
            item["chunkSize"] =
                chunkSize.Value;
        }

        return item;
    }

    private static Envelope RoundTrip(
        JsonObject item)
    {
        var body =
            new JsonObject
            {
                ["batchId"] = BatchId,
                ["pageIndex"] = 0,
                ["items"] =
                    new JsonArray(item)
            };

        var envelope =
            new Envelope(
                1,
                MessageTypes.OfferPage,
                Session,
                new MessageId(
                    MessageIdValue),
                1,
                body);

        return ProtocolCodec.Decode(
            ProtocolCodec.Encode(
                envelope));
    }
}
