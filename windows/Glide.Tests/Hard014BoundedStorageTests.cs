using System.Security.Cryptography;
using Glide.Core.Models;
using Glide.Core.Security;
using Glide.Core.Storage;

namespace Glide.Tests;

public sealed class Hard014BoundedStorageTests : IDisposable
{
    private readonly string _root =
        Path.Combine(
            Path.GetTempPath(),
            "glide-hard014-storage-" + Guid.NewGuid().ToString("N"));

    public Hard014BoundedStorageTests()
    {
        Directory.CreateDirectory(_root);
    }

    [Fact]
    public void StreamingSha256_EmptyPayload_UsesCanonicalDigest()
    {
        using var hash = new StreamingSha256();

        Sha256Digest digest = hash.Complete();

        Assert.Equal(0L, hash.BytesProcessed);
        Assert.Equal(
            "e3b0c44298fc1c149afbf4c8996fb924" +
            "27ae41e4649b934ca495991b7852b855",
            digest.Value);
    }

    [Theory]
    [InlineData(1L * 1024 * 1024 * 1024)]
    [InlineData((5L * 1024 * 1024 * 1024) + (3L * 1024 * 1024))]
    public void StreamingSha256_LargeLogicalPayload_UsesLongCounterWithoutAllocationScaling(
        long logicalSize)
    {
        byte[] block = new byte[1024 * 1024];
        using var hash = new StreamingSha256();

        // Warm the append path before allocation accounting so JIT setup is not attributed to
        // payload processing.
        hash.Append(ReadOnlySpan<byte>.Empty);

        GC.Collect();
        GC.WaitForPendingFinalizers();
        GC.Collect();
        long before =
            GC.GetAllocatedBytesForCurrentThread();

        long remaining = logicalSize;
        while (remaining > 0)
        {
            int take =
                (int)Math.Min(
                    block.Length,
                    remaining);
            hash.Append(
                block.AsSpan(0, take));
            remaining -= take;
        }

        Sha256Digest digest = hash.Complete();

        long allocated =
            GC.GetAllocatedBytesForCurrentThread() -
            before;

        Assert.Equal(logicalSize, hash.BytesProcessed);
        Assert.Equal(64, digest.Value.Length);
        Assert.InRange(
            allocated,
            0L,
            2L * 1024 * 1024);
    }

    [Fact]
    public void Reservations_UseInt64AccountingForOneAndFiveGiB()
    {
        const long oneGiB =
            1L * 1024 * 1024 * 1024;
        const long fiveGiB =
            5L * 1024 * 1024 * 1024;
        const long available =
            7L * 1024 * 1024 * 1024;

        var store = CreateStore(
            "large-reservation",
            () => available);

        using IncomingFileReservation one =
            store.ReserveIncoming(
                NewSessionId(),
                NewItemId(),
                "one-gib.bin",
                oneGiB);

        using IncomingFileReservation five =
            store.ReserveIncoming(
                NewSessionId(),
                NewItemId(),
                "five-gib.bin",
                fiveGiB);

        Assert.Equal(
            oneGiB + fiveGiB,
            store.ReservedBytes);
    }

    [Fact]
    public void Reservations_AreCumulativeAndReleasedAfterFailureOrDispose()
    {
        var store = CreateStore(
            "reservation-budget",
            () => 100);

        SessionId session = NewSessionId();

        using IncomingFileReservation first =
            store.ReserveIncoming(
                session,
                NewItemId(),
                "first.bin",
                60);

        ProtocolViolationException ex =
            Assert.Throws<ProtocolViolationException>(
                () => store.ReserveIncoming(
                    session,
                    NewItemId(),
                    "second.bin",
                    41));

        Assert.Equal(
            WireErrorCodes.InsufficientStorage,
            ex.FailureCode);
        Assert.Equal(60L, store.ReservedBytes);

        first.Dispose();
        Assert.Equal(0L, store.ReservedBytes);

        using IncomingFileReservation retry =
            store.ReserveIncoming(
                session,
                NewItemId(),
                "second.bin",
                41);

        Assert.Equal(41L, store.ReservedBytes);
    }

