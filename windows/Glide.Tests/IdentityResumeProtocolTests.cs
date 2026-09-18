using System.Text.Json.Nodes;
using Glide.Core.Codec;
using Glide.Core.Models;
using Glide.Core.Security;

namespace Glide.Tests;

public sealed class IdentityResumeProtocolTests
{
    private static readonly SessionId ActiveSession =
        new("11111111-1111-4111-8111-111111111111");

    private const string ChallengeMessageId =
        "22222222-2222-4222-8222-222222222222";
    private const string OriginalSessionId =
        "33333333-3333-4333-8333-333333333333";
    private const string ItemId =
        "44444444-4444-4444-8444-444444444444";
    private const string RequestMessageId =
        "55555555-5555-4555-8555-555555555555";
    private const string SignerFingerprint =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private const string VerifierFingerprint =
        "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd";
    private const string Nonce =
        "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
    private const string ChannelBinding =
        "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100";
    private const string CapabilityHash =
        "992a66ad3dccb3c5190e046f92b6190751644f1f98946ac6511350952d333b47";
    private const string RootHash =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    [Fact]
    public void IdentityChallenge_RoundTripsWithBoundFields()
    {
        var body = new JsonObject
        {
            ["profile"] =
                IdentityResumeTranscript.IdentityProfile,
            ["challengeNonceHex"] = Nonce,
            ["verifierFingerprintHex"] =
                VerifierFingerprint,
            ["verifierRole"] = "receiver",
            ["signerRole"] = "sender",
            ["channelBindingHex"] = ChannelBinding,
            ["capabilityHashHex"] = CapabilityHash
        };

        Envelope decoded =
            RoundTrip(
                MessageTypes.IdentityChallenge,
                ChallengeMessageId,
                body);

        Assert.Equal(
            MessageTypes.IdentityChallenge,
            decoded.Type);
        Assert.Equal(
            Nonce,
            decoded.Body["challengeNonceHex"]!
                .ToString());
    }

    [Fact]
    public void ResumeRequest_RoundTripsWhenJournalMathMatches()
    {
        Envelope decoded =
            RoundTrip(
                MessageTypes.ResumeRequest,
                RequestMessageId,
                ValidResumeBody());

        Assert.Equal(
            "2097152",
            decoded.Body["contiguousByteOffset"]!
                .ToString());
        Assert.Equal(
            "2",
            decoded.Body["verifiedChunkCount"]!
                .ToString());
    }

    [Fact]
    public void ResumeRequest_MismatchedChunkCount_IsRejected()
    {
        JsonObject body =
            ValidResumeBody();
        body["verifiedChunkCount"] = 3;

        Assert.Throws<ProtocolViolationException>(
            () =>
                RoundTrip(
                    MessageTypes.ResumeRequest,
                    RequestMessageId,
                    body));
    }

    [Fact]
    public void IdentityChallenge_UppercaseFingerprint_IsRejected()
    {
        var body = new JsonObject
        {
            ["profile"] =
                IdentityResumeTranscript.IdentityProfile,
            ["challengeNonceHex"] = Nonce,
            ["verifierFingerprintHex"] =
                VerifierFingerprint.ToUpperInvariant(),
            ["verifierRole"] = "receiver",
            ["signerRole"] = "sender",
            ["channelBindingHex"] = ChannelBinding,
            ["capabilityHashHex"] = CapabilityHash
        };

        Assert.Throws<ProtocolViolationException>(
            () =>
                RoundTrip(
                    MessageTypes.IdentityChallenge,
                    ChallengeMessageId,
                    body));
    }

    [Fact]
    public void ResumeReject_UnknownCode_IsRejected()
    {
        var body = new JsonObject
        {
            ["profile"] =
                IdentityResumeTranscript.ResumeProfile,
            ["requestMessageId"] =
                RequestMessageId,
            ["originalSessionId"] =
                OriginalSessionId,
            ["itemId"] = ItemId,
            ["code"] = "FREE_FORM_REASON"
        };

        Assert.Throws<ProtocolViolationException>(
            () =>
                RoundTrip(
                    MessageTypes.ResumeReject,
                    "66666666-6666-4666-8666-666666666666",
                    body));
    }

