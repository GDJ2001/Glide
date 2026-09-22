using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Runtime.CompilerServices;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using Glide.Core.Models;
using Glide.Core.Transport;

namespace Glide.Transport.WebRtc;

/// <summary>
/// .NET WebRTC Transport Adapter (HARD-027).
/// 
/// Implements <see cref="IWebRtcTransferTransport"/> using platform-native
/// WebRTC bindings for Windows/Linux Glide desktop applications.
/// 
/// Security invariants (HARD-025–030):
/// - DTLS 1.2+ encryption via native WebRTC PeerConnection
/// - Ephemeral ECDSA P-256 DTLS credentials per session
/// - SHA-256 fingerprint verification via QR code (out-of-band)
/// - No plaintext fallback
/// - O(1) memory streaming with bounded flow control
/// 
/// In production, this wraps a native WebRTC library (WebRTC.NET, SIPSorcery, etc.).
/// For testing and abstraction, the data channel is simulated.
/// </summary>
public sealed class DotNetWebRtcTransferTransport : IWebRtcTransferTransport
{
    private const int HighWaterMark = 1_048_576; // 1 MiB
    private const int LowWaterMark = 256 * 1024;   // 256 KiB
    private const int ChunkSize = 65536;            // 64 KiB
    private const int MaxControlMessageBytes = 16384;

    private readonly int _signalingPort;
    private readonly string _localAddress;
    private readonly int _signalingTtlSeconds;
    private volatile bool _isClosed;

    // Signaling
    private TcpListener? _signalingListener;
    private readonly CancellationTokenSource _cts = new();

    // Session state
    private SessionState? _sessionState;

    // Data channel
    private DataChannelHandle? _activeDataChannel;

    // Expected streams
    private readonly ConcurrentDictionary<string, long> _expectedStreams = new();

    // Concurrent bags for reactive streams
    private readonly ConcurrentBag<WebRtcPeerState> _peerStateBuffer = new();
    private readonly ConcurrentBag<IceCandidateInfo> _iceCandidatesBuffer = new();
    private readonly ConcurrentBag<DataChannelMessage> _dataChannelMessagesBuffer = new();
    private readonly ConcurrentBag<IncomingWebRtcStream> _incomingStreamsBuffer = new();

    // Semaphores for async enumeration
    private readonly SemaphoreSlim _peerStateSemaphore = new(0);
    private readonly SemaphoreSlim _iceCandidatesSemaphore = new(0);
    private readonly SemaphoreSlim _dataMessagesSemaphore = new(0);
    private readonly SemaphoreSlim _incomingStreamsSemaphore = new(0);

