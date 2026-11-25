using System.Security.Cryptography;
using System.Text.Json.Nodes;
using Glide.Core.Codec;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Desktop.Services;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;

namespace Glide.Desktop;

public sealed partial class DesktopTransferEngine
{
    public async Task<DesktopTransferResult> ResumeTrustedAsync(DesktopResumeRequest request,
        IDesktopTransferInteraction interaction, CancellationToken cancellationToken = default)
    {
        var role = request.Batch.Role;
        var direction = ResumeDirection(role);
        try
        {
            var trust = RequireResumeTrust(request.Local);
            var peer = trust.GetTrustedPeer(request.Batch.PeerFingerprintHex)
                ?? throw new ProtocolViolationException(WireErrorCodes.AuthenticationFailed, "The paired device was revoked.");
            var original = new SessionId(request.Batch.Manifest.SessionId);
            var stored = await RequireAcceptedBatchAsync(request.Local, original, role, peer,
                request.Batch.ManifestDigest, cancellationToken).ConfigureAwait(false);
            var endpoints = TrustedEndpointResolver.BuildSendCandidates(new DesktopSendRequest(
                "", 0, [], CandidateDetails: request.Candidates), peer);
            using var discoveryBudget = _timeoutPolicy.CreateTimeoutTokenSource(
                _timeoutPolicy.TrustedCandidateBudget, cancellationToken);
            string failure = TrustedEndpointResolver.DeviceUnavailableMessage;
            foreach (var candidate in endpoints)
            {
                var open = new Profile2Message.SessionOpen(SessionId.NewRandom().Value,
                    Profile2Constants.PurposeTrustedResume, Profile2Constants.AuthMethodIdentityV1,
                    InvitationCodec.ToUnpaddedBase64Url(RandomNumberGenerator.GetBytes(32)),
                    ResumeDeviceName(request.Local), GetLocalPlatformString(),
                    InvitationCodec.ToUnpaddedBase64Url(trust.LocalPublicKeyDer()),
                    ExpectedPresenceId: candidate.AdvertisedPresenceId,
                    ExpectedPeerIdentityFingerprint: peer.FingerprintHex,
                    OriginalSessionId: original.Value, InitiatorRole: role, ResumeManifestDigest: stored.ManifestDigest);
                TlsSocketConnection connection;
                try
                {
                    connection = await new TlsSocketClient().ConnectAsync(candidate.Endpoint.Address,
                        candidate.Endpoint.Port, null, discoveryBudget.Token,
                        tcpConnectTimeout: _timeoutPolicy.TcpConnectTimeout,
                        tlsHandshakeTimeout: _timeoutPolicy.TlsHandshakeTimeout).ConfigureAwait(false);
                }
                catch (Exception error) when (error is IOException or System.Net.Sockets.SocketException
                    or System.Security.Authentication.AuthenticationException or TimeoutException)
                {
                    continue;
                }
                await using (connection.ConfigureAwait(false))
                {
                    using var openDeadline = _timeoutPolicy.CreateTimeoutTokenSource(
                        _timeoutPolicy.PreambleAndSessionOpenTimeout, discoveryBudget.Token);
                    Profile2Message.SessionOpenAck? ack;
                    try
                    {
                        await LanSessionPreambleInspector.WriteGld2PreambleAsync(connection.TransportStream, openDeadline.Token)
                            .ConfigureAwait(false);
                        await Profile2WireCodec.WriteMessageAsync(connection.TransportStream, open, openDeadline.Token)
                            .ConfigureAwait(false);
                        ack = await Profile2WireCodec.ReadMessageAsync(connection.TransportStream, openDeadline.Token)
                            .ConfigureAwait(false) as Profile2Message.SessionOpenAck;
                    }
                    catch (IOException)
                    {
                        continue;
                    }
                    if (ack?.SessionId != open.SessionId)
                        continue;
                    if (!ack.Accepted)
                    {
                        failure = ack.ErrorCode switch
                        {
                            Profile2Constants.ErrorBusy => TrustedEndpointResolver.DeviceBusyMessage,
                            Profile2Constants.ErrorUnsupportedVersion => "This device does not support resuming a batch.",
                            _ => failure
                        };
                        continue;
                    }
                    if (!MatchesResumeKey(ack.ResponderIdentityPublicKey, peer))
                        continue;
                    // Once the peer agrees to authenticate, never retry this attempt at another endpoint.
                    return await RunResumeBatchAsync(connection, open, stored, request.Local, peer,
                        interaction, localEndpointHostsTls: false, cancellationToken).ConfigureAwait(false);
                }
            }
            return DesktopTransferResult.Failed(failure, TransferErrorCategory.Network);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            return DesktopTransferResult.CancelledByUser("Resume cancelled.");
        }
        catch (Exception error)
        {
            return DesktopTransferResult.Failed(DesktopTransferFailures.DescribeUserMessage(direction, error),
                DesktopTransferFailures.Classify(error));
        }
    }

