using Glide.Core.Pairing;
using Xunit;

namespace Glide.Tests;

public class LanBootstrapCodecTests
{
    private const string ValidInvitationId = "00000000-0000-4000-8000-000000000001";
    private const string ValidSessionId = "00000000-0000-4000-8000-000000000002";
    private const string ValidToken = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG";
    private const string ValidHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    [Fact]
    public void BootstrapMessage_CanonicalRoundTrip()
    {
        var original = new LanBootstrapMessage(
            invitationId: ValidInvitationId,
            token: ValidToken,
            invitationHash: ValidHash,
            sessionId: ValidSessionId,
            role: "sender"
        );

        byte[] encoded = LanBootstrapCodec.EncodeBootstrap(original);
        Assert.True(encoded.Length <= LanBootstrapCodec.MaxBootstrapJsonBytes);

        var decoded = LanBootstrapCodec.DecodeBootstrap(encoded);
        Assert.Equal(original, decoded);
    }

    [Fact]
    public void BootstrapMessage_TokenRedactedInToString()
    {
        var msg = new LanBootstrapMessage(
            invitationId: ValidInvitationId,
            token: ValidToken,
            invitationHash: ValidHash,
            sessionId: ValidSessionId,
            role: "sender"
        );

        string str = msg.ToString();
        Assert.DoesNotContain(ValidToken, str);
        Assert.Contains("[REDACTED]", str);
    }

    [Fact]
    public void BootstrapResultAccepted_CanonicalRoundTrip()
    {
        var result = LanBootstrapResult.Accepted(ValidSessionId);
        byte[] encoded = LanBootstrapCodec.EncodeResult(result);
        Assert.True(encoded.Length <= LanBootstrapCodec.MaxResultJsonBytes);

        var decoded = LanBootstrapCodec.DecodeResult(encoded);
        Assert.Equal(result, decoded);
        Assert.Equal("accepted", decoded.Status);
        Assert.Equal(ValidSessionId, decoded.SessionId);
        Assert.Null(decoded.Code);
        Assert.True(decoded.IsAccepted);
    }

    [Fact]
    public void BootstrapResultRejected_AllCatalogCodes()
    {
        foreach (var code in Enum.GetValues<LanBootstrapFailureCode>())
        {
            var result = LanBootstrapResult.Rejected(code);
            byte[] encoded = LanBootstrapCodec.EncodeResult(result);
            var decoded = LanBootstrapCodec.DecodeResult(encoded);

            Assert.Equal(result, decoded);
            Assert.Equal("rejected", decoded.Status);
            Assert.Equal(code.ToString(), decoded.Code);
            Assert.Null(decoded.SessionId);
            Assert.False(decoded.IsAccepted);
        }
    }
}
