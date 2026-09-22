using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using Glide.Core.Pairing;
using Glide.Transport.Sockets;
using Glide.Transport.Web;

namespace Glide.Windows.UI.Services;

/// <summary>
/// Manages the lifecycle of the TLS socket listener for LAN transfers (HARD-032).
///
/// Handles:
/// - Listener creation and disposal
/// - Invitation rotation loop
/// - Inbound connection acceptance
/// </summary>
public sealed class LanListenerService : IDisposable
{
    private TlsSocketListener? _listener;
    private CancellationTokenSource? _invitationRotationCts;
    private CancellationTokenSource? _listenerLoopCts;

    public int BoundPort => _listener?.BoundPort ?? 0;
    public bool IsActive => _listener != null;

    public event Action<Exception>? ListenerError;

    /// <summary>
    /// Creates and starts a TLS listener on the preferred port.
    /// </summary>
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

    /// <summary>
    /// Stops the listener and associated loops.
    /// </summary>
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

    /// <summary>
    /// Begins the invitation rotation loop.
    /// </summary>
    public void StartInvitationRotation(
        TimeSpan interval,
        Func<CancellationToken, Task> onRotation,
        CancellationToken externalToken)
    {
        _invitationRotationCts?.Dispose();
        _invitationRotationCts = CancellationTokenSource.CreateLinkedTokenSource(externalToken);
        var token = _invitationRotationCts.Token;

        _ = Task.Run(async () =>
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    await Task.Delay(interval, token);
                }
                catch (OperationCanceledException)
                {
                    break;
                }

                try
                {
                    await onRotation(token);
                }
                catch (Exception ex)
                {
                    ListenerError?.Invoke(ex);
                }
            }
        }, token);
    }

    /// <summary>
    /// Begins accepting inbound connections.
    /// </summary>
    public void StartAcceptingConnections(
        Func<TlsSocketConnection, CancellationToken, Task> onConnection,
        CancellationToken externalToken)
    {
        _listenerLoopCts?.Dispose();
        _listenerLoopCts = CancellationTokenSource.CreateLinkedTokenSource(externalToken);
        var token = _listenerLoopCts.Token;

        _ = Task.Run(async () =>
        {
            while (_listener != null && !token.IsCancellationRequested)
            {
                try
                {
                    var conn = await _listener!.AcceptConnectionAsync();
                    _ = Task.Run(() => onConnection(conn, token), token);
                }
                catch (Exception ex)
                {
                    ListenerError?.Invoke(ex);
                    break;
                }
            }
        }, token);
    }

public TlsSocketListener? GetListener() => _listener;

    public void Dispose()
    {
        Stop();
    }
}
