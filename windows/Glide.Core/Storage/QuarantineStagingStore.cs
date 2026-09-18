using Glide.Core.Codec;
using Glide.Core.Models;
using Glide.Core.Security;

namespace Glide.Core.Storage;

/// <summary>
/// Shared desktop quarantine/storage boundary.
///
/// Incoming items reserve staging capacity before the peer is authorized to stream. Every item is
/// written through one bounded writer that owns byte counting, SHA-256, cleanup and final commit.
/// Destination paths are sanitized and checked for reparse-point traversal immediately before
/// commit. Duplicate-name resolution and publication are serialized so concurrent sessions cannot
/// choose the same final path.
/// </summary>
public sealed class QuarantineStagingStore
{
    public const int StreamingBufferBytes = 64 * 1024;

    private readonly string _stagingRoot;
    private readonly string _destinationRoot;
    private readonly Func<long>? _freeSpaceProvider;

    private readonly object _reservationGate = new();
    private readonly object _commitGate = new();
    private readonly Dictionary<string, long> _reservations =
        new(StringComparer.Ordinal);
    private long _reservedBytes;

    public string StagingRoot => _stagingRoot;
    public string DestinationRoot => _destinationRoot;

    public long ReservedBytes
    {
        get
        {
            lock (_reservationGate)
            {
                return _reservedBytes;
            }
        }
    }

    public QuarantineStagingStore(
        string? stagingRoot = null,
        string? destinationRoot = null,
        Func<long>? freeSpaceProvider = null)
    {
        _stagingRoot = Path.GetFullPath(
            stagingRoot ?? GetDefaultStagingRoot());

        _destinationRoot = Path.GetFullPath(
            destinationRoot ?? Path.Combine(
                Environment.GetFolderPath(
                    Environment.SpecialFolder.UserProfile),
                "Downloads"));

        _freeSpaceProvider = freeSpaceProvider;
    }

    private static string GetDefaultStagingRoot()
    {
        if (OperatingSystem.IsLinux())
        {
            string? xdgData =
                Environment.GetEnvironmentVariable("XDG_DATA_HOME");
            if (!string.IsNullOrWhiteSpace(xdgData))
            {
                return Path.Combine(xdgData, "glide", "staging");
            }
        }

        return Path.Combine(
            Environment.GetFolderPath(
                Environment.SpecialFolder.LocalApplicationData),
            "Glide",
            "staging");
    }

    public long GetAvailableFreeSpaceBytes()
    {
        if (_freeSpaceProvider != null)
        {
            return Math.Max(0, _freeSpaceProvider());
        }

        try
        {
            string root =
                Path.GetPathRoot(_stagingRoot)
                ?? (OperatingSystem.IsWindows()
                    ? Path.GetPathRoot(Environment.SystemDirectory) ?? "C:\\"
                    : "/");
            return new DriveInfo(root).AvailableFreeSpace;
        }
        catch
        {
            // Preserve the historical public query behavior. ReserveIncoming still tracks all
            // reservations against this value; callers that require deterministic disk-full
            // behavior should supply freeSpaceProvider (as production/web tests already do).
            return long.MaxValue;
        }
    }

    public string GetQuarantinePath(
        SessionId sessionId,
        ItemId itemId) =>
        Path.Combine(
            _stagingRoot,
            sessionId.Value,
            $"{itemId.Value}.quarantine");

