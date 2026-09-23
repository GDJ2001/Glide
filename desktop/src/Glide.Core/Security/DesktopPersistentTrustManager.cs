using System.Security.Cryptography;
using System.Text.RegularExpressions;
using Glide.Core.Models;

namespace Glide.Core.Security;

public sealed record DesktopIdentityProofContext(
    string ActiveSessionId,
    string ChallengeMessageId,
    string SignerRole,
    string VerifierRole,
    string VerifierFingerprintHex,
    string ChallengeNonceHex,
    string ChannelBindingHex,
    IReadOnlyCollection<string> NegotiatedCapabilities,
    string? ExpectedSignerFingerprintHex = null);

public sealed class VerifiedDesktopPeerIdentity
{
    internal VerifiedDesktopPeerIdentity(
        string fingerprintHex,
        string publicKeyDerHex,
        string displayName)
    {
        FingerprintHex = fingerprintHex;
        PublicKeyDerHex = publicKeyDerHex;
        DisplayName = displayName;
    }

    public string FingerprintHex { get; }
    public string PublicKeyDerHex { get; }
    public string DisplayName { get; }
}

public static class DesktopIdentityProofVerifier
{
    private static readonly Regex LowerHex =
        new(
            "^[0-9a-f]+$",
            RegexOptions.Compiled |
            RegexOptions.CultureInvariant);

    public static VerifiedDesktopPeerIdentity Verify(
        DesktopIdentityProofContext context,
        string responseProfile,
        string challengeMessageId,
        string challengeNonceHex,
        string signerFingerprintHex,
        string publicKeyDerHex,
        string signatureHex,
        string displayName)
    {
        ArgumentNullException.ThrowIfNull(context);

        if (!string.Equals(
                responseProfile,
                IdentityResumeTranscript.IdentityProfile,
                StringComparison.Ordinal))
        {
            throw new CryptographicException(
                "Unsupported persistent identity profile.");
        }

        if (!string.Equals(
                challengeMessageId,
                context.ChallengeMessageId,
                StringComparison.Ordinal) ||
            !string.Equals(
                challengeNonceHex,
                context.ChallengeNonceHex,
                StringComparison.Ordinal))
        {
            throw new CryptographicException(
                "Persistent identity response does not correlate to the active challenge.");
        }

        if (!context.NegotiatedCapabilities.Contains(
                ProtocolCapabilities.IdentityV1,
                StringComparer.Ordinal))
        {
            throw new CryptographicException(
                "identity-v1 was not negotiated for this session.");
        }

        if (string.IsNullOrWhiteSpace(displayName))
        {
            throw new CryptographicException(
                "Persistent identity display name cannot be blank.");
        }

        byte[] publicKeyDer =
            DecodeCanonicalHex(
                publicKeyDerHex,
                nameof(publicKeyDerHex));
        byte[] signature =
            DecodeCanonicalHex(
                signatureHex,
                nameof(signatureHex));

        try
        {
            string derivedFingerprint =
                Convert.ToHexStringLower(
                    SHA256.HashData(
                        publicKeyDer));

            if (!string.Equals(
                    signerFingerprintHex,
                    derivedFingerprint,
                    StringComparison.Ordinal))
            {
                throw new CryptographicException(
                    "Persistent identity fingerprint does not match the presented public key.");
            }

            if (context.ExpectedSignerFingerprintHex != null &&
                !string.Equals(
                    context.ExpectedSignerFingerprintHex,
                    derivedFingerprint,
                    StringComparison.Ordinal))
            {
                throw new CryptographicException(
                    "Presented identity does not match the enrolled peer fingerprint.");
            }

            string capabilityHash =
                IdentityResumeTranscript
                    .CapabilityHashHex(
                        context.NegotiatedCapabilities);

            byte[] transcript =
                IdentityResumeTranscript
                    .IdentityProofBytes(
                        context.ActiveSessionId,
                        context.ChallengeMessageId,
                        context.SignerRole,
                        context.VerifierRole,
                        derivedFingerprint,
                        context.VerifierFingerprintHex,
                        context.ChallengeNonceHex,
                        context.ChannelBindingHex,
                        capabilityHash);

            using ECDsa publicKey =
                ECDsa.Create();
            publicKey.ImportSubjectPublicKeyInfo(
                publicKeyDer,
                out int bytesRead);
            if (bytesRead != publicKeyDer.Length)
            {
                throw new CryptographicException(
                    "Persistent identity public key contains trailing data.");
            }

            if (!publicKey.VerifyData(
                    transcript,
                    signature,
                    HashAlgorithmName.SHA256,
                    DSASignatureFormat.Rfc3279DerSequence))
            {
                throw new CryptographicException(
                    "Persistent identity signature verification failed.");
            }

            return new VerifiedDesktopPeerIdentity(
                derivedFingerprint,
                publicKeyDerHex,
                displayName);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(
                signature);
        }
    }

