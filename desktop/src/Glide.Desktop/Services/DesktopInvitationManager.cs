using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using Glide.Core.Pairing;
using Glide.Desktop.Network;
using Glide.Transport.Sockets;

namespace Glide.Desktop.Services;

public sealed class DesktopInvitationManager
{
    public const string VirtualMachineNatGuidance =
        "This virtual machine is using NAT, so phones on your network cannot reach it. Shut down the VM, set Adapter 1 to Bridged Adapter, restart Glide, and create a new QR code.";

    private readonly LanInvitationAuthority _authority = new();
    private readonly IDesktopNetworkSnapshotProvider _snapshotProvider;

    public DesktopInvitationManager(IDesktopNetworkSnapshotProvider? snapshotProvider = null)
    {
        _snapshotProvider = snapshotProvider ?? SystemNetworkSnapshotProvider.Instance;
        _snapshotProvider.NetworkAddressChanged += OnNetworkAddressChanged;
    }

    private void OnNetworkAddressChanged(object? sender, EventArgs e)
    {
        RevokeActive();
        RevokeActivePairWindow();
    }

    public LanInvitation? ActiveInvitation { get; private set; }
    public PairReadyWindowAuthority? ActivePairWindow { get; private set; }
    public LanInvitationAuthority Authority => _authority;
    public IDesktopNetworkSnapshotProvider SnapshotProvider => _snapshotProvider;

    public DesktopInvitationReadiness AssessReadiness()
    {
        var snapshot = _snapshotProvider.GetSnapshot();
        return EvaluateReadiness(snapshot);
    }

    public static DesktopInvitationReadiness EvaluateReadiness(IEnumerable<DesktopNetworkInterface> interfaces)
    {
        ArgumentNullException.ThrowIfNull(interfaces);

        var validLanCandidates = new List<CandidateEndpoint>();
        var natCandidates = new List<CandidateEndpoint>();

        foreach (var iface in interfaces)
        {
            if (iface.OperationalStatus != OperationalStatus.Up)
                continue;

            if (iface.InterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel)
                continue;

            bool hasIpv4Gateway = iface.GatewayAddresses.Any(g =>
                g.AddressFamily == AddressFamily.InterNetwork &&
                !IPAddress.IsLoopback(g) &&
                !g.Equals(IPAddress.Any));

            bool hasVirtualBoxNatGateway = iface.GatewayAddresses.Any(IsVirtualBoxNatGateway);

            foreach (var unicast in iface.UnicastAddresses)
            {
                var ip = unicast.Address;
                if (ip.AddressFamily != AddressFamily.InterNetwork)
                    continue;

                if (IPAddress.IsLoopback(ip) || ip.Equals(IPAddress.Any))
                    continue;

                if (IsLinkLocal(ip))
                    continue;

                if (IsVirtualBoxNatSubnet(ip) && hasVirtualBoxNatGateway)
                {
                    natCandidates.Add(new CandidateEndpoint(ip, hasIpv4Gateway, iface.InterfaceType));
                }
                else
                {
                    validLanCandidates.Add(new CandidateEndpoint(ip, hasIpv4Gateway, iface.InterfaceType));
                }
            }
        }

        // If LAN candidates coexist with NAT candidates, omit NAT and prefer LAN with gateway
        if (validLanCandidates.Count > 0)
        {
            var orderedAddresses = validLanCandidates
                .OrderBy(c => c.HasGateway ? 0 : 1)
                .ThenBy(c => c.InterfaceType == NetworkInterfaceType.Wireless80211 ? 0 :
                             c.InterfaceType == NetworkInterfaceType.Ethernet ? 1 : 2)
                .Select(c => c.Address.ToString())
                .Distinct(StringComparer.Ordinal)
                .ToList();

            return new DesktopInvitationReadiness(
                Status: DesktopInvitationReadinessStatus.Ready,
                CandidateAddresses: orderedAddresses,
                PrimaryAddress: orderedAddresses[0]);
        }

        // NAT-only state detected
        if (natCandidates.Count > 0)
        {
            return new DesktopInvitationReadiness(
                Status: DesktopInvitationReadinessStatus.VirtualMachineNatOnly,
                CandidateAddresses: Array.Empty<string>(),
                PrimaryAddress: null,
                FailureReason: VirtualMachineNatGuidance);
        }

        // No usable IPv4 interfaces
        return new DesktopInvitationReadiness(
            Status: DesktopInvitationReadinessStatus.NoUsableIpv4,
            CandidateAddresses: Array.Empty<string>(),
            PrimaryAddress: null,
            FailureReason: "No usable local IPv4 address is available for a Glide pairing invitation.");
    }

    public LanInvitation CreateReceiverInvitation(
        TlsSocketListener listener,
        string deviceName,
        int listeningPort)
    {
        ArgumentNullException.ThrowIfNull(listener);

        byte[] spkiHashBytes = SHA256.HashData(
            listener.ServerCertificate.PublicKey.ExportSubjectPublicKeyInfo());
        string spkiSha256 = Convert.ToHexString(spkiHashBytes).ToLowerInvariant();

        return CreateInvitation(spkiSha256, deviceName, listeningPort);
    }

