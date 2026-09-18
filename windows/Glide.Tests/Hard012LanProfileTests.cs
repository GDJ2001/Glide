using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using Glide.Core.Pairing;
using Glide.Transport.Discovery;
using Glide.Transport.Sockets;

namespace Glide.Tests;

public sealed class Hard012LanProfileTests
{
    private const string PresenceId = "00112233445566778899aabbccddeeff";

    [Fact]
    public void DiscoveryTxt_EncodesCanonicalAndroidCompatibleContract()
    {
        var attributes = LanDiscoveryTxtCodec.Encode(
            PresenceId,
            "Windows Receiver",
            LanDiscoveryConstants.DeviceTypeDesktop);

        Assert.Equal(
            new[] { "dn", "dt", "p", "q", "tv", "v" },
            attributes.Keys.OrderBy(key => key, StringComparer.Ordinal).ToArray());
        Assert.Equal("1", attributes["v"]);
        Assert.Equal("1", attributes["tv"]);
        Assert.Equal("1", attributes["q"]);
        Assert.Equal(PresenceId, attributes["p"]);
        Assert.Equal("Windows Receiver", attributes["dn"]);
        Assert.Equal("desktop", attributes["dt"]);

        Assert.DoesNotContain("fp", attributes.Keys);
        Assert.DoesNotContain("cap", attributes.Keys);
        Assert.DoesNotContain("alpn", attributes.Keys);
        Assert.DoesNotContain("token", attributes.Keys);
    }

    [Fact]
    public void DiscoveryTxt_DecodesAndroidContractAndRejectsLegacyOrMalformedProfiles()
    {
        var canonical = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["v"] = "1",
            ["tv"] = "1",
            ["q"] = "1",
            ["p"] = PresenceId,
            ["dn"] = "Pixel Receiver",
            ["dt"] = "phone"
        };

        Assert.True(LanDiscoveryTxtCodec.TryDecode(canonical, out var decoded));
        Assert.NotNull(decoded);
        Assert.Equal(PresenceId, decoded!.PresenceId);
        Assert.Equal("Pixel Receiver", decoded.DeviceName);
        Assert.Equal("phone", decoded.DeviceType);

        var legacy = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["v"] = "1",
            ["dn"] = "Legacy",
            ["dt"] = "desktop",
            ["alpn"] = "glide-v1",
            ["cap"] = "0x07",
            ["fp"] = "deadbeef"
        };
        Assert.False(LanDiscoveryTxtCodec.TryDecode(legacy, out _));

        var badPresence = new Dictionary<string, string>(canonical, StringComparer.Ordinal)
        {
            ["p"] = "00112233445566778899AABBCCDDEEFF"
        };
        Assert.False(LanDiscoveryTxtCodec.TryDecode(badPresence, out _));
    }

    [Fact]
    public async Task InvitationAuthority_ConcurrentReplay_AllowsExactlyOneConsumer()
    {
        LanInvitation invitation = CreateCanonicalInvitation();
        var authority = new LanInvitationAuthority(invitation);
        var bootstrap = new LanBootstrapMessage(
            invitation.InvitationId,
            invitation.Token,
            invitation.InvitationHash,
            Guid.NewGuid().ToString("D").ToLowerInvariant());

        Task<LanBootstrapResult>[] attempts = Enumerable.Range(0, 24)
            .Select(_ => Task.Run(() => authority.Consume(bootstrap)))
            .ToArray();

        LanBootstrapResult[] results = await Task.WhenAll(attempts);

        Assert.Single(results, result => result.IsAccepted);
        Assert.All(
            results.Where(result => !result.IsAccepted),
            result => Assert.Equal(
                LanBootstrapFailureCode.INVITATION_USED.ToString(),
                result.Code));
        Assert.True(authority.IsConsumed);
    }

    [Fact]
    public void InvitationAuthority_BadTokenDoesNotConsumeValidInvitation()
    {
        LanInvitation invitation = CreateCanonicalInvitation();
        var authority = new LanInvitationAuthority(invitation);

        string wrongToken = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32))
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');

        var rejected = authority.Consume(new LanBootstrapMessage(
            invitation.InvitationId,
            wrongToken,
            invitation.InvitationHash,
            Guid.NewGuid().ToString("D").ToLowerInvariant()));

        Assert.False(rejected.IsAccepted);
        Assert.Equal(
            LanBootstrapFailureCode.AUTHENTICATION_FAILED.ToString(),
            rejected.Code);
        Assert.False(authority.IsConsumed);

        var accepted = authority.Consume(new LanBootstrapMessage(
            invitation.InvitationId,
            invitation.Token,
            invitation.InvitationHash,
            Guid.NewGuid().ToString("D").ToLowerInvariant()));

        Assert.True(accepted.IsAccepted);
        Assert.True(authority.IsConsumed);
    }

    [Fact]
    public async Task Listener_AcceptsCertificateLessQrClient_WithCanonicalAlpn()
    {
        using var listener = new TlsSocketListener(port: 0);

        var serverTask = listener.AcceptConnectionAsync();

        using var tcp = new TcpClient();
        await tcp.ConnectAsync("127.0.0.1", listener.BoundPort);

        await using var ssl = new SslStream(
            tcp.GetStream(),
            leaveInnerStreamOpen: false,
            (_, _, _, _) => true);

        await ssl.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
        {
            TargetHost = "localhost",
            EnabledSslProtocols = SslProtocols.Tls13,
            ApplicationProtocols =
            [
                new SslApplicationProtocol(LanDiscoveryConstants.AlpnToken)
            ]
        });

        await using var server = await serverTask;

        Assert.Equal(
            new SslApplicationProtocol(LanDiscoveryConstants.AlpnToken),
            ssl.NegotiatedApplicationProtocol);
        Assert.Empty(server.RemotePublicKeyBytes);
        Assert.Null(server.RemoteSpkiSha256);
    }

    [Fact]
    public async Task Listener_RejectsLegacyAlpn()
    {
        using var listener = new TlsSocketListener(port: 0);

        var serverTask = listener.AcceptConnectionAsync();

        using var tcp = new TcpClient();
        await tcp.ConnectAsync("127.0.0.1", listener.BoundPort);

        await using var ssl = new SslStream(
            tcp.GetStream(),
            leaveInnerStreamOpen: false,
            (_, _, _, _) => true);

        try
        {
            await ssl.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
            {
                TargetHost = "localhost",
                EnabledSslProtocols = SslProtocols.Tls13,
                ApplicationProtocols =
                [
                    new SslApplicationProtocol("glide-v1")
                ]
            });
        }
        catch (AuthenticationException)
        {
            // The TLS stack may reject the missing ALPN overlap during the handshake.
        }

        await Assert.ThrowsAnyAsync<AuthenticationException>(async () => await serverTask);
    }
    private static LanInvitation CreateCanonicalInvitation()
    {
        string token = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32))
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');

        var draft = new LanInvitation(
            invitationId: Guid.NewGuid().ToString("D").ToLowerInvariant(),
            presence: Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant(),
            name: "Profile Test Receiver",
            platform: "windows",
            endpoints: [new LanEndpoint("127.0.0.1", 42424)],
            spkiSha256: new string('a', 64),
            token: token,
            expiresAt: DateTimeOffset.UtcNow.AddMinutes(2).ToUnixTimeSeconds().ToString(),
            ttlSeconds: 120);

        return InvitationCodec.Decode(InvitationCodec.Encode(draft));
    }
}
