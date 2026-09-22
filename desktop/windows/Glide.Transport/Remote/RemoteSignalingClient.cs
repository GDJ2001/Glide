using System.Net.WebSockets;
using System.Text;

namespace Glide.Transport.Remote;

/// <summary>
/// Thrown when signaling broker returns an error response or connectivity fails.
/// </summary>
public sealed class SignalingException : Exception
{
    public string? ErrorCode { get; }

    public SignalingException(string message, string? errorCode = null, Exception? inner = null)
        : base(message, inner)
    {
        ErrorCode = errorCode;
    }
}

/// <summary>
/// Zero-dependency WebSocket client for negotiating ephemeral peer rendezvous with the signaling broker.
/// Fully zero-knowledge: payloads passed to the broker are encrypted ciphertext.
/// </summary>
public sealed class RemoteSignalingClient : IAsyncDisposable
{
    private readonly ClientWebSocket _webSocket;
    private readonly ArraySegment<byte> _buffer = new(new byte[8192]);

    public bool IsConnected => _webSocket.State == WebSocketState.Open;

    public RemoteSignalingClient(ClientWebSocket? webSocket = null)
    {
        _webSocket = webSocket ?? new ClientWebSocket();
    }

    /// <summary>
    /// Connects to the remote signaling broker endpoint.
    /// </summary>
    public async Task ConnectAsync(Uri signalingUri, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(signalingUri);
        if (_webSocket.State != WebSocketState.Open)
        {
            await _webSocket.ConnectAsync(signalingUri, cancellationToken);
        }
    }

    /// <summary>
    /// Registers host on the broker for an ephemeral rendezvousId and awaits a peer's arrival.
    /// </summary>
    public async Task<PeerJoinedEvent> ListenAsync(string rendezvousId, CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(rendezvousId);

        var request = new ListenRequest(rendezvousId);
        await SendMessageAsync(request, cancellationToken);

        // Read messages until PeerJoinedEvent is received
        while (_webSocket.State == WebSocketState.Open && !cancellationToken.IsCancellationRequested)
        {
            var msg = await ReceiveMessageAsync(cancellationToken);
            if (msg is ListeningEvent)
            {
                continue; // Host successfully registered, wait for peer
            }
            if (msg is PeerJoinedEvent peerJoined)
            {
                return peerJoined;
            }
            if (msg is SignalingError error)
            {
                throw new SignalingException($"Signaling error: {error.ErrorCode} - {error.Message}", error.ErrorCode);
            }
        }

        throw new SignalingException("Signaling connection closed before peer joined.");
    }

    /// <summary>
    /// Connects as a peer to an existing rendezvousId, submitting client public key and encrypted candidate payload.
    /// </summary>
    public async Task RendezvousAsync(
        string rendezvousId,
        string clientPublicKeyHex,
        string encryptedPayload,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(rendezvousId);
        ArgumentException.ThrowIfNullOrWhiteSpace(clientPublicKeyHex);
        ArgumentException.ThrowIfNullOrWhiteSpace(encryptedPayload);

        var request = new RendezvousRequest(rendezvousId, clientPublicKeyHex, encryptedPayload);
        await SendMessageAsync(request, cancellationToken);
    }

    /// <summary>
    /// Sends a signaling message envelope over the WebSocket.
    /// </summary>
    public async Task SendMessageAsync(SignalingMessage message, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(message);
        string json = message.ToJsonString();
        byte[] bytes = Encoding.UTF8.GetBytes(json);
        await _webSocket.SendAsync(
            new ArraySegment<byte>(bytes),
            WebSocketMessageType.Text,
            endOfMessage: true,
            cancellationToken);
    }

    /// <summary>
    /// Receives and deserializes the next signaling message envelope.
    /// </summary>
    public async Task<SignalingMessage> ReceiveMessageAsync(CancellationToken cancellationToken = default)
    {
        using var ms = new MemoryStream();
        WebSocketReceiveResult result;

        do
        {
            result = await _webSocket.ReceiveAsync(_buffer, cancellationToken);
            if (result.MessageType == WebSocketMessageType.Close)
            {
                await _webSocket.CloseAsync(WebSocketCloseStatus.NormalClosure, "Closing", cancellationToken);
                throw new SignalingException("Signaling broker closed connection.");
            }
            if (_buffer.Array != null)
            {
                ms.Write(_buffer.Array, _buffer.Offset, result.Count);
            }
        } while (!result.EndOfMessage);

        string json = Encoding.UTF8.GetString(ms.ToArray());
        return SignalingMessageCodec.Parse(json);
    }

    public async ValueTask DisposeAsync()
    {
        if (_webSocket.State == WebSocketState.Open)
        {
            try
            {
                await _webSocket.CloseAsync(WebSocketCloseStatus.NormalClosure, "Disposing", CancellationToken.None);
            }
            catch
            {
                // Best-effort cleanup
            }
        }
        _webSocket.Dispose();
    }
}
