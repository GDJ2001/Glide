using Glide.Core.Models;
using Glide.Core.Security;

namespace Glide.Desktop;

public enum TransferErrorCategory
{
    None,
    Network,
    Security,
    Storage,
    Protocol,
    LimitExceeded,
    UserCancelled,
    UserRejected,
    PathSanitized,
    Unknown
}

public abstract record TransferEvent
{
    public sealed record PhaseChanged(
        DesktopTransferPhase Phase,
        DesktopTransferDirection? Direction,
        string PeerName,
        string Message) : TransferEvent;

    public sealed record ProgressUpdated(
        DesktopTransferProgress Progress,
        double RateBytesPerSecond,
        string RateText,
        string TransferredBytesText) : TransferEvent;

    public sealed record ItemStatusChanged(
        DesktopTransferDirection Direction,
        string ItemName,
        string Status) : TransferEvent;

    public sealed record OfferReceived(
        DesktopOffer Offer) : TransferEvent;

    public sealed record SasCodeCalculated(
        string SasCode,
        string PeerName) : TransferEvent;

    public sealed record RecipientConfirmationRequired(
        string PeerName) : TransferEvent;

    public sealed record TrustEnrollmentRequired(
        VerifiedDesktopPeerIdentity Peer) : TransferEvent;

    public sealed record Completed(
        DesktopTransferResult Result) : TransferEvent;

    public sealed record Failed(
        TransferErrorCategory Category,
        string Message,
        Exception? Exception = null) : TransferEvent;
}