    private static byte[] DecodeCanonicalHex(
        string value,
        string name)
    {
        if (string.IsNullOrEmpty(value) ||
            value.Length % 2 != 0 ||
            !LowerHex.IsMatch(value))
        {
            throw new CryptographicException(
                $"{name} must be non-empty even-length lowercase hexadecimal.");
        }

        return Convert.FromHexString(value);
    }
}

public sealed record DesktopTrustInitialization(
    string LocalFingerprintHex,
    int RevokedPeerCount,
    bool ResetAfterIdentityLossOrChange);

public sealed class DesktopPersistentTrustManager
{
    private readonly IDesktopPersistentIdentityStore _identity;
    private readonly DesktopTrustStore _store;
    private readonly Func<long> _nowUnixMs;

    public DesktopPersistentTrustManager(
        IDesktopPersistentIdentityStore identity,
        DesktopTrustStore store,
        Func<long>? nowUnixMs = null)
    {
        _identity =
            identity ??
            throw new ArgumentNullException(
                nameof(identity));
        _store =
            store ??
            throw new ArgumentNullException(
                nameof(store));
        _nowUnixMs =
            nowUnixMs ??
            (() => DateTimeOffset.UtcNow
                .ToUnixTimeMilliseconds());
    }

    public DesktopTrustInitialization Initialize()
    {
        DesktopTrustDocument document =
            _store.Load();
        bool hadIdentity =
            _identity.HasIdentity();
        string fingerprint =
            _identity.GetFingerprintHex();

        bool fingerprintChanged =
            document.LocalIdentityFingerprintHex != null &&
            !string.Equals(
                document.LocalIdentityFingerprintHex,
                fingerprint,
                StringComparison.Ordinal);

        bool restoreWithoutKey =
            !hadIdentity &&
            (document.Peers.Count > 0 ||
             document.HeadlessPolicies.Count > 0);

        int revoked = 0;
        if (fingerprintChanged ||
            restoreWithoutKey)
        {
            revoked =
                document.Peers.Count;
            document.Peers.Clear();
            document.HeadlessPolicies.Clear();
        }

        document.LocalIdentityFingerprintHex =
            fingerprint;
        _store.Save(document);

        return new DesktopTrustInitialization(
            fingerprint,
            revoked,
            fingerprintChanged ||
                restoreWithoutKey);
    }

    public string LocalFingerprintHex() =>
        _identity.GetFingerprintHex();

    public byte[] LocalPublicKeyDer() =>
        _identity.GetOrCreatePublicKeyDer();

    public byte[] SignTranscript(
        ReadOnlySpan<byte> transcript) =>
        _identity.SignTranscript(
            transcript);

    public IReadOnlyList<DesktopTrustedPeer>
        GetTrustedPeers() =>
        _store.Load().Peers;

    public DesktopTrustedPeer? GetTrustedPeer(
        string fingerprintHex) =>
        _store.Load().Peers.FirstOrDefault(
            peer =>
                string.Equals(
                    peer.FingerprintHex,
                    fingerprintHex,
                    StringComparison.Ordinal));

    public DesktopTrustedPeer? EnrollVerifiedPeer(
        VerifiedDesktopPeerIdentity peer,
        bool userApproved)
    {
        ArgumentNullException.ThrowIfNull(peer);
        if (!userApproved)
        {
            return null;
        }

        DesktopTrustDocument document =
            _store.Load();
        DesktopTrustedPeer? existing =
            document.Peers.FirstOrDefault(
                candidate =>
                    string.Equals(
                        candidate.FingerprintHex,
                        peer.FingerprintHex,
                        StringComparison.Ordinal));

        long now = _nowUnixMs();
        DesktopTrustedPeer record;
        if (existing != null)
        {
            if (!string.Equals(
                    existing.PublicKeyDerHex,
                    peer.PublicKeyDerHex,
                    StringComparison.Ordinal))
            {
                throw new CryptographicException(
                    "Stored key material changed for an enrolled peer fingerprint.");
            }

            record =
                existing with
                {
                    DisplayName =
                        peer.DisplayName,
                    LastSeenAtUnixMs =
                        Math.Max(
                            existing.LastSeenAtUnixMs,
                            now)
                };
            int index =
                document.Peers.FindIndex(
                    item =>
                        item.FingerprintHex ==
                            existing.FingerprintHex);
            document.Peers[index] =
                record;
        }
        else
        {
            record =
                new DesktopTrustedPeer(
                    peer.FingerprintHex,
                    peer.PublicKeyDerHex,
                    peer.DisplayName,
                    now,
                    now);
            record.Validate();
            document.Peers.Add(
                record);
        }

        _store.Save(document);
        return record;
    }

