namespace Glide.Desktop;

public enum DesktopTransferDirection
{
    Send,
    Receive
}

public sealed record DesktopTransferFile(string FilePath)
{
    public string Name => Path.GetFileName(FilePath);
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
    long TotalBytes);

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
    Task<bool> ConfirmSasAsync(
        DesktopTransferDirection direction,
        string peerName,
        int sixDigitCode,
        CancellationToken cancellationToken);

    Task<bool> ConfirmOfferAsync(
        DesktopOffer offer,
        CancellationToken cancellationToken);

    void OnInvitationConsumed(DesktopTransferDirection direction);
    void OnPeerIdentified(DesktopTransferDirection direction, string peerName);
    void OnStatus(DesktopTransferDirection direction, string message);
    void OnProgress(DesktopTransferProgress progress);
    void OnItemStatus(DesktopTransferDirection direction, string itemName, string status);
    void OnHistory(DesktopHistoryEntry entry);
}

public abstract class DesktopTransferInteractionBase : IDesktopTransferInteraction
{
    public virtual Task<bool> ConfirmSasAsync(
        DesktopTransferDirection direction,
        string peerName,
        int sixDigitCode,
        CancellationToken cancellationToken) => Task.FromResult(true);

    public virtual Task<bool> ConfirmOfferAsync(
        DesktopOffer offer,
        CancellationToken cancellationToken) => Task.FromResult(true);

    public virtual void OnInvitationConsumed(DesktopTransferDirection direction) { }
    public virtual void OnPeerIdentified(DesktopTransferDirection direction, string peerName) { }
    public virtual void OnStatus(DesktopTransferDirection direction, string message) { }
    public virtual void OnProgress(DesktopTransferProgress progress) { }
    public virtual void OnItemStatus(DesktopTransferDirection direction, string itemName, string status) { }
    public virtual void OnHistory(DesktopHistoryEntry entry) { }
}
