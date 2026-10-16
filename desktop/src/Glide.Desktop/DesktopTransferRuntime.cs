using System.Diagnostics;
using Glide.Transport.Sockets;
using Glide.Desktop.Services;
using Glide.Core.Security;

namespace Glide.Desktop;

/// <summary>
/// Owns the single active desktop batch. Both GUI and CLI callers use this boundary so a second
/// incoming or outgoing request cannot race an active consent or transfer session.
/// </summary>
public sealed class DesktopTransferRuntime : IAsyncDisposable
{
    private readonly IDesktopTransferExecutor _executor;
    private readonly SemaphoreSlim _activeBatch = new(1, 1);
    private readonly object _gate = new();
    private CancellationTokenSource? _activeCancellation;
    private DesktopTransferSnapshot _snapshot = DesktopTransferSnapshot.Idle;

    public DesktopTransferRuntime(IDesktopTransferExecutor? executor = null)
    {
        _executor = executor ?? new DesktopTransferEngine();
    }

    public event EventHandler<DesktopTransferSnapshot>? SnapshotChanged;
    public event EventHandler<TransferEvent>? EventOccurred;

    public DesktopTransferSnapshot Snapshot
    {
        get
        {
            lock (_gate)
            {
                return _snapshot;
            }
        }
    }

    public bool IsBusy => _activeBatch.CurrentCount == 0;

    public bool CancelActiveTransfer()
    {
        lock (_gate)
        {
            if (_activeCancellation == null || _activeCancellation.IsCancellationRequested)
            {
                return false;
            }

            _activeCancellation.Cancel();
            return true;
        }
    }

    public Task<DesktopTransferResult> SendAsync(
        DesktopSendRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default) =>
        RunAsync(
            DesktopTransferDirection.Send,
            request.Invitation?.Name ?? "Pending peer",
            (observer, token) => _executor.SendAsync(request, observer, token),
            interaction,
            cancellationToken);

    public Task<DesktopTransferResult> SendTrustedAsync(
        DesktopSendRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default) =>
        RunAsync(
            DesktopTransferDirection.Send,
            "Paired device",
            (observer, token) => _executor.SendTrustedAsync(request, observer, token),
            interaction,
            cancellationToken);

    public Task<DesktopTransferResult> ReceiveAsync(
        TlsSocketConnection connection,
        DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default) =>
        RunAsync(
            DesktopTransferDirection.Receive,
            "Incoming peer",
            (observer, token) => _executor.ReceiveAsync(connection, request, observer, token),
            interaction,
            cancellationToken);

    public async Task<DesktopTransferResult> ReceiveTrustedAsync(
        TlsSocketConnection connection,
        Glide.Core.Pairing.Profile2Message.SessionOpen open,
        DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default)
    {
        string peerName = string.IsNullOrWhiteSpace(open.InitiatorDeviceName)
            ? "Paired device"
            : open.InitiatorDeviceName;
        return await RunAsync(
            DesktopTransferDirection.Receive,
            peerName,
            (observer, token) => _executor.ReceiveTrustedAsync(
                connection, open, request, observer, isBusy: false, token),
            interaction,
            cancellationToken,
            (token) => _executor.ReceiveTrustedAsync(
                connection, open, request, interaction, isBusy: true, token));
    }

    public Task<DesktopTransferResult> ReceiveFromInvitationAsync(
        DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default) =>
        RunAsync(
            DesktopTransferDirection.Receive,
            request.Invitation?.Name ?? "Sending peer",
            (observer, token) => _executor.ReceiveFromInvitationAsync(request, observer, token),
            interaction,
            cancellationToken);

    public Task<DesktopTransferResult> SendHostedAsync(
        TlsSocketConnection connection,
        DesktopSendRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default) =>
        RunAsync(
            DesktopTransferDirection.Send,
            "Connecting device",
            (observer, token) => _executor.SendHostedAsync(connection, request, observer, token),
            interaction,
            cancellationToken);

