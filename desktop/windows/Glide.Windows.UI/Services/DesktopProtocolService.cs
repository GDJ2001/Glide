using System.Net.Sockets;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Desktop;
using Glide.Transport.Sockets;

namespace Glide.Windows.UI.Services;

/// <summary>
/// Desktop transfer protocol service (HARD-032).
///
/// Handles:
/// - Send protocol execution (connect, bootstrap, offer, stream)
/// - Receive protocol execution (accept, bootstrap, receive, verify)
/// - Receiver-as-client execution (connect, bootstrap, receive, verify from invitation)
/// - Transfer engine delegation
/// </summary>
public sealed class DesktopProtocolService
{
    private readonly DesktopTransferEngine _engine = new();

    /// <summary>
    /// Executes a paired send operation.
    /// </summary>
    public async Task<DesktopTransferResult> SendAsync(
        DesktopSendRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken)
    {
        return await _engine.SendAsync(request, interaction, cancellationToken);
    }

    /// <summary>
    /// Executes a receive operation on an accepted connection.
    /// </summary>
    public async Task<DesktopTransferResult> ReceiveAsync(
        TlsSocketConnection connection,
        DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken)
    {
        return await _engine.ReceiveAsync(connection, request, interaction, cancellationToken);
    }

    /// <summary>
    /// Executes a receiver-as-client operation connecting to a sender-hosted endpoint.
    /// </summary>
    public async Task<DesktopTransferResult> ReceiveFromInvitationAsync(
        DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken)
    {
        return await _engine.ReceiveFromInvitationAsync(request, interaction, cancellationToken);
    }
}
