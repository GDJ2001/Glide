using System.Security.Cryptography;
using System.Text.Json.Nodes;
using Glide.Core.Models;
using Glide.Core.Security;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;

namespace Glide.Desktop;

internal sealed record DesktopAuthenticatedPeer(
    string FingerprintHex,
    bool IsEnrolled);

/// <summary>
/// identity-v1 mutual proof for an already-authenticated Glide LAN TLS channel.
///
/// The proof is bound to the SHA-256 SPKI fingerprint of the TLS server certificate actually used
/// by the connection, the active BOOTSTRAP session id, fresh challenges, both persistent identity
/// fingerprints, roles and the exact negotiated capability set.
/// </summary>
internal static class DesktopTrustedSessionAuthenticator
{
    public static async Task<DesktopAuthenticatedPeer> AuthenticateSenderAsync(
        LanRecordChannel channel,
        DesktopControlSequence sequence,
        TlsSocketConnection connection,
        SessionId sessionId,
        IReadOnlyCollection<string> negotiatedCapabilities,
        string peerDisplayName,
        DesktopPersistentTrustManager trustManager,
        string? expectedPeerFingerprintHex,
        IDesktopTransferInteraction interaction,
        bool localEndpointHostsTls,
        CancellationToken cancellationToken)
    {
        RequireIdentityCapability(negotiatedCapabilities);
        string channelBinding =
            // The identity transcript is always bound to the TLS *server*
            // certificate. Historically senders always initiated the socket,
            // so the remote certificate was the server certificate. Sender-
            // hosted QR pairing reverses only connection establishment: the
            // sender is now the TLS server and the phone is the client.
            // Binding that path to RemoteSpkiSha256 makes each endpoint sign a
            // different transcript and forces a safe-but-confusing disconnect.
            RequireChannelBinding(
                localEndpointHostsTls
                    ? connection.LocalSpkiSha256
                    : connection.RemoteSpkiSha256);
        string localFingerprint =
            trustManager.LocalFingerprintHex();
        RequireExpectedPeerIsEnrolled(
            trustManager,
            expectedPeerFingerprintHex);

        string challengeNonce =
            Convert.ToHexStringLower(
                RandomNumberGenerator.GetBytes(32));
        string capabilityHash =
            IdentityResumeTranscript.CapabilityHashHex(
                negotiatedCapabilities);

        Envelope challenge =
            sequence.Create(
                MessageTypes.IdentityChallenge,
                sessionId,
                CreateChallengeBody(
                    challengeNonce,
                    localFingerprint,
                    verifierRole: "sender",
                    signerRole: "receiver",
                    channelBinding,
                    capabilityHash));

        await channel.WriteEnvelopeAsync(
            challenge,
            cancellationToken);

        Envelope responseEnvelope =
            await sequence.ReadExpectedAsync(
                channel,
                MessageTypes.IdentityResponse,
                sessionId,
                cancellationToken);

        VerifiedDesktopPeerIdentity peer =
            VerifyResponse(
                responseEnvelope,
                sessionId,
                challenge,
                signerRole: "receiver",
                verifierRole: "sender",
                verifierFingerprint: localFingerprint,
                channelBinding,
                negotiatedCapabilities,
                expectedPeerFingerprintHex,
                peerDisplayName,
                connection,
                localEndpointHostsTls);

        DesktopAuthenticatedPeer authenticated =
            await FinalizeVerifiedPeerAsync(
                trustManager,
                peer,
                expectedPeerFingerprintHex,
                interaction,
                cancellationToken);

        Envelope reciprocalChallenge =
            await sequence.ReadExpectedAsync(
                channel,
                MessageTypes.IdentityChallenge,
                sessionId,
                cancellationToken);

        ValidateChallenge(
            reciprocalChallenge,
            expectedVerifierFingerprint: peer.FingerprintHex,
            verifierRole: "receiver",
            signerRole: "sender",
            channelBinding,
            capabilityHash);

        await channel.WriteEnvelopeAsync(
            CreateResponse(
                sequence,
                sessionId,
                reciprocalChallenge,
                signerRole: "sender",
                verifierRole: "receiver",
                verifierFingerprint:
                    peer.FingerprintHex,
                channelBinding,
                capabilityHash,
                trustManager),
            cancellationToken);

        return authenticated;
    }

