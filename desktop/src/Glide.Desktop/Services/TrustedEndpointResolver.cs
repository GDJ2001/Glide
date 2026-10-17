using System.Net;
using System.Net.Sockets;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Transport.Discovery;

namespace Glide.Desktop.Services;

public enum TrustedResolutionFailureKind
{
    None = 0,
    DeviceUnavailable,
    CouldNotVerifyDevice,
    SavedDeviceIdentityChanged,
    DeviceBusy
}

public sealed record TrustedEndpointCandidate(
    LanEndpoint Endpoint,
    bool IsSavedEndpoint,
    bool DisplayNameMatched,
    string? AdvertisedPresenceId = null);

/// <summary>
/// Resolves, validates, deduplicates, and ranks candidate endpoints for a saved trusted peer.
/// Replaces name-based endpoint selection across Linux and Windows desktop clients.
/// </summary>
public static class TrustedEndpointResolver
{
    public const int DefaultMaxCandidates = 8;

    public const string DeviceUnavailableMessage =
        "Couldn’t reach this device — check the local network";

    public const string CouldNotVerifyDeviceMessage =
        "Could not verify this device — check that Glide is open on the paired device";

    public const string SavedIdentityChangedMessage =
        "Identity changed — pair again";

    public const string DeviceBusyMessage =
        "This device is busy — try again";

    /// <summary>
    /// Combines the peer's saved endpoint with eligible current discovery endpoints,
    /// validates local addresses and ports, deduplicates, ranks the saved endpoint first,
    /// uses display name only as an ordering hint among discovery candidates, and bounds to at most <paramref name="maxCandidates"/>.
    /// </summary>
    public static IReadOnlyList<TrustedEndpointCandidate> ResolveCandidates(
        DesktopTrustedPeer peer,
        IEnumerable<DiscoveredPeer>? discoveredPeers,
        Func<IPAddress, bool>? addressPolicy = null,
        int maxCandidates = DefaultMaxCandidates)
    {
        ArgumentNullException.ThrowIfNull(peer);
        int limit = Math.Clamp(maxCandidates, 1, DefaultMaxCandidates);
        var policy = addressPolicy ?? IsValidCandidateAddress;

        var results = new List<TrustedEndpointCandidate>(limit);
        var seen = new HashSet<(string Host, int Port)>();

        if (TryNormalizeEndpoint(peer.LastKnownHost, peer.LastKnownPort ?? 42424, policy, out var savedEndpoint))
        {
            seen.Add((savedEndpoint.Address, savedEndpoint.Port));
            results.Add(new TrustedEndpointCandidate(
                savedEndpoint,
                IsSavedEndpoint: true,
                DisplayNameMatched: true));
        }

        if (discoveredPeers != null && results.Count < limit)
        {
            var eligibleDiscovery = new List<(DiscoveredPeer Peer, LanEndpoint Endpoint, int NameRank)>();
            foreach (var discovered in discoveredPeers)
            {
                if (discovered is null || !discovered.SupportsTrustedReconnect)
                {
                    continue;
                }

                if (!TryNormalizeEndpoint(discovered.Host, discovered.Port, policy, out var endpoint))
                {
                    continue;
                }

                int nameRank = string.Equals(discovered.DeviceName, peer.DisplayName, StringComparison.Ordinal)
                    ? 0
                    : string.Equals(discovered.DeviceName, peer.DisplayName, StringComparison.OrdinalIgnoreCase)
                        ? 1
                        : 2;

                eligibleDiscovery.Add((discovered, endpoint, nameRank));
            }

            foreach (var item in eligibleDiscovery
                .OrderBy(x => x.NameRank)
                .ThenBy(x => x.Endpoint.Address, StringComparer.Ordinal)
                .ThenBy(x => x.Endpoint.Port))
            {
                if (results.Count >= limit)
                {
                    break;
                }

                if (seen.Add((item.Endpoint.Address, item.Endpoint.Port)))
                {
                    results.Add(new TrustedEndpointCandidate(
                        item.Endpoint,
                        IsSavedEndpoint: false,
                        DisplayNameMatched: item.NameRank <= 1,
                        AdvertisedPresenceId: item.Peer.PresenceId));
                }
            }
        }

        return results;
    }

    /// <summary>
    /// Resolves candidate <see cref="LanEndpoint"/> instances for <see cref="DesktopSendRequest"/>.
    /// </summary>
    public static IReadOnlyList<LanEndpoint> ResolveEndpoints(
        DesktopTrustedPeer peer,
        IEnumerable<DiscoveredPeer>? discoveredPeers,
        Func<IPAddress, bool>? addressPolicy = null,
        int maxCandidates = DefaultMaxCandidates) =>
        ResolveCandidates(peer, discoveredPeers, addressPolicy, maxCandidates)
            .Select(c => c.Endpoint)
            .ToArray();

