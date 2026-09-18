using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;

namespace Glide.Core.Pairing;

/// <summary>
/// Thread-safe, single-use authority for one desktop LAN QR invitation.
/// Mirrors the Android invitation authority's fail-closed bootstrap semantics:
/// exact invitation identity/hash, constant-time token/hash checks, monotonic
/// expiration after construction, atomic consumption, and replay rejection.
/// </summary>
public sealed class LanInvitationAuthority
{
    private readonly object _gate = new();
    private readonly LanInvitation _invitation;
    private readonly byte[] _tokenHash;
    private readonly byte[] _invitationHashBytes;
    private readonly long _monotonicExpiresAt;
    private bool _revoked;
    private bool _consumed;

    public LanInvitationAuthority(LanInvitation invitation)
    {
        ArgumentNullException.ThrowIfNull(invitation);
        if (string.IsNullOrWhiteSpace(invitation.InvitationHash))
        {
            throw new ArgumentException(
                "Invitation authority requires a canonical decoded invitation with invitationHash.",
                nameof(invitation));
        }

        _invitation = invitation;
        _tokenHash = SHA256.HashData(Encoding.UTF8.GetBytes(invitation.Token));
        _invitationHashBytes = Encoding.ASCII.GetBytes(invitation.InvitationHash.ToLowerInvariant());

        long nowEpochSeconds = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        if (!long.TryParse(invitation.ExpiresAt, out long expiresAtEpochSeconds))
        {
            expiresAtEpochSeconds = nowEpochSeconds;
        }

        long remainingSeconds = Math.Max(0, expiresAtEpochSeconds - nowEpochSeconds);
        long nowTicks = Stopwatch.GetTimestamp();
        double requestedTicks = remainingSeconds * (double)Stopwatch.Frequency;
        long maxDelta = long.MaxValue - nowTicks;
        long expiryDelta = requestedTicks >= maxDelta
            ? maxDelta
            : (long)requestedTicks;
        _monotonicExpiresAt = nowTicks + expiryDelta;
    }

    public LanInvitation Invitation => _invitation;

    public LanBootstrapResult Consume(
        LanBootstrapMessage message,
        bool isBusy = false)
    {
        ArgumentNullException.ThrowIfNull(message);

        lock (_gate)
        {
            if (isBusy)
            {
                return LanBootstrapResult.Rejected(LanBootstrapFailureCode.BUSY);
            }

            if (_consumed &&
                string.Equals(
                    _invitation.InvitationId,
                    message.InvitationId,
                    StringComparison.OrdinalIgnoreCase))
            {
                return LanBootstrapResult.Rejected(
                    LanBootstrapFailureCode.INVITATION_USED);
            }

            if (_revoked ||
                !string.Equals(
                    _invitation.InvitationId,
                    message.InvitationId,
                    StringComparison.OrdinalIgnoreCase))
            {
                return LanBootstrapResult.Rejected(
                    LanBootstrapFailureCode.AUTHENTICATION_FAILED);
            }

            if (Stopwatch.GetTimestamp() >= _monotonicExpiresAt ||
                _invitation.IsExpired())
            {
                _revoked = true;
                return LanBootstrapResult.Rejected(
                    LanBootstrapFailureCode.INVITATION_EXPIRED);
            }

            byte[] candidateTokenHash =
                SHA256.HashData(Encoding.UTF8.GetBytes(message.Token));
            if (!CryptographicOperations.FixedTimeEquals(
                    candidateTokenHash,
                    _tokenHash))
            {
                return LanBootstrapResult.Rejected(
                    LanBootstrapFailureCode.AUTHENTICATION_FAILED);
            }

            byte[] candidateInvitationHash =
                Encoding.ASCII.GetBytes(message.InvitationHash.ToLowerInvariant());
            if (candidateInvitationHash.Length != _invitationHashBytes.Length ||
                !CryptographicOperations.FixedTimeEquals(
                    candidateInvitationHash,
                    _invitationHashBytes))
            {
                return LanBootstrapResult.Rejected(
                    LanBootstrapFailureCode.AUTHENTICATION_FAILED);
            }

            _consumed = true;
            return LanBootstrapResult.Accepted(message.SessionId);
        }
    }

    public void Revoke()
    {
        lock (_gate)
        {
            _revoked = true;
        }
    }

    public bool IsConsumed
    {
        get
        {
            lock (_gate)
            {
                return _consumed;
            }
        }
    }
}
