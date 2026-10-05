using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using Glide.Desktop.Network;
using Glide.Desktop.Services;
using Xunit;

namespace Glide.Tests;

public sealed class DesktopInvitationManagerTests
{
    private sealed class TestNetworkSnapshotProvider : IDesktopNetworkSnapshotProvider
    {
        private List<DesktopNetworkInterface> _interfaces = new();

        public event EventHandler? NetworkAddressChanged;

        public void SetInterfaces(List<DesktopNetworkInterface> interfaces)
        {
            _interfaces = interfaces;
        }

        public void FireNetworkAddressChanged()
        {
            NetworkAddressChanged?.Invoke(this, EventArgs.Empty);
        }

        public IReadOnlyList<DesktopNetworkInterface> GetSnapshot() => _interfaces;
    }

    [Fact]
    public void NatOnly_ReturnsVirtualMachineNatOnly()
    {
        var iface = new DesktopNetworkInterface(
            Id: "eth0",
            Name: "eth0",
            InterfaceType: NetworkInterfaceType.Ethernet,
            OperationalStatus: OperationalStatus.Up,
            UnicastAddresses: new[]
            {
                new DesktopUnicastAddress(IPAddress.Parse("10.0.2.15"), IPAddress.Parse("255.255.255.0"), 24)
            },
            GatewayAddresses: new[] { IPAddress.Parse("10.0.2.2") }
        );

        var readiness = DesktopInvitationManager.EvaluateReadiness(new[] { iface });

        Assert.Equal(DesktopInvitationReadinessStatus.VirtualMachineNatOnly, readiness.Status);
        Assert.Null(readiness.PrimaryAddress);
        Assert.Empty(readiness.CandidateAddresses);
        Assert.Equal(DesktopInvitationManager.VirtualMachineNatGuidance, readiness.FailureReason);
    }

    [Fact]
    public void Bridged_ReturnsReady()
    {
        var iface = new DesktopNetworkInterface(
            Id: "eth0",
            Name: "eth0",
            InterfaceType: NetworkInterfaceType.Ethernet,
            OperationalStatus: OperationalStatus.Up,
            UnicastAddresses: new[]
            {
                new DesktopUnicastAddress(IPAddress.Parse("192.168.0.125"), IPAddress.Parse("255.255.255.0"), 24)
            },
            GatewayAddresses: new[] { IPAddress.Parse("192.168.0.1") }
        );

        var readiness = DesktopInvitationManager.EvaluateReadiness(new[] { iface });

        Assert.Equal(DesktopInvitationReadinessStatus.Ready, readiness.Status);
        Assert.Equal("192.168.0.125", readiness.PrimaryAddress);
        Assert.Contains("192.168.0.125", readiness.CandidateAddresses);
    }

    [Fact]
    public void MixedNatAndLan_OmitsNatCandidate_PrefersLanWithGateway()
    {
        var natIface = new DesktopNetworkInterface(
            Id: "eth0",
            Name: "eth0",
            InterfaceType: NetworkInterfaceType.Ethernet,
            OperationalStatus: OperationalStatus.Up,
            UnicastAddresses: new[]
            {
                new DesktopUnicastAddress(IPAddress.Parse("10.0.2.15"), IPAddress.Parse("255.255.255.0"), 24)
            },
            GatewayAddresses: new[] { IPAddress.Parse("10.0.2.2") }
        );

        var lanIface = new DesktopNetworkInterface(
            Id: "wlan0",
            Name: "wlan0",
            InterfaceType: NetworkInterfaceType.Wireless80211,
            OperationalStatus: OperationalStatus.Up,
            UnicastAddresses: new[]
            {
                new DesktopUnicastAddress(IPAddress.Parse("192.168.1.100"), IPAddress.Parse("255.255.255.0"), 24)
            },
            GatewayAddresses: new[] { IPAddress.Parse("192.168.1.1") }
        );

        var readiness = DesktopInvitationManager.EvaluateReadiness(new[] { natIface, lanIface });

        Assert.Equal(DesktopInvitationReadinessStatus.Ready, readiness.Status);
        Assert.Equal("192.168.1.100", readiness.PrimaryAddress);
        Assert.DoesNotContain("10.0.2.15", readiness.CandidateAddresses);
        Assert.Contains("192.168.1.100", readiness.CandidateAddresses);
    }

    [Fact]
    public void LinkLocalAndLoopback_FailClosed_ReturnsNoUsableIpv4()
    {
        var loopbackIface = new DesktopNetworkInterface(
            Id: "lo",
            Name: "lo",
            InterfaceType: NetworkInterfaceType.Loopback,
            OperationalStatus: OperationalStatus.Up,
            UnicastAddresses: new[]
            {
                new DesktopUnicastAddress(IPAddress.Parse("127.0.0.1"), IPAddress.Parse("255.0.0.0"), 8)
            },
            GatewayAddresses: Array.Empty<IPAddress>()
        );

        var linkLocalIface = new DesktopNetworkInterface(
            Id: "eth0",
            Name: "eth0",
            InterfaceType: NetworkInterfaceType.Ethernet,
            OperationalStatus: OperationalStatus.Up,
            UnicastAddresses: new[]
            {
                new DesktopUnicastAddress(IPAddress.Parse("169.254.10.20"), IPAddress.Parse("255.255.0.0"), 16)
            },
            GatewayAddresses: Array.Empty<IPAddress>()
        );

        var readiness = DesktopInvitationManager.EvaluateReadiness(new[] { loopbackIface, linkLocalIface });

        Assert.Equal(DesktopInvitationReadinessStatus.NoUsableIpv4, readiness.Status);
        Assert.Null(readiness.PrimaryAddress);
        Assert.Empty(readiness.CandidateAddresses);
    }

    [Fact]
    public void DebouncedNetworkChange_RevokesActiveInvitation()
    {
        var provider = new TestNetworkSnapshotProvider();
        provider.SetInterfaces(new List<DesktopNetworkInterface>
        {
            new(
                Id: "eth0",
                Name: "eth0",
                InterfaceType: NetworkInterfaceType.Ethernet,
                OperationalStatus: OperationalStatus.Up,
                UnicastAddresses: new[]
                {
                    new DesktopUnicastAddress(IPAddress.Parse("192.168.0.50"), IPAddress.Parse("255.255.255.0"), 24)
                },
                GatewayAddresses: new[] { IPAddress.Parse("192.168.0.1") }
            )
        });

        var manager = new DesktopInvitationManager(provider);

        var invitation = manager.CreateInvitation(
            spkiSha256: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            deviceName: "Desktop PC",
            listeningPort: 50000
        );

        Assert.NotNull(invitation);
        Assert.NotNull(manager.ActiveInvitation);

        // Network change event fires
        provider.FireNetworkAddressChanged();

        Assert.Null(manager.ActiveInvitation);
    }
}
