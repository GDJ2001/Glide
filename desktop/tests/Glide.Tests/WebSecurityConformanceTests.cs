using System.Net;
using System.Text;
using System.Text.Json.Nodes;
using Glide.Core.Storage;
using Glide.Transport.Web;
using Xunit;

namespace Glide.Tests;

public class WebSecurityConformanceTests : IDisposable
{
    private readonly string _tempDirectory;
    private readonly string _stagingDirectory;
    private readonly string _downloadsDirectory;
    private readonly QuarantineStagingStore _quarantineStore;

    public WebSecurityConformanceTests()
    {
        _tempDirectory = Path.Combine(Path.GetTempPath(), "GlideWebSecurityTests_" + Guid.NewGuid().ToString("N"));
        _stagingDirectory = Path.Combine(_tempDirectory, "staging");
        _downloadsDirectory = Path.Combine(_tempDirectory, "downloads");

        Directory.CreateDirectory(_stagingDirectory);
        Directory.CreateDirectory(_downloadsDirectory);

        _quarantineStore = new QuarantineStagingStore(_stagingDirectory, _downloadsDirectory);
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
            // Ignore cleanup
        }
    }

    [Fact]
    public async Task TokenReplay_SubsequentQueryTokenAttempt_Returns403Forbidden()
    {
        await using var gateway = new GlideWebGateway(
            host: "127.0.0.1",
            port: 0,
            quarantineStore: _quarantineStore);

        await gateway.StartAsync();

        // 1. Initial legitimate onboarding request
        var legitimateCookieContainer = new CookieContainer();
        using var legitimateHandler = new HttpClientHandler { CookieContainer = legitimateCookieContainer };
        using var legitimateClient = new HttpClient(legitimateHandler);

        var firstResp = await legitimateClient.GetAsync(gateway.OnboardingUrl);
        Assert.Equal(HttpStatusCode.OK, firstResp.StatusCode);
        Assert.True(gateway.IsTokenConsumed);

        // 2. Token replay attack attempt by a different client/attacker
        var attackerCookieContainer = new CookieContainer();
        using var attackerHandler = new HttpClientHandler { CookieContainer = attackerCookieContainer };
        using var attackerClient = new HttpClient(attackerHandler);

        var replayResp = await attackerClient.GetAsync(gateway.OnboardingUrl);
        Assert.Equal(HttpStatusCode.Forbidden, replayResp.StatusCode);
    }

    [Theory]
    [InlineData("/")]
    [InlineData("/style.css")]
    [InlineData("/app.js")]
    [InlineData("/api/v1/manifest")]
    public async Task SecurityHeaders_AllEndpoints_ContainCspNoSniffDenyNoCache(string endpoint)
    {
        string sampleFile = Path.Combine(_tempDirectory, "sample.txt");
        await File.WriteAllTextAsync(sampleFile, "Security Conformance Test");
        var item = new OfferedWebItem(sampleFile, "sample.txt", "text/plain");

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

        var resp = await client.GetAsync($"http://127.0.0.1:{gateway.Port}{endpoint}");
        Assert.Equal(HttpStatusCode.OK, resp.StatusCode);

        // Verify CSP
        Assert.True(resp.Headers.Contains("Content-Security-Policy"));
        string csp = string.Join("; ", resp.Headers.GetValues("Content-Security-Policy"));
        Assert.Contains("default-src 'self'", csp);
        Assert.Contains("frame-ancestors 'none'", csp);

        // Verify X-Content-Type-Options
        Assert.True(resp.Headers.Contains("X-Content-Type-Options"));
        Assert.Equal("nosniff", resp.Headers.GetValues("X-Content-Type-Options").First());

        // Verify X-Frame-Options
        Assert.True(resp.Headers.Contains("X-Frame-Options"));
        Assert.Equal("DENY", resp.Headers.GetValues("X-Frame-Options").First());

        // Verify Cache-Control
        Assert.NotNull(resp.Headers.CacheControl);
        Assert.True(resp.Headers.CacheControl.NoStore);
    }

    [Theory]
    [InlineData("/")]
    [InlineData("/index.html")]
    [InlineData("/api/v1/manifest")]
    [InlineData("/api/v1/download/any-item-id")]
    public async Task UnauthorizedAccess_WithoutTokenOrCookie_Returns403Forbidden(string endpoint)
    {
        await using var gateway = new GlideWebGateway(
            host: "127.0.0.1",
            port: 0,
            quarantineStore: _quarantineStore);

        await gateway.StartAsync();

        using var unauthClient = new HttpClient();
        var resp = await unauthClient.GetAsync($"http://127.0.0.1:{gateway.Port}{endpoint}");
        Assert.Equal(HttpStatusCode.Forbidden, resp.StatusCode);
    }

    [Fact]
    public async Task SessionCookie_IssuedWithHttpOnlyAndSameSiteStrict()
    {
        await using var gateway = new GlideWebGateway(
            host: "127.0.0.1",
            port: 0,
            quarantineStore: _quarantineStore);

        await gateway.StartAsync();

        using var handler = new HttpClientHandler { UseCookies = false };
        using var client = new HttpClient(handler);

        var resp = await client.GetAsync(gateway.OnboardingUrl);
        Assert.Equal(HttpStatusCode.OK, resp.StatusCode);

        Assert.True(resp.Headers.Contains("Set-Cookie"));
        string setCookie = string.Join("; ", resp.Headers.GetValues("Set-Cookie"));
        Assert.Contains("glide_web_session=", setCookie);
        Assert.Contains("HttpOnly", setCookie);
        Assert.Contains("SameSite=Strict", setCookie);
    }

    [Fact]
    public async Task PathTraversalAttacks_AreSanitizedAndBlocked()
    {
        await using var gateway = new GlideWebGateway(
            host: "127.0.0.1",
            port: 0,
            quarantineStore: _quarantineStore);

        await gateway.StartAsync();

        var cookieContainer = new CookieContainer();
        using var handler = new HttpClientHandler { CookieContainer = cookieContainer };
        using var client = new HttpClient(handler);

        await client.GetAsync(gateway.OnboardingUrl);

        // Attempt traversal download
        var downloadResp = await client.GetAsync($"http://127.0.0.1:{gateway.Port}/api/v1/download/../../etc/passwd");
        Assert.Equal(HttpStatusCode.NotFound, downloadResp.StatusCode);

        // Attempt traversal upload
        byte[] payload = Encoding.UTF8.GetBytes("Traversal attack attempt");
        using var uploadReq = new HttpRequestMessage(HttpMethod.Post, $"http://127.0.0.1:{gateway.Port}/api/v1/upload")
        {
            Content = new ByteArrayContent(payload)
        };
        uploadReq.Headers.Add("X-File-Name", Uri.EscapeDataString("../../../Windows/System32/evil.dll"));

        var uploadResp = await client.SendAsync(uploadReq);
        Assert.Equal(HttpStatusCode.OK, uploadResp.StatusCode);

        // Verify the file was sanitized and safely written inside downloads, not at arbitrary paths
        string committedFile = Path.Combine(_downloadsDirectory, "Windows_System32_evil.dll");
        Assert.True(File.Exists(committedFile));
    }
}
