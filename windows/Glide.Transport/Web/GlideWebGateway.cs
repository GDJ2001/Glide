using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Glide.Core.Models;
using Glide.Core.Storage;

namespace Glide.Transport.Web;

public sealed class OfferedWebItem
{
    public string ItemId { get; }
    public string FilePath { get; }
    public string FileName { get; }
    public long FileSize { get; }
    public string MimeType { get; }
    public string Sha256 { get; }

    public OfferedWebItem(string filePath, string? fileName = null, string? mimeType = null, string? itemId = null, string? sha256 = null)
    {
        FilePath = filePath ?? throw new ArgumentNullException(nameof(filePath));
        var fileInfo = new FileInfo(filePath);
        if (!fileInfo.Exists)
        {
            throw new FileNotFoundException($"Offered file not found: {filePath}", filePath);
        }

        FileSize = fileInfo.Length;
        FileName = fileName ?? fileInfo.Name;
        MimeType = mimeType ?? "application/octet-stream";
        ItemId = itemId ?? Guid.NewGuid().ToString("D");
        Sha256 = sha256 ?? ComputeFileSha256(filePath);
    }

    private static string ComputeFileSha256(string path)
    {
        using var stream = File.OpenRead(path);
        using var sha256 = SHA256.Create();
        byte[] hash = sha256.ComputeHash(stream);
        return Convert.ToHexStringLower(hash);
    }
}

public sealed class GlideWebGateway : IAsyncDisposable
{
    private readonly HttpListener _listener;
    private readonly string _host;
    private readonly int _requestedPort;
    private readonly string _tokenHex;
    private readonly DateTimeOffset _tokenCreatedAt;
    private readonly double _tokenTtlSeconds;
    private readonly string _sessionCookie;
    private readonly List<OfferedWebItem> _offeredItems;
    private readonly QuarantineStagingStore _quarantineStore;
    private readonly SessionId _sessionId;
    private readonly string _deviceName;
    private readonly string _platform;
    private readonly CancellationTokenSource _cts = new();

    private Task? _listenerLoopTask;
    private bool _tokenConsumed;
    private int _actualPort;

    public int Port => _actualPort;
    public string TokenHex => _tokenHex;
    public string OnboardingUrl => $"http://{_host}:{_actualPort}/?token={_tokenHex}";
    public bool IsListening => _listener.IsListening;
    public bool IsTokenConsumed => _tokenConsumed;

    public GlideWebGateway(
        string host = "127.0.0.1",
        int port = 0,
        IEnumerable<OfferedWebItem>? offeredItems = null,
        QuarantineStagingStore? quarantineStore = null,
        SessionId? sessionId = null,
        string? deviceName = null,
        string? platform = "windows",
        double tokenTtlSeconds = 120.0)
    {
        _host = string.IsNullOrWhiteSpace(host) ? "127.0.0.1" : host;
        _requestedPort = port;
        _offeredItems = offeredItems != null ? new List<OfferedWebItem>(offeredItems) : [];
        _quarantineStore = quarantineStore ?? new QuarantineStagingStore();
        _sessionId = sessionId ?? SessionId.NewRandom();
        _deviceName = deviceName ?? Environment.MachineName;
        _platform = platform ?? "windows";
        _tokenTtlSeconds = tokenTtlSeconds;

        byte[] tokenBytes = RandomNumberGenerator.GetBytes(32);
        _tokenHex = Convert.ToHexStringLower(tokenBytes);
        _tokenCreatedAt = DateTimeOffset.UtcNow;
        _sessionCookie = Guid.NewGuid().ToString("N");

        _listener = new HttpListener();
    }

    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        int bindPort = _requestedPort;
        if (bindPort == 0)
        {
            // Find an available port via TcpListener
            using var tcp = new System.Net.Sockets.TcpListener(IPAddress.Parse(_host), 0);
            tcp.Start();
            bindPort = ((IPEndPoint)tcp.LocalEndpoint).Port;
            tcp.Stop();
        }

