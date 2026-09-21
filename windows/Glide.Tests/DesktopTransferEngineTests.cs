using System.Security.Cryptography;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Desktop;
using Glide.Transport.Sockets;

namespace Glide.Tests;

public sealed class DesktopTransferEngineTests : IDisposable
{
    private readonly string _root;
    private readonly string _sourceDir;
    private readonly string _destinationDir;
    private readonly string _stagingDir;

    public DesktopTransferEngineTests()
    {
        _root = Path.Combine(
            Path.GetTempPath(),
            "glide-hard011-" + Guid.NewGuid().ToString("N"));
        _sourceDir = Path.Combine(_root, "source");
        _destinationDir = Path.Combine(_root, "destination");
        _stagingDir = Path.Combine(_root, "staging");
        Directory.CreateDirectory(_sourceDir);
        Directory.CreateDirectory(_destinationDir);
        Directory.CreateDirectory(_stagingDir);
    }

    [Fact]
    public async Task SharedEngine_QrPairedSendAndReceive_UsesSameSessionImplementation()
    {
        string firstPath = Path.Combine(_sourceDir, "alpha.bin");
        string secondPath = Path.Combine(_sourceDir, "beta.bin");
        byte[] firstPayload = Enumerable.Range(0, 32_768)
            .Select(i => (byte)(i % 251))
            .ToArray();
        byte[] secondPayload = Enumerable.Range(0, 8_193)
            .Select(i => (byte)(255 - (i % 251)))
            .ToArray();

        await File.WriteAllBytesAsync(firstPath, firstPayload);
        await File.WriteAllBytesAsync(secondPath, secondPayload);

        using var listener = new TlsSocketListener(0);
        var invitationAuthority = new LanInvitationAuthority();
        var invitation = invitationAuthority.Publish(CreateInvitation(listener));
        var receiverStore = new QuarantineStagingStore(
            stagingRoot: _stagingDir,
            destinationRoot: _destinationDir);

        var receiverInteraction = new RecordingInteraction();
        var senderInteraction = new RecordingInteraction();
        var receiverEngine = new DesktopTransferEngine();
        var senderEngine = new DesktopTransferEngine();

        var receiverTask = Task.Run(async () =>
        {
            await using var connection =
                await listener.AcceptConnectionAsync();
            return await receiverEngine.ReceiveAsync(
                connection,
                new DesktopReceiveRequest(
                    receiverStore,
                    invitationAuthority,
                    LocalDeviceName: "Receiver-Desktop"),
                receiverInteraction);
        });

        var senderResult = await senderEngine.SendAsync(
            new DesktopSendRequest(
                "127.0.0.1",
                listener.BoundPort,
                new[]
                {
                    new DesktopTransferFile(firstPath),
                    new DesktopTransferFile(secondPath)
                },
                invitation,
                LocalDeviceName: "Sender-Desktop"),
            senderInteraction);

        var receiverResult = await receiverTask.WaitAsync(
            TimeSpan.FromSeconds(15));

        Assert.True(senderResult.Success, $"Sender: {senderResult.Message} | Receiver: {receiverResult.Message}");
        Assert.True(receiverResult.Success, $"Receiver: {receiverResult.Message} | Sender: {senderResult.Message}");
        Assert.Equal(2, senderResult.VerifiedCount);
        Assert.Equal(2, receiverResult.VerifiedCount);

        Assert.True(senderInteraction.InvitationConsumed);
        Assert.True(receiverInteraction.InvitationConsumed);
        Assert.Equal("Receiver-Desktop", senderInteraction.LastPeerName);
        Assert.Equal("Sender-Desktop", receiverInteraction.LastPeerName);

        Assert.Equal(2, senderInteraction.History.Count);
        Assert.Equal(2, receiverInteraction.History.Count);
        Assert.Contains(
            senderInteraction.ItemStatuses,
            entry => entry.Status == "Verified");
        Assert.Contains(
            receiverInteraction.ItemStatuses,
            entry => entry.Status == "Verified");

        Assert.Equal(
            firstPayload,
            await File.ReadAllBytesAsync(
                Path.Combine(_destinationDir, "alpha.bin")));
        Assert.Equal(
            secondPayload,
            await File.ReadAllBytesAsync(
                Path.Combine(_destinationDir, "beta.bin")));
    }

