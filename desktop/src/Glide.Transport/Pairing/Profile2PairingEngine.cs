using System.Net;
using System.Security.Cryptography;
using System.Text;
using Glide.Core.Codec;
using Glide.Core.Crypto;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Transport.Discovery;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;

namespace Glide.Transport.Pairing;

public sealed record Profile2PairOutcome(
    bool Succeeded,
    DesktopTrustedPeer? Peer,
    string? ErrorCode,
    string UserMessage)
{
    public static Profile2PairOutcome Success(DesktopTrustedPeer peer) =>
        new(true, peer, null, $"Paired with {peer.DisplayName}");

    public static Profile2PairOutcome Failure(string errorCode, string userMessage) =>
        new(false, null, errorCode, userMessage);
}

public static class Profile2PairingEngine
{
    public static string MapErrorToUserCopy(string? errorCode, string? peerDeviceName = null, string? fallbackMessage = null)
    {
        return errorCode switch
        {
            Profile2Constants.ErrorExpired => "Code expired — get a new code",
            Profile2Constants.ErrorAuthFailed => "Incorrect code — check the other screen and try again",
            Profile2Constants.ErrorDeclined => string.IsNullOrWhiteSpace(peerDeviceName)
                ? "Pairing declined"
                : $"{peerDeviceName} declined pairing",
            Profile2Constants.ErrorBusy => "This device is busy — try again",
            Profile2Constants.ErrorIdentityChanged => "Identity changed — pair again",
            Profile2Constants.ErrorUntrustedPeer => "Identity changed — pair again",
            Profile2Constants.ErrorUnsupportedVersion => "Update Glide to pair this device",
            _ => !string.IsNullOrWhiteSpace(fallbackMessage)
                ? fallbackMessage
                : "Couldn’t reach this device — check the local network"
        };
    }

    public static readonly TimeSpan DefaultMachineAuthTimeout = TimeSpan.FromSeconds(15);
    public static readonly TimeSpan DefaultHumanApprovalTimeout = TimeSpan.FromSeconds(120);