        _actualPort = bindPort;
        string prefix = $"http://{_host}:{_actualPort}/";
        _listener.Prefixes.Clear();
        _listener.Prefixes.Add(prefix);
        _listener.Start();

        _listenerLoopTask = Task.Run(ProcessRequestsLoopAsync);
        await Task.Yield();
    }

    private async Task ProcessRequestsLoopAsync()
    {
        while (!_cts.Token.IsCancellationRequested && _listener.IsListening)
        {
            try
            {
                var context = await _listener.GetContextAsync();
                _ = Task.Run(() => HandleRequestAsync(context), _cts.Token);
            }
            catch (HttpListenerException) when (_cts.IsCancellationRequested || !_listener.IsListening)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch
            {
                if (_cts.IsCancellationRequested) break;
            }
        }
    }

    private async Task HandleRequestAsync(HttpListenerContext context)
    {
        var request = context.Request;
        var response = context.Response;

        // Apply global security headers per ADR-0019 / Web Specification
        response.Headers.Set("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self' ws:; frame-ancestors 'none';");
        response.Headers.Set("X-Content-Type-Options", "nosniff");
        response.Headers.Set("X-Frame-Options", "DENY");
        response.Headers.Set("Cache-Control", "no-store, no-cache, must-revalidate");

        try
        {
            string path = request.Url?.AbsolutePath ?? "/";

            // Static asset delivery for CSS and JS does not require re-authentication if session or referer valid
            if (path.Equals("/style.css", StringComparison.OrdinalIgnoreCase))
            {
                await ServeEmbeddedAssetAsync(request, response, "style.css", "text/css; charset=utf-8");
                return;
            }

            if (path.Equals("/app.js", StringComparison.OrdinalIgnoreCase))
            {
                await ServeEmbeddedAssetAsync(request, response, "app.js", "application/javascript");
                return;
            }

            // Authenticate request: either query token, Authorization header, or session cookie
            bool isAuthenticated = AuthenticateRequest(context);

            if (!isAuthenticated)
            {
                response.StatusCode = (int)HttpStatusCode.Forbidden;
                byte[] forbiddenBytes = Encoding.UTF8.GetBytes("<!DOCTYPE html><html><body><h1>403 Forbidden</h1><p>Session Expired or Unauthorized.</p></body></html>");
                response.ContentType = "text/html; charset=utf-8";
                response.ContentLength64 = forbiddenBytes.Length;
                await response.OutputStream.WriteAsync(forbiddenBytes);
                response.OutputStream.Close();
                return;
            }

            // Route authenticated endpoints
            if (path == "/" || path.Equals("/index.html", StringComparison.OrdinalIgnoreCase))
            {
                await ServeEmbeddedAssetAsync(request, response, "index.html", "text/html; charset=utf-8");
                return;
            }

            if (path.Equals("/api/v1/manifest", StringComparison.OrdinalIgnoreCase))
            {
                await ServeManifestAsync(request, response);
                return;
            }

            if (path.StartsWith("/api/v1/download/", StringComparison.OrdinalIgnoreCase))
            {
                string itemId = path["/api/v1/download/".Length..].Trim();
                await ServeDownloadAsync(request, response, itemId);
                return;
            }

            if (path.Equals("/api/v1/upload/proposal", StringComparison.OrdinalIgnoreCase) && request.HttpMethod.Equals("POST", StringComparison.OrdinalIgnoreCase))
            {
                await HandleProposalAsync(request, response);
                return;
            }

            if (path.Equals("/api/v1/upload", StringComparison.OrdinalIgnoreCase) && request.HttpMethod.Equals("POST", StringComparison.OrdinalIgnoreCase))
            {
                await HandleUploadAsync(request, response);
                return;
            }

            // Not found
            response.StatusCode = (int)HttpStatusCode.NotFound;
            response.Close();
        }
        catch (Exception ex)
        {
            try
            {
                response.StatusCode = (int)HttpStatusCode.InternalServerError;
                byte[] errBytes = Encoding.UTF8.GetBytes($"500 Internal Error: {ex.Message}");
                response.ContentType = "text/plain";
                response.ContentLength64 = errBytes.Length;
                await response.OutputStream.WriteAsync(errBytes);
                response.OutputStream.Close();
            }
            catch
            {
                // Ignored if connection reset
            }
        }
    }

    private bool AuthenticateRequest(HttpListenerContext context)
    {
        var request = context.Request;

        // Check query string token: ?token=<hex> (Strictly One-Use)
        string? queryToken = request.QueryString["token"];
        if (!string.IsNullOrEmpty(queryToken))
        {
            if (string.Equals(queryToken, _tokenHex, StringComparison.OrdinalIgnoreCase))
            {
                lock (_listener)
                {
                    // If already consumed, reject token replay
                    if (!_tokenConsumed && (DateTimeOffset.UtcNow - _tokenCreatedAt).TotalSeconds <= _tokenTtlSeconds)
                    {
                        _tokenConsumed = true;
                        // Issue HttpOnly SameSite=Strict session cookie
                        context.Response.Headers.Set("Set-Cookie", $"glide_web_session={_sessionCookie}; Path=/; HttpOnly; SameSite=Strict");
                        return true;
                    }
                }
            }
            return false;
        }

        // Check session cookie
        var sessionCookie = request.Cookies["glide_web_session"];
        if (sessionCookie != null && string.Equals(sessionCookie.Value, _sessionCookie, StringComparison.Ordinal))
        {
            return true;
        }

        // Check Authorization header
        string? authHeader = request.Headers["Authorization"];
        if (!string.IsNullOrEmpty(authHeader) && authHeader.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase))
        {
            string token = authHeader["Bearer ".Length..].Trim();
            if (string.Equals(token, _tokenHex, StringComparison.OrdinalIgnoreCase) &&
                (DateTimeOffset.UtcNow - _tokenCreatedAt).TotalSeconds <= _tokenTtlSeconds)
            {
                return true;
            }
        }

        return false;
    }

    private static async Task ServeEmbeddedAssetAsync(HttpListenerRequest request, HttpListenerResponse response, string assetName, string contentType)
    {
        var assembly = Assembly.GetExecutingAssembly();
        string resourceName = $"Glide.Transport.Web.Assets.{assetName}";

        using var stream = assembly.GetManifestResourceStream(resourceName);
        if (stream == null)
        {
            response.StatusCode = (int)HttpStatusCode.NotFound;
            response.Close();
            return;
        }

        response.StatusCode = (int)HttpStatusCode.OK;
        response.ContentType = contentType;
        response.ContentLength64 = stream.Length;

        if (!request.HttpMethod.Equals("HEAD", StringComparison.OrdinalIgnoreCase))
        {
            await stream.CopyToAsync(response.OutputStream);
        }
        response.OutputStream.Close();
    }

    private async Task ServeManifestAsync(HttpListenerRequest request, HttpListenerResponse response)
    {
        var rootNode = new JsonObject
        {
            ["sessionId"] = _sessionId.Value,
            ["device"] = new JsonObject
            {
                ["name"] = _deviceName,
                ["platform"] = _platform
            },
            ["items"] = new JsonArray(
                _offeredItems.Select(item => new JsonObject
                {
                    ["itemId"] = item.ItemId,
                    ["fileName"] = item.FileName,
                    ["fileSize"] = item.FileSize,
                    ["mimeType"] = item.MimeType,
                    ["sha256"] = item.Sha256
                }).ToArray()
            )
        };

        byte[] jsonBytes = JsonSerializer.SerializeToUtf8Bytes(rootNode);
        response.StatusCode = (int)HttpStatusCode.OK;
        response.ContentType = "application/json; charset=utf-8";
        response.ContentLength64 = jsonBytes.Length;

        if (!request.HttpMethod.Equals("HEAD", StringComparison.OrdinalIgnoreCase))
        {
            await response.OutputStream.WriteAsync(jsonBytes);
        }
        response.OutputStream.Close();
    }

    private async Task ServeDownloadAsync(HttpListenerRequest request, HttpListenerResponse response, string itemId)
    {
        var item = _offeredItems.FirstOrDefault(i => string.Equals(i.ItemId, itemId, StringComparison.OrdinalIgnoreCase));
        if (item == null || !File.Exists(item.FilePath))
        {
            response.StatusCode = (int)HttpStatusCode.NotFound;
            response.Close();
            return;
        }

        string safeFileName = WindowsPathSanitizer.SanitizeRelativePath(item.FileName).Replace('/', '_');
        string safeAscii = new string(safeFileName.Select(c => c <= 127 && c != '"' && c != '\\' ? c : '_').ToArray());
        if (string.IsNullOrWhiteSpace(safeAscii)) safeAscii = "download.bin";
        string encodedUtf8 = Uri.EscapeDataString(safeFileName);

        response.Headers.Set("Accept-Ranges", "bytes");
        response.Headers.Set("Content-Disposition", $"attachment; filename=\"{safeAscii}\"; filename*=UTF-8''{encodedUtf8}");
        response.ContentType = string.IsNullOrWhiteSpace(item.MimeType) ? "application/octet-stream" : item.MimeType;

        long fileLength = item.FileSize;
        bool isHead = request.HttpMethod.Equals("HEAD", StringComparison.OrdinalIgnoreCase);
        string? rangeHeader = request.Headers["Range"];

        if (!string.IsNullOrEmpty(rangeHeader) && rangeHeader.StartsWith("bytes=", StringComparison.OrdinalIgnoreCase))
        {
            if (!TryParseRange(rangeHeader, fileLength, out long start, out long end))
            {
                response.StatusCode = 416; // Range Not Satisfiable
                response.Headers.Set("Content-Range", $"bytes */{fileLength}");
                response.ContentLength64 = 0;
                response.Close();
                return;
            }

            long rangeLength = end - start + 1;
            response.StatusCode = (int)HttpStatusCode.PartialContent; // 206
            response.Headers.Set("Content-Range", $"bytes {start}-{end}/{fileLength}");
            response.ContentLength64 = rangeLength;

            if (isHead)
            {
                response.Close();
                return;
            }

            using var fileStream = new FileStream(item.FilePath, FileMode.Open, FileAccess.Read, FileShare.Read, 65536, useAsync: true);
            if (start > 0)
            {
                fileStream.Seek(start, SeekOrigin.Begin);
            }

            long bytesRemaining = rangeLength;
            byte[] buffer = new byte[65536];
            while (bytesRemaining > 0)
            {
                int toRead = (int)Math.Min(buffer.Length, bytesRemaining);
                int read = await fileStream.ReadAsync(buffer.AsMemory(0, toRead));
                if (read <= 0) break;
                await response.OutputStream.WriteAsync(buffer.AsMemory(0, read));
                bytesRemaining -= read;
            }
            response.OutputStream.Close();
            return;
        }

        // Full content (200 OK)
        response.StatusCode = (int)HttpStatusCode.OK;
        response.ContentLength64 = fileLength;

        if (isHead)
        {
            response.Close();
            return;
        }

        using (var fileStream = new FileStream(item.FilePath, FileMode.Open, FileAccess.Read, FileShare.Read, 65536, useAsync: true))
        {
            await fileStream.CopyToAsync(response.OutputStream);
        }
        response.OutputStream.Close();
    }

    private static bool TryParseRange(string rangeHeader, long totalLength, out long start, out long end)
    {
        start = 0;
        end = totalLength - 1;

        if (totalLength <= 0 || !rangeHeader.StartsWith("bytes=", StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        string spec = rangeHeader["bytes=".Length..].Trim();
        int commaIdx = spec.IndexOf(',');
        if (commaIdx >= 0)
        {
            spec = spec[..commaIdx].Trim();
        }

        int dashIdx = spec.IndexOf('-');
        if (dashIdx < 0) return false;

        string startStr = spec[..dashIdx].Trim();
        string endStr = spec[(dashIdx + 1)..].Trim();

        if (string.IsNullOrEmpty(startStr) && string.IsNullOrEmpty(endStr))
        {
            return false;
        }

        if (string.IsNullOrEmpty(startStr))
        {
            // Suffix range: bytes=-500 (last 500 bytes)
            if (!long.TryParse(endStr, out long suffixLen) || suffixLen <= 0) return false;
            start = Math.Max(0, totalLength - suffixLen);
            end = totalLength - 1;
            return true;
        }

        if (!long.TryParse(startStr, out start) || start < 0 || start >= totalLength)
        {
            return false;
        }

        if (string.IsNullOrEmpty(endStr))
        {
            end = totalLength - 1;
        }
        else
        {
            if (!long.TryParse(endStr, out end) || end < start)
            {
                return false;
            }
            if (end >= totalLength)
            {
                end = totalLength - 1;
            }
        }

        return true;
    }

    private async Task HandleProposalAsync(HttpListenerRequest request, HttpListenerResponse response)
    {
        using var reader = new StreamReader(request.InputStream, Encoding.UTF8);
        string body = await reader.ReadToEndAsync();

        string? rawFileName = null;
        long fileSize = 0;
        string? declaredSha256 = null;

        try
        {
            var node = JsonNode.Parse(body);
            if (node != null)
            {
                rawFileName = node["fileName"]?.GetValue<string>();
                fileSize = node["fileSize"]?.GetValue<long>() ?? 0;
                declaredSha256 = node["sha256"]?.GetValue<string>();
            }
        }
        catch
        {
            response.StatusCode = (int)HttpStatusCode.BadRequest;
            response.Close();
            return;
        }

        string safeFileName = WindowsPathSanitizer.SanitizeWebFilename(rawFileName);

        // Preflight available storage check (required size + 50 MB reserve)
        long availableSpace = _quarantineStore.GetAvailableFreeSpaceBytes();
        long requiredSpace = fileSize + (50L * 1024 * 1024);

        if (availableSpace < requiredSpace)
        {
            response.StatusCode = 507; // Insufficient Storage
            var errObj = new JsonObject
            {
                ["status"] = "error",
                ["error"] = "insufficient_storage",
                ["message"] = "Insufficient disk space on destination device."
            };
            byte[] errBytes = JsonSerializer.SerializeToUtf8Bytes(errObj);
            response.ContentType = "application/json; charset=utf-8";
            response.ContentLength64 = errBytes.Length;
            await response.OutputStream.WriteAsync(errBytes);
            response.OutputStream.Close();
            return;
        }

        var acceptedObj = new JsonObject
        {
            ["status"] = "accepted",
            ["uploadId"] = Guid.NewGuid().ToString("D"),
            ["fileName"] = safeFileName,
            ["maxChunkSize"] = 65536
        };

        byte[] acceptedBytes = JsonSerializer.SerializeToUtf8Bytes(acceptedObj);
        response.StatusCode = (int)HttpStatusCode.OK;
        response.ContentType = "application/json; charset=utf-8";
        response.ContentLength64 = acceptedBytes.Length;
        await response.OutputStream.WriteAsync(acceptedBytes);
        response.OutputStream.Close();
    }

    private async Task HandleUploadAsync(HttpListenerRequest request, HttpListenerResponse response)
    {
        string rawFileName = request.Headers["X-File-Name"] ?? "uploaded_file.bin";
        string decodedFileName = Uri.UnescapeDataString(rawFileName);
        string sanitizedFileName = WindowsPathSanitizer.SanitizeWebFilename(decodedFileName);

        string? declaredSha256 = request.Headers["X-Declared-Sha256"];
        string? sizeHeader = request.Headers["X-File-Size"] ?? request.Headers["Content-Length"];
        long.TryParse(sizeHeader, out long declaredSize);

        // Preflight disk space verification
        if (declaredSize > 0)
        {
            long availableSpace = _quarantineStore.GetAvailableFreeSpaceBytes();
            if (availableSpace < declaredSize + (50L * 1024 * 1024))
            {
                response.StatusCode = 507; // Insufficient Storage
                var errObj = new JsonObject
                {
                    ["status"] = "error",
                    ["error"] = "insufficient_storage",
                    ["message"] = "Insufficient disk space on destination device."
                };
                byte[] errBytes = JsonSerializer.SerializeToUtf8Bytes(errObj);
                response.ContentType = "application/json; charset=utf-8";
                response.ContentLength64 = errBytes.Length;
                await response.OutputStream.WriteAsync(errBytes);
                response.OutputStream.Close();
                return;
            }
        }

        var itemId = ItemId.NewRandom();
        string quarantinePath = _quarantineStore.GetQuarantinePath(_sessionId, itemId);
        string? quarantineDir = Path.GetDirectoryName(quarantinePath);
        if (quarantineDir != null && !Directory.Exists(quarantineDir))
        {
            Directory.CreateDirectory(quarantineDir);
        }

        byte[] computedHash;
        long totalBytes = 0;

        using (var fileStream = new FileStream(quarantinePath, FileMode.Create, FileAccess.Write, FileShare.None, 65536, useAsync: true))
        using (var sha256 = IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
        {
            byte[] buffer = new byte[65536];
            int bytesRead;

            while ((bytesRead = await request.InputStream.ReadAsync(buffer, 0, buffer.Length)) > 0)
            {
                await fileStream.WriteAsync(buffer.AsMemory(0, bytesRead));
                sha256.AppendData(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }

            computedHash = sha256.GetHashAndReset();
        }

        string actualHashHex = Convert.ToHexStringLower(computedHash);

        // Check declared checksum if provided
        if (!string.IsNullOrWhiteSpace(declaredSha256) &&
            !string.Equals(declaredSha256.Trim(), actualHashHex, StringComparison.OrdinalIgnoreCase))
        {
            // Immediate purge of quarantine file on checksum mismatch
            _quarantineStore.PurgeQuarantine(_sessionId, itemId);

            response.StatusCode = 422; // Unprocessable Entity
            var mismatchObj = new JsonObject
            {
                ["status"] = "error",
                ["error"] = "checksum_mismatch",
                ["declared"] = declaredSha256.Trim(),
                ["actual"] = actualHashHex
            };
            byte[] mismatchBytes = JsonSerializer.SerializeToUtf8Bytes(mismatchObj);
            response.ContentType = "application/json; charset=utf-8";
            response.ContentLength64 = mismatchBytes.Length;
            await response.OutputStream.WriteAsync(mismatchBytes);
            response.OutputStream.Close();
            return;
        }

        // Commit quarantine file to Downloads
        string finalPath = _quarantineStore.CommitQuarantineFile(_sessionId, itemId, sanitizedFileName);

        var resultObj = new JsonObject
        {
            ["status"] = "accepted",
            ["itemId"] = itemId.Value,
            ["fileName"] = Path.GetFileName(finalPath),
            ["bytesReceived"] = totalBytes,
            ["sha256"] = actualHashHex
        };

        byte[] resultBytes = JsonSerializer.SerializeToUtf8Bytes(resultObj);
        response.StatusCode = (int)HttpStatusCode.OK;
        response.ContentType = "application/json; charset=utf-8";
        response.ContentLength64 = resultBytes.Length;
        await response.OutputStream.WriteAsync(resultBytes);
        response.OutputStream.Close();
    }

    public async Task StopAsync()
    {
        _cts.Cancel();
        if (_listener.IsListening)
        {
            _listener.Stop();
        }

        if (_listenerLoopTask != null)
        {
            try
            {
                await _listenerLoopTask;
            }
            catch
            {
                // Ignored during shutdown
            }
        }
    }

    public async ValueTask DisposeAsync()
    {
        await StopAsync();
        _cts.Dispose();
        _listener.Close();
    }
}
