namespace Glide.Core.Remote;

/// <summary>
/// Immutable representation of an ephemeral out-of-band remote pairing invitation (ADR-0022, Section 2.1).
/// Invariants:
/// - RendezvousId: valid UUIDv4 string
/// - EphemeralPublicKeyHex: 64 lowercase hex characters (32-byte X25519 public key)
/// - SignalingEndpoint: valid wss:// or ws:// URI
/// - RelayBlindToken: non-empty Base64url token
/// - ExpiresAt: Unix timestamp (seconds) strictly bounded to &lt;= 120s TTL from creation
/// </summary>
public sealed class RemoteInvitation
{
    public const int DefaultTtlSeconds = 120;
    public const int MaxTtlSeconds = 120;

    public string RendezvousId { get; }
    public string EphemeralPublicKeyHex { get; }
    public string SignalingEndpoint { get; }
    public string RelayBlindToken { get; }
    public long ExpiresAt { get; }
    public int TtlSeconds { get; }

    public RemoteInvitation(
        string rendezvousId,
        string ephemeralPublicKeyHex,
        string signalingEndpoint,
        string relayBlindToken,
        long expiresAt,
        int ttlSeconds = DefaultTtlSeconds)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(rendezvousId);
        ArgumentException.ThrowIfNullOrWhiteSpace(ephemeralPublicKeyHex);
        ArgumentException.ThrowIfNullOrWhiteSpace(signalingEndpoint);
        ArgumentException.ThrowIfNullOrWhiteSpace(relayBlindToken);

        RendezvousId = rendezvousId.ToLowerInvariant();
        EphemeralPublicKeyHex = ephemeralPublicKeyHex.ToLowerInvariant();
        SignalingEndpoint = signalingEndpoint;
        RelayBlindToken = relayBlindToken;
        ExpiresAt = expiresAt;
        TtlSeconds = ttlSeconds;
    }

    public bool IsExpired(long? currentEpochSeconds = null)
    {
        long now = currentEpochSeconds ?? DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        return now > ExpiresAt;
    }
}
