using System.Diagnostics;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using Glide.Core.Codec;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Core.Transport.Lan;
using Glide.Desktop.Diagnostics;
using Glide.Desktop.Services;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;

namespace Glide.Desktop;

internal sealed class DesktopAttemptTimingContext
{
    public long DiscoveryTicks { get; set; }
    public long ConnectionTicks { get; set; }
    public long PreAuthTicks { get; set; }
    public string? PairingMode { get; set; }
}

internal sealed record PreparedSendItem(
    ItemId ItemId,
    DesktopTransferFile File,
    long DeclaredSize,
    DateTime LastWriteTimeUtc);

internal sealed record AuthenticatedSessionContext(
    LanRecordChannel Channel,
    DesktopControlSequence Sequence,
    SessionId SessionId,
    HashSet<string> NegotiatedCapabilities,
    string PeerName,
    string? AuthenticatedPeerFingerprintHex);

/// <summary>
/// Coordinates session establishment, Profile 1 invitation bootstrap, Profile 2 GLD2 SESSION_OPEN/ACK,
/// HELLO/HELLO_ACK capability negotiation, and mutual persistent identity authentication.
/// </summary>
internal sealed class DesktopSessionCoordinator
{
    private readonly DesktopTimeoutPolicy _timeoutPolicy;

    public DesktopSessionCoordinator(DesktopTimeoutPolicy timeoutPolicy)
    {
        _timeoutPolicy = timeoutPolicy ?? throw new ArgumentNullException(nameof(timeoutPolicy));
    }

    public static string GetLocalPlatformString()
    {
        if (OperatingSystem.IsWindows()) return "windows";
        if (OperatingSystem.IsLinux()) return "linux";
        if (OperatingSystem.IsMacOS()) return "macos";
        return "unknown";
    }

    public static SessionId NewSessionId() =>
        new(Guid.NewGuid().ToString("D").ToLowerInvariant());

    public static DesktopTransferResult? ValidateSendFiles(
        IReadOnlyList<DesktopTransferFile> files,
        int minCount,
        int maxCount,
        string countErrorMessage,
        out long totalBytes)
    {
        totalBytes = 0;
        if (files.Count < minCount || files.Count > maxCount)
        {
            return DesktopTransferResult.Failed(countErrorMessage);
        }

        foreach (DesktopTransferFile file in files)
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

        return null;
    }