    public static async Task<DesktopAuthenticatedPeer> AuthenticateReceiverAsync(
        LanRecordChannel channel,
        DesktopControlSequence sequence,
        TlsSocketConnection connection,
        SessionId sessionId,
        IReadOnlyCollection<string> negotiatedCapabilities,
        string peerDisplayName,
        DesktopPersistentTrustManager trustManager,
        string? expectedPeerFingerprintHex,
        IDesktopTransferInteraction interaction,
        bool localEndpointHostsTls = true,
        CancellationToken cancellationToken = default)
    {
        RequireIdentityCapability(negotiatedCapabilities);
        string channelBinding =
            RequireChannelBinding(
                localEndpointHostsTls
                    ? connection.LocalSpkiSha256
                    : connection.RemoteSpkiSha256);
        string localFingerprint =
            trustManager.LocalFingerprintHex();
        RequireExpectedPeerIsEnrolled(
            trustManager,
            expectedPeerFingerprintHex);

        string capabilityHash =
            IdentityResumeTranscript.CapabilityHashHex(
                negotiatedCapabilities);

        Envelope peerChallenge =
            await sequence.ReadExpectedAsync(
                channel,
                MessageTypes.IdentityChallenge,
                sessionId,
                cancellationToken);

        string claimedPeerFingerprint =
            RequireChallengeField(
                peerChallenge,
                "verifierFingerprintHex");

        if (expectedPeerFingerprintHex != null &&
            !string.Equals(
                claimedPeerFingerprint,
                expectedPeerFingerprintHex,
                StringComparison.Ordinal))
        {
            throw AuthenticationFailure(
                "Trusted peer identity was downgraded or substituted before proof.");
        }

        ValidateChallenge(
            peerChallenge,
            expectedVerifierFingerprint:
                claimedPeerFingerprint,
            verifierRole: "sender",
            signerRole: "receiver",
            channelBinding,
            capabilityHash);

        await channel.WriteEnvelopeAsync(
            CreateResponse(
                sequence,
                sessionId,
                peerChallenge,
                signerRole: "receiver",
                verifierRole: "sender",
                verifierFingerprint:
                    claimedPeerFingerprint,
                channelBinding,
                capabilityHash,
                trustManager),
            cancellationToken);

        string challengeNonce =
            Convert.ToHexStringLower(
                RandomNumberGenerator.GetBytes(32));
        Envelope localChallenge =
            sequence.Create(
                MessageTypes.IdentityChallenge,
                sessionId,
                CreateChallengeBody(
                    challengeNonce,
                    localFingerprint,
                    verifierRole: "receiver",
                    signerRole: "sender",
                    channelBinding,
                    capabilityHash));

        await channel.WriteEnvelopeAsync(
            localChallenge,
            cancellationToken);

        Envelope responseEnvelope =
            await sequence.ReadExpectedAsync(
                channel,
                MessageTypes.IdentityResponse,
                sessionId,
                cancellationToken);

        string expectedSigner =
            expectedPeerFingerprintHex
                ?? claimedPeerFingerprint;
        VerifiedDesktopPeerIdentity peer =
            VerifyResponse(
                responseEnvelope,
                sessionId,
                localChallenge,
                signerRole: "sender",
                verifierRole: "receiver",
                verifierFingerprint: localFingerprint,
                channelBinding,
                negotiatedCapabilities,
                expectedSigner,
                peerDisplayName,
                connection,
                localEndpointHostsTls);

        if (!string.Equals(
                peer.FingerprintHex,
                claimedPeerFingerprint,
                StringComparison.Ordinal))
        {
            throw AuthenticationFailure(
                "The peer identity that issued the first challenge differs from the identity that answered the reciprocal challenge.");
        }

        return await FinalizeVerifiedPeerAsync(
            trustManager,
            peer,
            expectedPeerFingerprintHex,
            interaction,
            cancellationToken);
    }

    private static JsonObject CreateChallengeBody(
        string nonce,
        string verifierFingerprint,
        string verifierRole,
        string signerRole,
        string channelBinding,
        string capabilityHash) =>
        new()
        {
            ["profile"] =
                IdentityResumeTranscript.IdentityProfile,
            ["challengeNonceHex"] = nonce,
            ["verifierFingerprintHex"] =
                verifierFingerprint,
            ["verifierRole"] = verifierRole,
            ["signerRole"] = signerRole,
            ["channelBindingHex"] =
                channelBinding,
            ["capabilityHashHex"] =
                capabilityHash
        };

