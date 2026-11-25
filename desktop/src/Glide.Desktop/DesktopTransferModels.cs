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
public sealed record DesktopTransferFile
{
    private long? _cachedSizeBytes;

    public DesktopTransferFile(
        string FilePath,
        string? RelativePath = null,
        long? KnownSizeBytes = null)
    {
        this.FilePath = FilePath;
        this.RelativePath = RelativePath;
        _cachedSizeBytes = KnownSizeBytes;
    }

    public string FilePath { get; init; }
    public string? RelativePath { get; init; }

    public string Name => Path.GetFileName(FilePath);

    public string TransferRelativePath =>
        RelativePath ?? Name;

    public long SizeBytes => _cachedSizeBytes ??= new FileInfo(FilePath).Length;

    public void Deconstruct(out string FilePath, out string? RelativePath)
    {
        FilePath = this.FilePath;
        RelativePath = this.RelativePath;
    }
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
    long TotalBytes,
    int ItemIndex = 1,
    int ItemCount = 1)
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
    long VerifiedBytes = 0,
    TransferErrorCategory ErrorCategory = TransferErrorCategory.None,
    bool Paused = false,
    string? ResumableSessionId = null)
{
    public static DesktopTransferResult Completed(string message, int count, long bytes) =>
        new(true, false, false, message, count, bytes);

    public static DesktopTransferResult RejectedByPeer(string message,
        TransferErrorCategory category = TransferErrorCategory.UserRejected) =>
        new(false, true, false, message, ErrorCategory: category);

    public static DesktopTransferResult CancelledByUser(string message) =>
        new(false, false, true, message, ErrorCategory: TransferErrorCategory.UserCancelled);

    public static DesktopTransferResult Failed(string message, TransferErrorCategory category = TransferErrorCategory.Unknown,
        int verifiedCount = 0, long verifiedBytes = 0) =>
        new(false, false, false, message, verifiedCount, verifiedBytes, category);

    public static DesktopTransferResult PausedForResume(string message, string sessionId,
        int verifiedCount = 0, long verifiedBytes = 0) =>
        new(false, false, false, message, verifiedCount, verifiedBytes, TransferErrorCategory.None,
            Paused: true, ResumableSessionId: sessionId);
}

public interface IDesktopTransferInteraction
{
    Task<bool> ConfirmRecipientAsync(
        string peerName,
        CancellationToken cancellationToken) => Task.FromResult(false);

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
    void OnPhase(DesktopTransferDirection direction, DesktopTransferPhase phase) { }
    void OnResumableBatchReady(DesktopTransferDirection direction, string originalSessionId) { }
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

    Task<DesktopTransferResult> ReceiveFromInvitationAsync(
        DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default) =>
        Task.FromResult(DesktopTransferResult.Failed(
            "This transfer runtime does not support receiving from invitation."));

    Task<DesktopTransferResult> SendHostedAsync(
        Glide.Transport.Sockets.TlsSocketConnection connection,
        DesktopSendRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default) =>
        Task.FromResult(DesktopTransferResult.Failed(
            "This transfer runtime does not support receiver-initiated pairing."));

    Task<DesktopTransferResult> SendTrustedAsync(
        DesktopSendRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default) =>
        SendAsync(request, interaction, cancellationToken);

    Task<DesktopTransferResult> ReceiveTrustedAsync(
        Glide.Transport.Sockets.TlsSocketConnection connection,
        Profile2Message.SessionOpen open,
        DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction,
        bool isBusy = false,
        CancellationToken cancellationToken = default) =>
        Task.FromResult(DesktopTransferResult.Failed(
            "This transfer runtime does not support trusted reconnect receiving."));

    Task<DesktopTransferResult> ResumeTrustedAsync(
        DesktopResumeRequest request, IDesktopTransferInteraction interaction, CancellationToken cancellationToken = default) =>
        Task.FromResult(DesktopTransferResult.Failed("This runtime does not support resuming transfers."));

    Task<DesktopTransferResult> ReceiveTrustedResumeAsync(
        Glide.Transport.Sockets.TlsSocketConnection connection, Profile2Message.SessionOpen open,
        DesktopReceiveRequest request, IDesktopTransferInteraction interaction, bool isBusy = false,
        CancellationToken cancellationToken = default) =>
        Task.FromResult(DesktopTransferResult.Failed("This runtime does not support resuming transfers."));
}

public abstract class DesktopTransferInteractionBase : IDesktopTransferInteraction
{
    public virtual Task<bool> ConfirmRecipientAsync(
        string peerName,
        CancellationToken cancellationToken) => Task.FromResult(false);

    public virtual Task<bool> ConfirmOfferAsync(
        DesktopOffer offer,
        CancellationToken cancellationToken) => Task.FromResult(false);

    public virtual Task<bool> ConfirmPeerEnrollmentAsync(
        VerifiedDesktopPeerIdentity peer,
        CancellationToken cancellationToken) => Task.FromResult(false);

    public virtual void OnInvitationConsumed(DesktopTransferDirection direction) { }
    public virtual void OnPeerIdentified(DesktopTransferDirection direction, string peerName) { }
    public virtual void OnStatus(DesktopTransferDirection direction, string message) { }
    public virtual void OnProgress(DesktopTransferProgress progress) { }
    public virtual void OnItemStatus(DesktopTransferDirection direction, string itemName, string status) { }
    public virtual void OnHistory(DesktopHistoryEntry entry) { }
    public virtual void OnPhase(DesktopTransferDirection direction, DesktopTransferPhase phase) { }
    public virtual void OnResumableBatchReady(DesktopTransferDirection direction, string originalSessionId) { }
}


public sealed record DesktopSendRequest(
    string Host,
    int Port,
    IReadOnlyList<DesktopTransferFile> Files,
    LanInvitation? Invitation = null,
    string LocalDeviceName = "",
    DesktopPersistentTrustManager? TrustManager = null,
    string? ExpectedPeerFingerprintHex = null,
    LanInvitationAuthority? HostedInvitationAuthority = null,
    string? ExpectedPresenceId = null,
    IReadOnlyList<LanEndpoint>? CandidateEndpoints = null,
    IReadOnlyList<Glide.Desktop.Services.TrustedEndpointCandidate>? CandidateDetails = null,
    Func<CancellationToken, Task<IReadOnlyList<Glide.Desktop.Services.TrustedEndpointCandidate>>>? RefreshCandidatesAsync = null,
    DesktopResumeStore? ResumeStore = null);

public sealed record DesktopReceiveRequest(
    QuarantineStagingStore StagingStore,
    LanInvitationAuthority? InvitationAuthority = null,
    string LocalDeviceName = "",
    DesktopPersistentTrustManager? TrustManager = null,
    string? ExpectedPeerFingerprintHex = null,
    LanInvitation? Invitation = null,
    DesktopResumeStore? ResumeStore = null);

public sealed record DesktopResumeRequest(
    DesktopAcceptedResumeBatch Batch,
    DesktopReceiveRequest Local,
    IReadOnlyList<Glide.Desktop.Services.TrustedEndpointCandidate> Candidates);
