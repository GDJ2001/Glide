using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Glide.Core.Models;
using Glide.Core.Storage;
using Glide.Transport.Web;
using Xunit;

namespace Glide.Tests;

public class WebGatewayIntegrationTests : IDisposable
{
    private readonly string _tempDirectory;
    private readonly QuarantineStagingStore _quarantineStore;

    public WebGatewayIntegrationTests()
    {
        _tempDirectory = Path.Combine(Path.GetTempPath(), "GlideWebTests_" + Guid.NewGuid().ToString("N"));
        string stagingDir = Path.Combine(_tempDirectory, "staging");
        string downloadsDir = Path.Combine(_tempDirectory, "downloads");

        Directory.CreateDirectory(stagingDir);
        Directory.CreateDirectory(downloadsDir);

        _quarantineStore = new QuarantineStagingStore(stagingDir, downloadsDir);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_tempDirectory))
            {
                Directory.Delete(_tempDirectory, recursive: true);
            }
        }
        catch
        {
            // Ignore cleanup errors
        }
    }

    [Fact]
    public async Task AssetDelivery_ReturnsHtmlCssJs_WithStrictCspHeaders()
    {
        await using var gateway = new GlideWebGateway(
            host: "127.0.0.1",
            port: 0,
            quarantineStore: _quarantineStore);

        await gateway.StartAsync();

        using var handler = new HttpClientHandler { AllowAutoRedirect = false };
        using var client = new HttpClient(handler);

        // Fetch CSS
        var cssResp = await client.GetAsync($"http://127.0.0.1:{gateway.Port}/style.css");
        Assert.Equal(HttpStatusCode.OK, cssResp.StatusCode);
        Assert.Equal("text/css; charset=utf-8", cssResp.Content.Headers.ContentType?.ToString());
        Assert.True(cssResp.Headers.Contains("Content-Security-Policy"));
        Assert.Equal("nosniff", cssResp.Headers.GetValues("X-Content-Type-Options").First());
        Assert.Equal("DENY", cssResp.Headers.GetValues("X-Frame-Options").First());

        // Fetch JS
        var jsResp = await client.GetAsync($"http://127.0.0.1:{gateway.Port}/app.js");
        Assert.Equal(HttpStatusCode.OK, jsResp.StatusCode);
        Assert.Equal("application/javascript", jsResp.Content.Headers.ContentType?.ToString());
        Assert.True(jsResp.Headers.Contains("Content-Security-Policy"));

        // Fetch HTML without token -> 403 Forbidden
        var unauthorizedHtml = await client.GetAsync($"http://127.0.0.1:{gateway.Port}/");
        Assert.Equal(HttpStatusCode.Forbidden, unauthorizedHtml.StatusCode);

        // Fetch HTML with valid token -> 200 OK
        var authorizedHtml = await client.GetAsync(gateway.OnboardingUrl);
        Assert.Equal(HttpStatusCode.OK, authorizedHtml.StatusCode);
        string htmlContent = await authorizedHtml.Content.ReadAsStringAsync();
        Assert.Contains("Glide Web Share", htmlContent);
        Assert.True(authorizedHtml.Headers.Contains("Content-Security-Policy"));
    }

    [Fact]
    public async Task TokenAuthentication_RejectsUnauthorized_AndAcceptsValidToken()
    {
        string sampleFile = Path.Combine(_tempDirectory, "sample.txt");
        await File.WriteAllTextAsync(sampleFile, "Glide Web Gateway Test Content");
        var item = new OfferedWebItem(sampleFile, "sample.txt", "text/plain");

        await using var gateway = new GlideWebGateway(
            host: "127.0.0.1",
            port: 0,
            offeredItems: [item],
            quarantineStore: _quarantineStore,
            deviceName: "TestWindowsPC");

        await gateway.StartAsync();

        var cookieContainer = new CookieContainer();
        using var handler = new HttpClientHandler { CookieContainer = cookieContainer };
        using var client = new HttpClient(handler);

        // 1. Direct call to /api/v1/manifest without token -> 403
        var unauthResp = await client.GetAsync($"http://127.0.0.1:{gateway.Port}/api/v1/manifest");
        Assert.Equal(HttpStatusCode.Forbidden, unauthResp.StatusCode);

        // 2. Direct call with invalid token -> 403
        var badTokenResp = await client.GetAsync($"http://127.0.0.1:{gateway.Port}/api/v1/manifest?token=deadbeef");
        Assert.Equal(HttpStatusCode.Forbidden, badTokenResp.StatusCode);

        // 3. Redeem token via Onboarding URL -> 200 OK + sets session cookie
        var onboardResp = await client.GetAsync(gateway.OnboardingUrl);
        Assert.Equal(HttpStatusCode.OK, onboardResp.StatusCode);
        Assert.True(gateway.IsTokenConsumed);

        // 4. Now call /api/v1/manifest with session cookie -> 200 OK
        var manifestResp = await client.GetAsync($"http://127.0.0.1:{gateway.Port}/api/v1/manifest");
        Assert.Equal(HttpStatusCode.OK, manifestResp.StatusCode);
        string manifestJson = await manifestResp.Content.ReadAsStringAsync();

        var node = JsonNode.Parse(manifestJson)?.AsObject();
        Assert.NotNull(node);
        Assert.Equal("TestWindowsPC", node["device"]?["name"]?.GetValue<string>());
        var items = node["items"]?.AsArray();
        Assert.NotNull(items);
        Assert.Single(items);
        Assert.Equal("sample.txt", items[0]?["fileName"]?.GetValue<string>());
        Assert.Equal(item.FileSize, items[0]?["fileSize"]?.GetValue<long>());
    }

    [Fact]
    public async Task DownloadItem_StreamsAccurateBytes_WithContentDisposition()
    {
        byte[] payload = new byte[128 * 1024]; // 128 KiB
        Random.Shared.NextBytes(payload);

        string testFilePath = Path.Combine(_tempDirectory, "large_binary.dat");
        await File.WriteAllBytesAsync(testFilePath, payload);

        var item = new OfferedWebItem(testFilePath, "large_binary.dat", "application/octet-stream");

        await using var gateway = new GlideWebGateway(
            host: "127.0.0.1",
            port: 0,
            offeredItems: [item],
            quarantineStore: _quarantineStore);

        await gateway.StartAsync();

        var cookieContainer = new CookieContainer();
        using var handler = new HttpClientHandler { CookieContainer = cookieContainer };
        using var client = new HttpClient(handler);

        // Authenticate
        await client.GetAsync(gateway.OnboardingUrl);

        // Download
        var downloadResp = await client.GetAsync($"http://127.0.0.1:{gateway.Port}/api/v1/download/{item.ItemId}");
        Assert.Equal(HttpStatusCode.OK, downloadResp.StatusCode);
        Assert.Equal("bytes", downloadResp.Headers.GetValues("Accept-Ranges").First());
        string disposition = downloadResp.Content.Headers.GetValues("Content-Disposition").First();
        Assert.Contains("filename=\"large_binary.dat\"", disposition);
        Assert.Contains("filename*=UTF-8''large_binary.dat", disposition);

        byte[] downloadedBytes = await downloadResp.Content.ReadAsByteArrayAsync();
        Assert.Equal(payload.Length, downloadedBytes.Length);
        Assert.Equal(payload, downloadedBytes);
    }

    [Fact]
    public async Task UploadItem_StagesInQuarantine_AndVerifiesSha256()
    {
        await using var gateway = new GlideWebGateway(
            host: "127.0.0.1",
            port: 0,
            quarantineStore: _quarantineStore);

        await gateway.StartAsync();

        var cookieContainer = new CookieContainer();
        using var handler = new HttpClientHandler { CookieContainer = cookieContainer };
        using var client = new HttpClient(handler);

        // Authenticate
        await client.GetAsync(gateway.OnboardingUrl);

        // Upload a file
        byte[] uploadPayload = Encoding.UTF8.GetBytes("Hello from Apple Safari on iPhone!");
        string expectedSha256 = Convert.ToHexStringLower(SHA256.HashData(uploadPayload));

        using var request = new HttpRequestMessage(HttpMethod.Post, $"http://127.0.0.1:{gateway.Port}/api/v1/upload");
        request.Headers.Add("X-File-Name", Uri.EscapeDataString("iphone_notes.txt"));
        request.Content = new ByteArrayContent(uploadPayload);

        var uploadResp = await client.SendAsync(request);
        Assert.Equal(HttpStatusCode.OK, uploadResp.StatusCode);

        string respJson = await uploadResp.Content.ReadAsStringAsync();
        var node = JsonNode.Parse(respJson)?.AsObject();
        Assert.NotNull(node);
        Assert.Equal("accepted", node["status"]?.GetValue<string>());
        Assert.Equal(expectedSha256, node["sha256"]?.GetValue<string>());
        Assert.Equal(uploadPayload.Length, node["bytesReceived"]?.GetValue<long>());

        // Verify committed file exists in downloads directory
        string committedFile = Path.Combine(_tempDirectory, "downloads", "iphone_notes.txt");
        Assert.True(File.Exists(committedFile));
        byte[] savedBytes = await File.ReadAllBytesAsync(committedFile);
        Assert.Equal(uploadPayload, savedBytes);
    }

    [Fact]
    public async Task DownloadItem_SupportsRangeRequest_PartialContent206()
    {
        byte[] payload = new byte[1000];
        for (int i = 0; i < payload.Length; i++) payload[i] = (byte)(i % 256);

        string testFilePath = Path.Combine(_tempDirectory, "range_test.dat");
        await File.WriteAllBytesAsync(testFilePath, payload);

        var item = new OfferedWebItem(testFilePath, "range_test.dat", "application/octet-stream");

        await using var gateway = new GlideWebGateway(
            host: "127.0.0.1",
            port: 0,
            offeredItems: [item],
            quarantineStore: _quarantineStore);

        await gateway.StartAsync();

        var cookieContainer = new CookieContainer();
        using var handler = new HttpClientHandler { CookieContainer = cookieContainer };
        using var client = new HttpClient(handler);

        // Authenticate
        await client.GetAsync(gateway.OnboardingUrl);

        // 1. Explicit range: bytes=100-199 (100 bytes)
        using (var req1 = new HttpRequestMessage(HttpMethod.Get, $"http://127.0.0.1:{gateway.Port}/api/v1/download/{item.ItemId}"))
        {
            req1.Headers.Add("Range", "bytes=100-199");
            var resp1 = await client.SendAsync(req1);
            Assert.Equal(HttpStatusCode.PartialContent, resp1.StatusCode);
            Assert.Equal("bytes 100-199/1000", resp1.Content.Headers.GetValues("Content-Range").First());
            Assert.Equal(100, resp1.Content.Headers.ContentLength);
            byte[] body1 = await resp1.Content.ReadAsByteArrayAsync();
            Assert.Equal(payload[100..200], body1);
        }

        // 2. Open-ended range: bytes=900- (100 bytes)
        using (var req2 = new HttpRequestMessage(HttpMethod.Get, $"http://127.0.0.1:{gateway.Port}/api/v1/download/{item.ItemId}"))
        {
            req2.Headers.Add("Range", "bytes=900-");
            var resp2 = await client.SendAsync(req2);
            Assert.Equal(HttpStatusCode.PartialContent, resp2.StatusCode);
            Assert.Equal("bytes 900-999/1000", resp2.Content.Headers.GetValues("Content-Range").First());
            Assert.Equal(100, resp2.Content.Headers.ContentLength);
            byte[] body2 = await resp2.Content.ReadAsByteArrayAsync();
            Assert.Equal(payload[900..1000], body2);
        }

        // 3. Suffix range: bytes=-50 (last 50 bytes)
        using (var req3 = new HttpRequestMessage(HttpMethod.Get, $"http://127.0.0.1:{gateway.Port}/api/v1/download/{item.ItemId}"))
        {
            req3.Headers.Add("Range", "bytes=-50");
            var resp3 = await client.SendAsync(req3);
            Assert.Equal(HttpStatusCode.PartialContent, resp3.StatusCode);
            Assert.Equal("bytes 950-999/1000", resp3.Content.Headers.GetValues("Content-Range").First());
            Assert.Equal(50, resp3.Content.Headers.ContentLength);
            byte[] body3 = await resp3.Content.ReadAsByteArrayAsync();
            Assert.Equal(payload[^50..], body3);
        }
    }

    [Fact]
    public async Task DownloadItem_InvalidRange_Returns416RangeNotSatisfiable()
    {
        byte[] payload = new byte[100];
        string testFilePath = Path.Combine(_tempDirectory, "range_invalid.dat");
        await File.WriteAllBytesAsync(testFilePath, payload);

        var item = new OfferedWebItem(testFilePath, "range_invalid.dat", "application/octet-stream");

        await using var gateway = new GlideWebGateway(
            host: "127.0.0.1",
            port: 0,
            offeredItems: [item],
            quarantineStore: _quarantineStore);

        await gateway.StartAsync();

        var cookieContainer = new CookieContainer();
        using var handler = new HttpClientHandler { CookieContainer = cookieContainer };
        using var client = new HttpClient(handler);

        await client.GetAsync(gateway.OnboardingUrl);

        using var req = new HttpRequestMessage(HttpMethod.Get, $"http://127.0.0.1:{gateway.Port}/api/v1/download/{item.ItemId}");
        req.Headers.Add("Range", "bytes=500-600");
        var resp = await client.SendAsync(req);

        Assert.Equal((HttpStatusCode)416, resp.StatusCode);
        Assert.Equal("bytes */100", resp.Content.Headers.GetValues("Content-Range").First());
    }

    [Fact]
    public async Task DownloadItem_HeadMethod_ReturnsHeadersWithoutBody()
    {
        byte[] payload = new byte[512];
        string testFilePath = Path.Combine(_tempDirectory, "head_test.dat");
        await File.WriteAllBytesAsync(testFilePath, payload);

        var item = new OfferedWebItem(testFilePath, "head_test.dat", "application/octet-stream");

        await using var gateway = new GlideWebGateway(
            host: "127.0.0.1",
            port: 0,
            offeredItems: [item],
            quarantineStore: _quarantineStore);

        await gateway.StartAsync();

        var cookieContainer = new CookieContainer();
        using var handler = new HttpClientHandler { CookieContainer = cookieContainer };
        using var client = new HttpClient(handler);

        await client.GetAsync(gateway.OnboardingUrl);

        using var req = new HttpRequestMessage(HttpMethod.Head, $"http://127.0.0.1:{gateway.Port}/api/v1/download/{item.ItemId}");
        var resp = await client.SendAsync(req);

        Assert.Equal(HttpStatusCode.OK, resp.StatusCode);
        Assert.Equal(512, resp.Content.Headers.ContentLength);
        Assert.Equal("bytes", resp.Headers.GetValues("Accept-Ranges").First());
        Assert.True(resp.Content.Headers.Contains("Content-Disposition"));

        byte[] body = await resp.Content.ReadAsByteArrayAsync();
        Assert.Empty(body);
    }

    [Fact]
    public async Task DownloadItem_UnicodeFilename_IncludesRfc6266Encoding()
    {
        string unicodeFileName = "日本語写真_📷.jpg";
        string testFilePath = Path.Combine(_tempDirectory, "unicode_test.jpg");
        await File.WriteAllBytesAsync(testFilePath, [1, 2, 3, 4]);

        var item = new OfferedWebItem(testFilePath, unicodeFileName, "image/jpeg");

        await using var gateway = new GlideWebGateway(
            host: "127.0.0.1",
            port: 0,
            offeredItems: [item],
            quarantineStore: _quarantineStore);

        await gateway.StartAsync();

        var cookieContainer = new CookieContainer();
        using var handler = new HttpClientHandler { CookieContainer = cookieContainer };
        using var client = new HttpClient(handler);

        await client.GetAsync(gateway.OnboardingUrl);

        var resp = await client.GetAsync($"http://127.0.0.1:{gateway.Port}/api/v1/download/{item.ItemId}");
        Assert.Equal(HttpStatusCode.OK, resp.StatusCode);

        string disposition = resp.Content.Headers.GetValues("Content-Disposition").First();
        Assert.Contains("filename=", disposition);
        Assert.Contains("filename*=UTF-8''", disposition);
        Assert.Contains(Uri.EscapeDataString(unicodeFileName), disposition);
    }

    [Fact]
    public async Task ExpiredToken_ReturnsForbidden()
    {
        await using var gateway = new GlideWebGateway(
            host: "127.0.0.1",
            port: 0,
            quarantineStore: _quarantineStore,
            tokenTtlSeconds: 0.05); // 50ms TTL

        await gateway.StartAsync();

        // Wait for token to expire
        await Task.Delay(150);

        using var client = new HttpClient();
        var resp = await client.GetAsync(gateway.OnboardingUrl);
        Assert.Equal(HttpStatusCode.Forbidden, resp.StatusCode);
    }
}