    private static async Task<Profile2Message> ReadMessageWithDeadlineAsync(
        Stream stream,
        TimeSpan deadline,
        CancellationToken cancellationToken)
    {
        if (deadline <= TimeSpan.Zero || deadline == Timeout.InfiniteTimeSpan)
        {
            return await Profile2WireCodec.ReadMessageAsync(stream, cancellationToken).ConfigureAwait(false);
        }

        using var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        cts.CancelAfter(deadline);
        try
        {
            return await Profile2WireCodec.ReadMessageAsync(stream, cts.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            throw new TimeoutException($"Profile 2 message read timed out after {deadline.TotalSeconds:F1}s.");
        }
    }

    private static async Task WriteMessageWithDeadlineAsync(
        Stream stream,
        Profile2Message message,
        TimeSpan deadline,
        CancellationToken cancellationToken)
    {
        if (deadline <= TimeSpan.Zero || deadline == Timeout.InfiniteTimeSpan)
        {
            await Profile2WireCodec.WriteMessageAsync(stream, message, cancellationToken).ConfigureAwait(false);
            return;
        }

        using var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        cts.CancelAfter(deadline);
        try
        {
            await Profile2WireCodec.WriteMessageAsync(stream, message, cts.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            throw new TimeoutException($"Profile 2 message write timed out after {deadline.TotalSeconds:F1}s.");
        }
    }

    public static async Task<Profile2PairOutcome> RunInitiatorPinPairAsync(
        string host,
        int port,
        string? expectedPresenceId,
        string sixDigitPin,
        string localDeviceName,
        string localPlatform,
        DesktopPersistentTrustManager trustManager,
        Func<VerifiedDesktopPeerIdentity, CancellationToken, Task<bool>> approvePeerAsync,
        Action? onVerifying = null,
        CancellationToken cancellationToken = default,
        TimeSpan? tcpConnectTimeout = null,
        TimeSpan? tlsHandshakeTimeout = null,
        TimeSpan? machineAuthTimeout = null,
        TimeSpan? humanApprovalTimeout = null)
    {
        ArgumentNullException.ThrowIfNull(trustManager);
        ArgumentNullException.ThrowIfNull(approvePeerAsync);

        string normalizedPin = (sixDigitPin ?? string.Empty).Replace(" ", "", StringComparison.Ordinal).Trim();
        if (normalizedPin.Length != 6 || !normalizedPin.All(char.IsAsciiDigit))
        {
            return Profile2PairOutcome.Failure(
                Profile2Constants.ErrorAuthFailed,
                "Incorrect code — check the other screen and try again");
        }

        TlsSocketConnection? connection = null;
        try
        {
            var client = new TlsSocketClient();
            connection = await client.ConnectAsync(
                host,
                port,
                expectedSpkiSha256: null,
                cancellationToken,
                tcpConnectTimeout,
                tlsHandshakeTimeout).ConfigureAwait(false);
            return await RunInitiatorPinPairOverConnectionAsync(
                connection,
                host,
                port,
                expectedPresenceId,
                normalizedPin,
                localDeviceName,
                localPlatform,
                trustManager,
                approvePeerAsync,
                onVerifying,
                cancellationToken,
                machineAuthTimeout,
                humanApprovalTimeout).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            return Profile2PairOutcome.Failure("CANCELLED", "Pairing cancelled");
        }
        catch (Exception)
        {
            return Profile2PairOutcome.Failure(
                "UNREACHABLE",
                "Couldn’t reach this device — check the local network");
        }
        finally
        {
            if (connection != null)
            {
                await connection.DisposeAsync().ConfigureAwait(false);
            }
        }
    }

    public static async Task<Profile2PairOutcome> RunInitiatorPinPairOverConnectionAsync(
        TlsSocketConnection connection,
        string remoteHost,
        int remotePort,
        string? expectedPresenceId,
        string sixDigitPin,
        string localDeviceName,
        string localPlatform,
        DesktopPersistentTrustManager trustManager,
        Func<VerifiedDesktopPeerIdentity, CancellationToken, Task<bool>> approvePeerAsync,
        Action? onVerifying = null,
        CancellationToken cancellationToken = default,
        TimeSpan? machineAuthTimeout = null,
        TimeSpan? humanApprovalTimeout = null)
    {
        TimeSpan authDeadline = machineAuthTimeout ?? DefaultMachineAuthTimeout;
        TimeSpan approvalDeadline = humanApprovalTimeout ?? DefaultHumanApprovalTimeout;
        byte[] pinBytes = Encoding.ASCII.GetBytes(sixDigitPin);
        try
        {
            Stream stream = connection.TransportStream;
            await LanSessionPreambleInspector.WriteGld2PreambleAsync(stream, cancellationToken).ConfigureAwait(false);

            string sessionId = Guid.NewGuid().ToString("D").ToLowerInvariant();
            byte[] initiatorNonce = RandomNumberGenerator.GetBytes(32);
            byte[] initiatorSpki = trustManager.LocalPublicKeyDer();

            var open = new Profile2Message.SessionOpen(
                SessionId: sessionId,
                Purpose: Profile2Constants.PurposePairOnly,
                AuthMethod: Profile2Constants.AuthMethodSpake2PinV1,
                InitiatorNonce: InvitationCodec.ToUnpaddedBase64Url(initiatorNonce),
                InitiatorDeviceName: localDeviceName,
                InitiatorPlatform: localPlatform,
                InitiatorIdentityPublicKey: InvitationCodec.ToUnpaddedBase64Url(initiatorSpki),
                ExpectedPresenceId: expectedPresenceId);

            await WriteMessageWithDeadlineAsync(stream, open, authDeadline, cancellationToken).ConfigureAwait(false);

            var ackMsg = await ReadMessageWithDeadlineAsync(stream, authDeadline, cancellationToken).ConfigureAwait(false);
            if (ackMsg is not Profile2Message.SessionOpenAck ack)
            {
                return Profile2PairOutcome.Failure(Profile2Constants.ErrorProtocolOrder, "Pairing failed");
            }

            if (!ack.Accepted)
            {
                return Profile2PairOutcome.Failure(
                    ack.ErrorCode ?? Profile2Constants.ErrorAuthFailed,
                    MapErrorToUserCopy(ack.ErrorCode, ack.ResponderDeviceName, ack.Message));
            }

            byte[] responderNonce = InvitationCodec.FromUnpaddedBase64Url(ack.ResponderNonce ?? string.Empty);
            byte[] responderSpki = InvitationCodec.FromUnpaddedBase64Url(ack.ResponderIdentityPublicKey ?? string.Empty);
            string responderName = ack.ResponderDeviceName ?? "Glide Device";
            string responderPlatform = ack.ResponderPlatform ?? "unknown";
            string advertisedPresenceId = ack.AdvertisedPresenceId ?? (expectedPresenceId ?? string.Empty);

            GlideSpake2Session.BuildRoleIdentifiers(
                sessionId,
                initiatorNonce,
                responderNonce,
                out byte[] aliceName,
                out byte[] bobName);

            using var spake = new GlideSpake2Session(Spake2Role.Alice, aliceName, bobName);
            byte[] mySpakeMsg = spake.GenerateMessage(pinBytes);
            CryptographicOperations.ZeroMemory(pinBytes);

            await WriteMessageWithDeadlineAsync(
                stream,
                new Profile2Message.PairMsg(
                    SessionId: sessionId,
                    Role: Profile2Constants.RoleInitiator,
                    SpakePublicValue: InvitationCodec.ToUnpaddedBase64Url(mySpakeMsg)),
                authDeadline,
                cancellationToken).ConfigureAwait(false);

            var peerMsgRaw = await ReadMessageWithDeadlineAsync(stream, authDeadline, cancellationToken).ConfigureAwait(false);
            if (peerMsgRaw is Profile2Message.PairResult earlyFailure)
            {
                return Profile2PairOutcome.Failure(
                    earlyFailure.ErrorCode ?? Profile2Constants.ErrorAuthFailed,
                    MapErrorToUserCopy(earlyFailure.ErrorCode, responderName, earlyFailure.Message));
            }
            if (peerMsgRaw is not Profile2Message.PairMsg peerPairMsg ||
                string.IsNullOrEmpty(peerPairMsg.SpakePublicValue))
            {
                return Profile2PairOutcome.Failure(Profile2Constants.ErrorProtocolOrder, "Pairing failed");
            }

            byte[] peerSpakeBytes = InvitationCodec.FromUnpaddedBase64Url(peerPairMsg.SpakePublicValue);
            byte[] sharedSecret = spake.ProcessPeerMessage(peerSpakeBytes);
            try
            {
                return await CompleteInitiatorHandshakeAsync(
                    stream,
                    sessionId,
                    Profile2Constants.AuthMethodSpake2PinV1,
                    initiatorNonce,
                    responderNonce,
                    initiatorSpki,
                    responderSpki,
                    responderName,
                    responderPlatform,
                    remoteHost,
                    remotePort,
                    advertisedPresenceId,
                    connection.RemoteSpkiSha256 ?? string.Empty,
                    sharedSecret,
                    trustManager,
                    approvePeerAsync,
                    onVerifying,
                    authDeadline,
                    approvalDeadline,
                    cancellationToken).ConfigureAwait(false);
            }
            finally
            {
                CryptographicOperations.ZeroMemory(sharedSecret);
            }
        }
        finally
        {
            CryptographicOperations.ZeroMemory(pinBytes);
        }
    }

    public static async Task<Profile2PairOutcome> RunInitiatorQrPairAsync(
        LanPairV2Invitation invitation,
        string localDeviceName,
        string localPlatform,
        DesktopPersistentTrustManager trustManager,
        Func<VerifiedDesktopPeerIdentity, CancellationToken, Task<bool>> approvePeerAsync,
        Action? onVerifying = null,
        CancellationToken cancellationToken = default,
        TimeSpan? tcpConnectTimeout = null,
        TimeSpan? tlsHandshakeTimeout = null,
        TimeSpan? machineAuthTimeout = null,
        TimeSpan? humanApprovalTimeout = null)
    {
        ArgumentNullException.ThrowIfNull(invitation);
        ArgumentNullException.ThrowIfNull(trustManager);
        ArgumentNullException.ThrowIfNull(approvePeerAsync);

        if (invitation.IsExpired())
        {
            return Profile2PairOutcome.Failure(
                Profile2Constants.ErrorExpired,
                "Code expired — get a new code");
        }

        TimeSpan authDeadline = machineAuthTimeout ?? DefaultMachineAuthTimeout;
        TimeSpan approvalDeadline = humanApprovalTimeout ?? DefaultHumanApprovalTimeout;
        LanEndpoint endpoint = invitation.Endpoints[0];
        TlsSocketConnection? connection = null;
        try
        {
            var client = new TlsSocketClient();
            connection = await client.ConnectAsync(
                endpoint.Address,
                endpoint.Port,
                expectedSpkiSha256: invitation.SpkiSha256,
                cancellationToken,
                tcpConnectTimeout,
                tlsHandshakeTimeout).ConfigureAwait(false);

            Stream stream = connection.TransportStream;
            await LanSessionPreambleInspector.WriteGld2PreambleAsync(stream, cancellationToken).ConfigureAwait(false);

            string sessionId = Guid.NewGuid().ToString("D").ToLowerInvariant();
            byte[] initiatorNonce = RandomNumberGenerator.GetBytes(32);
            byte[] initiatorSpki = trustManager.LocalPublicKeyDer();

            var open = new Profile2Message.SessionOpen(
                SessionId: sessionId,
                Purpose: Profile2Constants.PurposePairOnly,
                AuthMethod: Profile2Constants.AuthMethodQrTokenV2,
                InitiatorNonce: InvitationCodec.ToUnpaddedBase64Url(initiatorNonce),
                InitiatorDeviceName: localDeviceName,
                InitiatorPlatform: localPlatform,
                InitiatorIdentityPublicKey: InvitationCodec.ToUnpaddedBase64Url(initiatorSpki),
                ExpectedPresenceId: invitation.Presence,
                InvitationId: invitation.InvitationId);

            await WriteMessageWithDeadlineAsync(stream, open, authDeadline, cancellationToken).ConfigureAwait(false);

            var ackMsg = await ReadMessageWithDeadlineAsync(stream, authDeadline, cancellationToken).ConfigureAwait(false);
            if (ackMsg is not Profile2Message.SessionOpenAck ack)
            {
                return Profile2PairOutcome.Failure(Profile2Constants.ErrorProtocolOrder, "Pairing failed");
            }
            if (!ack.Accepted)
            {
                return Profile2PairOutcome.Failure(
                    ack.ErrorCode ?? Profile2Constants.ErrorAuthFailed,
                    MapErrorToUserCopy(ack.ErrorCode, ack.ResponderDeviceName ?? invitation.Name, ack.Message));
            }

            byte[] responderNonce = InvitationCodec.FromUnpaddedBase64Url(ack.ResponderNonce ?? string.Empty);
            byte[] responderSpki = InvitationCodec.FromUnpaddedBase64Url(ack.ResponderIdentityPublicKey ?? string.Empty);
            string responderName = ack.ResponderDeviceName ?? invitation.Name;
            string responderPlatform = ack.ResponderPlatform ?? invitation.Platform;
            string advertisedPresenceId = ack.AdvertisedPresenceId ?? invitation.Presence;

            await WriteMessageWithDeadlineAsync(
                stream,
                new Profile2Message.PairMsg(
                    SessionId: sessionId,
                    Role: Profile2Constants.RoleInitiator,
                    InvitationHash: invitation.InvitationHash),
                authDeadline,
                cancellationToken).ConfigureAwait(false);

            var peerMsgRaw = await ReadMessageWithDeadlineAsync(stream, authDeadline, cancellationToken).ConfigureAwait(false);
            if (peerMsgRaw is Profile2Message.PairResult earlyFailure)
            {
                return Profile2PairOutcome.Failure(
                    earlyFailure.ErrorCode ?? Profile2Constants.ErrorAuthFailed,
                    MapErrorToUserCopy(earlyFailure.ErrorCode, responderName, earlyFailure.Message));
            }
            if (peerMsgRaw is not Profile2Message.PairMsg peerPairMsg ||
                !string.Equals(peerPairMsg.InvitationHash, invitation.InvitationHash, StringComparison.Ordinal))
            {
                return Profile2PairOutcome.Failure(
                    Profile2Constants.ErrorAuthFailed,
                    "Code expired — get a new code");
            }

            byte[] qrSecret = DeriveQrSharedSecret(invitation.Token, invitation.InvitationHash);
            try
            {
                return await CompleteInitiatorHandshakeAsync(
                    stream,
                    sessionId,
                    Profile2Constants.AuthMethodQrTokenV2,
                    initiatorNonce,
                    responderNonce,
                    initiatorSpki,
                    responderSpki,
                    responderName,
                    responderPlatform,
                    endpoint.Address,
                    endpoint.Port,
                    advertisedPresenceId,
                    connection.RemoteSpkiSha256 ?? string.Empty,
                    qrSecret,
                    trustManager,
                    approvePeerAsync,
                    onVerifying,
                    authDeadline,
                    approvalDeadline,
                    cancellationToken).ConfigureAwait(false);
            }
            finally
            {
                CryptographicOperations.ZeroMemory(qrSecret);
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            return Profile2PairOutcome.Failure("CANCELLED", "Pairing cancelled");
        }
        catch (Exception)
        {
            return Profile2PairOutcome.Failure(
                "UNREACHABLE",
                "Couldn’t reach this device — check the local network");
        }
        finally
        {
            if (connection != null)
            {
                await connection.DisposeAsync().ConfigureAwait(false);
            }
        }
    }

    private static async Task<Profile2PairOutcome> CompleteInitiatorHandshakeAsync(
        Stream stream,
        string sessionId,
        string authMethod,
        byte[] initiatorNonce,
        byte[] responderNonce,
        byte[] initiatorSpki,
        byte[] responderSpki,
        string responderName,
        string responderPlatform,
        string remoteHost,
        int remotePort,
        string advertisedPresenceId,
        string serverTlsSpkiSha256Hex,
        byte[] sharedSecret64,
        DesktopPersistentTrustManager trustManager,
        Func<VerifiedDesktopPeerIdentity, CancellationToken, Task<bool>> approvePeerAsync,
        Action? onVerifying,
        TimeSpan authDeadline,
        TimeSpan approvalDeadline,
        CancellationToken cancellationToken)
    {
        onVerifying?.Invoke();

        byte[] transcriptBytes = GlideSpake2Session.BuildPairV2TranscriptBytes(
            sessionId: sessionId,
            initiatorNonce32: initiatorNonce,
            responderNonce32: responderNonce,
            initiatorIdentitySpki: initiatorSpki,
            responderIdentitySpki: responderSpki,
            advertisedPresenceIdHex: advertisedPresenceId,
            serverTlsSpkiSha256Hex: serverTlsSpkiSha256Hex,
            purpose: Profile2Constants.PurposePairOnly,
            method: authMethod);

        byte[] transcriptHash = SHA256.HashData(transcriptBytes);
        var (confirmI2RKey, confirmR2IKey) = GlideSpake2Session.DeriveConfirmationKeys(sharedSecret64, transcriptHash);
        try
        {
            byte[] myMac = GlideSpake2Session.ComputeConfirmationMac(confirmI2RKey, transcriptHash);
            byte[] mySig = trustManager.SignTranscript(transcriptBytes);

            await WriteMessageWithDeadlineAsync(
                stream,
                new Profile2Message.PairConfirm(
                    SessionId: sessionId,
                    Role: Profile2Constants.RoleInitiator,
                    KeyConfirmationMac: InvitationCodec.ToUnpaddedBase64Url(myMac),
                    IdentitySignature: InvitationCodec.ToUnpaddedBase64Url(mySig)),
                authDeadline,
                cancellationToken).ConfigureAwait(false);

            var nextMsg = await ReadMessageWithDeadlineAsync(stream, authDeadline, cancellationToken).ConfigureAwait(false);
            if (nextMsg is Profile2Message.PairResult failureResult)
            {
                return Profile2PairOutcome.Failure(
                    failureResult.ErrorCode ?? Profile2Constants.ErrorAuthFailed,
                    MapErrorToUserCopy(failureResult.ErrorCode, responderName, failureResult.Message));
            }
            if (nextMsg is not Profile2Message.PairConfirm peerConfirm)
            {
                return Profile2PairOutcome.Failure(Profile2Constants.ErrorProtocolOrder, "Pairing failed");
            }

            byte[] peerMac = InvitationCodec.FromUnpaddedBase64Url(peerConfirm.KeyConfirmationMac);
            byte[] expectedPeerMac = GlideSpake2Session.ComputeConfirmationMac(confirmR2IKey, transcriptHash);
            if (!CryptographicOperations.FixedTimeEquals(peerMac, expectedPeerMac))
            {
                return Profile2PairOutcome.Failure(
                    Profile2Constants.ErrorAuthFailed,
                    "Incorrect code — check the other screen and try again");
            }

            byte[] peerSig = InvitationCodec.FromUnpaddedBase64Url(peerConfirm.IdentitySignature);
            if (!VerifyIdentitySignature(responderSpki, transcriptBytes, peerSig, out string responderFingerprintHex))
            {
                return Profile2PairOutcome.Failure(
                    Profile2Constants.ErrorAuthFailed,
                    "Incorrect code — check the other screen and try again");
            }

            var verifiedPeer = new VerifiedDesktopPeerIdentity(
                fingerprintHex: responderFingerprintHex,
                publicKeyDerHex: Convert.ToHexStringLower(responderSpki),
                displayName: responderName,
                platform: responderPlatform,
                lastKnownHost: remoteHost,
                lastKnownPort: remotePort);

            bool initiatorApproved;
            using (var approvalCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken))
            {
                if (approvalDeadline > TimeSpan.Zero && approvalDeadline != Timeout.InfiniteTimeSpan)
                {
                    approvalCts.CancelAfter(approvalDeadline);
                }
                try
                {
                    initiatorApproved = await approvePeerAsync(verifiedPeer, approvalCts.Token).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
                {
                    initiatorApproved = false;
                }
            }

            await WriteMessageWithDeadlineAsync(
                stream,
                new Profile2Message.PairApproval(
                    SessionId: sessionId,
                    Role: Profile2Constants.RoleInitiator,
                    Approved: initiatorApproved,
                    Reason: initiatorApproved ? null : Profile2Constants.ErrorDeclined),
                authDeadline,
                cancellationToken).ConfigureAwait(false);

            var responderApprovalMsg = await ReadMessageWithDeadlineAsync(stream, approvalDeadline, cancellationToken).ConfigureAwait(false);
            if (responderApprovalMsg is Profile2Message.PairResult directResult)
            {
                return Profile2PairOutcome.Failure(
                    directResult.ErrorCode ?? Profile2Constants.ErrorDeclined,
                    MapErrorToUserCopy(directResult.ErrorCode, responderName, directResult.Message));
            }
            if (responderApprovalMsg is not Profile2Message.PairApproval responderApproval)
            {
                return Profile2PairOutcome.Failure(Profile2Constants.ErrorProtocolOrder, "Pairing failed");
            }

            var finalResultMsg = await ReadMessageWithDeadlineAsync(stream, authDeadline, cancellationToken).ConfigureAwait(false);
            if (finalResultMsg is not Profile2Message.PairResult finalResult || !finalResult.Paired || !initiatorApproved || !responderApproval.Approved)
            {
                string errCode = (finalResultMsg as Profile2Message.PairResult)?.ErrorCode ?? Profile2Constants.ErrorDeclined;
                string? errMsg = (finalResultMsg as Profile2Message.PairResult)?.Message;
                return Profile2PairOutcome.Failure(errCode, MapErrorToUserCopy(errCode, responderName, errMsg));
            }

            DesktopTrustedPeer? enrolled = trustManager.EnrollVerifiedPeer(verifiedPeer, userApproved: true);
            if (enrolled == null)
            {
                return Profile2PairOutcome.Failure(Profile2Constants.ErrorDeclined, "Pairing declined");
            }

            return Profile2PairOutcome.Success(enrolled);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(confirmI2RKey);
            CryptographicOperations.ZeroMemory(confirmR2IKey);
        }
    }

    /// <summary>
    /// Handles an incoming `PAIR_ONLY` session on the responder after `SESSION_OPEN` has been read.
    /// </summary>
    public static async Task<Profile2PairOutcome> HandleResponderPairOnlySessionAsync(
        TlsSocketConnection connection,
        Profile2Message.SessionOpen open,
        PairReadyWindowAuthority? activeWindow,
        bool isBusy,
        string localDeviceName,
        string localPlatform,
        DesktopPersistentTrustManager trustManager,
        Func<VerifiedDesktopPeerIdentity, CancellationToken, Task<bool>> approvePeerAsync,
        Action? onVerifying = null,
        CancellationToken cancellationToken = default,
        TimeSpan? machineAuthTimeout = null,
        TimeSpan? humanApprovalTimeout = null)
    {
        ArgumentNullException.ThrowIfNull(connection);
        ArgumentNullException.ThrowIfNull(open);
        ArgumentNullException.ThrowIfNull(trustManager);
        ArgumentNullException.ThrowIfNull(approvePeerAsync);

        TimeSpan authDeadline = machineAuthTimeout ?? DefaultMachineAuthTimeout;
        TimeSpan approvalDeadline = humanApprovalTimeout ?? DefaultHumanApprovalTimeout;
        Stream stream = connection.TransportStream;

        if (!string.Equals(open.Purpose, Profile2Constants.PurposePairOnly, StringComparison.Ordinal))
        {
            await WriteMessageWithDeadlineAsync(
                stream,
                new Profile2Message.SessionOpenAck(
                    SessionId: open.SessionId,
                    Accepted: false,
                    ErrorCode: Profile2Constants.ErrorProtocolOrder,
                    Message: "Invalid session purpose for PAIR_ONLY handler",
                    ResponderDeviceName: localDeviceName),
                authDeadline,
                cancellationToken).ConfigureAwait(false);
            return Profile2PairOutcome.Failure(Profile2Constants.ErrorProtocolOrder, "Pairing failed");
        }

        if (isBusy)
        {
            await WriteMessageWithDeadlineAsync(
                stream,
                new Profile2Message.SessionOpenAck(
                    SessionId: open.SessionId,
                    Accepted: false,
                    ErrorCode: Profile2Constants.ErrorBusy,
                    Message: "This device is busy — try again",
                    ResponderDeviceName: localDeviceName),
                authDeadline,
                cancellationToken).ConfigureAwait(false);
            return Profile2PairOutcome.Failure(Profile2Constants.ErrorBusy, "This device is busy — try again");
        }

        if (activeWindow == null || !activeWindow.IsActive())
        {
            await WriteMessageWithDeadlineAsync(
                stream,
                new Profile2Message.SessionOpenAck(
                    SessionId: open.SessionId,
                    Accepted: false,
                    ErrorCode: Profile2Constants.ErrorExpired,
                    Message: "Code expired — get a new code",
                    ResponderDeviceName: localDeviceName),
                authDeadline,
                cancellationToken).ConfigureAwait(false);
            return Profile2PairOutcome.Failure(Profile2Constants.ErrorExpired, "Code expired — get a new code");
        }

        if (!string.IsNullOrEmpty(open.ExpectedPresenceId) &&
            !string.Equals(open.ExpectedPresenceId, activeWindow.PresenceId, StringComparison.OrdinalIgnoreCase))
        {
            await WriteMessageWithDeadlineAsync(
                stream,
                new Profile2Message.SessionOpenAck(
                    SessionId: open.SessionId,
                    Accepted: false,
                    ErrorCode: Profile2Constants.ErrorExpired,
                    Message: "Code expired — get a new code",
                    ResponderDeviceName: localDeviceName),
                authDeadline,
                cancellationToken).ConfigureAwait(false);
            return Profile2PairOutcome.Failure(Profile2Constants.ErrorExpired, "Code expired — get a new code");
        }

        byte[] initiatorNonce = InvitationCodec.FromUnpaddedBase64Url(open.InitiatorNonce);
        byte[] initiatorSpki = InvitationCodec.FromUnpaddedBase64Url(open.InitiatorIdentityPublicKey);
        byte[] responderNonce = RandomNumberGenerator.GetBytes(32);
        byte[] responderSpki = trustManager.LocalPublicKeyDer();

        await WriteMessageWithDeadlineAsync(
            stream,
            new Profile2Message.SessionOpenAck(
                SessionId: open.SessionId,
                Accepted: true,
                ResponderNonce: InvitationCodec.ToUnpaddedBase64Url(responderNonce),
                ResponderDeviceName: localDeviceName,
                ResponderPlatform: localPlatform,
                ResponderIdentityPublicKey: InvitationCodec.ToUnpaddedBase64Url(responderSpki),
                AdvertisedPresenceId: activeWindow.PresenceId),
            authDeadline,
            cancellationToken).ConfigureAwait(false);

        Profile2Message pairMsgRaw;
        try
        {
            pairMsgRaw = await ReadMessageWithDeadlineAsync(stream, authDeadline, cancellationToken).ConfigureAwait(false);
        }
        catch (TimeoutException)
        {
            return Profile2PairOutcome.Failure(Profile2Constants.ErrorProtocolOrder, "Pairing failed");
        }
        catch (Glide.Core.Models.ProtocolViolationException)
        {
            return Profile2PairOutcome.Failure(Profile2Constants.ErrorProtocolOrder, "Pairing failed");
        }

        if (pairMsgRaw is not Profile2Message.PairMsg initiatorPairMsg)
        {
            return Profile2PairOutcome.Failure(Profile2Constants.ErrorProtocolOrder, "Pairing failed");
        }

        byte[] sharedSecret = Array.Empty<byte>();
        try
        {
            if (string.Equals(open.AuthMethod, Profile2Constants.AuthMethodSpake2PinV1, StringComparison.Ordinal))
            {
                if (!activeWindow.TryCopyPinBytes(out byte[] pinBytes, out string? errCode, out string? errMsg))
                {
                    await WriteMessageWithDeadlineAsync(
                        stream,
                        new Profile2Message.PairResult(open.SessionId, false, errCode, errMsg),
                        authDeadline,
                        cancellationToken).ConfigureAwait(false);
                    return Profile2PairOutcome.Failure(errCode ?? Profile2Constants.ErrorExpired, errMsg ?? "Code expired — get a new code");
                }

                try
                {
                    GlideSpake2Session.BuildRoleIdentifiers(
                        open.SessionId,
                        initiatorNonce,
                        responderNonce,
                        out byte[] aliceName,
                        out byte[] bobName);

                    using var spake = new GlideSpake2Session(Spake2Role.Bob, bobName, aliceName);
                    byte[] mySpakeMsg = spake.GenerateMessage(pinBytes);
                    CryptographicOperations.ZeroMemory(pinBytes);

                    byte[] peerSpakeBytes = InvitationCodec.FromUnpaddedBase64Url(initiatorPairMsg.SpakePublicValue ?? string.Empty);
                    sharedSecret = spake.ProcessPeerMessage(peerSpakeBytes);

                    await WriteMessageWithDeadlineAsync(
                        stream,
                        new Profile2Message.PairMsg(
                            SessionId: open.SessionId,
                            Role: Profile2Constants.RoleResponder,
                            SpakePublicValue: InvitationCodec.ToUnpaddedBase64Url(mySpakeMsg)),
                        authDeadline,
                        cancellationToken).ConfigureAwait(false);
                }
                finally
                {
                    CryptographicOperations.ZeroMemory(pinBytes);
                }
            }
            else if (string.Equals(open.AuthMethod, Profile2Constants.AuthMethodQrTokenV2, StringComparison.Ordinal))
            {
                if (!string.Equals(open.InvitationId, activeWindow.QrInvitation.InvitationId, StringComparison.Ordinal) ||
                    !string.Equals(initiatorPairMsg.InvitationHash, activeWindow.QrInvitation.InvitationHash, StringComparison.Ordinal))
                {
                    await WriteMessageWithDeadlineAsync(
                        stream,
                        new Profile2Message.PairResult(
                            open.SessionId,
                            false,
                            Profile2Constants.ErrorExpired,
                            "Code expired — get a new code"),
                        authDeadline,
                        cancellationToken).ConfigureAwait(false);
                    return Profile2PairOutcome.Failure(Profile2Constants.ErrorExpired, "Code expired — get a new code");
                }

                sharedSecret = DeriveQrSharedSecret(activeWindow.QrInvitation.Token, activeWindow.QrInvitation.InvitationHash);

                await WriteMessageWithDeadlineAsync(
                    stream,
                    new Profile2Message.PairMsg(
                        SessionId: open.SessionId,
                        Role: Profile2Constants.RoleResponder,
                        InvitationHash: activeWindow.QrInvitation.InvitationHash),
                    authDeadline,
                    cancellationToken).ConfigureAwait(false);
            }
            else
            {
                await WriteMessageWithDeadlineAsync(
                    stream,
                    new Profile2Message.PairResult(
                        open.SessionId,
                        false,
                        Profile2Constants.ErrorUnsupportedVersion,
                        "Update Glide to pair this device"),
                    authDeadline,
                    cancellationToken).ConfigureAwait(false);
                return Profile2PairOutcome.Failure(Profile2Constants.ErrorUnsupportedVersion, "Update Glide to pair this device");
            }

            onVerifying?.Invoke();

            byte[] transcriptBytes = GlideSpake2Session.BuildPairV2TranscriptBytes(
                sessionId: open.SessionId,
                initiatorNonce32: initiatorNonce,
                responderNonce32: responderNonce,
                initiatorIdentitySpki: initiatorSpki,
                responderIdentitySpki: responderSpki,
                advertisedPresenceIdHex: activeWindow.PresenceId,
                serverTlsSpkiSha256Hex: connection.LocalSpkiSha256 ?? string.Empty,
                purpose: Profile2Constants.PurposePairOnly,
                method: open.AuthMethod);

            byte[] transcriptHash = SHA256.HashData(transcriptBytes);
            var (confirmI2RKey, confirmR2IKey) = GlideSpake2Session.DeriveConfirmationKeys(sharedSecret, transcriptHash);
            try
            {
                var confirmRaw = await ReadMessageWithDeadlineAsync(stream, authDeadline, cancellationToken).ConfigureAwait(false);
                if (confirmRaw is not Profile2Message.PairConfirm initiatorConfirm)
                {
                    return Profile2PairOutcome.Failure(Profile2Constants.ErrorProtocolOrder, "Pairing failed");
                }

                byte[] initiatorMac = InvitationCodec.FromUnpaddedBase64Url(initiatorConfirm.KeyConfirmationMac);
                byte[] expectedInitiatorMac = GlideSpake2Session.ComputeConfirmationMac(confirmI2RKey, transcriptHash);
                bool macMatches = CryptographicOperations.FixedTimeEquals(initiatorMac, expectedInitiatorMac);

                byte[] initiatorSig = InvitationCodec.FromUnpaddedBase64Url(initiatorConfirm.IdentitySignature);
                bool sigMatches = VerifyIdentitySignature(initiatorSpki, transcriptBytes, initiatorSig, out string initiatorFingerprintHex);

                if (!macMatches || !sigMatches)
                {
                    bool windowInvalidated = string.Equals(open.AuthMethod, Profile2Constants.AuthMethodSpake2PinV1, StringComparison.Ordinal)
                        && activeWindow.RecordFailedPinAttempt();

                    string failCode = windowInvalidated ? Profile2Constants.ErrorExpired : Profile2Constants.ErrorAuthFailed;
                    string failMsg = windowInvalidated
                        ? "Code expired — get a new code"
                        : "Incorrect code — check the other screen and try again";

                    await WriteMessageWithDeadlineAsync(
                        stream,
                        new Profile2Message.PairResult(open.SessionId, false, failCode, failMsg),
                        authDeadline,
                        cancellationToken).ConfigureAwait(false);

                    return Profile2PairOutcome.Failure(failCode, failMsg);
                }

                byte[] myMac = GlideSpake2Session.ComputeConfirmationMac(confirmR2IKey, transcriptHash);
                byte[] mySig = trustManager.SignTranscript(transcriptBytes);

                await WriteMessageWithDeadlineAsync(
                    stream,
                    new Profile2Message.PairConfirm(
                        SessionId: open.SessionId,
                        Role: Profile2Constants.RoleResponder,
                        KeyConfirmationMac: InvitationCodec.ToUnpaddedBase64Url(myMac),
                        IdentitySignature: InvitationCodec.ToUnpaddedBase64Url(mySig)),
                    authDeadline,
                    cancellationToken).ConfigureAwait(false);

                string? remoteHost = (connection.TcpClient.Client.RemoteEndPoint as IPEndPoint)?.Address.ToString();
                var verifiedPeer = new VerifiedDesktopPeerIdentity(
                    fingerprintHex: initiatorFingerprintHex,
                    publicKeyDerHex: Convert.ToHexStringLower(initiatorSpki),
                    displayName: open.InitiatorDeviceName,
                    platform: open.InitiatorPlatform,
                    lastKnownHost: remoteHost,
                    lastKnownPort: LanDiscoveryConstants.DefaultPort);

                bool responderApproved;
                using (var approvalCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken))
                {
                    if (approvalDeadline > TimeSpan.Zero && approvalDeadline != Timeout.InfiniteTimeSpan)
                    {
                        approvalCts.CancelAfter(approvalDeadline);
                    }
                    try
                    {
                        responderApproved = await approvePeerAsync(verifiedPeer, approvalCts.Token).ConfigureAwait(false);
                    }
                    catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
                    {
                        responderApproved = false;
                    }
                }

                var initiatorApprovalRaw = await ReadMessageWithDeadlineAsync(stream, approvalDeadline, cancellationToken).ConfigureAwait(false);
                if (initiatorApprovalRaw is not Profile2Message.PairApproval initiatorApproval)
                {
                    return Profile2PairOutcome.Failure(Profile2Constants.ErrorProtocolOrder, "Pairing failed");
                }

                await WriteMessageWithDeadlineAsync(
                    stream,
                    new Profile2Message.PairApproval(
                        SessionId: open.SessionId,
                        Role: Profile2Constants.RoleResponder,
                        Approved: responderApproved,
                        Reason: responderApproved ? null : Profile2Constants.ErrorDeclined),
                    authDeadline,
                    cancellationToken).ConfigureAwait(false);

                if (!initiatorApproval.Approved || !responderApproved)
                {
                    string declineMsg = $"{open.InitiatorDeviceName} declined pairing";
                    await WriteMessageWithDeadlineAsync(
                        stream,
                        new Profile2Message.PairResult(
                            open.SessionId,
                            false,
                            Profile2Constants.ErrorDeclined,
                            declineMsg),
                        authDeadline,
                        cancellationToken).ConfigureAwait(false);
                    return Profile2PairOutcome.Failure(Profile2Constants.ErrorDeclined, responderApproved ? declineMsg : "Pairing declined");
                }

                if (!activeWindow.TryConsumeSuccess())
                {
                    await WriteMessageWithDeadlineAsync(
                        stream,
                        new Profile2Message.PairResult(
                            open.SessionId,
                            false,
                            Profile2Constants.ErrorExpired,
                            "Code expired — get a new code"),
                        authDeadline,
                        cancellationToken).ConfigureAwait(false);
                    return Profile2PairOutcome.Failure(Profile2Constants.ErrorExpired, "Code expired — get a new code");
                }

                DesktopTrustedPeer? enrolled = trustManager.EnrollVerifiedPeer(verifiedPeer, userApproved: true);
                if (enrolled == null)
                {
                    await WriteMessageWithDeadlineAsync(
                        stream,
                        new Profile2Message.PairResult(
                            open.SessionId,
                            false,
                            Profile2Constants.ErrorDeclined,
                            "Pairing declined"),
                        authDeadline,
                        cancellationToken).ConfigureAwait(false);
                    return Profile2PairOutcome.Failure(Profile2Constants.ErrorDeclined, "Pairing declined");
                }

                await WriteMessageWithDeadlineAsync(
                    stream,
                    new Profile2Message.PairResult(
                        open.SessionId,
                        true,
                        null,
                        $"Paired with {localDeviceName}"),
                    authDeadline,
                    cancellationToken).ConfigureAwait(false);

                return Profile2PairOutcome.Success(enrolled);
            }
            catch (TimeoutException)
            {
                return Profile2PairOutcome.Failure(Profile2Constants.ErrorProtocolOrder, "Pairing failed");
            }
            catch (Glide.Core.Models.ProtocolViolationException)
            {
                return Profile2PairOutcome.Failure(Profile2Constants.ErrorProtocolOrder, "Pairing failed");
            }
            finally
            {
                CryptographicOperations.ZeroMemory(confirmI2RKey);
                CryptographicOperations.ZeroMemory(confirmR2IKey);
            }
        }
        finally
        {
            if (sharedSecret.Length > 0)
            {
                CryptographicOperations.ZeroMemory(sharedSecret);
            }
        }
    }

    public static byte[] DeriveQrSharedSecret(string tokenBase64Url, string invitationHashHex)
    {
        byte[] tokenBytes = InvitationCodec.FromUnpaddedBase64Url(tokenBase64Url);
        byte[] salt = SHA256.HashData(Encoding.ASCII.GetBytes(invitationHashHex.ToLowerInvariant()));
        byte[] prk = HKDF.Extract(HashAlgorithmName.SHA256, tokenBytes, salt);
        try
        {
            byte[] secret = new byte[64];
            HKDF.Expand(HashAlgorithmName.SHA256, prk, secret, "glide-pair-v2-qr-secret"u8);
            return secret;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(tokenBytes);
            CryptographicOperations.ZeroMemory(prk);
        }
    }

    public static bool VerifyIdentitySignature(
        ReadOnlySpan<byte> publicKeySpkiDer,
        ReadOnlySpan<byte> transcriptBytes,
        ReadOnlySpan<byte> signatureDer,
        out string fingerprintHex)
    {
        fingerprintHex = string.Empty;
        try
        {
            using ECDsa ecdsa = ECDsa.Create();
            ecdsa.ImportSubjectPublicKeyInfo(publicKeySpkiDer, out int bytesRead);
            if (bytesRead != publicKeySpkiDer.Length)
            {
                return false;
            }

            if (!ecdsa.VerifyData(
                    transcriptBytes,
                    signatureDer,
                    HashAlgorithmName.SHA256,
                    DSASignatureFormat.Rfc3279DerSequence))
            {
                return false;
            }

            fingerprintHex = Convert.ToHexStringLower(SHA256.HashData(publicKeySpkiDer));
            return true;
        }
        catch
        {
            return false;
        }
    }
}
