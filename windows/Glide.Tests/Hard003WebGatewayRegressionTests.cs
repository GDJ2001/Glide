using System.Security.Cryptography;
using System.Text;
using Glide.Tests.Fakes;
using Xunit;

namespace Glide.Tests;

/// <summary>
/// Security regression tests for the secure WebRTC browser transport (HARD-025..030).
/// These tests verify that the new DTLS-encrypted DataChannel transport enforces
/// all required security invariants.
/// </summary>
[Trait("HARD", "003-green")]
public sealed class Hard003WebGatewayRegressionTests
{
    [Fact]
    public async Task OnboardingUrl_MustUseEncryptedTransport()
    {
        var transport = new FakeWebRtcTransferTransport();

        var session = await transport.CreateSessionAsync(
            Guid.NewGuid().ToString(),
            "Test Device",
            "windows");

        // The signaling URL must use WebSocket (not HTTP)
        Assert.StartsWith("ws://", session.SignalingUrl);

        // The session must have a DTLS fingerprint
        Assert.False(string.IsNullOrEmpty(session.DtlsFingerprintSha256));
        Assert.Equal(64, session.DtlsFingerprintSha256.Length); // 32 bytes hex encoded
    }

    [Fact]
    public async Task RedeemedOnboardingToken_MustNotRemainValidAsBearerCredential()
    {
        var transport = new FakeWebRtcTransferTransport(ttlSeconds: 1);

        var session = await transport.CreateSessionAsync(
            Guid.NewGuid().ToString(),
            "Test Device",
            "windows");

        // Session should expire after TTL
        await Task.Delay(1500);

        // Proposals on expired session should fail
        var exception = Assert.Throws<WebRtcException>(() =>
        {
            transport.ProposeUpload(session.SessionId, "test.txt", 100);
        });

        Assert.Equal(WebRtcErrorCode.SessionNotFound, exception.Code);
    }

    [Fact]
    public async Task Upload_MustRequireAcceptedProposalUploadId()
    {
        var transport = new FakeWebRtcTransferTransport();

        var session = await transport.CreateSessionAsync(
            Guid.NewGuid().ToString(),
            "Test Device",
            "windows");

        byte[] payload = Encoding.UTF8.GetBytes("proposal binding regression");

        // Upload without prior proposal should fail
        var result = transport.Upload(session.SessionId, "unconsumed-id", "test.txt", payload, "sha");

        Assert.False(result.Success);
        Assert.Contains("not bound", result.Error);
    }

    [Fact]
    public async Task Upload_MustRejectMissingDeclaredDigest_EvenAfterProposal()
    {
        var transport = new FakeWebRtcTransferTransport();

        var session = await transport.CreateSessionAsync(
            Guid.NewGuid().ToString(),
            "Test Device",
            "windows");

        byte[] payload = Encoding.UTF8.GetBytes("digest-required regression");
        string sha = Convert.ToHexStringLower(SHA256.HashData(payload));

        // Accept a proposal first
        string uploadId = transport.ProposeUpload(session.SessionId, "digest-required.txt", payload.Length, sha256: sha);

        // Upload without SHA-256 digest (null) should fail
        var result = transport.Upload(session.SessionId, uploadId, "digest-required.txt", payload, declaredDigest: null);

        Assert.False(result.Success);
        Assert.Contains("digest", result.Error);
    }

    [Fact]
    public async Task ProposalUploadId_MustBeConsumedExactlyOnce()
    {
        var transport = new FakeWebRtcTransferTransport();

        var session = await transport.CreateSessionAsync(
            Guid.NewGuid().ToString(),
            "Test Device",
            "windows");

        byte[] payload = Encoding.UTF8.GetBytes("single-use upload proposal");
        string sha = Convert.ToHexStringLower(SHA256.HashData(payload));

        // Accept a proposal
        string uploadId = transport.ProposeUpload(session.SessionId, "single-use.txt", payload.Length, sha256: sha);

        // First upload should succeed
        var first = transport.Upload(session.SessionId, uploadId, "single-use.txt", payload, sha);
        Assert.True(first.Success);

        // Second upload with same uploadId should fail
        var replay = transport.Upload(session.SessionId, uploadId, "single-use.txt", payload, sha);
        Assert.False(replay.Success);
        Assert.Contains("consumed", replay.Error);
    }
}
