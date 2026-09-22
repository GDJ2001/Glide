using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using Glide.Core.Pairing;
using Glide.Transport.Sockets;
using Glide.Transport.Web;

namespace Glide.Windows.UI.Services;

/// <summary>
/// Invitation lifecycle service for LAN pairing (HARD-032).
///
/// Handles:
/// - Invitation creation and publication via LanInvitationAuthority
/// - MDNS advertising coordination
/// - Invitation URI encoding
/// </summary>
public sealed class InvitationService
{
    private readonly LanInvitationAuthority _authority = new();

    public LanInvitation? ActiveInvitation { get; private set; }

    /// <summary>
    /// Provides access to the underlying invitation authority for validation during receives.
    /// </summary>
    public LanInvitationAuthority Authority => _authority;

    /// <summary>
    /// Creates a new receiver invitation with the given parameters.
    /// </summary>
    public LanInvitation CreateReceiverInvitation(
        TlsSocketListener listener,
        string deviceName,
        int listeningPort)
    {
        string presence = GeneratePresenceId();
        byte[] tokenBytes = RandomNumberGenerator.GetBytes(32);
        string token = Convert.ToBase64String(tokenBytes)
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');
        byte[] spkiHashBytes = SHA256.HashData(
            listener.ServerCertificate.PublicKey.ExportSubjectPublicKeyInfo());
        string spkiSha256 = Convert.ToHexString(spkiHashBytes).ToLowerInvariant();
        long expiresAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 120;

        var localIps = Dns.GetHostAddresses(Dns.GetHostName())
            .Where(ip => ip.AddressFamily == AddressFamily.InterNetwork)
            .Select(ip => ip.ToString())
            .ToList();

        var endpoints = localIps
            .Select(ip => new LanEndpoint(ip, listeningPort))
            .Take(LanInvitation.MaxEndpoints)
            .ToList();

        if (endpoints.Count == 0)
        {
            endpoints.Add(new LanEndpoint("127.0.0.1", listeningPort));
        }

        var invitation = new LanInvitation(
            invitationId: Guid.NewGuid().ToString("D").ToLowerInvariant(),
            presence: presence,
            name: deviceName,
            platform: "windows",
            endpoints: endpoints,
            spkiSha256: spkiSha256,
            token: token,
            expiresAt: expiresAt.ToString(),
            ttlSeconds: 120);

        LanInvitation canonical = _authority.Publish(invitation);
        ActiveInvitation = canonical;
        return canonical;
    }

    /// <summary>
    /// Revokes the active invitation.
    /// </summary>
    public void RevokeActive()
    {
        _authority.RevokeActive();
        ActiveInvitation = null;
    }

    /// <summary>
    /// Encodes an invitation to a URI string.
    /// </summary>
    public static string EncodeInvitation(LanInvitation invitation) =>
        InvitationCodec.Encode(invitation);

    private static string GeneratePresenceId() =>
        Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
}
