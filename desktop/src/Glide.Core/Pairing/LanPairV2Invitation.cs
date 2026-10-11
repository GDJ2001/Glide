using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Glide.Core.Models;

namespace Glide.Core.Pairing;

/// <summary>
/// Immutable validated Profile 2 pair-only QR/link invitation (`glide://pair/v2#...`).
/// Enforces `v=2`, `purpose="pair-only"`, `profile=2`, 120-second max TTL, and redacts secrets in ToString().
/// </summary>
public sealed record LanPairV2Invitation
{
    public const string RequiredPurpose = "pair-only";
    public const int MaxNameCodePoints = 64;
    public const int MaxNameUtf8Bytes = 128;
    public const int MaxEndpoints = 4;
    public const int MaxTtlSeconds = 120;

    private static readonly HashSet<string> AllowedPlatforms = new(StringComparer.Ordinal)
    {
        "android", "windows", "macos", "ios", "linux", "unknown"
    };

    private static readonly Regex PresenceRegex = new(@"^[0-9a-f]{32}$", RegexOptions.Compiled);
    private static readonly Regex TokenRegex = new(@"^[A-Za-z0-9_-]{43}$", RegexOptions.Compiled);
    private static readonly Regex ExpiresAtRegex = new(@"^[1-9][0-9]*$", RegexOptions.Compiled);

    public int V { get; init; } = 2;
    public string Purpose { get; init; } = RequiredPurpose;
    public string Transport { get; init; } = "lan-tls";
    public int Profile { get; init; } = 2;
    public int Protocol { get; init; } = 1;
    public string InvitationId { get; init; }
    public string Presence { get; init; }
    public string Name { get; init; }
    public string Platform { get; init; }
    public IReadOnlyList<LanEndpoint> Endpoints { get; init; }
    public string SpkiSha256 { get; init; }
    public string Token { get; init; }
    public string ExpiresAt { get; init; }
    public int TtlSeconds { get; init; } = MaxTtlSeconds;
    public string InvitationHash { get; init; } = "";

