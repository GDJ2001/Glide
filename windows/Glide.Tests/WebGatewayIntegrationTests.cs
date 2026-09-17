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
        Assert.Equal("attachment; filename=\"large_binary.dat\"", downloadResp.Content.Headers.GetValues("Content-Disposition").First());

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