    public bool RevokePeer(
        string fingerprintHex)
    {
        DesktopTrustDocument document =
            _store.Load();
        int removed =
            document.Peers.RemoveAll(
                peer =>
                    string.Equals(
                        peer.FingerprintHex,
                        fingerprintHex,
                        StringComparison.Ordinal));
        document.HeadlessPolicies.RemoveAll(
            policy =>
                string.Equals(
                    policy.PeerFingerprintHex,
                    fingerprintHex,
                    StringComparison.Ordinal));

        if (removed == 0)
        {
            return false;
        }

        _store.Save(document);
        return true;
    }

    public DesktopTrustInitialization RotateLocalIdentity()
    {
        DesktopTrustDocument document =
            _store.Load();
        int revoked =
            document.Peers.Count;

        document.Peers.Clear();
        document.HeadlessPolicies.Clear();
        _identity.DeleteIdentity();
        string fingerprint =
            _identity.GetFingerprintHex();
        document.LocalIdentityFingerprintHex =
            fingerprint;
        _store.Save(document);

        return new DesktopTrustInitialization(
            fingerprint,
            revoked,
            true);
    }

    public HeadlessReceivePolicy SetHeadlessPolicy(
        string peerFingerprintHex,
        string destinationRoot,
        long maxOfferBytes,
        bool enabled = true)
    {
        DesktopTrustDocument document =
            _store.Load();
        if (!document.Peers.Any(
                peer =>
                    string.Equals(
                        peer.FingerprintHex,
                        peerFingerprintHex,
                        StringComparison.Ordinal)))
        {
            throw new InvalidOperationException(
                "Headless policy requires an already-enrolled trusted peer.");
        }

        var policy =
            new HeadlessReceivePolicy(
                peerFingerprintHex,
                Path.GetFullPath(
                    destinationRoot),
                maxOfferBytes,
                enabled);
        policy.Validate();

        document.HeadlessPolicies.RemoveAll(
            existing =>
                existing.PeerFingerprintHex ==
                    peerFingerprintHex);
        document.HeadlessPolicies.Add(
            policy);
        _store.Save(document);
        return policy;
    }

    public bool RemoveHeadlessPolicy(
        string peerFingerprintHex)
    {
        DesktopTrustDocument document =
            _store.Load();
        int removed =
            document.HeadlessPolicies.RemoveAll(
                policy =>
                    policy.PeerFingerprintHex ==
                        peerFingerprintHex);
        if (removed == 0)
        {
            return false;
        }

        _store.Save(document);
        return true;
    }

    public HeadlessReceivePolicy? GetHeadlessPolicy(
        string peerFingerprintHex) =>
        _store.Load()
            .HeadlessPolicies
            .FirstOrDefault(
                policy =>
                    policy.Enabled &&
                    string.Equals(
                        policy.PeerFingerprintHex,
                        peerFingerprintHex,
                        StringComparison.Ordinal));

    public bool AuthorizeHeadlessOffer(
        string? authenticatedPeerFingerprintHex,
        long totalBytes,
        string destinationRoot)
    {
        if (string.IsNullOrWhiteSpace(
                authenticatedPeerFingerprintHex))
        {
            return false;
        }

        HeadlessReceivePolicy? policy =
            GetHeadlessPolicy(
                authenticatedPeerFingerprintHex);
        if (policy == null ||
            totalBytes < 0 ||
            totalBytes > policy.MaxOfferBytes)
        {
            return false;
        }

        string requestedRoot =
            NormalizePath(destinationRoot);
        string authorizedRoot =
            NormalizePath(policy.DestinationRoot);

        return string.Equals(
            requestedRoot,
            authorizedRoot,
            OperatingSystem.IsWindows()
                ? StringComparison.OrdinalIgnoreCase
                : StringComparison.Ordinal);
    }

    private static string NormalizePath(
        string value) =>
        Path.TrimEndingDirectorySeparator(
            Path.GetFullPath(value));
}
