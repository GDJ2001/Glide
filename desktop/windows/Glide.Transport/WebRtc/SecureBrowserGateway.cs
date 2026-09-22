using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Glide.Core.Models;
using Glide.Core.Transport;

namespace Glide.Transport.WebRtc;

/// <summary>
/// Secure Browser Gateway for .NET (HARD-027/HARD-028).
///
/// Replaces the insecure HTTP-based GlideWebGateway with a DTLS-encrypted
/// WebRTC DataChannel transport.
/// </summary>
public sealed class SecureBrowserGateway : IAsyncDisposable
{
    private const int HeaderReadTimeoutMs = 15_000;
    private const int MaxRequestSize = 65_536;

    private readonly IWebRtcTransferTransport _transport;
    private readonly string _host;
    private readonly int _port;
    private readonly string _deviceName;
    private readonly string _platform;
    private readonly int _ttlSeconds;
    private readonly CancellationTokenSource _cts = new();

    private TcpListener? _httpListener;
    private volatile bool _isStarted;
    private WebRtcSessionInfo? _sessionInfo;
    private readonly List<Task> _clientTasks = new();

    public SecureBrowserGateway(
        IWebRtcTransferTransport transport,
        string host = "127.0.0.1",
        int port = 0,
        string deviceName = "Glide Device",
        string platform = "windows",
        int ttlSeconds = 120)
    {
        _transport = transport ?? throw new ArgumentNullException(nameof(transport));
        _host = string.IsNullOrWhiteSpace(host) ? "127.0.0.1" : host;
        _port = port;
        _deviceName = deviceName;
        _platform = platform;
        _ttlSeconds = ttlSeconds;
    }

    public int BoundPort => ((IPEndPoint?)(_httpListener?.LocalEndpoint))?.Port ?? 0;
    public string? QrPayload => _sessionInfo is not null ? BuildQrPayload(_sessionInfo) : null;
    public string? SignalingUrl => _sessionInfo?.SignalingUrl;
    public string? CurrentSessionId => _sessionInfo?.SessionId;

    /// <summary>
    /// Start the gateway server and create a WebRTC session.
    /// </summary>
    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        if (_isStarted) return;

        // Create WebRTC session
        var sessionId = Guid.NewGuid().ToString("D");
        _sessionInfo = await _transport.CreateSessionAsync(
            sessionId, _deviceName, _platform, cancellationToken);

        // Start HTTP server for SPA bootstrap
        StartHttpServer();

        // Observe transport events
        _ = Task.Run(() => ObserveTransportEventsAsync(cancellationToken));