    [Fact]
    public async Task TenMiBWriter_StreamsHashesAndCommitsWithBoundedMemory()
    {
        const int blockSize = 64 * 1024;
        const int totalSize = 10 * 1024 * 1024;

        var store = CreateStore(
            "ten-mib",
            () => 100L * 1024 * 1024);

        SessionId session = NewSessionId();
        ItemId item = NewItemId();

        using IncomingFileReservation reservation =
            store.ReserveIncoming(
                session,
                item,
                "nested/ten-mib.bin",
                totalSize);

        await using QuarantineWriteSession writer =
            reservation.OpenWriter();

        byte[] block = new byte[blockSize];
        RandomNumberGenerator.Fill(block);

        using var expected =
            IncrementalHash.CreateHash(
                HashAlgorithmName.SHA256);

        int remaining = totalSize;
        while (remaining > 0)
        {
            int take =
                Math.Min(
                    block.Length,
                    remaining);

            expected.AppendData(
                block,
                0,
                take);

            await writer.WriteAsync(
                block.AsMemory(0, take));

            remaining -= take;
        }

        var expectedDigest =
            new Sha256Digest(
                Convert.ToHexStringLower(
                    expected.GetHashAndReset()));

        (string committedPath, Sha256Digest actualDigest) =
            await writer.CompleteAndCommitAsync(
                expectedDigest);

        Assert.Equal((long)totalSize, writer.WrittenBytes);
        Assert.Equal(expectedDigest, actualDigest);
        Assert.Equal((long)totalSize, new FileInfo(committedPath).Length);
        Assert.False(
            File.Exists(
                store.GetQuarantinePath(
                    session,
                    item)));
        Assert.Equal(0L, store.ReservedBytes);

    }

    [Fact]
    public async Task EmptyWriter_CommitsCanonicalZeroLengthFile()
    {
        var store = CreateStore(
            "empty",
            () => 1024 * 1024);

        SessionId session = NewSessionId();
        ItemId item = NewItemId();

        using IncomingFileReservation reservation =
            store.ReserveIncoming(
                session,
                item,
                "empty.bin",
                0);

        await using QuarantineWriteSession writer =
            reservation.OpenWriter();

        var expected =
            new Sha256Digest(
                "e3b0c44298fc1c149afbf4c8996fb924" +
                "27ae41e4649b934ca495991b7852b855");

        (string path, Sha256Digest actual) =
            await writer.CompleteAndCommitAsync(
                expected);

        Assert.Equal(expected, actual);
        Assert.Equal(0L, new FileInfo(path).Length);
        Assert.Equal(0L, store.ReservedBytes);
    }

    [Fact]
    public async Task AbandonedWriter_PurgesPartialQuarantineAndReleasesReservation()
    {
        var store = CreateStore(
            "cancel",
            () => 1024 * 1024);

        SessionId session = NewSessionId();
        ItemId item = NewItemId();
        string quarantine =
            store.GetQuarantinePath(
                session,
                item);

        using IncomingFileReservation reservation =
            store.ReserveIncoming(
                session,
                item,
                "cancel.bin",
                4096);

        await using (QuarantineWriteSession writer =
            reservation.OpenWriter())
        {
            await writer.WriteAsync(
                new byte[1024]);
            Assert.True(File.Exists(quarantine));
        }

        Assert.False(File.Exists(quarantine));
        Assert.Equal(0L, store.ReservedBytes);
    }

    [Fact]
    public async Task CancelledWrite_PurgesPartialAndReleasesReservation()
    {
        var store = CreateStore(
            "cancel-token",
            () => 1024 * 1024);

        SessionId session = NewSessionId();
        ItemId item = NewItemId();
        string quarantine =
            store.GetQuarantinePath(
                session,
                item);

        using IncomingFileReservation reservation =
            store.ReserveIncoming(
                session,
                item,
                "cancel-token.bin",
                4096);

        await using (QuarantineWriteSession writer =
            reservation.OpenWriter())
        {
            await writer.WriteAsync(
                new byte[1024]);

            using var cts =
                new CancellationTokenSource();
            cts.Cancel();

            await Assert.ThrowsAnyAsync<OperationCanceledException>(
                async () =>
                    await writer.WriteAsync(
                        new byte[1024],
                        cts.Token));
        }

        Assert.False(File.Exists(quarantine));
        Assert.Equal(0L, store.ReservedBytes);
    }

    [Fact]
    public void Reservation_RejectsTraversalBeforeCreatingQuarantine()
    {
        var store = CreateStore(
            "traversal",
            () => 1024 * 1024);

        SessionId session = NewSessionId();
        ItemId item = NewItemId();

        Assert.Throws<ArgumentException>(
            () => store.ReserveIncoming(
                session,
                item,
                "../escape.bin",
                1));

        Assert.False(
            File.Exists(
                store.GetQuarantinePath(
                    session,
                    item)));
        Assert.Equal(0L, store.ReservedBytes);
    }

