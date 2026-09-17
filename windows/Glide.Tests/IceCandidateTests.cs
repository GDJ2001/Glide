using System.Net;
using Glide.Core.Remote;
using Xunit;

namespace Glide.Tests;

public class IceCandidateTests
{
    [Fact]
    public void CalculatePriority_ComputesCorrectRfc8445Priorities()
    {
        uint hostPriority = IceCandidate.CalculatePriority(IceCandidateType.Host, 65535, 1);
        uint prflxPriority = IceCandidate.CalculatePriority(IceCandidateType.PeerReflexive, 65535, 1);
        uint srflxPriority = IceCandidate.CalculatePriority(IceCandidateType.ServerReflexive, 65535, 1);
        uint relayPriority = IceCandidate.CalculatePriority(IceCandidateType.Relayed, 65535, 1);

        // Host > PeerReflexive > ServerReflexive > Relayed
        Assert.True(hostPriority > prflxPriority);
        Assert.True(prflxPriority > srflxPriority);
        Assert.True(srflxPriority > relayPriority);

        // Explicit formula verification:
        // Host: (126 << 24) | (65535 << 8) | (256 - 1) = 0x7E | 0xFFFF | 0xFF
        uint expectedHost = (126u << 24) | (65535u << 8) | 255u;
        Assert.Equal(expectedHost, hostPriority);

        // Relayed: (0 << 24) | (65535 << 8) | 255 = 0x00FFFF | 255
        uint expectedRelay = (65535u << 8) | 255u;
        Assert.Equal(expectedRelay, relayPriority);
    }

    [Fact]
    public void ToSdpString_And_ParseSdpString_RoundTrip_Host()
    {
        var endPoint = new IPEndPoint(IPAddress.Parse("192.168.1.50"), 42424);
        var original = IceCandidate.CreateHost("1", endPoint);

        string sdp = original.ToSdpString();
        Assert.StartsWith("candidate:1 1 UDP", sdp);
        Assert.Contains("typ host", sdp);

        var parsed = IceCandidate.ParseSdpString(sdp);
        Assert.Equal(original.Foundation, parsed.Foundation);
        Assert.Equal(original.ComponentId, parsed.ComponentId);
        Assert.Equal(original.Transport, parsed.Transport);
        Assert.Equal(original.Priority, parsed.Priority);
        Assert.Equal(original.EndPoint, parsed.EndPoint);
        Assert.Equal(original.Type, parsed.Type);
        Assert.Null(parsed.RelatedEndPoint);
    }

    [Fact]
    public void ToSdpString_And_ParseSdpString_RoundTrip_ServerReflexive()
    {
        var publicEndPoint = new IPEndPoint(IPAddress.Parse("203.0.113.195"), 54321);
        var baseEndPoint = new IPEndPoint(IPAddress.Parse("192.168.1.50"), 42424);
        var original = IceCandidate.CreateServerReflexive("2", publicEndPoint, baseEndPoint);

        string sdp = original.ToSdpString();
        Assert.Contains("typ srflx", sdp);
        Assert.Contains("raddr 192.168.1.50 rport 42424", sdp);

        var parsed = IceCandidate.ParseSdpString(sdp);
        Assert.Equal(original.Foundation, parsed.Foundation);
        Assert.Equal(original.EndPoint, parsed.EndPoint);
        Assert.Equal(original.Type, parsed.Type);
        Assert.NotNull(parsed.RelatedEndPoint);
        Assert.Equal(baseEndPoint, parsed.RelatedEndPoint);
    }

    [Fact]
    public void ToJson_And_FromJson_RoundTrip_PreservesAllFields()
    {
        var publicEndPoint = new IPEndPoint(IPAddress.Parse("203.0.113.195"), 54321);
        var baseEndPoint = new IPEndPoint(IPAddress.Parse("192.168.1.50"), 42424);
        var original = IceCandidate.CreateServerReflexive("stun1", publicEndPoint, baseEndPoint);

        var json = original.ToJson();
        var restored = IceCandidate.FromJson(json);

        Assert.Equal(original.Foundation, restored.Foundation);
        Assert.Equal(original.ComponentId, restored.ComponentId);
        Assert.Equal(original.Transport, restored.Transport);
        Assert.Equal(original.Priority, restored.Priority);
        Assert.Equal(original.EndPoint, restored.EndPoint);
        Assert.Equal(original.Type, restored.Type);
        Assert.Equal(original.RelatedEndPoint, restored.RelatedEndPoint);
    }
}
