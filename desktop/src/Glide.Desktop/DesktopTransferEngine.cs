using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading.Channels;
using Glide.Core.Codec;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Core.Transport.Lan;
using Glide.Desktop.Diagnostics;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;

namespace Glide.Desktop;

/// <summary>
/// Shared desktop application/session engine for CLI and WPF.
///
/// LAN profile 1 is invitation-authenticated and server-pinned. Control envelopes use independent
/// strictly monotonic sequences per direction. Offers are paged at the cross-platform 32-item
/// ceiling, all identifiers are correlated exactly, control/data reads have deadlines, and file
/// hashing is performed incrementally while bytes are streamed.
/// </summary>
public sealed class DesktopTransferEngine : IDesktopTransferExecutor
{
    // A person must be able to inspect and accept an incoming offer. The
    // protocol gives that decision 120 seconds; the generic 30-second control
    // timeout is only suitable for machine-to-machine responses.
    private static readonly TimeSpan OfferDecisionTimeout = TimeSpan.FromSeconds(120);
    private const int MaxOfferPageItems = 32;
    private const int SafeOfferPagePayloadBytes = 14_000;
    private const int StreamBufferBytes = 65_520;

    private readonly IDesktopTransferDiagnostics _diagnostics;
    private readonly DesktopTransferIoOptions _ioOptions;
    private readonly TransferBufferPool _bufferPool;

    public DesktopTransferEngine(
        IDesktopTransferDiagnostics? diagnostics = null,
        DesktopTransferIoOptions? ioOptions = null,
        TransferBufferPool? bufferPool = null)
    {
        _diagnostics = diagnostics ?? BoundedTransferDiagnosticSink.CreateDefault();
        _ioOptions = ioOptions ?? DesktopTransferIoOptions.Default;
        _bufferPool = bufferPool ?? (
            _ioOptions.MaxProcessTransferBufferBytes == TransferBufferPool.DefaultMaxProcessBytes &&
            _ioOptions.ChunkBufferBytes <= TransferBufferPool.DefaultBufferBytes
                ? TransferBufferPool.Shared
                : new TransferBufferPool(
                    (int)Math.Min(int.MaxValue, _ioOptions.MaxProcessTransferBufferBytes),
                    Math.Max(4096, _ioOptions.ChunkBufferBytes)));
    }

    public IDesktopTransferDiagnostics Diagnostics => _diagnostics;
    public DesktopTransferIoOptions IoOptions => _ioOptions;
    public TransferBufferPool BufferPool => _bufferPool;
    public DesktopTransferDiagnosticSample? LastDiagnosticSample { get; private set; }
    public IReadOnlyList<DesktopTransferDiagnosticSample> LastDiagnosticSamples { get; private set; } = [];

    private sealed record PreparedSendItem(
        ItemId ItemId,
        DesktopTransferFile File,
        long DeclaredSize,
        DateTime LastWriteTimeUtc);

    public async Task<DesktopTransferResult> SendAsync(
        DesktopSendRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        ArgumentNullException.ThrowIfNull(interaction);

        if (cancellationToken.IsCancellationRequested)
        {
            return DesktopTransferResult.CancelledByUser("Transfer cancelled.");
        }

        if (request.Invitation == null)
        {
            if (!string.IsNullOrWhiteSpace(request.ExpectedPeerFingerprintHex))
            {
                return await SendTrustedAsync(request, interaction, cancellationToken);
            }

            return DesktopTransferResult.Failed(
                "Authenticated pairing invitation required. Scan or paste a current Glide pairing invitation before sending.");
        }

        if (request.Invitation.InvitationHash.Length != 64)
        {
            return DesktopTransferResult.Failed(
                "Pairing invitation is not canonical. Scan or paste the encoded pairing invitation again.");
        }

        if (request.Files.Count == 0)
        {
            return DesktopTransferResult.Failed("No files were selected for transfer.");
        }

        if (request.Files.Count > 1_000)
        {
            return DesktopTransferResult.Failed(
                "A transfer batch cannot contain more than 1,000 items.");
        }

        long totalBytes = 0;
        foreach (DesktopTransferFile file in request.Files)
        {
            if (!File.Exists(file.FilePath))
            {
                return DesktopTransferResult.Failed(
                    $"File not found: '{file.FilePath}'.");
            }

            long size = file.SizeBytes;
            if (size < 0 || size > ProtocolCatalog.MaxItemBytes)
            {
                return DesktopTransferResult.Failed(
                    $"File '{file.Name}' exceeds the protocol item-size limit.");
            }

            try
            {
                totalBytes = checked(totalBytes + size);
            }
            catch (OverflowException)
            {
                return DesktopTransferResult.Failed(
                    "Selected files exceed the protocol batch-size limit.");
            }

            if (totalBytes > ProtocolCatalog.MaxBatchBytes)
            {
                return DesktopTransferResult.Failed(
                    "Selected files exceed the protocol batch-size limit.");
            }
        }

        try
        {
            LanInvitation invitation = request.Invitation;
            if (invitation.IsExpired())
            {
                return DesktopTransferResult.Failed(
                    "Pairing invitation has expired. Request a new pairing code.");
            }

            var client = new TlsSocketClient();
            TlsSocketConnection? connectedSocket = null;
            Exception? lastConnectException = null;

            IReadOnlyList<LanEndpoint> endpointsToTry = !string.IsNullOrWhiteSpace(request.Host)
                ? new[] { new LanEndpoint(request.Host, request.Port > 0 ? request.Port : invitation.Endpoints.FirstOrDefault()?.Port ?? 42424) }
                : invitation.Endpoints;

            foreach (LanEndpoint endpoint in endpointsToTry)
            {
                string host = endpoint.Address;
                int port = endpoint.Port;

                interaction.OnStatus(
                    DesktopTransferDirection.Send,
                    $"Connecting to {host}:{port} over TLS 1.3...");

                try
                {
                    connectedSocket = await client.ConnectAsync(
                        host,
                        port,
                        expectedSpkiSha256: invitation.SpkiSha256,
                        cancellationToken);
                    break;
                }
                catch (Exception ex) when (ex is not OperationCanceledException)
                {
                    lastConnectException = ex;
                }
            }

            if (connectedSocket == null)
            {
                throw lastConnectException ?? new InvalidOperationException("Unable to connect to any endpoint in pairing invitation.");
            }

            await using TlsSocketConnection connection = connectedSocket;

            return await RunSenderSessionAsync(
                connection,
                request,
                totalBytes,
                interaction,
                cancellationToken);
        }
        catch (OperationCanceledException)
            when (cancellationToken.IsCancellationRequested)
        {
            interaction.OnStatus(
                DesktopTransferDirection.Send,
                "Transfer cancelled.");
            return DesktopTransferResult.CancelledByUser(
                "Transfer cancelled.");
        }
        catch (Exception ex)
        {
            interaction.OnStatus(
                DesktopTransferDirection.Send,
                $"Send failed: {ex.Message}");
            return DesktopTransferResult.Failed(
                $"Send failed: {ex.Message}", DesktopTransferFailures.Classify(ex));
        }
    }

    public async Task<DesktopTransferResult> ReceiveAsync(
        TlsSocketConnection connection,
        DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(connection);
        ArgumentNullException.ThrowIfNull(request);
        ArgumentNullException.ThrowIfNull(interaction);

        try
        {
            return await RunReceiverSessionAsync(
                connection,
                request,
                interaction,
                cancellationToken);
        }
        catch (OperationCanceledException)
            when (cancellationToken.IsCancellationRequested)
        {
            interaction.OnStatus(
                DesktopTransferDirection.Receive,
                "Transfer cancelled.");
            return DesktopTransferResult.CancelledByUser(
                "Transfer cancelled.");
        }
        catch (Exception ex)
        {
            interaction.OnStatus(
                DesktopTransferDirection.Receive,
                $"Receive failed: {ex.Message}");
            return DesktopTransferResult.Failed(
                $"Receive failed: {ex.Message}", DesktopTransferFailures.Classify(ex));
        }
    }

    public async Task<DesktopTransferResult> ReceiveFromInvitationAsync(
        DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        ArgumentNullException.ThrowIfNull(interaction);

        if (cancellationToken.IsCancellationRequested)
        {
            return DesktopTransferResult.CancelledByUser("Transfer cancelled.");
        }

        if (request.Invitation == null)
        {
            return DesktopTransferResult.Failed(
                "Authenticated pairing invitation required. Scan or paste a current Glide pairing invitation before receiving.");
        }

        if (request.Invitation.InvitationHash.Length != 64)
        {
            return DesktopTransferResult.Failed(
                "Pairing invitation is not canonical. Scan or paste the encoded pairing invitation again.");
        }

        try
        {
            LanInvitation invitation = request.Invitation;
            if (invitation.IsExpired())
            {
                return DesktopTransferResult.Failed(
                    "Pairing invitation has expired. Request a new pairing code.");
            }

            var client = new TlsSocketClient();
            TlsSocketConnection? connectedSocket = null;
            Exception? lastConnectException = null;

            foreach (LanEndpoint endpoint in invitation.Endpoints)
            {
                string host = endpoint.Address;
                int port = endpoint.Port;

                interaction.OnStatus(
                    DesktopTransferDirection.Receive,
                    $"Connecting to sender {host}:{port} over TLS 1.3...");

                try
                {
                    connectedSocket = await client.ConnectAsync(
                        host,
                        port,
                        expectedSpkiSha256: invitation.SpkiSha256,
                        cancellationToken);
                    break;
                }
                catch (Exception ex) when (ex is not OperationCanceledException)
                {
                    lastConnectException = ex;
                }
            }

            if (connectedSocket == null)
            {
                throw lastConnectException ?? new InvalidOperationException("Unable to connect to any endpoint in pairing invitation.");
            }

            await using TlsSocketConnection connection = connectedSocket;

            return await RunReceiverSessionAsync(
                connection,
                request,
                interaction,
                cancellationToken,
                localEndpointHostsTls: false);
        }
        catch (OperationCanceledException)
            when (cancellationToken.IsCancellationRequested)
        {
            interaction.OnStatus(
                DesktopTransferDirection.Receive,
                "Transfer cancelled.");
            return DesktopTransferResult.CancelledByUser(
                "Transfer cancelled.");
        }
        catch (Exception ex)
        {
            interaction.OnStatus(
                DesktopTransferDirection.Receive,
                $"Receive failed: {ex.Message}");
            return DesktopTransferResult.Failed(
                $"Receive failed: {ex.Message}", DesktopTransferFailures.Classify(ex));
        }
    }

    public async Task<DesktopTransferResult> SendHostedAsync(
        TlsSocketConnection connection,
        DesktopSendRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(connection);
        ArgumentNullException.ThrowIfNull(request);
        ArgumentNullException.ThrowIfNull(interaction);

        if (request.HostedInvitationAuthority == null)
        {
            return DesktopTransferResult.Failed(
                "A hosted pairing authority is required for this transfer.");
        }

        if (request.Invitation == null || request.Invitation.InvitationHash.Length != 64)
        {
            return DesktopTransferResult.Failed(
                "The displayed pairing code is no longer valid. Refresh it and try again.");
        }

        if (request.Files.Count == 0 || request.Files.Count > 1_000)
        {
            return DesktopTransferResult.Failed("Select between 1 and 1,000 files before pairing.");
        }

        long totalBytes = 0;
        foreach (DesktopTransferFile file in request.Files)
        {
            if (!File.Exists(file.FilePath))
            {
                return DesktopTransferResult.Failed($"File not found: '{file.FilePath}'.");
            }

            long size = file.SizeBytes;
            if (size < 0 || size > ProtocolCatalog.MaxItemBytes)
            {
                return DesktopTransferResult.Failed(
                    $"File '{file.Name}' exceeds the protocol item-size limit.");
            }

            try
            {
                totalBytes = checked(totalBytes + size);
            }
            catch (OverflowException)
            {
                return DesktopTransferResult.Failed(
                    "Selected files exceed the protocol batch-size limit.");
            }

            if (totalBytes > ProtocolCatalog.MaxBatchBytes)
            {
                return DesktopTransferResult.Failed(
                    "Selected files exceed the protocol batch-size limit.");
            }
        }

        try
        {
            return await RunSenderSessionAsync(
                connection,
                request,
                totalBytes,
                interaction,
                cancellationToken,
                request.HostedInvitationAuthority);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            return DesktopTransferResult.CancelledByUser("Transfer cancelled.");
        }
        catch (Exception ex)
        {
            interaction.OnStatus(DesktopTransferDirection.Send, $"Send failed: {ex.Message}");
            return DesktopTransferResult.Failed($"Send failed: {ex.Message}", DesktopTransferFailures.Classify(ex));
        }
    }

