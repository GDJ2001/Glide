using System.Net;
using Glide.Core.Remote;
using Glide.Transport.Remote;
using Xunit;

namespace Glide.Tests;

public class IceCandidateGathererTests
{
    [Fact]
    public void GetLocalUnicastAddresses_ReturnsNonEmptyNonLoopbackAddresses()
    {
        var addresses = IceCandidateGatherer.GetLocalUnicastAddresses();

        Assert.NotEmpty(addresses);
        foreach (var addr in addresses)
        {
            Assert.False(IPAddress.IsLoopback(addr));
            if (addr.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork)
            {
                byte[] b = addr.GetAddressBytes();
                Assert.False(b[0] == 169 && b[1] == 254); // Not link-local APIPA
            }
        }
    }

    [Fact]
    public async Task GatherCandidatesAsync_ReturnsHostCandidatesWithDescendingPriority()
    {
        var candidates = await IceCandidateGatherer.GatherCandidatesAsync(localPort: 42424);

        Assert.NotEmpty(candidates);
        Assert.All(candidates, c =>
        {
            Assert.Equal(IceCandidateType.Host, c.Type);
            Assert.Equal(42424, c.EndPoint.Port);
            Assert.False(IPAddress.IsLoopback(c.EndPoint.Address));
        });

        // Ensure sorted by descending priority
        for (int i = 0; i < candidates.Count - 1; i++)
        {
            Assert.True(candidates[i].Priority >= candidates[i + 1].Priority);
        }
    }
}
