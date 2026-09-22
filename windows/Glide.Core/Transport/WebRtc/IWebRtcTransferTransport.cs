using System.IO;
using System.Runtime.CompilerServices;
using System.Threading;
using System.Threading.Tasks;
using Glide.Core.Models;

namespace Glide.Core.Transport;

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
/// WebRTC data channel reliability mode.
/// </summary>
public enum DataChannelMode
{
    /// <summary>Guaranteed delivery, in-order. Default for Glide transfers.</summary>
    ReliableOrdered,
    /// <summary>Guaranteed delivery, any-order.</summary>
    ReliableUnordered,
    /// <summary>Bounded retransmits, time-limited. Not used by Glide.</summary>
    PartialReliability
}

/// <summary>
/// Configuration for a WebRTC data channel.
/// </summary>
public sealed record DataChannelConfig(
    string Label,
    DataChannelMode Mode = DataChannelMode.ReliableOrdered,
    int? MaxRetransmits = null,
    int? MaxPacketLifeTimeMs = null,
    bool Negotiated = false,
    string Protocol = "glide-webrtc/1"
)
{
    /// <summary>Validates the configuration.</summary>
    public void Validate()
    {
        if (string.IsNullOrWhiteSpace(Label))
            throw new ArgumentException("DataChannel label must not be blank.", nameof(Label));
        if (Label.Length > 512)
            throw new ArgumentException("DataChannel label must be <= 512 characters.", nameof(Label));
        if (Mode == DataChannelMode.PartialReliability && !(MaxRetransmits ?? MaxPacketLifeTimeMs).HasValue)
            throw new ArgumentException("PARTIAL_RELIABILITY requires maxRetransmits or maxPacketLifeTimeMs.");
    }
}