    private static Envelope CreateResponse(
        DesktopControlSequence sequence,
        SessionId sessionId,
        Envelope challenge,
        string signerRole,
        string verifierRole,
        string verifierFingerprint,
        string channelBinding,
        string capabilityHash,
        DesktopPersistentTrustManager trustManager)
    {
        string challengeNonce =
            RequireChallengeField(
                challenge,
                "challengeNonceHex");
        string signerFingerprint =
            trustManager.LocalFingerprintHex();

        byte[] transcript =
            IdentityResumeTranscript.IdentityProofBytes(
                sessionId.Value,
                challenge.MessageId.Value,
                signerRole,
                verifierRole,
                signerFingerprint,
                verifierFingerprint,
                challengeNonce,
                channelBinding,
                capabilityHash);
        byte[] signature =
            trustManager.SignTranscript(
                transcript);

        return sequence.Create(
            MessageTypes.IdentityResponse,
            sessionId,
            new JsonObject
            {
                ["profile"] =
                    IdentityResumeTranscript
                        .IdentityProfile,
                ["challengeMessageId"] =
                    challenge.MessageId.Value,
                ["challengeNonceHex"] =
                    challengeNonce,
                ["signerFingerprintHex"] =
                    signerFingerprint,
                ["publicKeyDerHex"] =
                    Convert.ToHexStringLower(
                        trustManager
                            .LocalPublicKeyDer()),
                ["signatureHex"] =
                    Convert.ToHexStringLower(
                        signature)
            });
    }

    private static VerifiedDesktopPeerIdentity VerifyResponse(
        Envelope response,
        SessionId sessionId,
        Envelope challenge,
        string signerRole,
        string verifierRole,
        string verifierFingerprint,
        string channelBinding,
        IReadOnlyCollection<string> negotiatedCapabilities,
        string? expectedSignerFingerprint,
        string displayName,
        TlsSocketConnection connection,
        bool localEndpointHostsTls)
    {
        string challengeNonce =
            RequireChallengeField(
                challenge,
                "challengeNonceHex");

        VerifiedDesktopPeerIdentity verified = DesktopIdentityProofVerifier.Verify(
            new DesktopIdentityProofContext(
                sessionId.Value,
                challenge.MessageId.Value,
                signerRole,
                verifierRole,
                verifierFingerprint,
                challengeNonce,
                channelBinding,
                negotiatedCapabilities,
                expectedSignerFingerprint),
            response.Body["profile"]?.ToString()
                ?? string.Empty,
            response.Body["challengeMessageId"]?.ToString()
                ?? string.Empty,
            response.Body["challengeNonceHex"]?.ToString()
                ?? string.Empty,
            response.Body["signerFingerprintHex"]?.ToString()
                ?? string.Empty,
            response.Body["publicKeyDerHex"]?.ToString()
                ?? string.Empty,
            response.Body["signatureHex"]?.ToString()
                ?? string.Empty,
            displayName);

        if (connection.TcpClient.Client.RemoteEndPoint is System.Net.IPEndPoint remoteEp)
        {
            string host = remoteEp.Address.ToString();
            int port = localEndpointHostsTls
                ? Glide.Transport.Discovery.LanDiscoveryConstants.DefaultPort
                : remoteEp.Port;
            return new VerifiedDesktopPeerIdentity(
                verified.FingerprintHex,
                verified.PublicKeyDerHex,
                verified.DisplayName,
                verified.Platform,
                lastKnownHost: host,
                lastKnownPort: port);
        }

        return verified;
    }

    private static void ValidateChallenge(
        Envelope challenge,
        string expectedVerifierFingerprint,
        string verifierRole,
        string signerRole,
        string channelBinding,
        string capabilityHash)
    {
        RequireExact(
            challenge,
            "profile",
            IdentityResumeTranscript.IdentityProfile);
        RequireExact(
            challenge,
            "verifierFingerprintHex",
            expectedVerifierFingerprint);
        RequireExact(
            challenge,
            "verifierRole",
            verifierRole);
        RequireExact(
            challenge,
            "signerRole",
            signerRole);
        RequireExact(
            challenge,
            "channelBindingHex",
            channelBinding);
        RequireExact(
            challenge,
            "capabilityHashHex",
            capabilityHash);
    }

