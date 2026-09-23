using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;

namespace Glide.Tests.Fakes;

/// <summary>
/// WebRTC session bootstrap info for QR verification.
/// Self-contained test double — mirrors Glide.Core.Transport.WebRtc.WebRtcSessionInfo.
/// </summary>
public sealed record WebRtcSessionInfo(
    string SessionId,
    string LocalSdpOffer,
    string DtlsFingerprintSha256,
    string SignalingUrl,
    long ExpiresAtEpochMs
)
{
    public void Validate()
    {
        if (string.IsNullOrWhiteSpace(SessionId))
            throw new ArgumentException("SessionId must not be blank.", nameof(SessionId));
        if (string.IsNullOrWhiteSpace(LocalSdpOffer))
            throw new ArgumentException("LocalSdpOffer must not be blank.", nameof(LocalSdpOffer));
        if (string.IsNullOrWhiteSpace(DtlsFingerprintSha256))
            throw new ArgumentException("DtlsFingerprintSha256 must not be blank.", nameof(DtlsFingerprintSha256));
        if (string.IsNullOrWhiteSpace(SignalingUrl))
            throw new ArgumentException("SignalingUrl must not be blank.", nameof(SignalingUrl));
        if (ExpiresAtEpochMs <= 0)
            throw new ArgumentException("ExpiresAtEpochMs must be positive.", nameof(ExpiresAtEpochMs));
    }
}

/// <summary>
/// WebRTC peer connection state.
/// </summary>
public enum WebRtcPeerState
{
    New,
    Connecting,
    Connected,
    Disconnected,
    Failed,
    Closed
}

/// <summary>
/// Exception type for WebRTC transport errors.
/// </summary>
public class WebRtcException : Exception
{
    public WebRtcErrorCode Code { get; }

    public WebRtcException(string message, WebRtcErrorCode code = WebRtcErrorCode.Unknown)
        : base(message)
    {
        Code = code;
    }
}

/// <summary>
/// Error codes for WebRTC transport failures.
/// </summary>
public enum WebRtcErrorCode
{
    SessionNotFound,
    DtlsHandshakeFailed,
    DtlsFingerprintMismatch,
    DatachannelOpenFailed,
    SdpExchangeFailed,
    IceNegotiationFailed,
    SignalingError,
    FlowControlOverflow,
    Unknown
}

/// <summary>
/// In-memory fake WebRTC transfer transport for .NET security regression tests.
///
/// Simulates the secure WebRTC DataChannel transport specified in HARD-025..030.
/// This fake mimics the behavior of a real WebRTC peer connection without requiring
/// platform-native WebRTC libraries.
///
/// Security invariants enforced:
/// - DTLS 1.2+ encryption (simulated: no plaintext channel exists)
/// - DTLS fingerprint verification (simulated via WebRtcSessionInfo.DtlsFingerprintSha256)
/// - Ephemeral, single-session DTLS credentials (new fingerprint per session)
/// - Session expiry (TTL-based, default 120s)
/// - UploadId binding (uploads require accepted proposal)
/// - Mandatory SHA-256 digest verification
/// - Single-use upload IDs (consumed exactly once)
/// </summary>
public sealed class FakeWebRtcTransferTransport : IAsyncDisposable
{
    private readonly ConcurrentDictionary<string, SessionState> _sessions = new();
    private readonly long _ttlMs;

    public FakeWebRtcTransferTransport(int ttlSeconds = 120)
    {
        _ttlMs = ttlSeconds * 1000L;
    }

    private sealed record SessionState(
        WebRtcSessionInfo Info,
        long CreatedAtEpochMs,
        ConcurrentDictionary<string, ProposalState> Proposals,
        ConcurrentBag<string> CompletedUploads
    );

    private sealed record ProposalState(
        string UploadId,
        string FileName,
        long FileSize,
        string MimeType,
        string Sha256,
        bool Consumed
    );

    public Task<WebRtcSessionInfo> CreateSessionAsync(
        string sessionId,
        string localDeviceName,
        string localPlatform,
        CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(sessionId))
            throw new ArgumentException("SessionId must not be blank.", nameof(sessionId));

        var fingerprintBytes = RandomNumberGenerator.GetBytes(32);
        var fingerprint = Convert.ToHexStringLower(fingerprintBytes);
        var expiresAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + _ttlMs;

        var info = new WebRtcSessionInfo(
            SessionId: sessionId,
            LocalSdpOffer: $"v=0\r\no=- {sessionId} 1 IN IP4 127.0.0.1\r\n...",
            DtlsFingerprintSha256: fingerprint,
            SignalingUrl: $"ws://127.0.0.1:0/signal?session={sessionId}",
            ExpiresAtEpochMs: expiresAt
        );

        var state = new SessionState(
            Info: info,
            CreatedAtEpochMs: DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            Proposals: new ConcurrentDictionary<string, ProposalState>(),
            CompletedUploads: new ConcurrentBag<string>()
        );

        _sessions[sessionId] = state;
        return Task.FromResult(info);
    }

    public ValueTask DisposeAsync()
    {
        _sessions.Clear();
        return ValueTask.CompletedTask;
    }

    // --- Test helpers ---

    public string ProposeUpload(
        string sessionId,
        string fileName,
        long fileSize,
        string mimeType = "application/octet-stream",
        string? sha256 = null)
    {
        if (!_sessions.TryGetValue(sessionId, out var session))
            throw new WebRtcException("Session not found", WebRtcErrorCode.SessionNotFound);

        if (DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() > session.Info.ExpiresAtEpochMs)
            throw new WebRtcException("Session expired", WebRtcErrorCode.SessionNotFound);

        var uploadId = Guid.NewGuid().ToString();
        var digest = sha256 ?? Convert.ToHexStringLower(SHA256.HashData(Array.Empty<byte>()));

        session.Proposals[uploadId] = new ProposalState(
            UploadId: uploadId,
            FileName: fileName,
            FileSize: fileSize,
            MimeType: mimeType,
            Sha256: digest,
            Consumed: false
        );

        return uploadId;
    }

    public UploadResult Upload(
        string sessionId,
        string uploadId,
        string fileName,
        byte[] payload,
        string? declaredDigest = null)
    {
        if (!_sessions.TryGetValue(sessionId, out var session))
            return new UploadResult(Success: false, Error: "Session not found");

        if (DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() > session.Info.ExpiresAtEpochMs)
            return new UploadResult(Success: false, Error: "Session expired");

        if (!session.Proposals.TryGetValue(uploadId, out var proposal))
            return new UploadResult(Success: false, Error: "UploadId not bound to an accepted proposal");

        if (proposal.Consumed)
            return new UploadResult(Success: false, Error: "UploadId already consumed (single-use)");

        // Mandatory digest check — declaredDigest must not be null
        if (declaredDigest is null)
            return new UploadResult(Success: false, Error: "SHA-256 digest is required");

        var digest = declaredDigest;
        var actualDigest = Convert.ToHexStringLower(SHA256.HashData(payload));
        if (!CryptographicOperations.FixedTimeEquals(
            Convert.FromHexString(digest),
            Convert.FromHexString(actualDigest)))
        {
            return new UploadResult(Success: false, Error: "SHA-256 checksum mismatch");
        }

        // Consume (single-use)
        session.Proposals[uploadId] = proposal with { Consumed = true };
        return new UploadResult(Success: true, FileName: fileName, Sha256: digest);
    }

    public sealed record UploadResult(
        bool Success,
        string? FileName = null,
        string? Sha256 = null,
        string? Error = null
    );
}
