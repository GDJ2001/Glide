using Glide.Core.Codec;
using Glide.Core.Pairing;
using Glide.Core.Storage;

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
    Task OnHistoryAsync(
        DesktopHistoryEntry entry,
        CancellationToken cancellationToken = default);
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
    public virtual Task OnHistoryAsync(
        DesktopHistoryEntry entry,
        CancellationToken cancellationToken = default) =>
        Task.CompletedTask;
}


public sealed record DesktopSendRequest(
    string Host,
    int Port,
    IReadOnlyList<DesktopTransferFile> Files,
    LanInvitation? Invitation = null,
    string LocalDeviceName = "");

public sealed record DesktopReceiveLimits(
    int MaxItems,
    long MaxItemBytes,
    long MaxBatchBytes,
    int MaxControlBytes = ProtocolCatalog.MaxControlBytes)
{
    public static DesktopReceiveLimits Default { get; } =
        new(
            MaxItems: 1000,
            MaxItemBytes: ProtocolCatalog.MaxItemBytes,
            MaxBatchBytes: ProtocolCatalog.MaxBatchBytes,
            MaxControlBytes: ProtocolCatalog.MaxControlBytes);

    public DesktopReceiveLimits Validate()
    {
        if (MaxItems is < 1 or > 1000)
        {
            throw new ArgumentOutOfRangeException(
                nameof(MaxItems),
                "Receive maxItems must be between 1 and 1000.");
        }

        if (MaxItemBytes is < 0 or > ProtocolCatalog.MaxItemBytes)
        {
            throw new ArgumentOutOfRangeException(
                nameof(MaxItemBytes),
                $"Receive maxItemBytes must be between 0 and {ProtocolCatalog.MaxItemBytes}.");
        }

        if (MaxBatchBytes is < 0 or > ProtocolCatalog.MaxBatchBytes ||
            MaxBatchBytes < MaxItemBytes)
        {
            throw new ArgumentOutOfRangeException(
                nameof(MaxBatchBytes),
                $"Receive maxBatchBytes must be between maxItemBytes and {ProtocolCatalog.MaxBatchBytes}.");
        }

        if (MaxControlBytes is < 1024 or > ProtocolCatalog.MaxControlBytes)
        {
            throw new ArgumentOutOfRangeException(
                nameof(MaxControlBytes),
                $"Receive maxControlBytes must be between 1024 and {ProtocolCatalog.MaxControlBytes}.");
        }

        return this;
    }
}

public sealed record DesktopReceiveRequest(
    QuarantineStagingStore StagingStore,
    LanInvitationAuthority? InvitationAuthority = null,
    string LocalDeviceName = "",
    DesktopReceiveLimits? Limits = null);
