using System.Diagnostics;
using Glide.Transport.Sockets;
using Glide.Desktop.Diagnostics;
using Glide.Desktop.Services;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Core.Pairing;

namespace Glide.Desktop;

public sealed record DesktopCancellationTiming(
    long RequestedTimestamp,
    long UiUpdatedTimestamp,
    long ResourcesReleasedTimestamp,
    double UiCancelLatencyMs,
    double ResourceReleaseLatencyMs);

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
    private Task? _activeTransferTask;
    private DesktopTransferSnapshot _snapshot = DesktopTransferSnapshot.Idle;
    private long _activeCancelRequestedTimestamp;
    private long _activeCancelUiUpdatedTimestamp;

    public DesktopTransferRuntime(IDesktopTransferExecutor? executor = null)
    {
        _executor = executor ?? new DesktopTransferEngine();
    }

    public event EventHandler<DesktopTransferSnapshot>? SnapshotChanged;
    public event EventHandler<TransferEvent>? EventOccurred;

    public DesktopCancellationTiming? LastCancellationTiming { get; private set; }
    public R3DiagnosticReport? LastR3DiagnosticReport { get; private set; }

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

            Volatile.Write(ref _activeCancelRequestedTimestamp, Stopwatch.GetTimestamp());
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
                connection, open, request, SilentInteraction.Instance, isBusy: true, token));
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

    public Task<DesktopTransferResult> ResumeTrustedAsync(
        DesktopResumeRequest request, IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default) =>
        RunAsync(request.Batch.Role == DesktopResumeJournalRoles.Sender
                ? DesktopTransferDirection.Send : DesktopTransferDirection.Receive,
            "Paired device", (observer, token) => _executor.ResumeTrustedAsync(request, observer, token),
            interaction, cancellationToken);

    public Task<DesktopTransferResult> ReceiveTrustedResumeAsync(
        TlsSocketConnection connection, Profile2Message.SessionOpen open, DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction, CancellationToken cancellationToken = default) =>
        RunAsync(open.InitiatorRole == DesktopResumeJournalRoles.Sender
                ? DesktopTransferDirection.Receive : DesktopTransferDirection.Send,
            open.InitiatorDeviceName,
            (observer, token) => _executor.ReceiveTrustedResumeAsync(connection, open, request, observer, false, token),
            interaction, cancellationToken,
            token => _executor.ReceiveTrustedResumeAsync(connection, open, request, SilentInteraction.Instance, true, token));

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

        if (!await _activeBatch.WaitAsync(0, cancellationToken).ConfigureAwait(false))
        {
            if (busyOperation is not null)
                return await Task.Run(() => busyOperation(cancellationToken), CancellationToken.None).ConfigureAwait(false);
            return DesktopTransferResult.Failed(
                "Glide is busy with another transfer. Wait for it to finish or cancel it before starting a new one.",
                TransferErrorCategory.Protocol);
        }

        using var linkedCancellation =
            CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        lock (_gate)
        {
            _activeCancellation = linkedCancellation;
            Volatile.Write(ref _activeCancelRequestedTimestamp, 0);
            Volatile.Write(ref _activeCancelUiUpdatedTimestamp, 0);
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
            Task<DesktopTransferResult> operationTask = Task.Run(
                () => operation(observer, linkedCancellation.Token),
                CancellationToken.None);
            lock (_gate)
            {
                _activeTransferTask = operationTask;
            }
            DesktopTransferResult result = await operationTask.ConfigureAwait(false);
            observer.MarkCompleted();
            DesktopTransferPhase phase = result switch
            {
                { Success: true } => DesktopTransferPhase.Completed,
                { Cancelled: true } => DesktopTransferPhase.Cancelled,
                { Rejected: true, ErrorCategory: TransferErrorCategory.UserRejected } => DesktopTransferPhase.Cancelled,
                _ => DesktopTransferPhase.Failed
            };
            SetSnapshot(new DesktopTransferSnapshot(phase, direction, observer.PeerName, result.Message, result, runId));
            if (phase == DesktopTransferPhase.Cancelled && Volatile.Read(ref _activeCancelRequestedTimestamp) > 0)
            {
                Volatile.Write(ref _activeCancelUiUpdatedTimestamp, Stopwatch.GetTimestamp());
            }
            NotifyEvent(result.Success ? new TransferEvent.Completed(result) :
                new TransferEvent.Failed(result.ErrorCategory, result.Message));
            return result;
        }
        catch (OperationCanceledException) when (linkedCancellation.IsCancellationRequested)
        {
            observer.MarkCompleted();
            const string message = "Transfer cancelled.";
            DesktopTransferResult result = DesktopTransferResult.CancelledByUser(message);
            SetSnapshot(new DesktopTransferSnapshot(
                DesktopTransferPhase.Cancelled,
                direction,
                observer.PeerName,
                message, result, runId));
            if (Volatile.Read(ref _activeCancelRequestedTimestamp) > 0)
            {
                Volatile.Write(ref _activeCancelUiUpdatedTimestamp, Stopwatch.GetTimestamp());
            }
            NotifyEvent(new TransferEvent.Failed(result.ErrorCategory, message));
            return result;
        }
        catch (Exception error)
        {
            observer.MarkCompleted();
            DesktopTransferResult result = DesktopTransferResult.Failed(error.Message, DesktopTransferFailures.Classify(error));
            SetSnapshot(new DesktopTransferSnapshot(
                DesktopTransferPhase.Failed, direction, observer.PeerName, result.Message, result, runId));
            NotifyEvent(new TransferEvent.Failed(result.ErrorCategory, result.Message, error));
            return result;
        }
        finally
        {
            observer.MarkCompleted();
            lock (_gate)
            {
                _activeCancellation = null;
                _activeTransferTask = null;
            }
            _activeBatch.Release();

            long cancelReqTs = Volatile.Read(ref _activeCancelRequestedTimestamp);
            R3DiagnosticReport? engineReport = (_executor as DesktopTransferEngine)?.LastR3DiagnosticReport;
            if (cancelReqTs > 0)
            {
                long releaseTs = Stopwatch.GetTimestamp();
                long uiTs = Volatile.Read(ref _activeCancelUiUpdatedTimestamp);
                if (uiTs <= 0) uiTs = releaseTs;
                double uiMs = Math.Round(Math.Max(0.0, Stopwatch.GetElapsedTime(cancelReqTs, uiTs).TotalMilliseconds), 2);
                double relMs = Math.Round(Math.Max(0.0, Stopwatch.GetElapsedTime(cancelReqTs, releaseTs).TotalMilliseconds), 2);
                LastCancellationTiming = new DesktopCancellationTiming(cancelReqTs, uiTs, releaseTs, uiMs, relMs);
                if (engineReport != null)
                {
                    engineReport = engineReport with
                    {
                        Cancellation = new R3CancellationInfo(
                            CancellationRequested: true,
                            UiCancelLatencyMs: MetricValue<double>.Available(uiMs),
                            ResourceReleaseLatencyMs: MetricValue<double>.Available(relMs))
                    };
                }
            }
            LastR3DiagnosticReport = engineReport;
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
        private int _completed;
        private const long ProgressIntervalMilliseconds = 200; // <= 5 Hz max
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

        public void MarkCompleted() => Interlocked.Exchange(ref _completed, 1);

        public override async Task<bool> ConfirmRecipientAsync(string peerName, CancellationToken cancellationToken)
        {
            OnPhase(_direction, DesktopTransferPhase.AwaitingConsent);
            // Recipient confirmation only authenticates the peer. The transfer engine reports
            // Transferring after the offer has been accepted and it is ready to send data.
            return await _inner.ConfirmRecipientAsync(peerName, cancellationToken).ConfigureAwait(false);
        }

        public override async Task<bool> ConfirmOfferAsync(DesktopOffer offer, CancellationToken cancellationToken)
        {
            _owner.NotifyEvent(new TransferEvent.OfferReceived(offer));
            OnPhase(_direction, DesktopTransferPhase.AwaitingConsent);
            // The decision must be written to the peer before the engine can enter its data phase.
            return await _inner.ConfirmOfferAsync(offer, cancellationToken).ConfigureAwait(false);
        }

        public override Task<bool> ConfirmPeerEnrollmentAsync(VerifiedDesktopPeerIdentity peer,
            CancellationToken cancellationToken) => _inner.ConfirmPeerEnrollmentAsync(peer, cancellationToken);
        public override void OnInvitationConsumed(DesktopTransferDirection direction) => _inner.OnInvitationConsumed(direction);
        public override void OnPeerIdentified(DesktopTransferDirection direction, string peerName)
        {
            if (Volatile.Read(ref _completed) != 0) return;
            PeerName = peerName;
            _inner.OnPeerIdentified(direction, peerName);
            DesktopTransferSnapshot current = _owner.Snapshot;
            if (current.RunId == _runId) _owner.SetSnapshot(current with { PeerName = peerName });
        }
        public override void OnStatus(DesktopTransferDirection direction, string message)
        {
            if (Volatile.Read(ref _completed) != 0) return;
            _inner.OnStatus(direction, message);
        }
        public override void OnProgress(DesktopTransferProgress progress)
        {
            if (Volatile.Read(ref _completed) != 0) return;
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
            if (Volatile.Read(ref _completed) != 0) return;
            _inner.OnItemStatus(direction, itemName, status);
            _owner.NotifyEvent(new TransferEvent.ItemStatusChanged(direction, itemName, status));
        }
        public override void OnHistory(DesktopHistoryEntry entry) => _inner.OnHistory(entry);
        public override void OnPhase(DesktopTransferDirection direction, DesktopTransferPhase phase)
        {
            if (Volatile.Read(ref _completed) != 0) return;
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
                DesktopTransferPhase.Saving => "Saving files…",
                DesktopTransferPhase.WaitingForConfirmation => "Waiting for receiver confirmation…",
                _ => "Connecting…"
            };
            _owner.SetSnapshot(new DesktopTransferSnapshot(phase, direction, PeerName, message, RunId: _runId));
        }
    }

    private sealed class SilentInteraction : DesktopTransferInteractionBase
    {
        public static SilentInteraction Instance { get; } = new();
    }

    public async ValueTask DisposeAsync()
    {
        CancelActiveTransfer();
        Task? runningTask;
        lock (_gate)
        {
            runningTask = _activeTransferTask;
        }

        if (runningTask != null)
        {
            try
            {
                await runningTask.ConfigureAwait(false);
            }
            catch
            {
                // Active transfer observed cancellation and completed cleanup.
            }
        }
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
    Saving,
    WaitingForConfirmation,
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
        DesktopTransferPhase.Paused or DesktopTransferPhase.Verifying or DesktopTransferPhase.Saving or
        DesktopTransferPhase.WaitingForConfirmation;
}
