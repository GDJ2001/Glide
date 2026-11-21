using System.ComponentModel;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using Glide.Core.Codec;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Core.Transport.Lan;
using Glide.Desktop;
using Glide.Desktop.Services;
using Glide.Transport.Discovery;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;
using Microsoft.Win32.SafeHandles;
using GlideAppProgram = Glide.App.Program;

namespace Glide.Tests;

[Collection("HARD-003 console/runtime")]
public sealed class Stage1And2CharacterizationTests : IDisposable
{
    private readonly string _tempRoot;
    private readonly List<DesktopPersistentIdentityStore> _identityStores = [];

    public Stage1And2CharacterizationTests()
    {
        _tempRoot = Path.Combine(
            Path.GetTempPath(),
            "GlideStage12Char_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_tempRoot);
    }

    public void Dispose()
    {
        foreach (DesktopPersistentIdentityStore store in _identityStores)
        {
            try { store.DeleteIdentity(); } catch { }
        }
        try
        {
            if (Directory.Exists(_tempRoot))
            {
                Directory.Delete(_tempRoot, recursive: true);
            }
        }
        catch
        {
            // Ignore cleanup errors
        }
    }

    private DesktopPersistentIdentityStore CreateIdentityStore(string directory)
    {
        string uniqueName = $"Glide.Stage12.{Path.GetFileName(_tempRoot)}.{Path.GetFileName(directory)}";
        var store = new DesktopPersistentIdentityStore(
            Path.Combine(directory, "identity-v1.pk8"),
            windowsKeyName: uniqueName);
        _identityStores.Add(store);
        return store;
    }

    [Fact]
    public async Task QuarantineStagingStore_WatchdogExpiry_PropagatesRawTimeoutException()
    {
        string stagingDir = Path.Combine(_tempRoot, "wd_staging");
        string destDir = Path.Combine(_tempRoot, "wd_dest");
        Directory.CreateDirectory(stagingDir);
        Directory.CreateDirectory(destDir);

        var stalledFs = new StalledVerificationFileSystem(TimeSpan.FromMilliseconds(350));
        var store = new QuarantineStagingStore(
            stagingDir,
            destDir,
            enableSameVolumeStaging: true,
            fileSystem: stalledFs);

        var sessionId = SessionId.NewRandom();
        var itemId = ItemId.NewRandom();
        byte[] payload = RandomNumberGenerator.GetBytes(64 * 1024);
        var digest = new Sha256Digest(Convert.ToHexStringLower(SHA256.HashData(payload)));

        await using (Stream writeStream = store.OpenQuarantineWrite(sessionId, itemId))
        {
            await writeStream.WriteAsync(payload);
        }

        await Assert.ThrowsAsync<TimeoutException>(async () =>
        {
            await store.CommitVerifiedQuarantineFileWithMetricsAsync(
                sessionId,
                itemId,
                "stalled.bin",
                digest,
                payload.Length,
                storageProgressTimeout: TimeSpan.FromMilliseconds(60));
        });
    }

    [Fact]
    public async Task QuarantineStagingStore_PreVerifiedExclusiveSession_CommitsWithoutExtraFullFileRead()
    {
        string stagingDir = Path.Combine(_tempRoot, "fast_staging");
        string destDir = Path.Combine(_tempRoot, "fast_dest");
        Directory.CreateDirectory(stagingDir);
        Directory.CreateDirectory(destDir);

        var countingFs = new CountingReadFileSystem();
        var store = new QuarantineStagingStore(
            stagingDir,
            destDir,
            enableSameVolumeStaging: true,
            fileSystem: countingFs);

        var sessionId = SessionId.NewRandom();
        var itemId = ItemId.NewRandom();
        byte[] payload = RandomNumberGenerator.GetBytes(512 * 1024);
        var digest = new Sha256Digest(Convert.ToHexStringLower(SHA256.HashData(payload)));

        using (var input = new MemoryStream(payload))
        {
            var (committedPath, actualDigest) = await store.WriteAndCommitAsync(
                sessionId,
                itemId,
                "fast/verified.bin",
                input,
                digest,
                payload.Length);

            Assert.Equal(digest, actualDigest);
            Assert.True(File.Exists(committedPath));
            Assert.Equal(payload, await File.ReadAllBytesAsync(committedPath));
        }

        Assert.Equal(0L, countingFs.VerificationBytesRead);
    }

    [Fact]
    public async Task SendTrustedAsync_ResolutionBudgetTimeoutDuringPostAckHandshake_ClassifiesAsDeviceUnavailable()
    {
        string senderTrustDir = Path.Combine(_tempRoot, "sender_trust");
        string receiverTrustDir = Path.Combine(_tempRoot, "receiver_trust");
        Directory.CreateDirectory(senderTrustDir);
        Directory.CreateDirectory(receiverTrustDir);

        var senderTrust = new DesktopPersistentTrustManager(
            CreateIdentityStore(senderTrustDir),
            new DesktopTrustStore(Path.Combine(senderTrustDir, "trust-v1.json")));
        var receiverTrust = new DesktopPersistentTrustManager(
            CreateIdentityStore(receiverTrustDir),
            new DesktopTrustStore(Path.Combine(receiverTrustDir, "trust-v1.json")));

        senderTrust.Initialize();
        receiverTrust.Initialize();

        string receiverFp = receiverTrust.LocalFingerprintHex();
        string receiverPubHex = Convert.ToHexStringLower(receiverTrust.LocalPublicKeyDer());
        string receiverPubB64 = InvitationCodec.ToUnpaddedBase64Url(receiverTrust.LocalPublicKeyDer());

        using var listener = new TlsSocketListener(port: 0);
        senderTrust.EnrollVerifiedPeer(
            new VerifiedDesktopPeerIdentity(
                receiverFp,
                receiverPubHex,
                "Stalling-Receiver",
                "linux",
                "127.0.0.1",
                listener.BoundPort),
            userApproved: true);

        string sourceFile = Path.Combine(_tempRoot, "budget.bin");
        await File.WriteAllBytesAsync(sourceFile, [1, 2, 3, 4]);

        using var serverCts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        Task serverTask = Task.Run(async () =>
        {
            await using TlsSocketConnection conn = await listener.AcceptConnectionAsync(serverCts.Token);
            LanSessionDispatchKind kind = await LanSessionPreambleInspector.InspectAndPrepareAsync(
                conn,
                serverCts.Token,
                TimeSpan.FromSeconds(2));
            Assert.Equal(LanSessionDispatchKind.Profile2Gld2, kind);

            Profile2Message msg = await Profile2WireCodec.ReadMessageAsync(conn.TransportStream, serverCts.Token);
            var open = Assert.IsType<Profile2Message.SessionOpen>(msg);

            await Profile2WireCodec.WriteMessageAsync(
                conn.TransportStream,
                new Profile2Message.SessionOpenAck(
                    SessionId: open.SessionId,
                    Accepted: true,
                    ResponderNonce: InvitationCodec.ToUnpaddedBase64Url(RandomNumberGenerator.GetBytes(32)),
                    ResponderDeviceName: "Stalling-Receiver",
                    ResponderPlatform: "linux",
                    ResponderIdentityPublicKey: receiverPubB64),
                serverCts.Token);

            // Stall after SESSION_OPEN_ACK so resolutionBudgetCts expires during HELLO / HELLO_ACK
            await Task.Delay(TimeSpan.FromSeconds(2), serverCts.Token);
        });

        var shortPolicy = DesktopTimeoutPolicy.Default with
        {
            TotalCandidateBudget = TimeSpan.FromMilliseconds(250),
            MachineAuthenticationTimeout = TimeSpan.FromSeconds(5)
        };
        var engine = new DesktopTransferEngine(timeoutPolicy: shortPolicy);

        DesktopTransferResult result = await engine.SendTrustedAsync(
            new DesktopSendRequest(
                Host: "127.0.0.1",
                Port: listener.BoundPort,
                Files: [new DesktopTransferFile(sourceFile)],
                LocalDeviceName: "Test-Sender",
                TrustManager: senderTrust,
                ExpectedPeerFingerprintHex: receiverFp),
            new AutoAcceptInteraction());

        serverCts.Cancel();
        try { await serverTask; } catch { }

        Assert.False(result.Success);
        Assert.Equal(TrustedEndpointResolver.DeviceUnavailableMessage, result.Message);
        Assert.Equal(TransferErrorCategory.Network, result.ErrorCategory);
    }

    [Fact]
    public void MdnsDiscoveryService_RespondsOnlyToGlideServiceQueries()
    {
        var sentPackets = new List<byte[]>();
        using var mdns = new MdnsDiscoveryService(
            interfaceAddressProvider: () => [IPAddress.Loopback],
            interfaceClientFactory: (ip, port) => new UdpClient(new IPEndPoint(ip, 0)));

        string presenceId = LanDiscoveryConstants.GeneratePresenceId();
        mdns.StartAdvertising("TestDesktop", 42424, presenceId);

        byte[] airplayQuery = BuildDnsQueryPacket("_airplay._tcp.local", qtype: 12);
        byte[] glidePtrQuery = BuildDnsQueryPacket("_glide._tcp.local", qtype: 12);
        byte[] glideAnyQuery = BuildDnsQueryPacket("_glide._tcp.local", qtype: 255);

        Assert.False(MdnsDiscoveryService.IsMatchingGlideQueryPacket(airplayQuery));
        Assert.True(MdnsDiscoveryService.IsMatchingGlideQueryPacket(glidePtrQuery));
        Assert.True(MdnsDiscoveryService.IsMatchingGlideQueryPacket(glideAnyQuery));
    }

    [Fact]
    public async Task DesktopTransferRuntime_DisposeAsync_AwaitsActiveTransferCancellationAndCleanup()
    {
        var startedTcs = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var cleanedUpTcs = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);

        var executor = new DelayedCleanupExecutor(startedTcs, cleanedUpTcs);
        var runtime = new DesktopTransferRuntime(executor);

        string dummyFile = Path.Combine(_tempRoot, "runtime.bin");
        await File.WriteAllBytesAsync(dummyFile, [0x42]);

        Task<DesktopTransferResult> sendTask = runtime.SendAsync(
            new DesktopSendRequest("127.0.0.1", 42424, [new DesktopTransferFile(dummyFile)]),
            new AutoAcceptInteraction());

        await startedTcs.Task.WaitAsync(TimeSpan.FromSeconds(5));
        await runtime.DisposeAsync();

        Assert.True(cleanedUpTcs.Task.IsCompleted, "DisposeAsync returned before active transfer cleanup finished.");
        DesktopTransferResult res = await sendTask.WaitAsync(TimeSpan.FromSeconds(2));
        Assert.True(res.Cancelled);
    }