    private async Task<DesktopTransferResult> RunAsync(
        DesktopTransferDirection direction,
        string peerName,
        Func<IDesktopTransferInteraction, CancellationToken, Task<DesktopTransferResult>> operation,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken,
        Func<CancellationToken, Task<DesktopTransferResult>>? busyOperation = null)
    {
        ArgumentNullException.ThrowIfNull(operation);

        if (!await _activeBatch.WaitAsync(0, cancellationToken))
        {
            if (busyOperation is not null) return await busyOperation(cancellationToken);
            return DesktopTransferResult.Failed(
                "Glide is busy with another transfer. Wait for it to finish or cancel it before starting a new one.",
                TransferErrorCategory.Protocol);
        }

        using var linkedCancellation =
            CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        lock (_gate)
        {
            _activeCancellation = linkedCancellation;
        }

        Guid runId = Guid.NewGuid();
        var observer = new RuntimeInteraction(this, interaction, direction, peerName, runId);
        SetSnapshot(new DesktopTransferSnapshot(
            DesktopTransferPhase.Preparing,
            direction,
            peerName,
            "Connecting…", RunId: runId));

        try
        {
            DesktopTransferResult result = await operation(observer, linkedCancellation.Token);
            DesktopTransferPhase phase = result switch
            {
                { Success: true } => DesktopTransferPhase.Completed,
                { Cancelled: true } => DesktopTransferPhase.Cancelled,
                { Rejected: true, ErrorCategory: TransferErrorCategory.UserRejected } => DesktopTransferPhase.Cancelled,
                _ => DesktopTransferPhase.Failed
            };
            SetSnapshot(new DesktopTransferSnapshot(phase, direction, observer.PeerName, result.Message, result, runId));
            NotifyEvent(result.Success ? new TransferEvent.Completed(result) :
                new TransferEvent.Failed(result.ErrorCategory, result.Message));
            return result;
        }
        catch (OperationCanceledException) when (linkedCancellation.IsCancellationRequested)
        {
            const string message = "Transfer cancelled.";
            DesktopTransferResult result = DesktopTransferResult.CancelledByUser(message);
            SetSnapshot(new DesktopTransferSnapshot(
                DesktopTransferPhase.Cancelled,
                direction,
                observer.PeerName,
                message, result, runId));
            NotifyEvent(new TransferEvent.Failed(result.ErrorCategory, message));
            return result;
        }
        catch (Exception error)
        {
            DesktopTransferResult result = DesktopTransferResult.Failed(error.Message, DesktopTransferFailures.Classify(error));
            SetSnapshot(new DesktopTransferSnapshot(
                DesktopTransferPhase.Failed, direction, observer.PeerName, result.Message, result, runId));
            NotifyEvent(new TransferEvent.Failed(result.ErrorCategory, result.Message, error));
            return result;
        }
        finally
        {
            lock (_gate)
            {
                _activeCancellation = null;
            }
            _activeBatch.Release();
        }
    }

    private void SetSnapshot(DesktopTransferSnapshot snapshot)
    {
        lock (_gate)
        {
            _snapshot = snapshot;
        }
        SnapshotChanged?.Invoke(this, snapshot);
        EventOccurred?.Invoke(this, new TransferEvent.PhaseChanged(
            snapshot.Phase,
            snapshot.Direction,
            snapshot.PeerName,
            snapshot.Message));
    }

    public void NotifyEvent(TransferEvent @event)
    {
        EventOccurred?.Invoke(this, @event);
    }

    private sealed class RuntimeInteraction : DesktopTransferInteractionBase
    {
        private readonly DesktopTransferRuntime _owner;
        private readonly IDesktopTransferInteraction _inner;
        private readonly DesktopTransferDirection _direction;
        private readonly Guid _runId;
        private readonly DesktopTransferRateCalculator _rate = new();
        private long _lastProgressTimestamp;
        private string? _lastProgressItem;
        private long _lastProgressBytes;
        private DesktopTransferPhase _lastPhase = DesktopTransferPhase.Preparing;
        private const long ProgressIntervalMilliseconds = 100;
        public string PeerName { get; private set; }

        public RuntimeInteraction(DesktopTransferRuntime owner, IDesktopTransferInteraction inner,
            DesktopTransferDirection direction, string peerName, Guid runId)
        {
            _owner = owner;
            _inner = inner;
            _direction = direction;
            PeerName = peerName;
            _runId = runId;
            _rate.Reset();
        }

        public override async Task<bool> ConfirmRecipientAsync(string peerName, CancellationToken cancellationToken)
        {
            OnPhase(_direction, DesktopTransferPhase.AwaitingConsent);
            // Recipient confirmation only authenticates the peer. The transfer engine reports
            // Transferring after the offer has been accepted and it is ready to send data.
            return await _inner.ConfirmRecipientAsync(peerName, cancellationToken);
        }

        public override async Task<bool> ConfirmOfferAsync(DesktopOffer offer, CancellationToken cancellationToken)
        {
            _owner.NotifyEvent(new TransferEvent.OfferReceived(offer));
            OnPhase(_direction, DesktopTransferPhase.AwaitingConsent);
            // The decision must be written to the peer before the engine can enter its data phase.
            return await _inner.ConfirmOfferAsync(offer, cancellationToken);
        }

