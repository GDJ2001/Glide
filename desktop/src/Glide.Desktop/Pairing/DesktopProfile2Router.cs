using Glide.Core.Pairing;
using Glide.Desktop.Services;
using Glide.Transport.Sockets;

namespace Glide.Desktop.Pairing;

/// <summary>
/// Routes one accepted GLD2 (Profile 2) connection by its declared purpose. Unknown
/// purposes and malformed or slow openers fail closed. A PAIR_ONLY session can never
/// reach transfer code; TRUSTED_SEND reaches it only when file receiving is enabled.
/// </summary>
public static class DesktopProfile2Router
{
    public static readonly TimeSpan OpenDeadline = TimeSpan.FromSeconds(10);

    public static Task RouteAsync(
        TlsSocketConnection connection,
        DesktopPairingCoordinator pairing,
        Func<TlsSocketConnection, Profile2Message.SessionOpen, CancellationToken, Task>? trustedSendHandler,
        CancellationToken cancellationToken) =>
        RouteAsync(connection, pairing, trustedSendHandler, openDeadline: null, cancellationToken);

    public static async Task RouteAsync(
        TlsSocketConnection connection,
        DesktopPairingCoordinator pairing,
        Func<TlsSocketConnection, Profile2Message.SessionOpen, CancellationToken, Task>? trustedSendHandler,
        TimeSpan? openDeadline,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(connection);
        ArgumentNullException.ThrowIfNull(pairing);

        TimeSpan effectiveDeadline = openDeadline ?? pairing.TimeoutPolicy.ProtocolPreambleTimeout;
        if (effectiveDeadline <= TimeSpan.Zero)
        {
            effectiveDeadline = OpenDeadline;
        }

        Profile2Message.SessionOpen open;
        try
        {
            using var deadline = pairing.TimeoutPolicy.CreateTimeoutTokenSource(effectiveDeadline, cancellationToken);
            Profile2Message first = await Profile2WireCodec
                .ReadMessageAsync(connection.TransportStream, deadline.Token)
                .ConfigureAwait(false);
            if (first is not Profile2Message.SessionOpen parsed)
            {
                await connection.DisposeAsync().ConfigureAwait(false);
                return;
            }
            open = parsed;
        }
        catch (Exception) when (!cancellationToken.IsCancellationRequested)
        {
            await connection.DisposeAsync().ConfigureAwait(false);
            return;
        }

        DesktopListenerSupervisor.NotifyAdmissionCompleted();

        if (string.Equals(open.Purpose, Profile2Constants.PurposePairOnly, StringComparison.Ordinal))
        {
            await pairing.HandleInboundAsync(connection, open, cancellationToken).ConfigureAwait(false);
            return;
        }

        if (string.Equals(open.Purpose, Profile2Constants.PurposeTrustedSend, StringComparison.Ordinal)
            && trustedSendHandler != null)
        {
            await trustedSendHandler(connection, open, cancellationToken).ConfigureAwait(false);
            return;
        }

        await connection.DisposeAsync().ConfigureAwait(false);
    }
}