    [Fact]
    public void DesktopTransferSelection_DelegatesToBatchPreparer_AndCachesFileSize()
    {
        string folder = Path.Combine(_tempRoot, "BatchFolder");
        Directory.CreateDirectory(folder);
        string fileA = Path.Combine(folder, "a.bin");
        File.WriteAllBytes(fileA, [1, 2, 3, 4, 5]);

        DesktopTransferSelectionResult expanded = DesktopTransferSelection.Expand([folder]);
        Assert.Single(expanded.Files);
        Assert.Empty(expanded.Skipped);
        Assert.Equal("BatchFolder/a.bin", expanded.Files[0].TransferRelativePath);
        Assert.Equal(5L, expanded.Files[0].SizeBytes);

        // Verify SizeBytes is cached and does not re-stat if file is truncated after preparation snapshot
        File.WriteAllBytes(fileA, [1]);
        Assert.Equal(5L, expanded.Files[0].SizeBytes);
    }

    [Fact]
    public async Task CliProgram_TrustedSend_And_Profile2Receive_RoundTripOverGld2()
    {
        string senderTrustDir = Path.Combine(_tempRoot, "cli_sender_trust");
        string receiverTrustDir = Path.Combine(_tempRoot, "cli_receiver_trust");
        string stagingDir = Path.Combine(_tempRoot, "cli_staging");
        string destDir = Path.Combine(_tempRoot, "cli_dest");
        Directory.CreateDirectory(senderTrustDir);
        Directory.CreateDirectory(receiverTrustDir);
        Directory.CreateDirectory(stagingDir);
        Directory.CreateDirectory(destDir);

        var senderTrust = new DesktopPersistentTrustManager(
            CreateIdentityStore(senderTrustDir),
            new DesktopTrustStore(Path.Combine(senderTrustDir, "trust-v1.json")));
        var receiverTrust = new DesktopPersistentTrustManager(
            CreateIdentityStore(receiverTrustDir),
            new DesktopTrustStore(Path.Combine(receiverTrustDir, "trust-v1.json")));

        senderTrust.Initialize();
        receiverTrust.Initialize();

        string senderFp = senderTrust.LocalFingerprintHex();
        string senderPubHex = Convert.ToHexStringLower(senderTrust.LocalPublicKeyDer());
        string receiverFp = receiverTrust.LocalFingerprintHex();
        string receiverPubHex = Convert.ToHexStringLower(receiverTrust.LocalPublicKeyDer());

        using var listener = new TlsSocketListener(port: 0);
        int port = listener.BoundPort;

        senderTrust.EnrollVerifiedPeer(
            new VerifiedDesktopPeerIdentity(
                receiverFp,
                receiverPubHex,
                "CLI-Receiver",
                "windows",
                "127.0.0.1",
                port),
            userApproved: true);

        receiverTrust.EnrollVerifiedPeer(
            new VerifiedDesktopPeerIdentity(
                senderFp,
                senderPubHex,
                "CLI-Sender",
                "windows",
                "127.0.0.1",
                0),
            userApproved: true);
        receiverTrust.SetHeadlessPolicy(senderFp, destDir, 10 * 1024 * 1024, enabled: true);

        string sourceFile = Path.Combine(_tempRoot, "cli-trusted.txt");
        byte[] expectedBytes = Encoding.UTF8.GetBytes("CLI Profile 2 GLD2 Trusted Send Verification");
        await File.WriteAllBytesAsync(sourceFile, expectedBytes);

        var stagingStore = new QuarantineStagingStore(stagingDir, destDir);
        var engine = new DesktopTransferEngine();

        Task<DesktopTransferResult> receiveTask = Task.Run(async () =>
        {
            await using TlsSocketConnection conn = await listener.AcceptConnectionAsync();
            return await GlideAppProgram.DispatchAcceptedConnectionAsync(
                conn,
                engine,
                stagingStore,
                new LanInvitationAuthority(),
                receiverTrust,
                receiverFp,
                isDaemon: true,
                authorizedPeerFingerprintHex: senderFp,
                destinationRoot: destDir,
                CancellationToken.None);
        });

        DesktopTransferResult sendResult = await engine.SendTrustedAsync(
            new DesktopSendRequest(
                Host: "127.0.0.1",
                Port: port,
                Files: [new DesktopTransferFile(sourceFile)],
                LocalDeviceName: "CLI-Sender",
                TrustManager: senderTrust,
                ExpectedPeerFingerprintHex: receiverFp),
            new AutoAcceptInteraction());

        DesktopTransferResult receiveResult = await receiveTask.WaitAsync(TimeSpan.FromSeconds(10));

        Assert.True(sendResult.Success, sendResult.Message);
        Assert.True(receiveResult.Success, receiveResult.Message);
        string savedPath = Path.Combine(destDir, "cli-trusted.txt");
        Assert.True(File.Exists(savedPath));
        Assert.Equal(expectedBytes, await File.ReadAllBytesAsync(savedPath));
    }

