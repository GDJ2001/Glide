using Glide.Transport.Remote;
using Xunit;

namespace Glide.Tests;

public class SignalingMessagesTests
{
    [Fact]
    public void ListenRequest_Serialization_And_Parsing()
    {
        var original = new ListenRequest("8a32a67e-071a-4d23-92b4-3ec9fef39db3");
        string json = original.ToJsonString();

        Assert.Contains("\"action\":\"listen\"", json);
        Assert.Contains("\"rendezvousId\":\"8a32a67e-071a-4d23-92b4-3ec9fef39db3\"", json);

        var parsed = SignalingMessageCodec.Parse(json);
        var req = Assert.IsType<ListenRequest>(parsed);
        Assert.Equal(original.RendezvousId, req.RendezvousId);
    }

    [Fact]
    public void RendezvousRequest_Serialization_And_Parsing()
    {
        var original = new RendezvousRequest(
            "8a32a67e-071a-4d23-92b4-3ec9fef39db3",
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "YWJjZGVmZ2hpams=");

        string json = original.ToJsonString();
        Assert.Contains("\"action\":\"rendezvous\"", json);

        var parsed = SignalingMessageCodec.Parse(json);
        var req = Assert.IsType<RendezvousRequest>(parsed);
        Assert.Equal(original.RendezvousId, req.RendezvousId);
        Assert.Equal(original.ClientPublicKeyHex, req.ClientPublicKeyHex);
        Assert.Equal(original.EncryptedPayload, req.EncryptedPayload);
    }

    [Fact]
    public void PeerJoinedEvent_Serialization_And_Parsing()
    {
        var original = new PeerJoinedEvent(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "YWJjZGVmZ2hpams=");

        string json = original.ToJsonString();
        Assert.Contains("\"event\":\"peer_joined\"", json);

        var parsed = SignalingMessageCodec.Parse(json);
        var evt = Assert.IsType<PeerJoinedEvent>(parsed);
        Assert.Equal(original.PeerPublicKeyHex, evt.PeerPublicKeyHex);
        Assert.Equal(original.EncryptedPayload, evt.EncryptedPayload);
    }

    [Fact]
    public void SignalingError_Serialization_And_Parsing()
    {
        var original = new SignalingError("NOT_FOUND", "Rendezvous session expired or nonexistent");
        string json = original.ToJsonString();

        Assert.Contains("\"error\":\"NOT_FOUND\"", json);

        var parsed = SignalingMessageCodec.Parse(json);
        var err = Assert.IsType<SignalingError>(parsed);
        Assert.Equal(original.ErrorCode, err.ErrorCode);
        Assert.Equal(original.Message, err.Message);
    }

    [Fact]
    public void SignalingMessageCodec_InvalidJson_Throws()
    {
        Assert.Throws<FormatException>(() => SignalingMessageCodec.Parse("{\"unknown\":\"field\"}"));
    }
}
