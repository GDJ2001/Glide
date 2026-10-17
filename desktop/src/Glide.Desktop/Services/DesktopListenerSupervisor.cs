using System.Collections.Concurrent;
using System.Net.Sockets;
using Glide.Transport.Sockets;

namespace Glide.Desktop.Services;

public sealed class DesktopListenerSupervisor : IDisposable
{
    private static readonly AsyncLocal<Action?> CurrentAdmissionCompleter = new();

    private readonly SemaphoreSlim _profile1SessionLease = new(1, 1);
    private TlsSocketListener? _listener;
    private CancellationTokenSource? _invitationRotationCts;
    private CancellationTokenSource? _listenerLoopCts;
    private Task? _invitationRotationTask;
    private Task? _listenerLoopTask;
    private int _activeAdmissions;

    public DesktopListenerSupervisor(DesktopTimeoutPolicy? timeoutPolicy = null)
    {
        TimeoutPolicy = timeoutPolicy ?? DesktopTimeoutPolicy.Default;
    }

    public DesktopTimeoutPolicy TimeoutPolicy { get; set; }
    public int BoundPort => _listener?.BoundPort ?? 0;
    public bool IsActive => _listener != null;
    public TlsSocketListener? Listener => _listener;
    public int ActiveAdmissionCount => Volatile.Read(ref _activeAdmissions);

    public event Action<Exception>? ListenerError;

    public static void NotifyAdmissionCompleted()
    {
        Action? completer = CurrentAdmissionCompleter.Value;
        CurrentAdmissionCompleter.Value = null;
        completer?.Invoke();
    }

    public int Start(int preferredPort)
    {
        Stop();

        try
        {
            _listener = new TlsSocketListener(preferredPort);
        }
        catch
        {
            _listener = new TlsSocketListener(0);
        }

        return _listener.BoundPort;
    }

    public void Stop()
    {
        _invitationRotationCts?.Cancel();
        _invitationRotationCts?.Dispose();
        _invitationRotationCts = null;

        _listenerLoopCts?.Cancel();
        _listenerLoopCts?.Dispose();
        _listenerLoopCts = null;

        _listener?.Dispose();
        _listener = null;
    }

    public async Task StopAsync()
    {
        Task? rotation = _invitationRotationTask;
        Task? loop = _listenerLoopTask;
        Stop();
        if (rotation != null)
        {
            try { await rotation.ConfigureAwait(false); } catch { }
        }
        if (loop != null)
        {
            try { await loop.ConfigureAwait(false); } catch { }
        }
        _invitationRotationTask = null;
        _listenerLoopTask = null;
    }