    public LanPairV2Invitation(
        string invitationId,
        string presence,
        string name,
        string platform,
        IReadOnlyList<LanEndpoint> endpoints,
        string spkiSha256,
        string token,
        string expiresAt,
        int ttlSeconds = MaxTtlSeconds,
        string invitationHash = "",
        int v = 2,
        string purpose = RequiredPurpose,
        string transport = "lan-tls",
        int profile = 2,
        int protocol = 1)
    {
        if (v != 2) throw new ArgumentException($"Unsupported pair-only invitation version {v}, expected 2", nameof(v));
        if (!string.Equals(purpose, RequiredPurpose, StringComparison.Ordinal))
            throw new ArgumentException($"Unsupported invitation purpose '{purpose}', expected '{RequiredPurpose}'", nameof(purpose));
        if (!string.Equals(transport, "lan-tls", StringComparison.Ordinal))
            throw new ArgumentException($"Unsupported transport '{transport}', expected 'lan-tls'", nameof(transport));
        if (profile != 2) throw new ArgumentException($"Unsupported LAN profile {profile}, expected 2", nameof(profile));
        if (protocol != 1) throw new ArgumentException($"Unsupported protocol version {protocol}, expected 1", nameof(protocol));

        if (!IdentifierValidation.IsValidUuidV4(invitationId))
            throw new ArgumentException($"invitationId must be a canonical lowercase UUIDv4, got: '{invitationId}'", nameof(invitationId));

        if (!PresenceRegex.IsMatch(presence))
            throw new ArgumentException("presence must be 32 lowercase hex characters", nameof(presence));

        if (string.IsNullOrWhiteSpace(name))
            throw new ArgumentException("name must not be blank", nameof(name));

        int codePoints = 0;
        var enumerator = StringInfo.GetTextElementEnumerator(name);
        while (enumerator.MoveNext()) codePoints++;
        if (codePoints > MaxNameCodePoints)
            throw new ArgumentException($"name exceeds maximum {MaxNameCodePoints} code points", nameof(name));

        if (Encoding.UTF8.GetByteCount(name) > MaxNameUtf8Bytes)
            throw new ArgumentException($"name exceeds maximum {MaxNameUtf8Bytes} UTF-8 bytes", nameof(name));

        if (!AllowedPlatforms.Contains(platform))
            throw new ArgumentException($"platform '{platform}' is not allowed", nameof(platform));

        if (endpoints == null || endpoints.Count is < 1 or > MaxEndpoints)
            throw new ArgumentException($"endpoints must contain 1..{MaxEndpoints} items", nameof(endpoints));

        if (endpoints.Distinct().Count() != endpoints.Count)
            throw new ArgumentException("endpoints must be unique, duplicate found", nameof(endpoints));

        if (!IdentifierValidation.IsValidSha256(spkiSha256))
            throw new ArgumentException("spkiSha256 must be 64 lowercase hex characters", nameof(spkiSha256));

        if (!TokenRegex.IsMatch(token))
            throw new ArgumentException("token must be 43 unpadded base64url characters encoding 32 bytes", nameof(token));

        byte[] decodedToken = InvitationCodec.FromUnpaddedBase64Url(token);
        if (decodedToken.Length != 32 || !string.Equals(InvitationCodec.ToUnpaddedBase64Url(decodedToken), token, StringComparison.Ordinal))
            throw new ArgumentException("token must be canonically encoded 32-byte base64url without trailing bit residue", nameof(token));

        if (!ExpiresAtRegex.IsMatch(expiresAt))
            throw new ArgumentException($"expiresAt must be a positive decimal epoch seconds string", nameof(expiresAt));

        if (ttlSeconds is <= 0 or > MaxTtlSeconds)
            throw new ArgumentOutOfRangeException(nameof(ttlSeconds), $"ttlSeconds must be in 1..{MaxTtlSeconds}, got: {ttlSeconds}");

        if (!string.IsNullOrEmpty(invitationHash) && !IdentifierValidation.IsValidSha256(invitationHash))
            throw new ArgumentException("invitationHash must be 64 lowercase hex characters if present", nameof(invitationHash));

        V = v;
        Purpose = purpose;
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

    public static LanPairV2Invitation CreateFresh(
        string presence,
        string name,
        string platform,
        IReadOnlyList<LanEndpoint> endpoints,
        string spkiSha256,
        int ttlSeconds = MaxTtlSeconds,
        long? nowEpochSeconds = null)
    {
        long now = nowEpochSeconds ?? DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        long expires = now + ttlSeconds;
        byte[] tokenBytes = RandomNumberGenerator.GetBytes(32);
        string token = Convert.ToBase64String(tokenBytes)
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');
        CryptographicOperations.ZeroMemory(tokenBytes);

        var draft = new LanPairV2Invitation(
            invitationId: Guid.NewGuid().ToString("D").ToLowerInvariant(),
            presence: presence,
            name: name,
            platform: platform,
            endpoints: endpoints,
            spkiSha256: spkiSha256,
            token: token,
            expiresAt: expires.ToString(CultureInfo.InvariantCulture),
            ttlSeconds: ttlSeconds);
        string uri = InvitationCodec.EncodePairV2(draft);
        return InvitationCodec.DecodePairV2(uri);
    }

    public override string ToString()
    {
        return $"LanPairV2Invitation(v={V}, purpose='{Purpose}', transport='{Transport}', profile={Profile}, protocol={Protocol}, " +
               $"invitationId=[REDACTED], presence=[REDACTED], name=[REDACTED], platform='{Platform}', " +
               $"endpoints=[REDACTED], spkiSha256=[REDACTED], token=[REDACTED], " +
               $"expiresAt='{ExpiresAt}', ttlSeconds={TtlSeconds}, invitationHash=[REDACTED])";
    }
}

public abstract record ParsedGlideInvitation
{
    private ParsedGlideInvitation() { }

    public sealed record TransferV1(LanInvitation Invitation) : ParsedGlideInvitation;
    public sealed record PairOnlyV2(LanPairV2Invitation Invitation) : ParsedGlideInvitation;
}
