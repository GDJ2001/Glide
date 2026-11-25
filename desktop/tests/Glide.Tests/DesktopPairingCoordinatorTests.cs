using System.IO;
using System.Net;
using System.Security.Cryptography;
using Glide.Core.Pairing;
using Glide.Core.Models;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Desktop;
using Glide.Desktop.Pairing;
using Glide.Desktop.Services;
using Glide.Transport.Discovery;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;
using Xunit;

namespace Glide.Tests;

public sealed class DesktopPairingCoordinatorTests
{
    [Theory]
    [InlineData(true, false)]
    [InlineData(false, false)]
    [InlineData(true, true)]
    public async Task TrustedBatchResumesRemainingFilesAndDoesNotRepeatCommittedFiles(bool senderInitiates, bool changeSource)
    {
        string root = Path.Combine(Path.GetTempPath(), $"glide-live-resume-{Guid.NewGuid():N}");
        Directory.CreateDirectory(root);
        try
        {
            using var senderIdentity = new InMemoryIdentityStore();
            using var receiverIdentity = new InMemoryIdentityStore();
            var senderTrust = new DesktopPersistentTrustManager(senderIdentity, new DesktopTrustStore(Path.Combine(root, "s.json")));
            var receiverTrust = new DesktopPersistentTrustManager(receiverIdentity, new DesktopTrustStore(Path.Combine(root, "r.json")));
            senderTrust.Initialize();
            receiverTrust.Initialize();
            void Enroll(DesktopPersistentTrustManager local, DesktopPersistentTrustManager peer, string name) =>
                local.EnrollVerifiedPeer(new VerifiedDesktopPeerIdentity(peer.LocalFingerprintHex(),
                    Convert.ToHexStringLower(peer.LocalPublicKeyDer()), name, "windows"), userApproved: true);
            Enroll(senderTrust, receiverTrust, "Receiver");
            Enroll(receiverTrust, senderTrust, "Sender");
            var senderJournal = new DesktopResumeStore(Path.Combine(root, "sr"));
            var receiverJournal = new DesktopResumeStore(Path.Combine(root, "rr"));
            var senderLocal = new DesktopReceiveRequest(
                new QuarantineStagingStore(Path.Combine(root, "stg-s"), Path.Combine(root, "dst-s")),
                TrustManager: senderTrust, ResumeStore: senderJournal);
            string received = Path.Combine(root, "dst-r");
            var receiverLocal = new DesktopReceiveRequest(
                new QuarantineStagingStore(Path.Combine(root, "stg-r"), received),
                TrustManager: receiverTrust, ResumeStore: receiverJournal);
            string a = Path.Combine(root, "a.txt");
            string b = Path.Combine(root, "b.bin");
            string c = Path.Combine(root, "c.txt");
            File.WriteAllText(a, "First");
            byte[] bytes = RandomNumberGenerator.GetBytes(3 * 1024 * 1024 + 271);
            File.WriteAllBytes(b, bytes);
            File.WriteAllBytes(c, []);
            var senderEngine = new DesktopTransferEngine(ioOptions: new DesktopTransferIoOptions { EnableBoundedPipeline = false });
            var receiverEngine = new DesktopTransferEngine(ioOptions: new DesktopTransferIoOptions { EnableBoundedPipeline = false });
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(45));
            using var initial = new TlsSocketListener(0);
            var originalReceiver = Task.Run(async () =>
            {
                await using var conn = await initial.AcceptConnectionAsync(timeout.Token);
                await LanSessionPreambleInspector.InspectAndPrepareAsync(conn, timeout.Token);
                var open = Assert.IsType<Profile2Message.SessionOpen>(
                    await Profile2WireCodec.ReadMessageAsync(conn.TransportStream, timeout.Token));
                return await receiverEngine.ReceiveTrustedAsync(conn, open, receiverLocal, new ResumeInteraction(progress =>
                {
                    if (progress.ItemName == "b.bin" && progress.TransferredBytes >= 1024 * 1024 + 5)
                        conn.AbortTransport();
                }), cancellationToken: timeout.Token);
            }, timeout.Token);
            var sent = await senderEngine.SendTrustedAsync(new DesktopSendRequest("127.0.0.1", initial.BoundPort,
                [new(a), new(b), new(c)], TrustManager: senderTrust,
                ExpectedPeerFingerprintHex: receiverTrust.LocalFingerprintHex(), ResumeStore: senderJournal,
                CandidateEndpoints: [new("127.0.0.1", initial.BoundPort)]), new ResumeInteraction(), timeout.Token);
            var interrupted = await originalReceiver;
            Assert.False(sent.Success);
            Assert.False(interrupted.Success);
            Assert.True(File.Exists(Path.Combine(received, "a.txt")), $"{sent.Message} / {interrupted.Message}");
            Assert.Equal("First", File.ReadAllText(Path.Combine(received, "a.txt")));
            var senderBatch = Assert.Single(await senderJournal.ListAcceptedBatchesAsync(timeout.Token));
            var receiverBatch = Assert.Single(await receiverJournal.ListAcceptedBatchesAsync(timeout.Token));
            Assert.Equal(1, receiverBatch.VerifiedCount);
            var partial = await receiverJournal.LoadAsync(new SessionId(receiverBatch.Manifest.SessionId),
                new ItemId(receiverBatch.Manifest.Items[1].Id), timeout.Token);
            Assert.Equal(1024 * 1024, partial?.ContiguousByteOffset);
            if (changeSource)
            {
                bytes[^1] ^= 0xff;
                File.WriteAllBytes(b, bytes);
            }

            using var reconnect = new TlsSocketListener(0);
            int approvals = 0;
            var incoming = Task.Run(async () =>
            {
                await using var conn = await reconnect.AcceptConnectionAsync(timeout.Token);
                await LanSessionPreambleInspector.InspectAndPrepareAsync(conn, timeout.Token);
                var open = Assert.IsType<Profile2Message.SessionOpen>(
                    await Profile2WireCodec.ReadMessageAsync(conn.TransportStream, timeout.Token));
                return await (senderInitiates ? receiverEngine : senderEngine).ReceiveTrustedResumeAsync(conn, open,
                    senderInitiates ? receiverLocal : senderLocal, new ResumeInteraction(approval: () => approvals++),
                    cancellationToken: timeout.Token);
            }, timeout.Token);
            var resumed = await (senderInitiates ? senderEngine : receiverEngine).ResumeTrustedAsync(
                new DesktopResumeRequest(senderInitiates ? senderBatch : receiverBatch,
                    senderInitiates ? senderLocal : receiverLocal, [new(new("127.0.0.1", reconnect.BoundPort), false, true)]),
                new ResumeInteraction(approval: () => approvals++), timeout.Token);
            var inbound = await incoming;
            if (changeSource)
            {
                Assert.False(resumed.Success);
                Assert.False(inbound.Success);
                Assert.False(File.Exists(Path.Combine(received, "b.bin")));
                Assert.False(File.Exists(Path.Combine(received, "c.txt")));
                Assert.Single(Directory.GetFiles(received, "a*"));
                return;
            }
            Assert.True(resumed.Success, $"{resumed.Message} / {inbound.Message}");
            Assert.True(inbound.Success, inbound.Message);
            Assert.Equal(senderInitiates ? 1 : 0, approvals);
            Assert.Equal(bytes, File.ReadAllBytes(Path.Combine(received, "b.bin")));
            Assert.Equal(0, new FileInfo(Path.Combine(received, "c.txt")).Length);
            Assert.Single(Directory.GetFiles(received, "a*"));
            Assert.Empty(await senderJournal.ListAcceptedBatchesAsync(timeout.Token));
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    private sealed class ResumeInteraction(Action<DesktopTransferProgress>? progress = null, Action? approval = null)
        : DesktopTransferInteractionBase
    {
        public override void OnProgress(DesktopTransferProgress snapshot) => progress?.Invoke(snapshot);
        public override Task<bool> ConfirmOfferAsync(DesktopOffer offer, CancellationToken cancellationToken)
        {
            approval?.Invoke();
            return Task.FromResult(true);
        }
    }

    private sealed class InMemoryIdentityStore : IDesktopPersistentIdentityStore, IDisposable
    {
        private readonly ECDsa _ecdsa = ECDsa.Create(ECCurve.NamedCurves.nistP256);

        public bool HasIdentity() => true;

        public byte[] GetOrCreatePublicKeyDer() => _ecdsa.ExportSubjectPublicKeyInfo();

        public string GetFingerprintHex() =>
            Convert.ToHexStringLower(SHA256.HashData(GetOrCreatePublicKeyDer()));

        public byte[] SignTranscript(ReadOnlySpan<byte> transcript) =>
            _ecdsa.SignData(transcript, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);

        public void DeleteIdentity() { }

        public void Dispose() => _ecdsa.Dispose();
    }

    [Fact]
    public void IsAllowedLocalLanAddress_RejectsLoopbackLinkLocalAndPublicWan_AllowsRfc1918()
    {
        Assert.False(DesktopPairingCoordinator.IsAllowedLocalLanAddress(IPAddress.Loopback));
        Assert.False(DesktopPairingCoordinator.IsAllowedLocalLanAddress(IPAddress.Parse("169.254.12.34")));
        Assert.False(DesktopPairingCoordinator.IsAllowedLocalLanAddress(IPAddress.Parse("8.8.8.8")));

        Assert.True(DesktopPairingCoordinator.IsAllowedLocalLanAddress(IPAddress.Parse("192.168.1.42")));
        Assert.True(DesktopPairingCoordinator.IsAllowedLocalLanAddress(IPAddress.Parse("10.0.0.15")));
        Assert.True(DesktopPairingCoordinator.IsAllowedLocalLanAddress(IPAddress.Parse("172.20.4.9")));
    }

    [Fact]
    public async Task TrustedResumeWithoutRuntimeReturnsUnsupportedWithoutRoutingToSend()
    {
        string root = Path.Combine(Path.GetTempPath(), $"glide-resume-router-{Guid.NewGuid():N}");
        using var identity = new InMemoryIdentityStore();
        using var mdns = new MdnsDiscoveryService();
        using var listener = new TlsSocketListener(port: 0);
        using var coordinator = new DesktopPairingCoordinator(
            new DesktopPersistentTrustManager(identity, new DesktopTrustStore(Path.Combine(root, "trust.json"))),
            new DesktopInvitationManager(), mdns, () => "Receiver",
            _ => Task.FromResult(listener), () => { }, () => false, addressPolicy: _ => true);
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var server = Task.Run(async () =>
        {
            await using var connection = await listener.AcceptConnectionAsync(timeout.Token);
            Assert.Equal(LanSessionDispatchKind.Profile2Gld2,
                await LanSessionPreambleInspector.InspectAndPrepareAsync(connection, timeout.Token));
            await DesktopProfile2Router.RouteAsync(connection, coordinator,
                (_, _, _) => throw new InvalidOperationException("Resume cannot enter the ordinary send flow"), timeout.Token);
        }, timeout.Token);
        var client = new TlsSocketClient();
        await using var sender = await client.ConnectAsync(
            IPAddress.Loopback.ToString(), listener.BoundPort, expectedSpkiSha256: null, timeout.Token);
        var open = new Profile2Message.SessionOpen(
            SessionId: Guid.NewGuid().ToString(),
            Purpose: Profile2Constants.PurposeTrustedResume,
            AuthMethod: Profile2Constants.AuthMethodIdentityV1,
            InitiatorNonce: InvitationCodec.ToUnpaddedBase64Url(new byte[32]),
            InitiatorDeviceName: "Sender",
            InitiatorPlatform: "windows",
            InitiatorIdentityPublicKey: InvitationCodec.ToUnpaddedBase64Url(identity.GetOrCreatePublicKeyDer()),
            ExpectedPeerIdentityFingerprint: identity.GetFingerprintHex(),
            OriginalSessionId: Guid.NewGuid().ToString(),
            ItemId: Guid.NewGuid().ToString(),
            InitiatorRole: "sender",
            ResumeManifestDigest: new string('a', 64));
        await LanSessionPreambleInspector.WriteGld2PreambleAsync(sender.TransportStream, timeout.Token);
        await Profile2WireCodec.WriteMessageAsync(sender.TransportStream, open, timeout.Token);
        var ack = Assert.IsType<Profile2Message.SessionOpenAck>(
            await Profile2WireCodec.ReadMessageAsync(sender.TransportStream, timeout.Token));
        Assert.False(ack.Accepted);
        Assert.Equal(Profile2Constants.ErrorUnsupportedVersion, ack.ErrorCode);
        Assert.Equal(open.SessionId, ack.SessionId);
        await server;
    }

    [Fact]
    public async Task StartReadyAsync_PublishesCodeLinkAndLocalEndpointsInDiagnostic()
    {
        string tempRoot = Path.Combine(Path.GetTempPath(), $"glide-pair-coord-{Guid.NewGuid():N}");
        Directory.CreateDirectory(tempRoot);
        using var idStore = new InMemoryIdentityStore();
        using var mdns = new MdnsDiscoveryService();
        using var listener = new TlsSocketListener(port: 0);
        try
        {
            var trust = new DesktopPersistentTrustManager(
                idStore,
                new DesktopTrustStore(Path.Combine(tempRoot, "trust.json")));
            trust.Initialize();

            var invManager = new DesktopInvitationManager();
            using var coordinator = new DesktopPairingCoordinator(
                trust,
                invManager,
                mdns,
                () => "Target-PC",
                _ => Task.FromResult(listener),
                () => { },
                () => false,
                addressPolicy: _ => true);

            await coordinator.StartReadyAsync();
            var snap = coordinator.Snapshot;

            Assert.Equal(DesktopPairingPhase.TargetReady, snap.Phase);
            Assert.Matches(@"^\d{3} \d{3}$", snap.Code);
            Assert.StartsWith("glide://pair/v2#", snap.QrUri);
            Assert.Equal("Enter this code on the other computer.", snap.Message);
            Assert.Contains($":{listener.BoundPort}", snap.Diagnostic);
            Assert.True(coordinator.IsReadyWindowActive);

            coordinator.Cancel();
            Assert.Equal(DesktopPairingPhase.Idle, coordinator.Snapshot.Phase);
            Assert.False(coordinator.IsReadyWindowActive);
        }
        finally
        {
            try { Directory.Delete(tempRoot, recursive: true); } catch { }
        }
    }

    [Fact]
    public async Task PairWithAddressAsync_CompletesSpake2PinPairAndEnrollsBothPeers()
    {
        string tempRoot = Path.Combine(Path.GetTempPath(), $"glide-pair-coord-{Guid.NewGuid():N}");
        Directory.CreateDirectory(tempRoot);
        using var initId = new InMemoryIdentityStore();
        using var respId = new InMemoryIdentityStore();
        using var initMdns = new MdnsDiscoveryService();
        using var respMdns = new MdnsDiscoveryService();
        using var listener = new TlsSocketListener(port: 0);
        try
        {
            var initTrust = new DesktopPersistentTrustManager(
                initId,
                new DesktopTrustStore(Path.Combine(tempRoot, "init-trust.json")));
            var respTrust = new DesktopPersistentTrustManager(
                respId,
                new DesktopTrustStore(Path.Combine(tempRoot, "resp-trust.json")));
            initTrust.Initialize();
            respTrust.Initialize();

            var initInv = new DesktopInvitationManager();
            var respInv = new DesktopInvitationManager();

            using var initiator = new DesktopPairingCoordinator(
                initTrust,
                initInv,
                initMdns,
                () => "Initiator-Desktop",
                _ => Task.FromResult(listener),
                () => { },
                () => false,
                addressPolicy: _ => true);

            using var responder = new DesktopPairingCoordinator(
                respTrust,
                respInv,
                respMdns,
                () => "Responder-Desktop",
                _ => Task.FromResult(listener),
                () => { },
                () => false,
                addressPolicy: _ => true);

            responder.Changed += snap =>
            {
                if (snap.Phase == DesktopPairingPhase.TargetApproval)
                {
                    responder.Approve();
                }
            };

            await responder.StartReadyAsync();
            var readySnap = responder.Snapshot;
            string rawPin = readySnap.Code.Replace(" ", "");

            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(15));
            var serverTask = Task.Run(async () =>
            {
                await using var conn = await listener.AcceptConnectionAsync(cts.Token);
                var kind = await LanSessionPreambleInspector.InspectAndPrepareAsync(conn, cts.Token);
                Assert.Equal(LanSessionDispatchKind.Profile2Gld2, kind);
                await DesktopProfile2Router.RouteAsync(conn, responder, null, cts.Token);
            }, cts.Token);

            await Task.WhenAll(
                initiator.PairWithAddressAsync($"127.0.0.1:{listener.BoundPort}", rawPin),
                serverTask);

            Assert.Equal(DesktopPairingPhase.Success, initiator.Snapshot.Phase);
            Assert.Equal("Paired with Responder-Desktop", initiator.Snapshot.Message);
            Assert.Equal(DesktopPairingPhase.Success, responder.Snapshot.Phase);
            Assert.Equal("Paired with Initiator-Desktop", responder.Snapshot.Message);

            var initSaved = initTrust.GetTrustedPeer(respTrust.LocalFingerprintHex());
            var respSaved = respTrust.GetTrustedPeer(initTrust.LocalFingerprintHex());
            Assert.NotNull(initSaved);
            Assert.NotNull(respSaved);
        }
        finally
        {
            try { Directory.Delete(tempRoot, recursive: true); } catch { }
        }
    }