    [Fact]
    public void OfferResumeMetadata_RoundTripsWhenPairIsCanonical()
    {
        JsonObject body =
            ValidOfferPageBody();
        JsonObject item =
            (JsonObject)((JsonArray)body["items"]!)[0]!;
        item["rootHash"] = RootHash;
        item["chunkSize"] = 1_048_576;

        Envelope decoded =
            RoundTrip(
                MessageTypes.OfferPage,
                "77777777-7777-4777-8777-777777777777",
                body);

        JsonObject decodedItem =
            (JsonObject)
                ((JsonArray)decoded.Body["items"]!)[0]!;
        Assert.Equal(
            RootHash,
            decodedItem["rootHash"]!.ToString());
        Assert.Equal(
            "1048576",
            decodedItem["chunkSize"]!.ToString());
    }

    [Fact]
    public void OfferResumeMetadata_MissingChunkSize_IsRejected()
    {
        JsonObject body =
            ValidOfferPageBody();
        JsonObject item =
            (JsonObject)((JsonArray)body["items"]!)[0]!;
        item["rootHash"] = RootHash;

        Assert.Throws<ProtocolViolationException>(
            () =>
                RoundTrip(
                    MessageTypes.OfferPage,
                    "77777777-7777-4777-8777-777777777778",
                    body));
    }

    [Fact]
    public void OfferResumeMetadata_UppercaseRoot_IsRejected()
    {
        JsonObject body =
            ValidOfferPageBody();
        JsonObject item =
            (JsonObject)((JsonArray)body["items"]!)[0]!;
        item["rootHash"] = RootHash.ToUpperInvariant();
        item["chunkSize"] = 1_048_576;

        Assert.Throws<ProtocolViolationException>(
            () =>
                RoundTrip(
                    MessageTypes.OfferPage,
                    "77777777-7777-4777-8777-777777777779",
                    body));
    }

    [Fact]
    public void ResumeCapability_RequiresIdentityCapability()
    {
        Assert.Throws<ProtocolViolationException>(
            () =>
                ProtocolCapabilities
                    .ValidateDependencies(
                        [ProtocolCapabilities.ResumeV1]));

        ProtocolCapabilities.ValidateDependencies(
            [
                ProtocolCapabilities.IdentityV1,
                ProtocolCapabilities.ResumeV1
            ]);
    }

    [Fact]
    public void ProductionOptionalCapabilities_DoNotAdvertiseTrustYet()
    {
        Assert.Contains(
            ProtocolCapabilities.FolderV1,
            ProtocolCapabilities.ProductionOptional);
        Assert.DoesNotContain(
            ProtocolCapabilities.IdentityV1,
            ProtocolCapabilities.ProductionOptional);
        Assert.DoesNotContain(
            ProtocolCapabilities.ResumeV1,
            ProtocolCapabilities.ProductionOptional);
    }

    private static JsonObject ValidOfferPageBody() =>
        new()
        {
            ["batchId"] =
                "88888888-8888-4888-8888-888888888888",
            ["pageIndex"] = 0,
            ["items"] =
                new JsonArray(
                    new JsonObject
                    {
                        ["itemId"] =
                            "99999999-9999-4999-8999-999999999999",
                        ["name"] = "resume.bin",
                        ["mime"] =
                            "application/octet-stream",
                        ["sizeBytes"] = "1048576"
                    })
        };

    private static JsonObject ValidResumeBody() =>
        new()
        {
            ["profile"] =
                IdentityResumeTranscript.ResumeProfile,
            ["originalSessionId"] =
                OriginalSessionId,
            ["itemId"] = ItemId,
            ["requesterFingerprintHex"] =
                VerifierFingerprint,
            ["responderFingerprintHex"] =
                SignerFingerprint,
            ["requesterRole"] = "receiver",
            ["responderRole"] = "sender",
            ["verifiedChunkCount"] = 2,
            ["contiguousByteOffset"] = "2097152",
            ["chunkSize"] = 1_048_576,
            ["totalSizeBytes"] = "5242880",
            ["chunkRootHashHex"] = RootHash,
            ["sessionNonceHex"] = Nonce,
            ["channelBindingHex"] =
                ChannelBinding,
            ["capabilityHashHex"] =
                CapabilityHash,
            ["signatureHex"] = "30440220"
        };

    private static Envelope RoundTrip(
        string type,
        string messageId,
        JsonObject body)
    {
        var envelope = new Envelope(
            1,
            type,
            ActiveSession,
            new MessageId(messageId),
            1,
            body);

        return ProtocolCodec.Decode(
            ProtocolCodec.Encode(envelope));
    }
}