    public async Task<DesktopTransferResult> SendTrustedAsync(
        DesktopSendRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        ArgumentNullException.ThrowIfNull(interaction);

        if (cancellationToken.IsCancellationRequested)
        {
            return DesktopTransferResult.CancelledByUser("Transfer cancelled.");
        }

        if (request.TrustManager == null || string.IsNullOrWhiteSpace(request.ExpectedPeerFingerprintHex))
        {
            return DesktopTransferResult.Failed("Identity changed — pair again", TransferErrorCategory.Security);
        }

        request.TrustManager.Initialize();
        DesktopTrustedPeer? enrolledPeer = request.TrustManager.GetTrustedPeer(request.ExpectedPeerFingerprintHex);
        if (enrolledPeer == null)
        {
            return DesktopTransferResult.Failed("Identity changed — pair again", TransferErrorCategory.Security);
        }

        if (request.Files.Count == 0)
        {
            return DesktopTransferResult.Failed("No files were selected for transfer.");
        }

        if (request.Files.Count > 1_000)
        {
            return DesktopTransferResult.Failed("A transfer batch cannot contain more than 1,000 items.");
        }

        long totalBytes = 0;
        foreach (DesktopTransferFile file in request.Files)
        {
            if (!File.Exists(file.FilePath))
            {
                return DesktopTransferResult.Failed($"File not found: '{file.FilePath}'.");
            }

            long size = file.SizeBytes;
            if (size < 0 || size > ProtocolCatalog.MaxItemBytes)
            {
                return DesktopTransferResult.Failed($"File '{file.Name}' exceeds the protocol item-size limit.");
            }

            try
            {
                totalBytes = checked(totalBytes + size);
            }
            catch (OverflowException)
            {
                return DesktopTransferResult.Failed("Selected files exceed the protocol batch-size limit.");
            }

            if (totalBytes > ProtocolCatalog.MaxBatchBytes)
            {
                return DesktopTransferResult.Failed("Selected files exceed the protocol batch-size limit.");
            }
        }

        IReadOnlyList<LanEndpoint> endpointsToTry;
        if (request.CandidateEndpoints is { Count: > 0 })
        {
            endpointsToTry = request.CandidateEndpoints;
        }
        else if (!string.IsNullOrWhiteSpace(request.Host))
        {
            endpointsToTry =
            [
                new LanEndpoint(
                    request.Host,
                    request.Port > 0 ? request.Port : enrolledPeer.LastKnownPort ?? 42424)
            ];
        }
        else if (!string.IsNullOrWhiteSpace(enrolledPeer.LastKnownHost))
        {
            endpointsToTry =
            [
                new LanEndpoint(
                    enrolledPeer.LastKnownHost,
                    enrolledPeer.LastKnownPort ?? 42424)
            ];
        }
        else
        {
            return DesktopTransferResult.Failed("Couldn’t reach this device — check the local network", TransferErrorCategory.Network);
        }

        TlsSocketConnection? connectedSocket = null;
        string? connectedHost = null;
        int connectedPort = 0;
        try
        {
            var client = new TlsSocketClient();
            foreach (LanEndpoint endpoint in endpointsToTry)
            {
                interaction.OnStatus(
                    DesktopTransferDirection.Send,
                    $"Connecting to {enrolledPeer.DisplayName} ({endpoint.Address}:{endpoint.Port})...");

                try
                {
                    connectedSocket = await client.ConnectAsync(
                        endpoint.Address,
                        endpoint.Port,
                        expectedSpkiSha256: null,
                        cancellationToken);
                    connectedHost = endpoint.Address;
                    connectedPort = endpoint.Port;
                    break;
                }
                catch (Exception ex) when (ex is not OperationCanceledException)
                {
                    // Try next candidate endpoint
                }
            }

            if (connectedSocket == null)
            {
                interaction.OnStatus(
                    DesktopTransferDirection.Send,
                    "Couldn’t reach this device — check the local network");
                return DesktopTransferResult.Failed("Couldn’t reach this device — check the local network", TransferErrorCategory.Network);
            }

            await using TlsSocketConnection connection = connectedSocket;

            await LanSessionPreambleInspector.WriteGld2PreambleAsync(
                connection.TransportStream,
                cancellationToken);

            SessionId sessionId = NewSessionId();
            string localName = string.IsNullOrWhiteSpace(request.LocalDeviceName)
                ? Environment.MachineName
                : request.LocalDeviceName;

            var open = new Profile2Message.SessionOpen(
                SessionId: sessionId.Value,
                Purpose: Profile2Constants.PurposeTrustedSend,
                AuthMethod: Profile2Constants.AuthMethodIdentityV1,
                InitiatorNonce: InvitationCodec.ToUnpaddedBase64Url(RandomNumberGenerator.GetBytes(32)),
                InitiatorDeviceName: localName,
                InitiatorPlatform: GetLocalPlatformString(),
                InitiatorIdentityPublicKey: InvitationCodec.ToUnpaddedBase64Url(request.TrustManager.LocalPublicKeyDer()),
                ExpectedPresenceId: request.ExpectedPresenceId);

            await Profile2WireCodec.WriteMessageAsync(
                connection.TransportStream,
                open,
                cancellationToken);

            Profile2Message ackRaw = await Profile2WireCodec.ReadMessageAsync(
                connection.TransportStream,
                cancellationToken);

            if (ackRaw is not Profile2Message.SessionOpenAck ack ||
                !string.Equals(ack.SessionId, sessionId.Value, StringComparison.Ordinal))
            {
            return DesktopTransferResult.Failed("Identity changed — pair again", TransferErrorCategory.Security);
            }

            if (!ack.Accepted)
            {
                string mapped = ack.ErrorCode switch
                {
                    Profile2Constants.ErrorBusy => "This device is busy — try again",
                    Profile2Constants.ErrorIdentityChanged or
                    Profile2Constants.ErrorUntrustedPeer or
                    Profile2Constants.ErrorAuthFailed => "Identity changed — pair again",
                    Profile2Constants.ErrorExpired => "Code expired — get a new code",
                    _ => Glide.Transport.Pairing.Profile2PairingEngine.MapErrorToUserCopy(
                        ack.ErrorCode,
                        enrolledPeer.DisplayName,
                        ack.Message)
                };
                interaction.OnStatus(DesktopTransferDirection.Send, mapped);
                return DesktopTransferResult.Failed(mapped, ack.ErrorCode switch
                {
                    Profile2Constants.ErrorBusy => TransferErrorCategory.Protocol,
                    Profile2Constants.ErrorIdentityChanged or Profile2Constants.ErrorUntrustedPeer or
                        Profile2Constants.ErrorAuthFailed => TransferErrorCategory.Security,
                    _ => TransferErrorCategory.Protocol
                });
            }

            byte[] responderSpki = InvitationCodec.FromUnpaddedBase64Url(ack.ResponderIdentityPublicKey ?? string.Empty);
            string responderFp = Convert.ToHexStringLower(SHA256.HashData(responderSpki));
            string responderPubHex = Convert.ToHexStringLower(responderSpki);

            if (!string.Equals(responderFp, enrolledPeer.FingerprintHex, StringComparison.Ordinal) ||
                !string.Equals(responderPubHex, enrolledPeer.PublicKeyDerHex, StringComparison.Ordinal))
            {
                interaction.OnStatus(DesktopTransferDirection.Send, "Identity changed — pair again");
            return DesktopTransferResult.Failed("Identity changed — pair again", TransferErrorCategory.Security);
            }

            DesktopTransferResult result = await RunSenderSessionAsync(
                connection,
                request,
                totalBytes,
                interaction,
                cancellationToken,
                hostedInvitationAuthority: null,
                preEstablishedTrustedSessionId: sessionId,
                trustedPeerFallbackName: ack.ResponderDeviceName ?? enrolledPeer.DisplayName);

            if (connectedHost != null)
            {
                request.TrustManager.EnrollVerifiedPeer(
                    new VerifiedDesktopPeerIdentity(
                        enrolledPeer.FingerprintHex,
                        enrolledPeer.PublicKeyDerHex,
                        ack.ResponderDeviceName ?? enrolledPeer.DisplayName,
                        ack.ResponderPlatform ?? enrolledPeer.Platform,
                        connectedHost,
                        connectedPort),
                    userApproved: true);
            }

            return result;
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            interaction.OnStatus(DesktopTransferDirection.Send, "Transfer cancelled.");
            return DesktopTransferResult.CancelledByUser("Transfer cancelled.");
        }
        catch (CryptographicException)
        {
            interaction.OnStatus(DesktopTransferDirection.Send, "Identity changed — pair again");
            return DesktopTransferResult.Failed("Identity changed — pair again", TransferErrorCategory.Security);
        }
        catch (ProtocolViolationException ex) when (string.Equals(ex.FailureCode, WireErrorCodes.AuthenticationFailed, StringComparison.Ordinal))
        {
            interaction.OnStatus(DesktopTransferDirection.Send, "Identity changed — pair again");
            return DesktopTransferResult.Failed("Identity changed — pair again", TransferErrorCategory.Security);
        }
        catch (Exception ex)
        {
            interaction.OnStatus(DesktopTransferDirection.Send, $"Send failed: {ex.Message}");
            return DesktopTransferResult.Failed($"Send failed: {ex.Message}", DesktopTransferFailures.Classify(ex));
        }
    }

