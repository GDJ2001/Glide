using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Glide.Core.Models;

namespace Glide.Core.Storage;

public static class DesktopResumeJournalRoles
{
    public const string Sender = "sender";
    public const string Receiver = "receiver";
}

public sealed record DesktopResumeJournal(
    string OriginalSessionId,
    string ItemId,
    string JournalRole,
    string ItemName,
    string PeerFingerprintHex,
    string? SourcePath,
    string? SourceIdentity,
    long TotalBytes,
    int ChunkSize,
    long VerifiedChunks,
    long ContiguousByteOffset,
    string ChunkRootHashHex,
    List<string> VerifiedChunkHashesHex,
    string? WholeFileSha256Hex,
    long CreatedAtUnixMs,
    long UpdatedAtUnixMs,
    long ExpiresAtUnixMs,
    string? StagingPath);

public enum DesktopResumeValidationCode
{
    Ready,
    NotFound,
    Expired,
    Unauthorized,
    JournalMismatch,
    SourceUnavailable,
    SourceChanged
}

public sealed record DesktopResumeValidationResult(
    DesktopResumeValidationCode Code,
    DesktopResumeJournal? Journal = null);

public sealed record DesktopResumeSourceSnapshot(
    string SourcePath,
    string SourceIdentity,
    long TotalBytes,
    int ChunkSize,
    string ChunkRootHashHex,
    IReadOnlyList<string> ChunkHashesHex,
    string WholeFileSha256Hex,
    DateTime LastWriteTimeUtc);

/// <summary>
/// Cross-platform sender source verifier for HARD-023.
///
/// A remembered path is never treated as source continuity proof. Every resume attempt reopens the
/// source and performs a bounded complete read, recomputing the chunk Merkle root and whole-file
/// SHA-256. Windows path identity is case-normalized; Linux remains case-sensitive.
/// </summary>
public sealed class DesktopResumeSourceVerifier
{
    public const int DefaultChunkSize = 1024 * 1024;
    public const int MaxChunkSize = 16 * 1024 * 1024;
    private const int ReadBufferBytes = 64 * 1024;
    private const string SourceIdentityDomain =
        "GLIDE-DESKTOP-SOURCE-V1";

    public async Task<DesktopResumeSourceSnapshot> SnapshotAsync(
        string sourcePath,
        int chunkSize = DefaultChunkSize,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(sourcePath);
        if (chunkSize is < 1 or > MaxChunkSize)
        {
            throw new ArgumentOutOfRangeException(
                nameof(chunkSize),
                "Resume chunk size must be in 1..16 MiB.");
        }

        string fullPath =
            Path.GetFullPath(sourcePath);
        var before =
            new FileInfo(fullPath);
        if (!before.Exists)
        {
            throw new FileNotFoundException(
                "Resume source no longer exists.",
                fullPath);
        }

        if (before.Length <= 0 ||
            before.Length >
                ProtocolCatalogLimits.MaxItemBytes)
        {
            throw new InvalidDataException(
                "Resume source size is outside the supported item range.");
        }

        long initialLength =
            before.Length;
        DateTime initialWriteTime =
            before.LastWriteTimeUtc;

        var chunkHashes =
            new List<string>(
                checked(
                    (int)Math.Min(
                        int.MaxValue,
                        (initialLength +
                         chunkSize - 1L) /
                        chunkSize)));
        using var wholeHash =
            IncrementalHash.CreateHash(
                HashAlgorithmName.SHA256);

        byte[] readBuffer =
            new byte[ReadBufferBytes];
        byte[] chunkBuffer =
            new byte[chunkSize];
        int chunkUsed = 0;
        long totalRead = 0;

        await using var stream =
            new FileStream(
                fullPath,
                FileMode.Open,
                FileAccess.Read,
                FileShare.Read,
                ReadBufferBytes,
                FileOptions.Asynchronous |
                FileOptions.SequentialScan);

        while (true)
        {
            cancellationToken
                .ThrowIfCancellationRequested();

            int read =
                await stream.ReadAsync(
                    readBuffer.AsMemory(
                        0,
                        readBuffer.Length),
                    cancellationToken);
            if (read == 0)
            {
                break;
            }

            totalRead =
                checked(totalRead + read);
            if (totalRead > initialLength)
            {
                throw new IOException(
                    "Resume source grew while it was being verified.");
            }

            wholeHash.AppendData(
                readBuffer,
                0,
                read);

            int offset = 0;
            while (offset < read)
            {
                int copy =
                    Math.Min(
                        read - offset,
                        chunkSize - chunkUsed);
                Buffer.BlockCopy(
                    readBuffer,
                    offset,
                    chunkBuffer,
                    chunkUsed,
                    copy);
                offset += copy;
                chunkUsed += copy;

                if (chunkUsed == chunkSize)
                {
                    chunkHashes.Add(
                        HashBytesHex(
                            chunkBuffer));
                    chunkBuffer =
                        new byte[chunkSize];
                    chunkUsed = 0;
                }
            }
        }

        if (totalRead != initialLength)
        {
            throw new EndOfStreamException(
                $"Resume source ended early: expected {initialLength} bytes, read {totalRead}.");
        }

        if (chunkUsed > 0)
        {
            chunkHashes.Add(
                HashBytesHex(
                    chunkBuffer.AsSpan(
                        0,
                        chunkUsed)));
        }

        before.Refresh();
        if (!before.Exists ||
            before.Length != initialLength ||
            before.LastWriteTimeUtc !=
                initialWriteTime)
        {
            throw new IOException(
                "Resume source changed while it was being verified.");
        }

        string wholeFileHash =
            Convert.ToHexStringLower(
                wholeHash.GetHashAndReset());
        string rootHash =
            DesktopResumeStore
                .ComputeMerkleRootHex(
                    chunkHashes);

        return new DesktopResumeSourceSnapshot(
            fullPath,
            ComputeSourceIdentity(
                fullPath),
            initialLength,
            chunkSize,
            rootHash,
            chunkHashes,
            wholeFileHash,
            initialWriteTime);
    }