/// <summary>
/// Result of a WebRTC session bootstrap.
/// Contains all information needed by the remote peer to establish
/// a WebRTC connection and verify the DTLS fingerprint.
/// </summary>
public sealed record WebRtcSessionInfo(
    string SessionId,
    string LocalSdpOffer,
    string DtlsFingerprintSha256,
    string SignalingUrl,
    long ExpiresAtEpochMs
)
{
    /// <summary>Validates the session info.</summary>
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
/// Incoming data channel message.
/// </summary>
public abstract record DataChannelMessage
{
    private DataChannelMessage() { }

    /// <summary>Text (JSON control) message.</summary>
    public sealed record Text(string Content) : DataChannelMessage;

    /// <summary>Binary (bulk data) message.</summary>
    public sealed record Binary(byte[] Content) : DataChannelMessage;
}

/// <summary>
/// ICE candidate information for signaling exchange.
/// </summary>
public sealed record IceCandidateInfo(
    string Candidate,
    string SdpMid,
    int SdpMLineIndex
)
{
    /// <summary>Validates the ICE candidate.</summary>
    public void Validate()
    {
        if (string.IsNullOrWhiteSpace(Candidate))
            throw new ArgumentException("ICE candidate must not be blank.", nameof(Candidate));
        if (string.IsNullOrWhiteSpace(SdpMid))
            throw new ArgumentException("sdpMid must not be blank.", nameof(SdpMid));
        if (SdpMLineIndex < 0)
            throw new ArgumentException("sdpMLineIndex must be non-negative.", nameof(SdpMLineIndex));
    }
}

/// <summary>
/// Incoming stream from WebRTC data channel.
/// Represents a file being received from the browser client.
/// </summary>
public sealed record IncomingWebRtcStream(
    string ItemId,
    string FileName,
    long TotalBytes,
    Stream Stream
)
{
    /// <summary>Validates the incoming stream.</summary>
    public void Validate()
    {
        if (string.IsNullOrWhiteSpace(ItemId))
            throw new ArgumentException("ItemId must not be blank.", nameof(ItemId));
        if (string.IsNullOrWhiteSpace(FileName))
            throw new ArgumentException("FileName must not be blank.", nameof(FileName));
        if (TotalBytes < 0)
            throw new ArgumentException("TotalBytes must be non-negative.", nameof(TotalBytes));
    }
}

/// <summary>
/// WebRTC transfer transport adapter (HARD-025–030).
/// Replaces the insecure HTTP-based GlideWebGateway with a DTLS-encrypted
/// WebRTC DataChannel transport.
/// </summary>
/// <remarks>
/// <para><b>Security Invariants</b></para>
/// <list type="bullet">
///   <item>All communication MUST be DTLS 1.2+ encrypted</item>
///   <item>DTLS fingerprints MUST be verified out-of-band (QR code)</item>
///   <item>Ephemeral, single-session DTLS credentials</item>
///   <item>No plaintext fallback permitted</item>
/// </list>
/// <para><b>Implementation Requirements</b></para>
/// <list type="bullet">
///   <item>Android: Use org.webrtc:google-webrtc library</item>
///   <item>.NET: Use WebRTC.NET or SIPSorcery bindings</item>
///   <item>No libdatachannel native bindings</item>
/// </list>
/// <para><b>Lifecycle</b></para>
/// <list type="number">
///   <item><see cref="CreateSessionAsync"/> — Generate ephemeral DTLS credentials and SDP offer</item>
///   <item>Exchange SDP offer/answer and ICE candidates via signaling channel</item>
///   <item><see cref="HandleSdpAnswerAsync"/> / <see cref="AddIceCandidateAsync"/> — Process remote signaling</item>
///   <item>DTLS handshake (automatic via WebRTC stack)</item>
///   <item>Verify DTLS fingerprint matches QR code (browser-side)</item>
///   <item>DataChannel opens — transfer control and bulk data</item>
///   <item><see cref="DisposeAsync"/> — Terminate session, zero ephemeral keys</item>
/// </list>
/// </remarks>
public interface IWebRtcTransferTransport : IAsyncDisposable
{
    /// <summary>
    /// Initialize a new WebRTC session and generate an SDP offer.
    /// </summary>
    /// <remarks>
    /// This method:
    /// <list type="number">
    ///   <item>Generates an ephemeral ECDSA P-256 key pair</item>
    ///   <item>Creates a self-signed X.509 certificate</item>
    ///   <item>Computes the SHA-256 DTLS fingerprint</item>
    ///   <item>Creates a WebRTC PeerConnection with the ephemeral certificate</item>
    ///   <item>Generates an SDP offer</item>
    ///   <item>Returns session info for QR code encoding</item>
    /// </list>
    /// </remarks>
    /// <param name="sessionId">Unique session identifier (UUIDv4)</param>
    /// <param name="localDeviceName">Human-readable device name</param>
    /// <param name="localPlatform">Platform identifier ("android", "windows", "linux")</param>
    /// <param name="cancellationToken">Cancellation token</param>
    /// <returns>Session info including SDP offer, DTLS fingerprint, and signaling URL</returns>
    /// <exception cref="WebRtcException">If session creation fails</exception>
    Task<WebRtcSessionInfo> CreateSessionAsync(
        string sessionId,
        string localDeviceName,
        string localPlatform,
        CancellationToken cancellationToken = default
    );

    /// <summary>
    /// Process an incoming SDP answer from the remote peer.
    /// </summary>
    /// <remarks>
    /// Called when the browser responds with its SDP answer.
    /// After this call, DTLS handshake begins automatically.
    /// </remarks>
    /// <param name="sdpAnswer">SDP answer string from remote peer</param>
    /// <param name="cancellationToken">Cancellation token</param>
    /// <exception cref="WebRtcException">If SDP answer is invalid or session not found</exception>
    Task HandleSdpAnswerAsync(
        string sdpAnswer,
        CancellationToken cancellationToken = default
    );

    /// <summary>
    /// Add a remote ICE candidate received via signaling.
    /// </summary>
    /// <remarks>
    /// ICE candidates are exchanged out-of-band via the signaling channel.
    /// This method adds a candidate gathered by the remote peer.
    /// </remarks>
    /// <param name="candidate">ICE candidate string</param>
    /// <param name="sdpMid">SDP media identification ("0" for first media stream)</param>
    /// <param name="sdpMLineIndex">SDP media line index</param>
    /// <param name="cancellationToken">Cancellation token</param>
    /// <exception cref="WebRtcException">If candidate is invalid</exception>
    Task AddIceCandidateAsync(
        string candidate,
        string sdpMid,
        int sdpMLineIndex,
        CancellationToken cancellationToken = default
    );

    /// <summary>
    /// Observe peer connection state changes.
    /// </summary>
    /// <remarks>
    /// Emits state transitions: New → Connecting → Connected → Disconnected/Closed.
    /// Implementations SHOULD emit Failed on DTLS handshake failure.
    /// </remarks>
    /// <param name="cancellationToken">Cancellation token</param>
    /// <returns>Async enumerable of peer state changes</returns>
    IAsyncEnumerable<WebRtcPeerState> ObservePeerStateAsync(
        [EnumeratorCancellation] CancellationToken cancellationToken = default
    );

    /// <summary>
    /// Observe incoming ICE candidates to be sent to remote peer via signaling.
    /// </summary>
    /// <remarks>
    /// Local ICE candidates are gathered by the WebRTC stack and must be
    /// forwarded to the remote peer through the signaling channel.
    /// </remarks>
    /// <param name="cancellationToken">Cancellation token</param>
    /// <returns>Async enumerable of ICE candidates</returns>
    IAsyncEnumerable<IceCandidateInfo> ObserveLocalIceCandidatesAsync(
        [EnumeratorCancellation] CancellationToken cancellationToken = default
    );

    /// <summary>
    /// Observe incoming data channel messages.
    /// </summary>
    /// <remarks>
    /// Emits messages from the remote peer's DataChannel.
    /// Messages are either text (control) or binary (bulk data).
    /// </remarks>
    /// <param name="cancellationToken">Cancellation token</param>
    /// <returns>Async enumerable of data channel messages</returns>
    IAsyncEnumerable<DataChannelMessage> ObserveDataChannelMessagesAsync(
        [EnumeratorCancellation] CancellationToken cancellationToken = default
    );

    /// <summary>
    /// Send a text control message over the data channel.
    /// </summary>
    /// <remarks>
    /// Used for JSON control messages (manifest, proposals, consent, errors).
    /// </remarks>
    /// <param name="text">JSON-encoded control message</param>
    /// <param name="cancellationToken">Cancellation token</param>
    /// <exception cref="WebRtcException">If DataChannel is not open</exception>
    Task SendControlMessageAsync(
        string text,
        CancellationToken cancellationToken = default
    );

    /// <summary>
    /// Send binary data over the data channel with flow control.
    /// </summary>
    /// <remarks>
    /// Implements bounded-buffered sending to prevent overwhelming the
    /// DataChannel's internal buffer. Monitors bufferedAmount and
    /// applies backpressure when exceeding high-water mark.
    /// </remarks>
    /// <param name="data">Binary payload (file chunk)</param>
    /// <param name="cancellationToken">Cancellation token</param>
    /// <returns>Async enumerable of progress updates</returns>
    /// <exception cref="WebRtcException">If DataChannel is not open</exception>
    IAsyncEnumerable<BulkProgress> SendBinaryDataAsync(
        byte[] data,
        CancellationToken cancellationToken = default
    );

    /// <summary>
    /// Send a stream's contents over the data channel with flow control.
    /// </summary>
    /// <remarks>
    /// Streams data in chunks to maintain O(1) memory usage.
    /// Reads from the stream in 64 KiB chunks and sends each chunk
    /// with flow control.
    /// </remarks>
    /// <param name="stream">Input stream to read from</param>
    /// <param name="totalBytes">Total expected bytes (for progress reporting)</param>
    /// <param name="cancellationToken">Cancellation token</param>
    /// <returns>Async enumerable of progress updates</returns>
    /// <exception cref="WebRtcException">If DataChannel is not open</exception>
    IAsyncEnumerable<BulkProgress> SendStreamAsync(
        Stream stream,
        long totalBytes,
        CancellationToken cancellationToken = default
    );

    /// <summary>
    /// Register an expected incoming stream for receive mode.
    /// </summary>
    /// <remarks>
    /// Called when the browser proposes to upload a file.
    /// The transport should prepare to receive the specified number of bytes.
    /// </remarks>
    /// <param name="itemId">Unique item identifier</param>
    /// <param name="expectedBytes">Expected stream size in bytes</param>
    void RegisterExpectedStream(string itemId, long expectedBytes);

    /// <summary>
    /// Observe incoming binary streams (for receive mode).
    /// </summary>
    /// <remarks>
    /// Emits <see cref="IncomingWebRtcStream"/> when a new file upload begins.
    /// The stream should be consumed and SHA-256 verified.
    /// </remarks>
    /// <param name="cancellationToken">Cancellation token</param>
    /// <returns>Async enumerable of incoming streams</returns>
    IAsyncEnumerable<IncomingWebRtcStream> ObserveIncomingStreamsAsync(
        [EnumeratorCancellation] CancellationToken cancellationToken = default
    );
}

/// <summary>
/// Exception type for WebRTC transport errors.
/// </summary>
public class WebRtcException : Exception
{
    /// <summary>The error code for this exception.</summary>
    public WebRtcErrorCode Code { get; }

    public WebRtcException(string message, WebRtcErrorCode code = WebRtcErrorCode.Unknown)
        : base(message)
    {
        Code = code;
    }

    public WebRtcException(string message, Exception innerException, WebRtcErrorCode code = WebRtcErrorCode.Unknown)
        : base(message, innerException)
    {
        Code = code;
    }
}

/// <summary>
/// Error codes for WebRTC transport failures.
/// </summary>
public enum WebRtcErrorCode
{
    /// <summary>Session not found or expired</summary>
    SessionNotFound,
    /// <summary>DTLS handshake failed</summary>
    DtlsHandshakeFailed,
    /// <summary>DTLS fingerprint mismatch (MITM suspected)</summary>
    DtlsFingerprintMismatch,
    /// <summary>DataChannel could not be opened</summary>
    DatachannelOpenFailed,
    /// <summary>SDP offer/answer exchange failed</summary>
    SdpExchangeFailed,
    /// <summary>ICE negotiation failed</summary>
    IceNegotiationFailed,
    /// <summary>Signaling channel error</summary>
    SignalingError,
    /// <summary>Flow control buffer overflow</summary>
    FlowControlOverflow,
    /// <summary>Unknown error</summary>
    Unknown
}

/// <summary>
/// Placeholder implementation of <see cref="IWebRtcTransferTransport"/> for compilation.
/// This stub exists to satisfy the interface contract during HARD-025.
/// It MUST be replaced with a real implementation in HARD-026 (Android)
/// and HARD-027 (.NET).
/// </summary>
public sealed class PlaceholderWebRtcTransferTransport : IWebRtcTransferTransport
{
    public Task<WebRtcSessionInfo> CreateSessionAsync(
        string sessionId,
        string localDeviceName,
        string localPlatform,
        CancellationToken cancellationToken = default)
    {
        throw new NotImplementedException(
            "IWebRtcTransferTransport.CreateSessionAsync not implemented. " +
            "Implement in HARD-026 (Android) or HARD-027 (.NET)."
        );
    }

    public Task HandleSdpAnswerAsync(string sdpAnswer, CancellationToken cancellationToken = default)
    {
        throw new NotImplementedException(
            "IWebRtcTransferTransport.HandleSdpAnswerAsync not implemented."
        );
    }

    public Task AddIceCandidateAsync(string candidate, string sdpMid, int sdpMLineIndex, CancellationToken cancellationToken = default)
    {
        throw new NotImplementedException(
            "IWebRtcTransferTransport.AddIceCandidateAsync not implemented."
        );
    }

    public IAsyncEnumerable<WebRtcPeerState> ObservePeerStateAsync([EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        throw new NotImplementedException(
            "IWebRtcTransferTransport.ObservePeerStateAsync not implemented."
        );
    }

    public IAsyncEnumerable<IceCandidateInfo> ObserveLocalIceCandidatesAsync([EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        throw new NotImplementedException(
            "IWebRtcTransferTransport.ObserveLocalIceCandidatesAsync not implemented."
        );
    }

    public IAsyncEnumerable<DataChannelMessage> ObserveDataChannelMessagesAsync([EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        throw new NotImplementedException(
            "IWebRtcTransferTransport.ObserveDataChannelMessagesAsync not implemented."
        );
    }

    public Task SendControlMessageAsync(string text, CancellationToken cancellationToken = default)
    {
        throw new NotImplementedException(
            "IWebRtcTransferTransport.SendControlMessageAsync not implemented."
        );
    }

    public IAsyncEnumerable<BulkProgress> SendBinaryDataAsync(byte[] data, CancellationToken cancellationToken = default)
    {
        throw new NotImplementedException(
            "IWebRtcTransferTransport.SendBinaryDataAsync not implemented."
        );
    }

    public IAsyncEnumerable<BulkProgress> SendStreamAsync(Stream stream, long totalBytes, CancellationToken cancellationToken = default)
    {
        throw new NotImplementedException(
            "IWebRtcTransferTransport.SendStreamAsync not implemented."
        );
    }

    public void RegisterExpectedStream(string itemId, long expectedBytes)
    {
        throw new NotImplementedException(
            "IWebRtcTransferTransport.RegisterExpectedStream not implemented."
        );
    }

    public IAsyncEnumerable<IncomingWebRtcStream> ObserveIncomingStreamsAsync([EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        throw new NotImplementedException(
            "IWebRtcTransferTransport.ObserveIncomingStreamsAsync not implemented."
        );
    }

    public ValueTask DisposeAsync()
    {
        return ValueTask.CompletedTask;
    }
}
