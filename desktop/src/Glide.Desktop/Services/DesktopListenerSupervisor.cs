using Glide.Transport.Sockets;

namespace Glide.Desktop.Services;

public sealed class DesktopListenerSupervisor : IDisposable
{
    private TlsSocketListener? _listener;
    private CancellationTokenSource? _invitationRotationCts;
    private CancellationTokenSource? _listenerLoopCts;
    private Task? _invitationRotationTask;
    private Task? _listenerLoopTask;

    public int BoundPort => _listener?.BoundPort ?? 0;
    public bool IsActive => _listener != null;
    public TlsSocketListener? Listener => _listener;

    public event Action<Exception>? ListenerError;

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
        if (rotation != null) await rotation;
        if (loop != null) await loop;
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
                    await Task.Delay(interval, token);
                    if (!token.IsCancellationRequested)
                    {
                        await onRotation(token);
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
            while (!token.IsCancellationRequested)
            {
                try
                {
                    TlsSocketConnection conn = await listener.AcceptConnectionAsync(token);
                    try
                    {
                        if (onProfile2ConnectionAccepted != null)
                        {
                            var dispatch = await Glide.Transport.Framing.LanSessionPreambleInspector
                                .InspectAndPrepareAsync(conn, token);
                            switch (dispatch)
                            {
                                case Glide.Transport.Framing.LanSessionDispatchKind.Profile2Gld2:
                                    await onProfile2ConnectionAccepted(conn, token);
                                    break;
                                case Glide.Transport.Framing.LanSessionDispatchKind.Profile1LanRecord:
                                    await onConnectionAccepted(conn, token);
                                    break;
                                default:
                                    await conn.DisposeAsync();
                                    break;
                            }
                        }
                        else
                        {
                            // Await each authenticated session: one GUI listener must not
                            // accumulate unbounded connection tasks in the background.
                            await onConnectionAccepted(conn, token);
                        }
                    }
                    catch (Exception ex) when (!token.IsCancellationRequested)
                    {
                        await conn.DisposeAsync();
                        ListenerError?.Invoke(ex);
                    }
                }
                catch (OperationCanceledException) when (token.IsCancellationRequested)
                {
                    break;
                }
                catch (OperationCanceledException ex)
                {
                    ListenerError?.Invoke(ex);
                }
                catch (ObjectDisposedException) when (token.IsCancellationRequested)
                {
                    break;
                }
                catch (Exception ex)
                {
                    if (!token.IsCancellationRequested)
                    {
                        ListenerError?.Invoke(ex);
                    }
                }
            }
        }, token);
    }

    public void Dispose()
    {
        Stop();
    }
}