    public async Task<DesktopTransferResult> ReceiveTrustedResumeAsync(TlsSocketConnection connection,
        Profile2Message.SessionOpen open, DesktopReceiveRequest request, IDesktopTransferInteraction interaction,
        bool isBusy = false, CancellationToken cancellationToken = default)
    {
        string role = open.InitiatorRole == DesktopResumeJournalRoles.Sender
            ? DesktopResumeJournalRoles.Receiver : DesktopResumeJournalRoles.Sender;
        try
        {
            using var openDeadline = _timeoutPolicy.CreateTimeoutTokenSource(
                _timeoutPolicy.PreambleAndSessionOpenTimeout, cancellationToken);
            string? errorCode = null;
            DesktopTrustedPeer? peer = null;
            var trust = request.TrustManager;
            trust?.Initialize();
            string? localFp = trust?.LocalFingerprintHex();
            if (open.Purpose != Profile2Constants.PurposeTrustedResume ||
                open.OriginalSessionId == null || open.ResumeManifestDigest == null ||
                open.ItemId != null || request.ResumeStore == null || trust == null)
            {
                errorCode = Profile2Constants.ErrorUnsupportedVersion;
            }
            else
            {
                var claim = InvitationCodec.FromUnpaddedBase64Url(open.InitiatorIdentityPublicKey);
                var fingerprint = Convert.ToHexStringLower(SHA256.HashData(claim));
                peer = trust.GetTrustedPeer(fingerprint);
                if (open.ExpectedPeerIdentityFingerprint != localFp || peer == null ||
                    (request.ExpectedPeerFingerprintHex != null && fingerprint != request.ExpectedPeerFingerprintHex) ||
                    !MatchesResumeKey(open.InitiatorIdentityPublicKey, peer))
                    errorCode = Profile2Constants.ErrorUntrustedPeer;
                else if (isBusy)
                    errorCode = Profile2Constants.ErrorBusy;
            }
            await Profile2WireCodec.WriteMessageAsync(connection.TransportStream,
                new Profile2Message.SessionOpenAck(open.SessionId, errorCode == null,
                    ErrorCode: errorCode,
                    ResponderNonce: errorCode == null ? InvitationCodec.ToUnpaddedBase64Url(RandomNumberGenerator.GetBytes(32)) : null,
                    ResponderDeviceName: ResumeDeviceName(request), ResponderPlatform: GetLocalPlatformString(),
                    ResponderIdentityPublicKey: trust == null ? null : InvitationCodec.ToUnpaddedBase64Url(trust.LocalPublicKeyDer())),
                openDeadline.Token).ConfigureAwait(false);
            if (errorCode != null)
                return DesktopTransferResult.RejectedByPeer("This resume connection could not be accepted.");
            return await RunResumeBatchAsync(connection, open, null, request, peer!,
                interaction, localEndpointHostsTls: true, cancellationToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            return DesktopTransferResult.CancelledByUser("Resume cancelled.");
        }
        catch (Exception error)
        {
            return DesktopTransferResult.Failed(DesktopTransferFailures.DescribeUserMessage(ResumeDirection(role), error),
                DesktopTransferFailures.Classify(error));
        }
    }

    private async Task<DesktopTransferResult> RunResumeBatchAsync(TlsSocketConnection connection,
        Profile2Message.SessionOpen open, DesktopAcceptedResumeBatch? knownBatch, DesktopReceiveRequest request,
        DesktopTrustedPeer peer, IDesktopTransferInteraction interaction, bool localEndpointHostsTls,
        CancellationToken token)
    {
        var session = new SessionId(open.SessionId);
        var original = new SessionId(open.OriginalSessionId!);
        var trust = RequireResumeTrust(request);
        var store = request.ResumeStore!;
        bool sender = (open.InitiatorRole == DesktopResumeJournalRoles.Sender) != localEndpointHostsTls;
        string role = sender ? DesktopResumeJournalRoles.Sender : DesktopResumeJournalRoles.Receiver;
        var direction = ResumeDirection(role);
        interaction.OnPeerIdentified(direction, peer.DisplayName);
        interaction.OnPhase(direction, DesktopTransferPhase.Preparing);
        await using var channel = new LanRecordChannel(connection.TransportStream,
            lifetimeToken: token, abortTransport: connection.AbortTransport);
        var sequence = new DesktopControlSequence();
        using (var auth = _timeoutPolicy.CreateTimeoutTokenSource(_timeoutPolicy.MachineAuthTimeout, token))
        {
            if (sender)
            {
                await channel.WriteEnvelopeAsync(sequence.Create(MessageTypes.Hello, session, new JsonObject
                {
                    ["supportedMajors"] = new JsonArray(1), ["requiredCapabilities"] = new JsonArray("stream-v1", "sha256"),
                    ["optionalCapabilities"] = new JsonArray("folder-v1", "identity-v1", "resume-v1"),
                    ["role"] = "sender", ["ephemeralPeerId"] = SessionId.NewRandom().Value, ["displayName"] = ResumeDeviceName(request)
                }), auth.Token).ConfigureAwait(false);
                var ack = await sequence.ReadExpectedAsync(channel, MessageTypes.HelloAck, session, auth.Token).ConfigureAwait(false);
                var caps = ValidateHelloAckCapabilities(ack, true, true, resumeOffered: true);
                RequireResumeCapability(caps);
                await DesktopTrustedSessionAuthenticator.AuthenticateSenderAsync(channel, sequence, connection, session,
                    caps, peer.DisplayName, trust, peer.FingerprintHex, interaction, localEndpointHostsTls,
                    auth.Token).ConfigureAwait(false);
                return await ContinueAuthenticatedResumeAsync(channel, sequence, session, original, request, open, caps,
                    knownBatch, peer, role, localEndpointHostsTls, connection, interaction, token).ConfigureAwait(false);
            }
            var hello = await sequence.ReadExpectedAsync(channel, MessageTypes.Hello, session, auth.Token).ConfigureAwait(false);
            RequireExactString(hello, "role", "sender");
            var negotiated = NegotiateHelloCapabilities(hello, true, true);
            RequireResumeCapability(negotiated);
            await channel.WriteEnvelopeAsync(sequence.Create(MessageTypes.HelloAck, session, new JsonObject
            {
                ["selectedMajor"] = 1,
                ["capabilities"] = new JsonArray(negotiated.Order(StringComparer.Ordinal).Select(c => JsonValue.Create(c)).ToArray()),
                ["ephemeralPeerId"] = SessionId.NewRandom().Value, ["displayName"] = ResumeDeviceName(request),
                ["limits"] = new JsonObject
                {
                    ["maxItems"] = 1000,
                    ["maxItemBytes"] = ProtocolCatalog.MaxItemBytes.ToString(),
                    ["maxBatchBytes"] = ProtocolCatalog.MaxBatchBytes.ToString(),
                    ["maxControlBytes"] = ProtocolCatalog.MaxControlBytes
                }
            }), auth.Token).ConfigureAwait(false);
            await DesktopTrustedSessionAuthenticator.AuthenticateReceiverAsync(channel, sequence, connection, session,
                negotiated, peer.DisplayName, trust, peer.FingerprintHex, interaction, localEndpointHostsTls,
                auth.Token).ConfigureAwait(false);
            return await ContinueAuthenticatedResumeAsync(channel, sequence, session, original, request, open, negotiated,
                knownBatch, peer, role, localEndpointHostsTls, connection, interaction, token).ConfigureAwait(false);
        }
    }

    private async Task<DesktopTransferResult> ContinueAuthenticatedResumeAsync(LanRecordChannel channel,
        DesktopControlSequence sequence, SessionId session, SessionId original, DesktopReceiveRequest local,
        Profile2Message.SessionOpen open, HashSet<string> caps, DesktopAcceptedResumeBatch? knownBatch,
        DesktopTrustedPeer peer, string role, bool localEndpointHostsTls, TlsSocketConnection connection,
        IDesktopTransferInteraction interaction, CancellationToken token)
    {
        var store = local.ResumeStore!;
        var trust = RequireResumeTrust(local);
        var accepted = knownBatch ?? await RequireAcceptedBatchAsync(local, original, role, peer,
            open.ResumeManifestDigest!, token).ConfigureAwait(false);
        await using var cancelCleanup = new DesktopResumeCancellation(store, original, token);
        string binding = (localEndpointHostsTls ? connection.LocalSpkiSha256 : connection.RemoteSpkiSha256)
            ?? throw new ProtocolViolationException(WireErrorCodes.AuthenticationFailed, "Missing TLS resume binding.");
        var manifest = accepted.Manifest;
        bool sender = role == DesktopResumeJournalRoles.Sender;
        int prefix;
        if (!sender)
        {
            RequireResumePeer(trust, peer);
            if (accepted.PendingCommitPath != null)
                accepted = await store.RecoverBatchPublishAsync(original, local.StagingStore.DestinationRoot, token)
                    .ConfigureAwait(false) ?? accepted;
            prefix = accepted.VerifiedCount;
            var remaining = manifest.Items.Skip(prefix).Select(item => new DesktopOfferItem(item.Id, item.Name,
                item.SizeBytes, WindowsPathSanitizer.SanitizeRelativePath(item.RelativePath ?? item.Name))).ToArray();
            long remainingBytes = remaining.Sum(item => item.SizeBytes);
            bool available = remaining.Length == 0 ||
                local.StagingStore.HasSufficientFreeSpaceForBatch(remainingBytes, remaining.Max(item => item.SizeBytes));
            bool consent = available;
            if (available && localEndpointHostsTls && remaining.Length != 0)
            {
                interaction.OnPhase(DesktopTransferDirection.Receive, DesktopTransferPhase.AwaitingConsent);
                using var approval = _timeoutPolicy.CreateTimeoutTokenSource(_timeoutPolicy.HumanApprovalTimeout, token);
                consent = await interaction.ConfirmOfferAsync(new DesktopOffer(manifest.BatchId, peer.DisplayName,
                    remaining, remainingBytes, peer.FingerprintHex), approval.Token).ConfigureAwait(false);
            }
            RequireResumePeer(trust, peer);
            var decision = sequence.Create(MessageTypes.OfferDecision, session,
                new JsonObject { ["batchId"] = manifest.BatchId, ["accepted"] = consent });
            if (consent)
                decision = decision with { Extensions = new JsonObject
                {
                    ["resumeManifestDigest"] = accepted.ManifestDigest, ["resumeVerifiedCount"] = prefix
                } };
            else
                decision.Body["code"] = available ? WireErrorCodes.UserRejected : WireErrorCodes.InsufficientStorage;
            await channel.WriteEnvelopeAsync(decision, token).ConfigureAwait(false);
            if (!consent)
                return DesktopTransferResult.RejectedByPeer(available ? "Resume was declined." : "Not enough space to resume.");
        }
        else
        {
            interaction.OnPhase(DesktopTransferDirection.Send, DesktopTransferPhase.AwaitingConsent);
            var decision = await sequence.ReadExpectedAsync(channel, MessageTypes.OfferDecision, session, token,
                timeout: _timeoutPolicy.HumanApprovalTimeout).ConfigureAwait(false);
            RequireExactString(decision, "batchId", manifest.BatchId);
            if (decision.Body["accepted"]?.GetValue<bool>() != true)
                return DesktopTransferResult.RejectedByPeer("The receiving device declined resume.");
            if (decision.Extensions?["resumeManifestDigest"]?.ToString() != accepted.ManifestDigest ||
                decision.Extensions["resumeVerifiedCount"] is not JsonValue count ||
                !int.TryParse(count.ToString(), out prefix) || prefix < 0 || prefix > manifest.Items.Count)
                throw new ProtocolViolationException(WireErrorCodes.AuthenticationFailed, "Invalid accepted resume batch receipt.");
        }

        long total = manifest.Items.Sum(item => item.SizeBytes);
        long transferred = manifest.Items.Take(prefix).Sum(item => item.SizeBytes);
        using IDisposable? lease = sender ? null : local.StagingStore.AcquireSessionLease(session);
        try
        {
            foreach (var item in manifest.Items.Skip(prefix))
            {
                RequireResumePeer(trust, peer);
                interaction.OnPhase(ResumeDirection(role), DesktopTransferPhase.Transferring);
                interaction.OnItemStatus(ResumeDirection(role), item.Name, "Resuming");
                void Progress(long itemOffset) => interaction.OnProgress(new DesktopTransferProgress(
                    ResumeDirection(role), peer.DisplayName, item.Name, transferred + itemOffset, total));
                if (sender)
                    await SendResumedItemAsync(channel, sequence, session, original, item,
                        store, trust, peer, binding, caps, Progress, token).ConfigureAwait(false);
                else
                    await ReceiveResumedItemAsync(channel, sequence, session, original, item,
                        local, trust, peer, binding, caps, interaction, Progress, token).ConfigureAwait(false);
                transferred += item.SizeBytes;
            }
            if (sender)
            {
                await channel.WriteEnvelopeAsync(sequence.Create(MessageTypes.BatchEnd, session,
                    new JsonObject { ["batchId"] = manifest.BatchId }), token).ConfigureAwait(false);
                var result = await sequence.ReadExpectedAsync(channel, MessageTypes.BatchResult, session, token)
                    .ConfigureAwait(false);
                RequireExactString(result, "batchId", manifest.BatchId);
                RequireExactLong(result, "verifiedCount", manifest.Items.Count);
                RequireExactLong(result, "totalVerifiedBytes", total);
                await store.PurgeOriginalSessionAsync(original, CancellationToken.None).ConfigureAwait(false);
            }
            else
            {
                var end = await sequence.ReadExpectedAsync(channel, MessageTypes.BatchEnd, session, token).ConfigureAwait(false);
                RequireExactString(end, "batchId", manifest.BatchId);
                await channel.WriteEnvelopeAsync(sequence.Create(MessageTypes.BatchResult, session, new JsonObject
                {
                    ["batchId"] = manifest.BatchId, ["status"] = "completed", ["verifiedCount"] = manifest.Items.Count,
                    ["totalVerifiedBytes"] = total.ToString()
                }), token).ConfigureAwait(false);
            }
            return DesktopTransferResult.Completed("Transfer complete.", manifest.Items.Count, total);
        }
        finally
        {
            if (!sender)
                local.StagingStore.PurgeSession(session);
        }
    }

    private static DesktopTransferDirection ResumeDirection(string role) => role == DesktopResumeJournalRoles.Sender
        ? DesktopTransferDirection.Send : DesktopTransferDirection.Receive;

    private static string ResumeDeviceName(DesktopReceiveRequest request) =>
        string.IsNullOrWhiteSpace(request.LocalDeviceName) ? Environment.MachineName : request.LocalDeviceName;

    private static DesktopPersistentTrustManager RequireResumeTrust(DesktopReceiveRequest request)
    {
        if (request.ResumeStore == null || request.TrustManager == null)
            throw new ProtocolViolationException(WireErrorCodes.UnsupportedProtocol, "Resume storage or trust is unavailable.");
        request.TrustManager.Initialize();
        return request.TrustManager;
    }

    private static async Task<DesktopAcceptedResumeBatch> RequireAcceptedBatchAsync(DesktopReceiveRequest request,
        SessionId original, string role, DesktopTrustedPeer peer, string digest, CancellationToken token)
    {
        var batch = await request.ResumeStore!.FindAcceptedBatchAsync(original, role, peer.FingerprintHex, digest, token)
            .ConfigureAwait(false);
        string? local = role == DesktopResumeJournalRoles.Sender
            ? batch?.Manifest.SenderFingerprint : batch?.Manifest.ReceiverFingerprint;
        if (batch == null || local != request.TrustManager?.LocalFingerprintHex())
            throw new ProtocolViolationException(WireErrorCodes.AuthenticationFailed,
                "The approved batch has expired, was discarded, or belongs to another device.");
        return batch;
    }

    private static bool MatchesResumeKey(string? encoded, DesktopTrustedPeer peer) =>
        encoded != null && Convert.ToHexStringLower(InvitationCodec.FromUnpaddedBase64Url(encoded)) == peer.PublicKeyDerHex;

    private static void RequireResumePeer(DesktopPersistentTrustManager trust, DesktopTrustedPeer peer)
    {
        if (trust.GetTrustedPeer(peer.FingerprintHex)?.PublicKeyDerHex != peer.PublicKeyDerHex)
            throw new ProtocolViolationException(WireErrorCodes.AuthenticationFailed, "The paired device was revoked.");
    }

    private static void RequireResumeCapability(HashSet<string> caps)
    {
        if (!caps.Contains(ProtocolCapabilities.ResumeV1))
            throw new ProtocolViolationException(WireErrorCodes.UnsupportedProtocol, "The peer omitted the resume capability.");
    }
}