    private static async Task<DesktopAuthenticatedPeer>
        FinalizeVerifiedPeerAsync(
            DesktopPersistentTrustManager trustManager,
            VerifiedDesktopPeerIdentity peer,
            string? expectedPeerFingerprintHex,
            IDesktopTransferInteraction interaction,
            CancellationToken cancellationToken)
    {
        DesktopTrustedPeer? existing =
            trustManager.GetTrustedPeer(
                peer.FingerprintHex);

        if (expectedPeerFingerprintHex != null)
        {
            if (existing == null)
            {
                throw AuthenticationFailure(
                    "Expected trusted peer has been revoked or is no longer enrolled.");
            }

            if (!string.Equals(
                    existing.PublicKeyDerHex,
                    peer.PublicKeyDerHex,
                    StringComparison.Ordinal))
            {
                throw AuthenticationFailure(
                    "Enrolled peer public key changed.");
            }

            trustManager.EnrollVerifiedPeer(
                peer,
                userApproved: true);
            return new DesktopAuthenticatedPeer(
                peer.FingerprintHex,
                IsEnrolled: true);
        }

        if (existing != null)
        {
            if (!string.Equals(
                    existing.PublicKeyDerHex,
                    peer.PublicKeyDerHex,
                    StringComparison.Ordinal))
            {
                throw AuthenticationFailure(
                    "Enrolled peer public key changed.");
            }

            trustManager.EnrollVerifiedPeer(
                peer,
                userApproved: true);
            return new DesktopAuthenticatedPeer(
                peer.FingerprintHex,
                IsEnrolled: true);
        }

        bool approved =
            await interaction
                .ConfirmPeerEnrollmentAsync(
                    peer,
                    cancellationToken);
        if (approved)
        {
            trustManager.EnrollVerifiedPeer(
                peer,
                userApproved: true);
        }

        return new DesktopAuthenticatedPeer(
            peer.FingerprintHex,
            approved);
    }

    private static void RequireExpectedPeerIsEnrolled(
        DesktopPersistentTrustManager trustManager,
        string? expectedPeerFingerprintHex)
    {
        if (expectedPeerFingerprintHex == null)
        {
            return;
        }

        DesktopTrustedPeer? expected =
            trustManager.GetTrustedPeer(
                expectedPeerFingerprintHex);
        if (expected == null)
        {
            throw AuthenticationFailure(
                "Expected trusted peer is not enrolled or has been revoked.");
        }
    }

    private static void RequireIdentityCapability(
        IReadOnlyCollection<string> negotiatedCapabilities)
    {
        if (!negotiatedCapabilities.Contains(
                ProtocolCapabilities.IdentityV1,
                StringComparer.Ordinal))
        {
            throw AuthenticationFailure(
                "identity-v1 was required but was not negotiated.");
        }
    }

    private static string RequireChannelBinding(
        string? binding)
    {
        if (binding == null ||
            binding.Length != 64 ||
            binding.Any(
                static ch =>
                    !((ch >= '0' && ch <= '9') ||
                      (ch >= 'a' && ch <= 'f'))) ||
            binding.All(static ch => ch == '0'))
        {
            throw AuthenticationFailure(
                "TLS server SPKI channel binding is unavailable or invalid.");
        }

        return binding;
    }

    private static string RequireChallengeField(
        Envelope challenge,
        string field) =>
        challenge.Body[field]?.ToString()
        ?? throw AuthenticationFailure(
            $"Identity challenge is missing '{field}'.");

    private static void RequireExact(
        Envelope challenge,
        string field,
        string expected)
    {
        string actual =
            RequireChallengeField(
                challenge,
                field);
        if (!string.Equals(
                actual,
                expected,
                StringComparison.Ordinal))
        {
            throw AuthenticationFailure(
                $"Identity challenge '{field}' does not match the active trusted-session context.");
        }
    }

    private static ProtocolViolationException
        AuthenticationFailure(
            string message) =>
        new(
            WireErrorCodes.AuthenticationFailed,
            message);
}
