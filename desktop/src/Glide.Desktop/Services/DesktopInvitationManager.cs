using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using Glide.Core.Pairing;
using Glide.Transport.Sockets;

namespace Glide.Desktop.Services;

public sealed class DesktopInvitationManager
{
    private readonly LanInvitationAuthority _authority = new();

    public LanInvitation? ActiveInvitation { get; private set; }
    public LanInvitationAuthority Authority => _authority;

    public LanInvitation CreateReceiverInvitation(
        TlsSocketListener listener,
        string deviceName,
        int listeningPort)
    {
        ArgumentNullException.ThrowIfNull(listener);

        string presence = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
        byte[] tokenBytes = RandomNumberGenerator.GetBytes(32);
        string token = Convert.ToBase64String(tokenBytes)
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');

        byte[] spkiHashBytes = SHA256.HashData(
            listener.ServerCertificate.PublicKey.ExportSubjectPublicKeyInfo());
        string spkiSha256 = Convert.ToHexString(spkiHashBytes).ToLowerInvariant();
        long expiresAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 120;

        var localIps = NetworkInterface.GetAllNetworkInterfaces()
            .Where(adapter => adapter.OperationalStatus == OperationalStatus.Up &&
                adapter.NetworkInterfaceType is not NetworkInterfaceType.Loopback and not NetworkInterfaceType.Tunnel)
            .OrderBy(adapter => adapter.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 ? 0 :
                adapter.NetworkInterfaceType == NetworkInterfaceType.Ethernet ? 1 : 2)
            .SelectMany(adapter => adapter.GetIPProperties().UnicastAddresses)
            .Select(address => address.Address)
            .Where(ip => ip.AddressFamily == AddressFamily.InterNetwork)
            .Where(ip => !IPAddress.IsLoopback(ip) && !ip.Equals(IPAddress.Any))
            .Where(ip => !ip.GetAddressBytes().AsSpan(0, 2).SequenceEqual(new byte[] { 169, 254 }))
            .Select(ip => ip.ToString())
            .Distinct(StringComparer.Ordinal)
            .ToList();

        var endpoints = localIps
            .Select(ip => new LanEndpoint(ip, listeningPort))
            .Take(LanInvitation.MaxEndpoints)
            .ToList();

        if (endpoints.Count == 0)
        {
            throw new InvalidOperationException(
                "No reachable local IPv4 address is available for a Glide pairing invitation.");
        }

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
            spkiSha256: spkiSha256,
            token: token,
            expiresAt: expiresAt.ToString(),
            ttlSeconds: 120);

        LanInvitation canonical = _authority.Publish(invitation);
        ActiveInvitation = canonical;
        return canonical;
    }

    public void RevokeActive()
    {
        _authority.RevokeActive();
        ActiveInvitation = null;
    }

    public static string EncodeInvitation(LanInvitation invitation) =>
        InvitationCodec.Encode(invitation);
}
