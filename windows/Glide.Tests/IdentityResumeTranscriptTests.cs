using System.Text.Json;
using Glide.Core.Security;

namespace Glide.Tests;

public sealed class IdentityResumeTranscriptTests
{
    private readonly JsonDocument _fixture =
        JsonDocument.Parse(
            File.ReadAllText(
                LocateFixture()));

    [Fact]
    public void CapabilityVector_MatchesSharedFixture()
    {
        JsonElement vector =
            _fixture.RootElement
                .GetProperty("capabilityVector");
        string[] capabilities =
            vector.GetProperty("capabilities")
                .EnumerateArray()
                .Select(
                    static item =>
                        item.GetString()!)
                .ToArray();

        byte[] canonical =
            IdentityResumeTranscript
                .CapabilityCanonicalBytes(
                    capabilities);

        Assert.Equal(
            vector.GetProperty("canonicalHex")
                .GetString(),
            Convert.ToHexStringLower(canonical));
        Assert.Equal(
            vector.GetProperty("sha256Hex")
                .GetString(),
            IdentityResumeTranscript
                .CapabilityHashHex(
                    capabilities));
    }

    [Fact]
    public void IdentityVector_MatchesSharedFixtureByteForByte()
    {
        JsonElement vector =
            _fixture.RootElement
                .GetProperty("identityVector");

        byte[] transcript =
            IdentityResumeTranscript.IdentityProofBytes(
                Value(vector, "activeSessionId"),
                Value(vector, "challengeMessageId"),
                Value(vector, "signerRole"),
                Value(vector, "verifierRole"),
                Value(vector, "signerFingerprintHex"),
                Value(vector, "verifierFingerprintHex"),
                Value(vector, "challengeNonceHex"),
                Value(vector, "channelBindingHex"),
                Value(vector, "capabilityHashHex"));

        Assert.Equal(
            Value(vector, "transcriptHex"),
            Convert.ToHexStringLower(transcript));
        Assert.Equal(
            Value(vector, "transcriptSha256Hex"),
            IdentityResumeTranscript
                .Sha256Hex(transcript));
    }

    [Fact]
    public void ResumeVector_MatchesSharedFixtureByteForByte()
    {
        JsonElement vector =
            _fixture.RootElement
                .GetProperty("resumeVector");

        byte[] transcript =
            IdentityResumeTranscript.ResumeRequestBytes(
                Value(vector, "activeSessionId"),
                Value(vector, "originalSessionId"),
                Value(vector, "itemId"),
                Value(vector, "requesterRole"),
                Value(vector, "responderRole"),
                Value(vector, "requesterFingerprintHex"),
                Value(vector, "responderFingerprintHex"),
                Value(vector, "sessionNonceHex"),
                Value(vector, "channelBindingHex"),
                Value(vector, "capabilityHashHex"),
                vector.GetProperty("chunkSize")
                    .GetInt32(),
                vector.GetProperty("totalSizeBytes")
                    .GetInt64(),
                vector.GetProperty("verifiedChunkCount")
                    .GetInt64(),
                vector.GetProperty("contiguousByteOffset")
                    .GetInt64(),
                Value(vector, "chunkRootHashHex"));

        Assert.Equal(
            Value(vector, "transcriptHex"),
            Convert.ToHexStringLower(transcript));
        Assert.Equal(
            Value(vector, "transcriptSha256Hex"),
            IdentityResumeTranscript
                .Sha256Hex(transcript));
    }

    [Fact]
    public void ResumeVector_RoleSwap_IsRejected()
    {
        JsonElement vector =
            _fixture.RootElement
                .GetProperty("resumeVector");

        Assert.Throws<ArgumentException>(
            () =>
                IdentityResumeTranscript.ResumeRequestBytes(
                    Value(vector, "activeSessionId"),
                    Value(vector, "originalSessionId"),
                    Value(vector, "itemId"),
                    "sender",
                    "sender",
                    Value(vector, "requesterFingerprintHex"),
                    Value(vector, "responderFingerprintHex"),
                    Value(vector, "sessionNonceHex"),
                    Value(vector, "channelBindingHex"),
                    Value(vector, "capabilityHashHex"),
                    1_048_576,
                    5_242_880,
                    2,
                    2_097_152,
                    Value(vector, "chunkRootHashHex")));
    }

    [Fact]
    public void IdentityVector_ZeroChannelBinding_IsRejected()
    {
        JsonElement vector =
            _fixture.RootElement
                .GetProperty("identityVector");

        Assert.Throws<ArgumentException>(
            () =>
                IdentityResumeTranscript.IdentityProofBytes(
                    Value(vector, "activeSessionId"),
                    Value(vector, "challengeMessageId"),
                    Value(vector, "signerRole"),
                    Value(vector, "verifierRole"),
                    Value(vector, "signerFingerprintHex"),
                    Value(vector, "verifierFingerprintHex"),
                    Value(vector, "challengeNonceHex"),
                    new string('0', 64),
                    Value(vector, "capabilityHashHex")));
    }
    private static string Value(
        JsonElement value,
        string property) =>
        value.GetProperty(property)
            .GetString()!;

    private static string LocateFixture()
    {
        var current =
            new DirectoryInfo(
                Directory.GetCurrentDirectory());

        while (current != null)
        {
            string candidate =
                Path.Combine(
                    current.FullName,
                    "protocol",
                    "fixtures",
                    "identity_resume_v1_vectors.json");
            if (File.Exists(candidate))
            {
                return candidate;
            }

            current = current.Parent;
        }

        throw new FileNotFoundException(
            "Could not locate identity_resume_v1_vectors.json");
    }
}