    [Fact]
    public async Task SharedEngine_IdentityV1_MutuallyAuthenticatesBeforeOffer()
    {
        string sourcePath =
            Path.Combine(
                _sourceDir,
                "trusted.bin");
        byte[] payload =
            RandomNumberGenerator.GetBytes(
                16_384);
        await File.WriteAllBytesAsync(
            sourcePath,
            payload);

        using var listener =
            new TlsSocketListener(0);
        var invitationAuthority =
            new LanInvitationAuthority();
        var invitation =
            invitationAuthority.Publish(
                CreateInvitation(listener));
        var receiverStore =
            new QuarantineStagingStore(
                stagingRoot: _stagingDir,
                destinationRoot: _destinationDir);

        var senderTrust =
            new DesktopPersistentTrustManager(
                new FakeDesktopIdentityStore(true),
                new DesktopTrustStore(
                    Path.Combine(
                        _root,
                        "sender-trust.json")));
        var receiverTrust =
            new DesktopPersistentTrustManager(
                new FakeDesktopIdentityStore(true),
                new DesktopTrustStore(
                    Path.Combine(
                        _root,
                        "receiver-trust.json")));

        senderTrust.Initialize();
        receiverTrust.Initialize();

        var senderInteraction =
            new RecordingInteraction
            {
                AcceptEnrollment = true
            };
        var receiverInteraction =
            new RecordingInteraction
            {
                AcceptEnrollment = true
            };

        var receiverTask = Task.Run(async () =>
        {
            await using var connection =
                await listener.AcceptConnectionAsync();
            return await new DesktopTransferEngine()
                .ReceiveAsync(
                    connection,
                    new DesktopReceiveRequest(
                        receiverStore,
                        invitationAuthority,
                        LocalDeviceName:
                            "Trusted-Receiver",
                        TrustManager:
                            receiverTrust),
                    receiverInteraction);
        });

        DesktopTransferResult senderResult =
            await new DesktopTransferEngine()
                .SendAsync(
                    new DesktopSendRequest(
                        "127.0.0.1",
                        listener.BoundPort,
                        [
                            new DesktopTransferFile(
                                sourcePath)
                        ],
                        invitation,
                        LocalDeviceName:
                            "Trusted-Sender",
                        TrustManager:
                            senderTrust),
                    senderInteraction);

        DesktopTransferResult receiverResult =
            await receiverTask.WaitAsync(
                TimeSpan.FromSeconds(15));

        Assert.True(
            senderResult.Success,
            senderResult.Message);
        Assert.True(
            receiverResult.Success,
            receiverResult.Message);

        DesktopTrustedPeer senderView =
            Assert.Single(
                senderTrust.GetTrustedPeers());
        DesktopTrustedPeer receiverView =
            Assert.Single(
                receiverTrust.GetTrustedPeers());

        Assert.Equal(
            receiverTrust.LocalFingerprintHex(),
            senderView.FingerprintHex);
        Assert.Equal(
            senderTrust.LocalFingerprintHex(),
            receiverView.FingerprintHex);

        Assert.NotNull(
            receiverInteraction
                .LastOfferAuthenticatedFingerprint);
        Assert.Equal(
            senderTrust.LocalFingerprintHex(),
            receiverInteraction
                .LastOfferAuthenticatedFingerprint);
        Assert.Equal(
            1,
            senderInteraction.EnrollmentPrompts);
        Assert.Equal(
            1,
            receiverInteraction.EnrollmentPrompts);

        Assert.Equal(
            payload,
            await File.ReadAllBytesAsync(
                Path.Combine(
                    _destinationDir,
                    "trusted.bin")));
    }

