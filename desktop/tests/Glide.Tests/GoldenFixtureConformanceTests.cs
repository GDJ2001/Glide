using Glide.Core.Codec;
using Glide.Core.Models;
using Xunit;

namespace Glide.Tests;

public class GoldenFixtureConformanceTests
{
    private static readonly string FixturesDir = LocateFixturesDir();

    private static string LocateFixturesDir()
    {
        var current = new DirectoryInfo(AppContext.BaseDirectory);
        while (current != null)
        {
            string candidate = Path.Combine(current.FullName, "android", "core", "src", "test", "resources", "fixtures");
            if (Directory.Exists(candidate))
            {
                return candidate;
            }
            current = current.Parent;
        }
        throw new DirectoryNotFoundException("Could not locate fixtures directory.");
    }

    [Theory]
    [InlineData("hello.json", MessageTypes.Hello)]
    [InlineData("hello_ack.json", MessageTypes.HelloAck)]
    [InlineData("offer_begin.json", MessageTypes.OfferBegin)]
    [InlineData("offer_page.json", MessageTypes.OfferPage)]
    [InlineData("offer_end.json", MessageTypes.OfferEnd)]
    [InlineData("offer_decision.json", MessageTypes.OfferDecision)]
    [InlineData("item_start.json", MessageTypes.ItemStart)]
    [InlineData("item_ready.json", MessageTypes.ItemReady)]
    [InlineData("item_end.json", MessageTypes.ItemEnd)]
    [InlineData("item_end_zero_byte.json", MessageTypes.ItemEnd)]
    [InlineData("item_result_verified.json", MessageTypes.ItemResult)]
    [InlineData("item_result_failed.json", MessageTypes.ItemResult)]
    [InlineData("batch_end.json", MessageTypes.BatchEnd)]
    [InlineData("batch_result.json", MessageTypes.BatchResult)]
    [InlineData("cancel.json", MessageTypes.Cancel)]
    [InlineData("cancel_ack.json", MessageTypes.CancelAck)]
    [InlineData("error.json", MessageTypes.Error)]
    [InlineData("ping.json", MessageTypes.Ping)]
    [InlineData("pong.json", MessageTypes.Pong)]
    [InlineData("message_with_valid_extensions.json", MessageTypes.Ping)]
    public void CanonicalPositiveFixtures_DecodeAndValidateSuccessfully(string fixtureFileName, string expectedType)
    {
        string filePath = Path.Combine(FixturesDir, fixtureFileName);
        Assert.True(File.Exists(filePath), $"Fixture file not found: {filePath}");

        byte[] rawBytes = File.ReadAllBytes(filePath);
        var envelope = ProtocolCodec.Decode(rawBytes);

        Assert.Equal(1, envelope.V);
        Assert.Equal(expectedType, envelope.Type);
        Assert.True(IdentifierValidation.IsValidUuidV4(envelope.SessionId.Value));
        Assert.True(IdentifierValidation.IsValidUuidV4(envelope.MessageId.Value));
        Assert.True(envelope.Seq >= 1);
        Assert.NotNull(envelope.Body);

        // Re-encoding must succeed and be within limits
        byte[] reEncoded = ProtocolCodec.Encode(envelope);
        Assert.True(reEncoded.Length <= ProtocolCodec.MaxControlBytes);

        // Re-decoding must be idempotent
        var reDecoded = ProtocolCodec.Decode(reEncoded);
        Assert.Equal(envelope.Type, reDecoded.Type);
        Assert.Equal(envelope.SessionId, reDecoded.SessionId);
        Assert.Equal(envelope.MessageId, reDecoded.MessageId);
        Assert.Equal(envelope.Seq, reDecoded.Seq);
    }

    [Theory]
    [InlineData("invalid_bom.bin")]
    [InlineData("invalid_malformed_utf8.bin")]
    [InlineData("invalid_decimal_overflow.json")]
    [InlineData("invalid_depth_exceeded.json")]
    [InlineData("invalid_duplicate_keys.json")]
    [InlineData("invalid_exceeds_16kib.json")]
    [InlineData("invalid_negative_seq.json")]
    [InlineData("invalid_non_canonical_uuid.json")]
    [InlineData("invalid_oversized_extensions.json")]
    [InlineData("invalid_unknown_body_field.json")]
    [InlineData("invalid_unsupported_version.json")]
    public void CanonicalNegativeFixtures_ThrowProtocolViolationException(string fixtureFileName)
    {
        string filePath = Path.Combine(FixturesDir, fixtureFileName);
        Assert.True(File.Exists(filePath), $"Fixture file not found: {filePath}");

        byte[] rawBytes = File.ReadAllBytes(filePath);

        Assert.ThrowsAny<Exception>(() =>
        {
            ProtocolCodec.Decode(rawBytes);
        });
    }
}
