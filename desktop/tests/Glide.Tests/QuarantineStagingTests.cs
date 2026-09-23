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
}
