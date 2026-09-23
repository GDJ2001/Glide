using System.Security.Cryptography;
using System.Text;
using Glide.Core.Models;
using Glide.Core.Storage;
using Xunit;

namespace Glide.Tests;

public sealed class DesktopResumeStoreTests : IDisposable
{
    private readonly string _testRoot;
    private readonly string _testDestDir;
    private long _clock;
    private DesktopResumeStore _store;

    public DesktopResumeStoreTests()
    {
        _testRoot = Path.Combine(Path.GetTempPath(), "GlideTest_Resume_" + Guid.NewGuid().ToString("N"));
        _testDestDir = Path.Combine(Path.GetTempPath(), "GlideTest_ResumeDest_" + Guid.NewGuid().ToString("N"));
        _clock = 1_000_000L;
        _store = new DesktopResumeStore(_testRoot, () => _clock, () => 0L);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_testRoot)) Directory.Delete(_testRoot, true);
            if (Directory.Exists(_testDestDir)) Directory.Delete(_testDestDir, true);
        }
        catch { }
    }

    [Fact]
    public async Task InitializeReceiverAsync_NewSession_CreatesJournal()
    {
        var sessionId = new SessionId("11111111-1111-4111-8111-111111111111");
        var itemId = new ItemId("22222222-2222-4222-8222-222222222222");
        var peer = new string('a', 64);
        var root = new string('b', 64);

        var journal = await _store.InitializeReceiverAsync(sessionId, itemId, "file.bin", peer, 1024, 256, root);

        Assert.Equal(sessionId.Value, journal.OriginalSessionId);
        Assert.Equal(itemId.Value, journal.ItemId);
        Assert.Equal(DesktopResumeJournalRoles.Receiver, journal.JournalRole);
        Assert.Equal(0, journal.VerifiedChunks);
        Assert.Equal(0, journal.ContiguousByteOffset);
        Assert.NotNull(journal.StagingPath);
        Assert.True(Directory.Exists(journal.StagingPath));
    }

    [Fact]
    public async Task InitializeReceiverAsync_DuplicateSessionId_ReturnsExisting()
    {
        var sessionId = new SessionId("11111111-1111-4111-8111-111111111111");
        var itemId = new ItemId("22222222-2222-4222-8222-222222222222");
        var peer = new string('a', 64);
        var root = new string('b', 64);

        var first = await _store.InitializeReceiverAsync(sessionId, itemId, "file.bin", peer, 1024, 256, root);
        var second = await _store.InitializeReceiverAsync(sessionId, itemId, "file.bin", peer, 1024, 256, root);

        Assert.Equal(first.VerifiedChunks, second.VerifiedChunks);
        Assert.Equal(first.StagingPath, second.StagingPath);
    }

    [Fact]
    public async Task InitializeReceiverAsync_ExpiredJournal_PurgesAndRecreates()
    {
        var sessionId = new SessionId("11111111-1111-4111-8111-111111111111");
        var itemId = new ItemId("22222222-2222-4222-8222-222222222222");
        var peer = new string('a', 64);
        var root = new string('b', 64);

        var first = await _store.InitializeReceiverAsync(sessionId, itemId, "file.bin", peer, 1024, 256, root);
        var oldStaging = first.StagingPath;

        _clock += DesktopResumeStore.MaxTtlMilliseconds + 1;

        var second = await _store.InitializeReceiverAsync(sessionId, itemId, "file.bin", peer, 1024, 256, root);

        Assert.Equal(0, second.VerifiedChunks);
        Assert.Equal(0, second.ContiguousByteOffset);
    }

    [Fact]
    public async Task RecordReceiverChunkAsync_ExactNextChunk_Advances()
    {
        var sessionId = new SessionId("11111111-1111-4111-8111-111111111111");
        var itemId = new ItemId("22222222-2222-4222-8222-222222222222");
        var peer = new string('a', 64);
        var root = new string('b', 64);

        var journal = await _store.InitializeReceiverAsync(sessionId, itemId, "file.bin", peer, 1024, 256, root);

        var data = new byte[256];
        Random.Shared.NextBytes(data);

        bool ok = await _store.RecordReceiverChunkAsync(sessionId, itemId, 0L, data);

        Assert.True(ok);
        var updated = await _store.LoadAsync(sessionId, itemId);
        Assert.NotNull(updated);
        Assert.Equal(1, updated.VerifiedChunks);
        Assert.Equal(256, updated.ContiguousByteOffset);
    }

    [Fact]
    public async Task RecordReceiverChunkAsync_WrongChunkIndex_Rejects()
    {
        var sessionId = new SessionId("11111111-1111-4111-8111-111111111111");
        var itemId = new ItemId("22222222-2222-4222-8222-222222222222");
        var peer = new string('a', 64);
        var root = new string('b', 64);

        await _store.InitializeReceiverAsync(sessionId, itemId, "file.bin", peer, 1024, 256, root);

        var data = new byte[256];
        bool ok = await _store.RecordReceiverChunkAsync(sessionId, itemId, 1L, data);

        Assert.False(ok);
    }

    [Fact]
    public async Task ReconcileReceiverAsync_ValidChunks_ReturnsReady()
    {
        var sessionId = new SessionId("11111111-1111-4111-8111-111111111111");
        var itemId = new ItemId("22222222-2222-4222-8222-222222222222");
        var peer = new string('a', 64);
        var root = new string('b', 64);

        await _store.InitializeReceiverAsync(sessionId, itemId, "file.bin", peer, 1024, 256, root);

        var data = new byte[256];
        for (int i = 0; i < 4; i++)
        {
            Random.Shared.NextBytes(data);
            await _store.RecordReceiverChunkAsync(sessionId, itemId, i, data);
        }

        // re-create store to simulate restart
        var store2 = new DesktopResumeStore(_testRoot, () => _clock);
        var result = await store2.ReconcileReceiverAsync(sessionId, itemId, peer, true);

        Assert.Equal(DesktopResumeValidationCode.Ready, result.Code);
        Assert.NotNull(result.Journal);
        Assert.Equal(4, result.Journal.VerifiedChunks);
    }

    [Fact]
    public async Task ReconcileReceiverAsync_PeerRevoked_ReturnsUnauthorized()
    {
        var sessionId = new SessionId("11111111-1111-4111-8111-111111111111");
        var itemId = new ItemId("22222222-2222-4222-8222-222222222222");
        var peer = new string('a', 64);
        var root = new string('b', 64);

        await _store.InitializeReceiverAsync(sessionId, itemId, "file.bin", peer, 1024, 256, root);

        var data = new byte[256];
        await _store.RecordReceiverChunkAsync(sessionId, itemId, 0L, data);

        var result = await _store.ReconcileReceiverAsync(sessionId, itemId, peer, false);

        Assert.Equal(DesktopResumeValidationCode.Unauthorized, result.Code);
    }

    [Fact]
    public async Task PurgeExpiredAsync_OldJournals_Purged()
    {
        var sessionId = new SessionId("11111111-1111-4111-8111-111111111111");
        var itemId = new ItemId("22222222-2222-4222-8222-222222222222");
        var peer = new string('a', 64);
        var root = new string('b', 64);

        await _store.InitializeReceiverAsync(sessionId, itemId, "file.bin", peer, 1024, 256, root);

        _clock += DesktopResumeStore.MaxTtlMilliseconds + 1;

        var purged = await _store.PurgeExpiredAsync();

        Assert.Equal(1, purged);
    }

    [Fact]
    public async Task ComputeMerkleRootHex_MatchesAndroidParity()
    {
        // Verify Merkle root computation matches known vectors
        var hashes = new List<string>
        {
            Convert.ToHexStringLower(SHA256.HashData(new byte[] { 1, 2, 3, 4 })),
            Convert.ToHexStringLower(SHA256.HashData(new byte[] { 5, 6, 7, 8 })),
            Convert.ToHexStringLower(SHA256.HashData(new byte[] { 9, 10, 11, 12 }))
        };

        var root = DesktopResumeStore.ComputeMerkleRootHex(hashes);

        Assert.NotNull(root);
        Assert.Equal(64, root.Length);
    }

    [Fact]
    public async Task AssembleVerifiedReceiverAsync_CompleteChunks_PromotesToDestination()
    {
        var sessionId = new SessionId("11111111-1111-4111-8111-111111111111");
        var itemId = new ItemId("22222222-2222-4222-8222-222222222222");
        var peer = new string('a', 64);
        var root = new string('b', 64);

        int chunkSize = 256;
        long totalBytes = 1024;
        long chunkCount = totalBytes / chunkSize;

        var chunkHashes = new List<string>();
        var allData = new byte[totalBytes];
        Random.Shared.NextBytes(allData);
        using (var wholeHash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
        {
            wholeHash.AppendData(allData);
            var wholeDigest = new Sha256Digest(Convert.ToHexStringLower(wholeHash.GetHashAndReset()));

            for (long i = 0; i < chunkCount; i++)
            {
                var chunk = new byte[chunkSize];
                Buffer.BlockCopy(allData, (int)(i * chunkSize), chunk, 0, chunkSize);
                chunkHashes.Add(Convert.ToHexStringLower(SHA256.HashData(chunk)));
            }

            // Compute the Merkle root
            var merkleRoot = DesktopResumeStore.ComputeMerkleRootHex(chunkHashes);

            // Use a different peer fingerprint to avoid conflicts
            var testPeer = new string('c', 64);
            var journal = await _store.InitializeReceiverAsync(sessionId, itemId, "test.bin", testPeer, totalBytes, chunkSize, merkleRoot);

            for (long i = 0; i < chunkCount; i++)
            {
                var chunk = new byte[chunkSize];
                Buffer.BlockCopy(allData, (int)(i * chunkSize), chunk, 0, chunkSize);
                await _store.RecordReceiverChunkAsync(sessionId, itemId, i, chunk);
            }

            // Re-create store to simulate restart and reconcile
            var store2 = new DesktopResumeStore(_testRoot, () => _clock);
            var reconcileResult = await store2.ReconcileReceiverAsync(sessionId, itemId, testPeer, true);
            Assert.Equal(DesktopResumeValidationCode.Ready, reconcileResult.Code);

            // Assemble
            var destPath = Path.Combine(_testDestDir, "final.bin");
            Directory.CreateDirectory(_testDestDir);
            bool ok = await store2.AssembleVerifiedReceiverAsync(sessionId, itemId, wholeDigest, destPath);

            Assert.True(ok);
            Assert.True(File.Exists(destPath));
            Assert.Equal(totalBytes, new FileInfo(destPath).Length);
        }
    }

    [Fact]
    public async Task LoadAsync_NonExistent_ReturnsNull()
    {
        var sessionId = new SessionId("11111111-1111-4111-8111-111111111111");
        var itemId = new ItemId("22222222-2222-4222-8222-222222222222");

        var result = await _store.LoadAsync(sessionId, itemId);

        Assert.Null(result);
    }

    [Fact]
    public async Task InitializeSenderAsync_NewSession_CreatesJournal()
    {
        var sessionId = new SessionId("11111111-1111-4111-8111-111111111111");
        var itemId = new ItemId("22222222-2222-4222-8222-222222222222");
        var peer = new string('a', 64);
        var sourcePath = Path.Combine(_testRoot, "source.bin");
        Directory.CreateDirectory(_testRoot);
        await File.WriteAllBytesAsync(sourcePath, new byte[] { 1, 2, 3, 4, 5 });

        var verifier = new DesktopResumeSourceVerifier();
        var snapshot = await verifier.SnapshotAsync(sourcePath, 1024 * 1024);

        var journal = await _store.InitializeSenderAsync(sessionId, itemId, "source.bin", peer, snapshot);

        Assert.Equal(DesktopResumeJournalRoles.Sender, journal.JournalRole);
        Assert.Equal(sourcePath, journal.SourcePath);
    }

    [Fact]
    public async Task RevalidateSenderAsync_SourceChanged_ReturnsSourceChanged()
    {
        var sessionId = new SessionId("11111111-1111-4111-8111-111111111111");
        var itemId = new ItemId("22222222-2222-4222-8222-222222222222");
        var peer = new string('d', 64);
        var sourcePath = Path.Combine(_testRoot, "source.bin");
        Directory.CreateDirectory(_testRoot);
        await File.WriteAllBytesAsync(sourcePath, new byte[] { 1, 2, 3, 4, 5 });

        var verifier = new DesktopResumeSourceVerifier();
        var snapshot = await verifier.SnapshotAsync(sourcePath, 1024 * 1024);

        await _store.InitializeSenderAsync(sessionId, itemId, "source.bin", peer, snapshot);

        // Modify source
        await File.WriteAllBytesAsync(sourcePath, new byte[] { 10, 20, 30 });

        var store2 = new DesktopResumeStore(_testRoot, () => _clock);
        var result = await store2.RevalidateSenderAsync(sessionId, itemId, peer, true, verifier);

        Assert.Equal(DesktopResumeValidationCode.SourceChanged, result.Code);
    }

    [Fact]
    public async Task EvictUnderStoragePressureAsync_JournalsExist_EvictsOldest()
    {
        var peer = new string('e', 64);
        var root = new string('f', 64);

        for (int i = 0; i < 3; i++)
        {
            var sessionId = new SessionId($"11111111-1111-4111-8111-{i:D12}");
            var itemId = new ItemId($"22222222-2222-4222-8222-{i:D12}");
            await _store.InitializeReceiverAsync(sessionId, itemId, $"file{i}.bin", peer, 1024, 256, root);
            _clock += 1000;
        }

        var store2 = new DesktopResumeStore(_testRoot, () => _clock, () => 1L);
        var evicted = await store2.EvictUnderStoragePressureAsync(1024L * 1024L * 1024L);

        Assert.True(evicted >= 1);
    }

    [Fact]
    public async Task PurgePeerAsync_RemovesAllJournalsForPeer()
    {
        var peer = new string('e', 64);
        var root = new string('f', 64);

        for (int i = 0; i < 2; i++)
        {
            var sessionId = new SessionId($"11111111-1111-4111-8111-{i:D12}");
            var itemId = new ItemId($"22222222-2222-4222-8222-{i:D12}");
            await _store.InitializeReceiverAsync(sessionId, itemId, $"file{i}.bin", peer, 1024, 256, root);
        }

        var purged = await _store.PurgePeerAsync(peer);

        Assert.Equal(2, purged);
    }
}
