using System.Net;
using System.Net.NetworkInformation;

namespace Glide.Desktop.Network;

public enum DesktopInvitationReadinessStatus
{
    Ready,
    NoUsableIpv4,
    VirtualMachineNatOnly
}

public sealed record DesktopInvitationReadiness(
    DesktopInvitationReadinessStatus Status,
    IReadOnlyList<string> CandidateAddresses,
    string? PrimaryAddress,
    string? FailureReason = null)
{
    public bool IsReady => Status == DesktopInvitationReadinessStatus.Ready;
}

public sealed record DesktopNetworkInterface(
    string Id,
    string Name,
    NetworkInterfaceType InterfaceType,
    OperationalStatus OperationalStatus,
    IReadOnlyList<DesktopUnicastAddress> UnicastAddresses,
    IReadOnlyList<IPAddress> GatewayAddresses);

public sealed record DesktopUnicastAddress(
    IPAddress Address,
    IPAddress? IPv4Mask,
    int PrefixLength);

public interface IDesktopNetworkSnapshotProvider
{
    IReadOnlyList<DesktopNetworkInterface> GetSnapshot();
    event EventHandler? NetworkAddressChanged;
}
