using System.Text;
using Glide.Transport.Discovery;

namespace Glide.Tests;

public sealed class LanDiscoveryProfileTests
{
    private const string Presence =
        "0123456789abcdef0123456789abcdef";

    [Fact]
    public void CanonicalAvailabilityRecord_MatchesAndroidProfile()
    {
        var attributes = new Dictionary<string, string>
        {
            ["v"] = "1",
            ["tv"] = "1",
            ["q"] = "1",
            ["p"] = Presence,
            ["dn"] = "Desktop Peer",
            ["dt"] = LanDiscoveryConstants.DeviceTypeDesktop
        };

        Assert.True(
            MdnsDiscoveryService
                .IsCompatibleAvailabilityRecord(attributes));
    }

    [Fact]
    public void LegacyFingerprintCapabilityRecord_IsRejected()
    {
        var legacy = new Dictionary<string, string>
        {
            ["v"] = "1",
            ["dn"] = "Desktop Peer",
            ["dt"] = LanDiscoveryConstants.DeviceTypeDesktop,
            ["fp"] = "a1b2c3d4",
            ["cap"] = "7",
            ["alpn"] = "glide-v1"
        };

        Assert.False(
            MdnsDiscoveryService
                .IsCompatibleAvailabilityRecord(legacy));
    }

    [Fact]
    public void Advertisement_DoesNotExposeAuthenticationMaterial()
    {
        byte[] packet =
            MdnsDiscoveryService.BuildResponsePacketForTesting(
                "Desktop Peer",
                42424,
                Presence);
        string printable =
            Encoding.UTF8.GetString(packet);

        Assert.Contains("v=1", printable);
        Assert.Contains("tv=1", printable);
        Assert.Contains("q=1", printable);
        Assert.Contains($"p={Presence}", printable);
        Assert.Contains("dn=Desktop Peer", printable);
        Assert.Contains("dt=desktop", printable);

        Assert.DoesNotContain("fp=", printable);
        Assert.DoesNotContain("cap=", printable);
        Assert.DoesNotContain("alpn=", printable);
        Assert.DoesNotContain("token", printable);
        Assert.True(packet.Length < 1_500);
    }

    [Fact]
    public void DeviceName_NormalizationStaysInsideCrossPlatformBounds()
    {
        string source =
            string.Concat(
                Enumerable.Repeat("😀", 100));
        string normalized =
            LanDiscoveryConstants.NormalizeDeviceName(source);

        Assert.InRange(
            normalized.EnumerateRunes().Count(),
            1,
            LanDiscoveryConstants.MaxDeviceNameCodePoints);
        Assert.InRange(
            Encoding.UTF8.GetByteCount(normalized),
            1,
            LanDiscoveryConstants.MaxDeviceNameUtf8Bytes);
    }
}