    [Fact]
    public async Task PairWithLinkAsync_RejectsTransferV1Uri_SucceedsWithPairOnlyV2Uri()
    {
        string tempRoot = Path.Combine(Path.GetTempPath(), $"glide-pair-coord-{Guid.NewGuid():N}");
        Directory.CreateDirectory(tempRoot);
        using var initId = new InMemoryIdentityStore();
        using var respId = new InMemoryIdentityStore();
        using var initMdns = new MdnsDiscoveryService();
        using var respMdns = new MdnsDiscoveryService();
        using var listener = new TlsSocketListener(port: 0);
        try
        {
            var initTrust = new DesktopPersistentTrustManager(
                initId,
                new DesktopTrustStore(Path.Combine(tempRoot, "init-trust.json")));
            var respTrust = new DesktopPersistentTrustManager(
                respId,
                new DesktopTrustStore(Path.Combine(tempRoot, "resp-trust.json")));
            initTrust.Initialize();
            respTrust.Initialize();

            var initInv = new DesktopInvitationManager();
            var respInv = new DesktopInvitationManager();

            using var initiator = new DesktopPairingCoordinator(
                initTrust,
                initInv,
                initMdns,
                () => "Initiator-Phone",
                _ => Task.FromResult(listener),
                () => { },
                () => false,
                addressPolicy: _ => true);

            using var responder = new DesktopPairingCoordinator(
                respTrust,
                respInv,
                respMdns,
                () => "Responder-PC",
                _ => Task.FromResult(listener),
                () => { },
                () => false,
                addressPolicy: _ => true);

            responder.Changed += snap =>
            {
                if (snap.Phase == DesktopPairingPhase.TargetApproval)
                {
                    responder.Approve();
                }
            };

            var v1Inv = respInv.CreateReceiverInvitation(listener, "Sender", listener.BoundPort);
            string v1Uri = InvitationCodec.Encode(v1Inv);

            await initiator.PairWithLinkAsync(v1Uri);
            Assert.Equal(DesktopPairingPhase.UseLink, initiator.Snapshot.Phase);
            Assert.Contains("sends files", initiator.Snapshot.Message, StringComparison.OrdinalIgnoreCase);

            await responder.StartReadyAsync();
            var readySnap = responder.Snapshot;

            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(15));
            var serverTask = Task.Run(async () =>
            {
                await using var conn = await listener.AcceptConnectionAsync(cts.Token);
                var kind = await LanSessionPreambleInspector.InspectAndPrepareAsync(conn, cts.Token);
                Assert.Equal(LanSessionDispatchKind.Profile2Gld2, kind);
                await DesktopProfile2Router.RouteAsync(conn, responder, null, cts.Token);
            }, cts.Token);

            await Task.WhenAll(
                initiator.PairWithLinkAsync(readySnap.QrUri),
                serverTask);

            Assert.Equal(DesktopPairingPhase.Success, initiator.Snapshot.Phase);
            Assert.Equal("Paired with Responder-PC", initiator.Snapshot.Message);
        }
        finally
        {
            try { Directory.Delete(tempRoot, recursive: true); } catch { }
        }
    }
}
