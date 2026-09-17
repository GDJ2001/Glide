using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Glide.Core.Storage;
using Glide.Transport.Web;

namespace Glide.Tests;

/// <summary>
/// Desired-security regressions for the transitional local web gateway.
/// They are intentionally red until HARD-025..030 replaces/hardens the release browser path.
/// </summary>
public sealed class Hard003WebGatewayRegressionTests : IDisposable
{
    private readonly string _root;
    private readonly QuarantineStagingStore _store;

    public Hard003WebGatewayRegressionTests()
    {
        _root = Path.Combine(Path.GetTempPath(), "GlideHard003Web", Guid.NewGuid().ToString("N"));
        string staging = Path.Combine(_root, "staging");
        string downloads = Path.Combine(_root, "downloads");
        Directory.CreateDirectory(staging);
        Directory.CreateDirectory(downloads);
        _store = new QuarantineStagingStore(staging, downloads, () => 10L * 1024 * 1024 * 1024);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true);
        }
        catch
        {
            // Cleanup must not hide the regression result.
        }
    }

    [Fact]
    public async Task OnboardingUrl_MustUseEncryptedTransport()
    {
        await using var gateway = new GlideWebGateway(
            host: "127.0.0.1",
            port: 0,
            quarantineStore: _store);
        await gateway.StartAsync();

        var uri = new Uri(gateway.OnboardingUrl);
        Assert.Equal(Uri.UriSchemeHttps, uri.Scheme);
    }

    [Fact]
    public async Task RedeemedOnboardingToken_MustNotRemainValidAsBearerCredential()
    {
        await using var gateway = new GlideWebGateway(
            host: "127.0.0.1",
            port: 0,
            quarantineStore: _store);
        await gateway.StartAsync();

        using (var redeemClient = new HttpClient(new HttpClientHandler { UseCookies = false }))
        {
            var redeemResponse = await redeemClient.GetAsync(gateway.OnboardingUrl);
            Assert.Equal(HttpStatusCode.OK, redeemResponse.StatusCode);
        }

        using var replayClient = new HttpClient(new HttpClientHandler { UseCookies = false });
        using var replayRequest = new HttpRequestMessage(
            HttpMethod.Get,
            $"http://127.0.0.1:{gateway.Port}/api/v1/manifest");
        replayRequest.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", gateway.TokenHex);

        var replayResponse = await replayClient.SendAsync(replayRequest);
        Assert.Equal(HttpStatusCode.Forbidden, replayResponse.StatusCode);
    }

    [Fact]
    public async Task Upload_MustRequireAcceptedProposalUploadId()
    {
        await using var gateway = new GlideWebGateway(
            host: "127.0.0.1",
            port: 0,
            quarantineStore: _store);
        await gateway.StartAsync();

        using var client = await CreateAuthenticatedClientAsync(gateway);
        byte[] payload = Encoding.UTF8.GetBytes("proposal binding regression");
        string sha = Convert.ToHexStringLower(SHA256.HashData(payload));

        using var request = new HttpRequestMessage(
            HttpMethod.Post,
            $"http://127.0.0.1:{gateway.Port}/api/v1/upload");
        request.Headers.Add("X-File-Name", "unproposed.txt");
        request.Headers.Add("X-File-Size", payload.Length.ToString());
        request.Headers.Add("X-Declared-Sha256", sha);
        request.Content = new ByteArrayContent(payload);

        var response = await client.SendAsync(request);
        Assert.Equal((HttpStatusCode)428, response.StatusCode);
    }

    [Fact]
    public async Task Upload_MustRejectMissingDeclaredDigest_EvenAfterProposal()
    {
        await using var gateway = new GlideWebGateway(
            host: "127.0.0.1",
            port: 0,
            quarantineStore: _store);
        await gateway.StartAsync();

        using var client = await CreateAuthenticatedClientAsync(gateway);
        byte[] payload = Encoding.UTF8.GetBytes("digest-required regression");
        string uploadId = await CreateProposalAsync(client, gateway, "digest-required.txt", payload);

        using var request = new HttpRequestMessage(
            HttpMethod.Post,
            $"http://127.0.0.1:{gateway.Port}/api/v1/upload");
        request.Headers.Add("X-Upload-Id", uploadId);
        request.Headers.Add("X-File-Name", "digest-required.txt");
        request.Headers.Add("X-File-Size", payload.Length.ToString());
        request.Content = new ByteArrayContent(payload);

        var response = await client.SendAsync(request);
        Assert.NotEqual(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task ProposalUploadId_MustBeConsumedExactlyOnce()
    {
        await using var gateway = new GlideWebGateway(
            host: "127.0.0.1",
            port: 0,
            quarantineStore: _store);
        await gateway.StartAsync();

        using var client = await CreateAuthenticatedClientAsync(gateway);
        byte[] payload = Encoding.UTF8.GetBytes("single-use upload proposal");
        string sha = Convert.ToHexStringLower(SHA256.HashData(payload));
        string uploadId = await CreateProposalAsync(client, gateway, "single-use.txt", payload, sha);

        HttpResponseMessage first = await SendUploadAsync(client, gateway, uploadId, "single-use.txt", payload, sha);
        Assert.Equal(HttpStatusCode.OK, first.StatusCode);

        HttpResponseMessage replay = await SendUploadAsync(client, gateway, uploadId, "single-use.txt", payload, sha);
        Assert.NotEqual(HttpStatusCode.OK, replay.StatusCode);
    }

    private static async Task<HttpClient> CreateAuthenticatedClientAsync(GlideWebGateway gateway)
    {
        var cookies = new CookieContainer();
        var client = new HttpClient(new HttpClientHandler { CookieContainer = cookies });
        var response = await client.GetAsync(gateway.OnboardingUrl);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        return client;
    }

    private static async Task<string> CreateProposalAsync(
        HttpClient client,
        GlideWebGateway gateway,
        string fileName,
        byte[] payload,
        string? sha = null)
    {
        sha ??= Convert.ToHexStringLower(SHA256.HashData(payload));
        var proposal = new JsonObject
        {
            ["fileName"] = fileName,
            ["fileSize"] = payload.LongLength,
            ["mimeType"] = "application/octet-stream",
            ["sha256"] = sha
        };
        using var content = new StringContent(proposal.ToJsonString(), Encoding.UTF8, "application/json");
        var response = await client.PostAsync(
            $"http://127.0.0.1:{gateway.Port}/api/v1/upload/proposal",
            content);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var body = JsonNode.Parse(await response.Content.ReadAsStringAsync())?.AsObject();
        return body?["uploadId"]?.GetValue<string>()
            ?? throw new Xunit.Sdk.XunitException("Proposal response did not contain uploadId.");
    }

    private static async Task<HttpResponseMessage> SendUploadAsync(
        HttpClient client,
        GlideWebGateway gateway,
        string uploadId,
        string fileName,
        byte[] payload,
        string sha)
    {
        var request = new HttpRequestMessage(
            HttpMethod.Post,
            $"http://127.0.0.1:{gateway.Port}/api/v1/upload");
        request.Headers.Add("X-Upload-Id", uploadId);
        request.Headers.Add("X-File-Name", fileName);
        request.Headers.Add("X-File-Size", payload.Length.ToString());
        request.Headers.Add("X-Declared-Sha256", sha);
        request.Content = new ByteArrayContent(payload);
        return await client.SendAsync(request);
    }
}
