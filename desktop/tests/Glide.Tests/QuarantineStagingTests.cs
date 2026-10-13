using System.Security.Cryptography;
using System.Text;
using Glide.Core.Models;
using Glide.Core.Storage;
using Xunit;

namespace Glide.Tests;

public class QuarantineStagingTests : IDisposable
{
    private readonly string _testStagingDir;
    private readonly string _testDestDir;
    private readonly QuarantineStagingStore _store;

    public QuarantineStagingTests()
    {
        _testStagingDir = Path.Combine(Path.GetTempPath(), "GlideTest_Staging_" + Guid.NewGuid().ToString("N"));
        _testDestDir = Path.Combine(Path.GetTempPath(), "GlideTest_Dest_" + Guid.NewGuid().ToString("N"));
        _store = new QuarantineStagingStore(_testStagingDir, _testDestDir);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_testStagingDir)) Directory.Delete(_testStagingDir, true);
            if (Directory.Exists(_testDestDir)) Directory.Delete(_testDestDir, true);
        }
        catch
        {
            // Ignore cleanup exceptions
        }
    }

    [Fact]
    public async Task DestinationPreflight_RequiresWritableDirectoryAndLeavesNoProbe()
    {
        await _store.VerifyDestinationWritableAsync();
        Assert.Empty(Directory.GetFiles(_testDestDir, ".glide-write-check-*.tmp"));

        string occupiedPath = Path.Combine(_testDestDir, "occupied");
        await File.WriteAllTextAsync(occupiedPath, "file, not folder");
        var invalidStore = new QuarantineStagingStore(_testStagingDir, occupiedPath);
        await Assert.ThrowsAnyAsync<IOException>(() => invalidStore.VerifyDestinationWritableAsync());
    }

    [Fact]
    public async Task WriteAndCommit_ValidFile_QuarantinesAndCommitsAtomically()
    {
        var sessionId = new SessionId("11111111-1111-4111-8111-111111111111");
        var itemId = new ItemId("22222222-2222-4222-8222-222222222222");
        byte[] payload = Encoding.UTF8.GetBytes("Hello, Glide Android-to-Windows transfer!");
        byte[] hash = SHA256.HashData(payload);
        var expectedDigest = new Sha256Digest(Convert.ToHexStringLower(hash));

        using var memStream = new MemoryStream(payload);
        var (committedPath, actualDigest) = await _store.WriteAndCommitAsync(
            sessionId,
            itemId,
            "docs/greeting.txt",
            memStream,
            expectedDigest,
            payload.Length);

        Assert.True(File.Exists(committedPath));
        Assert.Equal(expectedDigest, actualDigest);
        Assert.Equal(payload, await File.ReadAllBytesAsync(committedPath));

        // Quarantine file must no longer exist (moved atomically)
        Assert.False(File.Exists(_store.GetQuarantinePath(sessionId, itemId)));
    }

    [Fact]
    public async Task WriteAndCommit_DigestMismatch_PurgesQuarantineAndThrows()
    {
        var sessionId = new SessionId("11111111-1111-4111-8111-111111111111");
        var itemId = new ItemId("22222222-2222-4222-8222-222222222222");
        byte[] payload = Encoding.UTF8.GetBytes("Payload with tamper!");
        var expectedDigest = new Sha256Digest("0000000000000000000000000000000000000000000000000000000000000000");

        using var memStream = new MemoryStream(payload);
        await Assert.ThrowsAsync<ProtocolViolationException>(async () =>
        {
            await _store.WriteAndCommitAsync(
                sessionId,
                itemId,
                "tampered.txt",
                memStream,
                expectedDigest,
                payload.Length);
        });

        // Staging file must be purged
        Assert.False(File.Exists(_store.GetQuarantinePath(sessionId, itemId)));
    }

    [Fact]
    public async Task WriteAndCommit_ExistingFile_ResolvesCollisionMonotonically()
    {
        var sessionId = new SessionId("11111111-1111-4111-8111-111111111111");
        var itemId1 = new ItemId("22222222-2222-4222-8222-222222222222");
        var itemId2 = new ItemId("33333333-3333-4333-8333-333333333333");

        byte[] payload1 = Encoding.UTF8.GetBytes("File 1");
        var digest1 = new Sha256Digest(Convert.ToHexStringLower(SHA256.HashData(payload1)));

        byte[] payload2 = Encoding.UTF8.GetBytes("File 2");
        var digest2 = new Sha256Digest(Convert.ToHexStringLower(SHA256.HashData(payload2)));

        using var memStream1 = new MemoryStream(payload1);
        var (path1, _) = await _store.WriteAndCommitAsync(sessionId, itemId1, "report.txt", memStream1, digest1, payload1.Length);

        using var memStream2 = new MemoryStream(payload2);
        var (path2, _) = await _store.WriteAndCommitAsync(sessionId, itemId2, "report.txt", memStream2, digest2, payload2.Length);

        Assert.Equal("report.txt", Path.GetFileName(path1));
        Assert.Equal("report (1).txt", Path.GetFileName(path2));
        Assert.True(File.Exists(path1));
        Assert.True(File.Exists(path2));
    }

    [Fact]
    public async Task CommitVerified_CopyDigestMismatch_LeavesNoPublicOrTemporaryFile()
    {
        var sessionId = new SessionId("11111111-1111-4111-8111-111111111111");
        var itemId = new ItemId("22222222-2222-4222-8222-222222222222");
        await using (var stream = _store.OpenQuarantineWrite(sessionId, itemId))
            await stream.WriteAsync("changed"u8.ToArray());

        var expectedDigest = new Sha256Digest(new string('0', 64));
        await Assert.ThrowsAsync<IOException>(() => _store.CommitVerifiedQuarantineFileAsync(
            sessionId, itemId, "docs/received.txt", expectedDigest, 7));

        Assert.False(File.Exists(Path.Combine(_testDestDir, "docs", "received.txt")));
        Assert.Empty(Directory.GetFiles(_testDestDir, ".glide-commit-*.part",
            SearchOption.AllDirectories));
    }

    [Fact]
    public void QuarantineWrite_ExistingPart_IsNeverOverwritten()
    {
        var sessionId = new SessionId("11111111-1111-4111-8111-111111111111");
        var itemId = new ItemId("22222222-2222-4222-8222-222222222222");
        using var first = _store.OpenQuarantineWrite(sessionId, itemId);
        Assert.Throws<IOException>(() => _store.OpenQuarantineWrite(sessionId, itemId));
    }

    [Fact]
    public void PurgeAbandonedCommitFiles_RemovesOnlyOldGlideTemporaryFiles()
    {
        string nested = Path.Combine(_testDestDir, "received");
        Directory.CreateDirectory(nested);
        string oldCommit = Path.Combine(nested,
            $".glide-commit-{Guid.NewGuid():N}.part");
        string recentCommit = Path.Combine(nested,
            $".glide-commit-{Guid.NewGuid():N}.part");
        string unrelated = Path.Combine(nested, ".glide-commit-not-a-guid.part");
        File.WriteAllText(oldCommit, "old verified bytes");
        File.WriteAllText(recentCommit, "active bytes");
        File.WriteAllText(unrelated, "user data");
        File.SetLastWriteTimeUtc(oldCommit, DateTime.UtcNow.AddHours(-1));
        File.SetLastWriteTimeUtc(unrelated, DateTime.UtcNow.AddHours(-1));

        Assert.Equal(1, _store.PurgeAbandonedCommitFiles());
        Assert.False(File.Exists(oldCommit));
        Assert.True(File.Exists(recentCommit));
        Assert.True(File.Exists(unrelated));
    }

    [Fact]
    public async Task WriteAndCommit_OversizedDeclaredLength_RejectsBeforeReading()
    {
        var sessionId = SessionId.NewRandom();
        var itemId = ItemId.NewRandom();
        using var source = new MemoryStream("unread"u8.ToArray());
        var digest = new Sha256Digest(new string('0', 64));

        await Assert.ThrowsAsync<ProtocolViolationException>(() =>
            _store.WriteAndCommitAsync(sessionId, itemId, "file.txt", source, digest,
                Glide.Core.Codec.ProtocolCatalog.MaxItemBytes + 1));
        Assert.Equal(0, source.Position);
        Assert.False(File.Exists(_store.GetQuarantinePath(sessionId, itemId)));
    }

    [Fact]
    public void PurgeAbandonedQuarantineSessions_PreservesRecentSession()
    {
        var oldSession = SessionId.NewRandom();
        var recentSession = SessionId.NewRandom();
        var item = ItemId.NewRandom();
        using (_store.OpenQuarantineWrite(oldSession, item)) { }
        using (_store.OpenQuarantineWrite(recentSession, item)) { }
        string oldPath = _store.GetQuarantinePath(oldSession, item);
        string recentPath = _store.GetQuarantinePath(recentSession, item);
        File.SetLastWriteTimeUtc(oldPath, DateTime.UtcNow.AddDays(-2));

        Assert.Equal(1, _store.PurgeAbandonedQuarantineSessions());
        Assert.False(File.Exists(oldPath));
        Assert.False(Directory.Exists(Path.GetDirectoryName(oldPath)));
        Assert.True(File.Exists(recentPath));
    }
}