        _isStarted = true;
    }

    /// <summary>
    /// Stop the gateway server.
    /// </summary>
    public Task StopAsync(CancellationToken cancellationToken = default)
    {
        try { _httpListener?.Stop(); } catch { }
        _isStarted = false;
        return Task.CompletedTask;
    }

    /// <summary>
    /// Close the gateway and release all resources.
    /// </summary>
    public async ValueTask DisposeAsync()
    {
        await StopAsync();
        _cts.Cancel();
        await _transport.DisposeAsync();
    }

    // ═══════════════════════════════════════════════════════════════════
    // HTTP Server for SPA Bootstrap
    // ═══════════════════════════════════════════════════════════════════

    private void StartHttpServer()
    {
        var address = IPAddress.Parse(_host);
        var listener = new TcpListener(address, _port);
        listener.Start();
        _httpListener = listener;

        _ = Task.Run(async () =>
        {
            while (!_cts.Token.IsCancellationRequested)
            {
                try
                {
                    var client = await _httpListener.AcceptTcpClientAsync(_cts.Token);
                    var task = Task.Run(async () => await HandleHttpClientAsync(client, _cts.Token));
                    lock (_clientTasks) { _clientTasks.Add(task); }
                }
                catch (OperationCanceledException) { break; }
                catch { break; }
            }
        });
    }

    private async Task HandleHttpClientAsync(TcpClient client, CancellationToken cancellationToken)
    {
        try
        {
            using (client)
            {
                await using var stream = client.GetStream();
                stream.ReadTimeout = HeaderReadTimeoutMs;

                // Read request line
                var requestLine = await ReadLineAsync(stream, cancellationToken);
                if (requestLine == null) return;

                var parts = requestLine.Split(' ');
                if (parts.Length < 2) return;

                var method = parts[0].ToUpperInvariant();
                var rawUri = parts[1];

                // Read headers
                var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                while (true)
                {
                    var headerLine = await ReadLineAsync(stream, cancellationToken);
                    if (headerLine == null || headerLine.Length == 0) break;
                    var colonIdx = headerLine.IndexOf(':');
                    if (colonIdx > 0)
                    {
                        var key = headerLine[..colonIdx].Trim().ToLowerInvariant();
                        var value = headerLine[(colonIdx + 1)..].Trim();
                        headers[key] = value;
                    }
                }

                // Parse URI
                var uriParts = rawUri.Split('?', 2);
                var path = uriParts[0];

                var isHead = method == "HEAD";
                var isGetOrHead = method == "GET" || isHead;

                if (isGetOrHead && (path == "/" || path == "/index.html"))
                {
                    SendResponse(stream, 200, "OK",
                        "text/html; charset=utf-8",
                        GlideWebRtcAssets.GetIndexHtml(), isHead);
                }
                else if (isGetOrHead && path == "/style.css")
                {
                    SendResponse(stream, 200, "OK",
                        "text/css; charset=utf-8",
                        GlideWebRtcAssets.GetStyleCss(), isHead);
                }
                else if (isGetOrHead && path == "/app.js")
                {
                    SendResponse(stream, 200, "OK",
                        "application/javascript; charset=utf-8",
                        GlideWebRtcAssets.GetAppJs(), isHead);
                }
                else if (isGetOrHead && path == "/api/v1/qr")
                {
                    var payload = QrPayload ?? "";
                    var signalingUrl = SignalingUrl ?? "";
                    var currentSessionId = CurrentSessionId ?? "";
                    var json = JsonSerializer.Serialize(new
                    {
                        qr = payload,
                        signalingUrl,
                        sessionId = currentSessionId
                    });
                    SendResponse(stream, 200, "OK",
                        "application/json; charset=utf-8",
                        Encoding.UTF8.GetBytes(json), isHead);
                }
                else
                {
                    SendResponse(stream, 404, "Not Found",
                        "text/plain",
                        Encoding.UTF8.GetBytes("404 Not Found\r\n"), isHead);
                }

                stream.Flush();
            }
        }
        catch { /* Connection error */ }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Transport Event Observation
    // ═══════════════════════════════════════════════════════════════════

    private async Task ObserveTransportEventsAsync(CancellationToken cancellationToken)
    {
        try
        {
            await foreach (var state in _transport.ObservePeerStateAsync(cancellationToken))
            {
                // Handle peer state changes
            }
        }
        catch (OperationCanceledException) { }
        catch { }

        try
        {
            await foreach (var candidate in _transport.ObserveLocalIceCandidatesAsync(cancellationToken))
            {
                // ICE candidates forwarded via signaling WebSocket
            }
        }
        catch (OperationCanceledException) { }
        catch { }

        try
        {
            await foreach (var message in _transport.ObserveDataChannelMessagesAsync(cancellationToken))
            {
                HandleDataChannelMessage(message);
            }
        }
        catch (OperationCanceledException) { }
        catch { }
    }

    private void HandleDataChannelMessage(DataChannelMessage message)
    {
        switch (message)
        {
            case DataChannelMessage.Text text:
                HandleControlMessage(text.Content);
                break;
            case DataChannelMessage.Binary binary:
                // Handle binary frame
                break;
        }
    }

    private void HandleControlMessage(string text)
    {
        try
        {
            using var json = JsonDocument.Parse(text);
            var root = json.RootElement;
            if (root.TryGetProperty("type", out var typeElement))
            {
                var type = typeElement.GetString();
                // Dispatch: join_session, sdp_offer, ice_candidate, etc.
            }
        }
        catch { /* Invalid JSON */ }
    }

    // ═══════════════════════════════════════════════════════════════════
    // QR Code Payload
    // ═══════════════════════════════════════════════════════════════════

    private string BuildQrPayload(WebRtcSessionInfo info)
    {
        var expSeconds = info.ExpiresAtEpochMs / 1000;
        return JsonSerializer.Serialize(new
        {
            v = 1,
            sid = info.SessionId,
            fp = $"sha-256:{info.DtlsFingerprintSha256}",
            sig = info.SignalingUrl,
            dn = _deviceName,
            dp = _platform,
            exp = expSeconds
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    // HTTP Utilities
    // ═══════════════════════════════════════════════════════════════════

    private static async Task<string?> ReadLineAsync(NetworkStream stream, CancellationToken cancellationToken)
    {
        var buffer = new List<byte>();
        var prevByte = -1;

        while (true)
        {
            var readBuffer = new byte[1];
            var bytesRead = await stream.ReadAsync(readBuffer, cancellationToken);
            if (bytesRead == 0) return buffer.Count > 0 ? Encoding.UTF8.GetString(buffer.ToArray()) : null;

            var currentByte = readBuffer[0];

            if (prevByte == '\r' && currentByte == '\n')
            {
                return Encoding.UTF8.GetString(buffer.ToArray(), 0, Math.Max(0, buffer.Count - 1));
            }

            buffer.Add(currentByte);
            prevByte = currentByte;

            if (buffer.Count > MaxRequestSize) return null;
        }
    }

    private static void SendResponse(
        NetworkStream output,
        int statusCode,
        string statusText,
        string contentType,
        byte[] body,
        bool isHead = false)
    {
        var headerText = new StringBuilder();
        headerText.AppendLine($"HTTP/1.1 {statusCode} {statusText}");
        headerText.AppendLine($"Content-Type: {contentType}");
        headerText.AppendLine($"Content-Length: {body.Length}");
        headerText.AppendLine("X-Content-Type-Options: nosniff");
        headerText.AppendLine("X-Frame-Options: DENY");
        headerText.AppendLine("Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self' ws: wss:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
        headerText.AppendLine("Referrer-Policy: no-referrer");
        headerText.AppendLine("Strict-Transport-Security: max-age=31536000; includeSubDomains");
        headerText.AppendLine("Connection: close");
        headerText.AppendLine();

        var headerBytes = Encoding.UTF8.GetBytes(headerText.ToString());
        output.Write(headerBytes);
        if (!isHead) output.Write(body);
        output.Flush();
    }
}