    [Fact]
    public void CliProgram_TryParseConnectEndpoint_SupportsIpv4AndIpv6()
    {
        Assert.True(GlideAppProgram.TryParseConnectEndpoint("192.168.1.40:42424", out string? host1, out int port1));
        Assert.Equal("192.168.1.40", host1);
        Assert.Equal(42424, port1);

        Assert.True(GlideAppProgram.TryParseConnectEndpoint("[::1]:42424", out string? host2, out int port2));
        Assert.Equal("::1", host2);
        Assert.Equal(42424, port2);

        Assert.True(GlideAppProgram.TryParseConnectEndpoint("fe80::1", out string? host3, out int port3));
        Assert.Equal("fe80::1", host3);
        Assert.Equal(0, port3);
    }

    [Fact]
    public async Task CliProgram_ExplicitPortBindConflict_FailsClosedWithoutFallback()
    {
        using var occupiedListener = new TcpListener(IPAddress.Any, 0);
        occupiedListener.Start();
        int occupiedPort = ((IPEndPoint)occupiedListener.LocalEndpoint).Port;

        string trustDir = Path.Combine(_tempRoot, "bind_trust");
        Directory.CreateDirectory(trustDir);

        int exitCode = await GlideAppProgram.RunReceiveWithOptionsAsync(
            port: occupiedPort,
            portExplicitlySpecified: true,
            trustedPeerFingerprint: null,
            enableMdns: false,
            trustManager: new DesktopPersistentTrustManager(
                CreateIdentityStore(trustDir),
                new DesktopTrustStore(Path.Combine(trustDir, "trust-v1.json"))));

        Assert.Equal(1, exitCode);
    }