    public async Task<(AuthenticatedSessionContext? Session, DesktopTransferResult? EarlyResult)> EstablishSenderSessionAsync(
        TlsSocketConnection connection,
        LanRecordChannel channel,
        DesktopSendRequest request,
        IDesktopTransferInteraction interaction,
        IDesktopTransferAttemptDiagnostics diag,
        LanInvitationAuthority? hostedInvitationAuthority,
        SessionId? preEstablishedTrustedSessionId,
        string? trustedPeerFallbackName,
        CancellationToken authToken,
        CancellationToken cancellationToken,
        Action? onPeerCryptographicallyAuthenticated)
    {
        long authStart = Stopwatch.GetTimestamp();
        var sequence = new DesktopControlSequence();
        SessionId sessionId;

        if (preEstablishedTrustedSessionId is { } trustedSessionId)
        {
            sessionId = trustedSessionId;
        }
        else
        {
            LanInvitation invitation = request.Invitation
                ?? throw new InvalidOperationException("Authenticated pairing invitation is required.");

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

                await channel.WriteBootstrapAsync(bootstrap, cancellationToken).ConfigureAwait(false);
                bootstrapResult = await DesktopControlSequence.ReadBootstrapResultAsync(
                    channel,
                    cancellationToken).ConfigureAwait(false);
            }
            else
            {
                LanRecord? firstRecord = await DesktopControlSequence.ReadRecordAsync(
                    channel,
                    cancellationToken).ConfigureAwait(false);
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
                await channel.WriteBootstrapResultAsync(bootstrapResult, cancellationToken).ConfigureAwait(false);
                if (!bootstrapResult.IsAccepted)
                {
                    return (null, DesktopTransferResult.RejectedByPeer(
                        $"Pairing bootstrap rejected: {bootstrapResult.Code}",
                        DesktopTransferFailures.FromBootstrapCode(bootstrapResult.Code)));
                }

                sessionId = new SessionId(bootstrap.SessionId);
            }

            if (!bootstrapResult.IsAccepted)
            {
                return (null, DesktopTransferResult.RejectedByPeer(
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

            interaction.OnInvitationConsumed(DesktopTransferDirection.Send);
        }

        string localName = string.IsNullOrWhiteSpace(request.LocalDeviceName)
            ? Environment.MachineName
            : request.LocalDeviceName;

        DesktopPersistentTrustManager? trustManager = request.TrustManager;
        if (request.ExpectedPeerFingerprintHex != null && trustManager == null)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.AuthenticationFailed,
                "A trusted peer was requested but no persistent trust manager is available.");
        }

        trustManager?.Initialize();

        JsonArray optionalCapabilities = trustManager == null
            ? new JsonArray(ProtocolCapabilities.FolderV1)
            : new JsonArray(ProtocolCapabilities.FolderV1, ProtocolCapabilities.IdentityV1);

        await channel.WriteEnvelopeAsync(
            sequence.Create(
                MessageTypes.Hello,
                sessionId,
                new JsonObject
                {
                    ["supportedMajors"] = new JsonArray(1),
                    ["requiredCapabilities"] = new JsonArray(
                        ProtocolCapabilities.StreamV1,
                        ProtocolCapabilities.Sha256),
                    ["optionalCapabilities"] = optionalCapabilities,
                    ["role"] = "sender",
                    ["ephemeralPeerId"] = Guid.NewGuid().ToString("D").ToLowerInvariant(),
                    ["displayName"] = localName
                }),
            authToken).ConfigureAwait(false);

        Envelope helloAck = await sequence.ReadExpectedAsync(
            channel,
            MessageTypes.HelloAck,
            sessionId,
            authToken,
            timeout: _timeoutPolicy.MachineAuthTimeout).ConfigureAwait(false);

        HashSet<string> negotiatedCapabilities = ValidateHelloAckCapabilities(
            helloAck,
            identityOffered: trustManager != null,
            requireIdentity: request.ExpectedPeerFingerprintHex != null);

        string peerName = helloAck.Body["displayName"]?.ToString()
            ?? trustedPeerFallbackName
            ?? request.Invitation?.Name
            ?? "Remote Peer";
        interaction.OnPeerIdentified(DesktopTransferDirection.Send, peerName);

        if (hostedInvitationAuthority != null)
        {
            interaction.OnStatus(
                DesktopTransferDirection.Send,
                $"{peerName} connected. Confirm the receiving device before sending.");
            diag.OnPhaseChanged(DesktopTransferPhase.AwaitingConsent);
            if (!await interaction.ConfirmRecipientAsync(peerName, cancellationToken).ConfigureAwait(false))
            {
                return (null, DesktopTransferResult.RejectedByPeer(
                    "You did not approve the connected receiving device."));
            }
        }

        string? authenticatedFingerprint = null;
        if (negotiatedCapabilities.Contains(ProtocolCapabilities.IdentityV1))
        {
            if (trustManager == null)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.AuthenticationFailed,
                    "Peer selected identity-v1 when no local persistent identity authority was offered.");
            }

