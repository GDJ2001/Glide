using Glide.Transport.Sockets;

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
            token => _executor.SendAsync(request, interaction, token),
            cancellationToken);

    public Task<DesktopTransferResult> ReceiveAsync(
        TlsSocketConnection connection,
        DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default) =>
        RunAsync(
            DesktopTransferDirection.Receive,
            "Incoming peer",
            token => _executor.ReceiveAsync(connection, request, interaction, token),
            cancellationToken);

    public Task<DesktopTransferResult> SendHostedAsync(
        TlsSocketConnection connection,
        DesktopSendRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default) =>
        RunAsync(
            DesktopTransferDirection.Send,
            "Connecting device",
            token => _executor.SendHostedAsync(connection, request, interaction, token),
            cancellationToken);

    private async Task<DesktopTransferResult> RunAsync(
        DesktopTransferDirection direction,
        string peerName,
        Func<CancellationToken, Task<DesktopTransferResult>> operation,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(operation);

        if (!await _activeBatch.WaitAsync(0, cancellationToken))
        {
            return DesktopTransferResult.Failed(
                "Glide is busy with another transfer. Wait for it to finish or cancel it before starting a new one.");
        }

        using var linkedCancellation =
            CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        lock (_gate)
        {
            _activeCancellation = linkedCancellation;
        }

        SetSnapshot(new DesktopTransferSnapshot(
            DesktopTransferPhase.Preparing,
            direction,
            peerName,
            "Preparing transfer…"));

        try
        {
            SetSnapshot(new DesktopTransferSnapshot(
                DesktopTransferPhase.Transferring,
                direction,
                peerName,
                "Transfer in progress."));

            DesktopTransferResult result = await operation(linkedCancellation.Token);
            DesktopTransferPhase phase = result switch
            {
                { Success: true } => DesktopTransferPhase.Completed,
                { Cancelled: true } => DesktopTransferPhase.Cancelled,
                { Rejected: true } => DesktopTransferPhase.Cancelled,
                _ => DesktopTransferPhase.Failed
            };
            SetSnapshot(new DesktopTransferSnapshot(phase, direction, peerName, result.Message));
            return result;
        }
        catch (OperationCanceledException) when (linkedCancellation.IsCancellationRequested)
        {
            const string message = "Transfer cancelled.";
            SetSnapshot(new DesktopTransferSnapshot(
                DesktopTransferPhase.Cancelled,
                direction,
                peerName,
                message));
            return DesktopTransferResult.CancelledByUser(message);
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
    string Message)
{
    public static DesktopTransferSnapshot Idle { get; } =
        new(DesktopTransferPhase.Idle, null, string.Empty, "Ready");

    public bool IsActive => Phase is DesktopTransferPhase.Preparing or
        DesktopTransferPhase.AwaitingConsent or DesktopTransferPhase.Transferring or
        DesktopTransferPhase.Paused or DesktopTransferPhase.Verifying;
}