    public static string ComputeSourceIdentity(
        string sourcePath)
    {
        string fullPath =
            Path.GetFullPath(sourcePath);
        string canonical =
            OperatingSystem.IsWindows()
                ? fullPath.ToUpperInvariant()
                : fullPath;

        byte[] domain =
            Encoding.UTF8.GetBytes(
                SourceIdentityDomain);
        byte[] pathBytes =
            Encoding.UTF8.GetBytes(
                canonical);
        byte[] input =
            new byte[
                domain.Length +
                1 +
                pathBytes.Length];
        Buffer.BlockCopy(
            domain,
            0,
            input,
            0,
            domain.Length);
        Buffer.BlockCopy(
            pathBytes,
            0,
            input,
            domain.Length + 1,
            pathBytes.Length);

        return Convert.ToHexStringLower(
            SHA256.HashData(input));
    }

    private static string HashBytesHex(
        ReadOnlySpan<byte> bytes) =>
        Convert.ToHexStringLower(
            SHA256.HashData(bytes));
}

/// <summary>
/// Dependency-free durable true-resume store for Windows and Linux.
///
/// Journals are written atomically as JSON. Receiver chunks are private per-item files. Progress
/// advances only for the exact next chunk, and each retained chunk is re-hashed after restart.
/// Sender journals persist source authority but never trust path/offset alone.
/// </summary>
public sealed class DesktopResumeStore
{
    public const long MaxTtlMilliseconds =
        24L * 60L * 60L * 1000L;
    public const int MaxChunkSize =
        16 * 1024 * 1024;

    private const int CopyBufferBytes =
        64 * 1024;

    private readonly string _root;
    private readonly string _journalsRoot;
    private readonly string _itemsRoot;
    private readonly Func<long> _nowUnixMs;
    private readonly Func<long>? _freeSpaceProvider;
    private readonly SemaphoreSlim _gate =
        new(1, 1);
    private readonly JsonSerializerOptions _json =
        new()
        {
            WriteIndented = true
        };

    public DesktopResumeStore(
        string? root = null,
        Func<long>? nowUnixMs = null,
        Func<long>? freeSpaceProvider = null)
    {
        _root =
            Path.GetFullPath(
                root ??
                GetDefaultRoot());
        _journalsRoot =
            Path.Combine(
                _root,
                "journals");
        _itemsRoot =
            Path.Combine(
                _root,
                "items");
        _nowUnixMs =
            nowUnixMs ??
            (() =>
                DateTimeOffset.UtcNow
                    .ToUnixTimeMilliseconds());
        _freeSpaceProvider =
            freeSpaceProvider;

        Directory.CreateDirectory(
            _journalsRoot);
        Directory.CreateDirectory(
            _itemsRoot);
    }

    public string Root => _root;

