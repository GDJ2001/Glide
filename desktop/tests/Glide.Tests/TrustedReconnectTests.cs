using System.IO;
using System.Security.Cryptography;
using System.Text;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Desktop;
using Glide.Transport.Framing;
using Glide.Transport.Pairing;
using Glide.Transport.Sockets;
using Xunit;

namespace Glide.Tests;

public sealed class TrustedReconnectTests
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

    private sealed class TestTransferInteraction : DesktopTransferInteractionBase
    {
        private readonly bool _acceptOffers;
        public int OfferPromptCount { get; private set; }
        public List<string> StatusMessages { get; } = [];

        public TestTransferInteraction(bool acceptOffers = true)
        {
            _acceptOffers = acceptOffers;
        }

        public override Task<bool> ConfirmOfferAsync(
            DesktopOffer offer,
            CancellationToken cancellationToken)
        {
            OfferPromptCount++;
            return Task.FromResult(_acceptOffers);
        }

        public override Task<bool> ConfirmPeerEnrollmentAsync(
            VerifiedDesktopPeerIdentity peer,
            CancellationToken cancellationToken) => Task.FromResult(true);

        public override void OnStatus(DesktopTransferDirection direction, string message)
        {
            lock (StatusMessages)
            {
                StatusMessages.Add(message);
            }
        }
    }

    [Fact]
    public async Task TrustedSend_AfterSpake2PinPair_TransfersAcrossRotatedListenerCertificatesWithoutNewInvitation()
    {
        string tempRoot = CreateTempDirectory();
        using var initId = new InMemoryIdentityStore();
        using var respId = new InMemoryIdentityStore();
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

            // 1. Pair once via SPAKE2 6-digit PIN without transferring any files
            await PairDevicesViaPinAsync(initTrust, respTrust);

            string responderFingerprint = respTrust.LocalFingerprintHex();
            Assert.NotNull(initTrust.GetTrustedPeer(responderFingerprint));
            Assert.NotNull(respTrust.GetTrustedPeer(initTrust.LocalFingerprintHex()));

            string sourceDir = Path.Combine(tempRoot, "source");
            string destDir1 = Path.Combine(tempRoot, "dest1");
            string destDir2 = Path.Combine(tempRoot, "dest2");
            Directory.CreateDirectory(sourceDir);
            Directory.CreateDirectory(destDir1);
            Directory.CreateDirectory(destDir2);

            string file1Path = Path.Combine(sourceDir, "alpha-report.txt");
            byte[] file1Bytes = Encoding.UTF8.GetBytes("Trusted reconnect transfer #1 across initial listener certificate.");
            await File.WriteAllBytesAsync(file1Path, file1Bytes);

            string file2Path = Path.Combine(sourceDir, "beta-report.txt");
            byte[] file2Bytes = Encoding.UTF8.GetBytes("Trusted reconnect transfer #2 across rotated listener certificate without any QR or PIN.");
            await File.WriteAllBytesAsync(file2Path, file2Bytes);

            var engine = new DesktopTransferEngine();

            // 2. First TRUSTED_SEND over listener #1
            string listener1Spki;
            using (var listener1 = new TlsSocketListener(port: 0))
            {
                listener1Spki = listener1.LocalSpkiSha256;
                var senderInteraction1 = new TestTransferInteraction(acceptOffers: true);
                var receiverInteraction1 = new TestTransferInteraction(acceptOffers: true);

                var receiveTask1 = AcceptAndRunTrustedReceiveAsync(
                    engine,
                    listener1,
                    respTrust,
                    destDir1,
                    receiverInteraction1);

                var sendRequest1 = new DesktopSendRequest(
                    Host: "127.0.0.1",
                    Port: listener1.BoundPort,
                    Files: [new DesktopTransferFile(file1Path)],
                    Invitation: null,
                    TrustManager: initTrust,
                    ExpectedPeerFingerprintHex: responderFingerprint,
                    LocalDeviceName: "Initiator Windows PC",
                    CandidateEndpoints: [new LanEndpoint("127.0.0.1", listener1.BoundPort)]);

                var sendTask1 = engine.SendTrustedAsync(sendRequest1, senderInteraction1);
                await Task.WhenAll(sendTask1, receiveTask1);

                var sendResult1 = await sendTask1;
                var receiveResult1 = await receiveTask1;

                Assert.True(sendResult1.Success, sendResult1.Message);
                Assert.True(receiveResult1.Success, receiveResult1.Message);
                Assert.Equal(1, receiverInteraction1.OfferPromptCount);
                Assert.Equal(file1Bytes, await File.ReadAllBytesAsync(Path.Combine(destDir1, "alpha-report.txt")));
            }

            // 3. Rotate responder TLS listener certificate (new TlsSocketListener with different SPKI)
            using (var listener2 = new TlsSocketListener(port: 0))
            {
                Assert.NotEqual(listener1Spki, listener2.LocalSpkiSha256);

                var senderInteraction2 = new TestTransferInteraction(acceptOffers: true);
                var receiverInteraction2 = new TestTransferInteraction(acceptOffers: true);

                var receiveTask2 = AcceptAndRunTrustedReceiveAsync(
                    engine,
                    listener2,
                    respTrust,
                    destDir2,
                    receiverInteraction2);

                var sendRequest2 = new DesktopSendRequest(
                    Host: "127.0.0.1",
                    Port: listener2.BoundPort,
                    Files: [new DesktopTransferFile(file2Path)],
                    Invitation: null,
                    TrustManager: initTrust,
                    ExpectedPeerFingerprintHex: responderFingerprint,
                    LocalDeviceName: "Initiator Windows PC",
                    CandidateEndpoints: [new LanEndpoint("127.0.0.1", listener2.BoundPort)]);

                // Also verify SendAsync automatically delegates to SendTrustedAsync when Invitation is null
                var sendTask2 = engine.SendAsync(sendRequest2, senderInteraction2);
                await Task.WhenAll(sendTask2, receiveTask2);

                var sendResult2 = await sendTask2;
                var receiveResult2 = await receiveTask2;

                Assert.True(sendResult2.Success, sendResult2.Message);
                Assert.True(receiveResult2.Success, receiveResult2.Message);
                Assert.Equal(1, receiverInteraction2.OfferPromptCount);
                Assert.Equal(file2Bytes, await File.ReadAllBytesAsync(Path.Combine(destDir2, "beta-report.txt")));
            }
        }
        finally
        {
            CleanupTempDirectory(tempRoot);
        }
    }

    [Fact]
    public async Task TrustedSend_WhenResponderIdentityKeyRotates_FailsClosedWithIdentityChangedPairAgain()
    {
        string tempRoot = CreateTempDirectory();
        using var initId = new InMemoryIdentityStore();
        using var respIdOriginal = new InMemoryIdentityStore();
        using var respIdRotated = new InMemoryIdentityStore();
        try
        {
            string respTrustPath = Path.Combine(tempRoot, "resp-trust.json");
            var initTrust = new DesktopPersistentTrustManager(
                initId,
                new DesktopTrustStore(Path.Combine(tempRoot, "init-trust.json")));
            var respTrustOriginal = new DesktopPersistentTrustManager(
                respIdOriginal,
                new DesktopTrustStore(respTrustPath));
            initTrust.Initialize();
            respTrustOriginal.Initialize();

            await PairDevicesViaPinAsync(initTrust, respTrustOriginal);
            string originalResponderFp = respTrustOriginal.LocalFingerprintHex();

            // Simulate responder identity key rotation while initiator still expects originalResponderFp
            var respTrustRotated = new DesktopPersistentTrustManager(
                respIdRotated,
                new DesktopTrustStore(Path.Combine(tempRoot, "resp-rotated-trust.json")));
            respTrustRotated.Initialize();
            respTrustRotated.EnrollVerifiedPeer(
                new VerifiedDesktopPeerIdentity(
                    initTrust.LocalFingerprintHex(),
                    Convert.ToHexStringLower(initTrust.LocalPublicKeyDer()),
                    "Initiator Windows PC",
                    "windows"),
                userApproved: true);

            string sourceDir = Path.Combine(tempRoot, "source");
            string destDir = Path.Combine(tempRoot, "dest");
            Directory.CreateDirectory(sourceDir);
            Directory.CreateDirectory(destDir);
            string filePath = Path.Combine(sourceDir, "secret.txt");
            await File.WriteAllTextAsync(filePath, "Must never be sent to rotated identity.");

            var engine = new DesktopTransferEngine();
            using var listener = new TlsSocketListener(port: 0);
            var senderInteraction = new TestTransferInteraction(acceptOffers: true);
            var receiverInteraction = new TestTransferInteraction(acceptOffers: true);

            var receiveTask = AcceptAndRunTrustedReceiveAsync(
                engine,
                listener,
                respTrustRotated,
                destDir,
                receiverInteraction);

            var sendRequest = new DesktopSendRequest(
                Host: "127.0.0.1",
                Port: listener.BoundPort,
                Files: [new DesktopTransferFile(filePath)],
                Invitation: null,
                TrustManager: initTrust,
                ExpectedPeerFingerprintHex: originalResponderFp,
                LocalDeviceName: "Initiator Windows PC",
                CandidateEndpoints: [new LanEndpoint("127.0.0.1", listener.BoundPort)]);

            var sendResult = await engine.SendTrustedAsync(sendRequest, senderInteraction);
            var receiveResult = await receiveTask;

            Assert.False(sendResult.Success);
            Assert.Equal("Identity changed — pair again", sendResult.Message);
            Assert.False(receiveResult.Success);
            Assert.Equal(0, receiverInteraction.OfferPromptCount);
            Assert.Empty(Directory.GetFiles(destDir));
        }
        finally
        {
            CleanupTempDirectory(tempRoot);
        }
    }

    [Fact]
    public async Task TrustedSend_WhenInitiatorIsRevoked_ResponderRejectsAndSenderSeesIdentityChangedPairAgain()
    {
        string tempRoot = CreateTempDirectory();
        using var initId = new InMemoryIdentityStore();
        using var respId = new InMemoryIdentityStore();
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

            await PairDevicesViaPinAsync(initTrust, respTrust);

            // Responder revokes initiator
            Assert.True(respTrust.RevokePeer(initTrust.LocalFingerprintHex()));

            string sourceDir = Path.Combine(tempRoot, "source");
            string destDir = Path.Combine(tempRoot, "dest");
            Directory.CreateDirectory(sourceDir);
            Directory.CreateDirectory(destDir);
            string filePath = Path.Combine(sourceDir, "revoked.txt");
            await File.WriteAllTextAsync(filePath, "Must be rejected before offer.");

            var engine = new DesktopTransferEngine();
            using var listener = new TlsSocketListener(port: 0);
            var senderInteraction = new TestTransferInteraction(acceptOffers: true);
            var receiverInteraction = new TestTransferInteraction(acceptOffers: true);

            var receiveTask = AcceptAndRunTrustedReceiveAsync(
                engine,
                listener,
                respTrust,
                destDir,
                receiverInteraction);

            var sendRequest = new DesktopSendRequest(
                Host: "127.0.0.1",
                Port: listener.BoundPort,
                Files: [new DesktopTransferFile(filePath)],
                Invitation: null,
                TrustManager: initTrust,
                ExpectedPeerFingerprintHex: respTrust.LocalFingerprintHex(),
                LocalDeviceName: "Initiator Windows PC",
                CandidateEndpoints: [new LanEndpoint("127.0.0.1", listener.BoundPort)]);

            var sendResult = await engine.SendTrustedAsync(sendRequest, senderInteraction);
            var receiveResult = await receiveTask;

            Assert.False(sendResult.Success);
            Assert.Equal("Identity changed — pair again", sendResult.Message);
            Assert.False(receiveResult.Success);
            Assert.True(receiveResult.Rejected);
            Assert.Equal(0, receiverInteraction.OfferPromptCount);
            Assert.Empty(Directory.GetFiles(destDir));
        }
        finally
        {
            CleanupTempDirectory(tempRoot);
        }
    }

    [Fact]
    public async Task TrustedSend_StillRequiresExplicitReceiverAcceptance_AndWritesNothingWhenDeclined()
    {
        string tempRoot = CreateTempDirectory();
        using var initId = new InMemoryIdentityStore();
        using var respId = new InMemoryIdentityStore();
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

            await PairDevicesViaPinAsync(initTrust, respTrust);

            string sourceDir = Path.Combine(tempRoot, "source");
            string destDir = Path.Combine(tempRoot, "dest");
            Directory.CreateDirectory(sourceDir);
            Directory.CreateDirectory(destDir);
            string filePath = Path.Combine(sourceDir, "declined.txt");
            await File.WriteAllTextAsync(filePath, "Receiver will decline this offer.");

            var engine = new DesktopTransferEngine();
            using var listener = new TlsSocketListener(port: 0);
            var senderInteraction = new TestTransferInteraction(acceptOffers: true);
            var receiverInteraction = new TestTransferInteraction(acceptOffers: false);

            var receiveTask = AcceptAndRunTrustedReceiveAsync(
                engine,
                listener,
                respTrust,
                destDir,
                receiverInteraction);

            var sendRequest = new DesktopSendRequest(
                Host: "127.0.0.1",
                Port: listener.BoundPort,
                Files: [new DesktopTransferFile(filePath)],
                Invitation: null,
                TrustManager: initTrust,
                ExpectedPeerFingerprintHex: respTrust.LocalFingerprintHex(),
                LocalDeviceName: "Initiator Windows PC",
                CandidateEndpoints: [new LanEndpoint("127.0.0.1", listener.BoundPort)]);

            var sendResult = await engine.SendTrustedAsync(sendRequest, senderInteraction);
            var receiveResult = await receiveTask;

            Assert.False(sendResult.Success);
            Assert.True(sendResult.Rejected);
            Assert.False(receiveResult.Success);
            Assert.True(receiveResult.Rejected);
            Assert.Equal(1, receiverInteraction.OfferPromptCount);
            Assert.Empty(Directory.GetFiles(destDir));
        }
        finally
        {
            CleanupTempDirectory(tempRoot);
        }
    }

    [Fact]
    public async Task PairOnlySession_RejectsUnexpectedFileTransferOfferWithProtocolOrder()
    {
        string tempRoot = CreateTempDirectory();
        using var respId = new InMemoryIdentityStore();
        using var rogueId = new InMemoryIdentityStore();
        try
        {
            var respTrust = new DesktopPersistentTrustManager(
                respId,
                new DesktopTrustStore(Path.Combine(tempRoot, "resp-trust.json")));
            respTrust.Initialize();

            using var listener = new TlsSocketListener(port: 0);
            using var authority = new PairReadyWindowAuthority(
                presenceId: "0123456789abcdef0123456789abcdef",
                deviceName: "Responder PC",
                platform: "windows",
                endpoints: [new LanEndpoint("127.0.0.1", listener.BoundPort)],
                serverSpkiSha256Hex: listener.LocalSpkiSha256,
                fixedPinForTest: "123456");

            var serverTask = Task.Run(async () =>
            {
                await using var conn = await listener.AcceptConnectionAsync();
                var kind = await LanSessionPreambleInspector.InspectAndPrepareAsync(conn);
                Assert.Equal(LanSessionDispatchKind.Profile2Gld2, kind);
                var open = Assert.IsType<Profile2Message.SessionOpen>(
                    await Profile2WireCodec.ReadMessageAsync(conn.TransportStream));
                return await Profile2PairingEngine.HandleResponderPairOnlySessionAsync(
                    conn,
                    open,
                    authority,
                    isBusy: false,
                    localDeviceName: "Responder PC",
                    localPlatform: "windows",
                    trustManager: respTrust,
                    approvePeerAsync: (_, _) => Task.FromResult(true));
            });

            var client = new TlsSocketClient();
            await using (var clientConn = await client.ConnectAsync("127.0.0.1", listener.BoundPort, expectedSpkiSha256: null))
            {
                await LanSessionPreambleInspector.WriteGld2PreambleAsync(clientConn.TransportStream);
                string sessionId = Guid.NewGuid().ToString("D");
                await Profile2WireCodec.WriteMessageAsync(
                    clientConn.TransportStream,
                    new Profile2Message.SessionOpen(
                        SessionId: sessionId,
                        Purpose: Profile2Constants.PurposePairOnly,
                        AuthMethod: Profile2Constants.AuthMethodSpake2PinV1,
                        InitiatorNonce: InvitationCodec.ToUnpaddedBase64Url(RandomNumberGenerator.GetBytes(32)),
                        InitiatorDeviceName: "Rogue Sender",
                        InitiatorPlatform: "windows",
                        InitiatorIdentityPublicKey: InvitationCodec.ToUnpaddedBase64Url(rogueId.GetOrCreatePublicKeyDer()),
                        ExpectedPresenceId: "0123456789abcdef0123456789abcdef"));

                var ack = Assert.IsType<Profile2Message.SessionOpenAck>(
                    await Profile2WireCodec.ReadMessageAsync(clientConn.TransportStream));
                Assert.True(ack.Accepted);

                // Rogue peer attempts to send an OFFER_BEGIN control frame on a PAIR_ONLY session
                byte[] rogueOfferJson = Encoding.UTF8.GetBytes("{\"v\":2,\"t\":\"OFFER_BEGIN\",\"sessionId\":\"" + sessionId + "\"}");
                byte[] lenPrefix = new byte[4];
                System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(lenPrefix, (uint)rogueOfferJson.Length);
                await clientConn.TransportStream.WriteAsync(lenPrefix);
                await clientConn.TransportStream.WriteAsync(rogueOfferJson);
                await clientConn.TransportStream.FlushAsync();
            }

            var respOutcome = await serverTask;
            Assert.False(respOutcome.Succeeded);
            Assert.Equal(Profile2Constants.ErrorProtocolOrder, respOutcome.ErrorCode);
            Assert.Empty(respTrust.GetTrustedPeers());
        }
        finally
        {
            CleanupTempDirectory(tempRoot);
        }
    }

    private static async Task PairDevicesViaPinAsync(
        DesktopPersistentTrustManager initTrust,
        DesktopPersistentTrustManager respTrust)
    {
        using var listener = new TlsSocketListener(port: 0);
        using var authority = new PairReadyWindowAuthority(
            presenceId: "fedcba9876543210fedcba9876543210",
            deviceName: "Responder Linux PC",
            platform: "linux",
            endpoints: [new LanEndpoint("127.0.0.1", listener.BoundPort)],
            serverSpkiSha256Hex: listener.LocalSpkiSha256,
            fixedPinForTest: "654321");

        var serverTask = Task.Run(async () =>
        {
            await using var conn = await listener.AcceptConnectionAsync();
            var kind = await LanSessionPreambleInspector.InspectAndPrepareAsync(conn);
            Assert.Equal(LanSessionDispatchKind.Profile2Gld2, kind);
            var open = Assert.IsType<Profile2Message.SessionOpen>(
                await Profile2WireCodec.ReadMessageAsync(conn.TransportStream));
            return await Profile2PairingEngine.HandleResponderPairOnlySessionAsync(
                conn,
                open,
                authority,
                isBusy: false,
                localDeviceName: "Responder Linux PC",
                localPlatform: "linux",
                trustManager: respTrust,
                approvePeerAsync: (_, _) => Task.FromResult(true));
        });

        var clientTask = Profile2PairingEngine.RunInitiatorPinPairAsync(
            host: "127.0.0.1",
            port: listener.BoundPort,
            expectedPresenceId: "fedcba9876543210fedcba9876543210",
            sixDigitPin: "654321",
            localDeviceName: "Initiator Windows PC",
            localPlatform: "windows",
            trustManager: initTrust,
            approvePeerAsync: (_, _) => Task.FromResult(true));

        await Task.WhenAll(clientTask, serverTask);
        Assert.True((await clientTask).Succeeded);
        Assert.True((await serverTask).Succeeded);
    }

    private static async Task<DesktopTransferResult> AcceptAndRunTrustedReceiveAsync(
        DesktopTransferEngine engine,
        TlsSocketListener listener,
        DesktopPersistentTrustManager respTrust,
        string destinationDirectory,
        IDesktopTransferInteraction interaction)
    {
        await using var conn = await listener.AcceptConnectionAsync();
        var kind = await LanSessionPreambleInspector.InspectAndPrepareAsync(conn);
        Assert.Equal(LanSessionDispatchKind.Profile2Gld2, kind);
        var open = Assert.IsType<Profile2Message.SessionOpen>(
            await Profile2WireCodec.ReadMessageAsync(conn.TransportStream));

        string stagingDir = Path.Combine(destinationDirectory, ".glide-staging");
        var stagingStore = new QuarantineStagingStore(stagingDir, destinationDirectory);

        var receiveRequest = new DesktopReceiveRequest(
            StagingStore: stagingStore,
            LocalDeviceName: "Responder Linux PC",
            TrustManager: respTrust);

        return await engine.ReceiveTrustedAsync(
            conn,
            open,
            receiveRequest,
            interaction,
            isBusy: false);
    }

    private static string CreateTempDirectory()
    {
        string path = Path.Combine(Path.GetTempPath(), "glide-trusted-reconnect-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(path);
        return path;
    }

    private static void CleanupTempDirectory(string path)
    {
        try
        {
            if (Directory.Exists(path))
            {
                Directory.Delete(path, recursive: true);
            }
        }
        catch
        {
            // Ignore cleanup errors in tests
        }
    }
}