    private static byte[] BuildDnsQueryPacket(string qname, ushort qtype)
    {
        using var ms = new MemoryStream();
        using var writer = new BinaryWriter(ms);
        writer.Write((ushort)0); // ID
        writer.Write((byte)0x00); // Flags hi (query)
        writer.Write((byte)0x00); // Flags lo
        writer.Write((byte)0x00);
        writer.Write((byte)0x01); // QDCOUNT = 1
        writer.Write((ushort)0); // ANCOUNT
        writer.Write((ushort)0); // NSCOUNT
        writer.Write((ushort)0); // ARCOUNT

        foreach (string label in qname.Split('.', StringSplitOptions.RemoveEmptyEntries))
        {
            byte[] bytes = Encoding.UTF8.GetBytes(label);
            writer.Write((byte)bytes.Length);
            writer.Write(bytes);
        }
        writer.Write((byte)0x00);
        writer.Write((byte)((qtype >> 8) & 0xFF));
        writer.Write((byte)(qtype & 0xFF));
        writer.Write((byte)0x00);
        writer.Write((byte)0x01); // IN class
        return ms.ToArray();
    }

    private sealed class AutoAcceptInteraction : DesktopTransferInteractionBase
    {
        public override Task<bool> ConfirmOfferAsync(DesktopOffer offer, CancellationToken cancellationToken) =>
            Task.FromResult(true);
    }

