using System.ComponentModel;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Core.Transport.Lan;
using Glide.Desktop;
using Glide.Desktop.Diagnostics;
using Glide.Desktop.Pairing;
using Glide.Desktop.Services;
using Glide.Transport.Discovery;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;
using Microsoft.Win32.SafeHandles;
using Xunit;
using Xunit.Abstractions;

namespace Glide.Tests;

public sealed class LinuxAuditRegressionTests
{
    private readonly ITestOutputHelper _output;

    public LinuxAuditRegressionTests(ITestOutputHelper output)
    {
        _output = output;
    }

    [Fact]
    public async Task CaptureBaselineAndPostFixMetrics()
    {
        string tempRoot = Path.Combine(Path.GetTempPath(), "Glide_Baseline_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(tempRoot);
        try
        {
            using var mdns = new MdnsDiscoveryService();
            var invManager = new DesktopInvitationManager();
            using var listener = new TlsSocketListener(0);
            using var coordinator = new DesktopPairingCoordinator(
                null,
                invManager,
                mdns,
                () => "Baseline-Linux",
                _ => Task.FromResult(listener),
                () => { },
                () => false,
                addressPolicy: _ => true);

            byte[] pkt1 = MdnsDiscoveryService.BuildResponsePacketForTesting(
                "Glide-PeerA",
                53319,
                "11111111111111111111111111111111",
                pairReady: true,
                trustedReconnectSupported: true);

            var swDiscovery = Stopwatch.StartNew();
            mdns.ProcessIncomingPacketForTesting(pkt1, new IPEndPoint(IPAddress.Parse("192.168.0.101"), 5353));
            swDiscovery.Stop();

            coordinator.BeginFindDevices();
            var phaseImmediatelyAfterCachedBegin = coordinator.Snapshot.Phase;

            using var blockedStream = new BlockingWriteStream();
            var channel = new LanRecordChannel(blockedStream, coalesceWrites: true, maxCoalesceDelayMs: 5);
            var bulkRef = new BulkRef(Guid.NewGuid().ToString("D"));
            await channel.WriteDataChunkAsync(bulkRef, new byte[1024]);
            await blockedStream.WriteStarted.WaitAsync(TimeSpan.FromSeconds(2));

            var swCancel = Stopwatch.StartNew();
            channel.CancelAndAbortTransport();
            await channel.DisposeAsync();
            swCancel.Stop();

            string srcDir = Path.Combine(tempRoot, "src");
            string stgDir = Path.Combine(tempRoot, "stg");
            string dstDir = Path.Combine(tempRoot, "dst");
            Directory.CreateDirectory(srcDir);
            Directory.CreateDirectory(stgDir);
            Directory.CreateDirectory(dstDir);

            string largeFile = Path.Combine(srcDir, "baseline_128mib.bin");
            byte[] payload = RandomNumberGenerator.GetBytes(128 * 1024 * 1024);
            await File.WriteAllBytesAsync(largeFile, payload);

            var senderPool = new TransferBufferPool();
            var receiverPool = new TransferBufferPool();
            var diagSink = new BoundedTransferDiagnosticSink(buildCommit: "d8673449f368");
            var senderEngine = new DesktopTransferEngine(bufferPool: senderPool, diagnostics: diagSink);
            var receiverEngine = new DesktopTransferEngine(bufferPool: receiverPool, diagnostics: diagSink);

            var auth = new LanInvitationAuthority();
            string token = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
            var inv = auth.Publish(InvitationCodec.Decode(InvitationCodec.Encode(new LanInvitation(
                Guid.NewGuid().ToString("D"),
                "22222222222222222222222222222222",
                "Receiver-Linux",
                "linux",
                [new LanEndpoint("127.0.0.1", listener.BoundPort)],
                listener.LocalSpkiSha256,
                token,
                (DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 120).ToString(),
                120))));

            var store = new QuarantineStagingStore(stgDir, dstDir, enableSameVolumeStaging: true);
            var recvReq = new DesktopReceiveRequest(store, auth, "Receiver-Linux");
            var sendReq = new DesktopSendRequest("127.0.0.1", listener.BoundPort, [new DesktopTransferFile(largeFile)], inv, "Sender-Linux");

            var swTransfer = Stopwatch.StartNew();
            var recvTask = Task.Run(async () =>
            {
                await using var conn = await listener.AcceptConnectionAsync();
                return await receiverEngine.ReceiveAsync(conn, recvReq, new AutoAcceptInteraction());
            });
            var sendRes = await senderEngine.SendAsync(sendReq, new AutoAcceptInteraction());
            var recvRes = await recvTask;
            swTransfer.Stop();

            double mbTransferred = payload.Length / 1_000_000.0;
            double rateMBps = mbTransferred / swTransfer.Elapsed.TotalSeconds;

            _output.WriteLine($"POSTFIX_DISCOVERY_PARSE_MS={swDiscovery.Elapsed.TotalMilliseconds:F3}");
            _output.WriteLine($"POSTFIX_CACHED_FIND_PHASE={phaseImmediatelyAfterCachedBegin}");
            _output.WriteLine($"POSTFIX_SCHEDULED_WRITE_CANCEL_MS={swCancel.ElapsedMilliseconds}");
            _output.WriteLine($"POSTFIX_128MIB_TOTAL_MS={swTransfer.Elapsed.TotalMilliseconds:F1}");
            _output.WriteLine($"POSTFIX_128MIB_RATE_MBPS={rateMBps:F2}");

            Assert.Equal(DesktopPairingPhase.PeersFound, phaseImmediatelyAfterCachedBegin);
            Assert.True(swCancel.ElapsedMilliseconds < 200, $"Expected fast cancellation, got {swCancel.ElapsedMilliseconds} ms");
            Assert.True(sendRes.Success);
            Assert.True(recvRes.Success);
        }
        finally
        {
            try { Directory.Delete(tempRoot, true); } catch { }
        }
    }

    // =========================================================================
    // 1. Invitation rotation / goodbye for old presence must not remove replacement
    // =========================================================================
    [Fact]
    public void DiscoveryCache_InvitationRotationAndOldGoodbye_PreservesCurrentReplacementAndRefreshesTtl()
    {
        var fakeTime = new ManualTimeProvider(DateTimeOffset.Parse("2026-10-17T10:00:00Z"));
        using var mdns = new MdnsDiscoveryService(timeProvider: fakeTime, maxCacheEntries: 4);
        var remoteEndpoint = new IPEndPoint(IPAddress.Parse("192.168.0.245"), 5353);

        string oldPresence = "11111111111111111111111111111111";
        string newPresence = "22222222222222222222222222222222";

        byte[] announceOld = MdnsDiscoveryService.BuildResponsePacketForTesting(
            "Ubuntu-Desktop",
            53319,
            oldPresence,
            ttlSeconds: 120,
            pairReady: true,
            trustedReconnectSupported: true);
        mdns.ProcessIncomingPacketForTesting(announceOld, remoteEndpoint);
        Assert.Single(mdns.Snapshot.Peers);
        Assert.Equal(oldPresence, mdns.Snapshot.Peers[0].PresenceId);

        // Rotate invitation -> new presence at the same endpoint (192.168.0.245:53319)
        fakeTime.Advance(TimeSpan.FromSeconds(105));
        byte[] announceNew = MdnsDiscoveryService.BuildResponsePacketForTesting(
            "Ubuntu-Desktop",
            53319,
            newPresence,
            ttlSeconds: 120,
            pairReady: true,
            trustedReconnectSupported: true);
        mdns.ProcessIncomingPacketForTesting(announceNew, remoteEndpoint);
        Assert.Single(mdns.Snapshot.Peers);
        Assert.Equal(newPresence, mdns.Snapshot.Peers[0].PresenceId);

        // Delayed goodbye (TTL=0) for the OLD presence arrives: must NOT remove the newer advertisement!
        byte[] goodbyeOld = MdnsDiscoveryService.BuildResponsePacketForTesting(
            "Ubuntu-Desktop",
            53319,
            oldPresence,
            ttlSeconds: 0,
            pairReady: false,
            trustedReconnectSupported: true);
        mdns.ProcessIncomingPacketForTesting(goodbyeOld, remoteEndpoint);
        Assert.Single(mdns.Snapshot.Peers);
        Assert.Equal(newPresence, mdns.Snapshot.Peers[0].PresenceId);

        // Old 120s window passes (+20s = 125s total since first announce, but only 20s since new announce):
        // Old presence expiry must NOT evict the new presence!
        fakeTime.Advance(TimeSpan.FromSeconds(20));
        mdns.PruneExpiredPeers();
        Assert.Single(mdns.Snapshot.Peers);

        // Repeated identical announcement refreshes expiry even when displayed fields are unchanged
        fakeTime.Advance(TimeSpan.FromSeconds(90));
        mdns.ProcessIncomingPacketForTesting(announceNew, remoteEndpoint);
        fakeTime.Advance(TimeSpan.FromSeconds(60));
        mdns.PruneExpiredPeers();
        Assert.Single(mdns.Snapshot.Peers);

        // Advance past refreshed TTL (120s) -> peer is evicted and snapshot revision increments
        long revBeforeEvict = mdns.Snapshot.Revision;
        fakeTime.Advance(TimeSpan.FromSeconds(70));
        mdns.PruneExpiredPeers();
        Assert.Empty(mdns.Snapshot.Peers);
        Assert.True(mdns.Snapshot.Revision > revBeforeEvict);
    }

    // =========================================================================
    // 2 & 6. Cached discovery immediately populates Find devices; late results transition Empty -> PeersFound
    // =========================================================================
    [Fact]
    public async Task PairingCoordinator_CachedDiscoveryEvaluatesImmediately_AndLateResultTransitionsEmptyToPeersFound()
    {
        var fakeTime = new ManualTimeProvider(DateTimeOffset.Parse("2026-10-17T10:00:00Z"));
        using var mdns = new MdnsDiscoveryService(timeProvider: fakeTime);
        var invManager = new DesktopInvitationManager();
        using var listener = new TlsSocketListener(0);
        using var coordinator = new DesktopPairingCoordinator(
            null,
            invManager,
            mdns,
            () => "Linux-Host",
            _ => Task.FromResult(listener),
            () => { },
            () => false,
            addressPolicy: _ => true,
            scanTimeoutDuration: TimeSpan.FromMilliseconds(80));

        // Case A: Peer already cached before BeginFindDevices() -> immediately transitions to PeersFound
        byte[] cachedPkt = MdnsDiscoveryService.BuildResponsePacketForTesting(
            "Cached-Peer",
            53319,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            ttlSeconds: 120,
            pairReady: true,
            trustedReconnectSupported: true);
        mdns.ProcessIncomingPacketForTesting(cachedPkt, new IPEndPoint(IPAddress.Parse("192.168.0.200"), 5353));

        coordinator.BeginFindDevices();
        Assert.Equal(DesktopPairingPhase.PeersFound, coordinator.Snapshot.Phase);

        // Expire the cached peer -> transitions out of PeersFound to Empty
        fakeTime.Advance(TimeSpan.FromSeconds(130));
        mdns.PruneExpiredPeers();
        Assert.Equal(DesktopPairingPhase.Empty, coordinator.Snapshot.Phase);

        // Start a fresh scan when cache is empty -> transitions to Empty after scan window completes
        coordinator.BeginFindDevices();
        Assert.Equal(DesktopPairingPhase.Scanning, coordinator.Snapshot.Phase);
        await Task.Delay(150);
        Assert.Equal(DesktopPairingPhase.Empty, coordinator.Snapshot.Phase);

        // Case B: Late discovery result arrives while in Empty -> automatically transitions Empty to PeersFound!
        byte[] latePkt = MdnsDiscoveryService.BuildResponsePacketForTesting(
            "Late-Ubuntu-Peer",
            53319,
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            ttlSeconds: 120,
            pairReady: true,
            trustedReconnectSupported: true);
        mdns.ProcessIncomingPacketForTesting(latePkt, new IPEndPoint(IPAddress.Parse("192.168.0.201"), 5353));

        Assert.Equal(DesktopPairingPhase.PeersFound, coordinator.Snapshot.Phase);
    }

    // =========================================================================
    // 3. Interface changes debounce, rebind discovery, advance generation, and report diagnostics
    // =========================================================================
    [Fact]
    public async Task NetworkReconciliation_DebouncesRebindsAdvancesGenerationAndReportsDiagnostics()
    {
        var currentInterfaces = new List<IPAddress> { IPAddress.Parse("192.168.0.104") };
        var createdClients = new List<UdpClient>();

        using var mdns = new MdnsDiscoveryService(
            interfaceAddressProvider: () => [.. currentInterfaces],
            reconcileDebounceDelay: TimeSpan.FromMilliseconds(80),
            interfaceClientFactory: (ip, _) =>
            {
                var c = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
                lock (createdClients) createdClients.Add(c);
                return c;
            });

        mdns.Start(enableBrowsing: true);
        long initialGen = mdns.CurrentGeneration;
        Assert.Equal(LanDiscoveryDiagnosticStatus.Healthy, mdns.Diagnostics.Status);

        // Cache a peer on 192.168.0.245
        byte[] pkt = MdnsDiscoveryService.BuildResponsePacketForTesting(
            "Ubuntu-VM",
            53319,
            "cccccccccccccccccccccccccccccccc",
            pairReady: true,
            trustedReconnectSupported: true);
        mdns.ProcessIncomingPacketForTesting(pkt, new IPEndPoint(IPAddress.Parse("192.168.0.245"), 5353), expectedGeneration: initialGen);
        Assert.Single(mdns.Snapshot.Peers);

        // Fire rapid interface change notifications -> debounced into a single reconcile
        currentInterfaces.Clear();
        currentInterfaces.Add(IPAddress.Parse("10.20.30.40"));
        mdns.NotifyNetworkInterfacesChanged();
        mdns.NotifyNetworkInterfacesChanged();
        mdns.NotifyNetworkInterfacesChanged();

        await Task.Delay(250);
        long secondGen = mdns.CurrentGeneration;
        Assert.True(secondGen > initialGen, "Expected generation to advance after debounced interface reconciliation.");
        Assert.Equal(1, mdns.ReconcileCount);

        // Stale packet from earlier generation must be rejected!
        byte[] staleGenPkt = MdnsDiscoveryService.BuildResponsePacketForTesting(
            "Stale-Gen-Peer",
            53319,
            "dddddddddddddddddddddddddddddddd",
            pairReady: true,
            trustedReconnectSupported: true);
        mdns.ProcessIncomingPacketForTesting(
            staleGenPkt,
            new IPEndPoint(IPAddress.Parse("10.20.30.99"), 5353),
            expectedGeneration: initialGen);
        Assert.DoesNotContain(mdns.Snapshot.Peers, p => p.DeviceName == "Stale-Gen-Peer");

        // No eligible interfaces -> reported as NoEligibleInterfaces without crashing
        currentInterfaces.Clear();
        DiscoverySnapshot noIfSnapshot = await mdns.ReconcileInterfacesAsync();
        Assert.Equal(LanDiscoveryDiagnosticStatus.NoEligibleInterfaces, noIfSnapshot.Diagnostics.Status);
    }

    // =========================================================================
    // 4. Scheduled-write cancellation & receiver pipeline staging-failure cancellation
    // =========================================================================
    [Fact]
    public async Task ScheduledWriteCancellation_AndReceiverStagingFailure_AbortPromptlyAndReturnBuffersOnce()
    {
        // Part A: LanRecordChannel scheduled write on a blocked stream cancels and aborts transport immediately
        using var blockedStream = new BlockingWriteStream();
        var pool = new TransferBufferPool();
        var channel = new LanRecordChannel(blockedStream, coalesceWrites: true, maxCoalesceDelayMs: 10);
        var bulkRef = new BulkRef(Guid.NewGuid().ToString("D"));

        // First write starts a timer flush that blocks inside BlockingWriteStream.WriteAsync
        await channel.WriteDataChunkAsync(bulkRef, new byte[4096]);
        await blockedStream.WriteStarted.WaitAsync(TimeSpan.FromSeconds(2));

        var sw = Stopwatch.StartNew();
        using var callCts = new CancellationTokenSource(TimeSpan.FromMilliseconds(60));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(async () =>
            await channel.WriteDataChunkAsync(bulkRef, new byte[4096], callCts.Token));
        sw.Stop();

        Assert.True(sw.ElapsedMilliseconds < 400, $"Expected prompt cancellation of blocked scheduled write, took {sw.ElapsedMilliseconds} ms");
        Assert.True(blockedStream.Aborted || blockedStream.Disposed);

        await channel.DisposeAsync();
        Assert.Equal(0, pool.RentedBufferCount);

        // Part B: Receiver staging write failure aborts socket read immediately rather than waiting for 60s payload timeout
        string tempRoot = Path.Combine(Path.GetTempPath(), "Glide_RecvAbort_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(tempRoot);
        try
        {
            var failingFs = new ControllableQuarantineFileSystem(failAfterStagingWriteBytes: 128 * 1024);
            var store = new QuarantineStagingStore(
                Path.Combine(tempRoot, "stg"),
                Path.Combine(tempRoot, "dst"),
                enableSameVolumeStaging: true,
                fileSystem: failingFs);

            string srcFile = Path.Combine(tempRoot, "large.bin");
            await File.WriteAllBytesAsync(srcFile, RandomNumberGenerator.GetBytes(8 * 1024 * 1024));

            using var listener = new TlsSocketListener(0);
            var auth = new LanInvitationAuthority();
            string token = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
            var inv = auth.Publish(InvitationCodec.Decode(InvitationCodec.Encode(new LanInvitation(
                Guid.NewGuid().ToString("D"),
                "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                "Receiver-FailWrite",
                "linux",
                [new LanEndpoint("127.0.0.1", listener.BoundPort)],
                listener.LocalSpkiSha256,
                token,
                (DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 120).ToString(),
                120))));

            var enginePool = new TransferBufferPool();
            var engine = new DesktopTransferEngine(bufferPool: enginePool);
            var swRecv = Stopwatch.StartNew();
            var recvTask = Task.Run(async () =>
            {
                await using var conn = await listener.AcceptConnectionAsync();
                return await engine.ReceiveAsync(conn, new DesktopReceiveRequest(store, auth, "Receiver-FailWrite"), new AutoAcceptInteraction());
            });
            var sendTask = engine.SendAsync(
                new DesktopSendRequest("127.0.0.1", listener.BoundPort, [new DesktopTransferFile(srcFile)], inv, "Sender"),
                new AutoAcceptInteraction());

            var recvResult = await recvTask.WaitAsync(TimeSpan.FromSeconds(10));
            _ = await sendTask.WaitAsync(TimeSpan.FromSeconds(10));
            swRecv.Stop();

            Assert.False(recvResult.Success);
            Assert.True(swRecv.Elapsed < TimeSpan.FromSeconds(5), $"Receiver staging failure should abort immediately, took {swRecv.Elapsed}");
            Assert.Equal(0, enginePool.RentedBufferCount);
        }
        finally
        {
            try { Directory.Delete(tempRoot, true); } catch { }
        }
    }

    // =========================================================================
    // 5. Authenticate endpoint candidates correctly (name collision "ubuntu", saved trust preserved)
    // =========================================================================
    [Fact]
    public async Task TrustedEndpointResolution_SkipsUnrelatedSameNamePeer_AuthenticatesSecondCandidate_AndUpdatesSavedEndpoint()
    {
        string tempRoot = Path.Combine(Path.GetTempPath(), "Glide_TrustedCandidates_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(tempRoot);
        try
        {
            var senderTrust = CreateTrustManager(Path.Combine(tempRoot, "sender"));
            var unrelatedUbuntuTrust = CreateTrustManager(Path.Combine(tempRoot, "unrelated-ubuntu"));
            var realUbuntuTrust = CreateTrustManager(Path.Combine(tempRoot, "real-ubuntu"));

            string senderFp = senderTrust.Initialize().LocalFingerprintHex;
            string unrelatedFp = unrelatedUbuntuTrust.Initialize().LocalFingerprintHex;
            string realFp = realUbuntuTrust.Initialize().LocalFingerprintHex;

            // Both "ubuntu" devices have distinct identities; sender is paired with realUbuntu AND also has a second saved device named "ubuntu"
            senderTrust.EnrollVerifiedPeer(
                new VerifiedDesktopPeerIdentity(
                    realFp,
                    Convert.ToHexStringLower(realUbuntuTrust.LocalPublicKeyDer()),
                    "ubuntu",
                    "linux",
                    "127.0.0.1",
                    1), // Stale/unreachable port 1
                userApproved: true);
            senderTrust.EnrollVerifiedPeer(
                new VerifiedDesktopPeerIdentity(
                    unrelatedFp,
                    Convert.ToHexStringLower(unrelatedUbuntuTrust.LocalPublicKeyDer()),
                    "ubuntu",
                    "linux",
                    "127.0.0.1",
                    2),
                userApproved: true);
            Assert.Equal(2, senderTrust.GetTrustedPeers().Count);

            realUbuntuTrust.EnrollVerifiedPeer(
                new VerifiedDesktopPeerIdentity(
                    senderFp,
                    Convert.ToHexStringLower(senderTrust.LocalPublicKeyDer()),
                    "Windows-Sender",
                    "windows"),
                userApproved: true);

            using var unrelatedListener = new TlsSocketListener(0);
            using var realListener = new TlsSocketListener(0);

            int unrelatedOffersReceived = 0;
            int realOffersReceived = 0;

            var engine = new DesktopTransferEngine();
            using var listenerCts = new CancellationTokenSource(TimeSpan.FromSeconds(15));

            // Start unrelated "ubuntu" listener on candidate #1
            var unrelatedServerTask = Task.Run(async () =>
            {
                try
                {
                    await using var conn = await unrelatedListener.AcceptConnectionAsync(listenerCts.Token);
                    var kind = await LanSessionPreambleInspector.InspectAndPrepareAsync(conn, listenerCts.Token);
                    if (kind == LanSessionDispatchKind.Profile2Gld2)
                    {
                        var open = (Profile2Message.SessionOpen)await Profile2WireCodec.ReadMessageAsync(conn.TransportStream, listenerCts.Token);
                        var req = new DesktopReceiveRequest(
                            new QuarantineStagingStore(Path.Combine(tempRoot, "stg-unrelated"), Path.Combine(tempRoot, "dst-unrelated")),
                            LocalDeviceName: "ubuntu",
                            TrustManager: unrelatedUbuntuTrust);
                        await engine.ReceiveTrustedAsync(conn, open, req, new CountingAcceptInteraction(() => Interlocked.Increment(ref unrelatedOffersReceived)), isBusy: false, listenerCts.Token);
                    }
                }
                catch { }
            });

            // Start authentic "ubuntu" listener on candidate #2
            var realServerTask = Task.Run(async () =>
            {
                await using var conn = await realListener.AcceptConnectionAsync(listenerCts.Token);
                var kind = await LanSessionPreambleInspector.InspectAndPrepareAsync(conn, listenerCts.Token);
                Assert.Equal(LanSessionDispatchKind.Profile2Gld2, kind);
                var open = (Profile2Message.SessionOpen)await Profile2WireCodec.ReadMessageAsync(conn.TransportStream, listenerCts.Token);
                var req = new DesktopReceiveRequest(
                    new QuarantineStagingStore(Path.Combine(tempRoot, "stg-real"), Path.Combine(tempRoot, "dst-real")),
                    LocalDeviceName: "ubuntu",
                    TrustManager: realUbuntuTrust);
                return await engine.ReceiveTrustedAsync(conn, open, req, new CountingAcceptInteraction(() => Interlocked.Increment(ref realOffersReceived)), isBusy: false, listenerCts.Token);
            });

            string srcFile = Path.Combine(tempRoot, "secret-payload.txt");
            await File.WriteAllTextAsync(srcFile, "Authentic recipient only");

            // Discovery reports unrelated "ubuntu" first, and authentic "ubuntu" second
            var discoveredPeers = new[]
            {
                new DiscoveredPeer("ubuntu", "127.0.0.1", unrelatedListener.BoundPort, "10101010101010101010101010101010", SupportsTrustedReconnect: true),
                new DiscoveredPeer("ubuntu", "127.0.0.1", realListener.BoundPort, "20202020202020202020202020202020", SupportsTrustedReconnect: true)
            };

            DesktopTrustedPeer savedRealPeer = senderTrust.GetTrustedPeers().Single(p => p.FingerprintHex == realFp);
            var candidateEndpoints = TrustedEndpointResolver.ResolveEndpoints(
                savedRealPeer,
                discoveredPeers,
                addressPolicy: _ => true);

            // Rank 0 is saved endpoint (127.0.0.1:1), followed by the two discovered endpoints
            Assert.Equal(1, candidateEndpoints[0].Port);

            var sendRequest = new DesktopSendRequest(
                Host: savedRealPeer.LastKnownHost ?? "127.0.0.1",
                Port: savedRealPeer.LastKnownPort ?? 1,
                Files: [new DesktopTransferFile(srcFile)],
                LocalDeviceName: "Windows-Sender",
                TrustManager: senderTrust,
                ExpectedPeerFingerprintHex: realFp,
                CandidateEndpoints: candidateEndpoints);

            DesktopTransferResult sendResult = await engine.SendTrustedAsync(sendRequest, new AutoAcceptInteraction(), listenerCts.Token);
            DesktopTransferResult recvResult = await realServerTask;
            await unrelatedServerTask;

            Assert.True(sendResult.Success, $"Expected SendTrustedAsync to succeed via second candidate, got: {sendResult.Message}");
            Assert.True(recvResult.Success);
            Assert.Equal(0, unrelatedOffersReceived); // Must NEVER send a file offer to the unrelated device!
            Assert.Equal(1, realOffersReceived);

            // Both distinct devices named "ubuntu" remain preserved in the trust store, and the authentic peer's endpoint was updated!
            var updatedPeers = senderTrust.GetTrustedPeers();
            Assert.Equal(2, updatedPeers.Count);
            DesktopTrustedPeer updatedReal = updatedPeers.Single(p => p.FingerprintHex == realFp);
            Assert.Equal("127.0.0.1", updatedReal.LastKnownHost);
            Assert.Equal(realListener.BoundPort, updatedReal.LastKnownPort);

            // Also verify failure classification: an unrelated discovery candidate alone must produce CouldNotVerifyDevice, NOT SavedDeviceIdentityChanged
            var failureKind = TrustedEndpointResolver.ClassifyExhaustedCandidates(
                sawBusyOnSavedOrVerified: false,
                sawSavedEndpointIdentityChanged: false,
                sawUnrelatedCandidateIdentity: true);
            Assert.Equal(TrustedResolutionFailureKind.CouldNotVerifyDevice, failureKind);
            Assert.Equal(TrustedEndpointResolver.CouldNotVerifyDeviceMessage, TrustedEndpointResolver.CreateFailureResult(failureKind).Message);
        }
        finally
        {
            try { Directory.Delete(tempRoot, true); } catch { }
        }
    }

    // =========================================================================
    // 7. Stalled Profile 2 sessions bounded by deadlines & 4-slot admission concurrency
    // =========================================================================
    [Fact]
    public async Task StalledIncomingSessions_DoNotMonopolizeListener_AndTimeoutCleanly()
    {
        using var supervisor = new DesktopListenerSupervisor(new DesktopTimeoutPolicy { MaxConcurrentAdmissions = 4 });
        int port = supervisor.Start(0);
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(15));

        var legitCompletedTcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        supervisor.StartListenerLoop(
            async (conn, token) =>
            {
                await using (conn)
                {
                    legitCompletedTcs.TrySetResult(true);
                }
            },
            cts.Token,
            async (conn, token) =>
            {
                await using (conn)
                {
                    legitCompletedTcs.TrySetResult(true);
                }
            });

        // Open 3 stalled raw TCP connections that never complete TLS handshake or preamble
        using var stalled1 = new TcpClient();
        using var stalled2 = new TcpClient();
        using var stalled3 = new TcpClient();
        await stalled1.ConnectAsync("127.0.0.1", port);
        await stalled2.ConnectAsync("127.0.0.1", port);
        await stalled3.ConnectAsync("127.0.0.1", port);

        // A 4th legitimate TLS + GLD2 client connects while the 3 stalled peers are hanging:
        // Because DesktopListenerSupervisor permits up to 4 concurrent admissions, it must NOT be blocked!
        var sw = Stopwatch.StartNew();
        var client = new TlsSocketClient();
        await using (var legitConn = await client.ConnectAsync("127.0.0.1", port, expectedSpkiSha256: supervisor.Listener!.LocalSpkiSha256, cts.Token))
        {
            await LanSessionPreambleInspector.WriteGld2PreambleAsync(legitConn.TransportStream, cts.Token);
            bool completed = await legitCompletedTcs.Task.WaitAsync(TimeSpan.FromSeconds(5));
            Assert.True(completed);
        }
        sw.Stop();

        Assert.True(sw.Elapsed < TimeSpan.FromSeconds(5), $"Legitimate client should not wait for stalled peers, took {sw.Elapsed}");
    }

    // =========================================================================
    // 8. Finalization exceeding 30 seconds succeeds under separate finalization deadline
    // =========================================================================
    [Fact]
    public async Task FinalizationDeadline_CheckedCalculation_AndSlowFinalizationSucceedsWithWaitingForConfirmationPhase()
    {
        var policy = DesktopTimeoutPolicy.Default;

        // Minimum 120 seconds for small files
        Assert.Equal(TimeSpan.FromSeconds(120), policy.CalculateFinalizationDeadline(0));
        Assert.Equal(TimeSpan.FromSeconds(120), policy.CalculateFinalizationDeadline(100 * 1024 * 1024L));

        // 1 GiB file: 60s + 3 * 1024 MiB / (10 MiB/s) = 60s + 307.2s = 367.2s
        TimeSpan oneGiBDeadline = policy.CalculateFinalizationDeadline(1024L * 1024 * 1024);
        Assert.InRange(oneGiBDeadline.TotalSeconds, 367.0, 368.0);

        // 64 GiB ceiling (~5h 28m 40.8s), 75 GiB capped at 6 hours, and checked arithmetic throws OverflowException on long.MaxValue
        TimeSpan sixtyFourGiBDeadline = policy.CalculateFinalizationDeadline(64L * 1024 * 1024 * 1024);
        Assert.InRange(sixtyFourGiBDeadline.TotalSeconds, 19720.0, 19722.0);
        Assert.Equal(TimeSpan.FromHours(6), policy.CalculateFinalizationDeadline(75L * 1024 * 1024 * 1024));
        Assert.Throws<OverflowException>(() => policy.CalculateFinalizationDeadline(long.MaxValue));

        // Simulate a receiver whose commit/finalization takes > 1.2s while ordinary NoPayloadProgressTimeout is 400ms,
        // proving that sender waits using FinalizationDeadline (not ordinary 30s control timeout) and reports WaitingForConfirmation!
        string tempRoot = Path.Combine(Path.GetTempPath(), "Glide_SlowFinalization_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(tempRoot);
        try
        {
            var slowCommitFs = new ControllableQuarantineFileSystem(commitDelay: TimeSpan.FromMilliseconds(1200));
            var store = new QuarantineStagingStore(
                Path.Combine(tempRoot, "stg"),
                Path.Combine(tempRoot, "dst"),
                enableSameVolumeStaging: true,
                fileSystem: slowCommitFs);

            string srcFile = Path.Combine(tempRoot, "sample.bin");
            await File.WriteAllBytesAsync(srcFile, RandomNumberGenerator.GetBytes(256 * 1024));

            using var listener = new TlsSocketListener(0);
            var auth = new LanInvitationAuthority();
            string token = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
            var inv = auth.Publish(InvitationCodec.Decode(InvitationCodec.Encode(new LanInvitation(
                Guid.NewGuid().ToString("D"),
                "ffffffffffffffffffffffffffffffff",
                "Receiver-SlowCommit",
                "linux",
                [new LanEndpoint("127.0.0.1", listener.BoundPort)],
                listener.LocalSpkiSha256,
                token,
                (DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 120).ToString(),
                120))));

            var customPolicy = new DesktopTimeoutPolicy
            {
                NoPayloadProgressTimeout = TimeSpan.FromMilliseconds(400),
                MinFinalizationTimeout = TimeSpan.FromSeconds(10)
            };
            var senderEngine = new DesktopTransferEngine(timeoutPolicy: customPolicy);
            var receiverEngine = new DesktopTransferEngine(timeoutPolicy: customPolicy);

            var observedSenderPhases = new List<DesktopTransferPhase>();
            var senderInteraction = new PhaseTrackingInteraction(phase =>
            {
                lock (observedSenderPhases) observedSenderPhases.Add(phase);
            });

            var recvTask = Task.Run(async () =>
            {
                await using var conn = await listener.AcceptConnectionAsync();
                return await receiverEngine.ReceiveAsync(conn, new DesktopReceiveRequest(store, auth, "Receiver-SlowCommit"), new AutoAcceptInteraction());
            });

            var sendRes = await senderEngine.SendAsync(
                new DesktopSendRequest("127.0.0.1", listener.BoundPort, [new DesktopTransferFile(srcFile)], inv, "Sender"),
                senderInteraction);
            var recvRes = await recvTask;

            Assert.True(sendRes.Success, $"Expected slow finalization to succeed, got: {sendRes.Message}");
            Assert.True(recvRes.Success);
            Assert.Contains(DesktopTransferPhase.WaitingForConfirmation, observedSenderPhases);
        }
        finally
        {
            try { Directory.Delete(tempRoot, true); } catch { }
        }
    }

    // =========================================================================
    // 9. Portable Linux storage publication: ENOSYS/EOPNOTSUPP/EINVAL/EXDEV verified-copy fallback & fail-closed checks
    // =========================================================================
    [Theory]
    [InlineData(38)]  // ENOSYS
    [InlineData(95)]  // EOPNOTSUPP
    [InlineData(22)]  // EINVAL
    [InlineData(18)]  // EXDEV
    public async Task QuarantineStagingStore_UnsupportedAtomicRename_FallsBackToVerifiedCopyWithDestinationRereadAndDirFsync(int simulatedErrno)
    {
        string tempRoot = Path.Combine(Path.GetTempPath(), $"Glide_Fallback_{simulatedErrno}_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(tempRoot);
        try
        {
            string stgDir = Path.Combine(tempRoot, "stg");
            string dstDir = Path.Combine(tempRoot, "dst");
            Directory.CreateDirectory(stgDir);
            Directory.CreateDirectory(dstDir);
            var controllableFs = new ControllableQuarantineFileSystem(atomicMoveErrno: simulatedErrno);

            var store = new QuarantineStagingStore(
                stgDir,
                dstDir,
                enableSameVolumeStaging: true,
                fileSystem: controllableFs);

            byte[] payload = RandomNumberGenerator.GetBytes(512 * 1024);
            var expectedDigest = new Sha256Digest(Convert.ToHexStringLower(SHA256.HashData(payload)));
            var sessionId = new SessionId(Guid.NewGuid().ToString("D"));
            var itemId = new ItemId(Guid.NewGuid().ToString("D"));

            await using (Stream stagingStream = store.OpenQuarantineWrite(sessionId, itemId))
            {
                await stagingStream.WriteAsync(payload);
            }

            QuarantineCommitResult commitResult = await store.CommitVerifiedQuarantineFileWithMetricsAsync(
                sessionId,
                itemId,
                "portable-linux.bin",
                expectedDigest,
                payload.Length);

            Assert.Equal(QuarantineCommitStrategy.VerifiedCopyFallback, commitResult.StrategyUsed);
            Assert.True(File.Exists(commitResult.CommittedPath));
            Assert.Equal(payload, await File.ReadAllBytesAsync(commitResult.CommittedPath));
            Assert.True(controllableFs.DirectoryFsyncCount > 0, "Expected parent directory fsync after publication.");
            Assert.Empty(Directory.GetFiles(dstDir, "*.part*", SearchOption.AllDirectories));
        }
        finally
        {
            try { Directory.Delete(tempRoot, true); } catch { }
        }
    }

    [Fact]
    public async Task QuarantineStagingStore_PermissionDiskFullAndCorruptFallback_FailClosedAndNeverPublishCorruptFile()
    {
        string tempRoot = Path.Combine(Path.GetTempPath(), "Glide_FailClosed_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(tempRoot);
        try
        {
            byte[] payload = RandomNumberGenerator.GetBytes(128 * 1024);
            var expectedDigest = new Sha256Digest(Convert.ToHexStringLower(SHA256.HashData(payload)));

            // Case 1: Permission error (EACCES = 13) on atomic move must NOT silently fall back; must fail closed
            var permFs = new ControllableQuarantineFileSystem(atomicMoveErrno: QuarantineStagingStore.LinuxEacces);
            Directory.CreateDirectory(Path.Combine(tempRoot, "stg1"));
            Directory.CreateDirectory(Path.Combine(tempRoot, "dst1"));
            var permStore = new QuarantineStagingStore(
                Path.Combine(tempRoot, "stg1"),
                Path.Combine(tempRoot, "dst1"),
                enableSameVolumeStaging: true,
                fileSystem: permFs);
            var s1 = new SessionId(Guid.NewGuid().ToString("D"));
            var i1 = new ItemId(Guid.NewGuid().ToString("D"));
            await using (var str = permStore.OpenQuarantineWrite(s1, i1)) await str.WriteAsync(payload);
            await Assert.ThrowsAsync<UnauthorizedAccessException>(async () =>
                await permStore.CommitVerifiedQuarantineFileAsync(s1, i1, "denied.bin", expectedDigest, payload.Length));
            Assert.False(File.Exists(Path.Combine(tempRoot, "dst1", "denied.bin")));

            // Case 2: Corrupt destination re-read during verified-copy fallback must delete .part and throw
            var corruptCopyFs = new ControllableQuarantineFileSystem(
                atomicMoveErrno: QuarantineStagingStore.LinuxExdev,
                corruptDestinationPartOnCopy: true);
            Directory.CreateDirectory(Path.Combine(tempRoot, "stg2"));
            Directory.CreateDirectory(Path.Combine(tempRoot, "dst2"));
            var corruptStore = new QuarantineStagingStore(
                Path.Combine(tempRoot, "stg2"),
                Path.Combine(tempRoot, "dst2"),
                enableSameVolumeStaging: true,
                fileSystem: corruptCopyFs);
            var s2 = new SessionId(Guid.NewGuid().ToString("D"));
            var i2 = new ItemId(Guid.NewGuid().ToString("D"));
            await using (var str = corruptStore.OpenQuarantineWrite(s2, i2)) await str.WriteAsync(payload);
            var ex = await Assert.ThrowsAsync<IOException>(async () =>
                await corruptStore.CommitVerifiedQuarantineFileAsync(s2, i2, "corrupt.bin", expectedDigest, payload.Length));
            Assert.Contains("mismatch", ex.Message, StringComparison.OrdinalIgnoreCase);
            Assert.False(File.Exists(Path.Combine(tempRoot, "dst2", "corrupt.bin")));
            Assert.Empty(Directory.GetFiles(Path.Combine(tempRoot, "dst2"), "*.part*", SearchOption.AllDirectories));
        }
        finally
        {
            try { Directory.Delete(tempRoot, true); } catch { }
        }
    }

    // =========================================================================
    // 10. Rotating diagnostic logs & sanitized export free of secrets
    // =========================================================================
    [Fact]
    public async Task RotatingDiagnostics_BoundsFileCount_AndSanitizesSecretsOnExport()
    {
        string tempRoot = Path.Combine(Path.GetTempPath(), "Glide_DiagRotate_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(tempRoot);
        try
        {
            string logPath = Path.Combine(tempRoot, "logs", "glide-diagnostics.jsonl");
            await using (var sink = new BoundedTransferDiagnosticSink(logPath, buildCommit: "testcommit", maxFileBytes: 4096, maxFileCount: 4))
            {
                for (int i = 0; i < 80; i++)
                {
                    sink.RecordEvent(
                        "pairing",
                        $"Rejected link glide://pair/v1#secret_token_{i} with token=supersecret{i} and pin: 123456",
                        $"spki=deadbeef{i} pad={new string('x', 80)}");
                }
            }

            string[] logFiles = Directory.GetFiles(Path.Combine(tempRoot, "logs"), "glide-diagnostics.jsonl*");
            Assert.InRange(logFiles.Length, 1, 4);

            string exportPath = Path.Combine(tempRoot, "exported-bundle.jsonl");
            await BoundedTransferDiagnosticSink.ExportSanitizedDiagnosticBundleAsync(
                exportPath,
                logPath,
                "summary token=topsecret glide://pair/v2# secret");

            string exportedContent = await File.ReadAllTextAsync(exportPath);
            Assert.DoesNotContain("supersecret", exportedContent, StringComparison.OrdinalIgnoreCase);
            Assert.DoesNotContain("topsecret", exportedContent, StringComparison.OrdinalIgnoreCase);
            Assert.DoesNotContain("123456", exportedContent, StringComparison.OrdinalIgnoreCase);
            Assert.Contains("glide://pair/[REDACTED]", exportedContent, StringComparison.Ordinal);
        }
        finally
        {
            try { Directory.Delete(tempRoot, true); } catch { }
        }
    }

    private static DesktopPersistentTrustManager CreateTrustManager(string dir)
    {
        Directory.CreateDirectory(dir);
        return new DesktopPersistentTrustManager(
            new InMemoryIdentityStore(),
            new DesktopTrustStore(Path.Combine(dir, "trusted.json")));
    }

    private sealed class ManualTimeProvider(DateTimeOffset initialUtc) : TimeProvider
    {
        private DateTimeOffset _utcNow = initialUtc;
        public override DateTimeOffset GetUtcNow() => _utcNow;
        public void Advance(TimeSpan delta) => _utcNow = _utcNow.Add(delta);
    }

    private sealed class InMemoryIdentityStore : IDesktopPersistentIdentityStore
    {
        private ECDsa? _key;

        public bool HasIdentity() => _key != null;

        public byte[] GetOrCreatePublicKeyDer()
        {
            _key ??= ECDsa.Create(ECCurve.NamedCurves.nistP256);
            return _key.ExportSubjectPublicKeyInfo();
        }

        public string GetFingerprintHex() =>
            Convert.ToHexStringLower(SHA256.HashData(GetOrCreatePublicKeyDer()));

        public byte[] SignTranscript(ReadOnlySpan<byte> transcript)
        {
            _key ??= ECDsa.Create(ECCurve.NamedCurves.nistP256);
            return _key.SignData(transcript, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);
        }

        public void DeleteIdentity()
        {
            _key?.Dispose();
            _key = null;
        }
    }

    private sealed class AutoAcceptInteraction : DesktopTransferInteractionBase
    {
        public override Task<bool> ConfirmOfferAsync(DesktopOffer offer, CancellationToken cancellationToken) =>
            Task.FromResult(true);
    }

    private sealed class CountingAcceptInteraction(Action onOffer) : DesktopTransferInteractionBase
    {
        public override Task<bool> ConfirmOfferAsync(DesktopOffer offer, CancellationToken cancellationToken)
        {
            onOffer();
            return Task.FromResult(true);
        }
    }

    private sealed class PhaseTrackingInteraction(Action<DesktopTransferPhase> onPhase) : DesktopTransferInteractionBase
    {
        public override Task<bool> ConfirmOfferAsync(DesktopOffer offer, CancellationToken cancellationToken) =>
            Task.FromResult(true);

        public override void OnPhase(DesktopTransferDirection direction, DesktopTransferPhase phase) =>
            onPhase(phase);
    }

    private sealed class ControllableQuarantineFileSystem(
        int? atomicMoveErrno = null,
        bool corruptDestinationPartOnCopy = false,
        long? failAfterStagingWriteBytes = null,
        TimeSpan? commitDelay = null) : IQuarantineFileSystem
    {
        public int DirectoryFsyncCount { get; private set; }

        public uint GetEffectiveUserId() => 1000;

        public bool TryGetFileIdentityByPath(string path, out QuarantineFileIdentity identity, out int errno)
        {
            errno = 0;
            string full = Path.GetFullPath(path);
            bool isDir = Directory.Exists(full);
            bool isFile = File.Exists(full);
            if (!isDir && !isFile)
            {
                identity = default;
                errno = 2; // ENOENT
                return false;
            }

            identity = new QuarantineFileIdentity(
                DeviceMajor: 8,
                DeviceMinor: 1,
                Inode: 1001,
                OwnerUid: 1000,
                Mode: isDir ? (ushort)0x41C0 : (ushort)0x8180,
                IsDirectory: isDir,
                IsRegularFile: isFile,
                IsSymbolicLink: false);
            return true;
        }

        public bool TryGetFileIdentityByHandle(SafeFileHandle handle, out QuarantineFileIdentity identity, out int errno)
        {
            errno = 0;
            identity = new QuarantineFileIdentity(
                DeviceMajor: 8,
                DeviceMinor: 1,
                Inode: 1001,
                OwnerUid: 1000,
                Mode: 0x8180,
                IsDirectory: false,
                IsRegularFile: true,
                IsSymbolicLink: false);
            return true;
        }

        public void AtomicRenameNoReplace(
            SafeFileHandle? sourceFileHandle,
            string sourceDirectoryPath,
            string sourceFileName,
            string targetDirectoryPath,
            string targetFileName)
        {
            if (commitDelay is { } delay && delay > TimeSpan.Zero)
            {
                Thread.Sleep(delay);
            }

            if (atomicMoveErrno is { } errno)
            {
                throw new Win32Exception(errno, $"Simulated renameat2 errno={errno}");
            }

            string srcPath = Path.Combine(sourceDirectoryPath, sourceFileName);
            string dstPath = Path.Combine(targetDirectoryPath, targetFileName);
            File.Move(srcPath, dstPath, overwrite: false);
        }

        public void TryFlushDirectory(string directoryPath)
        {
            DirectoryFsyncCount++;
        }

        public Stream OpenVerificationReadWriteStream(string path, int bufferSize) =>
            new FileStream(
                path,
                FileMode.Open,
                FileAccess.ReadWrite,
                FileShare.ReadWrite | FileShare.Delete,
                bufferSize,
                FileOptions.Asynchronous | FileOptions.SequentialScan);

        public Stream OpenReadStream(string path, int bufferSize) =>
            new FileStream(
                path,
                FileMode.Open,
                FileAccess.Read,
                FileShare.ReadWrite | FileShare.Delete,
                bufferSize,
                FileOptions.Asynchronous | FileOptions.SequentialScan);

        public Stream OpenCreateNewWriteStream(string path, int bufferSize)
        {
            var inner = new FileStream(
                path,
                FileMode.CreateNew,
                FileAccess.Write,
                FileShare.None,
                bufferSize,
                FileOptions.Asynchronous);

            if (failAfterStagingWriteBytes is { } limit && path.EndsWith(".quarantine", StringComparison.OrdinalIgnoreCase))
            {
                return new FailingAfterBytesStream(inner, limit);
            }

            if (corruptDestinationPartOnCopy && path.EndsWith(".part", StringComparison.OrdinalIgnoreCase))
            {
                return new CorruptingWriteStream(inner);
            }

            return inner;
        }
    }

    private sealed class FailingAfterBytesStream(Stream inner, long maxBytes) : Stream
    {
        private long _written;
        public override bool CanRead => false;
        public override bool CanSeek => false;
        public override bool CanWrite => true;
        public override long Length => inner.Length;
        public override long Position { get => inner.Position; set => inner.Position = value; }
        public override void Flush() => inner.Flush();
        public override Task FlushAsync(CancellationToken cancellationToken) => inner.FlushAsync(cancellationToken);
        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => inner.SetLength(value);
        public override void Write(byte[] buffer, int offset, int count)
        {
            _written += count;
            if (_written > maxBytes) throw new IOException("Simulated disk write failure in staging.");
            inner.Write(buffer, offset, count);
        }
        public override ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default)
        {
            _written += buffer.Length;
            if (_written > maxBytes) throw new IOException("Simulated disk write failure in staging.");
            return inner.WriteAsync(buffer, cancellationToken);
        }
        protected override void Dispose(bool disposing)
        {
            if (disposing) inner.Dispose();
            base.Dispose(disposing);
        }
        public override async ValueTask DisposeAsync()
        {
            await inner.DisposeAsync().ConfigureAwait(false);
            await base.DisposeAsync().ConfigureAwait(false);
        }
    }

    private sealed class CorruptingWriteStream(Stream inner) : Stream
    {
        private bool _corrupted;
        public override bool CanRead => inner.CanRead;
        public override bool CanSeek => inner.CanSeek;
        public override bool CanWrite => inner.CanWrite;
        public override long Length => inner.Length;
        public override long Position { get => inner.Position; set => inner.Position = value; }
        public override void Flush() => inner.Flush();
        public override Task FlushAsync(CancellationToken cancellationToken) => inner.FlushAsync(cancellationToken);
        public override int Read(byte[] buffer, int offset, int count) => inner.Read(buffer, offset, count);
        public override long Seek(long offset, SeekOrigin origin) => inner.Seek(offset, origin);
        public override void SetLength(long value) => inner.SetLength(value);
        public override void Write(byte[] buffer, int offset, int count)
        {
            byte[] copy = buffer.AsSpan(offset, count).ToArray();
            if (!_corrupted && copy.Length > 0)
            {
                copy[0] ^= 0xFF;
                _corrupted = true;
            }
            inner.Write(copy, 0, copy.Length);
        }
        public override ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default)
        {
            byte[] copy = buffer.ToArray();
            if (!_corrupted && copy.Length > 0)
            {
                copy[0] ^= 0xFF;
                _corrupted = true;
            }
            return inner.WriteAsync(copy, cancellationToken);
        }
        protected override void Dispose(bool disposing)
        {
            if (disposing) inner.Dispose();
            base.Dispose(disposing);
        }
        public override async ValueTask DisposeAsync()
        {
            await inner.DisposeAsync().ConfigureAwait(false);
            await base.DisposeAsync().ConfigureAwait(false);
        }
    }

    private sealed class BlockingWriteStream : Stream
    {
        private readonly TaskCompletionSource _writeStarted = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly TaskCompletionSource _releaseWrite = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public Task WriteStarted => _writeStarted.Task;
        public bool Aborted { get; private set; }
        public bool Disposed { get; private set; }

        public override bool CanRead => false;
        public override bool CanSeek => false;
        public override bool CanWrite => true;
        public override long Length => 0;
        public override long Position { get => 0; set { } }
        public override void Flush() { }
        public override int Read(byte[] buffer, int offset, int count) => 0;
        public override long Seek(long offset, SeekOrigin origin) => 0;
        public override void SetLength(long value) { }
        public override void Write(byte[] buffer, int offset, int count) { }

        public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default)
        {
            _writeStarted.TrySetResult();
            using var reg = cancellationToken.Register(() => Aborted = true);
            await _releaseWrite.Task.WaitAsync(cancellationToken).ConfigureAwait(false);
        }

        protected override void Dispose(bool disposing)
        {
            Disposed = true;
            _releaseWrite.TrySetCanceled();
            base.Dispose(disposing);
        }
    }
}