    [Fact]
    public async Task SharedEngine_ExpectedTrustedPeer_RejectsIdentityCapabilityDowngrade()
    {
        string sourcePath =
            Path.Combine(
                _sourceDir,
                "downgrade.bin");
        await File.WriteAllBytesAsync(
            sourcePath,
            RandomNumberGenerator.GetBytes(1024));

        using var listener =
            new TlsSocketListener(0);
        var invitationAuthority =
            new LanInvitationAuthority();
        var invitation =
            invitationAuthority.Publish(
                CreateInvitation(listener));
        var receiverStore =
            new QuarantineStagingStore(
                stagingRoot: _stagingDir,
                destinationRoot: _destinationDir);

        var senderIdentity =
            new FakeDesktopIdentityStore(true);
        var expectedReceiverIdentity =
            new FakeDesktopIdentityStore(true);
        var senderStore =
            new DesktopTrustStore(
                Path.Combine(
                    _root,
                    "downgrade-sender-trust.json"));
        var senderTrust =
            new DesktopPersistentTrustManager(
                senderIdentity,
                senderStore);
        senderTrust.Initialize();

        DesktopTrustDocument senderDocument =
            senderStore.Load();
        long now =
            DateTimeOffset.UtcNow
                .ToUnixTimeMilliseconds();
        senderDocument.Peers.Add(
            new DesktopTrustedPeer(
                expectedReceiverIdentity
                    .GetFingerprintHex(),
                Convert.ToHexStringLower(
                    expectedReceiverIdentity
                        .GetOrCreatePublicKeyDer()),
                "Expected Receiver",
                now,
                now));
        senderStore.Save(
            senderDocument);

        var receiverTask = Task.Run(async () =>
        {
            await using var connection =
                await listener.AcceptConnectionAsync();
            return await new DesktopTransferEngine()
                .ReceiveAsync(
                    connection,
                    new DesktopReceiveRequest(
                        receiverStore,
                        invitationAuthority,
                        LocalDeviceName:
                            "Downgrade-Receiver"),
                    new RecordingInteraction());
        });

        DesktopTransferResult senderResult =
            await new DesktopTransferEngine()
                .SendAsync(
                    new DesktopSendRequest(
                        "127.0.0.1",
                        listener.BoundPort,
                        [
                            new DesktopTransferFile(
                                sourcePath)
                        ],
                        invitation,
                        LocalDeviceName:
                            "Downgrade-Sender",
                        TrustManager:
                            senderTrust,
                        ExpectedPeerFingerprintHex:
                            expectedReceiverIdentity
                                .GetFingerprintHex()),
                    new RecordingInteraction());

        DesktopTransferResult receiverResult =
            await receiverTask.WaitAsync(
                TimeSpan.FromSeconds(15));

        Assert.False(
            senderResult.Success);
        Assert.Contains(
            "identity-v1",
            senderResult.Message,
            StringComparison.OrdinalIgnoreCase);
        Assert.False(
            receiverResult.Success);
        Assert.Empty(
            Directory.GetFiles(
                _destinationDir,
                "*",
                SearchOption.AllDirectories));
    }

    [Fact]
    public async Task SharedEngine_ExpectedTrustedPeer_RevokedBeforeHandshake_FailsClosed()
    {
        string sourcePath =
            Path.Combine(
                _sourceDir,
                "revoked.bin");
        await File.WriteAllBytesAsync(
            sourcePath,
            RandomNumberGenerator.GetBytes(
                1024));

        using var listener =
            new TlsSocketListener(0);
        var invitationAuthority =
            new LanInvitationAuthority();
        var invitation =
            invitationAuthority.Publish(
                CreateInvitation(listener));
        var receiverStore =
            new QuarantineStagingStore(
                stagingRoot: _stagingDir,
                destinationRoot:
                    _destinationDir);

        var senderIdentity =
            new FakeDesktopIdentityStore(true);
        var receiverIdentity =
            new FakeDesktopIdentityStore(true);
        var senderStore =
            new DesktopTrustStore(
                Path.Combine(
                    _root,
                    "revoked-sender-trust.json"));
        var senderTrust =
            new DesktopPersistentTrustManager(
                senderIdentity,
                senderStore);
        senderTrust.Initialize();

        long now =
            DateTimeOffset.UtcNow
                .ToUnixTimeMilliseconds();
        string receiverFingerprint =
            receiverIdentity
                .GetFingerprintHex();
        DesktopTrustDocument senderDocument =
            senderStore.Load();
        senderDocument.Peers.Add(
            new DesktopTrustedPeer(
                receiverFingerprint,
                Convert.ToHexStringLower(
                    receiverIdentity
                        .GetOrCreatePublicKeyDer()),
                "Revoked Receiver",
                now,
                now));
        senderStore.Save(
            senderDocument);
        Assert.True(
            senderTrust.RevokePeer(
                receiverFingerprint));

        var receiverTrust =
            new DesktopPersistentTrustManager(
                receiverIdentity,
                new DesktopTrustStore(
                    Path.Combine(
                        _root,
                        "revoked-receiver-trust.json")));
        receiverTrust.Initialize();

        var receiverTask = Task.Run(async () =>
        {
            await using var connection =
                await listener
                    .AcceptConnectionAsync();
            return await new DesktopTransferEngine()
                .ReceiveAsync(
                    connection,
                    new DesktopReceiveRequest(
                        receiverStore,
                        invitationAuthority,
                        LocalDeviceName:
                            "Revoked-Receiver",
                        TrustManager:
                            receiverTrust),
                    new RecordingInteraction());
        });

        DesktopTransferResult senderResult =
            await new DesktopTransferEngine()
                .SendAsync(
                    new DesktopSendRequest(
                        "127.0.0.1",
                        listener.BoundPort,
                        [
                            new DesktopTransferFile(
                                sourcePath)
                        ],
                        invitation,
                        LocalDeviceName:
                            "Strict-Sender",
                        TrustManager:
                            senderTrust,
                        ExpectedPeerFingerprintHex:
                            receiverFingerprint),
                    new RecordingInteraction());

        DesktopTransferResult receiverResult =
            await receiverTask.WaitAsync(
                TimeSpan.FromSeconds(15));

        Assert.False(
            senderResult.Success);
        Assert.Contains(
            "not enrolled",
            senderResult.Message,
            StringComparison.OrdinalIgnoreCase);
        Assert.False(
            receiverResult.Success);
        Assert.Empty(
            Directory.GetFiles(
                _destinationDir,
                "*",
                SearchOption.AllDirectories));
    }