    public async Task<DesktopTransferResult> ReceiveTrustedAsync(
        TlsSocketConnection connection,
        Profile2Message.SessionOpen open,
        DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction,
        bool isBusy = false,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(connection);
        ArgumentNullException.ThrowIfNull(open);
        ArgumentNullException.ThrowIfNull(request);
        ArgumentNullException.ThrowIfNull(interaction);

        string localName = string.IsNullOrWhiteSpace(request.LocalDeviceName)
            ? Environment.MachineName
            : request.LocalDeviceName;

        try
        {
            if (!string.Equals(open.Purpose, Profile2Constants.PurposeTrustedSend, StringComparison.Ordinal))
            {
                await Profile2WireCodec.WriteMessageAsync(
                    connection.TransportStream,
                    new Profile2Message.SessionOpenAck(
                        SessionId: open.SessionId,
                        Accepted: false,
                        ErrorCode: Profile2Constants.ErrorProtocolOrder,
                        Message: "Invalid session purpose for TRUSTED_SEND",
                        ResponderDeviceName: localName),
                    cancellationToken);
                return DesktopTransferResult.Failed("Invalid session purpose for TRUSTED_SEND.");
            }

            if (isBusy)
            {
                await Profile2WireCodec.WriteMessageAsync(
                    connection.TransportStream,
                    new Profile2Message.SessionOpenAck(
                        SessionId: open.SessionId,
                        Accepted: false,
                        ErrorCode: Profile2Constants.ErrorBusy,
                        Message: "This device is busy — try again",
                        ResponderDeviceName: localName),
                    cancellationToken);
                return DesktopTransferResult.RejectedByPeer("This device is busy — try again", TransferErrorCategory.Protocol);
            }

            if (!string.Equals(open.AuthMethod, Profile2Constants.AuthMethodIdentityV1, StringComparison.Ordinal))
            {
                await Profile2WireCodec.WriteMessageAsync(
                    connection.TransportStream,
                    new Profile2Message.SessionOpenAck(
                        SessionId: open.SessionId,
                        Accepted: false,
                        ErrorCode: Profile2Constants.ErrorUnsupportedVersion,
                        Message: "Update Glide to pair this device",
                        ResponderDeviceName: localName),
                    cancellationToken);
                return DesktopTransferResult.RejectedByPeer("Unsupported trusted send authentication method.", TransferErrorCategory.Protocol);
            }

            if (request.TrustManager == null)
            {
                await Profile2WireCodec.WriteMessageAsync(
                    connection.TransportStream,
                    new Profile2Message.SessionOpenAck(
                        SessionId: open.SessionId,
                        Accepted: false,
                        ErrorCode: Profile2Constants.ErrorUntrustedPeer,
                        Message: "Identity changed — pair again",
                        ResponderDeviceName: localName),
                    cancellationToken);
                return DesktopTransferResult.RejectedByPeer("Trusted reconnect requires a persistent trust manager.", TransferErrorCategory.Security);
            }

            request.TrustManager.Initialize();

            byte[] initiatorSpki;
            try
            {
                initiatorSpki = InvitationCodec.FromUnpaddedBase64Url(open.InitiatorIdentityPublicKey);
            }
            catch
            {
                await Profile2WireCodec.WriteMessageAsync(
                    connection.TransportStream,
                    new Profile2Message.SessionOpenAck(
                        SessionId: open.SessionId,
                        Accepted: false,
                        ErrorCode: Profile2Constants.ErrorAuthFailed,
                        Message: "Identity changed — pair again",
                        ResponderDeviceName: localName),
                    cancellationToken);
                return DesktopTransferResult.RejectedByPeer("Malformed initiator identity key.", TransferErrorCategory.Security);
            }

            string initiatorFp = Convert.ToHexStringLower(SHA256.HashData(initiatorSpki));
            string initiatorPubHex = Convert.ToHexStringLower(initiatorSpki);
            DesktopTrustedPeer? enrolledSender = request.TrustManager.GetTrustedPeer(initiatorFp);

            if (enrolledSender == null)
            {
                await Profile2WireCodec.WriteMessageAsync(
                    connection.TransportStream,
                    new Profile2Message.SessionOpenAck(
                        SessionId: open.SessionId,
                        Accepted: false,
                        ErrorCode: Profile2Constants.ErrorUntrustedPeer,
                        Message: "Identity changed — pair again",
                        ResponderDeviceName: localName),
                    cancellationToken);
                return DesktopTransferResult.RejectedByPeer("Trusted reconnect rejected: sender is not enrolled.", TransferErrorCategory.Security);
            }

            if (!string.Equals(enrolledSender.PublicKeyDerHex, initiatorPubHex, StringComparison.Ordinal))
            {
                await Profile2WireCodec.WriteMessageAsync(
                    connection.TransportStream,
                    new Profile2Message.SessionOpenAck(
                        SessionId: open.SessionId,
                        Accepted: false,
                        ErrorCode: Profile2Constants.ErrorIdentityChanged,
                        Message: "Identity changed — pair again",
                        ResponderDeviceName: localName),
                    cancellationToken);
                return DesktopTransferResult.RejectedByPeer("Trusted reconnect rejected: sender identity key changed.", TransferErrorCategory.Security);
            }

            await Profile2WireCodec.WriteMessageAsync(
                connection.TransportStream,
                new Profile2Message.SessionOpenAck(
                    SessionId: open.SessionId,
                    Accepted: true,
                    ResponderNonce: InvitationCodec.ToUnpaddedBase64Url(RandomNumberGenerator.GetBytes(32)),
                    ResponderDeviceName: localName,
                    ResponderPlatform: GetLocalPlatformString(),
                    ResponderIdentityPublicKey: InvitationCodec.ToUnpaddedBase64Url(request.TrustManager.LocalPublicKeyDer())),
                cancellationToken);

            DesktopReceiveRequest enforcedRequest = request with
            {
                ExpectedPeerFingerprintHex = enrolledSender.FingerprintHex
            };

            return await RunReceiverSessionAsync(
                connection,
                enforcedRequest,
                interaction,
                cancellationToken,
                localEndpointHostsTls: true,
                preEstablishedTrustedSessionId: new SessionId(open.SessionId));
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            interaction.OnStatus(DesktopTransferDirection.Receive, "Transfer cancelled.");
            return DesktopTransferResult.CancelledByUser("Transfer cancelled.");
        }
        catch (Exception ex)
        {
            interaction.OnStatus(DesktopTransferDirection.Receive, $"Receive failed: {ex.Message}");
            return DesktopTransferResult.Failed($"Receive failed: {ex.Message}", DesktopTransferFailures.Classify(ex));
        }
    }

    private static string GetLocalPlatformString()
    {
        if (OperatingSystem.IsWindows()) return "windows";
        if (OperatingSystem.IsLinux()) return "linux";
        if (OperatingSystem.IsMacOS()) return "macos";
        return "unknown";
    }

    private DesktopTransferResult CompleteAttempt(
        IDesktopTransferAttemptDiagnostics diag,
        DesktopTransferResult result)
    {
        LastDiagnosticSample = diag.Complete(result);
        LastDiagnosticSamples = diag.GetRecordedSamples();
        return result;
    }

    private async Task<DesktopTransferResult> RunSenderSessionAsync(
        TlsSocketConnection connection,
        DesktopSendRequest request,
        long totalBytes,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken,
        LanInvitationAuthority? hostedInvitationAuthority = null,
        SessionId? preEstablishedTrustedSessionId = null,
        string? trustedPeerFallbackName = null)
    {
        await using IDesktopTransferAttemptDiagnostics diag =
            _diagnostics.BeginAttempt(DesktopTransferDirection.Send, totalBytes);
        try
        {
            return await RunSenderSessionCoreAsync(
                connection,
                request,
                totalBytes,
                interaction,
                diag,
                cancellationToken,
                hostedInvitationAuthority,
                preEstablishedTrustedSessionId,
                trustedPeerFallbackName);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            CompleteAttempt(diag, DesktopTransferResult.CancelledByUser("Transfer cancelled."));
            throw;
        }
        catch (Exception ex)
        {
            CompleteAttempt(diag, DesktopTransferResult.Failed(ex.Message, DesktopTransferFailures.Classify(ex)));
            throw;
        }
    }

    private async Task<DesktopTransferResult> RunSenderSessionCoreAsync(
        TlsSocketConnection connection,
        DesktopSendRequest request,
        long totalBytes,
        IDesktopTransferInteraction interaction,
        IDesktopTransferAttemptDiagnostics diag,
        CancellationToken cancellationToken,
        LanInvitationAuthority? hostedInvitationAuthority = null,
        SessionId? preEstablishedTrustedSessionId = null,
        string? trustedPeerFallbackName = null)
    {
        await using var channel =
            new LanRecordChannel(
                connection.TransportStream,
                coalesceWrites: _ioOptions.EnableCoalescedFraming,
                coalesceBufferBytes: _ioOptions.WriteCoalesceBufferBytes,
                maxCoalesceDelayMs: (int)Math.Max(1, _ioOptions.MaxWriteBatchDelay.TotalMilliseconds),
                bufferPool: _bufferPool);
        var sequence = new DesktopControlSequence();
        SessionId sessionId;

        if (preEstablishedTrustedSessionId is { } trustedSessionId)
        {
            sessionId = trustedSessionId;
        }
        else
        {
            LanInvitation invitation = request.Invitation
                ?? throw new InvalidOperationException(
                    "Authenticated pairing invitation is required.");

            LanBootstrapResult bootstrapResult;
            if (hostedInvitationAuthority == null)
            {
                sessionId = NewSessionId();
                var bootstrap = new LanBootstrapMessage(
                    invitationId: invitation.InvitationId,
                    token: invitation.Token,
                    invitationHash: invitation.InvitationHash,
                    sessionId: sessionId.Value,
                    role: LanBootstrapMessage.RoleSender);

                await channel.WriteBootstrapAsync(bootstrap, cancellationToken);
                bootstrapResult = await DesktopControlSequence.ReadBootstrapResultAsync(
                    channel,
                    cancellationToken);
            }
            else
            {
                LanRecord? firstRecord = await DesktopControlSequence.ReadRecordAsync(
                    channel,
                    cancellationToken);
                if (firstRecord is not LanRecord.Bootstrap bootstrapRecord)
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.ProtocolOrder,
                        "Authenticated BOOTSTRAP must be the first LAN record.");
                }

                LanBootstrapMessage bootstrap = LanBootstrapCodec.DecodeBootstrap(
                    bootstrapRecord.JsonBytes);
                bootstrapResult = hostedInvitationAuthority.Consume(
                    bootstrap,
                    LanBootstrapMessage.RoleReceiver);
                await channel.WriteBootstrapResultAsync(bootstrapResult, cancellationToken);
                if (!bootstrapResult.IsAccepted)
                {
                    return CompleteAttempt(
                        diag,
                        DesktopTransferResult.RejectedByPeer(
                            $"Pairing bootstrap rejected: {bootstrapResult.Code}",
                            DesktopTransferFailures.FromBootstrapCode(bootstrapResult.Code)));
                }

                sessionId = new SessionId(bootstrap.SessionId);
            }

            if (!bootstrapResult.IsAccepted)
            {
                return CompleteAttempt(
                    diag,
                    DesktopTransferResult.RejectedByPeer(
                        $"Pairing bootstrap rejected by peer: {bootstrapResult.Code}",
                        DesktopTransferFailures.FromBootstrapCode(bootstrapResult.Code)));
            }