    [Fact]
    public void Reservation_RejectsExistingDestinationReparsePoint_WhenPlatformAllowsCreation()
    {
        string scope =
            Path.Combine(_root, "reparse");
        string staging =
            Path.Combine(scope, "staging");
        string destination =
            Path.Combine(scope, "destination");
        string outside =
            Path.Combine(scope, "outside");

        Directory.CreateDirectory(destination);
        Directory.CreateDirectory(outside);

        string link =
            Path.Combine(
                destination,
                "linked");

        try
        {
            Directory.CreateSymbolicLink(
                link,
                outside);
        }
        catch (Exception ex)
            when (ex is UnauthorizedAccessException or
                     IOException or
                     PlatformNotSupportedException)
        {
            // Some Windows CI hosts do not grant symbolic-link creation. The same source runs
            // unconditionally on Linux, where this branch is exercised by the shared test job.
            return;
        }

        var store =
            new QuarantineStagingStore(
                staging,
                destination,
                () => 1024 * 1024);

        ProtocolViolationException failure =
            Assert.Throws<ProtocolViolationException>(
                () => store.ReserveIncoming(
                    NewSessionId(),
                    NewItemId(),
                    "linked/escape.bin",
                    1));

        Assert.Equal(
            WireErrorCodes.DestinationUnavailable,
            failure.FailureCode);
        Assert.Equal(0L, store.ReservedBytes);
    }

    [Fact]
    public async Task ConcurrentDuplicateNames_CommitToDistinctCollisionSafePaths()
    {
        string scope =
            Path.Combine(
                _root,
                "duplicates");
        string staging =
            Path.Combine(
                scope,
                "staging");
        string destination =
            Path.Combine(
                scope,
                "destination");

        var firstStore =
            new QuarantineStagingStore(
                staging,
                destination,
                () => 1024 * 1024);
        var secondStore =
            new QuarantineStagingStore(
                staging,
                destination,
                () => 1024 * 1024);

        SessionId session = NewSessionId();

        using IncomingFileReservation firstReservation =
            firstStore.ReserveIncoming(
                session,
                NewItemId(),
                "report.txt",
                1);
        using IncomingFileReservation secondReservation =
            secondStore.ReserveIncoming(
                session,
                NewItemId(),
                "report.txt",
                1);

        await using QuarantineWriteSession first =
            firstReservation.OpenWriter();
        await using QuarantineWriteSession second =
            secondReservation.OpenWriter();

        await first.WriteAsync(new byte[] { 0x01 });
        await second.WriteAsync(new byte[] { 0x02 });

        var digestOne =
            new Sha256Digest(
                Convert.ToHexStringLower(
                    SHA256.HashData(
                        new byte[] { 0x01 })));
        var digestTwo =
            new Sha256Digest(
                Convert.ToHexStringLower(
                    SHA256.HashData(
                        new byte[] { 0x02 })));

        Task<(string committedPath, Sha256Digest actualDigest)> firstCommit =
            first.CompleteAndCommitAsync(digestOne);
        Task<(string committedPath, Sha256Digest actualDigest)> secondCommit =
            second.CompleteAndCommitAsync(digestTwo);

        var results =
            await Task.WhenAll(
                firstCommit,
                secondCommit);

        string[] names =
            results
                .Select(result =>
                    Path.GetFileName(
                        result.committedPath))
                .OrderBy(name => name, StringComparer.Ordinal)
                .ToArray();

        Assert.Equal(
            new[]
            {
                "report (1).txt",
                "report.txt"
            },
            names);
        Assert.Equal(0L, firstStore.ReservedBytes);
        Assert.Equal(0L, secondStore.ReservedBytes);
    }

    private QuarantineStagingStore CreateStore(
        string name,
        Func<long> freeSpaceProvider)
    {
        string scope =
            Path.Combine(
                _root,
                name);

        return new QuarantineStagingStore(
            Path.Combine(scope, "staging"),
            Path.Combine(scope, "destination"),
            freeSpaceProvider);
    }

    private static SessionId NewSessionId() =>
        new(
            Guid.NewGuid()
                .ToString("D")
                .ToLowerInvariant());

    private static ItemId NewItemId() =>
        new(
            Guid.NewGuid()
                .ToString("D")
                .ToLowerInvariant());

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_root))
            {
                Directory.Delete(
                    _root,
                    recursive: true);
            }
        }
        catch
        {
            // Best-effort test cleanup.
        }
    }
}