    [Fact]
    public async Task SharedEngine_CancelledSender_ReturnsCancelledWithoutStartingProtocol()
    {
        string sourcePath = Path.Combine(_sourceDir, "cancel.bin");
        await File.WriteAllBytesAsync(
            sourcePath,
            RandomNumberGenerator.GetBytes(1024));

        using var listener = new TlsSocketListener(0);
        using var cts = new CancellationTokenSource();
        cts.Cancel();

        var engine = new DesktopTransferEngine();
        var result = await engine.SendAsync(
            new DesktopSendRequest(
                "127.0.0.1",
                listener.BoundPort,
                new[] { new DesktopTransferFile(sourcePath) },
                Invitation: null,
                LocalDeviceName: "Cancelled-Sender"),
            new RecordingInteraction(),
            cts.Token);

        Assert.True(result.Cancelled);
        Assert.False(result.Success);
    }

    [Fact]
    public async Task SharedEngine_ReceiverOfferRejection_StopsBeforeFileCommit()
    {
        string sourcePath = Path.Combine(_sourceDir, "reject.bin");
        await File.WriteAllBytesAsync(
            sourcePath,
            RandomNumberGenerator.GetBytes(4096));

        using var listener = new TlsSocketListener(0);
        var invitationAuthority = new LanInvitationAuthority();
        var invitation = invitationAuthority.Publish(CreateInvitation(listener));
        var receiverStore = new QuarantineStagingStore(
            stagingRoot: _stagingDir,
            destinationRoot: _destinationDir);

        var receiverInteraction = new RecordingInteraction
        {
            AcceptOffers = false
        };
        var senderInteraction = new RecordingInteraction();
        var receiverEngine = new DesktopTransferEngine();
        var senderEngine = new DesktopTransferEngine();

        var receiverTask = Task.Run(async () =>
        {
            await using var connection =
                await listener.AcceptConnectionAsync();
            return await receiverEngine.ReceiveAsync(
                connection,
                new DesktopReceiveRequest(
                    receiverStore,
                    invitationAuthority,
                    "Rejecting-Receiver"),
                receiverInteraction);
        });

        var senderResult = await senderEngine.SendAsync(
            new DesktopSendRequest(
                "127.0.0.1",
                listener.BoundPort,
                new[] { new DesktopTransferFile(sourcePath) },
                invitation,
                "Offer-Sender"),
            senderInteraction);

        var receiverResult = await receiverTask.WaitAsync(
            TimeSpan.FromSeconds(15));

        Assert.True(senderResult.Rejected, $"Sender: {senderResult.Message} | Receiver: {receiverResult.Message}");
        Assert.True(receiverResult.Rejected, $"Receiver: {receiverResult.Message} | Sender: {senderResult.Message}");
        Assert.Empty(Directory.GetFiles(
            _destinationDir,
            "*",
            SearchOption.AllDirectories));
        Assert.Empty(senderInteraction.History);
        Assert.Empty(receiverInteraction.History);
    }