    /// <summary>
    /// Reserves staging capacity and validates the final relative path without opening a file
    /// handle. Reservations are cumulative across concurrent sessions.
    /// </summary>
    public IncomingFileReservation ReserveIncoming(
        SessionId sessionId,
        ItemId itemId,
        string rawRelativePath,
        long expectedSizeBytes)
    {
        if (expectedSizeBytes < 0 ||
            expectedSizeBytes > ProtocolCatalog.MaxItemBytes)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.LimitExceeded,
                $"Incoming item size {expectedSizeBytes} is outside the supported range.");
        }

        string sanitizedRelativePath =
            WindowsPathSanitizer.SanitizeRelativePath(rawRelativePath);

        try
        {
            EnsureRootAndRelativePathSafe(
                _destinationRoot,
                sanitizedRelativePath,
                createDirectories: false,
                WireErrorCodes.DestinationUnavailable);
        }
        catch (ProtocolViolationException)
        {
            throw;
        }
        catch (Exception ex)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.DestinationUnavailable,
                "Unable to validate the configured destination path.",
                ex);
        }

        string reservationKey =
            BuildReservationKey(sessionId, itemId);

        lock (_reservationGate)
        {
            if (_reservations.ContainsKey(reservationKey))
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.Busy,
                    $"Storage is already reserved for item '{itemId.Value}'.");
            }

            long available = GetAvailableFreeSpaceBytes();
            long unreserved =
                available == long.MaxValue
                    ? long.MaxValue
                    : Math.Max(0, available - _reservedBytes);

            if (expectedSizeBytes > unreserved)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.InsufficientStorage,
                    $"Insufficient staging space for {expectedSizeBytes} bytes; {unreserved} bytes remain unreserved.");
            }

            _reservedBytes = checked(
                _reservedBytes + expectedSizeBytes);
            _reservations.Add(
                reservationKey,
                expectedSizeBytes);
        }

        try
        {
            EnsureSafeStagingSessionDirectory(sessionId);
            return new IncomingFileReservation(
                this,
                sessionId,
                itemId,
                sanitizedRelativePath,
                expectedSizeBytes,
                reservationKey);
        }
        catch (ProtocolViolationException)
        {
            ReleaseReservation(
                reservationKey,
                sessionId,
                itemId,
                purgeQuarantine: true);
            throw;
        }
        catch (Exception ex)
        {
            ReleaseReservation(
                reservationKey,
                sessionId,
                itemId,
                purgeQuarantine: true);
            throw new ProtocolViolationException(
                WireErrorCodes.DestinationUnavailable,
                "Unable to prepare quarantine storage for the incoming item.",
                ex);
        }
    }

    /// <summary>
    /// Compatibility helper used by browser/storage callers. It now delegates to the same
    /// reservation-backed streaming writer as the native desktop receiver.
    /// </summary>
    public async Task<(string committedPath, Sha256Digest actualDigest)>
        WriteAndCommitAsync(
            SessionId sessionId,
            ItemId itemId,
            string rawRelativePath,
            Stream inputStream,
            Sha256Digest expectedDigest,
            long expectedSizeBytes,
            CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(inputStream);

        using IncomingFileReservation reservation =
            ReserveIncoming(
                sessionId,
                itemId,
                rawRelativePath,
                expectedSizeBytes);

        await using QuarantineWriteSession writer =
            reservation.OpenWriter();

        byte[] buffer = new byte[StreamingBufferBytes];

        while (true)
        {
            int bytesRead = await inputStream.ReadAsync(
                buffer.AsMemory(0, buffer.Length),
                cancellationToken);
            if (bytesRead == 0)
            {
                break;
            }

            await writer.WriteAsync(
                buffer.AsMemory(0, bytesRead),
                cancellationToken);
        }

        return await writer.CompleteAndCommitAsync(
            expectedDigest,
            cancellationToken);
    }

    public void PurgeQuarantine(
        SessionId sessionId,
        ItemId itemId)
    {
        string key = BuildReservationKey(sessionId, itemId);
        ReleaseReservation(
            key,
            sessionId,
            itemId,
            purgeQuarantine: true);
    }

    public void PurgeSession(SessionId sessionId)
    {
        List<(string Key, ItemId ItemId)> owned = [];

        lock (_reservationGate)
        {
            string prefix = sessionId.Value + "/";
            foreach (var (key, bytes) in _reservations)
            {
                if (key.StartsWith(prefix, StringComparison.Ordinal))
                {
                    string itemText = key[prefix.Length..];
                    owned.Add(
                        (key, new ItemId(itemText)));
                }
            }
        }

        foreach (var entry in owned)
        {
            ReleaseReservation(
                entry.Key,
                sessionId,
                entry.ItemId,
                purgeQuarantine: true);
        }

        try
        {
            string sessionDir =
                Path.Combine(_stagingRoot, sessionId.Value);
            if (Directory.Exists(sessionDir))
            {
                Directory.Delete(sessionDir, recursive: true);
            }
        }
        catch
        {
            // Best-effort cleanup after every reservation has been released.
        }
    }

    /// <summary>
    /// Compatibility commit for callers that staged the file themselves. The path is sanitized
    /// again and publication still goes through the centralized safe/collision-serialized commit.
    /// </summary>
    public string CommitQuarantineFile(
        SessionId sessionId,
        ItemId itemId,
        string sanitizedRelativePath)
    {
        string cleanPath =
            WindowsPathSanitizer.SanitizeRelativePath(
                sanitizedRelativePath);
        string quarantinePath =
            GetQuarantinePath(sessionId, itemId);

        if (!File.Exists(quarantinePath))
        {
            throw new FileNotFoundException(
                $"Quarantine file not found: {quarantinePath}");
        }

        return CommitQuarantinePath(
            quarantinePath,
            cleanPath);
    }

    public static string ResolveCollision(string targetPath)
    {
        if (!File.Exists(targetPath) &&
            !Directory.Exists(targetPath))
        {
            return targetPath;
        }

        string directory =
            Path.GetDirectoryName(targetPath)
            ?? string.Empty;
        string fileNameWithoutExt =
            Path.GetFileNameWithoutExtension(targetPath);
        string extension =
            Path.GetExtension(targetPath);

        int counter = 1;
        while (true)
        {
            string candidate = Path.Combine(
                directory,
                $"{fileNameWithoutExt} ({counter}){extension}");
            if (!File.Exists(candidate) &&
                !Directory.Exists(candidate))
            {
                return candidate;
            }

            counter = checked(counter + 1);
        }
    }

    internal QuarantineWriteSession OpenWriter(
        IncomingFileReservation reservation)
    {
        EnsureSafeStagingSessionDirectory(
            reservation.SessionId);

        string quarantinePath =
            GetQuarantinePath(
                reservation.SessionId,
                reservation.ItemId);

        if (File.Exists(quarantinePath) ||
            Directory.Exists(quarantinePath))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.Busy,
                $"Quarantine path already exists for item '{reservation.ItemId.Value}'.");
        }

        try
        {
            var stream = new FileStream(
                quarantinePath,
                FileMode.CreateNew,
                FileAccess.Write,
                FileShare.None,
                StreamingBufferBytes,
                FileOptions.Asynchronous |
                FileOptions.SequentialScan);

            return new QuarantineWriteSession(
                this,
                reservation,
                stream);
        }
        catch (ProtocolViolationException)
        {
            throw;
        }
        catch (Exception ex)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.WriteFailure,
                $"Unable to open quarantine storage for item '{reservation.ItemId.Value}'.",
                ex);
        }
    }

    internal string CommitReservation(
        IncomingFileReservation reservation)
    {
        string quarantinePath =
            GetQuarantinePath(
                reservation.SessionId,
                reservation.ItemId);

        if (!File.Exists(quarantinePath))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.WriteFailure,
                "Verified quarantine file disappeared before commit.");
        }

        string finalPath = CommitQuarantinePath(
            quarantinePath,
            reservation.SanitizedRelativePath);

        ReleaseReservation(
            reservation.ReservationKey,
            reservation.SessionId,
            reservation.ItemId,
            purgeQuarantine: false);

        return finalPath;
    }

    internal void AbandonReservation(
        IncomingFileReservation reservation)
    {
        ReleaseReservation(
            reservation.ReservationKey,
            reservation.SessionId,
            reservation.ItemId,
            purgeQuarantine: true);
    }

    private string CommitQuarantinePath(
        string quarantinePath,
        string sanitizedRelativePath)
    {
        lock (_commitGate)
        {
            EnsureRootAndRelativePathSafe(
                _destinationRoot,
                sanitizedRelativePath,
                createDirectories: true,
                WireErrorCodes.DestinationUnavailable);

            string targetPath = GetDestinationPath(
                sanitizedRelativePath);

            for (int attempt = 0; attempt < 1000; attempt++)
            {
                string finalPath =
                    ResolveCollision(targetPath);

                string? targetDirectory =
                    Path.GetDirectoryName(finalPath);
                if (targetDirectory != null)
                {
                    EnsureDirectoryChainSafe(
                        _destinationRoot,
                        targetDirectory,
                        createDirectories: true,
                        WireErrorCodes.DestinationUnavailable);
                }

                try
                {
                    File.Move(
                        quarantinePath,
                        finalPath,
                        overwrite: false);
                    return finalPath;
                }
                catch (IOException)
                    when (File.Exists(finalPath) ||
                          Directory.Exists(finalPath))
                {
                    // A different store/process won this collision name after resolution.
                    // Re-resolve against the filesystem and retry without overwriting.
                    continue;
                }
                catch (Exception ex)
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.DestinationUnavailable,
                        "Verified quarantine file could not be committed to the destination.",
                        ex);
                }
            }

            throw new ProtocolViolationException(
                WireErrorCodes.DestinationUnavailable,
                "Unable to resolve a collision-safe destination after 1000 attempts.");
        }
    }

    private string GetDestinationPath(
        string sanitizedRelativePath)
    {
        string candidate = Path.GetFullPath(
            Path.Combine(
                _destinationRoot,
                sanitizedRelativePath.Replace(
                    '/',
                    Path.DirectorySeparatorChar)));

        EnsureContainedByRoot(
            _destinationRoot,
            candidate,
            WireErrorCodes.DestinationUnavailable);
        return candidate;
    }

    private void EnsureSafeStagingSessionDirectory(
        SessionId sessionId)
    {
        string sessionDirectory = Path.GetFullPath(
            Path.Combine(
                _stagingRoot,
                sessionId.Value));

        EnsureContainedByRoot(
            _stagingRoot,
            sessionDirectory,
            WireErrorCodes.WriteFailure);
        EnsureDirectoryChainSafe(
            _stagingRoot,
            sessionDirectory,
            createDirectories: true,
            WireErrorCodes.WriteFailure);
    }

    private static void EnsureRootAndRelativePathSafe(
        string root,
        string sanitizedRelativePath,
        bool createDirectories,
        string failureCode)
    {
        string target = Path.GetFullPath(
            Path.Combine(
                root,
                sanitizedRelativePath.Replace(
                    '/',
                    Path.DirectorySeparatorChar)));

        EnsureContainedByRoot(
            root,
            target,
            failureCode);

        string targetDirectory =
            Path.GetDirectoryName(target)
            ?? root;

        EnsureDirectoryChainSafe(
            root,
            targetDirectory,
            createDirectories,
            failureCode);
    }

    private static void EnsureDirectoryChainSafe(
        string root,
        string targetDirectory,
        bool createDirectories,
        string failureCode)
    {
        string rootFull =
            Path.TrimEndingDirectorySeparator(
                Path.GetFullPath(root));
        string targetFull =
            Path.TrimEndingDirectorySeparator(
                Path.GetFullPath(targetDirectory));

        EnsureContainedByRoot(
            rootFull,
            targetFull,
            failureCode);

        EnsureNoReparseAncestors(
            rootFull,
            failureCode);

        if (createDirectories &&
            !Directory.Exists(rootFull))
        {
            Directory.CreateDirectory(rootFull);
        }

        EnsureNoReparsePoint(
            rootFull,
            failureCode);

        string relative =
            Path.GetRelativePath(
                rootFull,
                targetFull);

        if (relative == ".")
        {
            return;
        }

        string current = rootFull;
        foreach (string segment in relative.Split(
            Path.DirectorySeparatorChar,
            Path.AltDirectorySeparatorChar))
        {
            if (string.IsNullOrEmpty(segment) ||
                segment == ".")
            {
                continue;
            }

            current = Path.Combine(
                current,
                segment);

            if (File.Exists(current) &&
                !Directory.Exists(current))
            {
                throw new ProtocolViolationException(
                    failureCode,
                    $"Storage path component '{current}' is not a directory.");
            }

            if (!Directory.Exists(current))
            {
                if (!createDirectories)
                {
                    // A non-existent future component cannot currently be a reparse point.
                    continue;
                }

                Directory.CreateDirectory(current);
            }

            EnsureNoReparsePoint(
                current,
                failureCode);
        }
    }

    private static void EnsureContainedByRoot(
        string root,
        string candidate,
        string failureCode)
    {
        string rootFull =
            Path.TrimEndingDirectorySeparator(
                Path.GetFullPath(root));
        string candidateFull =
            Path.GetFullPath(candidate);
        string relative =
            Path.GetRelativePath(
                rootFull,
                candidateFull);

        if (Path.IsPathRooted(relative) ||
            relative == ".." ||
            relative.StartsWith(
                ".." + Path.DirectorySeparatorChar,
                StringComparison.Ordinal) ||
            relative.StartsWith(
                ".." + Path.AltDirectorySeparatorChar,
                StringComparison.Ordinal))
        {
            throw new ProtocolViolationException(
                failureCode,
                "Storage path escaped its configured root.");
        }
    }

    private static void EnsureNoReparseAncestors(
        string path,
        string failureCode)
    {
        DirectoryInfo? current =
            new DirectoryInfo(
                Path.GetFullPath(path));

        while (current != null)
        {
            if (current.Exists)
            {
                EnsureNoReparsePoint(
                    current.FullName,
                    failureCode);
            }

            current = current.Parent;
        }
    }

    private static void EnsureNoReparsePoint(
        string path,
        string failureCode)
    {
        if (!Directory.Exists(path) &&
            !File.Exists(path))
        {
            return;
        }

        FileAttributes attributes =
            File.GetAttributes(path);
        if ((attributes & FileAttributes.ReparsePoint) != 0)
        {
            throw new ProtocolViolationException(
                failureCode,
                $"Storage path '{path}' traverses a reparse point or symbolic link.");
        }
    }

    private void ReleaseReservation(
        string reservationKey,
        SessionId sessionId,
        ItemId itemId,
        bool purgeQuarantine)
    {
        lock (_reservationGate)
        {
            if (_reservations.Remove(
                    reservationKey,
                    out long actualReserved))
            {
                _reservedBytes =
                    Math.Max(
                        0,
                        _reservedBytes - actualReserved);
            }
        }

        if (!purgeQuarantine)
        {
            return;
        }

        try
        {
            string quarantinePath =
                GetQuarantinePath(
                    sessionId,
                    itemId);
            if (File.Exists(quarantinePath))
            {
                File.Delete(quarantinePath);
            }
        }
        catch
        {
            // Cleanup is best effort; a future session/startup reconciler may retry.
        }
    }

    private static string BuildReservationKey(
        SessionId sessionId,
        ItemId itemId) =>
        $"{sessionId.Value}/{itemId.Value}";
}

