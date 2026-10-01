using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using Glide.Core.Models;

namespace Glide.Core.Pairing;

/// <summary>
/// Immutable validated QR pairing invitation representation (LAN-006, Section 8).
/// Enforces strict Section-8 schema constraints, zero platform imports,
/// and redacts the 32-byte shared token from ToString() to avoid secret leakage.
/// </summary>
public sealed record LanInvitation
{
    public const int MaxNameCodePoints = 64;
    public const int MaxNameUtf8Bytes = 128;
    public const int MaxEndpoints = 4;

    private static readonly HashSet<string> AllowedPlatforms = new(StringComparer.Ordinal)
    {
        "android", "windows", "macos", "ios", "linux", "web", "unknown"
    };

    private static readonly Regex PresenceRegex = new(@"^[0-9a-f]{32}$", RegexOptions.Compiled);
    private static readonly Regex TokenRegex = new(@"^[A-Za-z0-9_-]{43}$", RegexOptions.Compiled);
    private static readonly Regex ExpiresAtRegex = new(@"^[1-9][0-9]*$", RegexOptions.Compiled);

    public int V { get; init; } = 1;
    public string Transport { get; init; } = "lan-tls";
    public int Profile { get; init; } = 1;
    public int Protocol { get; init; } = 1;
    public string InvitationId { get; init; }
    public string Presence { get; init; }
    public string Name { get; init; }
    public string Platform { get; init; }
    public IReadOnlyList<LanEndpoint> Endpoints { get; init; }
    public string SpkiSha256 { get; init; }
    public string Token { get; init; }
    public string ExpiresAt { get; init; }
    public int TtlSeconds { get; init; } = 120;
    public string InvitationHash { get; init; } = "";

    public LanInvitation(
        string invitationId,
        string presence,
        string name,
        string platform,
        IReadOnlyList<LanEndpoint> endpoints,
        string spkiSha256,
        string token,
        string expiresAt,
        int ttlSeconds = 120,
        string invitationHash = "",
        int v = 1,
        string transport = "lan-tls",
        int profile = 1,
        int protocol = 1)
    {
        if (v != 1) throw new ArgumentException($"Unsupported invitation version {v}, expected 1", nameof(v));
        if (transport != "lan-tls") throw new ArgumentException($"Unsupported transport '{transport}', expected 'lan-tls'", nameof(transport));
        if (profile != 1) throw new ArgumentException($"Unsupported LAN profile {profile}, expected 1", nameof(profile));
        if (protocol != 1) throw new ArgumentException($"Unsupported protocol version {protocol}, expected 1", nameof(protocol));

        if (!IdentifierValidation.IsValidUuidV4(invitationId))
        {
            throw new ArgumentException($"invitationId must be a canonical lowercase UUIDv4, got: '{invitationId}'", nameof(invitationId));
        }

        if (!PresenceRegex.IsMatch(presence))
        {
            throw new ArgumentException("presence must be 32 lowercase hex characters", nameof(presence));
        }

        if (string.IsNullOrWhiteSpace(name))
        {
            throw new ArgumentException("name must not be blank", nameof(name));
        }

        int codePoints = 0;
        var enumerator = StringInfo.GetTextElementEnumerator(name);
        while (enumerator.MoveNext()) codePoints++;
        if (codePoints > MaxNameCodePoints)
        {
            throw new ArgumentException($"name exceeds maximum {MaxNameCodePoints} code points", nameof(name));
        }

        if (Encoding.UTF8.GetByteCount(name) > MaxNameUtf8Bytes)
        {
            throw new ArgumentException($"name exceeds maximum {MaxNameUtf8Bytes} UTF-8 bytes", nameof(name));
        }

        if (!AllowedPlatforms.Contains(platform))
        {
            throw new ArgumentException($"platform '{platform}' is not allowed, must be one of [{string.Join(", ", AllowedPlatforms)}]", nameof(platform));
        }

        if (endpoints == null || endpoints.Count is < 1 or > MaxEndpoints)
        {
            throw new ArgumentException($"endpoints must contain 1..{MaxEndpoints} items, got: {endpoints?.Count ?? 0}", nameof(endpoints));
        }

        if (endpoints.Distinct().Count() != endpoints.Count)
        {
            throw new ArgumentException("endpoints must be unique, duplicate found", nameof(endpoints));
        }

        if (!IdentifierValidation.IsValidSha256(spkiSha256))
        {
            throw new ArgumentException("spkiSha256 must be 64 lowercase hex characters", nameof(spkiSha256));
        }

        if (!TokenRegex.IsMatch(token))
        {
            throw new ArgumentException("token must be 43 unpadded base64url characters encoding 32 bytes", nameof(token));
        }

        if (!ExpiresAtRegex.IsMatch(expiresAt))
        {
            throw new ArgumentException($"expiresAt must be a positive decimal epoch seconds string, got: '{expiresAt}'", nameof(expiresAt));
        }

        if (ttlSeconds <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(ttlSeconds), $"ttlSeconds must be positive, got: {ttlSeconds}");
        }

        if (!string.IsNullOrEmpty(invitationHash) && !IdentifierValidation.IsValidSha256(invitationHash))
        {
            throw new ArgumentException("invitationHash must be 64 lowercase hex characters if present", nameof(invitationHash));
        }

        V = v;
        Transport = transport;
        Profile = profile;
        Protocol = protocol;
        InvitationId = invitationId;
        Presence = presence;
        Name = name;
        Platform = platform;
        Endpoints = endpoints;
        SpkiSha256 = spkiSha256;
        Token = token;
        ExpiresAt = expiresAt;
        TtlSeconds = ttlSeconds;
        InvitationHash = invitationHash;
    }

    /// <summary>
    /// Checks if the invitation has expired according to current UTC epoch seconds.
    /// </summary>
    public bool IsExpired(long currentEpochSeconds = 0)
    {
        if (currentEpochSeconds <= 0)
        {
            currentEpochSeconds = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        }
        if (long.TryParse(ExpiresAt, out long exp))
        {
            return currentEpochSeconds > exp;
        }
        return true;
    }

    /// <summary>
    /// Redacts the shared secret token to prevent accidental logging or exposure.
    /// </summary>
    public override string ToString()
    {
        return $"LanInvitation(v={V}, transport='{Transport}', profile={Profile}, protocol={Protocol}, " +
               $"invitationId=[REDACTED], presence=[REDACTED], name=[REDACTED], platform='{Platform}', " +
               $"endpoints=[REDACTED], spkiSha256=[REDACTED], token=[REDACTED], " +
               $"expiresAt='{ExpiresAt}', ttlSeconds={TtlSeconds}, invitationHash=[REDACTED])";
    }
}