            if (!string.Equals(
                    bootstrapResult.SessionId,
                    sessionId.Value,
                    StringComparison.Ordinal))
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.AuthenticationFailed,
                    "Accepted bootstrap returned a different sessionId.");
            }

            interaction.OnInvitationConsumed(
                DesktopTransferDirection.Send);
        }

        string localName =
            string.IsNullOrWhiteSpace(request.LocalDeviceName)
                ? Environment.MachineName
                : request.LocalDeviceName;

        DesktopPersistentTrustManager? trustManager =
            request.TrustManager;
        if (request.ExpectedPeerFingerprintHex != null &&
            trustManager == null)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.AuthenticationFailed,
                "A trusted peer was requested but no persistent trust manager is available.");
        }

        trustManager?.Initialize();

        JsonArray optionalCapabilities =
            trustManager == null
                ? new JsonArray(
                    ProtocolCapabilities.FolderV1)
                : new JsonArray(
                    ProtocolCapabilities.FolderV1,
                    ProtocolCapabilities.IdentityV1);

        await channel.WriteEnvelopeAsync(
            sequence.Create(
                MessageTypes.Hello,
                sessionId,
                new JsonObject
                {
                    ["supportedMajors"] = new JsonArray(1),
                    ["requiredCapabilities"] =
                        new JsonArray(
                            ProtocolCapabilities.StreamV1,
                            ProtocolCapabilities.Sha256),
                    ["optionalCapabilities"] =
                        optionalCapabilities,
                    ["role"] = "sender",
                    ["ephemeralPeerId"] =
                        Guid.NewGuid()
                            .ToString("D")
                            .ToLowerInvariant(),
                    ["displayName"] = localName
                }),
            cancellationToken);

        Envelope helloAck =
            await sequence.ReadExpectedAsync(
                channel,
                MessageTypes.HelloAck,
                sessionId,
                cancellationToken);

        HashSet<string> negotiatedCapabilities =
            ValidateHelloAckCapabilities(
                helloAck,
                identityOffered:
                    trustManager != null,
                requireIdentity:
                    request.ExpectedPeerFingerprintHex != null);

        string peerName =
            helloAck.Body["displayName"]?.ToString()
            ?? trustedPeerFallbackName
            ?? request.Invitation?.Name
            ?? "Remote Peer";
        interaction.OnPeerIdentified(
            DesktopTransferDirection.Send,
            peerName);

        if (hostedInvitationAuthority != null)
        {
            interaction.OnStatus(
                DesktopTransferDirection.Send,
                $"{peerName} connected. Confirm the receiving device before sending.");
            diag.OnPhaseChanged(DesktopTransferPhase.AwaitingConsent);
            if (!await interaction.ConfirmRecipientAsync(peerName, cancellationToken))
            {
                return CompleteAttempt(
                    diag,
                    DesktopTransferResult.RejectedByPeer(
                        "You did not approve the connected receiving device."));
            }
        }

        if (negotiatedCapabilities.Contains(
                ProtocolCapabilities.IdentityV1))
        {
            if (trustManager == null)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.AuthenticationFailed,
                    "Peer selected identity-v1 when no local persistent identity authority was offered.");
            }

            DesktopAuthenticatedPeer authenticated =
                await DesktopTrustedSessionAuthenticator
                    .AuthenticateSenderAsync(
                        channel,
                        sequence,
                        connection,
                        sessionId,
                        negotiatedCapabilities,
                        peerName,
                        trustManager,
                        request.ExpectedPeerFingerprintHex,
                        interaction,
                        localEndpointHostsTls:
                            hostedInvitationAuthority != null,
                        cancellationToken: cancellationToken);

            interaction.OnStatus(
                DesktopTransferDirection.Send,
                authenticated.IsEnrolled
                    ? $"Trusted identity authenticated: {authenticated.FingerprintHex[..12]}..."
                    : $"Peer identity cryptographically verified: {authenticated.FingerprintHex[..12]}...");
        }

        var preparedItems =
            new List<PreparedSendItem>(request.Files.Count);
        foreach (DesktopTransferFile file in request.Files)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var info = new FileInfo(file.FilePath);
            preparedItems.Add(
                new PreparedSendItem(
                    new ItemId(
                        Guid.NewGuid()
                            .ToString("D")
                            .ToLowerInvariant()),
                    file,
                    info.Length,
                    info.LastWriteTimeUtc));
        }

        var batchId = new BatchId(
            Guid.NewGuid()
                .ToString("D")
                .ToLowerInvariant());
        List<JsonArray> offerPages =
            BuildOfferPages(preparedItems);

        await channel.WriteEnvelopeAsync(
            sequence.Create(
                MessageTypes.OfferBegin,
                sessionId,
                new JsonObject
                {
                    ["batchId"] = batchId.Value,
                    ["itemCount"] = (long)preparedItems.Count,
                    ["totalBytes"] = totalBytes.ToString(),
                    ["pageCount"] = (long)offerPages.Count
                }),
            cancellationToken);

        for (int pageIndex = 0;
             pageIndex < offerPages.Count;
             pageIndex++)
        {
            await channel.WriteEnvelopeAsync(
                sequence.Create(
                    MessageTypes.OfferPage,
                    sessionId,
                    new JsonObject
                    {
                        ["batchId"] = batchId.Value,
                        ["pageIndex"] = pageIndex,
                        ["items"] = offerPages[pageIndex]
                    }),
                cancellationToken);
        }

        await channel.WriteEnvelopeAsync(
            sequence.Create(
                MessageTypes.OfferEnd,
                sessionId,
                new JsonObject
                {
                    ["batchId"] = batchId.Value
                }),
            cancellationToken);

        interaction.OnStatus(
            DesktopTransferDirection.Send,
            "Waiting for peer approval...");
        interaction.OnPhase(DesktopTransferDirection.Send, DesktopTransferPhase.AwaitingConsent);
        diag.OnPhaseChanged(DesktopTransferPhase.AwaitingConsent);

        Envelope offerDecision =
            await sequence.ReadExpectedAsync(
                channel,
                MessageTypes.OfferDecision,
                sessionId,
                cancellationToken,
                timeout: OfferDecisionTimeout);
        RequireExactString(
            offerDecision,
            "batchId",
            batchId.Value);

        bool accepted =
            offerDecision.Body["accepted"]?.GetValue<bool>()
            ?? false;
        if (!accepted)
        {
            string code =
                offerDecision.Body["code"]?.ToString()
                ?? WireErrorCodes.UserRejected;
            if (code == WireErrorCodes.InsufficientStorage)
            {
                return CompleteAttempt(
                    diag,
                    DesktopTransferResult.Failed(
                        "The receiving device cannot save these files. Ask its user to choose a writable folder in Settings or free space, then try again.",
                        TransferErrorCategory.Storage));
            }
            return CompleteAttempt(
                diag,
                DesktopTransferResult.RejectedByPeer(
                    $"Peer rejected the transfer offer ({code})."));
        }

        diag.OnConsentCompleted();
        interaction.OnPhase(DesktopTransferDirection.Send, DesktopTransferPhase.Transferring);
        diag.OnPhaseChanged(DesktopTransferPhase.Transferring);

        long cumulativeTransferred = 0;
        int verifiedCount = 0;
        long verifiedBytes = 0;
        int failedCount = 0;

        foreach (PreparedSendItem prepared in preparedItems)
        {
            cancellationToken.ThrowIfCancellationRequested();
            interaction.OnPhase(DesktopTransferDirection.Send, DesktopTransferPhase.Transferring);
            diag.OnPhaseChanged(DesktopTransferPhase.Transferring);
            DesktopTransferFile file = prepared.File;

            interaction.OnItemStatus(
                DesktopTransferDirection.Send,
                file.Name,
                "Sending");

            var bulkRef = new BulkRef(
                Guid.NewGuid()
                    .ToString("D")
                    .ToLowerInvariant());

            await channel.WriteEnvelopeAsync(
                sequence.Create(
                    MessageTypes.ItemStart,
                    sessionId,
                    new JsonObject
                    {
                        ["batchId"] = batchId.Value,
                        ["itemId"] = prepared.ItemId.Value,
                        ["bulkRef"] = bulkRef.Value,
                        ["sizeBytes"] =
                            prepared.DeclaredSize.ToString()
                    }),
                cancellationToken);

            Envelope itemReady =
                await sequence.ReadExpectedAsync(
                    channel,
                    MessageTypes.ItemReady,
                    sessionId,
                    cancellationToken);
            RequireExactString(
                itemReady,
                "batchId",
                batchId.Value);
            RequireExactString(
                itemReady,
                "itemId",
                prepared.ItemId.Value);

            long actualBytes = 0;
            using var hash =
                IncrementalHash.CreateHash(
                    HashAlgorithmName.SHA256);
            await using var stream =
                new FileStream(
                    file.FilePath,
                    FileMode.Open,
                    FileAccess.Read,
                    FileShare.Read,
                    StreamBufferBytes,
                    useAsync: true);

            if (_ioOptions.EnableBoundedPipeline)
            {
                (actualBytes, cumulativeTransferred) = await StreamSenderItemBoundedAsync(
                    stream,
                    hash,
                    channel,
                    bulkRef,
                    prepared,
                    peerName,
                    cumulativeTransferred,
                    totalBytes,
                    interaction,
                    diag,
                    cancellationToken).ConfigureAwait(false);
            }
            else
            {
                byte[] buffer =
                    new byte[StreamBufferBytes];
                while (true)
                {
                    long readStart = Stopwatch.GetTimestamp();
                    int read = await stream.ReadAsync(
                        buffer.AsMemory(0, buffer.Length),
                        cancellationToken).ConfigureAwait(false);
                    diag.AddSourceRead(read, Stopwatch.GetTimestamp() - readStart);
                    if (read <= 0)
                    {
                        break;
                    }

                    actualBytes = checked(
                        actualBytes + read);
                    if (actualBytes >
                        prepared.DeclaredSize)
                    {
                        throw new ProtocolViolationException(
                            WireErrorCodes.SourceUnavailable,
                            $"Source '{file.Name}' grew while it was being transferred.");
                    }

                    long hashStart = Stopwatch.GetTimestamp();
                    hash.AppendData(
                        buffer,
                        0,
                        read);
                    diag.AddHashCompute(Stopwatch.GetTimestamp() - hashStart);

                    long writeStart = Stopwatch.GetTimestamp();
                    await channel.WriteDataChunkAsync(
                        bulkRef,
                        buffer.AsMemory(0, read),
                        cancellationToken).ConfigureAwait(false);
                    diag.AddTlsWrite(read, Stopwatch.GetTimestamp() - writeStart);

                    cumulativeTransferred += read;
                    interaction.OnProgress(
                        new DesktopTransferProgress(
                            DesktopTransferDirection.Send,
                            peerName,
                            file.Name,
                            cumulativeTransferred,
                            totalBytes));
                }
            }

            long finalHashStart = Stopwatch.GetTimestamp();
            byte[] digestBytes =
                hash.GetHashAndReset();
            diag.AddHashCompute(Stopwatch.GetTimestamp() - finalHashStart);

            if (actualBytes != prepared.DeclaredSize)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.SourceUnavailable,
                    $"Source '{file.Name}' changed size while it was being transferred.");
            }

            var afterTransfer = new FileInfo(
                file.FilePath);
            if (afterTransfer.Length !=
                    prepared.DeclaredSize ||
                afterTransfer.LastWriteTimeUtc !=
                    prepared.LastWriteTimeUtc)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.SourceUnavailable,
                    $"Source '{file.Name}' changed during transfer.");
            }

            string digest =
                Convert.ToHexStringLower(
                    digestBytes);

            await channel.WriteStreamEndAsync(
                bulkRef,
                actualBytes,
                cancellationToken).ConfigureAwait(false);

            await channel.WriteEnvelopeAsync(
                sequence.Create(
                    MessageTypes.ItemEnd,
                    sessionId,
                    new JsonObject
                    {
                        ["batchId"] = batchId.Value,
                        ["itemId"] = prepared.ItemId.Value,
                        ["actualBytes"] =
                            actualBytes.ToString(),
                        ["sha256"] = digest
                    }),
                cancellationToken).ConfigureAwait(false);

            interaction.OnPhase(DesktopTransferDirection.Send, DesktopTransferPhase.Verifying);
            diag.OnPhaseChanged(DesktopTransferPhase.Verifying);
            Envelope itemResult;
            try
            {
                itemResult =
                    await sequence.ReadExpectedAsync(
                        channel,
                        MessageTypes.ItemResult,
                        sessionId,
                        cancellationToken).ConfigureAwait(false);
            }
            catch (Exception ackError) when (!cancellationToken.IsCancellationRequested && IsUncertainAckFailure(ackError))
            {
                string uncertainMessage =
                    $"Confirmation uncertain for '{file.Name}': the connection closed after all bytes and digest were sent, " +
                    "while waiting for final receiver confirmation. The receiving device may have already verified and saved the file; " +
                    "check the receiver before resending.";
                interaction.OnStatus(DesktopTransferDirection.Send, uncertainMessage);
                return CompleteAttempt(
                    diag,
                    DesktopTransferResult.Failed(
                        uncertainMessage,
                        TransferErrorCategory.Network,
                        verifiedCount,
                        verifiedBytes));
            }
            RequireExactString(
                itemResult,
                "batchId",
                batchId.Value);
            RequireExactString(
                itemResult,
                "itemId",
                prepared.ItemId.Value);

            bool verified = string.Equals(
                itemResult.Body["status"]?.ToString(),
                "verified",
                StringComparison.Ordinal);
            if (verified)
            {
                RequireExactLong(
                    itemResult,
                    "sizeBytes",
                    actualBytes);
                RequireExactString(
                    itemResult,
                    "sha256",
                    digest);

                verifiedCount++;
                verifiedBytes += actualBytes;
                diag.OnItemVerified(actualBytes);
                interaction.OnItemStatus(
                    DesktopTransferDirection.Send,
                    file.Name,
                    "Verified");
                interaction.OnHistory(
                    new DesktopHistoryEntry(
                        DesktopTransferDirection.Send,
                        file.Name,
                        actualBytes,
                        file.FilePath,
                        peerName,
                        DateTimeOffset.Now));
            }
            else
            {
                failedCount++;
                interaction.OnItemStatus(
                    DesktopTransferDirection.Send,
                    file.Name,
                    "Failed");
            }
        }

        await channel.WriteEnvelopeAsync(
            sequence.Create(
                MessageTypes.BatchEnd,
                sessionId,
                new JsonObject
                {
                    ["batchId"] = batchId.Value
                }),
            cancellationToken).ConfigureAwait(false);

        interaction.OnPhase(DesktopTransferDirection.Send, DesktopTransferPhase.Verifying);
        diag.OnPhaseChanged(DesktopTransferPhase.Verifying);
        Envelope terminal;
        try
        {
            terminal =
                await sequence.ReadExpectedOneOfAsync(
                    channel,
                    [MessageTypes.BatchResult, MessageTypes.Error],
                    sessionId,
                    cancellationToken).ConfigureAwait(false);
        }
        catch (Exception ackError) when (!cancellationToken.IsCancellationRequested && IsUncertainAckFailure(ackError))
        {
            string uncertainBatchMessage =
                $"Confirmation uncertain: the connection closed while waiting for final batch acknowledgment " +
                $"after {verifiedCount} item(s) were verified ({verifiedBytes} bytes). " +
                "The receiving device may have already saved the files; check the receiver before resending.";
            interaction.OnStatus(DesktopTransferDirection.Send, uncertainBatchMessage);
            return CompleteAttempt(
                diag,
                DesktopTransferResult.Failed(
                    uncertainBatchMessage,
                    TransferErrorCategory.Network,
                    verifiedCount,
                    verifiedBytes));
        }

        if (terminal.Type == MessageTypes.Error)
        {
            string detail =
                terminal.Body["detail"]?.ToString()
                ?? "Peer reported a terminal transfer error.";
            string message =
                $"Transfer failed after verification: {detail}";
            interaction.OnStatus(
                DesktopTransferDirection.Send,
                message);
            return CompleteAttempt(
                diag,
                DesktopTransferResult.Failed(
                    message, TransferErrorCategory.Protocol, verifiedCount, verifiedBytes));
        }

        RequireExactString(
            terminal,
            "batchId",
            batchId.Value);

        int peerVerifiedCount =
            checked((int)long.Parse(
                terminal.Body["verifiedCount"]!
                    .ToString()));
        long peerVerifiedBytes = long.Parse(
            terminal.Body["totalVerifiedBytes"]!
                .ToString());

        if (peerVerifiedCount != verifiedCount ||
            peerVerifiedBytes != verifiedBytes)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                "BATCH_RESULT verification totals disagree with ITEM_RESULT outcomes.");
        }

        if (failedCount != 0 ||
            verifiedCount != preparedItems.Count ||
            verifiedBytes != totalBytes)
        {
            string partialMessage =
                $"Transfer ended with {failedCount} failed item(s); " +
                $"{verifiedCount}/{preparedItems.Count} item(s) were verified.";
            interaction.OnStatus(
                DesktopTransferDirection.Send,
                partialMessage);
            return CompleteAttempt(
                diag,
                DesktopTransferResult.Failed(
                    partialMessage, TransferErrorCategory.Security, verifiedCount, verifiedBytes));
        }

        string successMessage =
            $"Transfer complete: peer verified {verifiedCount} item(s) ({verifiedBytes} bytes).";
        interaction.OnStatus(
            DesktopTransferDirection.Send,
            successMessage);
        return CompleteAttempt(
            diag,
            DesktopTransferResult.Completed(
                successMessage,
                verifiedCount,
                verifiedBytes));
    }

    private async Task<DesktopTransferResult> RunReceiverSessionAsync(
        TlsSocketConnection connection,
        DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction,
        CancellationToken cancellationToken,
        bool localEndpointHostsTls = true,
        SessionId? preEstablishedTrustedSessionId = null)
    {
        await using IDesktopTransferAttemptDiagnostics diag =
            _diagnostics.BeginAttempt(DesktopTransferDirection.Receive, 0);
        try
        {
            return await RunReceiverSessionCoreAsync(
                connection,
                request,
                interaction,
                diag,
                cancellationToken,
                localEndpointHostsTls,
                preEstablishedTrustedSessionId);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            CompleteAttempt(diag, DesktopTransferResult.CancelledByUser("Transfer cancelled."));
            throw;
        }
        catch (Exception ex)
        {
            CompleteAttempt(diag, DesktopTransferResult.Failed(ex.Message, DesktopTransferFailures.Classify(ex)));
            throw;
        }
    }

    private async Task<DesktopTransferResult> RunReceiverSessionCoreAsync(
        TlsSocketConnection connection,
        DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction,
        IDesktopTransferAttemptDiagnostics diag,
        CancellationToken cancellationToken,
        bool localEndpointHostsTls = true,
        SessionId? preEstablishedTrustedSessionId = null)
    {
        await using var channel =
            new LanRecordChannel(
                connection.TransportStream,
                coalesceWrites: _ioOptions.EnableCoalescedFraming,
                coalesceBufferBytes: _ioOptions.WriteCoalesceBufferBytes,
                maxCoalesceDelayMs: (int)Math.Max(1, _ioOptions.MaxWriteBatchDelay.TotalMilliseconds),
                bufferPool: _bufferPool);

        LanBootstrapResult bootstrapResult;
        SessionId sessionId;
        var sequence = new DesktopControlSequence();

        if (preEstablishedTrustedSessionId is { } trustedSessionId)
        {
            sessionId = trustedSessionId;
        }
        else if (localEndpointHostsTls)
        {
            if (request.InvitationAuthority == null)
            {
                throw new InvalidOperationException("InvitationAuthority required for inbound receiver session.");
            }

            LanRecord? firstRecord =
                await DesktopControlSequence.ReadRecordAsync(
                    channel,
                    cancellationToken);
            if (firstRecord == null)
            {
                return CompleteAttempt(
                    diag,
                    DesktopTransferResult.Failed(
                        "Peer disconnected before sending the first record."));
            }

            if (firstRecord is not LanRecord.Bootstrap bootstrapRecord)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.ProtocolOrder,
                    "Authenticated BOOTSTRAP must be the first LAN record.");
            }

            LanBootstrapMessage bootstrap =
                LanBootstrapCodec.DecodeBootstrap(
                    bootstrapRecord.JsonBytes);
            bootstrapResult =
                request.InvitationAuthority.Consume(
                    bootstrap);

            await channel.WriteBootstrapResultAsync(
                bootstrapResult,
                cancellationToken);

            if (!bootstrapResult.IsAccepted)
            {
                return CompleteAttempt(
                    diag,
                    DesktopTransferResult.RejectedByPeer(
                        $"Inbound pairing bootstrap rejected: {bootstrapResult.Code}.",
                        DesktopTransferFailures.FromBootstrapCode(bootstrapResult.Code)));
            }

            sessionId = new SessionId(bootstrap.SessionId);
        }
        else
        {
            LanInvitation invitation = request.Invitation
                ?? throw new InvalidOperationException("Authenticated pairing invitation is required.");

            sessionId = NewSessionId();
            var bootstrap = new LanBootstrapMessage(
                invitationId: invitation.InvitationId,
                token: invitation.Token,
                invitationHash: invitation.InvitationHash,
                sessionId: sessionId.Value,
                role: LanBootstrapMessage.RoleReceiver);

            await channel.WriteBootstrapAsync(bootstrap, cancellationToken);
            bootstrapResult = await DesktopControlSequence.ReadBootstrapResultAsync(
                channel,
                cancellationToken);

            if (!bootstrapResult.IsAccepted)
            {
                return CompleteAttempt(
                    diag,
                    DesktopTransferResult.RejectedByPeer(
                        $"Pairing bootstrap rejected by peer: {bootstrapResult.Code}.",
                        DesktopTransferFailures.FromBootstrapCode(bootstrapResult.Code)));
            }

            if (!string.Equals(
                    bootstrapResult.SessionId,
                    sessionId.Value,
                    StringComparison.Ordinal))
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.AuthenticationFailed,
                    "Accepted bootstrap returned a different sessionId.");
            }
        }

        try
        {
            if (preEstablishedTrustedSessionId == null)
            {
                interaction.OnInvitationConsumed(
                    DesktopTransferDirection.Receive);
            }

            Envelope hello =
                await sequence.ReadExpectedAsync(
                    channel,
                    MessageTypes.Hello,
                    sessionId,
                    cancellationToken);

            if (!string.Equals(
                    hello.Body["role"]?.ToString(),
                    "sender",
                    StringComparison.Ordinal))
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.ProtocolOrder,
                    "Inbound transfer HELLO must use sender role.");
            }

            DesktopPersistentTrustManager? trustManager =
                request.TrustManager;
            if (request.ExpectedPeerFingerprintHex != null &&
                trustManager == null)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.AuthenticationFailed,
                    "A trusted peer was required but no persistent trust manager is available.");
            }

            trustManager?.Initialize();

            HashSet<string> negotiatedCapabilities =
                NegotiateHelloCapabilities(
                    hello,
                    allowIdentity:
                        trustManager != null);

            if (request.ExpectedPeerFingerprintHex != null &&
                !negotiatedCapabilities.Contains(
                    ProtocolCapabilities.IdentityV1))
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.AuthenticationFailed,
                    "Known trusted peer attempted a capability downgrade that omitted identity-v1.");
            }

            string peerName =
                hello.Body["displayName"]?.ToString()
                ?? "Remote Peer";
            interaction.OnPeerIdentified(
                DesktopTransferDirection.Receive,
                peerName);

            string localName =
                string.IsNullOrWhiteSpace(
                    request.LocalDeviceName)
                    ? Environment.MachineName
                    : request.LocalDeviceName;

            await channel.WriteEnvelopeAsync(
                sequence.Create(
                    MessageTypes.HelloAck,
                    sessionId,
                    new JsonObject
                    {
                        ["selectedMajor"] = 1,
                        ["capabilities"] =
                            new JsonArray(
                                negotiatedCapabilities
                                    .OrderBy(
                                        static capability =>
                                            capability,
                                        StringComparer.Ordinal)
                                    .Select(
                                        static capability =>
                                            JsonValue.Create(capability))
                                    .ToArray()),
                        ["ephemeralPeerId"] =
                            Guid.NewGuid()
                                .ToString("D")
                                .ToLowerInvariant(),
                        ["displayName"] = localName,
                        ["limits"] = new JsonObject
                        {
                            ["maxItems"] = 1000,
                            ["maxItemBytes"] =
                                ProtocolCatalog.MaxItemBytes
                                    .ToString(),
                            ["maxBatchBytes"] =
                                ProtocolCatalog.MaxBatchBytes
                                    .ToString(),
                            ["maxControlBytes"] =
                                ProtocolCatalog.MaxControlBytes
                        }
                    }),
                cancellationToken);

            string? authenticatedPeerFingerprint = null;
            if (negotiatedCapabilities.Contains(
                    ProtocolCapabilities.IdentityV1))
            {
                if (trustManager == null)
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.AuthenticationFailed,
                        "identity-v1 was negotiated without a local persistent trust authority.");
                }

                DesktopAuthenticatedPeer authenticated =
                    await DesktopTrustedSessionAuthenticator
                        .AuthenticateReceiverAsync(
                            channel,
                            sequence,
                            connection,
                            sessionId,
                            negotiatedCapabilities,
                            peerName,
                            trustManager,
                            request.ExpectedPeerFingerprintHex,
                            interaction,
                            localEndpointHostsTls,
                            cancellationToken);
                authenticatedPeerFingerprint =
                    authenticated.FingerprintHex;

                interaction.OnStatus(
                    DesktopTransferDirection.Receive,
                    authenticated.IsEnrolled
                        ? $"Trusted identity authenticated: {authenticated.FingerprintHex[..12]}..."
                        : $"Peer identity cryptographically verified: {authenticated.FingerprintHex[..12]}...");
            }

            Envelope offerBegin =
                await sequence.ReadExpectedAsync(
                    channel,
                    MessageTypes.OfferBegin,
                    sessionId,
                    cancellationToken);

            string batchId =
                offerBegin.Body["batchId"]!
                    .ToString();
            int itemCount =
                checked((int)long.Parse(
                    offerBegin.Body["itemCount"]!
                        .ToString()));
            long totalBytes = long.Parse(
                offerBegin.Body["totalBytes"]!
                    .ToString());
            int pageCount =
                checked((int)long.Parse(
                    offerBegin.Body["pageCount"]!
                        .ToString()));

            diag.SetDeclaredTotalBytes(totalBytes);

            var items =
                new List<DesktopOfferItem>(
                    itemCount);
            var itemIds =
                new HashSet<string>(
                    StringComparer.Ordinal);
            long offeredBytes = 0;

            for (int pageIndex = 0;
                 pageIndex < pageCount;
                 pageIndex++)
            {
                Envelope offerPage =
                    await sequence.ReadExpectedAsync(
                        channel,
                        MessageTypes.OfferPage,
                        sessionId,
                        cancellationToken);
                RequireExactString(
                    offerPage,
                    "batchId",
                    batchId);

                int actualPageIndex =
                    checked((int)long.Parse(
                        offerPage.Body["pageIndex"]!
                            .ToString()));
                if (actualPageIndex != pageIndex)
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.ProtocolOrder,
                        $"Expected OFFER_PAGE index {pageIndex}, received {actualPageIndex}.");
                }

                JsonArray itemArray =
                    offerPage.Body["items"]
                    as JsonArray
                    ?? throw new ProtocolViolationException(
                        WireErrorCodes.MalformedMessage,
                        "OFFER_PAGE items must be an array.");

                foreach (JsonNode? node in itemArray)
                {
                    if (node is not JsonObject itemObject)
                    {
                        throw new ProtocolViolationException(
                            WireErrorCodes.MalformedMessage,
                            "OFFER_PAGE item must be an object.");
                    }

                    string itemId =
                        itemObject["itemId"]!
                            .ToString();
                    if (!itemIds.Add(itemId))
                    {
                        throw new ProtocolViolationException(
                            WireErrorCodes.MalformedMessage,
                            $"Duplicate offer itemId '{itemId}'.");
                    }

                    bool carriesResumeMetadata =
                        itemObject["rootHash"] != null ||
                        itemObject["chunkSize"] != null;
                    if (carriesResumeMetadata &&
                        !negotiatedCapabilities.Contains(
                            ProtocolCapabilities.ResumeV1))
                    {
                        throw new ProtocolViolationException(
                            WireErrorCodes.UnsupportedProtocol,
                            "OFFER_PAGE carried resume metadata without negotiated resume-v1.");
                    }

                    string name =
                        itemObject["name"]!
                            .ToString();
                    long size = long.Parse(
                        itemObject["sizeBytes"]!
                            .ToString());
                    string rawRelativePath =
                        itemObject[
                            "relativePath"]?
                            .ToString()
                        ?? name;
                    string relativePath =
                        WindowsPathSanitizer
                            .SanitizeRelativePath(
                                rawRelativePath);

                    offeredBytes =
                        checked(offeredBytes + size);
                    items.Add(
                        new DesktopOfferItem(
                            itemId,
                            name,
                            size,
                            relativePath));
                }
            }

            Envelope offerEnd =
                await sequence.ReadExpectedAsync(
                    channel,
                    MessageTypes.OfferEnd,
                    sessionId,
                    cancellationToken);
            RequireExactString(
                offerEnd,
                "batchId",
                batchId);

            if (items.Count != itemCount)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.ProtocolOrder,
                    $"Offer declared {itemCount} item(s) but supplied {items.Count}.");
            }

            if (offeredBytes != totalBytes)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.ProtocolOrder,
                    $"Offer declared {totalBytes} bytes but item metadata totals {offeredBytes}.");
            }

            bool discardStorage = _ioOptions.DiscardStorageForBenchmark;
            long maxItemBytes = items.Count > 0 ? items.Max(static item => item.SizeBytes) : 0;
            bool hasStorage = discardStorage ||
                request.StagingStore.HasSufficientFreeSpaceForBatch(totalBytes, maxItemBytes);
            if (hasStorage && !discardStorage)
            {
                try
                {
                    await request.StagingStore.VerifyDestinationWritableAsync(cancellationToken).ConfigureAwait(false);
                }
                catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException)
                {
                    hasStorage = false;
                }
            }

            interaction.OnPhase(DesktopTransferDirection.Receive, DesktopTransferPhase.AwaitingConsent);
            diag.OnPhaseChanged(DesktopTransferPhase.AwaitingConsent);

            bool offerAccepted =
                hasStorage &&
                await interaction.ConfirmOfferAsync(
                    new DesktopOffer(
                        batchId,
                        peerName,
                        items,
                        totalBytes,
                        AuthenticatedPeerFingerprintHex:
                            authenticatedPeerFingerprint),
                    cancellationToken).ConfigureAwait(false);

            var decisionBody =
                new JsonObject
                {
                    ["batchId"] = batchId,
                    ["accepted"] = offerAccepted
                };
            if (!offerAccepted)
            {
                decisionBody["code"] =
                    hasStorage
                        ? WireErrorCodes.UserRejected
                        : WireErrorCodes.InsufficientStorage;
            }

            await channel.WriteEnvelopeAsync(
                sequence.Create(
                    MessageTypes.OfferDecision,
                    sessionId,
                    decisionBody),
                cancellationToken).ConfigureAwait(false);

            if (!offerAccepted)
            {
                return CompleteAttempt(
                    diag,
                    hasStorage
                        ? DesktopTransferResult.RejectedByPeer("Transfer offer was rejected.")
                        : DesktopTransferResult.Failed(
                            "The receiving folder is unavailable, read-only, or short of space. Choose a writable folder in Settings and try again.",
                            TransferErrorCategory.Storage));
            }

            using IDisposable sessionLease = request.StagingStore.AcquireSessionLease(sessionId);
            diag.OnConsentCompleted();
            interaction.OnPhase(DesktopTransferDirection.Receive, DesktopTransferPhase.Transferring);
            diag.OnPhaseChanged(DesktopTransferPhase.Transferring);

            long cumulativeTransferred = 0;
            int verifiedCount = 0;
            long verifiedBytes = 0;
            int failedCount = 0;

            foreach (DesktopOfferItem item in items)
            {
                cancellationToken.ThrowIfCancellationRequested();
                interaction.OnPhase(DesktopTransferDirection.Receive, DesktopTransferPhase.Transferring);
                diag.OnPhaseChanged(DesktopTransferPhase.Transferring);
                interaction.OnItemStatus(
                    DesktopTransferDirection.Receive,
                    item.Name,
                    "Receiving");

                Envelope itemStart =
                    await sequence.ReadExpectedAsync(
                        channel,
                        MessageTypes.ItemStart,
                        sessionId,
                        cancellationToken);
                RequireExactString(
                    itemStart,
                    "batchId",
                    batchId);
                RequireExactString(
                    itemStart,
                    "itemId",
                    item.ItemId);
                RequireExactLong(
                    itemStart,
                    "sizeBytes",
                    item.SizeBytes);

                string bulkRefText =
                    itemStart.Body["bulkRef"]!
                        .ToString();
                var bulkRef =
                    new BulkRef(bulkRefText);

                await channel.WriteEnvelopeAsync(
                    sequence.Create(
                        MessageTypes.ItemReady,
                        sessionId,
                        new JsonObject
                        {
                            ["batchId"] = batchId,
                            ["itemId"] = item.ItemId
                        }),
                    cancellationToken);

                var itemId =
                    new ItemId(item.ItemId);
                byte[] hash;
                long receivedBytes = 0;
                try
                {
                    await using Stream fileStream = discardStorage
                        ? Stream.Null
                        : request.StagingStore.OpenQuarantineWrite(sessionId, itemId);
                    using var sha256 =
                        IncrementalHash.CreateHash(
                            HashAlgorithmName.SHA256);

                    using var itemTimeoutCts =
                        CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);

                    if (_ioOptions.EnableBoundedPipeline)
                    {
                        (receivedBytes, cumulativeTransferred) = await StreamReceiverItemBoundedAsync(
                            channel,
                            fileStream,
                            sha256,
                            bulkRef,
                            item,
                            peerName,
                            discardStorage,
                            cumulativeTransferred,
                            totalBytes,
                            itemTimeoutCts,
                            interaction,
                            diag,
                            cancellationToken).ConfigureAwait(false);
                    }
                    else
                    {
                        while (true)
                        {
                            long readStart = Stopwatch.GetTimestamp();
                            LanRecord? record =
                                await DesktopControlSequence
                                    .ReadOwnedRecordWithDeadlineAsync(
                                        channel,
                                        bulkRef,
                                        itemTimeoutCts,
                                        cancellationToken).ConfigureAwait(false);
                            long readTicks = Stopwatch.GetTimestamp() - readStart;
                            if (record == null)
                            {
                                throw new EndOfStreamException(
                                    "Connection terminated during payload transfer.");
                            }

                            if (record is LanRecord.OwnedData ownedData)
                            {
                                using (ownedData)
                                {
                                    int chunkLength = ownedData.ChunkLength;
                                    diag.AddTlsRead(chunkLength, readTicks);
                                    if (!string.Equals(
                                            ownedData.BulkRef.Value,
                                            bulkRef.Value,
                                            StringComparison.Ordinal))
                                    {
                                        throw new ProtocolViolationException(
                                            WireErrorCodes.MalformedMessage,
                                            "Unmatched bulkRef in DATA record.");
                                    }

                                    receivedBytes = checked(receivedBytes + chunkLength);
                                    if (receivedBytes > item.SizeBytes)
                                    {
                                        throw new ProtocolViolationException(
                                            WireErrorCodes.LimitExceeded,
                                            $"Item '{item.Name}' exceeded its declared size.");
                                    }

                                    if (!discardStorage)
                                    {
                                        long writeStart = Stopwatch.GetTimestamp();
                                        await fileStream.WriteAsync(
                                            ownedData.ChunkMemory,
                                            cancellationToken).ConfigureAwait(false);
                                        diag.AddStagingWrite(chunkLength, Stopwatch.GetTimestamp() - writeStart);
                                    }

                                    long hashStart = Stopwatch.GetTimestamp();
                                    sha256.AppendData(ownedData.ChunkSpan);
                                    diag.AddHashCompute(Stopwatch.GetTimestamp() - hashStart);

                                    cumulativeTransferred += chunkLength;
                                    interaction.OnProgress(
                                        new DesktopTransferProgress(
                                            DesktopTransferDirection.Receive,
                                            peerName,
                                            item.Name,
                                            cumulativeTransferred,
                                            totalBytes));
                                }
                            }
                            else if (record is
                                LanRecord.Data dataRecord)
                            {
                                diag.AddTlsRead(dataRecord.Chunk.Length, readTicks);
                                if (!string.Equals(
                                        dataRecord.BulkRef.Value,
                                        bulkRef.Value,
                                        StringComparison.Ordinal))
                                {
                                    throw new ProtocolViolationException(
                                        WireErrorCodes.MalformedMessage,
                                        "Unmatched bulkRef in DATA record.");
                                }

                                receivedBytes =
                                    checked(
                                        receivedBytes +
                                        dataRecord.Chunk.Length);
                                if (receivedBytes >
                                    item.SizeBytes)
                                {
                                    throw new ProtocolViolationException(
                                        WireErrorCodes.LimitExceeded,
                                        $"Item '{item.Name}' exceeded its declared size.");
                                }

                                if (!discardStorage)
                                {
                                    long writeStart = Stopwatch.GetTimestamp();
                                    await fileStream.WriteAsync(
                                        dataRecord.Chunk.AsMemory(),
                                        cancellationToken).ConfigureAwait(false);
                                    diag.AddStagingWrite(dataRecord.Chunk.Length, Stopwatch.GetTimestamp() - writeStart);
                                }

                                long hashStart = Stopwatch.GetTimestamp();
                                sha256.AppendData(
                                    dataRecord.Chunk);
                                diag.AddHashCompute(Stopwatch.GetTimestamp() - hashStart);

                                cumulativeTransferred +=
                                    dataRecord.Chunk.Length;
                                interaction.OnProgress(
                                    new DesktopTransferProgress(
                                        DesktopTransferDirection.Receive,
                                        peerName,
                                        item.Name,
                                        cumulativeTransferred,
                                        totalBytes));
                            }
                            else if (record is
                                LanRecord.StreamEnd streamEnd)
                            {
                                if (!string.Equals(
                                        streamEnd.BulkRef.Value,
                                        bulkRef.Value,
                                        StringComparison.Ordinal))
                                {
                                    throw new ProtocolViolationException(
                                        WireErrorCodes.MalformedMessage,
                                        "Unmatched bulkRef in STREAM_END record.");
                                }

                                if (streamEnd.ActualBytes !=
                                        receivedBytes ||
                                    receivedBytes !=
                                        item.SizeBytes)
                                {
                                    throw new ProtocolViolationException(
                                        WireErrorCodes.IntegrityMismatch,
                                        $"STREAM_END actualBytes {streamEnd.ActualBytes} does not match expected {item.SizeBytes}.");
                                }

                                break;
                            }
                            else
                            {
                                throw new ProtocolViolationException(
                                    WireErrorCodes.ProtocolOrder,
                                    $"Unexpected record kind during data stream: {record.Kind}.");
                            }
                        }
                    }

                    if (!discardStorage)
                    {
                        long flushStart = Stopwatch.GetTimestamp();
                        await fileStream.FlushAsync(
                            cancellationToken).ConfigureAwait(false);
                        diag.AddStagingWrite(0, Stopwatch.GetTimestamp() - flushStart);
                    }

                    long finalHashStart = Stopwatch.GetTimestamp();
                    hash =
                        sha256.GetHashAndReset();
                    diag.AddHashCompute(Stopwatch.GetTimestamp() - finalHashStart);
                }
                catch
                {
                    if (!discardStorage)
                    {
                        request.StagingStore
                            .PurgeQuarantine(
                                sessionId,
                                itemId);
                    }
                    throw;
                }

                interaction.OnPhase(DesktopTransferDirection.Receive, DesktopTransferPhase.Verifying);
                diag.OnPhaseChanged(DesktopTransferPhase.Verifying);
                Envelope itemEnd =
                    await sequence.ReadExpectedAsync(
                        channel,
                        MessageTypes.ItemEnd,
                        sessionId,
                        cancellationToken).ConfigureAwait(false);
                RequireExactString(
                    itemEnd,
                    "batchId",
                    batchId);
                RequireExactString(
                    itemEnd,
                    "itemId",
                    item.ItemId);
                RequireExactLong(
                    itemEnd,
                    "actualBytes",
                    receivedBytes);

                string declaredSha =
                    itemEnd.Body["sha256"]!
                        .ToString();
                string actualSha =
                    Convert.ToHexStringLower(
                        hash);

                if (!string.Equals(
                        actualSha,
                        declaredSha,
                        StringComparison.Ordinal))
                {
                    if (!discardStorage)
                    {
                        request.StagingStore
                            .PurgeQuarantine(
                                sessionId,
                                itemId);
                    }

                    await channel.WriteEnvelopeAsync(
                        sequence.Create(
                            MessageTypes.ItemResult,
                            sessionId,
                            new JsonObject
                            {
                                ["batchId"] = batchId,
                                ["itemId"] = item.ItemId,
                                ["status"] = "failed",
                                ["code"] =
                                    WireErrorCodes
                                        .IntegrityMismatch
                            }),
                        cancellationToken).ConfigureAwait(false);

                    failedCount++;
                    interaction.OnItemStatus(
                        DesktopTransferDirection.Receive,
                        item.Name,
                        "Failed");
                    continue;
                }

                string finalPath;
                if (discardStorage)
                {
                    interaction.OnPhase(DesktopTransferDirection.Receive, DesktopTransferPhase.Saving);
                    diag.OnPhaseChanged(DesktopTransferPhase.Saving);
                    finalPath = Path.Combine(request.StagingStore.DestinationRoot, item.RelativePath);
                }
                else
                {
                    QuarantineCommitResult commitResult =
                        await request.StagingStore
                            .CommitVerifiedQuarantineFileWithMetricsAsync(
                                sessionId,
                                itemId,
                                item.RelativePath,
                                new Sha256Digest(actualSha),
                                receivedBytes,
                                cancellationToken,
                                onBeforePublish: () =>
                                {
                                    interaction.OnPhase(DesktopTransferDirection.Receive, DesktopTransferPhase.Saving);
                                    diag.OnPhaseChanged(DesktopTransferPhase.Saving);
                                }).ConfigureAwait(false);
                    finalPath = commitResult.CommittedPath;
                    diag.AddVerificationRead(receivedBytes, commitResult.VerificationReadStopwatchTicks);
                    diag.AddCommit(commitResult.CommitStopwatchTicks);
                }

                verifiedCount++;
                verifiedBytes +=
                    item.SizeBytes;
                diag.OnItemVerified(item.SizeBytes);

                await channel.WriteEnvelopeAsync(
                    sequence.Create(
                        MessageTypes.ItemResult,
                        sessionId,
                        new JsonObject
                        {
                            ["batchId"] = batchId,
                            ["itemId"] = item.ItemId,
                            ["status"] = "verified",
                            ["sizeBytes"] =
                                item.SizeBytes.ToString(),
                            ["sha256"] = actualSha
                        }),
                    cancellationToken).ConfigureAwait(false);

                interaction.OnItemStatus(
                    DesktopTransferDirection.Receive,
                    item.Name,
                    "Verified");
                interaction.OnHistory(
                    new DesktopHistoryEntry(
                        DesktopTransferDirection.Receive,
                        item.Name,
                        item.SizeBytes,
                        finalPath,
                        peerName,
                        DateTimeOffset.Now));
            }

            Envelope batchEnd =
                await sequence.ReadExpectedAsync(
                    channel,
                    MessageTypes.BatchEnd,
                    sessionId,
                    cancellationToken);
            RequireExactString(
                batchEnd,
                "batchId",
                batchId);

            if (verifiedCount == 0 &&
                failedCount > 0)
            {
                await channel.WriteEnvelopeAsync(
                    sequence.Create(
                        MessageTypes.Error,
                        sessionId,
                        new JsonObject
                        {
                            ["code"] =
                                WireErrorCodes
                                    .IntegrityMismatch,
                            ["batchId"] = batchId,
                            ["detail"] =
                                "No offered item passed integrity verification."
                        }),
                    cancellationToken);

                string failedMessage =
                    $"Transfer failed: all {failedCount} item(s) failed verification.";
                interaction.OnStatus(
                    DesktopTransferDirection.Receive,
                    failedMessage);
                return CompleteAttempt(
                    diag,
                    DesktopTransferResult.Failed(
                        failedMessage, TransferErrorCategory.Security));
            }

            await channel.WriteEnvelopeAsync(
                sequence.Create(
                    MessageTypes.BatchResult,
                    sessionId,
                    new JsonObject
                    {
                        ["batchId"] = batchId,
                        ["status"] = "completed",
                        ["verifiedCount"] =
                            verifiedCount,
                        ["totalVerifiedBytes"] =
                            verifiedBytes.ToString()
                    }),
                cancellationToken);

            if (failedCount > 0)
            {
                string partialMessage =
                    $"Transfer ended with {failedCount} failed item(s); " +
                    $"{verifiedCount}/{items.Count} item(s) were safely saved.";
                interaction.OnStatus(
                    DesktopTransferDirection.Receive,
                    partialMessage);
                if (!discardStorage)
                {
                    request.StagingStore.PurgeSession(sessionId);
                }
                return CompleteAttempt(
                    diag,
                    DesktopTransferResult.Failed(
                        partialMessage, TransferErrorCategory.Security, verifiedCount, verifiedBytes));
            }

            string message =
                $"Transfer complete: {verifiedCount} file(s) safely saved ({verifiedBytes} bytes).";
            interaction.OnStatus(
                DesktopTransferDirection.Receive,
                message);
            if (!discardStorage)
            {
                request.StagingStore.PurgeSession(sessionId);
            }
            return CompleteAttempt(
                diag,
                DesktopTransferResult.Completed(
                    message,
                    verifiedCount,
                    verifiedBytes));
        }
        catch
        {
            if (!_ioOptions.DiscardStorageForBenchmark)
            {
                request.StagingStore.PurgeSession(
                    sessionId);
            }
            throw;
        }
    }

    private static HashSet<string> NegotiateHelloCapabilities(
        Envelope hello,
        bool allowIdentity)
    {
        HashSet<string> required =
            ReadCapabilitySet(
                hello.Body["requiredCapabilities"],
                "HELLO requiredCapabilities");
        if (!required.Contains(
                ProtocolCapabilities.StreamV1) ||
            !required.Contains(
                ProtocolCapabilities.Sha256))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.UnsupportedProtocol,
                "HELLO must require stream-v1 and sha256.");
        }

        HashSet<string> optional =
            ReadCapabilitySet(
                hello.Body["optionalCapabilities"],
                "HELLO optionalCapabilities");

        ProtocolCapabilities.ValidateDependencies(
            optional);

        var negotiated =
            new HashSet<string>(
                ProtocolCapabilities.Required,
                StringComparer.Ordinal);

        foreach (string capability in optional)
        {
            if (ProtocolCapabilities.ProductionOptional.Contains(
                    capability) ||
                (allowIdentity &&
                 string.Equals(
                     capability,
                     ProtocolCapabilities.IdentityV1,
                     StringComparison.Ordinal)))
            {
                negotiated.Add(capability);
            }
        }

        ProtocolCapabilities.ValidateDependencies(
            negotiated);
        return negotiated;
    }

    private static HashSet<string> ValidateHelloAckCapabilities(
        Envelope helloAck,
        bool identityOffered,
        bool requireIdentity)
    {
        if (long.Parse(helloAck.Body["selectedMajor"]?.ToString() ?? "-1") != 1)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.UnsupportedProtocol,
                "HELLO_ACK selectedMajor must be 1.");
        }

        HashSet<string> capabilities =
            ReadCapabilitySet(
                helloAck.Body["capabilities"],
                "HELLO_ACK capabilities");

        if (!capabilities.Contains(
                ProtocolCapabilities.StreamV1) ||
            !capabilities.Contains(
                ProtocolCapabilities.Sha256))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.UnsupportedProtocol,
                "HELLO_ACK must include stream-v1 and sha256.");
        }

        var allowed =
            new HashSet<string>(
                ProtocolCapabilities.Required,
                StringComparer.Ordinal);
        allowed.UnionWith(
            ProtocolCapabilities.ProductionOptional);
        if (identityOffered)
        {
            allowed.Add(
                ProtocolCapabilities.IdentityV1);
        }

        if (capabilities.Except(
                allowed,
                StringComparer.Ordinal).Any())
        {
            throw new ProtocolViolationException(
                WireErrorCodes.UnsupportedProtocol,
                "HELLO_ACK selected an unsupported or unoffered capability.");
        }

        if (requireIdentity &&
            !capabilities.Contains(
                ProtocolCapabilities.IdentityV1))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.AuthenticationFailed,
                "Known trusted peer omitted identity-v1 from HELLO_ACK.");
        }

        ProtocolCapabilities.ValidateDependencies(
            capabilities);
        return capabilities;
    }

    private static HashSet<string> ReadCapabilitySet(
        JsonNode? node,
        string fieldName)
    {
        if (node is not JsonArray array)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"{fieldName} must be an array.");
        }

        var result =
            new HashSet<string>(
                StringComparer.Ordinal);

        foreach (JsonNode? item in array)
        {
            if (item is not JsonValue value ||
                value.GetValue<object>() is not string capability ||
                string.IsNullOrWhiteSpace(capability))
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.MalformedMessage,
                    $"{fieldName} must contain only non-empty strings.");
            }

            if (!result.Add(capability))
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.MalformedMessage,
                    $"{fieldName} contains duplicate capability '{capability}'.");
            }
        }

        return result;
    }

    private static List<JsonArray> BuildOfferPages(
        IReadOnlyList<PreparedSendItem> items)
    {
        var pages = new List<JsonArray>();
        var current = new JsonArray();
        int estimatedBytes = 256;

        foreach (PreparedSendItem item in items)
        {
            int itemEstimate =
                EstimateOfferItemBytes(item);
            if (current.Count > 0 &&
                (current.Count >= MaxOfferPageItems ||
                 estimatedBytes + itemEstimate >
                 SafeOfferPagePayloadBytes))
            {
                pages.Add(current);
                current = new JsonArray();
                estimatedBytes = 256;
            }

            current.Add(
                CreateOfferItem(item));
            estimatedBytes += itemEstimate;
        }

        if (current.Count > 0)
        {
            pages.Add(current);
        }

        if (pages.Count == 0 ||
            pages.Count > 1_000)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.LimitExceeded,
                $"Offer page count {pages.Count} is outside the protocol limit.");
        }

        return pages;
    }

    private static JsonObject CreateOfferItem(
        PreparedSendItem item) =>
        new()
        {
            ["itemId"] = item.ItemId.Value,
            ["name"] = item.File.Name,
            ["mime"] =
                "application/octet-stream",
            ["sizeBytes"] =
                item.DeclaredSize.ToString(),
            ["relativePath"] =
                item.File.TransferRelativePath
        };

    private static int EstimateOfferItemBytes(
        PreparedSendItem item)
    {
        const int JsonOverhead = 128;
        return JsonOverhead +
               Encoding.UTF8.GetByteCount(
                   item.File.TransferRelativePath) * 2 +
               item.DeclaredSize
                   .ToString()
                   .Length;
    }

    private static void RequireExactString(
        Envelope envelope,
        string key,
        string expected)
    {
        string actual =
            envelope.Body[key]?.ToString()
            ?? throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"{envelope.Type} is missing '{key}'.");

        if (!string.Equals(
                actual,
                expected,
                StringComparison.Ordinal))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                $"{envelope.Type} '{key}' value '{actual}' does not match expected '{expected}'.");
        }
    }

    private static void RequireExactLong(
        Envelope envelope,
        string key,
        long expected)
    {
        string value =
            envelope.Body[key]?.ToString()
            ?? throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"{envelope.Type} is missing '{key}'.");

        if (!long.TryParse(
                value,
                out long actual) ||
            actual != expected)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.ProtocolOrder,
                $"{envelope.Type} '{key}' value '{value}' does not match expected '{expected}'.");
        }
    }

    private async Task<(long ActualBytes, long CumulativeTransferred)> StreamSenderItemBoundedAsync(
        Stream stream,
        IncrementalHash hash,
        LanRecordChannel channel,
        BulkRef bulkRef,
        PreparedSendItem prepared,
        string peerName,
        long cumulativeTransferred,
        long totalBytes,
        IDesktopTransferInteraction interaction,
        IDesktopTransferAttemptDiagnostics diag,
        CancellationToken cancellationToken)
    {
        int queueCapacity = Math.Clamp(_ioOptions.QueueBufferCount, 1, 64);
        int chunkBytes = Math.Clamp(_ioOptions.ChunkBufferBytes, 4096, Math.Min(StreamBufferBytes, _bufferPool.BufferBytes));

        var boundedQueue = Channel.CreateBounded<OwnedTransferBuffer>(
            new BoundedChannelOptions(queueCapacity)
            {
                FullMode = BoundedChannelFullMode.Wait,
                SingleWriter = true,
                SingleReader = true,
                AllowSynchronousContinuations = false
            });

        using var pipelineCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        long actualBytes = 0;
        long cumulative = cumulativeTransferred;
        int currentDepth = 0;
        int maxDepth = 0;

        Task producerTask = Task.Run(async () =>
        {
            Exception? producerError = null;
            try
            {
                while (true)
                {
                    OwnedTransferBuffer leased = await _bufferPool.RentAsync(chunkBytes, pipelineCts.Token).ConfigureAwait(false);
                    bool handedOff = false;
                    try
                    {
                        long readStart = Stopwatch.GetTimestamp();
                        int read = await stream.ReadAsync(
                            leased.GetAvailableMemory(chunkBytes),
                            pipelineCts.Token).ConfigureAwait(false);
                        diag.AddSourceRead(read, Stopwatch.GetTimestamp() - readStart);

                        if (read <= 0)
                        {
                            break;
                        }

                        actualBytes = checked(actualBytes + read);
                        if (actualBytes > prepared.DeclaredSize)
                        {
                            throw new ProtocolViolationException(
                                WireErrorCodes.SourceUnavailable,
                                $"Source '{prepared.File.Name}' grew while it was being transferred.");
                        }

                        leased.Length = read;

                        long hashStart = Stopwatch.GetTimestamp();
                        hash.AppendData(leased.Span);
                        diag.AddHashCompute(Stopwatch.GetTimestamp() - hashStart);

                        int depth = Interlocked.Increment(ref currentDepth);
                        UpdateMax(ref maxDepth, depth);
                        diag.UpdateQueueOccupancy(depth, Volatile.Read(ref maxDepth), 0, 0, _bufferPool.RentedBufferCount, _bufferPool.RentedBytes);

                        await boundedQueue.Writer.WriteAsync(leased, pipelineCts.Token).ConfigureAwait(false);
                        handedOff = true;
                    }
                    finally
                    {
                        if (!handedOff)
                        {
                            leased.Dispose();
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                producerError = ex;
                throw;
            }
            finally
            {
                boundedQueue.Writer.TryComplete(producerError);
            }
        }, CancellationToken.None);

        try
        {
            await foreach (OwnedTransferBuffer ownedChunk in boundedQueue.Reader.ReadAllAsync(pipelineCts.Token).ConfigureAwait(false))
            {
                using (ownedChunk)
                {
                    int depth = Interlocked.Decrement(ref currentDepth);
                    diag.UpdateQueueOccupancy(Math.Max(0, depth), Volatile.Read(ref maxDepth), 0, 0, _bufferPool.RentedBufferCount, _bufferPool.RentedBytes);

                    int len = ownedChunk.Length;
                    long writeStart = Stopwatch.GetTimestamp();
                    await channel.WriteDataChunkAsync(
                        bulkRef,
                        ownedChunk.ReadOnlyMemory,
                        pipelineCts.Token).ConfigureAwait(false);
                    diag.AddTlsWrite(len, Stopwatch.GetTimestamp() - writeStart);

                    cumulative += len;
                    interaction.OnProgress(
                        new DesktopTransferProgress(
                            DesktopTransferDirection.Send,
                            peerName,
                            prepared.File.Name,
                            cumulative,
                            totalBytes));
                }
            }

            await producerTask.ConfigureAwait(false);
            return (actualBytes, cumulative);
        }
        catch
        {
            pipelineCts.Cancel();
            DrainAndDisposeChannel(boundedQueue.Reader, ref currentDepth);
            try
            {
                await producerTask.ConfigureAwait(false);
            }
            catch
            {
                // Primary exception or producer exception handled by caller
            }

            if (cancellationToken.IsCancellationRequested)
            {
                cancellationToken.ThrowIfCancellationRequested();
            }

            throw;
        }
        finally
        {
            DrainAndDisposeChannel(boundedQueue.Reader, ref currentDepth);
            diag.UpdateQueueOccupancy(0, Volatile.Read(ref maxDepth), 0, 0, _bufferPool.RentedBufferCount, _bufferPool.RentedBytes);
        }
    }

    private async Task<(long ReceivedBytes, long CumulativeTransferred)> StreamReceiverItemBoundedAsync(
        LanRecordChannel channel,
        Stream fileStream,
        IncrementalHash sha256,
        BulkRef bulkRef,
        DesktopOfferItem item,
        string peerName,
        bool discardStorage,
        long cumulativeTransferred,
        long totalBytes,
        CancellationTokenSource itemTimeoutCts,
        IDesktopTransferInteraction interaction,
        IDesktopTransferAttemptDiagnostics diag,
        CancellationToken cancellationToken)
    {
        int queueCapacity = Math.Clamp(_ioOptions.QueueBufferCount, 1, 64);

        var boundedQueue = Channel.CreateBounded<OwnedTransferBuffer>(
            new BoundedChannelOptions(queueCapacity)
            {
                FullMode = BoundedChannelFullMode.Wait,
                SingleWriter = true,
                SingleReader = true,
                AllowSynchronousContinuations = false
            });

        using var pipelineCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        long receivedBytes = 0;
        long cumulative = cumulativeTransferred;
        int currentDepth = 0;
        int maxDepth = 0;

        Task consumerTask = Task.Run(async () =>
        {
            try
            {
                await foreach (OwnedTransferBuffer ownedChunk in boundedQueue.Reader.ReadAllAsync(pipelineCts.Token).ConfigureAwait(false))
                {
                    using (ownedChunk)
                    {
                        int depth = Interlocked.Decrement(ref currentDepth);
                        diag.UpdateQueueOccupancy(0, 0, Math.Max(0, depth), Volatile.Read(ref maxDepth), _bufferPool.RentedBufferCount, _bufferPool.RentedBytes);

                        int chunkLength = ownedChunk.Length;

                        long hashStart = Stopwatch.GetTimestamp();
                        sha256.AppendData(ownedChunk.Span);
                        diag.AddHashCompute(Stopwatch.GetTimestamp() - hashStart);

                        if (!discardStorage)
                        {
                            long writeStart = Stopwatch.GetTimestamp();
                            await fileStream.WriteAsync(
                                ownedChunk.ReadOnlyMemory,
                                pipelineCts.Token).ConfigureAwait(false);
                            diag.AddStagingWrite(chunkLength, Stopwatch.GetTimestamp() - writeStart);
                        }

                        cumulative += chunkLength;
                        interaction.OnProgress(
                            new DesktopTransferProgress(
                                DesktopTransferDirection.Receive,
                                peerName,
                                item.Name,
                                cumulative,
                                totalBytes));
                    }
                }
            }
            catch
            {
                pipelineCts.Cancel();
                throw;
            }
        }, CancellationToken.None);

        Exception? producerError = null;
        try
        {
            while (true)
            {
                long readStart = Stopwatch.GetTimestamp();
                LanRecord? record = await DesktopControlSequence
                    .ReadOwnedRecordWithDeadlineAsync(
                        channel,
                        bulkRef,
                        itemTimeoutCts,
                        pipelineCts.Token).ConfigureAwait(false);
                long readTicks = Stopwatch.GetTimestamp() - readStart;

                if (record == null)
                {
                    throw new EndOfStreamException(
                        "Connection terminated during payload transfer.");
                }

                if (record is LanRecord.OwnedData ownedData)
                {
                    bool handedOff = false;
                    try
                    {
                        int chunkLength = ownedData.ChunkLength;
                        diag.AddTlsRead(chunkLength, readTicks);

                        if (!string.Equals(
                                ownedData.BulkRef.Value,
                                bulkRef.Value,
                                StringComparison.Ordinal))
                        {
                            throw new ProtocolViolationException(
                                WireErrorCodes.MalformedMessage,
                                "Unmatched bulkRef in DATA record.");
                        }

                        receivedBytes = checked(receivedBytes + chunkLength);
                        if (receivedBytes > item.SizeBytes)
                        {
                            throw new ProtocolViolationException(
                                WireErrorCodes.LimitExceeded,
                                $"Item '{item.Name}' exceeded its declared size.");
                        }

                        int depth = Interlocked.Increment(ref currentDepth);
                        UpdateMax(ref maxDepth, depth);
                        diag.UpdateQueueOccupancy(0, 0, depth, Volatile.Read(ref maxDepth), _bufferPool.RentedBufferCount, _bufferPool.RentedBytes);

                        await boundedQueue.Writer.WriteAsync(ownedData.Buffer, pipelineCts.Token).ConfigureAwait(false);
                        handedOff = true;
                    }
                    finally
                    {
                        if (!handedOff)
                        {
                            ownedData.Dispose();
                        }
                    }
                }
                else if (record is LanRecord.Data dataRecord)
                {
                    int chunkLength = dataRecord.Chunk.Length;
                    diag.AddTlsRead(chunkLength, readTicks);

                    if (!string.Equals(
                            dataRecord.BulkRef.Value,
                            bulkRef.Value,
                            StringComparison.Ordinal))
                    {
                        throw new ProtocolViolationException(
                            WireErrorCodes.MalformedMessage,
                            "Unmatched bulkRef in DATA record.");
                    }

                    receivedBytes = checked(receivedBytes + chunkLength);
                    if (receivedBytes > item.SizeBytes)
                    {
                        throw new ProtocolViolationException(
                            WireErrorCodes.LimitExceeded,
                            $"Item '{item.Name}' exceeded its declared size.");
                    }

                    OwnedTransferBuffer copyBuffer = await _bufferPool.RentAsync(chunkLength, pipelineCts.Token).ConfigureAwait(false);
                    bool handedOff = false;
                    try
                    {
                        dataRecord.Chunk.AsSpan().CopyTo(copyBuffer.Span);
                        int depth = Interlocked.Increment(ref currentDepth);
                        UpdateMax(ref maxDepth, depth);
                        diag.UpdateQueueOccupancy(0, 0, depth, Volatile.Read(ref maxDepth), _bufferPool.RentedBufferCount, _bufferPool.RentedBytes);
                        await boundedQueue.Writer.WriteAsync(copyBuffer, pipelineCts.Token).ConfigureAwait(false);
                        handedOff = true;
                    }
                    finally
                    {
                        if (!handedOff)
                        {
                            copyBuffer.Dispose();
                        }
                    }
                }
                else if (record is LanRecord.StreamEnd streamEnd)
                {
                    if (!string.Equals(
                            streamEnd.BulkRef.Value,
                            bulkRef.Value,
                            StringComparison.Ordinal))
                    {
                        throw new ProtocolViolationException(
                            WireErrorCodes.MalformedMessage,
                            "Unmatched bulkRef in STREAM_END record.");
                    }

                    if (streamEnd.ActualBytes != receivedBytes ||
                        receivedBytes != item.SizeBytes)
                    {
                        throw new ProtocolViolationException(
                            WireErrorCodes.IntegrityMismatch,
                            $"STREAM_END actualBytes {streamEnd.ActualBytes} does not match expected {item.SizeBytes}.");
                    }

                    break;
                }
                else
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.ProtocolOrder,
                        $"Unexpected record kind during data stream: {record.Kind}.");
                }
            }

            boundedQueue.Writer.TryComplete();
            await consumerTask.ConfigureAwait(false);
            return (receivedBytes, cumulative);
        }
        catch (Exception ex)
        {
            producerError = ex;
            boundedQueue.Writer.TryComplete(ex);
            pipelineCts.Cancel();
            DrainAndDisposeChannel(boundedQueue.Reader, ref currentDepth);

            try
            {
                await consumerTask.ConfigureAwait(false);
            }
            catch (Exception consumerEx) when (producerError is OperationCanceledException && consumerEx is not OperationCanceledException)
            {
                throw;
            }
            catch
            {
                // Primary exception handled below
            }

            if (cancellationToken.IsCancellationRequested)
            {
                cancellationToken.ThrowIfCancellationRequested();
            }

            throw;
        }
        finally
        {
            boundedQueue.Writer.TryComplete(producerError);
            DrainAndDisposeChannel(boundedQueue.Reader, ref currentDepth);
            diag.UpdateQueueOccupancy(0, 0, 0, Volatile.Read(ref maxDepth), _bufferPool.RentedBufferCount, _bufferPool.RentedBytes);
        }
    }

    private static void UpdateMax(ref int targetMax, int candidate)
    {
        int current = Volatile.Read(ref targetMax);
        while (candidate > current)
        {
            int prev = Interlocked.CompareExchange(ref targetMax, candidate, current);
            if (prev == current)
            {
                break;
            }
            current = prev;
        }
    }

    private static void DrainAndDisposeChannel(ChannelReader<OwnedTransferBuffer> reader, ref int currentDepth)
    {
        while (reader.TryRead(out OwnedTransferBuffer? leftover))
        {
            Interlocked.Decrement(ref currentDepth);
            leftover.Dispose();
        }
    }

    private static bool IsUncertainAckFailure(Exception error) =>
        error is EndOfStreamException
            or IOException
            or System.Net.Sockets.SocketException
            or TimeoutException;

    private static SessionId NewSessionId() =>
        new(
            Guid.NewGuid()
                .ToString("D")
                .ToLowerInvariant());
}