    /// <summary>
    /// Builds the ordered, deduplicated, validated candidate list for an outgoing <see cref="DesktopSendRequest"/>.
    /// Rank 0 is always the saved endpoint (<see cref="DesktopTrustedPeer.LastKnownHost"/>:<see cref="DesktopTrustedPeer.LastKnownPort"/>)
    /// when valid, or the request's primary Host:Port when it matches the saved peer.
    /// </summary>
    public static IReadOnlyList<TrustedEndpointCandidate> BuildSendCandidates(
        DesktopSendRequest request,
        DesktopTrustedPeer enrolledPeer,
        int maxCandidates = DefaultMaxCandidates)
    {
        ArgumentNullException.ThrowIfNull(request);
        ArgumentNullException.ThrowIfNull(enrolledPeer);

        int limit = Math.Clamp(maxCandidates, 1, DefaultMaxCandidates);
        var results = new List<TrustedEndpointCandidate>(limit);
        var seen = new HashSet<(string Host, int Port)>();

        bool hasSaved = TryNormalizeEndpoint(
            enrolledPeer.LastKnownHost,
            enrolledPeer.LastKnownPort ?? 42424,
            IsValidCandidateAddress,
            out var savedEndpoint);

        if (hasSaved)
        {
            seen.Add((savedEndpoint.Address, savedEndpoint.Port));
            results.Add(new TrustedEndpointCandidate(
                savedEndpoint,
                IsSavedEndpoint: true,
                DisplayNameMatched: true));
        }

        int fallbackPort = request.Port > 0 ? request.Port : enrolledPeer.LastKnownPort ?? 42424;
        if (results.Count < limit &&
            TryNormalizeEndpoint(request.Host, fallbackPort, IsValidCandidateAddress, out var primaryEndpoint) &&
            seen.Add((primaryEndpoint.Address, primaryEndpoint.Port)))
        {
            bool isPrimarySaved = !hasSaved || request.CandidateEndpoints == null || request.CandidateEndpoints.Count <= 1;
            results.Add(new TrustedEndpointCandidate(
                primaryEndpoint,
                IsSavedEndpoint: isPrimarySaved,
                DisplayNameMatched: true));
        }

        if (request.CandidateEndpoints != null)
        {
            foreach (var candidate in request.CandidateEndpoints)
            {
                if (results.Count >= limit)
                {
                    break;
                }

                if (candidate is null)
                {
                    continue;
                }

                if (TryNormalizeEndpoint(candidate.Address, candidate.Port, IsValidCandidateAddress, out var normalized) &&
                    seen.Add((normalized.Address, normalized.Port)))
                {
                    bool isSavedMatch = hasSaved &&
                        string.Equals(normalized.Address, savedEndpoint.Address, StringComparison.Ordinal) &&
                        normalized.Port == savedEndpoint.Port;
                    results.Add(new TrustedEndpointCandidate(
                        normalized,
                        IsSavedEndpoint: isSavedMatch,
                        DisplayNameMatched: false));
                }
            }
        }

        return results;
    }

    public static TrustedResolutionFailureKind ClassifyExhaustedCandidates(
        bool sawBusyOnSavedOrVerified,
        bool sawSavedEndpointIdentityChanged,
        bool sawUnrelatedCandidateIdentity)
    {
        if (sawBusyOnSavedOrVerified)
        {
            return TrustedResolutionFailureKind.DeviceBusy;
        }

        if (sawSavedEndpointIdentityChanged)
        {
            return TrustedResolutionFailureKind.SavedDeviceIdentityChanged;
        }

        if (sawUnrelatedCandidateIdentity)
        {
            return TrustedResolutionFailureKind.CouldNotVerifyDevice;
        }

        return TrustedResolutionFailureKind.DeviceUnavailable;
    }

    public static DesktopTransferResult CreateFailureResult(TrustedResolutionFailureKind kind) =>
        kind switch
        {
            TrustedResolutionFailureKind.DeviceBusy =>
                DesktopTransferResult.Failed(DeviceBusyMessage, TransferErrorCategory.Protocol),
            TrustedResolutionFailureKind.SavedDeviceIdentityChanged =>
                DesktopTransferResult.Failed(SavedIdentityChangedMessage, TransferErrorCategory.Security),
            TrustedResolutionFailureKind.CouldNotVerifyDevice =>
                DesktopTransferResult.Failed(CouldNotVerifyDeviceMessage, TransferErrorCategory.Security),
            _ =>
                DesktopTransferResult.Failed(DeviceUnavailableMessage, TransferErrorCategory.Network)
        };

    public static bool TryNormalizeEndpoint(
        string? host,
        int port,
        Func<IPAddress, bool>? addressPolicy,
        out LanEndpoint endpoint)
    {
        endpoint = default!;
        if (string.IsNullOrWhiteSpace(host) || port is < 1 or > 65535)
        {
            return false;
        }

        string trimmed = host.Trim();
        if (!LanEndpoint.IsValidAddress(trimmed) ||
            !IPAddress.TryParse(trimmed, out IPAddress? ip) ||
            ip.AddressFamily != AddressFamily.InterNetwork)
        {
            return false;
        }

        var policy = addressPolicy ?? IsValidCandidateAddress;
        if (!policy(ip))
        {
            return false;
        }

        endpoint = new LanEndpoint(ip.ToString(), port);
        return true;
    }

    public static bool IsValidCandidateAddress(IPAddress ip)
    {
        if (ip.AddressFamily != AddressFamily.InterNetwork)
        {
            return false;
        }

        if (ip.Equals(IPAddress.Any) || ip.Equals(IPAddress.Broadcast))
        {
            return false;
        }

        if (IPAddress.IsLoopback(ip))
        {
            return true;
        }

        byte[] b = ip.GetAddressBytes();
        // Reject IPv4 link-local 169.254.0.0/16 and multicast 224.0.0.0/4
        if (b[0] == 169 && b[1] == 254) return false;
        if (b[0] is >= 224 and <= 239) return false;

        // Allow RFC 1918 private ranges (10/8, 172.16/12, 192.168/16) and CGNAT/LAN overlay 100.64/10
        if (b[0] == 10) return true;
        if (b[0] == 172 && b[1] is >= 16 and <= 31) return true;
        if (b[0] == 192 && b[1] == 168) return true;
        if (b[0] == 100 && b[1] is >= 64 and <= 127) return true;

        return false;
    }
}