    public async Task<DesktopResumeJournal>
        InitializeReceiverAsync(
            SessionId originalSessionId,
            ItemId itemId,
            string itemName,
            string peerFingerprintHex,
            long totalBytes,
            int chunkSize,
            string chunkRootHashHex,
            CancellationToken cancellationToken =
                default)
    {
        ValidateCommon(
            itemName,
            peerFingerprintHex,
            totalBytes,
            chunkSize,
            chunkRootHashHex);

        await _gate.WaitAsync(
            cancellationToken);
        try
        {
            long now =
                _nowUnixMs();
            DesktopResumeJournal? existing =
                ReadKnownJournalUnsafe(
                    originalSessionId,
                    itemId);
            if (existing != null)
            {
                if (now >=
                    existing.ExpiresAtUnixMs)
                {
                    PurgeKnownUnsafe(
                        originalSessionId,
                        itemId,
                        existing);
                }
                else
                {
                    RequireReceiverAuthority(
                        existing,
                        peerFingerprintHex,
                        totalBytes,
                        chunkSize,
                        chunkRootHashHex);
                    return existing;
                }
            }

            string itemDirectory =
                GetItemDirectory(
                    originalSessionId,
                    itemId);
            Directory.CreateDirectory(
                itemDirectory);

            var journal =
                new DesktopResumeJournal(
                    originalSessionId.Value,
                    itemId.Value,
                    DesktopResumeJournalRoles
                        .Receiver,
                    itemName,
                    peerFingerprintHex,
                    null,
                    null,
                    totalBytes,
                    chunkSize,
                    0,
                    0,
                    chunkRootHashHex,
                    [],
                    null,
                    now,
                    now,
                    checked(
                        now +
                        MaxTtlMilliseconds),
                    itemDirectory);

            PersistUnsafe(
                journal);
            return journal;
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<DesktopResumeJournal>
        InitializeSenderAsync(
            SessionId originalSessionId,
            ItemId itemId,
            string itemName,
            string peerFingerprintHex,
            DesktopResumeSourceSnapshot snapshot,
            CancellationToken cancellationToken =
                default)
    {
        ArgumentNullException.ThrowIfNull(
            snapshot);
        ValidateCommon(
            itemName,
            peerFingerprintHex,
            snapshot.TotalBytes,
            snapshot.ChunkSize,
            snapshot.ChunkRootHashHex);
        RequireCanonicalSha256(
            snapshot.SourceIdentity,
            nameof(snapshot.SourceIdentity));
        RequireCanonicalSha256(
            snapshot.WholeFileSha256Hex,
            nameof(snapshot.WholeFileSha256Hex));

        await _gate.WaitAsync(
            cancellationToken);
        try
        {
            long now =
                _nowUnixMs();
            DesktopResumeJournal? existing =
                ReadKnownJournalUnsafe(
                    originalSessionId,
                    itemId);
            if (existing != null)
            {
                if (now >=
                    existing.ExpiresAtUnixMs)
                {
                    PurgeKnownUnsafe(
                        originalSessionId,
                        itemId,
                        existing);
                }
                else
                {
                    RequireSenderAuthority(
                        existing,
                        peerFingerprintHex,
                        snapshot);
                    return existing;
                }
            }

            var journal =
                new DesktopResumeJournal(
                    originalSessionId.Value,
                    itemId.Value,
                    DesktopResumeJournalRoles
                        .Sender,
                    itemName,
                    peerFingerprintHex,
                    Path.GetFullPath(
                        snapshot.SourcePath),
                    snapshot.SourceIdentity,
                    snapshot.TotalBytes,
                    snapshot.ChunkSize,
                    0,
                    0,
                    snapshot.ChunkRootHashHex,
                    [],
                    snapshot.WholeFileSha256Hex,
                    now,
                    now,
                    checked(
                        now +
                        MaxTtlMilliseconds),
                    null);

            PersistUnsafe(
                journal);
            return journal;
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<DesktopResumeJournal?>
        LoadAsync(
            SessionId originalSessionId,
            ItemId itemId,
            CancellationToken cancellationToken =
                default)
    {
        await _gate.WaitAsync(
            cancellationToken);
        try
        {
            return ReadKnownJournalUnsafe(
                originalSessionId,
                itemId);
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<bool>
        RecordReceiverChunkAsync(
            SessionId originalSessionId,
            ItemId itemId,
            long chunkIndex,
            ReadOnlyMemory<byte> chunkData,
            CancellationToken cancellationToken =
                default)
    {
        await _gate.WaitAsync(
            cancellationToken);
        try
        {
            DesktopResumeJournal? journal =
                ReadKnownJournalUnsafe(
                    originalSessionId,
                    itemId);
            if (journal == null ||
                journal.JournalRole !=
                    DesktopResumeJournalRoles
                        .Receiver ||
                _nowUnixMs() >=
                    journal.ExpiresAtUnixMs ||
                chunkIndex !=
                    journal.VerifiedChunks)
            {
                return false;
            }

            long chunkCount =
                TotalChunkCount(
                    journal.TotalBytes,
                    journal.ChunkSize);
            if (chunkIndex < 0 ||
                chunkIndex >= chunkCount)
            {
                return false;
            }

            int expectedLength =
                ExpectedChunkLength(
                    journal.TotalBytes,
                    journal.ChunkSize,
                    chunkIndex);
            if (chunkData.Length !=
                expectedLength)
            {
                return false;
            }

            if (journal.StagingPath == null ||
                !IsExactItemDirectory(
                    originalSessionId,
                    itemId,
                    journal.StagingPath))
            {
                return false;
            }

            if (journal
                    .VerifiedChunkHashesHex
                    .Count !=
                journal.VerifiedChunks)
            {
                return false;
            }

            string digest =
                Convert.ToHexStringLower(
                    SHA256.HashData(
                        chunkData.Span));
            string chunkPath =
                GetChunkPath(
                    journal.StagingPath,
                    chunkIndex);
            string tempPath =
                chunkPath +
                ".tmp-" +
                Guid.NewGuid()
                    .ToString("N");

            Directory.CreateDirectory(
                journal.StagingPath);
            try
            {
                await using (
                    var output =
                        new FileStream(
                            tempPath,
                            FileMode.CreateNew,
                            FileAccess.Write,
                            FileShare.None,
                            CopyBufferBytes,
                            FileOptions.Asynchronous |
                            FileOptions.WriteThrough))
                {
                    await output.WriteAsync(
                        chunkData,
                        cancellationToken);
                    await output.FlushAsync(
                        cancellationToken);
                    output.Flush(
                        flushToDisk: true);
                }

                File.Move(
                    tempPath,
                    chunkPath,
                    overwrite: true);

                var prefix =
                    new List<string>(
                        journal
                            .VerifiedChunkHashesHex)
                    {
                        digest
                    };
                long newOffset =
                    Math.Min(
                        journal.TotalBytes,
                        checked(
                            journal
                                .ContiguousByteOffset +
                            chunkData.Length));

                DesktopResumeJournal updated =
                    journal with
                    {
                        VerifiedChunks =
                            journal.VerifiedChunks +
                            1,
                        ContiguousByteOffset =
                            newOffset,
                        VerifiedChunkHashesHex =
                            prefix,
                        UpdatedAtUnixMs =
                            _nowUnixMs()
                    };

                try
                {
                    PersistUnsafe(
                        updated);
                }
                catch
                {
                    TryDeleteFile(
                        chunkPath);
                    throw;
                }

                return true;
            }
            finally
            {
                TryDeleteFile(
                    tempPath);
            }
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<DesktopResumeValidationResult>
        ReconcileReceiverAsync(
            SessionId originalSessionId,
            ItemId itemId,
            string authenticatedPeerFingerprintHex,
            bool peerStillTrusted,
            CancellationToken cancellationToken =
                default)
    {
        await _gate.WaitAsync(
            cancellationToken);
        try
        {
            DesktopResumeJournal? journal =
                ReadKnownJournalUnsafe(
                    originalSessionId,
                    itemId);
            if (journal == null)
            {
                return new(
                    DesktopResumeValidationCode
                        .NotFound);
            }

            if (journal.JournalRole !=
                DesktopResumeJournalRoles
                    .Receiver)
            {
                return new(
                    DesktopResumeValidationCode
                        .JournalMismatch);
            }

            if (_nowUnixMs() >=
                journal.ExpiresAtUnixMs)
            {
                PurgeKnownUnsafe(
                    originalSessionId,
                    itemId,
                    journal);
                return new(
                    DesktopResumeValidationCode
                        .Expired);
            }

            if (!peerStillTrusted ||
                !string.Equals(
                    authenticatedPeerFingerprintHex,
                    journal.PeerFingerprintHex,
                    StringComparison.Ordinal))
            {
                return new(
                    DesktopResumeValidationCode
                        .Unauthorized);
            }

            if (journal.StagingPath == null ||
                !IsExactItemDirectory(
                    originalSessionId,
                    itemId,
                    journal.StagingPath) ||
                journal
                    .VerifiedChunkHashesHex
                    .Count !=
                journal.VerifiedChunks)
            {
                PurgeKnownUnsafe(
                    originalSessionId,
                    itemId,
                    journal);
                return new(
                    DesktopResumeValidationCode
                        .JournalMismatch);
            }

            long validChunks = 0;
            long validOffset = 0;
            while (validChunks <
                journal.VerifiedChunks)
            {
                string chunkPath =
                    GetChunkPath(
                        journal.StagingPath,
                        validChunks);
                int expectedLength =
                    ExpectedChunkLength(
                        journal.TotalBytes,
                        journal.ChunkSize,
                        validChunks);

                if (!File.Exists(
                        chunkPath))
                {
                    break;
                }

                var info =
                    new FileInfo(
                        chunkPath);
                if (info.Length !=
                    expectedLength)
                {
                    break;
                }

                string actual =
                    await HashFileHexAsync(
                        chunkPath,
                        cancellationToken);
                if (!string.Equals(
                        actual,
                        journal
                            .VerifiedChunkHashesHex[
                                checked(
                                    (int)validChunks)],
                        StringComparison.Ordinal))
                {
                    break;
                }

                validOffset =
                    checked(
                        validOffset +
                        expectedLength);
                validChunks++;
            }

            if (validChunks <
                journal.VerifiedChunks)
            {
                DeleteChunkSuffixUnsafe(
                    journal.StagingPath,
                    validChunks);
                var validPrefix =
                    journal
                        .VerifiedChunkHashesHex
                        .Take(
                            checked(
                                (int)validChunks))
                        .ToList();
                journal =
                    journal with
                    {
                        VerifiedChunks =
                            validChunks,
                        ContiguousByteOffset =
                            validOffset,
                        VerifiedChunkHashesHex =
                            validPrefix,
                        UpdatedAtUnixMs =
                            _nowUnixMs()
                    };
                PersistUnsafe(
                    journal);
            }

            return new(
                DesktopResumeValidationCode
                    .Ready,
                journal);
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<DesktopResumeValidationResult>
        RevalidateSenderAsync(
            SessionId originalSessionId,
            ItemId itemId,
            string authenticatedPeerFingerprintHex,
            bool peerStillTrusted,
            DesktopResumeSourceVerifier verifier,
            CancellationToken cancellationToken =
                default)
    {
        ArgumentNullException.ThrowIfNull(
            verifier);

        DesktopResumeJournal? journal;
        await _gate.WaitAsync(
            cancellationToken);
        try
        {
            journal =
                ReadKnownJournalUnsafe(
                    originalSessionId,
                    itemId);
            if (journal == null)
            {
                return new(
                    DesktopResumeValidationCode
                        .NotFound);
            }

            if (journal.JournalRole !=
                DesktopResumeJournalRoles
                    .Sender)
            {
                return new(
                    DesktopResumeValidationCode
                        .JournalMismatch);
            }

            if (_nowUnixMs() >=
                journal.ExpiresAtUnixMs)
            {
                PurgeKnownUnsafe(
                    originalSessionId,
                    itemId,
                    journal);
                return new(
                    DesktopResumeValidationCode
                        .Expired);
            }

            if (!peerStillTrusted ||
                !string.Equals(
                    authenticatedPeerFingerprintHex,
                    journal.PeerFingerprintHex,
                    StringComparison.Ordinal))
            {
                return new(
                    DesktopResumeValidationCode
                        .Unauthorized);
            }

            if (journal.SourcePath == null ||
                journal.SourceIdentity == null ||
                journal.WholeFileSha256Hex ==
                    null)
            {
                return new(
                    DesktopResumeValidationCode
                        .JournalMismatch);
            }
        }
        finally
        {
            _gate.Release();
        }

        DesktopResumeSourceSnapshot current;
        try
        {
            current =
                await verifier.SnapshotAsync(
                    journal.SourcePath!,
                    journal.ChunkSize,
                    cancellationToken);
        }
        catch (
            OperationCanceledException)
        {
            throw;
        }
        catch
        {
            return new(
                DesktopResumeValidationCode
                    .SourceUnavailable);
        }

        bool unchanged =
            string.Equals(
                current.SourceIdentity,
                journal.SourceIdentity,
                StringComparison.Ordinal) &&
            current.TotalBytes ==
                journal.TotalBytes &&
            string.Equals(
                current.ChunkRootHashHex,
                journal.ChunkRootHashHex,
                StringComparison.Ordinal) &&
            string.Equals(
                current.WholeFileSha256Hex,
                journal.WholeFileSha256Hex,
                StringComparison.Ordinal);

        return unchanged
            ? new(
                DesktopResumeValidationCode
                    .Ready,
                journal)
            : new(
                DesktopResumeValidationCode
                    .SourceChanged);
    }

    public async Task<bool>
        AssembleVerifiedReceiverAsync(
            SessionId originalSessionId,
            ItemId itemId,
            Sha256Digest expectedWholeFileDigest,
            string destinationPath,
            CancellationToken cancellationToken =
                default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(
            destinationPath);

        await _gate.WaitAsync(
            cancellationToken);
        try
        {
            DesktopResumeJournal? journal =
                ReadKnownJournalUnsafe(
                    originalSessionId,
                    itemId);
            if (journal == null ||
                journal.JournalRole !=
                    DesktopResumeJournalRoles
                        .Receiver ||
                journal.StagingPath == null ||
                !IsExactItemDirectory(
                    originalSessionId,
                    itemId,
                    journal.StagingPath))
            {
                return false;
            }

            long chunkCount =
                TotalChunkCount(
                    journal.TotalBytes,
                    journal.ChunkSize);
            if (journal.VerifiedChunks !=
                    chunkCount ||
                journal.ContiguousByteOffset !=
                    journal.TotalBytes ||
                journal
                    .VerifiedChunkHashesHex
                    .Count !=
                    chunkCount)
            {
                return false;
            }

            string computedRoot =
                ComputeMerkleRootHex(
                    journal
                        .VerifiedChunkHashesHex);
            if (!string.Equals(
                    computedRoot,
                    journal.ChunkRootHashHex,
                    StringComparison.Ordinal))
            {
                return false;
            }

            string assembledPath =
                Path.Combine(
                    journal.StagingPath,
                    "assembled.verify");
            TryDeleteFile(
                assembledPath);

            try
            {
                using var wholeHash =
                    IncrementalHash.CreateHash(
                        HashAlgorithmName.SHA256);
                await using (
                    var output =
                        new FileStream(
                            assembledPath,
                            FileMode.CreateNew,
                            FileAccess.Write,
                            FileShare.None,
                            CopyBufferBytes,
                            FileOptions.Asynchronous |
                            FileOptions.WriteThrough))
                {
                    for (long index = 0;
                         index < chunkCount;
                         index++)
                    {
                        string chunkPath =
                            GetChunkPath(
                                journal.StagingPath,
                                index);
                        if (!File.Exists(
                                chunkPath))
                        {
                            return false;
                        }

                        int expectedLength =
                            ExpectedChunkLength(
                                journal.TotalBytes,
                                journal.ChunkSize,
                                index);
                        var info =
                            new FileInfo(
                                chunkPath);
                        if (info.Length !=
                            expectedLength)
                        {
                            return false;
                        }

                        using var chunkHash =
                            IncrementalHash
                                .CreateHash(
                                    HashAlgorithmName
                                        .SHA256);
                        await using var input =
                            new FileStream(
                                chunkPath,
                                FileMode.Open,
                                FileAccess.Read,
                                FileShare.Read,
                                CopyBufferBytes,
                                FileOptions.Asynchronous |
                                FileOptions.SequentialScan);
                        byte[] buffer =
                            new byte[
                                CopyBufferBytes];
                        while (true)
                        {
                            int read =
                                await input.ReadAsync(
                                    buffer.AsMemory(
                                        0,
                                        buffer.Length),
                                    cancellationToken);
                            if (read == 0)
                            {
                                break;
                            }

                            chunkHash.AppendData(
                                buffer,
                                0,
                                read);
                            wholeHash.AppendData(
                                buffer,
                                0,
                                read);
                            await output.WriteAsync(
                                buffer.AsMemory(
                                    0,
                                    read),
                                cancellationToken);
                        }

                        string chunkDigest =
                            Convert.ToHexStringLower(
                                chunkHash
                                    .GetHashAndReset());
                        if (!string.Equals(
                                chunkDigest,
                                journal
                                    .VerifiedChunkHashesHex[
                                        checked(
                                            (int)index)],
                                StringComparison.Ordinal))
                        {
                            return false;
                        }
                    }

                    await output.FlushAsync(
                        cancellationToken);
                    output.Flush(
                        flushToDisk: true);
                }

                var assembledInfo =
                    new FileInfo(
                        assembledPath);
                string wholeDigest =
                    Convert.ToHexStringLower(
                        wholeHash
                            .GetHashAndReset());
                if (assembledInfo.Length !=
                        journal.TotalBytes ||
                    !string.Equals(
                        wholeDigest,
                        expectedWholeFileDigest
                            .Value,
                        StringComparison.Ordinal))
                {
                    return false;
                }

                bool promoted =
                    await PromoteVerifiedAsync(
                        assembledPath,
                        destinationPath,
                        journal.TotalBytes,
                        expectedWholeFileDigest
                            .Value,
                        cancellationToken);
                if (!promoted)
                {
                    return false;
                }

                PurgeKnownUnsafe(
                    originalSessionId,
                    itemId,
                    journal);
                return true;
            }
            finally
            {
                TryDeleteFile(
                    assembledPath);
            }
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<int>
        PurgeOriginalSessionAsync(
            SessionId originalSessionId,
            CancellationToken cancellationToken =
                default)
    {
        await _gate.WaitAsync(
            cancellationToken);
        try
        {
            List<DesktopResumeJournal>
                journals =
                    LoadAllUnsafe()
                        .Where(
                            item =>
                                item.OriginalSessionId ==
                                originalSessionId
                                    .Value)
                        .ToList();
            foreach (DesktopResumeJournal journal
                     in journals)
            {
                PurgeJournalUnsafe(
                    journal);
            }

            return journals.Count;
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<int>
        PurgePeerAsync(
            string peerFingerprintHex,
            CancellationToken cancellationToken =
                default)
    {
        RequireCanonicalSha256(
            peerFingerprintHex,
            nameof(peerFingerprintHex));

        await _gate.WaitAsync(
            cancellationToken);
        try
        {
            List<DesktopResumeJournal>
                journals =
                    LoadAllUnsafe()
                        .Where(
                            item =>
                                item.PeerFingerprintHex ==
                                peerFingerprintHex)
                        .ToList();
            foreach (DesktopResumeJournal journal
                     in journals)
            {
                PurgeJournalUnsafe(
                    journal);
            }

            return journals.Count;
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<int>
        PurgeExpiredAsync(
            CancellationToken cancellationToken =
                default)
    {
        await _gate.WaitAsync(
            cancellationToken);
        try
        {
            long now =
                _nowUnixMs();
            List<DesktopResumeJournal>
                expired =
                    LoadAllUnsafe()
                        .Where(
                            item =>
                                item.ExpiresAtUnixMs <=
                                now)
                        .ToList();
            foreach (DesktopResumeJournal journal
                     in expired)
            {
                PurgeJournalUnsafe(
                    journal);
            }

            return expired.Count;
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<int>
        PurgeAllAsync(
            CancellationToken cancellationToken =
                default)
    {
        await _gate.WaitAsync(
            cancellationToken);
        try
        {
            List<DesktopResumeJournal> all =
                LoadAllUnsafe();
            foreach (DesktopResumeJournal journal
                     in all)
            {
                PurgeJournalUnsafe(
                    journal);
            }

            return all.Count;
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<int>
        EvictUnderStoragePressureAsync(
            long minimumFreeBytes,
            CancellationToken cancellationToken =
                default)
    {
        if (minimumFreeBytes < 0)
        {
            throw new ArgumentOutOfRangeException(
                nameof(minimumFreeBytes));
        }

        await _gate.WaitAsync(
            cancellationToken);
        try
        {
            if (GetFreeSpaceUnsafe() >=
                minimumFreeBytes)
            {
                return 0;
            }

            int evicted = 0;
            foreach (
                DesktopResumeJournal journal
                in LoadAllUnsafe()
                    .OrderBy(
                        item =>
                            item.UpdatedAtUnixMs)
                    .ThenBy(
                        item =>
                            item.CreatedAtUnixMs))
            {
                cancellationToken
                    .ThrowIfCancellationRequested();
                PurgeJournalUnsafe(
                    journal);
                evicted++;
                if (GetFreeSpaceUnsafe() >=
                    minimumFreeBytes)
                {
                    break;
                }
            }

            return evicted;
        }
        finally
        {
            _gate.Release();
        }
    }

    public static string ComputeMerkleRootHex(
        IReadOnlyList<string>
            chunkHashesHex)
    {
        if (chunkHashesHex.Count == 0)
        {
            return new string(
                '0',
                64);
        }

        var level =
            chunkHashesHex
                .Select(
                    hash =>
                    {
                        RequireCanonicalSha256(
                            hash,
                            nameof(
                                chunkHashesHex));
                        return Convert
                            .FromHexString(
                                hash);
                    })
                .ToList();

        while (level.Count > 1)
        {
            var next =
                new List<byte[]>(
                    (level.Count + 1) /
                    2);
            for (int index = 0;
                 index < level.Count;
                 index += 2)
            {
                if (index + 1 >=
                    level.Count)
                {
                    next.Add(
                        level[index]);
                    continue;
                }

                Span<byte> combined =
                    stackalloc byte[64];
                level[index]
                    .CopyTo(
                        combined[..32]);
                level[index + 1]
                    .CopyTo(
                        combined[32..]);
                next.Add(
                    SHA256.HashData(
                        combined));
            }

            level = next;
        }

        return Convert.ToHexStringLower(
            level[0]);
    }

    private DesktopResumeJournal?
        ReadKnownJournalUnsafe(
            SessionId originalSessionId,
            ItemId itemId)
    {
        string path =
            GetJournalPath(
                originalSessionId,
                itemId);
        if (!File.Exists(path))
        {
            return null;
        }

        try
        {
            string json =
                File.ReadAllText(
                    path);
            DesktopResumeJournal? journal =
                JsonSerializer.Deserialize<
                    DesktopResumeJournal>(
                    json,
                    _json);
            if (journal == null ||
                journal.OriginalSessionId !=
                    originalSessionId.Value ||
                journal.ItemId !=
                    itemId.Value)
            {
                throw new InvalidDataException(
                    "Resume journal identity mismatch.");
            }

            ValidateLoadedJournal(
                journal);
            return journal;
        }
        catch
        {
            TryDeleteFile(
                path);
            string itemDirectory =
                GetItemDirectory(
                    originalSessionId,
                    itemId);
            TryDeleteDirectory(
                itemDirectory);
            return null;
        }
    }

    private List<DesktopResumeJournal>
        LoadAllUnsafe()
    {
        var journals =
            new List<DesktopResumeJournal>();
        if (!Directory.Exists(
                _journalsRoot))
        {
            return journals;
        }

        foreach (string path in
                 Directory.EnumerateFiles(
                     _journalsRoot,
                     "*.json",
                     SearchOption.TopDirectoryOnly))
        {
            try
            {
                DesktopResumeJournal? journal =
                    JsonSerializer.Deserialize<
                        DesktopResumeJournal>(
                        File.ReadAllText(path),
                        _json);
                if (journal == null)
                {
                    throw new InvalidDataException();
                }

                ValidateLoadedJournal(
                    journal);
                journals.Add(
                    journal);
            }
            catch
            {
                string stem =
                    Path.GetFileNameWithoutExtension(
                        path);
                TryDeleteFile(
                    path);
                TryDeleteDirectory(
                    Path.Combine(
                        _itemsRoot,
                        stem));
            }
        }

        return journals;
    }

    private void PersistUnsafe(
        DesktopResumeJournal journal)
    {
        ValidateLoadedJournal(
            journal);

        string path =
            GetJournalPath(
                new SessionId(
                    journal.OriginalSessionId),
                new ItemId(
                    journal.ItemId));
        Directory.CreateDirectory(
            _journalsRoot);

        string tempPath =
            path +
            ".tmp-" +
            Guid.NewGuid()
                .ToString("N");

        try
        {
            string json =
                JsonSerializer.Serialize(
                    journal,
                    _json);
            using (
                var stream =
                    new FileStream(
                        tempPath,
                        FileMode.CreateNew,
                        FileAccess.Write,
                        FileShare.None,
                        CopyBufferBytes,
                        FileOptions.WriteThrough))
            using (
                var writer =
                    new StreamWriter(
                        stream,
                        new UTF8Encoding(
                            encoderShouldEmitUTF8Identifier:
                                false)))
            {
                writer.Write(
                    json);
                writer.Flush();
                stream.Flush(
                    flushToDisk: true);
            }

            File.Move(
                tempPath,
                path,
                overwrite: true);
        }
        finally
        {
            TryDeleteFile(
                tempPath);
        }
    }

    private void PurgeKnownUnsafe(
        SessionId originalSessionId,
        ItemId itemId,
        DesktopResumeJournal journal)
    {
        string journalPath =
            GetJournalPath(
                originalSessionId,
                itemId);
        TryDeleteFile(
            journalPath);

        if (journal.StagingPath !=
                null &&
            IsExactItemDirectory(
                originalSessionId,
                itemId,
                journal.StagingPath))
        {
            TryDeleteDirectory(
                journal.StagingPath);
        }
    }

    private void PurgeJournalUnsafe(
        DesktopResumeJournal journal)
    {
        var sessionId =
            new SessionId(
                journal.OriginalSessionId);
        var itemId =
            new ItemId(
                journal.ItemId);
        PurgeKnownUnsafe(
            sessionId,
            itemId,
            journal);
    }

    private string GetJournalPath(
        SessionId originalSessionId,
        ItemId itemId) =>
        Path.Combine(
            _journalsRoot,
            CompositeKey(
                originalSessionId,
                itemId) +
            ".json");

    private string GetItemDirectory(
        SessionId originalSessionId,
        ItemId itemId) =>
        Path.Combine(
            _itemsRoot,
            CompositeKey(
                originalSessionId,
                itemId));

    private static string GetChunkPath(
        string itemDirectory,
        long chunkIndex) =>
        Path.Combine(
            itemDirectory,
            $"chunk_{chunkIndex:D12}.part");

    private bool IsExactItemDirectory(
        SessionId originalSessionId,
        ItemId itemId,
        string candidate)
    {
        string expected =
            Path.GetFullPath(
                GetItemDirectory(
                    originalSessionId,
                    itemId));
        string actual =
            Path.GetFullPath(
                candidate);

        return string.Equals(
            Path.TrimEndingDirectorySeparator(
                expected),
            Path.TrimEndingDirectorySeparator(
                actual),
            OperatingSystem.IsWindows()
                ? StringComparison.OrdinalIgnoreCase
                : StringComparison.Ordinal);
    }

    private static string CompositeKey(
        SessionId originalSessionId,
        ItemId itemId)
    {
        byte[] bytes =
            Encoding.UTF8.GetBytes(
                originalSessionId.Value +
                "\0" +
                itemId.Value);
        return Convert.ToHexStringLower(
            SHA256.HashData(
                bytes));
    }

    private static long TotalChunkCount(
        long totalBytes,
        int chunkSize) =>
        checked(
            (totalBytes +
             chunkSize - 1L) /
            chunkSize);

    private static int ExpectedChunkLength(
        long totalBytes,
        int chunkSize,
        long chunkIndex)
    {
        long count =
            TotalChunkCount(
                totalBytes,
                chunkSize);
        if (chunkIndex < 0 ||
            chunkIndex >= count)
        {
            throw new ArgumentOutOfRangeException(
                nameof(chunkIndex));
        }

        if (chunkIndex ==
            count - 1)
        {
            long prior =
                checked(
                    chunkIndex *
                    chunkSize);
            return checked(
                (int)(
                    totalBytes -
                    prior));
        }

        return chunkSize;
    }

    private static void ValidateCommon(
        string itemName,
        string peerFingerprintHex,
        long totalBytes,
        int chunkSize,
        string chunkRootHashHex)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(
            itemName);
        RequireCanonicalSha256(
            peerFingerprintHex,
            nameof(peerFingerprintHex));
        RequireCanonicalSha256(
            chunkRootHashHex,
            nameof(chunkRootHashHex));

        if (totalBytes <= 0 ||
            totalBytes >
                ProtocolCatalogLimits.MaxItemBytes)
        {
            throw new ArgumentOutOfRangeException(
                nameof(totalBytes));
        }

        if (chunkSize is < 1 or >
            MaxChunkSize)
        {
            throw new ArgumentOutOfRangeException(
                nameof(chunkSize));
        }

        long count =
            TotalChunkCount(
                totalBytes,
                chunkSize);
        if (count >
            int.MaxValue)
        {
            throw new InvalidDataException(
                "Resume chunk count exceeds bounded journal capacity.");
        }
    }

    private void ValidateLoadedJournal(
        DesktopResumeJournal journal)
    {
        if (!IdentifierValidation.IsValidUuidV4(
                journal.OriginalSessionId) ||
            !IdentifierValidation.IsValidUuidV4(
                journal.ItemId))
        {
            throw new InvalidDataException(
                "Resume journal identifiers are invalid.");
        }

        ValidateCommon(
            journal.ItemName,
            journal.PeerFingerprintHex,
            journal.TotalBytes,
            journal.ChunkSize,
            journal.ChunkRootHashHex);

        if (journal.CreatedAtUnixMs <= 0 ||
            journal.UpdatedAtUnixMs <
                journal.CreatedAtUnixMs ||
            journal.ExpiresAtUnixMs <=
                journal.CreatedAtUnixMs ||
            journal.ExpiresAtUnixMs -
                journal.CreatedAtUnixMs >
                MaxTtlMilliseconds)
        {
            throw new InvalidDataException(
                "Resume journal timestamps are invalid.");
        }

        if (journal.VerifiedChunks < 0 ||
            journal.ContiguousByteOffset <
                0 ||
            journal.ContiguousByteOffset >
                journal.TotalBytes ||
            journal
                .VerifiedChunkHashesHex
                .Count !=
                journal.VerifiedChunks)
        {
            throw new InvalidDataException(
                "Resume journal progress is invalid.");
        }

        long expectedOffset =
            journal.VerifiedChunks == 0
                ? 0
                : Math.Min(
                    journal.TotalBytes,
                    checked(
                        journal.VerifiedChunks *
                        (long)journal.ChunkSize));
        if (journal.ContiguousByteOffset !=
            expectedOffset)
        {
            throw new InvalidDataException(
                "Resume journal offset is not the exact contiguous prefix.");
        }

        foreach (string digest in
                 journal.VerifiedChunkHashesHex)
        {
            RequireCanonicalSha256(
                digest,
                nameof(
                    journal
                        .VerifiedChunkHashesHex));
        }

        if (journal.JournalRole ==
            DesktopResumeJournalRoles.Receiver)
        {
            if (journal.SourcePath != null ||
                journal.SourceIdentity !=
                    null ||
                journal
                    .WholeFileSha256Hex !=
                    null ||
                journal.StagingPath == null ||
                !IsExactItemDirectory(
                    new SessionId(
                        journal
                            .OriginalSessionId),
                    new ItemId(
                        journal.ItemId),
                    journal.StagingPath))
            {
                throw new InvalidDataException(
                    "Receiver resume journal authority is inconsistent.");
            }
        }
        else if (journal.JournalRole ==
                 DesktopResumeJournalRoles.Sender)
        {
            if (journal.SourcePath == null ||
                journal.SourceIdentity ==
                    null ||
                journal
                    .WholeFileSha256Hex ==
                    null ||
                journal.StagingPath !=
                    null ||
                journal.VerifiedChunks !=
                    0 ||
                journal
                    .ContiguousByteOffset !=
                    0 ||
                journal
                    .VerifiedChunkHashesHex
                    .Count !=
                    0)
            {
                throw new InvalidDataException(
                    "Sender resume journal authority is inconsistent.");
            }

            RequireCanonicalSha256(
                journal.SourceIdentity,
                nameof(
                    journal.SourceIdentity));
            RequireCanonicalSha256(
                journal
                    .WholeFileSha256Hex,
                nameof(
                    journal
                        .WholeFileSha256Hex));
        }
        else
        {
            throw new InvalidDataException(
                "Resume journal role is invalid.");
        }
    }

    private static void RequireReceiverAuthority(
        DesktopResumeJournal journal,
        string peerFingerprintHex,
        long totalBytes,
        int chunkSize,
        string chunkRootHashHex)
    {
        if (journal.JournalRole !=
                DesktopResumeJournalRoles
                    .Receiver ||
            journal.PeerFingerprintHex !=
                peerFingerprintHex ||
            journal.TotalBytes !=
                totalBytes ||
            journal.ChunkSize !=
                chunkSize ||
            journal.ChunkRootHashHex !=
                chunkRootHashHex)
        {
            throw new InvalidDataException(
                "Existing receiver resume authority does not match the item.");
        }
    }

    private static void RequireSenderAuthority(
        DesktopResumeJournal journal,
        string peerFingerprintHex,
        DesktopResumeSourceSnapshot snapshot)
    {
        if (journal.JournalRole !=
                DesktopResumeJournalRoles
                    .Sender ||
            journal.PeerFingerprintHex !=
                peerFingerprintHex ||
            journal.SourcePath !=
                Path.GetFullPath(
                    snapshot.SourcePath) ||
            journal.SourceIdentity !=
                snapshot.SourceIdentity ||
            journal.TotalBytes !=
                snapshot.TotalBytes ||
            journal.ChunkSize !=
                snapshot.ChunkSize ||
            journal.ChunkRootHashHex !=
                snapshot.ChunkRootHashHex ||
            journal.WholeFileSha256Hex !=
                snapshot
                    .WholeFileSha256Hex)
        {
            throw new InvalidDataException(
                "Existing sender resume authority does not match the item.");
        }
    }

    private void DeleteChunkSuffixUnsafe(
        string itemDirectory,
        long firstInvalidChunk)
    {
        if (!Directory.Exists(
                itemDirectory))
        {
            return;
        }

        foreach (string path in
                 Directory.EnumerateFiles(
                     itemDirectory,
                     "chunk_*.part",
                     SearchOption.TopDirectoryOnly))
        {
            string name =
                Path.GetFileNameWithoutExtension(
                    path);
            string suffix =
                name["chunk_".Length..];
            if (long.TryParse(
                    suffix,
                    out long index) &&
                index >=
                    firstInvalidChunk)
            {
                TryDeleteFile(
                    path);
            }
        }
    }

    private async Task<bool>
        PromoteVerifiedAsync(
            string source,
            string destination,
            long expectedSize,
            string expectedDigestHex,
            CancellationToken cancellationToken)
    {
        string fullDestination =
            Path.GetFullPath(
                destination);
        if (File.Exists(
                fullDestination))
        {
            return false;
        }

        string? parent =
            Path.GetDirectoryName(
                fullDestination);
        if (!string.IsNullOrWhiteSpace(
                parent))
        {
            Directory.CreateDirectory(
                parent);
        }

        try
        {
            File.Move(
                source,
                fullDestination,
                overwrite: false);
            string digest =
                await HashFileHexAsync(
                    fullDestination,
                    cancellationToken);
            var info =
                new FileInfo(
                    fullDestination);
            if (info.Length ==
                    expectedSize &&
                digest ==
                    expectedDigestHex)
            {
                return true;
            }

            TryDeleteFile(
                fullDestination);
            return false;
        }
        catch (IOException)
        {
            // Cross-filesystem promotion falls back to a verified destination-side temporary copy.
        }

        if (parent == null)
        {
            return false;
        }

        string tempDestination =
            Path.Combine(
                parent,
                "." +
                Path.GetFileName(
                    fullDestination) +
                ".glide-resume-" +
                Guid.NewGuid()
                    .ToString("N"));

        try
        {
            await using var input =
                new FileStream(
                    source,
                    FileMode.Open,
                    FileAccess.Read,
                    FileShare.Read,
                    CopyBufferBytes,
                    FileOptions.Asynchronous |
                    FileOptions.SequentialScan);
            await using var output =
                new FileStream(
                    tempDestination,
                    FileMode.CreateNew,
                    FileAccess.Write,
                    FileShare.None,
                    CopyBufferBytes,
                    FileOptions.Asynchronous |
                    FileOptions.WriteThrough);
            using var hash =
                IncrementalHash.CreateHash(
                    HashAlgorithmName.SHA256);

            byte[] buffer =
                new byte[
                    CopyBufferBytes];
            long total = 0;
            while (true)
            {
                int read =
                    await input.ReadAsync(
                        buffer.AsMemory(
                            0,
                            buffer.Length),
                        cancellationToken);
                if (read == 0)
                {
                    break;
                }

                total =
                    checked(total + read);
                hash.AppendData(
                    buffer,
                    0,
                    read);
                await output.WriteAsync(
                    buffer.AsMemory(
                        0,
                        read),
                    cancellationToken);
            }

            await output.FlushAsync(
                cancellationToken);
            output.Flush(
                flushToDisk: true);

            string digest =
                Convert.ToHexStringLower(
                    hash.GetHashAndReset());
            if (total !=
                    expectedSize ||
                digest !=
                    expectedDigestHex)
            {
                return false;
            }

            File.Move(
                tempDestination,
                fullDestination,
                overwrite: false);
            return true;
        }
        finally
        {
            TryDeleteFile(
                tempDestination);
        }
    }

    private static async Task<string>
        HashFileHexAsync(
            string path,
            CancellationToken cancellationToken)
    {
        using var hash =
            IncrementalHash.CreateHash(
                HashAlgorithmName.SHA256);
        await using var input =
            new FileStream(
                path,
                FileMode.Open,
                FileAccess.Read,
                FileShare.Read,
                CopyBufferBytes,
                FileOptions.Asynchronous |
                FileOptions.SequentialScan);
        byte[] buffer =
            new byte[
                CopyBufferBytes];
        while (true)
        {
            int read =
                await input.ReadAsync(
                    buffer.AsMemory(
                        0,
                        buffer.Length),
                    cancellationToken);
            if (read == 0)
            {
                break;
            }

            hash.AppendData(
                buffer,
                0,
                read);
        }

        return Convert.ToHexStringLower(
            hash.GetHashAndReset());
    }

    private long GetFreeSpaceUnsafe()
    {
        if (_freeSpaceProvider != null)
        {
            return _freeSpaceProvider();
        }

        try
        {
            string root =
                Path.GetPathRoot(
                    _root) ??
                (
                    OperatingSystem.IsWindows()
                        ? "C:\\"
                        : "/"
                    );
            return new DriveInfo(
                root)
                .AvailableFreeSpace;
        }
        catch
        {
            return long.MaxValue;
        }
    }

    private static string GetDefaultRoot()
    {
        if (OperatingSystem.IsLinux())
        {
            string cache =
                Environment
                    .GetEnvironmentVariable(
                        "XDG_CACHE_HOME")
                ?? Path.Combine(
                    Environment.GetFolderPath(
                        Environment
                            .SpecialFolder
                            .UserProfile),
                    ".cache");
            return Path.Combine(
                cache,
                "glide",
                "resume");
        }

        return Path.Combine(
            Environment.GetFolderPath(
                Environment.SpecialFolder
                    .LocalApplicationData),
            "Glide",
            "resume");
    }

    private static void RequireCanonicalSha256(
        string value,
        string name)
    {
        if (!IdentifierValidation
                .IsValidSha256(
                    value))
        {
            throw new InvalidDataException(
                $"{name} must be canonical lowercase SHA-256.");
        }
    }

    private static void TryDeleteFile(
        string path)
    {
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
        catch
        {
        }
    }

    private static void TryDeleteDirectory(
        string path)
    {
        try
        {
            if (Directory.Exists(path))
            {
                Directory.Delete(
                    path,
                    recursive: true);
            }
        }
        catch
        {
        }
    }

    /// <summary>
    /// Local protocol size alias avoids coupling storage validation to the JSON codec implementation.
    /// </summary>
    private static class ProtocolCatalogLimits
    {
        public const long MaxItemBytes =
            68_719_476_736L;
    }
}