    [Fact]
    public async Task SharedEngine_UnpairedSend_FailsClosedBeforeConnecting()
    {
        string sourcePath = Path.Combine(_sourceDir, "unpaired.bin");
        await File.WriteAllBytesAsync(sourcePath, [0x01, 0x02, 0x03]);

        var engine = new DesktopTransferEngine();
        var result = await engine.SendAsync(
            new DesktopSendRequest(
                "127.0.0.1",
                9,
                [new DesktopTransferFile(sourcePath)],
                Invitation: null,
                LocalDeviceName: "Unpaired-Sender"),
            new RecordingInteraction());

        Assert.False(result.Success);
        Assert.Contains(
            "Authenticated pairing invitation required",
            result.Message,
            StringComparison.Ordinal);
    }

    [Fact]
    public void InvitationAuthority_ConsumesExactlyOnce()
    {
        using var listener = new TlsSocketListener(0);
        var authority = new LanInvitationAuthority();
        var invitation = authority.Publish(CreateInvitation(listener));
        string sessionId = Guid.NewGuid().ToString("D").ToLowerInvariant();
        var bootstrap = new LanBootstrapMessage(
            invitation.InvitationId,
            invitation.Token,
            invitation.InvitationHash,
            sessionId);

        var first = authority.Consume(bootstrap);
        var replay = authority.Consume(bootstrap);

        Assert.True(first.IsAccepted);
        Assert.False(replay.IsAccepted);
        Assert.Equal(
            LanBootstrapFailureCode.INVITATION_USED.ToString(),
            replay.Code);
    }

    private static LanInvitation CreateInvitation(
        TlsSocketListener listener)
    {
        byte[] tokenBytes = RandomNumberGenerator.GetBytes(32);
        string token = Convert.ToBase64String(tokenBytes)
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');

        string spki = Convert.ToHexString(
            SHA256.HashData(
                listener.ServerCertificate.PublicKey
                    .ExportSubjectPublicKeyInfo()))
            .ToLowerInvariant();

        var invitation = new LanInvitation(
            invitationId: Guid.NewGuid().ToString("D").ToLowerInvariant(),
            presence: Convert.ToHexString(
                    RandomNumberGenerator.GetBytes(16))
                .ToLowerInvariant(),
            name: "Receiver-Desktop",
            platform: "windows",
            endpoints:
            [
                new LanEndpoint("127.0.0.1", listener.BoundPort)
            ],
            spkiSha256: spki,
            token: token,
            expiresAt: (
                DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 120)
                .ToString(),
            ttlSeconds: 120);

        // The invitation hash is the canonical hash of the encoded QR payload.
        return InvitationCodec.Decode(InvitationCodec.Encode(invitation));
    }

    private sealed class RecordingInteraction
        : DesktopTransferInteractionBase
    {
        public bool AcceptOffers { get; init; } = true;
        public bool AcceptEnrollment { get; init; }
        public bool InvitationConsumed { get; private set; }
        public string? LastPeerName { get; private set; }
        public string? LastOfferAuthenticatedFingerprint { get; private set; }
        public int EnrollmentPrompts { get; private set; }

        public List<DesktopHistoryEntry> History { get; } = [];
        public List<(DesktopTransferDirection Direction, string Item, string Status)>
            ItemStatuses { get; } = [];

        public override Task<bool> ConfirmOfferAsync(
            DesktopOffer offer,
            CancellationToken cancellationToken)
        {
            LastOfferAuthenticatedFingerprint =
                offer.AuthenticatedPeerFingerprintHex;
            return Task.FromResult(
                AcceptOffers);
        }

        public override Task<bool> ConfirmPeerEnrollmentAsync(
            VerifiedDesktopPeerIdentity peer,
            CancellationToken cancellationToken)
        {
            EnrollmentPrompts++;
            return Task.FromResult(
                AcceptEnrollment);
        }

        public override void OnInvitationConsumed(
            DesktopTransferDirection direction) =>
            InvitationConsumed = true;

        public override void OnPeerIdentified(
            DesktopTransferDirection direction,
            string peerName) =>
            LastPeerName = peerName;

        public override void OnItemStatus(
            DesktopTransferDirection direction,
            string itemName,
            string status) =>
            ItemStatuses.Add((direction, itemName, status));

        public override void OnHistory(DesktopHistoryEntry entry) =>
            History.Add(entry);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_root))
            {
                Directory.Delete(_root, recursive: true);
            }
        }
        catch
        {
            // Best-effort test cleanup.
        }
    }
}
