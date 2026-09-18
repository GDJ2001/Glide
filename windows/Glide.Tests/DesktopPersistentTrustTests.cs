using System.Security.Cryptography;
using Glide.Core.Models;
using Glide.Core.Security;

namespace Glide.Tests;

public sealed class DesktopPersistentTrustTests : IDisposable
{
    private readonly string _root;

    public DesktopPersistentTrustTests()
    {
        _root = Path.Combine(
            Path.GetTempPath(),
            "GlideTrustTests",
            Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_root);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_root))
            {
                Directory.Delete(
                    _root,
                    recursive: true);
            }
        }
        catch
        {
            // Test cleanup must not hide the assertion result.
        }
    }

    [Fact]
    public void Initialize_RestoredTrustWithoutIdentity_RevokesAll()
    {
        string path =
            Path.Combine(
                _root,
                "trust-v1.json");
        var store =
            new DesktopTrustStore(path);
        store.Save(
            new DesktopTrustDocument
            {
                LocalIdentityFingerprintHex =
                    new string('a', 64),
                Peers =
                [
                    SamplePeer()
                ],
                HeadlessPolicies =
                [
                    new HeadlessReceivePolicy(
                        SamplePeer().FingerprintHex,
                        Path.GetFullPath(
                            Path.Combine(
                                _root,
                                "received")),
                        1024,
                        true)
                ]
            });

        var identity =
            new FakeDesktopIdentityStore(
                initialIdentity: false);
        var manager =
            new DesktopPersistentTrustManager(
                identity,
                store,
                nowUnixMs: () => 5_000L);

        DesktopTrustInitialization result =
            manager.Initialize();

        Assert.True(
            result.ResetAfterIdentityLossOrChange);
        Assert.Equal(
            1,
            result.RevokedPeerCount);
        Assert.Empty(
            manager.GetTrustedPeers());

        DesktopTrustDocument persisted =
            store.Load();
        Assert.Empty(
            persisted.HeadlessPolicies);
        Assert.Equal(
            identity.GetFingerprintHex(),
            persisted.LocalIdentityFingerprintHex);
    }

    [Fact]
    public void VerifiedEnrollment_AndHeadlessPolicy_ArePeerSpecific()
    {
        string path =
            Path.Combine(
                _root,
                "trust-v1.json");
        var identity =
            new FakeDesktopIdentityStore(
                initialIdentity: true);
        var store =
            new DesktopTrustStore(path);
        var manager =
            new DesktopPersistentTrustManager(
                identity,
                store,
                nowUnixMs: () => 10_000L);
        manager.Initialize();

        VerifiedDesktopPeerIdentity verified =
            CreateVerifiedPeer(
                identity.GetFingerprintHex());

        DesktopTrustedPeer? enrolled =
            manager.EnrollVerifiedPeer(
                verified,
                userApproved: true);

        Assert.NotNull(enrolled);
        Assert.Single(
            manager.GetTrustedPeers());

        string destination =
            Path.GetFullPath(
                Path.Combine(
                    _root,
                    "downloads"));
        HeadlessReceivePolicy policy =
            manager.SetHeadlessPolicy(
                verified.FingerprintHex,
                destination,
                maxOfferBytes: 4096);

        Assert.True(policy.Enabled);
        Assert.True(
            manager.AuthorizeHeadlessOffer(
                verified.FingerprintHex,
                totalBytes: 4096,
                destination));
        Assert.False(
            manager.AuthorizeHeadlessOffer(
                authenticatedPeerFingerprintHex: null,
                totalBytes: 1,
                destination));
        Assert.False(
            manager.AuthorizeHeadlessOffer(
                new string('b', 64),
                totalBytes: 1,
                destination));
        Assert.False(
            manager.AuthorizeHeadlessOffer(
                verified.FingerprintHex,
                totalBytes: 4097,
                destination));
        Assert.False(
            manager.AuthorizeHeadlessOffer(
                verified.FingerprintHex,
                totalBytes: 1,
                Path.Combine(
                    _root,
                    "other")));

        Assert.True(
            manager.RevokePeer(
                verified.FingerprintHex));
        Assert.Empty(
            manager.GetTrustedPeers());
        Assert.Null(
            manager.GetHeadlessPolicy(
                verified.FingerprintHex));
    }

    [Fact]
    public void HeadlessPolicy_CannotReferenceUnenrolledPeer()
    {
        var manager =
            new DesktopPersistentTrustManager(
                new FakeDesktopIdentityStore(true),
                new DesktopTrustStore(
                    Path.Combine(
                        _root,
                        "trust-v1.json")));
        manager.Initialize();

        Assert.Throws<InvalidOperationException>(
            () =>
                manager.SetHeadlessPolicy(
                    new string('c', 64),
                    Path.GetFullPath(
                        Path.Combine(
                            _root,
                            "dest")),
                    maxOfferBytes: 1024));
    }

    [Fact]
    public void IdentityProof_WrongChannelBinding_IsRejected()
    {
        var local =
            new FakeDesktopIdentityStore(true);
        VerifiedDesktopPeerIdentity verified =
            CreateVerifiedPeer(
                local.GetFingerprintHex());

        Assert.NotNull(verified);

        // Create a second proof and try to verify it under a different active channel.
        using ECDsa signer =
            ECDsa.Create(
                ECCurve.NamedCurves.nistP256);
        byte[] publicDer =
            signer.ExportSubjectPublicKeyInfo();
        string signerFingerprint =
            Convert.ToHexStringLower(
                SHA256.HashData(publicDer));
        IReadOnlyCollection<string> capabilities =
        [
            ProtocolCapabilities.StreamV1,
            ProtocolCapabilities.Sha256,
            ProtocolCapabilities.FolderV1,
            ProtocolCapabilities.IdentityV1
        ];
        var context =
            new DesktopIdentityProofContext(
                "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222",
                "sender",
                "receiver",
                local.GetFingerprintHex(),
                "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
                "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100",
                capabilities);
        byte[] transcript =
            IdentityResumeTranscript.IdentityProofBytes(
                context.ActiveSessionId,
                context.ChallengeMessageId,
                context.SignerRole,
                context.VerifierRole,
                signerFingerprint,
                context.VerifierFingerprintHex,
                context.ChallengeNonceHex,
                context.ChannelBindingHex,
                IdentityResumeTranscript.CapabilityHashHex(
                    capabilities));
        byte[] signature =
            signer.SignData(
                transcript,
                HashAlgorithmName.SHA256,
                DSASignatureFormat.Rfc3279DerSequence);

        var wrongChannel =
            context with
            {
                ChannelBindingHex =
                    new string('a', 64)
            };

        Assert.Throws<CryptographicException>(
            () =>
                DesktopIdentityProofVerifier.Verify(
                    wrongChannel,
                    IdentityResumeTranscript.IdentityProfile,
                    context.ChallengeMessageId,
                    context.ChallengeNonceHex,
                    signerFingerprint,
                    Convert.ToHexStringLower(publicDer),
                    Convert.ToHexStringLower(signature),
                    "Peer"));
    }

    [Fact]
    public void RotateIdentity_RevokesPeersAndPolicies()
    {
        var identity =
            new FakeDesktopIdentityStore(true);
        string original =
            identity.GetFingerprintHex();
        var store =
            new DesktopTrustStore(
                Path.Combine(
                    _root,
                    "trust-v1.json"));
        var manager =
            new DesktopPersistentTrustManager(
                identity,
                store);
        manager.Initialize();

        VerifiedDesktopPeerIdentity verified =
            CreateVerifiedPeer(
                identity.GetFingerprintHex());
        manager.EnrollVerifiedPeer(
            verified,
            userApproved: true);
        manager.SetHeadlessPolicy(
            verified.FingerprintHex,
            Path.GetFullPath(
                Path.Combine(
                    _root,
                    "dest")),
            2048);

        DesktopTrustInitialization result =
            manager.RotateLocalIdentity();

        Assert.Equal(
            1,
            result.RevokedPeerCount);
        Assert.NotEqual(
            original,
            result.LocalFingerprintHex);
        Assert.Empty(
            manager.GetTrustedPeers());
        Assert.Empty(
            store.Load().HeadlessPolicies);
    }

    [Fact]
    public void PlatformIdentityStore_SignsAndPersists_WithProtectedStorage()
    {
        if (!OperatingSystem.IsWindows() &&
            !OperatingSystem.IsLinux())
        {
            return;
        }

        string linuxPath =
            Path.Combine(
                _root,
                "identity",
                "identity-v1.pk8");
        string windowsName =
            "Glide.Test.Identity." +
            Guid.NewGuid().ToString("N");

        var identity =
            new DesktopPersistentIdentityStore(
                linuxKeyPath: linuxPath,
                windowsKeyName: windowsName);

        try
        {
            Assert.False(
                identity.HasIdentity());

            byte[] publicDer =
                identity.GetOrCreatePublicKeyDer();
            string fingerprint =
                identity.GetFingerprintHex();
            byte[] transcript =
                "GLIDE-SIGN-V1-test"
                    .Select(
                        static ch => (byte)ch)
                    .ToArray();
            byte[] signature =
                identity.SignTranscript(
                    transcript);

            Assert.True(
                identity.HasIdentity());
            Assert.Equal(
                64,
                fingerprint.Length);

            using ECDsa publicKey =
                ECDsa.Create();
            publicKey.ImportSubjectPublicKeyInfo(
                publicDer,
                out int bytesRead);
            Assert.Equal(
                publicDer.Length,
                bytesRead);
            Assert.True(
                publicKey.VerifyData(
                    transcript,
                    signature,
                    HashAlgorithmName.SHA256,
                    DSASignatureFormat.Rfc3279DerSequence));

            if (OperatingSystem.IsLinux())
            {
                UnixFileMode mode =
                    File.GetUnixFileMode(
                        linuxPath);
                UnixFileMode forbidden =
                    UnixFileMode.GroupRead |
                    UnixFileMode.GroupWrite |
                    UnixFileMode.GroupExecute |
                    UnixFileMode.OtherRead |
                    UnixFileMode.OtherWrite |
                    UnixFileMode.OtherExecute;
                Assert.Equal(
                    (UnixFileMode)0,
                    mode & forbidden);
            }
            else if (OperatingSystem.IsWindows())
            {
                using CngKey key =
                    CngKey.Open(
                        windowsName,
                        CngProvider.MicrosoftSoftwareKeyStorageProvider,
                        CngKeyOpenOptions.None);
                Assert.Equal(
                    CngExportPolicies.None,
                    key.ExportPolicy);
                Assert.ThrowsAny<CryptographicException>(
                    () =>
                        key.Export(
                            CngKeyBlobFormat.EccPrivateBlob));
            }
        }
        finally
        {
            identity.DeleteIdentity();
        }

        Assert.False(
            identity.HasIdentity());
    }

    [Fact]
    public void TrustStore_OnLinux_IsOwnerOnly()
    {
        if (!OperatingSystem.IsLinux())
        {
            return;
        }

        string path =
            Path.Combine(
                _root,
                "private",
                "trust-v1.json");
        var store =
            new DesktopTrustStore(path);
        store.Save(
            new DesktopTrustDocument());

        UnixFileMode mode =
            File.GetUnixFileMode(path);
        UnixFileMode forbidden =
            UnixFileMode.GroupRead |
            UnixFileMode.GroupWrite |
            UnixFileMode.GroupExecute |
            UnixFileMode.OtherRead |
            UnixFileMode.OtherWrite |
            UnixFileMode.OtherExecute;

        Assert.Equal(
            (UnixFileMode)0,
            mode & forbidden);
    }

    [Fact]
    public void LinuxIdentityStore_RejectsGroupReadableExistingKey()
    {
        if (!OperatingSystem.IsLinux())
        {
            return;
        }

        string keyPath =
            Path.Combine(
                _root,
                "identity-permissions",
                "identity-v1.pk8");
        var identity =
            new DesktopPersistentIdentityStore(
                linuxKeyPath: keyPath,
                windowsKeyName:
                    "unused-" + Guid.NewGuid().ToString("N"));

        identity.GetOrCreatePublicKeyDer();
        File.SetUnixFileMode(
            keyPath,
            UnixFileMode.UserRead |
            UnixFileMode.UserWrite |
            UnixFileMode.GroupRead);

        Assert.Throws<UnauthorizedAccessException>(
            () =>
                identity.GetOrCreatePublicKeyDer());

        File.SetUnixFileMode(
            keyPath,
            UnixFileMode.UserRead |
            UnixFileMode.UserWrite);
        identity.DeleteIdentity();
    }

    [Fact]
    public void LinuxTrustStore_RejectsGroupReadableDatabase()
    {
        if (!OperatingSystem.IsLinux())
        {
            return;
        }

        string path =
            Path.Combine(
                _root,
                "trust-permissions",
                "trust-v1.json");
        var store =
            new DesktopTrustStore(path);
        store.Save(
            new DesktopTrustDocument());

        File.SetUnixFileMode(
            path,
            UnixFileMode.UserRead |
            UnixFileMode.UserWrite |
            UnixFileMode.GroupRead);

        Assert.Throws<UnauthorizedAccessException>(
            () => store.Load());
    }

    [Fact]
    public void TrustStore_UnknownVersion_FailsClosed()
    {
        string path =
            Path.Combine(
                _root,
                "unknown-version",
                "trust-v1.json");
        Directory.CreateDirectory(
            Path.GetDirectoryName(path)!);

        File.WriteAllText(
            path,
            """
            {
              "version": 999,
              "localIdentityFingerprintHex": null,
              "peers": [],
              "headlessPolicies": []
            }
            """);

        if (OperatingSystem.IsLinux())
        {
            File.SetUnixFileMode(
                path,
                UnixFileMode.UserRead |
                UnixFileMode.UserWrite);
        }

        var store =
            new DesktopTrustStore(path);

        Assert.Throws<InvalidDataException>(
            () => store.Load());
    }

    [Fact]
    public void TrustStore_MissingVersionedFields_FailsClosed()
    {
        string path =
            Path.Combine(
                _root,
                "legacy-unversioned",
                "trust-v1.json");
        Directory.CreateDirectory(
            Path.GetDirectoryName(path)!);

        File.WriteAllText(
            path,
            """
            {
              "localIdentityFingerprintHex": null,
              "peers": [],
              "headlessPolicies": []
            }
            """);

        if (OperatingSystem.IsLinux())
        {
            File.SetUnixFileMode(
                path,
                UnixFileMode.UserRead |
                UnixFileMode.UserWrite);
        }

        var store =
            new DesktopTrustStore(path);

        Assert.Throws<InvalidDataException>(
            () => store.Load());
    }


    private VerifiedDesktopPeerIdentity
        CreateVerifiedPeer(
            string verifierFingerprintHex)
    {
        using ECDsa signer =
            ECDsa.Create(
                ECCurve.NamedCurves.nistP256);
        byte[] publicDer =
            signer.ExportSubjectPublicKeyInfo();
        string signerFingerprint =
            Convert.ToHexStringLower(
                SHA256.HashData(publicDer));
        IReadOnlyCollection<string> capabilities =
        [
            ProtocolCapabilities.StreamV1,
            ProtocolCapabilities.Sha256,
            ProtocolCapabilities.FolderV1,
            ProtocolCapabilities.IdentityV1
        ];

        var context =
            new DesktopIdentityProofContext(
                "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222",
                "sender",
                "receiver",
                verifierFingerprintHex,
                "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
                "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100",
                capabilities);

        byte[] transcript =
            IdentityResumeTranscript.IdentityProofBytes(
                context.ActiveSessionId,
                context.ChallengeMessageId,
                context.SignerRole,
                context.VerifierRole,
                signerFingerprint,
                context.VerifierFingerprintHex,
                context.ChallengeNonceHex,
                context.ChannelBindingHex,
                IdentityResumeTranscript.CapabilityHashHex(
                    capabilities));
        byte[] signature =
            signer.SignData(
                transcript,
                HashAlgorithmName.SHA256,
                DSASignatureFormat.Rfc3279DerSequence);

        return DesktopIdentityProofVerifier.Verify(
            context,
            IdentityResumeTranscript.IdentityProfile,
            context.ChallengeMessageId,
            context.ChallengeNonceHex,
            signerFingerprint,
            Convert.ToHexStringLower(publicDer),
            Convert.ToHexStringLower(signature),
            "Verified desktop peer");
    }

    private static DesktopTrustedPeer SamplePeer() =>
        new(
            "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd",
            "3059",
            "Restored peer",
            1_000L,
            2_000L);
}

internal sealed class FakeDesktopIdentityStore(
    bool initialIdentity)
    : IDesktopPersistentIdentityStore
{
    private ECDsa? _key =
        initialIdentity
            ? ECDsa.Create(
                ECCurve.NamedCurves.nistP256)
            : null;

    public bool HasIdentity() =>
        _key != null;

    public byte[] GetOrCreatePublicKeyDer() =>
        GetOrCreate()
            .ExportSubjectPublicKeyInfo();

    public string GetFingerprintHex() =>
        Convert.ToHexStringLower(
            SHA256.HashData(
                GetOrCreatePublicKeyDer()));

    public byte[] SignTranscript(
        ReadOnlySpan<byte> transcript) =>
        GetOrCreate().SignData(
            transcript,
            HashAlgorithmName.SHA256,
            DSASignatureFormat.Rfc3279DerSequence);

    public void DeleteIdentity()
    {
        _key?.Dispose();
        _key = null;
    }

    private ECDsa GetOrCreate()
    {
        if (_key != null)
        {
            return _key;
        }

        _key =
            ECDsa.Create(
                ECCurve.NamedCurves.nistP256);
        return _key;
    }
}
