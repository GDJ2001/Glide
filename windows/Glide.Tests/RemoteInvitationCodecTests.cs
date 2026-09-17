using Glide.Core.Remote;
using Xunit;

namespace Glide.Tests;

public class RemoteInvitationCodecTests
{
    private const string ValidRendezvousId = "8a32a67e-071a-4d23-92b4-3ec9fef39db3";
    private const string ValidHexKey = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private const string ValidSignaling = "wss://relay.glide.app/v1/signal";
    private const string ValidToken = "eyJhbGciOiJub25lIn0";

    [Fact]
    public void Encode_Decode_RoundTrip_PreservesAllFields()
    {
        long futureExp = DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 100;
        var original = new RemoteInvitation(
            rendezvousId: ValidRendezvousId,
            ephemeralPublicKeyHex: ValidHexKey,
            signalingEndpoint: ValidSignaling,
            relayBlindToken: ValidToken,
            expiresAt: futureExp,
            ttlSeconds: 120);

        string encodedUri = RemoteInvitationCodec.Encode(original);

        Assert.StartsWith("glide://remote/v1#", encodedUri);
        Assert.Contains($"id={ValidRendezvousId}", encodedUri);
        Assert.Contains($"key={ValidHexKey}", encodedUri);
        Assert.Contains($"token={ValidToken}", encodedUri);
        Assert.Contains($"exp={futureExp}", encodedUri);

        var decoded = RemoteInvitationCodec.Decode(encodedUri, checkExpiry: true);

        Assert.Equal(original.RendezvousId, decoded.RendezvousId);
        Assert.Equal(original.EphemeralPublicKeyHex, decoded.EphemeralPublicKeyHex);
        Assert.Equal(original.SignalingEndpoint, decoded.SignalingEndpoint);
        Assert.Equal(original.RelayBlindToken, decoded.RelayBlindToken);
        Assert.Equal(original.ExpiresAt, decoded.ExpiresAt);
        Assert.False(decoded.IsExpired());
    }

    [Fact]
    public void Encode_InvalidRendezvousId_Throws()
    {
        var inv = new RemoteInvitation(
            rendezvousId: "not-a-valid-uuid",
            ephemeralPublicKeyHex: ValidHexKey,
            signalingEndpoint: ValidSignaling,
            relayBlindToken: ValidToken,
            expiresAt: DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 60);

        Assert.Throws<RemoteInvitationException>(() => RemoteInvitationCodec.Encode(inv));
    }

    [Fact]
    public void Encode_InvalidHexKey_Throws()
    {
        var inv = new RemoteInvitation(
            rendezvousId: ValidRendezvousId,
            ephemeralPublicKeyHex: "too-short",
            signalingEndpoint: ValidSignaling,
            relayBlindToken: ValidToken,
            expiresAt: DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 60);

        Assert.Throws<RemoteInvitationException>(() => RemoteInvitationCodec.Encode(inv));
    }

    [Fact]
    public void Encode_InvalidSignalingUri_Throws()
    {
        var inv = new RemoteInvitation(
            rendezvousId: ValidRendezvousId,
            ephemeralPublicKeyHex: ValidHexKey,
            signalingEndpoint: "http://insecure.endpoint",
            relayBlindToken: ValidToken,
            expiresAt: DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 60);

        Assert.Throws<RemoteInvitationException>(() => RemoteInvitationCodec.Encode(inv));
    }

    [Fact]
    public void Decode_ExpiredUri_ThrowsRemoteInvitationException()
    {
        long pastExp = DateTimeOffset.UtcNow.ToUnixTimeSeconds() - 10;
        string uri = $"glide://remote/v1#id={ValidRendezvousId}&key={ValidHexKey}&sig={Uri.EscapeDataString(ValidSignaling)}&token={ValidToken}&exp={pastExp}";

        Assert.Throws<RemoteInvitationException>(() => RemoteInvitationCodec.Decode(uri, checkExpiry: true));

        // When expiry check is disabled, decode succeeds
        var decoded = RemoteInvitationCodec.Decode(uri, checkExpiry: false);
        Assert.NotNull(decoded);
        Assert.True(decoded.IsExpired());
    }

    [Fact]
    public void Decode_UnknownParameter_ThrowsRemoteInvitationException()
    {
        long exp = DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 60;
        string uri = $"glide://remote/v1#id={ValidRendezvousId}&key={ValidHexKey}&sig={Uri.EscapeDataString(ValidSignaling)}&token={ValidToken}&exp={exp}&evil=true";

        Assert.Throws<RemoteInvitationException>(() => RemoteInvitationCodec.Decode(uri));
    }

    [Fact]
    public void Decode_DuplicateParameter_ThrowsRemoteInvitationException()
    {
        long exp = DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 60;
        string uri = $"glide://remote/v1#id={ValidRendezvousId}&id={ValidRendezvousId}&key={ValidHexKey}&sig={Uri.EscapeDataString(ValidSignaling)}&token={ValidToken}&exp={exp}";

        Assert.Throws<RemoteInvitationException>(() => RemoteInvitationCodec.Decode(uri));
    }

    [Fact]
    public void Decode_MissingParameter_ThrowsRemoteInvitationException()
    {
        string uri = $"glide://remote/v1#id={ValidRendezvousId}&key={ValidHexKey}&token={ValidToken}&exp=1700000000";

        Assert.Throws<RemoteInvitationException>(() => RemoteInvitationCodec.Decode(uri));
    }

    [Fact]
    public void Decode_OversizedUri_ThrowsRemoteInvitationException()
    {
        string oversizedPadding = new('a', 2500);
        string uri = $"glide://remote/v1#id={ValidRendezvousId}&key={ValidHexKey}&sig={Uri.EscapeDataString(ValidSignaling)}&token={oversizedPadding}&exp=1700000000";

        Assert.Throws<RemoteInvitationException>(() => RemoteInvitationCodec.Decode(uri));
    }
}