        public override Task<bool> ConfirmPeerEnrollmentAsync(VerifiedDesktopPeerIdentity peer,
            CancellationToken cancellationToken) => _inner.ConfirmPeerEnrollmentAsync(peer, cancellationToken);
        public override void OnInvitationConsumed(DesktopTransferDirection direction) => _inner.OnInvitationConsumed(direction);
        public override void OnPeerIdentified(DesktopTransferDirection direction, string peerName)
        {
            PeerName = peerName;
            _inner.OnPeerIdentified(direction, peerName);
            DesktopTransferSnapshot current = _owner.Snapshot;
            if (current.RunId == _runId) _owner.SetSnapshot(current with { PeerName = peerName });
        }
        public override void OnStatus(DesktopTransferDirection direction, string message) => _inner.OnStatus(direction, message);
        public override void OnProgress(DesktopTransferProgress progress)
        {
            _lastProgressBytes = progress.TransferredBytes;
            // File streaming must not wait for a UI dispatch on every 64 KiB record.
            // Always publish the first update, item changes, and the final byte.
            long now = Stopwatch.GetTimestamp();
            bool isFinal = progress.TransferredBytes >= progress.TotalBytes;
            bool itemChanged = !string.Equals(_lastProgressItem, progress.ItemName, StringComparison.Ordinal);
            if (!isFinal && !itemChanged &&
                Stopwatch.GetElapsedTime(_lastProgressTimestamp, now).TotalMilliseconds < ProgressIntervalMilliseconds)
            {
                return;
            }

            _lastProgressTimestamp = now;
            _lastProgressItem = progress.ItemName;
            _inner.OnProgress(progress);
            var rate = _rate.Update(progress.TransferredBytes, progress.TotalBytes);
            _owner.NotifyEvent(new TransferEvent.ProgressUpdated(progress, rate.BytesPerSecond, rate.RateText, rate.TransferredBytesText));
        }
        public override void OnItemStatus(DesktopTransferDirection direction, string itemName, string status)
        {
            _inner.OnItemStatus(direction, itemName, status);
            _owner.NotifyEvent(new TransferEvent.ItemStatusChanged(direction, itemName, status));
        }
        public override void OnHistory(DesktopHistoryEntry entry) => _inner.OnHistory(entry);
        public override void OnPhase(DesktopTransferDirection direction, DesktopTransferPhase phase)
        {
            // Verification and destination commit can take time after the last network byte.
            // Do not fold that pause into the rate of the next file in the batch.
            if (phase == DesktopTransferPhase.Transferring && _lastPhase != phase)
                _rate.Reset(_lastProgressBytes);
            _lastPhase = phase;
            _inner.OnPhase(direction, phase);
            string message = phase switch
            {
                DesktopTransferPhase.AwaitingConsent => "Waiting for approval…",
                DesktopTransferPhase.Transferring => "Transferring…",
                DesktopTransferPhase.Verifying => "Verifying files…",
                _ => "Connecting…"
            };
            _owner.SetSnapshot(new DesktopTransferSnapshot(phase, direction, PeerName, message, RunId: _runId));
        }
    }

    public ValueTask DisposeAsync()
    {
        CancelActiveTransfer();
        // A caller may dispose the application boundary while an executor is still observing
        // cancellation. Disposing the semaphore here would make that executor's finally block
        // throw when it releases the lease. The runtime is process-owned and has no unmanaged
        // resources, so cancellation is the safe shutdown operation.
        return ValueTask.CompletedTask;
    }
}

public enum DesktopTransferPhase
{
    Idle,
    Queued,
    Preparing,
    AwaitingConsent,
    Transferring,
    Paused,
    Interrupted,
    Verifying,
    Completed,
    Cancelled,
    Failed
}

public sealed record DesktopTransferSnapshot(
    DesktopTransferPhase Phase,
    DesktopTransferDirection? Direction,
    string PeerName,
    string Message,
    DesktopTransferResult? Result = null,
    Guid RunId = default)
{
    public static DesktopTransferSnapshot Idle { get; } =
        new(DesktopTransferPhase.Idle, null, string.Empty, "Ready");

    public bool IsActive => Phase is DesktopTransferPhase.Preparing or
        DesktopTransferPhase.AwaitingConsent or DesktopTransferPhase.Transferring or
        DesktopTransferPhase.Paused or DesktopTransferPhase.Verifying;
}
