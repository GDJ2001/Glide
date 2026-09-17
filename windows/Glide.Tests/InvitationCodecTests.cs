using Glide.Core.Pairing;
using Xunit;

namespace Glide.Tests;

public class InvitationCodecTests
{
    private static LanInvitation SampleValidInvitation()
    {
        return new LanInvitation(
            invitationId: "00000000-0000-4000-8000-000000000001",
            presence: "00112233445566778899aabbccddeeff",
            name: "Pixel 9 Pro",
            platform: "android",
            endpoints:
            [
                new LanEndpoint("192.168.1.50", 49152),
                new LanEndpoint("2001:db8::1", 49153)
            ],
            spkiSha256: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            token: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLM_012",
            expiresAt: "1773729600",
            ttlSeconds: 120
        );
    }

    [Fact]
    public void RoundTrip_CanonicalFixture_PreservesAllFieldsAndComputesHash()
    {
        var original = SampleValidInvitation();
        string uri = InvitationCodec.Encode(original);

        Assert.StartsWith("glide://pair/v1#", uri);
        Assert.True(uri.Length <= InvitationCodec.MaxUriChars);

        var decoded = InvitationCodec.Decode(uri);

        Assert.Equal(original.V, decoded.V);
        Assert.Equal(original.Transport, decoded.Transport);
        Assert.Equal(original.Profile, decoded.Profile);
        Assert.Equal(original.Protocol, decoded.Protocol);
        Assert.Equal(original.InvitationId, decoded.InvitationId);
        Assert.Equal(original.Presence, decoded.Presence);
        Assert.Equal(original.Name, decoded.Name);
        Assert.Equal(original.Platform, decoded.Platform);
        Assert.Equal(original.Endpoints.Count, decoded.Endpoints.Count);
        Assert.Equal(original.Endpoints[0].Address, decoded.Endpoints[0].Address);
        Assert.Equal(original.Endpoints[0].Port, decoded.Endpoints[0].Port);
        Assert.Equal(original.Endpoints[1].Address, decoded.Endpoints[1].Address);
        Assert.Equal(original.Endpoints[1].Port, decoded.Endpoints[1].Port);
        Assert.Equal(original.SpkiSha256, decoded.SpkiSha256);
        Assert.Equal(original.Token, decoded.Token);
        Assert.Equal(original.ExpiresAt, decoded.ExpiresAt);
        Assert.Equal(original.TtlSeconds, decoded.TtlSeconds);

        Assert.False(string.IsNullOrWhiteSpace(decoded.InvitationHash));
        Assert.Equal(64, decoded.InvitationHash.Length);
        Assert.Matches("^[0-9a-f]{64}$", decoded.InvitationHash);
    }

    [Fact]
    public void SecretToken_IsStrictlyRedactedFromToString()
    {
        var invitation = SampleValidInvitation();
        string str = invitation.ToString();

        Assert.DoesNotContain(invitation.Token, str);
        Assert.Contains("token=[REDACTED]", str);
    }

    [Fact]
    public void Decode_RejectsUriWithoutPrefix()
    {
        var validUri = InvitationCodec.Encode(SampleValidInvitation());
        string invalidUri = validUri["glide://pair/v1#".Length..];

        var ex = Assert.Throws<InvitationException>(() => InvitationCodec.Decode(invalidUri));
        Assert.Contains("glide://pair/v1#", ex.Message);
    }

    [Fact]
    public void Decode_RejectsUriExceedingMaxChars()
    {
        string longUri = "glide://pair/v1#" + new string('a', InvitationCodec.MaxUriChars);

        var ex = Assert.Throws<InvitationException>(() => InvitationCodec.Decode(longUri));
        Assert.Contains("exceeds maximum", ex.Message);
    }

    [Fact]
    public void Decode_RejectsMalformedBase64Url()
    {
        string invalidUri = "glide://pair/v1#not+valid*base64=";

        var ex = Assert.Throws<InvitationException>(() => InvitationCodec.Decode(invalidUri));
        Assert.Contains("non-base64url", ex.Message);
    }

    [Fact]
    public void LanEndpoint_ValidatesIpv4AndIpv6()
    {
        var ep4 = new LanEndpoint("192.168.1.100", 8080);
        Assert.Equal("192.168.1.100", ep4.Address);
        Assert.Equal(8080, ep4.Port);

        var ep6 = new LanEndpoint("::1", 9000);
        Assert.Equal("::1", ep6.Address);
        Assert.Equal(9000, ep6.Port);

        Assert.Throws<ArgumentException>(() => new LanEndpoint("01.02.03.04", 8080)); // Leading zero rejected
        Assert.Throws<ArgumentException>(() => new LanEndpoint("hostname.local", 8080)); // Hostnames rejected
        Assert.Throws<ArgumentException>(() => new LanEndpoint("192.168.1.1/24", 8080)); // CIDR rejected
        Assert.Throws<ArgumentOutOfRangeException>(() => new LanEndpoint("192.168.1.1", 0)); // Port 0 rejected
        Assert.Throws<ArgumentOutOfRangeException>(() => new LanEndpoint("192.168.1.1", 70000)); // Port > 65535
    }
}