    private sealed class DelayedCleanupExecutor(
        TaskCompletionSource startedTcs,
        TaskCompletionSource cleanedUpTcs) : IDesktopTransferExecutor
    {
        public async Task<DesktopTransferResult> SendAsync(
            DesktopSendRequest request,
            IDesktopTransferInteraction interaction,
            CancellationToken cancellationToken = default)
        {
            startedTcs.TrySetResult();
            try
            {
                await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
                return DesktopTransferResult.Completed("Done", 1, 1);
            }
            catch (OperationCanceledException)
            {
                await Task.Delay(60, CancellationToken.None);
                cleanedUpTcs.TrySetResult();
                throw;
            }
        }

        public Task<DesktopTransferResult> ReceiveAsync(
            TlsSocketConnection connection,
            DesktopReceiveRequest request,
            IDesktopTransferInteraction interaction,
            CancellationToken cancellationToken = default) =>
            Task.FromResult(DesktopTransferResult.Failed("Not used"));
    }

    private sealed class StalledVerificationFileSystem(TimeSpan readDelay) : IQuarantineFileSystem
    {
        public uint GetEffectiveUserId() => 1000;

        public bool TryGetFileIdentityByPath(string path, out QuarantineFileIdentity identity, out int errno)
        {
            errno = 0;
            bool isDir = Directory.Exists(path);
            identity = new QuarantineFileIdentity(8, 1, 100, 1000, isDir ? (ushort)0x41C0 : (ushort)0x8180, isDir, !isDir, false);
            return true;
        }

        public bool TryGetFileIdentityByHandle(SafeFileHandle handle, out QuarantineFileIdentity identity, out int errno)
        {
            errno = 0;
            identity = new QuarantineFileIdentity(8, 1, 100, 1000, 0x8180, false, true, false);
            return true;
        }

        public void AtomicRenameNoReplace(SafeFileHandle? sourceFileHandle, string sourceDirectoryPath, string sourceFileName, string targetDirectoryPath, string targetFileName) =>
            File.Move(Path.Combine(sourceDirectoryPath, sourceFileName), Path.Combine(targetDirectoryPath, targetFileName), overwrite: false);

        public void TryFlushDirectory(string directoryPath) { }

        public Stream OpenVerificationReadWriteStream(string path, int bufferSize) =>
            new DelayedReadStream(new FileStream(path, FileMode.Open, FileAccess.ReadWrite, FileShare.ReadWrite | FileShare.Delete), readDelay);