    public LanInvitation CreateInvitation(
        string spkiSha256,
        string deviceName,
        int listeningPort)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(spkiSha256);

        var readiness = AssessReadiness();
        if (readiness.Status != DesktopInvitationReadinessStatus.Ready || readiness.CandidateAddresses.Count == 0)
        {
            throw new InvalidOperationException(
                readiness.FailureReason ?? "No reachable local IPv4 address is available for a Glide pairing invitation.");
        }

        string presence = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
        byte[] tokenBytes = RandomNumberGenerator.GetBytes(32);
        string token = Convert.ToBase64String(tokenBytes)
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');

        long expiresAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 120;

        var endpoints = readiness.CandidateAddresses
            .Select(ip => new LanEndpoint(ip, listeningPort))
            .Take(LanInvitation.MaxEndpoints)
            .ToList();

        string platformName = OperatingSystem.IsWindows()
            ? "windows"
            : OperatingSystem.IsLinux()
                ? "linux"
                : OperatingSystem.IsMacOS()
                    ? "macos"
                    : "desktop";

        var invitation = new LanInvitation(
            invitationId: Guid.NewGuid().ToString("D").ToLowerInvariant(),
            presence: presence,
            name: string.IsNullOrWhiteSpace(deviceName) ? Environment.MachineName : deviceName,
            platform: platformName,
            endpoints: endpoints,
            spkiSha256: spkiSha256.ToLowerInvariant(),
            token: token,
            expiresAt: expiresAt.ToString(),
            ttlSeconds: 120);

        LanInvitation canonical = _authority.Publish(invitation);
        ActiveInvitation = canonical;
        return canonical;
    }

    public PairReadyWindowAuthority CreatePairReadyWindow(
        TlsSocketListener listener,
        string deviceName,
        int listeningPort,
        TimeSpan? ttl = null,
        string? fixedPinForTest = null)
    {
        ArgumentNullException.ThrowIfNull(listener);

        byte[] spkiHashBytes = SHA256.HashData(
            listener.ServerCertificate.PublicKey.ExportSubjectPublicKeyInfo());
        string spkiSha256 = Convert.ToHexString(spkiHashBytes).ToLowerInvariant();

        var readiness = AssessReadiness();
        if (readiness.Status != DesktopInvitationReadinessStatus.Ready || readiness.CandidateAddresses.Count == 0)
        {
            throw new InvalidOperationException(
                readiness.FailureReason ?? "No reachable local IPv4 address is available for a Glide pairing invitation.");
        }

        string presence = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
        var endpoints = readiness.CandidateAddresses
            .Select(ip => new LanEndpoint(ip, listeningPort))
            .Take(LanInvitation.MaxEndpoints)
            .ToList();

        string platformName = OperatingSystem.IsWindows()
            ? "windows"
            : OperatingSystem.IsLinux()
                ? "linux"
                : OperatingSystem.IsMacOS()
                    ? "macos"
                    : "desktop";

        RevokeActivePairWindow();
        var window = new PairReadyWindowAuthority(
            presenceId: presence,
            deviceName: string.IsNullOrWhiteSpace(deviceName) ? Environment.MachineName : deviceName,
            platform: platformName,
            endpoints: endpoints,
            serverSpkiSha256Hex: spkiSha256,
            ttl: ttl,
            fixedPinForTest: fixedPinForTest);
        ActivePairWindow = window;
        return window;
    }

    public void RevokeActivePairWindow()
    {
        ActivePairWindow?.Dispose();
        ActivePairWindow = null;
    }

    public void RevokeActive()
    {
        _authority.RevokeActive();
        ActiveInvitation = null;
    }

    public static string EncodeInvitation(LanInvitation invitation) =>
        InvitationCodec.Encode(invitation);

    private static bool IsLinkLocal(IPAddress ip)
    {
        Span<byte> bytes = stackalloc byte[4];
        return ip.TryWriteBytes(bytes, out _) && bytes[0] == 169 && bytes[1] == 254;
    }

    private static bool IsVirtualBoxNatSubnet(IPAddress ip)
    {
        Span<byte> bytes = stackalloc byte[4];
        return ip.TryWriteBytes(bytes, out _) && bytes[0] == 10 && bytes[1] == 0 && bytes[2] == 2;
    }

    private static bool IsVirtualBoxNatGateway(IPAddress ip)
    {
        Span<byte> bytes = stackalloc byte[4];
        return ip.TryWriteBytes(bytes, out _) && bytes[0] == 10 && bytes[1] == 0 && bytes[2] == 2 && bytes[3] == 2;
    }

    private sealed record CandidateEndpoint(
        IPAddress Address,
        bool HasGateway,
        NetworkInterfaceType InterfaceType);
}
