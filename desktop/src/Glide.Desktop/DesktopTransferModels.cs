using Glide.Core.Pairing;
using Glide.Core.Storage;
using Glide.Core.Security;

namespace Glide.Desktop;

public enum DesktopTransferDirection
{
    Send,
    Receive
}

/// <summary>
/// An immutable source file selected for a transfer. <see cref="RelativePath"/> is the
/// receiver-visible, protocol-safe path; it is deliberately separate from the local source path.
/// </summary>
public sealed record DesktopTransferFile(
    string FilePath,
    string? RelativePath = null)
{
    public string Name => Path.GetFileName(FilePath);

    public string TransferRelativePath =>
        RelativePath ?? Name;

    public long SizeBytes => new FileInfo(FilePath).Length;
}

public sealed record DesktopOfferItem(
    string ItemId,
    string Name,
    long SizeBytes,
    string RelativePath);

public sealed record DesktopOffer(
    string BatchId,
    string PeerName,
    IReadOnlyList<DesktopOfferItem> Items,
    long TotalBytes,
    string? AuthenticatedPeerFingerprintHex = null);

public sealed record DesktopTransferProgress(
    DesktopTransferDirection Direction,
    string PeerName,
    string ItemName,
    long TransferredBytes,
    long TotalBytes)
{
    public double Fraction => TotalBytes <= 0 ? 1.0 : Math.Clamp((double)TransferredBytes / TotalBytes, 0.0, 1.0);
}

public sealed record DesktopHistoryEntry(
    DesktopTransferDirection Direction,
    string ItemName,
    long SizeBytes,
    string Path,
    string PeerName,
    DateTimeOffset Timestamp);

public sealed record DesktopTransferResult(
    bool Success,
    bool Rejected,
    bool Cancelled,
    string Message,
    int VerifiedCount = 0,
    long VerifiedBytes = 0)
{
    public static DesktopTransferResult Completed(string message, int count, long bytes) =>
        new(true, false, false, message, count, bytes);

    public static DesktopTransferResult RejectedByPeer(string message) =>
        new(false, true, false, message);

    public static DesktopTransferResult CancelledByUser(string message) =>
        new(false, false, true, message);

    public static DesktopTransferResult Failed(string message) =>
        new(false, false, false, message);
}

public interface IDesktopTransferInteraction
{
    Task<bool> ConfirmOfferAsync(
        DesktopOffer offer,
        CancellationToken cancellationToken);

    Task<bool> ConfirmPeerEnrollmentAsync(
        VerifiedDesktopPeerIdentity peer,
        CancellationToken cancellationToken);

    void OnInvitationConsumed(DesktopTransferDirection direction);
    void OnPeerIdentified(DesktopTransferDirection direction, string peerName);
    void OnStatus(DesktopTransferDirection direction, string message);
    void OnProgress(DesktopTransferProgress progress);
    void OnItemStatus(DesktopTransferDirection direction, string itemName, string status);
    void OnHistory(DesktopHistoryEntry entry);
}

/// <summary>
/// Protocol execution boundary owned by the desktop runtime. It keeps queue/lifecycle policy
/// independent from the concrete LAN implementation and makes that policy testable.
/// </summary>
public interface IDesktopTransferExecutor
{
    Task<DesktopTransferResult> SendAsync(
        DesktopSendRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default);

    Task<DesktopTransferResult> ReceiveAsync(
        Glide.Transport.Sockets.TlsSocketConnection connection,
        DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default);
}

public abstract class DesktopTransferInteractionBase : IDesktopTransferInteraction
{
    public virtual Task<bool> ConfirmOfferAsync(
        DesktopOffer offer,
        CancellationToken cancellationToken) => Task.FromResult(true);

    public virtual Task<bool> ConfirmPeerEnrollmentAsync(
        VerifiedDesktopPeerIdentity peer,
        CancellationToken cancellationToken) => Task.FromResult(false);

    public virtual void OnInvitationConsumed(DesktopTransferDirection direction) { }
    public virtual void OnPeerIdentified(DesktopTransferDirection direction, string peerName) { }
    public virtual void OnStatus(DesktopTransferDirection direction, string message) { }
    public virtual void OnProgress(DesktopTransferProgress progress) { }
    public virtual void OnItemStatus(DesktopTransferDirection direction, string itemName, string status) { }
    public virtual void OnHistory(DesktopHistoryEntry entry) { }
}


public sealed record DesktopSendRequest(
    string Host,
    int Port,
    IReadOnlyList<DesktopTransferFile> Files,
    LanInvitation? Invitation = null,
    string LocalDeviceName = "",
    DesktopPersistentTrustManager? TrustManager = null,
    string? ExpectedPeerFingerprintHex = null);

public sealed record DesktopReceiveRequest(
    QuarantineStagingStore StagingStore,
    LanInvitationAuthority InvitationAuthority,
    string LocalDeviceName = "",
    DesktopPersistentTrustManager? TrustManager = null,
    string? ExpectedPeerFingerprintHex = null);