/// <summary>
/// Capacity/path reservation for one inbound item. Disposing an uncommitted reservation releases
/// its budget and removes any partial quarantine file.
/// </summary>
public sealed class IncomingFileReservation : IDisposable
{
    private readonly QuarantineStagingStore _owner;
    private int _state; // 0=reserved, 1=writer-opened, 2=committed, 3=disposed

    internal IncomingFileReservation(
        QuarantineStagingStore owner,
        SessionId sessionId,
        ItemId itemId,
        string sanitizedRelativePath,
        long expectedSizeBytes,
        string reservationKey)
    {
        _owner = owner;
        SessionId = sessionId;
        ItemId = itemId;
        SanitizedRelativePath = sanitizedRelativePath;
        ExpectedSizeBytes = expectedSizeBytes;
        ReservationKey = reservationKey;
    }

    public SessionId SessionId { get; }
    public ItemId ItemId { get; }
    public string SanitizedRelativePath { get; }
    public long ExpectedSizeBytes { get; }

    internal string ReservationKey { get; }

    public QuarantineWriteSession OpenWriter()
    {
        if (Interlocked.CompareExchange(
                ref _state,
                1,
                0) != 0)
        {
            throw new InvalidOperationException(
                "Incoming storage reservation is no longer available for opening.");
        }

        try
        {
            return _owner.OpenWriter(this);
        }
        catch
        {
            Interlocked.Exchange(
                ref _state,
                3);
            _owner.AbandonReservation(this);
            throw;
        }
    }