    public DotNetWebRtcTransferTransport(
        int signalingPort = 0,
        string localAddress = "127.0.0.1",
        int signalingTtlSeconds = 120)
    {
        _signalingPort = signalingPort;
        _localAddress = localAddress;
        _signalingTtlSeconds = signalingTtlSeconds;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Session State
    // ═══════════════════════════════════════════════════════════════════

    private sealed record SessionState(
        string SessionId,
        string LocalDeviceName,
        string LocalPlatform,
        byte[] PublicKeyDer,
        byte[] PrivateKeyDer,
        X509Certificate2 Certificate,
        string DtlsFingerprintSha256,
        string LocalSdpOffer,
        string SignalingUrl,
        long ExpiresAtEpochMs);

    // ═══════════════════════════════════════════════════════════════════
    // DataChannel Handle
    // ═══════════════════════════════════════════════════════════════════

    private interface DataChannelHandle
    {
        void SendBinary(byte[] data);
        void SendText(string text);
        long BufferedAmount();
        void Close();
        bool IsOpen { get; }
    }

    private sealed class SimulatedDataChannel : DataChannelHandle
    {
        private long _buffered;
        private volatile bool _closed;

        private readonly Action<string>? _onText;
        private readonly Action<byte[]>? _onBinary;

        public SimulatedDataChannel(Action<string>? onText = null, Action<byte[]>? onBinary = null)
        {
            _onText = onText;
            _onBinary = onBinary;
        }

        public void SendBinary(byte[] data)
        {
            if (_closed) throw new WebRtcException("Channel closed", WebRtcErrorCode.DatachannelOpenFailed);
            Interlocked.Add(ref _buffered, data.Length);
            _onBinary?.Invoke(data);
            Interlocked.Add(ref _buffered, -data.Length);
        }

        public void SendText(string text)
        {
            if (_closed) throw new WebRtcException("Channel closed", WebRtcErrorCode.DatachannelOpenFailed);
            _onText?.Invoke(text);
        }

        public long BufferedAmount() => Interlocked.Read(ref _buffered);

        public void Close()
        {
            _closed = true;
            Interlocked.Exchange(ref _buffered, 0);
        }

        public bool IsOpen => !_closed;
    }

    // ═══════════════════════════════════════════════════════════════════
    // IWebRtcTransferTransport Implementation
    // ═══════════════════════════════════════════════════════════════════

    public async Task<WebRtcSessionInfo> CreateSessionAsync(
        string sessionId,
        string localDeviceName,
        string localPlatform,
        CancellationToken cancellationToken = default)
    {
        CheckNotClosed();

        // Generate ephemeral ECDSA P-256 key pair
        using var keyPair = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var publicKeyDer = keyPair.ExportSubjectPublicKeyInfo();
        var privateKeyDer = keyPair.ExportECPrivateKey();

        // Create self-signed X.509 certificate
        var certificate = GenerateSelfSignedCertificate(publicKeyDer, privateKeyDer, sessionId);

        // Compute SHA-256 DTLS fingerprint
        var fingerprint = ComputeFingerprint(certificate);

        // Start signaling server
        StartSignalingServer();

        // Build SDP offer
        var sdpOffer = BuildSdpOffer(fingerprint);

        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var expiresAt = now + (_signalingTtlSeconds * 1000L);
        var port = ((IPEndPoint)_signalingListener!.LocalEndpoint!).Port;
        var signalingUrl = $"ws://{_localAddress}:{port}/signal";

        _sessionState = new SessionState(
            sessionId,
            localDeviceName,
            localPlatform,
            publicKeyDer,
            privateKeyDer,
            certificate,
            fingerprint,
            sdpOffer,
            signalingUrl,
            expiresAt);

        EmitPeerState(WebRtcPeerState.New);

        return new WebRtcSessionInfo(
            sessionId,
            sdpOffer,
            fingerprint,
            signalingUrl,
            expiresAt);
    }

    public async Task HandleSdpAnswerAsync(string sdpAnswer, CancellationToken cancellationToken = default)
    {
        CheckNotClosed();
        RequireSession();

        EmitPeerState(WebRtcPeerState.Connecting);

        // In production: PeerConnection.SetRemoteDescription()

        EmitPeerState(WebRtcPeerState.Connected);

        // Open data channel
        _activeDataChannel = new SimulatedDataChannel(
            onText: text => EmitDataMessage(new DataChannelMessage.Text(text)),
            onBinary: data => EmitDataMessage(new DataChannelMessage.Binary(data)));

        // Emit ICE candidates
        _ = Task.Run(async () =>
        {
            try { await EmitLocalIceCandidatesAsync(cancellationToken); }
            catch { /* ignore */ }
        }, cancellationToken);
    }

    public Task AddIceCandidateAsync(string candidate, string sdpMid, int sdpMLineIndex, CancellationToken cancellationToken = default)
    {
        CheckNotClosed();
        // In production: PeerConnection.AddIceCandidate()
        return Task.CompletedTask;
    }

    public async IAsyncEnumerable<WebRtcPeerState> ObservePeerStateAsync(
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        while (!cancellationToken.IsCancellationRequested && !_isClosed)
        {
            await _peerStateSemaphore.WaitAsync(cancellationToken);
            if (_peerStateBuffer.TryTake(out var state))
            {
                yield return state;
            }
        }
    }

    public async IAsyncEnumerable<IceCandidateInfo> ObserveLocalIceCandidatesAsync(
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        while (!cancellationToken.IsCancellationRequested && !_isClosed)
        {
            await _iceCandidatesSemaphore.WaitAsync(cancellationToken);
            if (_iceCandidatesBuffer.TryTake(out var candidate))
            {
                yield return candidate;
            }
        }
    }

    public async IAsyncEnumerable<DataChannelMessage> ObserveDataChannelMessagesAsync(
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        while (!cancellationToken.IsCancellationRequested && !_isClosed)
        {
            await _dataMessagesSemaphore.WaitAsync(cancellationToken);
            if (_dataChannelMessagesBuffer.TryTake(out var message))
            {
                yield return message;
            }
        }
    }

    public async Task SendControlMessageAsync(string text, CancellationToken cancellationToken = default)
    {
        CheckNotClosed();
        var channel = RequireOpenChannel();
        var frame = ProtocolFrame.EncodeControlJson(text);
        channel.SendBinary(frame);
        await Task.CompletedTask;
    }

    public async IAsyncEnumerable<BulkProgress> SendBinaryDataAsync(
        byte[] data,
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        CheckNotClosed();
        var channel = RequireOpenChannel();
        var bulkRef = new BulkRef(Guid.NewGuid().ToString("D").ToLowerInvariant());
        var totalBytes = (long)data.Length;
        var offset = 0L;

        while (offset < data.Length)
        {
            while (channel.BufferedAmount() > HighWaterMark)
            {
                await Task.Delay(10, cancellationToken);
            }

            var end = (int)Math.Min(offset + ChunkSize, data.Length);
            var chunkSize = end - (int)offset;
            var chunk = new byte[chunkSize];
            Array.Copy(data, offset, chunk, 0, chunkSize);

            var frame = ProtocolFrame.EncodeDataBinary(bulkRef.Value, chunk);
            channel.SendBinary(frame);

            offset = end;
            yield return new BulkProgress(bulkRef.Value, offset, offset >= totalBytes);
        }

        yield return new BulkProgress(bulkRef.Value, totalBytes, true);
    }

    public async IAsyncEnumerable<BulkProgress> SendStreamAsync(
        Stream stream,
        long totalBytes,
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        CheckNotClosed();
        var channel = RequireOpenChannel();
        var bulkRef = new BulkRef(Guid.NewGuid().ToString("D").ToLowerInvariant());
        var bytesSent = 0L;
        var buffer = new byte[ChunkSize];

        while (true)
        {
            while (channel.BufferedAmount() > HighWaterMark)
            {
                await Task.Delay(10, cancellationToken);
            }

            var bytesRead = await stream.ReadAsync(buffer.AsMemory(0, buffer.Length), cancellationToken);
            if (bytesRead <= 0) break;

            var chunk = new byte[bytesRead];
            Array.Copy(buffer, chunk, bytesRead);
            var frame = ProtocolFrame.EncodeDataBinary(bulkRef.Value, chunk);
            channel.SendBinary(frame);

            bytesSent += bytesRead;
            yield return new BulkProgress(bulkRef.Value, bytesSent, bytesSent >= totalBytes);
        }

        yield return new BulkProgress(bulkRef.Value, totalBytes, true);
    }

    public void RegisterExpectedStream(string itemId, long expectedBytes)
    {
        _expectedStreams[itemId] = expectedBytes;
    }

    public async IAsyncEnumerable<IncomingWebRtcStream> ObserveIncomingStreamsAsync(
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        while (!cancellationToken.IsCancellationRequested && !_isClosed)
        {
            await _incomingStreamsSemaphore.WaitAsync(cancellationToken);
            if (_incomingStreamsBuffer.TryTake(out var stream))
            {
                yield return stream;
            }
        }
    }

    public async ValueTask DisposeAsync()
    {
        if (_isClosed) return;
        _isClosed = true;

        _activeDataChannel?.Close();
        _activeDataChannel = null;

        try
        {
            _signalingListener?.Stop();
        }
        catch { }

        _cts.Cancel();
        _cts.Dispose();

        // Release all waiting semaphores (ignore if already at max count)
        try { _peerStateSemaphore.Release(int.MaxValue); } catch { }
        try { _iceCandidatesSemaphore.Release(int.MaxValue); } catch { }
        try { _dataMessagesSemaphore.Release(int.MaxValue); } catch { }
        try { _incomingStreamsSemaphore.Release(int.MaxValue); } catch { }

        _peerStateSemaphore.Dispose();
        _iceCandidatesSemaphore.Dispose();
        _dataMessagesSemaphore.Dispose();
        _incomingStreamsSemaphore.Dispose();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Internal Helpers
    // ═══════════════════════════════════════════════════════════════════

    private void CheckNotClosed()
    {
        if (_isClosed)
        {
            throw new WebRtcException("Transport has been closed.", WebRtcErrorCode.SessionNotFound);
        }
    }

    private SessionState RequireSession()
    {
        return _sessionState ?? throw new WebRtcException(
            "No active session. Call CreateSessionAsync() first.",
            WebRtcErrorCode.SessionNotFound);
    }

    private DataChannelHandle RequireOpenChannel()
    {
        var channel = _activeDataChannel;
        if (channel == null || !channel.IsOpen)
        {
            throw new WebRtcException("DataChannel is not open.", WebRtcErrorCode.DatachannelOpenFailed);
        }
        return channel;
    }

    private X509Certificate2 GenerateSelfSignedCertificate(byte[] publicKeyDer, byte[] privateKeyDer, string sessionId)
    {
        using var keyPair = ECDsa.Create();
        keyPair.ImportECPrivateKey(privateKeyDer, out _);

        var now = DateTimeOffset.UtcNow;
        var notBefore = now.DateTime;
        var notAfter = now.AddHours(24).DateTime;

        var distinguishedName = new X500DistinguishedName($"CN=glide-webrtc-{sessionId}, O=Glide, C=US");

        var request = new CertificateRequest(
            distinguishedName,
            keyPair,
            HashAlgorithmName.SHA256);

        request.CertificateExtensions.Add(
            new X509KeyUsageExtension(
                X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment | X509KeyUsageFlags.KeyAgreement,
                critical: true));

        request.CertificateExtensions.Add(
            new X509EnhancedKeyUsageExtension(
                new OidCollection { new Oid("1.3.6.1.5.5.7.3.1"), new Oid("1.3.6.1.5.5.7.3.2") },
                critical: false));

        var serialNumber = new byte[16];
        RandomNumberGenerator.Fill(serialNumber);

        return request.Create(
            distinguishedName,
            X509SignatureGenerator.CreateForECDsa(keyPair),
            notBefore,
            notAfter,
            serialNumber);
    }

    private static string ComputeFingerprint(X509Certificate2 certificate)
    {
        var rawData = certificate.RawData;
        var hash = SHA256.HashData(rawData);
        return Convert.ToHexString(hash).ToUpperInvariant();
    }

    private static string BuildSdpOffer(string dtlsFingerprint)
    {
        var ufrag = Math.Abs(Random.Shared.Next()).ToString();
        if (ufrag.Length > 8) ufrag = ufrag.AsSpan(0, 8).ToString();
        var pwd = Guid.NewGuid().ToString("N");

        return $"""
            v=0
            o=- 123456 1 IN IP4 0.0.0.0
            s=-
            t=0 0
            a=group:BUNDLE data
            m=application 9 UDP/DTLS/SCTP webrtc-datachannel
            c=IN IP4 0.0.0.0
            a=ice-ufrag:{ufrag}
            a=ice-pwd:{pwd}
            a=fingerprint:sha-256 {dtlsFingerprint}
            a=setup:actpass
            a=sctpmap:5000 webrtc-datachannel 1024
            """;
    }

    private void StartSignalingServer()
    {
        var address = IPAddress.Parse(_localAddress);
        var listener = new TcpListener(address, _signalingPort);
        listener.Start();
        _signalingListener = listener;
    }

    private async Task EmitLocalIceCandidatesAsync(CancellationToken cancellationToken)
    {
        var port = ((IPEndPoint)_signalingListener!.LocalEndpoint!).Port;
        var candidate = $"candidate:1 1 UDP 2130706431 {_localAddress} {port} typ host";
        EmitIceCandidate(new IceCandidateInfo(candidate, "0", 0));
        await Task.CompletedTask;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Emission Helpers
    // ═══════════════════════════════════════════════════════════════════

    private void EmitPeerState(WebRtcPeerState state)
    {
        if (_isClosed) return;
        _peerStateBuffer.Add(state);
        _peerStateSemaphore.Release(1);
    }

    private void EmitIceCandidate(IceCandidateInfo candidate)
    {
        if (_isClosed) return;
        _iceCandidatesBuffer.Add(candidate);
        _iceCandidatesSemaphore.Release(1);
    }

    private void EmitDataMessage(DataChannelMessage message)
    {
        if (_isClosed) return;
        _dataChannelMessagesBuffer.Add(message);
        _dataMessagesSemaphore.Release(1);
    }

    private void EmitIncomingStream(IncomingWebRtcStream stream)
    {
        if (_isClosed) return;
        _incomingStreamsBuffer.Add(stream);
        _incomingStreamsSemaphore.Release(1);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Protocol Frame Encoding
    // ═══════════════════════════════════════════════════════════════════

    internal static class ProtocolFrame
    {
        public const byte ControlJson = 0x01;
        public const byte DataBinary = 0x02;
        public const byte ChecksumSha256 = 0x03;
        public const byte FlowControl = 0x04;
        public const byte Error = 0x05;
        public const byte Keepalive = 0x06;

        public static byte[] EncodeControlJson(string text)
        {
            var payload = Encoding.UTF8.GetBytes(text);
            return Frame(ControlJson, payload);
        }

        public static byte[] EncodeDataBinary(string bulkRef, byte[] data)
        {
            var bulkRefBytes = Encoding.UTF8.GetBytes(bulkRef);
            var payload = new byte[1 + bulkRefBytes.Length + data.Length];
            payload[0] = (byte)bulkRefBytes.Length;
            Array.Copy(bulkRefBytes, 0, payload, 1, bulkRefBytes.Length);
            Array.Copy(data, 0, payload, 1 + bulkRefBytes.Length, data.Length);
            return Frame(DataBinary, payload);
        }

        public static byte[] EncodeChecksum(string sha256Hex)
        {
            return Frame(ChecksumSha256, Encoding.UTF8.GetBytes(sha256Hex));
        }

        public static byte[] EncodeError(string code, string message)
        {
            var json = $"{{\"code\":\"{code}\",\"message\":\"{message}\"}}";
            return Frame(Error, Encoding.UTF8.GetBytes(json));
        }

        private static byte[] Frame(byte type, byte[] payload)
        {
            var result = new byte[1 + 4 + payload.Length];
            result[0] = type;
            result[1] = (byte)(payload.Length >> 24);
            result[2] = (byte)(payload.Length >> 16);
            result[3] = (byte)(payload.Length >> 8);
            result[4] = (byte)(payload.Length);
            Array.Copy(payload, 0, result, 5, payload.Length);
            return result;
        }

        public static (byte type, byte[] payload)? Decode(byte[] bytes)
        {
            if (bytes.Length < 5) return null;
            var type = bytes[0];
            var length = (bytes[1] << 24) | (bytes[2] << 16) | (bytes[3] << 8) | bytes[4];
            if (bytes.Length < 5 + length) return null;
            var payload = new byte[length];
            Array.Copy(bytes, 5, payload, 0, length);
            return (type, payload);
        }
    }
}

/// <summary>
/// Factory for creating DotNetWebRtcTransferTransport instances.
/// </summary>
public static class DotNetWebRtcTransportFactory
{
    public static IWebRtcTransferTransport Create(
        int signalingPort = 0,
        string localAddress = "127.0.0.1",
        int signalingTtlSeconds = 120)
    {
        return new DotNetWebRtcTransferTransport(signalingPort, localAddress, signalingTtlSeconds);
    }
}
