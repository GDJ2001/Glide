using System.Security.Cryptography;
using System.Text;

namespace Glide.Core.Pairing;

/// <summary>
/// Thread-safe one-use authority for the currently advertised LAN pairing invitation.
///
/// The authority canonicalizes the invitation before publication/consumption so the receiver
/// compares the same invitationHash that the sender obtains by decoding the QR payload.
/// A successful bootstrap consumes the invitation atomically; concurrent/replayed attempts fail.
/// </summary>
public sealed class LanInvitationAuthority
{
    private const int MaxConsumedIds = 128;

    private readonly object _gate = new();
    private readonly HashSet<string> _consumedIds = new(StringComparer.Ordinal);
    private readonly Queue<string> _consumedOrder = new();

    private LanInvitation? _activeInvitation;

    public LanInvitation? ActiveInvitation
    {
        get
        {
            lock (_gate)
            {
                return _activeInvitation;
            }
        }
    }

    public LanInvitation Publish(LanInvitation invitation)
    {
        ArgumentNullException.ThrowIfNull(invitation);

        // Encoding + decoding computes and attaches the canonical payload hash.
        LanInvitation canonical =
            InvitationCodec.Decode(InvitationCodec.Encode(invitation));

        lock (_gate)
        {
            _activeInvitation = canonical;
            return canonical;
        }
    }

    public void RevokeActive()
    {
        lock (_gate)
        {
            _activeInvitation = null;
        }
    }

    public void RevokeAll()
    {
        lock (_gate)
        {
            _activeInvitation = null;
            _consumedIds.Clear();
            _consumedOrder.Clear();
        }
    }

    public LanBootstrapResult Consume(
        LanBootstrapMessage message,
        string expectedRole = LanBootstrapMessage.RoleSender)
    {
        ArgumentNullException.ThrowIfNull(message);

        lock (_gate)
        {
            if (message.Role != expectedRole)
            {
                return LanBootstrapResult.Rejected(
                    LanBootstrapFailureCode.UNSUPPORTED_PROFILE);
            }

            if (_consumedIds.Contains(message.InvitationId))
            {
                return LanBootstrapResult.Rejected(
                    LanBootstrapFailureCode.INVITATION_USED);
            }

            LanInvitation? active = _activeInvitation;
            if (active == null)
            {
                return LanBootstrapResult.Rejected(
                    LanBootstrapFailureCode.AUTHENTICATION_FAILED);
            }

            if (active.IsExpired())
            {
                _activeInvitation = null;
                return LanBootstrapResult.Rejected(
                    LanBootstrapFailureCode.INVITATION_EXPIRED);
            }

            bool identifiersMatch = string.Equals(
                active.InvitationId,
                message.InvitationId,
                StringComparison.Ordinal);

            bool tokenMatches = FixedTimeEquals(
                active.Token,
                message.Token);
            bool hashMatches = FixedTimeEquals(
                active.InvitationHash,
                message.InvitationHash);

            if (!identifiersMatch || !tokenMatches || !hashMatches)
            {
                return LanBootstrapResult.Rejected(
                    LanBootstrapFailureCode.AUTHENTICATION_FAILED);
            }

            _activeInvitation = null;
            RememberConsumed(message.InvitationId);
            return LanBootstrapResult.Accepted(message.SessionId);
        }
    }

    public bool IsConsumed(string invitationId)
    {
        lock (_gate)
        {
            return _consumedIds.Contains(invitationId);
        }
    }

    private void RememberConsumed(string invitationId)
    {
        if (_consumedIds.Add(invitationId))
        {
            _consumedOrder.Enqueue(invitationId);
        }

        while (_consumedOrder.Count > MaxConsumedIds)
        {
            string old = _consumedOrder.Dequeue();
            _consumedIds.Remove(old);
        }
    }

    private static bool FixedTimeEquals(string left, string right)
    {
        byte[] leftBytes = Encoding.ASCII.GetBytes(left);
        byte[] rightBytes = Encoding.ASCII.GetBytes(right);

        if (leftBytes.Length != rightBytes.Length)
        {
            return false;
        }

        return CryptographicOperations.FixedTimeEquals(
            leftBytes,
            rightBytes);
    }
}
