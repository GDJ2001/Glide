using System.Security.Cryptography;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Core.Transport.Lan;
using Glide.Desktop;
using Glide.Desktop.Services;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;
using Xunit;

namespace Glide.Tests;

[Collection("HARD-003 console/runtime")]
public sealed class GbpsTransferPipelineAndStorageTests : IDisposable
{
    private readonly string _testRoot;

    public GbpsTransferPipelineAndStorageTests()
    {
        _testRoot = Path.Combine(Path.GetTempPath(), "Glide_GbpsTests_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_testRoot);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_testRoot))
            {
                Directory.Delete(_testRoot, recursive: true);
            }
        }
        catch
        {
            // Ignore cleanup errors
        }
    }

    private static BulkRef NewRandomBulkRef() =>
        new(Guid.NewGuid().ToString("D").ToLowerInvariant());

    private static LanInvitation CreateInvitation(TlsSocketListener listener)
    {
        string token = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32))
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');

        var invitation = new LanInvitation(
            invitationId: Guid.NewGuid().ToString("D").ToLowerInvariant(),
            presence: Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant(),
            name: "Receiver-PC",
            platform: "windows",
            endpoints: [new LanEndpoint("127.0.0.1", listener.BoundPort)],
            spkiSha256: listener.LocalSpkiSha256,
            token: token,
            expiresAt: (DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 120).ToString(),
            ttlSeconds: 120);

        return InvitationCodec.Decode(InvitationCodec.Encode(invitation));
    }

    [Fact]
    public async Task TransferBufferPool_EnforcesCap_AwaitsCapacity_AndZeroesOnDispose()
    {
        using var pool = new TransferBufferPool(maxProcessBytes: 4 * 4096, bufferBytes: 4096);
        Assert.Equal(4, pool.MaxBuffers);
        Assert.Equal(16_384, pool.MaxProcessBytes);

        OwnedTransferBuffer b1 = await pool.RentAsync(4096);
        OwnedTransferBuffer b2 = await pool.RentAsync(4096);
        OwnedTransferBuffer b3 = await pool.RentAsync(4096);
        OwnedTransferBuffer b4 = await pool.RentAsync(4096);

        Assert.Equal(4, pool.RentedBufferCount);
        Assert.Equal(16_384, pool.RentedBytes);

        // Fill b1 with non-zero secret bytes and keep reference to underlying array to verify zeroing
        byte[] rawRef = b1.RawBuffer;
        b1.Span.Fill(0xAB);

        // A 5th rent must wait until one of the 4 buffers is disposed
        ValueTask<OwnedTransferBuffer> fifthRentTask = pool.RentAsync(2048);
        Assert.False(fifthRentTask.IsCompleted);

        b1.Dispose();
        Assert.True(b1.IsDisposed);
        Assert.Throws<ObjectDisposedException>(() => _ = b1.Span);

        // Verify buffer was zeroed before returning to free queue
        Assert.All(rawRef, static b => Assert.Equal(0, b));

        OwnedTransferBuffer b5 = await fifthRentTask;
        Assert.Equal(2048, b5.Length);
        Assert.Equal(4, pool.RentedBufferCount);

        b2.Dispose();
        b3.Dispose();
        b4.Dispose();
        b5.Dispose();

        Assert.Equal(0, pool.RentedBufferCount);
        Assert.Equal(16_384, pool.PeakRentedBytes);
    }

    [Fact]
    public async Task CoalescedFraming_FragmentedPartialReads_RoundTripsOwnedDataAndControlRecords()
    {
        using var pool = new TransferBufferPool(maxProcessBytes: 1024 * 1024, bufferBytes: 65_536);
        using var rawStream = new MemoryStream();

        var bulkRef = NewRandomBulkRef();
        byte[] chunk1 = RandomNumberGenerator.GetBytes(19_999);
        byte[] chunk2 = RandomNumberGenerator.GetBytes(65_520);
        byte[] chunk3 = RandomNumberGenerator.GetBytes(1_024);

        await using (var writerChannel = new LanRecordChannel(
            rawStream,
            coalesceWrites: true,
            coalesceBufferBytes: 256 * 1024,
            maxCoalesceDelayMs: 5,
            bufferPool: pool))
        {
            await writerChannel.WriteDataChunkAsync(bulkRef, chunk1);
            await writerChannel.WriteDataChunkAsync(bulkRef, chunk2);
            await writerChannel.WriteDataChunkAsync(bulkRef, chunk3);
            await writerChannel.WriteStreamEndAsync(bulkRef, chunk1.Length + chunk2.Length + chunk3.Length);
        }

        rawStream.Position = 0;
        using var fragmentedStream = new FragmentedReadStream(rawStream, maxBytesPerRead: 137);
        await using var readerChannel = new LanRecordChannel(fragmentedStream, coalesceWrites: false, bufferPool: pool);

        var r1 = Assert.IsType<LanRecord.OwnedData>(await readerChannel.ReadOwnedRecordAsync(bulkRef));
        using (r1)
        {
            Assert.Equal(bulkRef, r1.BulkRef);
            Assert.Equal(chunk1, r1.ChunkSpan.ToArray());
        }

        var r2 = Assert.IsType<LanRecord.OwnedData>(await readerChannel.ReadOwnedRecordAsync(bulkRef));
        using (r2)
        {
            Assert.Equal(bulkRef, r2.BulkRef);
            Assert.Equal(chunk2, r2.ChunkSpan.ToArray());
        }

        var r3 = Assert.IsType<LanRecord.OwnedData>(await readerChannel.ReadOwnedRecordAsync(bulkRef));
        using (r3)
        {
            Assert.Equal(bulkRef, r3.BulkRef);
            Assert.Equal(chunk3, r3.ChunkSpan.ToArray());
        }

        var end = Assert.IsType<LanRecord.StreamEnd>(await readerChannel.ReadOwnedRecordAsync(bulkRef));
        Assert.Equal(bulkRef, end.BulkRef);
        Assert.Equal(chunk1.Length + chunk2.Length + chunk3.Length, end.ActualBytes);
        Assert.Equal(0, pool.RentedBufferCount);
    }

    [Fact]
    public async Task CoalescedFraming_TimerWriteFailureIsReportedToTransferWriter()
    {
        using var stream = new FailingWriteStream();
        await using var channel = new LanRecordChannel(stream, coalesceWrites: true, maxCoalesceDelayMs: 1);
        var bulkRef = NewRandomBulkRef();

        await channel.WriteDataChunkAsync(bulkRef, new byte[16]);
        await stream.WriteAttempted.WaitAsync(TimeSpan.FromSeconds(5));

        var error = await Assert.ThrowsAsync<IOException>(
            () => channel.WriteStreamEndAsync(bulkRef, 16));
        Assert.Equal("Injected write failure", error.Message);
    }

    [Theory]
    [InlineData(0L)]
    [InlineData(4_294_967_295L)] // 4 GiB - 1
    [InlineData(4_294_967_296L)] // 4 GiB exact boundary
    [InlineData(4_294_967_297L)] // 4 GiB + 1
    [InlineData(4_831_838_208L)] // 4.5 GiB reported slowdown region
    [InlineData(5_000_000_000L)] // 5.0 GB decimal
    public async Task StreamEnd_And_RateCalculator_Handle4GiBAnd5GBBoundariesWithoutOverflow(long boundaryBytes)
    {
        var bulkRef = NewRandomBulkRef();
        byte[] frame = new byte[LanRecordCodec.StreamEndFrameSize];
        int written = LanRecordCodec.EncodeStreamEndToSpan(bulkRef, boundaryBytes, frame);
        Assert.Equal(LanRecordCodec.StreamEndFrameSize, written);

        using var ms = new MemoryStream(frame);
        LanRecord? decoded = await LanRecordCodec.ReadOwnedRecordAsync(ms, TransferBufferPool.Shared, bulkRef);
        var streamEnd = Assert.IsType<LanRecord.StreamEnd>(decoded);
        Assert.Equal(bulkRef, streamEnd.BulkRef);
        Assert.Equal(boundaryBytes, streamEnd.ActualBytes);

        // Verify Decimal MB/s rate formatting across > 4.5 GB counters
        if (boundaryBytes >= 100_000L)
        {
            string formattedRate = DesktopTransferRateCalculator.FormatRate(boundaryBytes);
            Assert.EndsWith(" MB/s", formattedRate);
        }
    }

    [Fact]
    public void Presentation_UsesDecimalMBps_AndDistinguishesTransferringVerifyingSaving()
    {
        Assert.Equal("100.0 MB/s", DesktopTransferRateCalculator.FormatRate(100_000_000.0));
        Assert.Equal("100.0 MB", DesktopTransferRateCalculator.FormatBytes(100_000_000L));

        var state = new DesktopTransferPresentation();
        Guid run = Guid.NewGuid();
        state.ApplySnapshot(new(DesktopTransferPhase.Transferring, DesktopTransferDirection.Receive, "Peer", "Receiving", RunId: run));
        state.ApplyEvent(new TransferEvent.ProgressUpdated(
            new DesktopTransferProgress(DesktopTransferDirection.Receive, "Peer", "file.bin", 50_000_000, 100_000_000),
            50, "100.0 MB/s", "50.0 MB / 100.0 MB"));
        Assert.Equal("100.0 MB/s", state.RateText);

        // Speed must be hidden during Verifying and Saving
        state.ApplySnapshot(new(DesktopTransferPhase.Verifying, DesktopTransferDirection.Receive, "Peer", "Verifying", RunId: run));
        Assert.Equal("Verifying files…", state.PhaseText);
        Assert.Equal(string.Empty, state.RateText);

        state.ApplySnapshot(new(DesktopTransferPhase.Saving, DesktopTransferDirection.Receive, "Peer", "Saving", RunId: run));
        Assert.Equal("Saving files…", state.PhaseText);
        Assert.Equal(string.Empty, state.RateText);
    }

    [Fact]
    public async Task QuarantineStagingStore_SameVolumeAtomicRename_And_CrossVolumeFallback_PreserveIntegrity()
    {
        string stagingDir = Path.Combine(_testRoot, "staging");
        string destDir = Path.Combine(_testRoot, "dest");
        Directory.CreateDirectory(stagingDir);
        Directory.CreateDirectory(destDir);

        byte[] payload = RandomNumberGenerator.GetBytes(256 * 1024);
        var digest = new Sha256Digest(Convert.ToHexStringLower(SHA256.HashData(payload)));

        // 1. Same-volume atomic rename strategy
        var atomicStore = new QuarantineStagingStore(stagingDir, destDir, enableSameVolumeStaging: true);
        var s1 = SessionId.NewRandom();
        var i1 = ItemId.NewRandom();
        using (var input1 = new MemoryStream(payload))
        {
            var (path1, actual1) = await atomicStore.WriteAndCommitAsync(s1, i1, "atomic/file.bin", input1, digest, payload.Length);
            Assert.Equal(digest, actual1);
            Assert.True(File.Exists(path1));
            Assert.Equal(payload, await File.ReadAllBytesAsync(path1));
            Assert.Equal(QuarantineCommitStrategy.SameVolumeAtomicRename, atomicStore.LastCommitStrategy);
        }

        // 2. Verified-copy fallback strategy + collision resolution
        var fallbackStore = new QuarantineStagingStore(stagingDir, destDir, enableSameVolumeStaging: false);
        var s2 = SessionId.NewRandom();
        var i2 = ItemId.NewRandom();
        using (var input2 = new MemoryStream(payload))
        {
            var (path2, actual2) = await fallbackStore.WriteAndCommitAsync(s2, i2, "atomic/file.bin", input2, digest, payload.Length);
            Assert.Equal(digest, actual2);
            Assert.True(File.Exists(path2));
            Assert.Equal("file (1).bin", Path.GetFileName(path2));
            Assert.Equal(payload, await File.ReadAllBytesAsync(path2));
            Assert.Equal(QuarantineCommitStrategy.VerifiedCopyFallback, fallbackStore.LastCommitStrategy);
        }
    }

    [Fact]
    public async Task WindowsAtomicCommit_KeepsVerifiedSourceLockedUntilPublication()
    {
        if (!OperatingSystem.IsWindows()) return;

        string stagingDir = Path.Combine(_testRoot, "locked_staging");
        string destDir = Path.Combine(_testRoot, "locked_dest");
        Directory.CreateDirectory(stagingDir);
        Directory.CreateDirectory(destDir);
        var store = new QuarantineStagingStore(stagingDir, destDir);
        var session = SessionId.NewRandom();
        var item = ItemId.NewRandom();
        byte[] payload = RandomNumberGenerator.GetBytes(128 * 1024);
        var digest = new Sha256Digest(Convert.ToHexStringLower(SHA256.HashData(payload)));

        await using (Stream stream = store.OpenQuarantineWrite(session, item))
        {
            // The staging stream must remain usable after collection of the
            // wrapper's constructor locals.
            GC.Collect();
            GC.WaitForPendingFinalizers();
            await stream.WriteAsync(payload);
        }

        bool replacementBlocked = false;
        string quarantinePath = store.GetQuarantinePath(session, item);
        QuarantineCommitResult result = await store.CommitVerifiedQuarantineFileWithMetricsAsync(
            session, item, "received.bin", digest, payload.Length,
            onBeforePublish: () =>
            {
                try
                {
                    File.Move(quarantinePath, quarantinePath + ".replaced");
                }
                catch (IOException)
                {
                    replacementBlocked = true;
                }
            });

        Assert.True(replacementBlocked);
        Assert.Equal(QuarantineCommitStrategy.SameVolumeAtomicRename, result.StrategyUsed);
        Assert.Equal(payload, await File.ReadAllBytesAsync(result.CommittedPath));
    }

    [Fact]
    public async Task LinuxAtomicCommit_VerifiesSameVolumeRenameAndCollision()
    {
        if (!OperatingSystem.IsLinux()) return;

        string stagingDir = Path.Combine(_testRoot, "linux_atomic_staging");
        string destDir = Path.Combine(_testRoot, "linux_atomic_dest");
        Directory.CreateDirectory(stagingDir);
        Directory.CreateDirectory(destDir);
        var store = new QuarantineStagingStore(stagingDir, destDir, enableSameVolumeStaging: true);
        var session = SessionId.NewRandom();
        var item1 = ItemId.NewRandom();
        var item2 = ItemId.NewRandom();
        byte[] payload1 = RandomNumberGenerator.GetBytes(64 * 1024);
        byte[] payload2 = RandomNumberGenerator.GetBytes(64 * 1024);
        var digest1 = new Sha256Digest(Convert.ToHexStringLower(SHA256.HashData(payload1)));
        var digest2 = new Sha256Digest(Convert.ToHexStringLower(SHA256.HashData(payload2)));

        // Write and commit first file
        using (var in1 = new MemoryStream(payload1))
        {
            var (path1, d1) = await store.WriteAndCommitAsync(session, item1, "sample.dat", in1, digest1, payload1.Length);
            Assert.Equal(digest1, d1);
            Assert.True(File.Exists(path1));
            Assert.Equal("sample.dat", Path.GetFileName(path1));
            Assert.Equal(QuarantineCommitStrategy.SameVolumeAtomicRename, store.LastCommitStrategy);
        }

        // Commit second file with same relative name -> collision resolution with RENAME_NOREPLACE
        using (var in2 = new MemoryStream(payload2))
        {
            var (path2, d2) = await store.WriteAndCommitAsync(session, item2, "sample.dat", in2, digest2, payload2.Length);
            Assert.Equal(digest2, d2);
            Assert.True(File.Exists(path2));
            Assert.Equal("sample (1).dat", Path.GetFileName(path2));
            Assert.Equal(QuarantineCommitStrategy.SameVolumeAtomicRename, store.LastCommitStrategy);
            Assert.Equal(payload2, await File.ReadAllBytesAsync(path2));
            Assert.Equal(payload1, await File.ReadAllBytesAsync(Path.Combine(destDir, "sample.dat")));
        }
    }

    [Fact]
    public void QuarantineStagingStore_ActiveSessionLease_PreventsCleanupOfOldInFlightSession()
    {
        string stagingDir = Path.Combine(_testRoot, "lease_staging");
        string destDir = Path.Combine(_testRoot, "lease_dest");
        var store = new QuarantineStagingStore(stagingDir, destDir);

        var leasedSession = SessionId.NewRandom();
        var unleasedOldSession = SessionId.NewRandom();
        var itemId = ItemId.NewRandom();

        using IDisposable activeLease = store.AcquireSessionLease(leasedSession);
        using (store.OpenQuarantineWrite(leasedSession, itemId)) { }
        using (store.OpenQuarantineWrite(unleasedOldSession, itemId)) { }

        string leasedPath = store.GetQuarantinePath(leasedSession, itemId);
        string unleasedPath = store.GetQuarantinePath(unleasedOldSession, itemId);

        // Backdate both files to 3 days ago
        File.SetLastWriteTimeUtc(leasedPath, DateTime.UtcNow.AddDays(-3));
        File.SetLastWriteTimeUtc(unleasedPath, DateTime.UtcNow.AddDays(-3));

        // Cleanup must remove only the unleased old session and preserve the actively leased session
        int removed = store.PurgeAbandonedQuarantineSessions();
        Assert.Equal(1, removed);
        Assert.True(File.Exists(leasedPath));
        Assert.False(File.Exists(unleasedPath));

        // Once the lease is disposed, a subsequent maintenance pass can clean up the old session
        activeLease.Dispose();
        int removedAfterRelease = store.PurgeAbandonedQuarantineSessions();
        Assert.Equal(1, removedAfterRelease);
        Assert.False(File.Exists(leasedPath));
    }

    [Theory]
    [InlineData(false, true)] // Legacy Sender -> Optimized Receiver
    [InlineData(true, false)] // Optimized Sender -> Legacy Receiver
    [InlineData(true, true)]  // Optimized Sender -> Optimized Receiver
    public async Task Interoperability_LegacyAndOptimizedPeers_TransferZeroByteAndMultiChunkFiles(
        bool optimizeSender,
        bool optimizeReceiver)
    {
        string sourceDir = Path.Combine(_testRoot, $"src_{optimizeSender}_{optimizeReceiver}");
        string stagingDir = Path.Combine(_testRoot, $"stg_{optimizeSender}_{optimizeReceiver}");
        string destDir = Path.Combine(_testRoot, $"dst_{optimizeSender}_{optimizeReceiver}");
        Directory.CreateDirectory(sourceDir);
        Directory.CreateDirectory(stagingDir);
        Directory.CreateDirectory(destDir);

        string zeroFile = Path.Combine(sourceDir, "empty.txt");
        await File.WriteAllBytesAsync(zeroFile, []);

        string payloadFile = Path.Combine(sourceDir, "payload.bin");
        byte[] payloadBytes = RandomNumberGenerator.GetBytes(350_000);
        await File.WriteAllBytesAsync(payloadFile, payloadBytes);

        var senderOptions = optimizeSender ? DesktopTransferIoOptions.Default : DesktopTransferIoOptions.LegacyBaseline;
        var receiverOptions = optimizeReceiver ? DesktopTransferIoOptions.Default : DesktopTransferIoOptions.LegacyBaseline;

        using var senderPool = new TransferBufferPool();
        using var receiverPool = new TransferBufferPool();

        var senderEngine = new DesktopTransferEngine(ioOptions: senderOptions, bufferPool: senderPool);
        var receiverEngine = new DesktopTransferEngine(ioOptions: receiverOptions, bufferPool: receiverPool);

        using var listener = new TlsSocketListener(0);
        var invitationAuthority = new LanInvitationAuthority();
        LanInvitation invitation = invitationAuthority.Publish(CreateInvitation(listener));

        var store = new QuarantineStagingStore(stagingDir, destDir, enableSameVolumeStaging: optimizeReceiver);
        var receiveReq = new DesktopReceiveRequest(
            StagingStore: store,
            InvitationAuthority: invitationAuthority,
            LocalDeviceName: "Receiver-PC");

        var sendReq = new DesktopSendRequest(
            Host: "127.0.0.1",
            Port: listener.BoundPort,
            Files:
            [
                new DesktopTransferFile(zeroFile),
                new DesktopTransferFile(payloadFile)
            ],
            Invitation: invitation,
            LocalDeviceName: "Sender-PC");

        var receiveTask = Task.Run(async () =>
        {
            await using var connection = await listener.AcceptConnectionAsync();
            return await receiverEngine.ReceiveAsync(connection, receiveReq, new AutoAcceptInteraction());
        });

        var sendResult = await senderEngine.SendAsync(sendReq, new AutoAcceptInteraction());
        var receiveResult = await receiveTask.WaitAsync(TimeSpan.FromSeconds(15));

        Assert.True(sendResult.Success, sendResult.Message);
        Assert.True(receiveResult.Success, receiveResult.Message);
        Assert.Equal(2, sendResult.VerifiedCount);
        Assert.Equal(2, receiveResult.VerifiedCount);

        Assert.Equal(0, new FileInfo(Path.Combine(destDir, "empty.txt")).Length);
        Assert.Equal(payloadBytes, await File.ReadAllBytesAsync(Path.Combine(destDir, "payload.bin")));
        Assert.Equal(0, senderPool.RentedBufferCount);
        Assert.Equal(0, receiverPool.RentedBufferCount);
    }

    [Fact]
    public async Task BoundedStreaming_CancellationMidTransfer_StopsWorkers_ReturnsBuffers_AndPurgesQuarantine()
    {
        string sourceDir = Path.Combine(_testRoot, "cancel_src");
        string stagingDir = Path.Combine(_testRoot, "cancel_stg");
        string destDir = Path.Combine(_testRoot, "cancel_dst");
        Directory.CreateDirectory(sourceDir);
        Directory.CreateDirectory(stagingDir);
        Directory.CreateDirectory(destDir);

        string payloadFile = Path.Combine(sourceDir, "large_cancel.bin");
        byte[] payloadBytes = RandomNumberGenerator.GetBytes(2 * 1024 * 1024); // 2 MiB
        await File.WriteAllBytesAsync(payloadFile, payloadBytes);

        using var senderPool = new TransferBufferPool();
        using var receiverPool = new TransferBufferPool();

        var senderEngine = new DesktopTransferEngine(bufferPool: senderPool);
        var receiverEngine = new DesktopTransferEngine(bufferPool: receiverPool);

        using var listener = new TlsSocketListener(0);
        var invitationAuthority = new LanInvitationAuthority();
        LanInvitation invitation = invitationAuthority.Publish(CreateInvitation(listener));

        using var receiverCts = new CancellationTokenSource();
        var cancelInteraction = new CancelAfterProgressInteraction(cancelAtBytes: 256 * 1024, receiverCts);

        var store = new QuarantineStagingStore(stagingDir, destDir);
        var receiveReq = new DesktopReceiveRequest(
            StagingStore: store,
            InvitationAuthority: invitationAuthority,
            LocalDeviceName: "Receiver-PC");

        var sendReq = new DesktopSendRequest(
            Host: "127.0.0.1",
            Port: listener.BoundPort,
            Files: [new DesktopTransferFile(payloadFile)],
            Invitation: invitation,
            LocalDeviceName: "Sender-PC");

        var receiveTask = Task.Run(async () =>
        {
            await using var connection = await listener.AcceptConnectionAsync(receiverCts.Token);
            return await receiverEngine.ReceiveAsync(connection, receiveReq, cancelInteraction, receiverCts.Token);
        });

        Task<DesktopTransferResult> sendTask = senderEngine.SendAsync(sendReq, new AutoAcceptInteraction());

        DesktopTransferResult receiveResult = await receiveTask.WaitAsync(TimeSpan.FromSeconds(15));
        Assert.True(receiveResult.Cancelled, receiveResult.Message);
        Assert.False(receiveResult.Success);
        try
        {
            await sendTask;
        }
        catch
        {
            // Sender sees peer disconnect when receiver cancels
        }

        // All pooled buffers must be returned and zeroed; no incomplete quarantine files left behind
        Assert.Equal(0, senderPool.RentedBufferCount);
        Assert.Equal(0, receiverPool.RentedBufferCount);
        Assert.Empty(Directory.GetFiles(stagingDir, "*.quarantine", SearchOption.AllDirectories));
        Assert.Empty(Directory.GetFiles(destDir, "*", SearchOption.AllDirectories));
    }

    [Fact]
    public void FreeSpaceStrategy_And_DiskFullClassification_EnforceSameVolumeCopyHeadroom()
    {
        string stagingDir = Path.Combine(_testRoot, "space_stg");
        string destDir = Path.Combine(_testRoot, "space_dst");
        Directory.CreateDirectory(stagingDir);
        Directory.CreateDirectory(destDir);

        // Same-volume atomic rename only needs 1x totalBytes
        var atomicStore = new QuarantineStagingStore(stagingDir, destDir, enableSameVolumeStaging: true);
        Assert.True(atomicStore.CanUseSameVolumeAtomicCommit());
        Assert.True(atomicStore.HasSufficientFreeSpaceForBatch(totalBytes: 1024, maxItemBytes: 1024));

        // Verify disk-full HResult classification
        var diskFullEx = new IOException("There is not enough space on the disk.", unchecked((int)0x80070070));
        Assert.True(DesktopTransferFailures.IsDiskFull(diskFullEx));
        Assert.Equal(TransferErrorCategory.Storage, DesktopTransferFailures.Classify(diskFullEx));
    }

    private sealed class CancelAfterProgressInteraction : IDesktopTransferInteraction
    {
        private readonly long _cancelAtBytes;
        private readonly CancellationTokenSource _cts;

        public CancelAfterProgressInteraction(long cancelAtBytes, CancellationTokenSource cts)
        {
            _cancelAtBytes = cancelAtBytes;
            _cts = cts;
        }

        public Task<bool> ConfirmOfferAsync(DesktopOffer offer, CancellationToken cancellationToken) =>
            Task.FromResult(true);

        public Task<bool> ConfirmPeerEnrollmentAsync(VerifiedDesktopPeerIdentity peer, CancellationToken cancellationToken) =>
            Task.FromResult(true);

        public void OnInvitationConsumed(DesktopTransferDirection direction) { }
        public void OnPeerIdentified(DesktopTransferDirection direction, string peerName) { }
        public void OnStatus(DesktopTransferDirection direction, string statusText) { }
        public void OnItemStatus(DesktopTransferDirection direction, string itemName, string status) { }
        public void OnHistory(DesktopHistoryEntry entry) { }

        public void OnProgress(DesktopTransferProgress progress)
        {
            if (progress.TransferredBytes >= _cancelAtBytes)
            {
                _cts.Cancel();
            }
        }
    }

    private sealed class AutoAcceptInteraction : IDesktopTransferInteraction
    {
        public Task<bool> ConfirmOfferAsync(DesktopOffer offer, CancellationToken cancellationToken) =>
            Task.FromResult(true);

        public Task<bool> ConfirmPeerEnrollmentAsync(VerifiedDesktopPeerIdentity peer, CancellationToken cancellationToken) =>
            Task.FromResult(true);

        public void OnInvitationConsumed(DesktopTransferDirection direction) { }
        public void OnPeerIdentified(DesktopTransferDirection direction, string peerName) { }
        public void OnStatus(DesktopTransferDirection direction, string statusText) { }
        public void OnProgress(DesktopTransferProgress progress) { }
        public void OnItemStatus(DesktopTransferDirection direction, string itemName, string status) { }
        public void OnHistory(DesktopHistoryEntry entry) { }
    }

    private sealed class FailingWriteStream : MemoryStream
    {
        private readonly TaskCompletionSource _writeAttempted = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public Task WriteAttempted => _writeAttempted.Task;

        public override ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default)
        {
            _writeAttempted.TrySetResult();
            return ValueTask.FromException(new IOException("Injected write failure"));
        }
    }

    private sealed class FragmentedReadStream : Stream
    {
        private readonly Stream _inner;
        private readonly int _maxBytesPerRead;

        public FragmentedReadStream(Stream inner, int maxBytesPerRead)
        {
            _inner = inner;
            _maxBytesPerRead = Math.Max(1, maxBytesPerRead);
        }

        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => _inner.Length;
        public override long Position
        {
            get => _inner.Position;
            set => throw new NotSupportedException();
        }

        public override void Flush() => _inner.Flush();

        public override int Read(byte[] buffer, int offset, int count) =>
            _inner.Read(buffer, offset, Math.Min(count, _maxBytesPerRead));

        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            Memory<byte> slice = buffer.Length > _maxBytesPerRead
                ? buffer[.._maxBytesPerRead]
                : buffer;
            return _inner.ReadAsync(slice, cancellationToken);
        }

        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
}
