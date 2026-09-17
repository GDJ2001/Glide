using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using Glide.Core.Remote;

namespace Glide.Transport.Remote;

/// <summary>
/// Discovers and gathers RFC 8445 ICE candidates across active network interfaces
/// and external STUN servers.
/// </summary>
public static class IceCandidateGatherer
{
    /// <summary>
    /// Gathers local Host candidates and external Server-Reflexive candidates,
    /// sorted by descending RFC 8445 priority.
    /// </summary>
    public static async Task<IReadOnlyList<IceCandidate>> GatherCandidatesAsync(
        int localPort,
        string? stunHost = null,
        int stunPort = StunClient.DefaultStunPort,
        TimeSpan? stunTimeout = null,
        CancellationToken cancellationToken = default)
    {
        var candidates = new List<IceCandidate>();
        int foundationCounter = 1;

        // 1. Gather Host candidates from active network interfaces
        var hostIps = GetLocalUnicastAddresses();
        foreach (var ip in hostIps)
        {
            var endPoint = new IPEndPoint(ip, localPort);
            candidates.Add(IceCandidate.CreateHost(
                foundation: foundationCounter++.ToString(),
                endPoint: endPoint,
                localPref: 65535));
        }

        // 2. Gather Server-Reflexive candidate via STUN (if configured)
        if (!string.IsNullOrWhiteSpace(stunHost) && candidates.Count > 0)
        {
            try
            {
                var stunResult = await StunClient.QueryServerReflexiveAddressAsync(
                    stunHost,
                    stunPort,
                    stunTimeout ?? TimeSpan.FromSeconds(2),
                    cancellationToken);

                if (stunResult != null)
                {
                    var baseEndPoint = candidates[0].EndPoint;
                    candidates.Add(IceCandidate.CreateServerReflexive(
                        foundation: foundationCounter++.ToString(),
                        publicEndPoint: stunResult.PublicEndPoint,
                        baseEndPoint: baseEndPoint,
                        localPref: 65535));
                }
            }
            catch
            {
                // STUN failure is non-fatal; Host candidates remain available
            }
        }

        // 3. Sort candidates by descending RFC 8445 priority
        return candidates.OrderByDescending(c => c.Priority).ToList();
    }

    /// <summary>
    /// Extracts operational, non-loopback, non-APIPA IPv4 and IPv6 unicast addresses.
    /// </summary>
    public static List<IPAddress> GetLocalUnicastAddresses()
    {
        var validAddresses = new List<IPAddress>();

        try
        {
            var interfaces = NetworkInterface.GetAllNetworkInterfaces();
            foreach (var ni in interfaces)
            {
                if (ni.OperationalStatus != OperationalStatus.Up ||
                    ni.NetworkInterfaceType == NetworkInterfaceType.Loopback ||
                    ni.NetworkInterfaceType == NetworkInterfaceType.Tunnel)
                {
                    continue;
                }

                var ipProps = ni.GetIPProperties();
                foreach (var unicast in ipProps.UnicastAddresses)
                {
                    var addr = unicast.Address;

                    // Filter loopback and link-local (169.254.x.x / fe80::)
                    if (IPAddress.IsLoopback(addr)) continue;
                    if (addr.AddressFamily == AddressFamily.InterNetwork)
                    {
                        byte[] bytes = addr.GetAddressBytes();
                        if (bytes[0] == 169 && bytes[1] == 254) continue; // APIPA link-local
                        validAddresses.Add(addr);
                    }
                    else if (addr.AddressFamily == AddressFamily.InterNetworkV6 && !addr.IsIPv6LinkLocal)
                    {
                        validAddresses.Add(addr);
                    }
                }
            }
        }
        catch
        {
            // Fallback to DNS hostname lookup if network interfaces cannot be enumerated
            var hostAddresses = Dns.GetHostAddresses(Dns.GetHostName());
            validAddresses.AddRange(hostAddresses.Where(a => !IPAddress.IsLoopback(a)));
        }

        return validAddresses;
    }
}
