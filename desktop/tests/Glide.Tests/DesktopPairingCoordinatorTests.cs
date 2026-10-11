using System.IO;
using System.Net;
using System.Security.Cryptography;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Desktop.Pairing;
using Glide.Desktop.Services;
using Glide.Transport.Discovery;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;
using Xunit;

namespace Glide.Tests;

public sealed class DesktopPairingCoordinatorTests
{
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