    internal void MarkCommitted()
    {
        int previous = Interlocked.Exchange(
            ref _state,
            2);
        if (previous != 1)
        {
            throw new InvalidOperationException(
                "Only an opened writer may commit a storage reservation.");
        }
    }

    public void Dispose()
    {
        int previous = Interlocked.Exchange(
            ref _state,
            3);

        if (previous is 0 or 1)
        {
            _owner.AbandonReservation(this);
        }
    }
}

/// <summary>
/// Single-item bounded quarantine writer. It owns streaming byte counting, SHA-256 and cleanup.
/// </summary>
public sealed class QuarantineWriteSession : IAsyncDisposable
{
    private readonly QuarantineStagingStore _owner;
    private readonly IncomingFileReservation _reservation;
    private readonly StreamingSha256 _sha256;
    private FileStream? _stream;
    private bool _completed;
    private bool _disposed;

    internal QuarantineWriteSession(
        QuarantineStagingStore owner,
        IncomingFileReservation reservation,
        FileStream stream)
    {
        _owner = owner;
        _reservation = reservation;
        _stream = stream;
        _sha256 = new StreamingSha256();
    }

    public long WrittenBytes =>
        _sha256.BytesProcessed;

    public async ValueTask WriteAsync(
        ReadOnlyMemory<byte> data,
        CancellationToken cancellationToken = default)
    {
        ThrowIfUnavailable();

        long nextLength = checked(
            WrittenBytes + data.Length);
        if (nextLength >
            _reservation.ExpectedSizeBytes)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.LimitExceeded,
                $"Incoming stream exceeded reserved size {_reservation.ExpectedSizeBytes} bytes.");
        }

        if (data.IsEmpty)
        {
            return;
        }

        try
        {
            await _stream!.WriteAsync(
                data,
                cancellationToken);
            _sha256.Append(data.Span);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (ProtocolViolationException)
        {
            throw;
        }
        catch (Exception ex)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.WriteFailure,
                "Failed while writing inbound bytes to quarantine storage.",
                ex);
        }
    }

    public async Task<(string committedPath, Sha256Digest actualDigest)>
        CompleteAndCommitAsync(
            Sha256Digest expectedDigest,
            CancellationToken cancellationToken = default)
    {
        ThrowIfUnavailable();

        try
        {
            if (WrittenBytes !=
                _reservation.ExpectedSizeBytes)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.TransferInterrupted,
                    $"Incoming stream finished at {WrittenBytes} bytes; expected {_reservation.ExpectedSizeBytes}.");
            }

            Sha256Digest actualDigest =
                _sha256.Complete();

            if (!string.Equals(
                    actualDigest.Value,
                    expectedDigest.Value,
                    StringComparison.Ordinal))
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.IntegrityMismatch,
                    $"SHA-256 integrity mismatch. Expected {expectedDigest.Value}, got {actualDigest.Value}.");
            }

            await _stream!.FlushAsync(
                cancellationToken);
            _stream.Flush(
                flushToDisk: true);
            await _stream.DisposeAsync();
            _stream = null;

            string committedPath =
                _owner.CommitReservation(
                    _reservation);
            _reservation.MarkCommitted();
            _completed = true;

            return (
                committedPath,
                actualDigest);
        }
        catch (ProtocolViolationException)
        {
            await DisposeAsync();
            throw;
        }
        catch (OperationCanceledException)
        {
            await DisposeAsync();
            throw;
        }
        catch (Exception ex)
        {
            await DisposeAsync();
            throw new ProtocolViolationException(
                WireErrorCodes.WriteFailure,
                "Failed while flushing or committing verified quarantine storage.",
                ex);
        }
    }

    public async ValueTask DisposeAsync()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;

        if (_stream != null)
        {
            await _stream.DisposeAsync();
            _stream = null;
        }

        _sha256.Dispose();

        if (!_completed)
        {
            _reservation.Dispose();
        }
    }

    private void ThrowIfUnavailable()
    {
        if (_disposed)
        {
            throw new ObjectDisposedException(
                nameof(QuarantineWriteSession));
        }

        if (_completed)
        {
            throw new InvalidOperationException(
                "Quarantine writer has already committed.");
        }
    }
}