    public void StartInvitationRotation(
        TimeSpan interval,
        Func<CancellationToken, Task> onRotation,
        CancellationToken externalToken)
    {
        _invitationRotationCts?.Cancel();
        _invitationRotationCts?.Dispose();
        _invitationRotationCts = CancellationTokenSource.CreateLinkedTokenSource(externalToken);
        var token = _invitationRotationCts.Token;

        _invitationRotationTask = Task.Run(async () =>
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    await Task.Delay(interval, token).ConfigureAwait(false);
                    if (!token.IsCancellationRequested)
                    {
                        await onRotation(token).ConfigureAwait(false);
                    }
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch (Exception ex)
                {
                    ListenerError?.Invoke(ex);
                }
            }
        }, token);
    }

    public void StartListenerLoop(
        Func<TlsSocketConnection, CancellationToken, Task> onConnectionAccepted,
        CancellationToken externalToken,
        Func<TlsSocketConnection, CancellationToken, Task>? onProfile2ConnectionAccepted = null)
    {
        if (_listener == null) throw new InvalidOperationException("Start the listener before accepting connections.");

        _listenerLoopCts?.Cancel();
        _listenerLoopCts?.Dispose();
        _listenerLoopCts = CancellationTokenSource.CreateLinkedTokenSource(externalToken);
        var token = _listenerLoopCts.Token;
        TlsSocketListener listener = _listener;

        _listenerLoopTask = Task.Run(async () =>
        {
            var inflightTasks = new ConcurrentDictionary<long, Task>();
            long nextConnectionId = 0;

            try
            {
                while (!token.IsCancellationRequested)
                {
                    TcpClient tcpClient;
                    try
                    {
                        tcpClient = await listener.AcceptTcpClientAsync(token).ConfigureAwait(false);
                    }
                    catch (OperationCanceledException) when (token.IsCancellationRequested)
                    {
                        break;
                    }
                    catch (ObjectDisposedException) when (token.IsCancellationRequested)
                    {
                        break;
                    }
                    catch (SocketException) when (token.IsCancellationRequested)
                    {
                        break;
                    }
                    catch (Exception ex)
                    {
                        if (!token.IsCancellationRequested)
                        {
                            ListenerError?.Invoke(ex);
                        }
                        continue;
                    }

                    int maxAdmissions = Math.Max(1, TimeoutPolicy.MaxConcurrentAdmissions);
                    if (!TryAcquireAdmissionSlot(maxAdmissions))
                    {
                        try { tcpClient.Client?.Close(0); } catch { }
                        try { tcpClient.Close(); } catch { }
                        try { tcpClient.Dispose(); } catch { }
                        continue;
                    }

                    long connectionId = Interlocked.Increment(ref nextConnectionId);
                    Task clientTask = HandleAcceptedTcpClientAsync(
                        listener,
                        tcpClient,
                        onConnectionAccepted,
                        onProfile2ConnectionAccepted,
                        token);

                    inflightTasks[connectionId] = clientTask;
                    _ = clientTask.ContinueWith(
                        _ => inflightTasks.TryRemove(connectionId, out Task? _),
                        TaskScheduler.Default);
                }
            }
            finally
            {
                Task[] remaining = inflightTasks.Values.ToArray();
                if (remaining.Length > 0)
                {
                    try { await Task.WhenAll(remaining).ConfigureAwait(false); } catch { }
                }
            }
        }, token);
    }

    private bool TryAcquireAdmissionSlot(int maxAdmissions)
    {
        while (true)
        {
            int current = Volatile.Read(ref _activeAdmissions);
            if (current >= maxAdmissions)
            {
                return false;
            }
            if (Interlocked.CompareExchange(ref _activeAdmissions, current + 1, current) == current)
            {
                return true;
            }
        }
    }

    private async Task HandleAcceptedTcpClientAsync(
        TlsSocketListener listener,
        TcpClient tcpClient,
        Func<TlsSocketConnection, CancellationToken, Task> onConnectionAccepted,
        Func<TlsSocketConnection, CancellationToken, Task>? onProfile2ConnectionAccepted,
        CancellationToken token)
    {
        int admissionReleased = 0;
        void ReleaseAdmissionOnce()
        {
            if (Interlocked.Exchange(ref admissionReleased, 1) == 0)
            {
                Interlocked.Decrement(ref _activeAdmissions);
            }
        }

        CurrentAdmissionCompleter.Value = ReleaseAdmissionOnce;
        TlsSocketConnection? conn = null;
        try
        {
            conn = await listener.AuthenticateAcceptedClientAsync(
                tcpClient,
                token,
                TimeoutPolicy.TlsHandshakeTimeout).ConfigureAwait(false);

            if (onProfile2ConnectionAccepted != null)
            {
                var dispatch = await Glide.Transport.Framing.LanSessionPreambleInspector
                    .InspectAndPrepareAsync(conn, token, TimeoutPolicy.ProtocolPreambleTimeout)
                    .ConfigureAwait(false);

                switch (dispatch)
                {
                    case Glide.Transport.Framing.LanSessionDispatchKind.Profile2Gld2:
                        await onProfile2ConnectionAccepted(conn, token).ConfigureAwait(false);
                        break;

                    case Glide.Transport.Framing.LanSessionDispatchKind.Profile1LanRecord:
                        if (!_profile1SessionLease.Wait(0))
                        {
                            ReleaseAdmissionOnce();
                            await conn.DisposeAsync().ConfigureAwait(false);
                            break;
                        }
                        try
                        {
                            ReleaseAdmissionOnce();
                            await onConnectionAccepted(conn, token).ConfigureAwait(false);
                        }
                        finally
                        {
                            _profile1SessionLease.Release();
                        }
                        break;

                    default:
                        ReleaseAdmissionOnce();
                        await conn.DisposeAsync().ConfigureAwait(false);
                        break;
                }
            }
            else
            {
                if (!_profile1SessionLease.Wait(0))
                {
                    ReleaseAdmissionOnce();
                    await conn.DisposeAsync().ConfigureAwait(false);
                    return;
                }
                try
                {
                    ReleaseAdmissionOnce();
                    await onConnectionAccepted(conn, token).ConfigureAwait(false);
                }
                finally
                {
                    _profile1SessionLease.Release();
                }
            }
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested)
        {
            if (conn != null)
            {
                try { await conn.DisposeAsync().ConfigureAwait(false); } catch { }
            }
        }
        catch (Exception ex)
        {
            if (conn != null)
            {
                try { await conn.DisposeAsync().ConfigureAwait(false); } catch { }
            }
            if (!token.IsCancellationRequested)
            {
                ListenerError?.Invoke(ex);
            }
        }
        finally
        {
            CurrentAdmissionCompleter.Value = null;
            ReleaseAdmissionOnce();
        }
    }

    public void Dispose()
    {
        Stop();
    }
}