            DesktopAuthenticatedPeer authenticated = await DesktopTrustedSessionAuthenticator
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
                    localEndpointHostsTls: hostedInvitationAuthority != null,
                    cancellationToken: authToken,
                    machineAuthTimeout: _timeoutPolicy.MachineAuthTimeout).ConfigureAwait(false);

            authenticatedFingerprint = authenticated.FingerprintHex;
            interaction.OnStatus(
                DesktopTransferDirection.Send,
                authenticated.IsEnrolled
                    ? $"Trusted identity authenticated: {authenticated.FingerprintHex[..12]}..."
                    : $"Peer identity cryptographically verified: {authenticated.FingerprintHex[..12]}...");
        }

        onPeerCryptographicallyAuthenticated?.Invoke();
        diag.RecordAuthenticationDurationTicks(Stopwatch.GetTimestamp() - authStart);

        return (new AuthenticatedSessionContext(
            channel,
            sequence,
            sessionId,
            negotiatedCapabilities,
            peerName,
            authenticatedFingerprint), null);
    }

    public async Task<(AuthenticatedSessionContext? Session, DesktopTransferResult? EarlyResult)> EstablishReceiverSessionAsync(
        TlsSocketConnection connection,
        LanRecordChannel channel,
        DesktopReceiveRequest request,
        IDesktopTransferInteraction interaction,
        IDesktopTransferAttemptDiagnostics diag,
        bool localEndpointHostsTls,
        SessionId? preEstablishedTrustedSessionId,
        CancellationToken cancellationToken)
    {
        long authStart = Stopwatch.GetTimestamp();
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

            LanRecord? firstRecord = await DesktopControlSequence.ReadRecordAsync(
                channel,
                cancellationToken,
                timeout: _timeoutPolicy.PreambleAndSessionOpenTimeout).ConfigureAwait(false);
            if (firstRecord == null)
            {
                return (null, DesktopTransferResult.Failed(
                    "Peer disconnected before sending the first record."));
            }

            if (firstRecord is not LanRecord.Bootstrap bootstrapRecord)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.ProtocolOrder,
                    "Authenticated BOOTSTRAP must be the first LAN record.");
            }

            LanBootstrapMessage bootstrap = LanBootstrapCodec.DecodeBootstrap(bootstrapRecord.JsonBytes);
            bootstrapResult = request.InvitationAuthority.Consume(bootstrap);

            await channel.WriteBootstrapResultAsync(bootstrapResult, cancellationToken).ConfigureAwait(false);

            if (!bootstrapResult.IsAccepted)
            {
                return (null, DesktopTransferResult.RejectedByPeer(
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

            await channel.WriteBootstrapAsync(bootstrap, cancellationToken).ConfigureAwait(false);
            bootstrapResult = await DesktopControlSequence.ReadBootstrapResultAsync(
                channel,
                cancellationToken).ConfigureAwait(false);

            if (!bootstrapResult.IsAccepted)
            {
                return (null, DesktopTransferResult.RejectedByPeer(
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
                interaction.OnInvitationConsumed(DesktopTransferDirection.Receive);
            }

            Envelope hello = await sequence.ReadExpectedAsync(
                channel,
                MessageTypes.Hello,
                sessionId,
                cancellationToken,
                timeout: _timeoutPolicy.MachineAuthTimeout).ConfigureAwait(false);

            if (!string.Equals(
                    hello.Body["role"]?.ToString(),
                    "sender",
                    StringComparison.Ordinal))
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.ProtocolOrder,
                    "Inbound transfer HELLO must use sender role.");
            }

            DesktopPersistentTrustManager? trustManager = request.TrustManager;
            if (request.ExpectedPeerFingerprintHex != null && trustManager == null)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.AuthenticationFailed,
                    "A trusted peer was required but no persistent trust manager is available.");
            }

            trustManager?.Initialize();

            HashSet<string> negotiatedCapabilities = NegotiateHelloCapabilities(
                hello,
                allowIdentity: trustManager != null);

            if (request.ExpectedPeerFingerprintHex != null &&
                !negotiatedCapabilities.Contains(ProtocolCapabilities.IdentityV1))
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.AuthenticationFailed,
                    "Known trusted peer attempted a capability downgrade that omitted identity-v1.");
            }

            string peerName = hello.Body["displayName"]?.ToString() ?? "Remote Peer";
            interaction.OnPeerIdentified(DesktopTransferDirection.Receive, peerName);

            string localName = string.IsNullOrWhiteSpace(request.LocalDeviceName)
                ? Environment.MachineName
                : request.LocalDeviceName;

            await channel.WriteEnvelopeAsync(
                sequence.Create(
                    MessageTypes.HelloAck,
                    sessionId,
                    new JsonObject
                    {
                        ["selectedMajor"] = 1,
                        ["capabilities"] = new JsonArray(
                            negotiatedCapabilities
                                .OrderBy(static c => c, StringComparer.Ordinal)
                                .Select(static c => JsonValue.Create(c))
                                .ToArray()),
                        ["ephemeralPeerId"] = Guid.NewGuid().ToString("D").ToLowerInvariant(),
                        ["displayName"] = localName,
                        ["limits"] = new JsonObject
                        {
                            ["maxItems"] = 1000,
                            ["maxItemBytes"] = ProtocolCatalog.MaxItemBytes.ToString(),
                            ["maxBatchBytes"] = ProtocolCatalog.MaxBatchBytes.ToString(),
                            ["maxControlBytes"] = ProtocolCatalog.MaxControlBytes
                        }
                    }),
                cancellationToken).ConfigureAwait(false);

            string? authenticatedPeerFingerprint = null;
            if (negotiatedCapabilities.Contains(ProtocolCapabilities.IdentityV1))
            {
                if (trustManager == null)
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.AuthenticationFailed,
                        "identity-v1 was negotiated without a local persistent trust authority.");
                }

                DesktopAuthenticatedPeer authenticated = await DesktopTrustedSessionAuthenticator
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
                        cancellationToken,
                        machineAuthTimeout: _timeoutPolicy.MachineAuthTimeout).ConfigureAwait(false);
                authenticatedPeerFingerprint = authenticated.FingerprintHex;

                interaction.OnStatus(
                    DesktopTransferDirection.Receive,
                    authenticated.IsEnrolled
                        ? $"Trusted identity authenticated: {authenticated.FingerprintHex[..12]}..."
                        : $"Peer identity cryptographically verified: {authenticated.FingerprintHex[..12]}...");
            }

            diag.RecordAuthenticationDurationTicks(Stopwatch.GetTimestamp() - authStart);

            return (new AuthenticatedSessionContext(
                channel,
                sequence,
                sessionId,
                negotiatedCapabilities,
                peerName,
                authenticatedPeerFingerprint), null);
        }
        catch
        {
            request.StagingStore.PurgeSession(sessionId);
            throw;
        }
    }

    public static HashSet<string> NegotiateHelloCapabilities(
        Envelope hello,
        bool allowIdentity)
    {
        HashSet<string> required = ReadCapabilitySet(
            hello.Body["requiredCapabilities"],
            "HELLO requiredCapabilities");
        if (!required.Contains(ProtocolCapabilities.StreamV1) ||
            !required.Contains(ProtocolCapabilities.Sha256))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.UnsupportedProtocol,
                "HELLO must require stream-v1 and sha256.");
        }

        HashSet<string> optional = ReadCapabilitySet(
            hello.Body["optionalCapabilities"],
            "HELLO optionalCapabilities");

        ProtocolCapabilities.ValidateDependencies(optional);

        var negotiated = new HashSet<string>(
            ProtocolCapabilities.Required,
            StringComparer.Ordinal);

        foreach (string capability in optional)
        {
            if (ProtocolCapabilities.ProductionOptional.Contains(capability) ||
                (allowIdentity &&
                 string.Equals(capability, ProtocolCapabilities.IdentityV1, StringComparison.Ordinal)))
            {
                negotiated.Add(capability);
            }
        }

        ProtocolCapabilities.ValidateDependencies(negotiated);
        return negotiated;
    }

    public static HashSet<string> ValidateHelloAckCapabilities(
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

        HashSet<string> capabilities = ReadCapabilitySet(
            helloAck.Body["capabilities"],
            "HELLO_ACK capabilities");

        if (!capabilities.Contains(ProtocolCapabilities.StreamV1) ||
            !capabilities.Contains(ProtocolCapabilities.Sha256))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.UnsupportedProtocol,
                "HELLO_ACK must include stream-v1 and sha256.");
        }

        var allowed = new HashSet<string>(
            ProtocolCapabilities.Required,
            StringComparer.Ordinal);
        allowed.UnionWith(ProtocolCapabilities.ProductionOptional);
        if (identityOffered)
        {
            allowed.Add(ProtocolCapabilities.IdentityV1);
        }

        if (capabilities.Except(allowed, StringComparer.Ordinal).Any())
        {
            throw new ProtocolViolationException(
                WireErrorCodes.UnsupportedProtocol,
                "HELLO_ACK selected an unsupported or unoffered capability.");
        }

        if (requireIdentity &&
            !capabilities.Contains(ProtocolCapabilities.IdentityV1))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.AuthenticationFailed,
                "Known trusted peer omitted identity-v1 from HELLO_ACK.");
        }

        ProtocolCapabilities.ValidateDependencies(capabilities);
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

        var result = new HashSet<string>(StringComparer.Ordinal);

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
}