        public Stream OpenReadStream(string path, int bufferSize) =>
            new DelayedReadStream(new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete), readDelay);

        public Stream OpenCreateNewWriteStream(string path, int bufferSize) =>
            new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None, bufferSize, FileOptions.Asynchronous);
    }

    private sealed class DelayedReadStream(Stream inner, TimeSpan delay) : Stream
    {
        public override bool CanRead => true;
        public override bool CanSeek => inner.CanSeek;
        public override bool CanWrite => inner.CanWrite;
        public override long Length => inner.Length;
        public override long Position { get => inner.Position; set => inner.Position = value; }
        public override void Flush() => inner.Flush();
        public override int Read(byte[] buffer, int offset, int count) => inner.Read(buffer, offset, count);
        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            await Task.Delay(delay, cancellationToken);
            return await inner.ReadAsync(buffer, cancellationToken);
        }
        public override long Seek(long offset, SeekOrigin origin) => inner.Seek(offset, origin);
        public override void SetLength(long value) => inner.SetLength(value);
        public override void Write(byte[] buffer, int offset, int count) => inner.Write(buffer, offset, count);
        protected override void Dispose(bool disposing)
        {
            if (disposing) inner.Dispose();
            base.Dispose(disposing);
        }
    }

    private sealed class CountingReadFileSystem : IQuarantineFileSystem
    {
        private long _verificationBytesRead;
        public long VerificationBytesRead => Interlocked.Read(ref _verificationBytesRead);

        public uint GetEffectiveUserId() => 1000;

        public bool TryGetFileIdentityByPath(string path, out QuarantineFileIdentity identity, out int errno)
        {
            errno = 0;
            bool isDir = Directory.Exists(path);
            identity = new QuarantineFileIdentity(8, 1, 100, 1000, isDir ? (ushort)0x41C0 : (ushort)0x8180, isDir, !isDir, false);
            return true;
        }

        public bool TryGetFileIdentityByHandle(SafeFileHandle handle, out QuarantineFileIdentity identity, out int errno)
        {
            errno = 0;
            identity = new QuarantineFileIdentity(8, 1, 100, 1000, 0x8180, false, true, false);
            return true;
        }

        public void AtomicRenameNoReplace(SafeFileHandle? sourceFileHandle, string sourceDirectoryPath, string sourceFileName, string targetDirectoryPath, string targetFileName) =>
            File.Move(Path.Combine(sourceDirectoryPath, sourceFileName), Path.Combine(targetDirectoryPath, targetFileName), overwrite: false);

        public void TryFlushDirectory(string directoryPath) { }

        public Stream OpenVerificationReadWriteStream(string path, int bufferSize) =>
            new CountingStream(
                new FileStream(path, FileMode.Open, FileAccess.ReadWrite, FileShare.ReadWrite | FileShare.Delete),
                n => Interlocked.Add(ref _verificationBytesRead, n));

        public Stream OpenReadStream(string path, int bufferSize) =>
            new CountingStream(
                new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete),
                n => Interlocked.Add(ref _verificationBytesRead, n));

        public Stream OpenCreateNewWriteStream(string path, int bufferSize) =>
            new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None, bufferSize, FileOptions.Asynchronous);
    }

    private sealed class CountingStream(Stream inner, Action<int> onRead) : Stream
    {
        public override bool CanRead => inner.CanRead;
        public override bool CanSeek => inner.CanSeek;
        public override bool CanWrite => inner.CanWrite;
        public override long Length => inner.Length;
        public override long Position { get => inner.Position; set => inner.Position = value; }
        public override void Flush() => inner.Flush();
        public override int Read(byte[] buffer, int offset, int count)
        {
            int n = inner.Read(buffer, offset, count);
            if (n > 0) onRead(n);
            return n;
        }
        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            int n = await inner.ReadAsync(buffer, cancellationToken);
            if (n > 0) onRead(n);
            return n;
        }
        public override long Seek(long offset, SeekOrigin origin) => inner.Seek(offset, origin);
        public override void SetLength(long value) => inner.SetLength(value);
        public override void Write(byte[] buffer, int offset, int count) => inner.Write(buffer, offset, count);
        protected override void Dispose(bool disposing)
        {
            if (disposing) inner.Dispose();
            base.Dispose(disposing);
        }
    }
}
