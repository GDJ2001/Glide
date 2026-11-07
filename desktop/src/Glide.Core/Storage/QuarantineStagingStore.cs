using System.Buffers;
using System.ComponentModel;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using Glide.Core.Models;
using Microsoft.Win32.SafeHandles;

namespace Glide.Core.Storage;

public enum QuarantineCommitStrategy
{
    SameVolumeAtomicRename = 0,
    VerifiedCopyFallback = 1
}

public sealed record QuarantineCommitResult(
    string CommittedPath,
    QuarantineCommitStrategy StrategyUsed,
    long VerificationReadStopwatchTicks,
    long CommitStopwatchTicks);

public readonly record struct QuarantineFileIdentity(
    ulong DeviceMajor,
    ulong DeviceMinor,
    ulong Inode,
    uint OwnerUid,
    ushort Mode,
    bool IsDirectory,
    bool IsRegularFile,
    bool IsSymbolicLink);

public interface IQuarantineFileSystem
{
    uint GetEffectiveUserId();
    bool TryGetFileIdentityByPath(string path, out QuarantineFileIdentity identity, out int errno);
    bool TryGetFileIdentityByHandle(SafeFileHandle handle, out QuarantineFileIdentity identity, out int errno);
    void AtomicRenameNoReplace(
        SafeFileHandle? sourceFileHandle,
        string sourceDirectoryPath,
        string sourceFileName,
        string targetDirectoryPath,
        string targetFileName);
    void TryFlushDirectory(string directoryPath);
    Stream OpenVerificationReadWriteStream(string path, int bufferSize);
    Stream OpenReadStream(string path, int bufferSize);
    Stream OpenCreateNewWriteStream(string path, int bufferSize);
}

public sealed class QuarantineStagingStore
{
    public const int LinuxEperm = 1;
    public const int LinuxEio = 5;
    public const int LinuxEacces = 13;
    public const int LinuxEexist = 17;
    public const int LinuxExdev = 18;
    public const int LinuxEinval = 22;
    public const int LinuxEnospc = 28;
    public const int LinuxErofs = 30;
    public const int LinuxEnosys = 38;
    public const int LinuxEopnotsupp = 95;

    private const int MaxCommitCleanupEntries = 4_096;
    private const int MaxCommitCleanupDepth = 16;
    private const int MaxSessionCleanupEntries = 4_096;
    private const int VerificationReadBufferBytes = 262_144; // 256 KiB sequential verification read buffer
    private const string SessionLeaseFileName = ".glide-session.lease";
    private const string SameVolumeStagingSubdirectoryName = ".glide-staging";

    private static readonly TimeSpan AbandonedCommitAge = TimeSpan.FromMinutes(15);
    private static readonly TimeSpan AbandonedQuarantineAge = TimeSpan.FromDays(1);
    private static readonly ConcurrentDictionary<string, int> ActiveSessionLeases = new(StringComparer.OrdinalIgnoreCase);

    private readonly string _stagingRoot;
    private readonly string _destinationRoot;
    private readonly Func<long>? _freeSpaceProvider;
    private readonly Func<long>? _destinationFreeSpaceProvider;
    private readonly bool _enableSameVolumeStaging;
    private readonly IQuarantineFileSystem? _fileSystem;
    private bool _sameVolumeSecurityVerified = true;
    private int _commitCleanupAttempted;

    public string StagingRoot => _stagingRoot;
    public string DestinationRoot => _destinationRoot;
    public bool EnableSameVolumeStaging => _enableSameVolumeStaging;
    public QuarantineCommitStrategy LastCommitStrategy { get; private set; } = QuarantineCommitStrategy.SameVolumeAtomicRename;

    public QuarantineStagingStore(
        string? stagingRoot = null,
        string? destinationRoot = null,
        Func<long>? freeSpaceProvider = null,
        Func<long>? destinationFreeSpaceProvider = null,
        bool enableSameVolumeStaging = true,
        IQuarantineFileSystem? fileSystem = null)
    {
        _destinationRoot = Path.GetFullPath(
            destinationRoot ?? Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                "Downloads"));

        _enableSameVolumeStaging = enableSameVolumeStaging;
        _fileSystem = fileSystem;
        _stagingRoot = Path.GetFullPath(
            stagingRoot ?? ResolveDefaultStagingRoot(_destinationRoot, enableSameVolumeStaging));

        _freeSpaceProvider = freeSpaceProvider;
        _destinationFreeSpaceProvider = destinationFreeSpaceProvider;
    }

    private static string ResolveDefaultStagingRoot(string destinationRoot, bool enableSameVolumeStaging)
    {
        string defaultStaging = GetDefaultStagingRoot();

        if (OperatingSystem.IsWindows() && enableSameVolumeStaging)
        {
            try
            {
                if (IsEligibleWindowsNtfsVolume(destinationRoot, out DriveInfo? destDrive) && destDrive != null)
                {
                    if (AreOnSameVolume(defaultStaging, destinationRoot))
                    {
                        return defaultStaging;
                    }

                    // Place private application-managed staging on the destination NTFS volume
                    return Path.Combine(Path.GetFullPath(destinationRoot), SameVolumeStagingSubdirectoryName);
                }
            }
            catch
            {
                // Fall back to default LocalApplicationData staging root
            }
        }
        else if (OperatingSystem.IsLinux() && enableSameVolumeStaging)
        {
            try
            {
                if (IsEligibleLinuxSameVolumeDestination(destinationRoot, out string? linuxStaging) &&
                    linuxStaging != null)
                {
                    if (AreOnSameVolume(defaultStaging, destinationRoot))
                    {
                        return defaultStaging;
                    }

                    // Place private application-managed staging on the destination volume
                    return linuxStaging;
                }
            }
            catch
            {
                // Fall back to default XDG staging root
            }
        }

        return defaultStaging;
    }

    private static string GetDefaultStagingRoot()
    {
        if (OperatingSystem.IsLinux())
        {
            string? xdgData = Environment.GetEnvironmentVariable("XDG_DATA_HOME");
            if (!string.IsNullOrWhiteSpace(xdgData))
            {
                return Path.Combine(xdgData, "glide", "staging");
            }
        }

        return Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Glide", "staging");
    }

    /// <summary>
    /// Acquires an in-memory and on-disk lease for an active transfer session so maintenance
    /// cleanup never deletes an active transfer regardless of how long the transfer takes.
    /// </summary>
    public IDisposable AcquireSessionLease(SessionId sessionId)
    {
        string key = GetSessionLeaseKey(_stagingRoot, sessionId.Value);
        ActiveSessionLeases.AddOrUpdate(key, 1, static (_, count) => count + 1);
        TryWriteSessionLeaseFile(sessionId);
        return new SessionLeaseScope(_stagingRoot, sessionId);
    }

    private static string GetSessionLeaseKey(string stagingRoot, string sessionId) =>
        $"{Path.GetFullPath(stagingRoot)}::{sessionId}";

    private sealed class SessionLeaseScope : IDisposable
    {
        private readonly string _stagingRoot;
        private readonly SessionId _sessionId;
        private int _disposed;

        public SessionLeaseScope(string stagingRoot, SessionId sessionId)
        {
            _stagingRoot = stagingRoot;
            _sessionId = sessionId;
        }

        public void Dispose()
        {
            if (Interlocked.Exchange(ref _disposed, 1) != 0)
            {
                return;
            }

            ReleaseSessionLease(_stagingRoot, _sessionId);
        }
    }

    private static void ReleaseSessionLease(string stagingRoot, SessionId sessionId)
    {
        string key = GetSessionLeaseKey(stagingRoot, sessionId.Value);
        while (true)
        {
            if (!ActiveSessionLeases.TryGetValue(key, out int current))
            {
                break;
            }

            if (current <= 1)
            {
                if (ActiveSessionLeases.TryRemove(new KeyValuePair<string, int>(key, current)))
                {
                    TryDeleteSessionLeaseFile(stagingRoot, sessionId);
                    break;
                }
            }
            else if (ActiveSessionLeases.TryUpdate(key, current - 1, current))
            {
                break;
            }
        }
    }

    private void TryWriteSessionLeaseFile(SessionId sessionId)
    {
        try
        {
            string sessionDirectory = Path.Combine(_stagingRoot, sessionId.Value);
            if (!Directory.Exists(sessionDirectory))
            {
                return;
            }

            string leasePath = Path.Combine(sessionDirectory, SessionLeaseFileName);
            using var proc = Process.GetCurrentProcess();
            long startTicks = GetProcessStartTicksSafe(proc);
            string payload = $"{Environment.ProcessId}|{startTicks}|{DateTime.UtcNow.Ticks}";
            File.WriteAllText(leasePath, payload);
        }
        catch
        {
            // Best-effort lease marker
        }
    }

    private static void TryDeleteSessionLeaseFile(string stagingRoot, SessionId sessionId)
    {
        try
        {
            string sessionDirectory = Path.Combine(stagingRoot, sessionId.Value);
            string leasePath = Path.Combine(sessionDirectory, SessionLeaseFileName);
            if (File.Exists(leasePath))
            {
                File.Delete(leasePath);
            }
        }
        catch
        {
            // Best-effort lease cleanup
        }
    }

    private static bool IsSessionActivelyLeased(string stagingRoot, string sessionDirectory)
    {
        string sessionId = Path.GetFileName(sessionDirectory);
        string key = GetSessionLeaseKey(stagingRoot, sessionId);
        if (ActiveSessionLeases.TryGetValue(key, out int count) && count > 0)
        {
            return true;
        }

        string leasePath = Path.Combine(sessionDirectory, SessionLeaseFileName);
        if (!File.Exists(leasePath))
        {
            return false;
        }

        try
        {
            string content = File.ReadAllText(leasePath).Trim();
            string[] parts = content.Split('|');
            if (parts.Length >= 2 &&
                int.TryParse(parts[0], out int pid) &&
                long.TryParse(parts[1], out long expectedStartTicks))
            {
                if (pid == Environment.ProcessId)
                {
                    return ActiveSessionLeases.TryGetValue(key, out int localCount) && localCount > 0;
                }

                using Process proc = Process.GetProcessById(pid);
                if (proc.HasExited)
                {
                    return false;
                }

                long actualStartTicks = GetProcessStartTicksSafe(proc);
                return expectedStartTicks == 0 || actualStartTicks == 0 || actualStartTicks == expectedStartTicks;
            }
        }
        catch
        {
            // Process no longer exists or lease file unreadable -> not actively leased
        }

        return false;
    }

    private static long GetProcessStartTicksSafe(Process process)
    {
        try
        {
            return process.StartTime.ToUniversalTime().Ticks;
        }
        catch
        {
            return 0;
        }
    }

    public bool CanUseSameVolumeAtomicCommit()
    {
        if (!_enableSameVolumeStaging || !_sameVolumeSecurityVerified)
        {
            return false;
        }

        if (_fileSystem != null)
        {
            if (!_fileSystem.TryGetFileIdentityByPath(_stagingRoot, out QuarantineFileIdentity stagingStat, out int sErr))
            {
                if (IsFatalStorageErrno(sErr))
                {
                    throw CreateStorageExceptionFromErrno(sErr, "statx");
                }
                return false;
            }

            if (!_fileSystem.TryGetFileIdentityByPath(_destinationRoot, out QuarantineFileIdentity destStat, out int dErr))
            {
                if (IsFatalStorageErrno(dErr))
                {
                    throw CreateStorageExceptionFromErrno(dErr, "statx");
                }
                return false;
            }

            uint euid = _fileSystem.GetEffectiveUserId();
            if (stagingStat.IsSymbolicLink || destStat.IsSymbolicLink ||
                !stagingStat.IsDirectory || !destStat.IsDirectory ||
                stagingStat.OwnerUid != euid || destStat.OwnerUid != euid)
            {
                throw new IOException("Staging or destination directory failed ownership or symlink verification.");
            }

            return stagingStat.DeviceMajor == destStat.DeviceMajor &&
                   stagingStat.DeviceMinor == destStat.DeviceMinor;
        }

        if (OperatingSystem.IsWindows())
        {
            return IsEligibleWindowsNtfsVolume(_destinationRoot, out _) &&
                   IsEligibleWindowsNtfsVolume(_stagingRoot, out _) &&
                   AreOnSameVolume(_stagingRoot, _destinationRoot);
        }

        if (OperatingSystem.IsLinux())
        {
            if (TryGetLinuxFileIdentityByPath(_stagingRoot, out LinuxStatx stagingStat, out int sErr) &&
                TryGetLinuxFileIdentityByPath(_destinationRoot, out LinuxStatx destStat, out int dErr))
            {
                uint euid = LinuxGeteuid();
                return stagingStat.StxDevMajor == destStat.StxDevMajor &&
                       stagingStat.StxDevMinor == destStat.StxDevMinor &&
                       stagingStat.StxUid == euid &&
                       destStat.StxUid == euid &&
                       (stagingStat.StxMode & LinuxSIfmt) == LinuxSIfdir &&
                       (destStat.StxMode & LinuxSIfmt) == LinuxSIfdir;
            }

            return false;
        }

        return AreOnSameVolume(_stagingRoot, _destinationRoot);
    }

    internal static bool IsUnsupportedAtomicErrno(int errno) =>
        errno is LinuxEnosys or LinuxEopnotsupp or LinuxEinval or LinuxExdev;

    internal static bool IsFatalStorageErrno(int errno) =>
        errno is LinuxEacces or LinuxEperm or LinuxEnospc or LinuxErofs or LinuxEio;

    internal static Exception CreateStorageExceptionFromErrno(int errno, string operation, Exception? inner = null)
    {
        Win32Exception win32 = inner as Win32Exception ?? new Win32Exception(errno, $"{operation} failed with errno {errno}");
        return errno switch
        {
            LinuxEacces or LinuxEperm => new UnauthorizedAccessException(
                $"Permission denied during {operation} (errno {errno}).",
                win32),
            LinuxEnospc => new IOException(
                $"Insufficient disk space during {operation} (ENOSPC).",
                win32),
            LinuxErofs => new IOException(
                $"Destination filesystem is read-only during {operation} (EROFS).",
                win32),
            LinuxEio => new IOException(
                $"Storage I/O failure during {operation} (EIO).",
                win32),
            _ => new IOException(
                $"Storage operation {operation} failed with errno {errno}.",
                win32)
        };
    }

    public bool HasSufficientFreeSpaceForBatch(long totalBytes, long maxItemBytes = 0)
    {
        if (totalBytes < 0)
        {
            return false;
        }

        long stagingFree = GetAvailableFreeSpaceBytes();
        long destinationFree = GetDestinationAvailableFreeSpaceBytes();

        if (stagingFree < totalBytes || destinationFree < totalBytes)
        {
            return false;
        }

        // If staging and destination share a volume, but we must use the verified-copy fallback,
        // commit temporarily holds one additional item copy (.part) before deleting the .quarantine file.
        if (_freeSpaceProvider == null &&
            _destinationFreeSpaceProvider == null &&
            AreOnSameVolume(_stagingRoot, _destinationRoot) &&
            !CanUseSameVolumeAtomicCommit())
        {
            long extraNeeded = Math.Max(0, maxItemBytes);
            long totalNeeded = totalBytes > long.MaxValue - extraNeeded
                ? long.MaxValue
                : totalBytes + extraNeeded;
            return destinationFree >= totalNeeded;
        }

        return true;
    }

    public long GetAvailableFreeSpaceBytes()
    {
        if (_freeSpaceProvider != null) return _freeSpaceProvider();
        return GetAvailableFreeSpaceForPath(_stagingRoot);
    }

    public long GetDestinationAvailableFreeSpaceBytes()
    {
        if (_destinationFreeSpaceProvider != null) return _destinationFreeSpaceProvider();
        return GetAvailableFreeSpaceForPath(_destinationRoot);
    }

    private static long GetAvailableFreeSpaceForPath(string path)
    {
        try
        {
            DriveInfo? volume = ResolveVolumeDriveInfo(path);
            return volume?.AvailableFreeSpace ?? 0;
        }
        catch
        {
            // Unknown capacity is not evidence of enough capacity.
            return 0;
        }
    }

    private static DriveInfo? ResolveVolumeDriveInfo(string path)
    {
        string fullPath = Path.GetFullPath(path);
        StringComparison comparison = OperatingSystem.IsWindows()
            ? StringComparison.OrdinalIgnoreCase
            : StringComparison.Ordinal;
        return DriveInfo.GetDrives()
            .Where(drive => IsWithinVolume(fullPath, drive.RootDirectory.FullName, comparison))
            .OrderByDescending(drive => drive.RootDirectory.FullName.Length)
            .FirstOrDefault();
    }

    private static bool IsEligibleWindowsNtfsVolume(string path, out DriveInfo? volume)
    {
        volume = null;
        if (!OperatingSystem.IsWindows())
        {
            return false;
        }

        try
        {
            string fullPath = Path.GetFullPath(path);
            if (fullPath.StartsWith(@"\\", StringComparison.Ordinal))
            {
                return false;
            }

            volume = ResolveVolumeDriveInfo(fullPath);
            if (volume == null || !volume.IsReady)
            {
                return false;
            }

            if (volume.DriveType != DriveType.Fixed)
            {
                return false;
            }

            return string.Equals(volume.DriveFormat, "NTFS", StringComparison.OrdinalIgnoreCase);
        }
        catch
        {
            volume = null;
            return false;
        }
    }

    private static bool AreOnSameVolume(string pathA, string pathB)
    {
        try
        {
            DriveInfo? driveA = ResolveVolumeDriveInfo(pathA);
            DriveInfo? driveB = ResolveVolumeDriveInfo(pathB);
            if (driveA == null || driveB == null)
            {
                return false;
            }

            StringComparison comparison = OperatingSystem.IsWindows()
                ? StringComparison.OrdinalIgnoreCase
                : StringComparison.Ordinal;
            return string.Equals(
                Path.TrimEndingDirectorySeparator(driveA.RootDirectory.FullName),
                Path.TrimEndingDirectorySeparator(driveB.RootDirectory.FullName),
                comparison);
        }
        catch
        {
            return false;
        }
    }

    private static bool IsWithinVolume(string path, string volumeRoot, StringComparison comparison)
    {
        string root = Path.TrimEndingDirectorySeparator(Path.GetFullPath(volumeRoot));
        if (root.Equals(Path.GetPathRoot(root), comparison))
            return path.StartsWith(root, comparison);
        return path.Equals(root, comparison) ||
            path.StartsWith(root + Path.DirectorySeparatorChar, comparison);
    }

    /// <summary>
    /// Checks that verified files can be committed to the selected destination before
    /// an offer is accepted. Commit still revalidates the path and handles later changes.
    /// </summary>
    public async Task VerifyDestinationWritableAsync(CancellationToken cancellationToken = default)
    {
        string root = Path.GetFullPath(_destinationRoot);
        RejectLinkedAncestors(root);
        Directory.CreateDirectory(root);
        RejectLinkedAncestors(root);

        string probe = Path.Combine(root, $".glide-write-check-{Guid.NewGuid():N}.tmp");
        try
        {
            await using var stream = new FileStream(probe, FileMode.CreateNew, FileAccess.Write,
                FileShare.None, 1, FileOptions.Asynchronous | FileOptions.DeleteOnClose);
            await stream.WriteAsync(new byte[] { 0 }, cancellationToken).ConfigureAwait(false);
            await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            // DeleteOnClose handles normal disposal; this covers platforms that leave a
            // probe behind after an interrupted open or flush.
            if (File.Exists(probe)) File.Delete(probe);
        }
    }

    public string GetQuarantinePath(SessionId sessionId, ItemId itemId)
    {
        return Path.Combine(_stagingRoot, sessionId.Value, $"{itemId.Value}.quarantine");
    }

    public Stream OpenQuarantineWrite(SessionId sessionId, ItemId itemId)
    {
        if (Interlocked.Exchange(ref _commitCleanupAttempted, 1) == 0)
        {
            PurgeAbandonedCommitFiles();
            PurgeAbandonedQuarantineSessions();
        }
        string sessionDirectory = Path.Combine(_stagingRoot, sessionId.Value);
        EnsurePrivateDirectory(_stagingRoot, ref _sameVolumeSecurityVerified);
        EnsurePrivateDirectory(sessionDirectory, ref _sameVolumeSecurityVerified);

        string key = GetSessionLeaseKey(_stagingRoot, sessionId.Value);
        ActiveSessionLeases.AddOrUpdate(key, 1, static (_, count) => count + 1);
        TryWriteSessionLeaseFile(sessionId);

        string path = GetQuarantinePath(sessionId, itemId);
        Stream? innerStream = null;
        try
        {
            innerStream = _fileSystem != null
                ? _fileSystem.OpenCreateNewWriteStream(path, 65_536)
                : new FileStream(path, FileMode.CreateNew, FileAccess.Write,
                    FileShare.None, 65_536, useAsync: true);
            if (!OperatingSystem.IsWindows() && File.Exists(path))
            {
                File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite);
            }

            return new LeasedQuarantineStream(innerStream, _stagingRoot, sessionId);
        }
        catch
        {
            innerStream?.Dispose();
            try
            {
                if (File.Exists(path)) File.Delete(path);
            }
            catch
            {
                // Ignore delete error on failed open
            }
            ReleaseSessionLease(_stagingRoot, sessionId);
            throw;
        }
    }

    private sealed class LeasedQuarantineStream : Stream
    {
        private readonly Stream _inner;
        private readonly string _stagingRoot;
        private readonly SessionId _sessionId;
        private int _leaseReleased;

        public LeasedQuarantineStream(Stream inner, string stagingRoot, SessionId sessionId)
        {
            _inner = inner;
            _stagingRoot = stagingRoot;
            _sessionId = sessionId;
        }

        public override bool CanRead => _inner.CanRead;
        public override bool CanSeek => _inner.CanSeek;
        public override bool CanWrite => _inner.CanWrite;
        public override long Length => _inner.Length;
        public override long Position
        {
            get => _inner.Position;
            set => _inner.Position = value;
        }

        public override void Flush() => _inner.Flush();
        public override Task FlushAsync(CancellationToken cancellationToken) =>
            _inner.FlushAsync(cancellationToken);
        public override int Read(byte[] buffer, int offset, int count) =>
            _inner.Read(buffer, offset, count);
        public override ValueTask<int> ReadAsync(
            Memory<byte> buffer, CancellationToken cancellationToken = default) =>
            _inner.ReadAsync(buffer, cancellationToken);
        public override long Seek(long offset, SeekOrigin origin) =>
            _inner.Seek(offset, origin);
        public override void SetLength(long value) => _inner.SetLength(value);
        public override void Write(byte[] buffer, int offset, int count) =>
            _inner.Write(buffer, offset, count);
        public override ValueTask WriteAsync(
            ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default) =>
            _inner.WriteAsync(buffer, cancellationToken);

        protected override void Dispose(bool disposing)
        {
            try
            {
                if (disposing) _inner.Dispose();
            }
            finally
            {
                if (Interlocked.Exchange(ref _leaseReleased, 1) == 0)
                {
                    ReleaseSessionLease(_stagingRoot, _sessionId);
                }
            }
        }

        public override async ValueTask DisposeAsync()
        {
            try
            {
                await _inner.DisposeAsync().ConfigureAwait(false);
            }
            finally
            {
                if (Interlocked.Exchange(ref _leaseReleased, 1) == 0)
                {
                    ReleaseSessionLease(_stagingRoot, _sessionId);
                }
            }
        }
    }

    public async Task<(string committedPath, Sha256Digest actualDigest)> WriteAndCommitAsync(
        SessionId sessionId,
        ItemId itemId,
        string rawRelativePath,
        Stream inputStream,
        Sha256Digest expectedDigest,
        long expectedSizeBytes,
        CancellationToken cancellationToken = default)
    {
        if (expectedSizeBytes < 0 || expectedSizeBytes > Glide.Core.Codec.ProtocolCatalog.MaxItemBytes)
            throw new ProtocolViolationException(
                WireErrorCodes.LimitExceeded, "The declared item size exceeds the protocol limit.");
        string sanitizedRelativePath = WindowsPathSanitizer.SanitizeRelativePath(rawRelativePath);

        byte[] computedHash;
        long totalWritten = 0;

        using IDisposable sessionLease = AcquireSessionLease(sessionId);
        try
        {
            await using (var fileStream = OpenQuarantineWrite(sessionId, itemId))
            using (var sha256 = IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
            {
                byte[] buffer = ArrayPool<byte>.Shared.Rent(65_536);
                try
                {
                    int bytesRead;
                    while ((bytesRead = await inputStream.ReadAsync(buffer.AsMemory(0, 65_536), cancellationToken).ConfigureAwait(false)) > 0)
                    {
                        if (bytesRead > expectedSizeBytes - totalWritten)
                        {
                            throw new ProtocolViolationException(
                                WireErrorCodes.LimitExceeded,
                                $"Stream exceeded declared item size {expectedSizeBytes} bytes.");
                        }
                        await fileStream.WriteAsync(buffer.AsMemory(0, bytesRead), cancellationToken).ConfigureAwait(false);
                        sha256.AppendData(buffer.AsSpan(0, bytesRead));
                        totalWritten = checked(totalWritten + bytesRead);
                    }

                    if (totalWritten != expectedSizeBytes)
                    {
                        throw new ProtocolViolationException(
                            WireErrorCodes.TransferInterrupted,
                            $"Stream finished early: expected {expectedSizeBytes} bytes, but received {totalWritten} bytes");
                    }

                    await fileStream.FlushAsync(cancellationToken).ConfigureAwait(false);
                    computedHash = sha256.GetHashAndReset();
                }
                finally
                {
                    CryptographicOperations.ZeroMemory(buffer.AsSpan(0, 65_536));
                    ArrayPool<byte>.Shared.Return(buffer);
                }
            }

            string actualHashHex = Convert.ToHexStringLower(computedHash);
            var actualDigest = new Sha256Digest(actualHashHex);

            if (!string.Equals(actualHashHex, expectedDigest.Value, StringComparison.OrdinalIgnoreCase))
            {
                PurgeQuarantine(sessionId, itemId);
                throw new ProtocolViolationException(
                    WireErrorCodes.IntegrityMismatch,
                    $"SHA-256 integrity mismatch! Expected {expectedDigest.Value}, got {actualHashHex}");
            }

            string finalPath = await CommitVerifiedQuarantineFileAsync(
                sessionId, itemId, sanitizedRelativePath, actualDigest,
                expectedSizeBytes, cancellationToken).ConfigureAwait(false);
            return (finalPath, actualDigest);
        }
        catch
        {
            PurgeQuarantine(sessionId, itemId);
            throw;
        }
    }

    public void PurgeQuarantine(SessionId sessionId, ItemId itemId)
    {
        try
        {
            string quarantinePath = GetQuarantinePath(sessionId, itemId);
            if (File.Exists(quarantinePath))
            {
                File.Delete(quarantinePath);
            }
        }
        catch
        {
            // Ignore purge cleanup errors
        }
    }

    public void PurgeSession(SessionId sessionId)
    {
        try
        {
            TryDeleteSessionLeaseFile(_stagingRoot, sessionId);
            string sessionDir = Path.Combine(_stagingRoot, sessionId.Value);
            if (Directory.Exists(sessionDir))
            {
                Directory.Delete(sessionDir, recursive: true);
            }
        }
        catch
        {
            // Ignore purge cleanup errors
        }
    }

    public async Task<string> CommitVerifiedQuarantineFileAsync(
        SessionId sessionId,
        ItemId itemId,
        string rawRelativePath,
        Sha256Digest expectedDigest,
        long expectedSizeBytes,
        CancellationToken cancellationToken = default)
    {
        QuarantineCommitResult result = await CommitVerifiedQuarantineFileWithMetricsAsync(
            sessionId,
            itemId,
            rawRelativePath,
            expectedDigest,
            expectedSizeBytes,
            cancellationToken).ConfigureAwait(false);
        return result.CommittedPath;
    }

    public async Task<QuarantineCommitResult> CommitVerifiedQuarantineFileWithMetricsAsync(
        SessionId sessionId,
        ItemId itemId,
        string rawRelativePath,
        Sha256Digest expectedDigest,
        long expectedSizeBytes,
        CancellationToken cancellationToken = default,
        Action? onBeforePublish = null,
        Action<long>? onStorageProgress = null,
        TimeSpan? storageProgressTimeout = null)
    {
        string quarantinePath = GetQuarantinePath(sessionId, itemId);
        if (!File.Exists(quarantinePath))
            throw new FileNotFoundException($"Quarantine file not found: {quarantinePath}");
        if ((File.GetAttributes(quarantinePath) & FileAttributes.ReparsePoint) != 0)
            throw new IOException("The quarantine file is a symbolic link or reparse point.");
        if (expectedSizeBytes < 0 || expectedSizeBytes > Glide.Core.Codec.ProtocolCatalog.MaxItemBytes)
            throw new ArgumentOutOfRangeException(nameof(expectedSizeBytes));

        using var watchdogCts = storageProgressTimeout.HasValue
            ? CancellationTokenSource.CreateLinkedTokenSource(cancellationToken)
            : null;
        if (watchdogCts != null && storageProgressTimeout.HasValue)
        {
            watchdogCts.CancelAfter(storageProgressTimeout.Value);
        }

        void ReportProgress(long bytes)
        {
            if (watchdogCts != null && storageProgressTimeout.HasValue && !cancellationToken.IsCancellationRequested)
            {
                try
                {
                    watchdogCts.CancelAfter(storageProgressTimeout.Value);
                }
                catch (ObjectDisposedException)
                {
                }
            }
            onStorageProgress?.Invoke(bytes);
        }

        CancellationToken effectiveToken = watchdogCts?.Token ?? cancellationToken;

        try
        {
            if (CanUseSameVolumeAtomicCommit())
            {
                QuarantineCommitResult? atomicResult = await TryCommitSameVolumeAtomicAsync(
                    quarantinePath,
                    rawRelativePath,
                    expectedDigest,
                    expectedSizeBytes,
                    onBeforePublish,
                    ReportProgress,
                    effectiveToken).ConfigureAwait(false);

                if (atomicResult != null)
                {
                    LastCommitStrategy = QuarantineCommitStrategy.SameVolumeAtomicRename;
                    return atomicResult;
                }
            }

            LastCommitStrategy = QuarantineCommitStrategy.VerifiedCopyFallback;
            ReportProgress(0);
            onBeforePublish?.Invoke();
            return await CommitViaVerifiedCopyFallbackAsync(
                quarantinePath,
                rawRelativePath,
                expectedDigest,
                expectedSizeBytes,
                ReportProgress,
                effectiveToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (watchdogCts != null && watchdogCts.IsCancellationRequested && !cancellationToken.IsCancellationRequested)
        {
            throw new IOException(
                "Local storage verification or publication made no progress within the 60-second timeout.",
                new TimeoutException("Local storage I/O progress timed out."));
        }
    }

    private async Task<QuarantineCommitResult?> TryCommitSameVolumeAtomicAsync(
        string quarantinePath,
        string rawRelativePath,
        Sha256Digest expectedDigest,
        long expectedSizeBytes,
        Action? onBeforePublish,
        Action<long>? onStorageProgress,
        CancellationToken cancellationToken)
    {
        string initialFinalPath = ResolveSafeDestinationPath(rawRelativePath);
        string targetDirectory = Path.GetDirectoryName(initialFinalPath)!;
        string baseFileName = Path.GetFileName(WindowsPathSanitizer.SanitizeRelativePath(rawRelativePath).Split('/')[^1]);

        if (_fileSystem != null)
        {
            return await TryCommitInjectedFileSystemAtomicAsync(
                quarantinePath,
                rawRelativePath,
                initialFinalPath,
                targetDirectory,
                baseFileName,
                expectedDigest,
                expectedSizeBytes,
                onBeforePublish,
                onStorageProgress,
                cancellationToken).ConfigureAwait(false);
        }

        if (OperatingSystem.IsWindows())
        {
            if (!AreOnSameVolume(quarantinePath, targetDirectory))
            {
                return null;
            }
        }
        else if (OperatingSystem.IsLinux())
        {
            if (!TryGetLinuxFileIdentityByPath(quarantinePath, out LinuxStatx qStat, out int qErr))
            {
                if (IsFatalStorageErrno(qErr))
                {
                    throw CreateStorageExceptionFromErrno(qErr, "statx");
                }
                return null;
            }

            if ((qStat.StxMode & LinuxSIfmt) == LinuxS_Iflnk ||
                (qStat.StxMode & LinuxSIfmt) != LinuxSIfreg ||
                qStat.StxUid != LinuxGeteuid())
            {
                throw new IOException("The quarantine file is not a valid user-owned regular file.");
            }

            if (!TryGetLinuxFileIdentityByPath(targetDirectory, out LinuxStatx tStat, out int tErr))
            {
                if (IsFatalStorageErrno(tErr))
                {
                    throw CreateStorageExceptionFromErrno(tErr, "statx");
                }
                return null;
            }

            if ((tStat.StxMode & LinuxSIfmt) == LinuxS_Iflnk ||
                (tStat.StxMode & LinuxSIfmt) != LinuxSIfdir)
            {
                throw new IOException("The destination directory is a symbolic link or invalid directory.");
            }

            if (qStat.StxDevMajor != tStat.StxDevMajor ||
                qStat.StxDevMinor != tStat.StxDevMinor)
            {
                return null;
            }
        }
        else if (!AreOnSameVolume(quarantinePath, targetDirectory))
        {
            return null;
        }

        long verifyStart = Stopwatch.GetTimestamp();
        long verifyTicks = 0;
        long commitTicks = 0;
        string committedPath = initialFinalPath;
        ByHandleFileInformation sourceHandleInfo = default;
        bool hasWindowsHandleInfo = false;
        LinuxStatx sourceLinuxStatx = default;
        bool hasLinuxHandleInfo = false;

        // 1. Open staged file with exclusive lock (FileShare.None), flush to disk, validate handle, and independently re-verify length & SHA-256
        await using (var source = OpenExclusiveVerificationStream(quarantinePath))
        {
            if (OperatingSystem.IsWindows())
            {
                if (!TryGetWindowsHandleInfo(source.SafeFileHandle, out sourceHandleInfo))
                {
                    return null;
                }

                if ((sourceHandleInfo.FileAttributes & (uint)FileAttributes.ReparsePoint) != 0)
                {
                    throw new IOException("The quarantine file handle refers to a reparse point.");
                }

                hasWindowsHandleInfo = true;
            }
            else if (OperatingSystem.IsLinux())
            {
                if (!TryGetLinuxFileIdentityByFd(source.SafeFileHandle.DangerousGetHandle().ToInt32(), out sourceLinuxStatx, out int fdErr))
                {
                    if (IsFatalStorageErrno(fdErr))
                    {
                        throw CreateStorageExceptionFromErrno(fdErr, "statx");
                    }
                    return null;
                }

                if ((sourceLinuxStatx.StxMode & LinuxSIfmt) != LinuxSIfreg ||
                    sourceLinuxStatx.StxUid != LinuxGeteuid())
                {
                    throw new IOException("The quarantine file descriptor is not a valid user-owned regular file.");
                }

                hasLinuxHandleInfo = true;
            }

            source.Flush(flushToDisk: true);
            onStorageProgress?.Invoke(0);

            if (source.Length != expectedSizeBytes)
            {
                throw new IOException("The staged file changed before final commit.");
            }

            using var hasher = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            byte[] buffer = ArrayPool<byte>.Shared.Rent(VerificationReadBufferBytes);
            try
            {
                long totalRead = 0;
                int count;
                while ((count = await source.ReadAsync(buffer.AsMemory(0, VerificationReadBufferBytes), cancellationToken).ConfigureAwait(false)) > 0)
                {
                    totalRead = checked(totalRead + count);
                    if (totalRead > expectedSizeBytes)
                    {
                        throw new IOException("The staged file exceeded its verified length.");
                    }
                    hasher.AppendData(buffer.AsSpan(0, count));
                    onStorageProgress?.Invoke(count);
                }

                string verifiedDigest = Convert.ToHexStringLower(hasher.GetHashAndReset());
                if (totalRead != expectedSizeBytes ||
                    !string.Equals(verifiedDigest, expectedDigest.Value, StringComparison.Ordinal))
                {
                    throw new IOException("The staged file changed before final commit.");
                }
            }
            finally
            {
                CryptographicOperations.ZeroMemory(buffer.AsSpan(0, VerificationReadBufferBytes));
                ArrayPool<byte>.Shared.Return(buffer);
            }
            verifyTicks = Stopwatch.GetTimestamp() - verifyStart;
            onBeforePublish?.Invoke();
            long commitStart = Stopwatch.GetTimestamp();

            // 2. Bind destination directory handle so path replacement cannot substitute a symlink/junction during commit
            using SafeFileHandle? targetDirHandle = OperatingSystem.IsWindows()
                ? OpenValidatedWindowsDirectoryHandle(targetDirectory, out ByHandleFileInformation dirHandleInfo)
                : null;

            if (OperatingSystem.IsWindows() && hasWindowsHandleInfo)
            {
                if (targetDirHandle == null || targetDirHandle.IsInvalid)
                {
                    return null;
                }

                if (!TryGetWindowsHandleInfo(targetDirHandle, out ByHandleFileInformation validatedDirInfo) ||
                    (validatedDirInfo.FileAttributes & (uint)FileAttributes.ReparsePoint) != 0)
                {
                    throw new IOException("The destination directory is a symbolic link or reparse point.");
                }

                if (validatedDirInfo.VolumeSerialNumber != sourceHandleInfo.VolumeSerialNumber)
                {
                    // Different volume at handle level -> fall back to verified copy
                    return null;
                }
            }

            LinuxStatx validatedLinuxDirInfo = default;
            using SafeFileHandle? linuxTargetDirHandle = OperatingSystem.IsLinux() && hasLinuxHandleInfo
                ? OpenValidatedLinuxDirectoryHandle(targetDirectory, out validatedLinuxDirInfo)
                : null;

            LinuxStatx validatedLinuxStagingDirInfo = default;
            using SafeFileHandle? linuxStagingDirHandle = OperatingSystem.IsLinux() && hasLinuxHandleInfo
                ? OpenValidatedLinuxDirectoryHandle(Path.GetDirectoryName(quarantinePath)!, out validatedLinuxStagingDirInfo)
                : null;

            if (OperatingSystem.IsLinux() && hasLinuxHandleInfo)
            {
                if (linuxTargetDirHandle == null || linuxTargetDirHandle.IsInvalid ||
                    linuxStagingDirHandle == null || linuxStagingDirHandle.IsInvalid)
                {
                    return null;
                }

                if (validatedLinuxDirInfo.StxDevMajor != sourceLinuxStatx.StxDevMajor ||
                    validatedLinuxDirInfo.StxDevMinor != sourceLinuxStatx.StxDevMinor ||
                    validatedLinuxStagingDirInfo.StxDevMajor != sourceLinuxStatx.StxDevMajor ||
                    validatedLinuxStagingDirInfo.StxDevMinor != sourceLinuxStatx.StxDevMinor)
                {
                    return null;
                }
            }

            // 3. Publish using same-volume atomic rename with collision handling (overwrite: false)
            committedPath = ResolveSafeDestinationPath(rawRelativePath);
            if (!string.Equals(
                    Path.GetDirectoryName(committedPath),
                    targetDirectory,
                    OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal))
            {
                throw new IOException("The destination directory changed before commit.");
            }

            for (int attempt = 0; ; attempt++)
            {
                try
                {
                    if (OperatingSystem.IsWindows())
                    {
                        RenameVerifiedWindowsFile(
                            source.SafeFileHandle,
                            targetDirHandle!,
                            Path.GetFileName(committedPath));
                    }
                    else if (OperatingSystem.IsLinux() && hasLinuxHandleInfo)
                    {
                        RenameVerifiedLinuxFile(
                            linuxStagingDirHandle!,
                            Path.GetFileName(quarantinePath),
                            linuxTargetDirHandle!,
                            Path.GetFileName(committedPath));
                    }
                    else
                    {
                        File.Move(quarantinePath, committedPath, overwrite: false);
                    }
                    break;
                }
                catch (Win32Exception error) when (
                    OperatingSystem.IsWindows() &&
                    attempt < 32 &&
                    error.NativeErrorCode is 80 or 183)
                {
                    committedPath = ResolveCollision(Path.Combine(targetDirectory, baseFileName));
                }
                catch (Win32Exception error) when (
                    OperatingSystem.IsLinux() &&
                    attempt < 32 &&
                    error.NativeErrorCode == LinuxEexist)
                {
                    committedPath = ResolveCollision(Path.Combine(targetDirectory, baseFileName));
                }
                catch (Win32Exception error) when (
                    OperatingSystem.IsLinux() &&
                    IsUnsupportedAtomicErrno(error.NativeErrorCode))
                {
                    return null;
                }
                catch (Win32Exception error) when (
                    OperatingSystem.IsLinux() &&
                    IsFatalStorageErrno(error.NativeErrorCode))
                {
                    throw CreateStorageExceptionFromErrno(error.NativeErrorCode, "renameat2", error);
                }
                catch (IOException) when (
                    !OperatingSystem.IsWindows() && !OperatingSystem.IsLinux() &&
                    attempt < 32 && File.Exists(committedPath) && File.Exists(quarantinePath))
                {
                    committedPath = ResolveCollision(Path.Combine(targetDirectory, baseFileName));
                }
            }

            // The same exclusive source handle was verified and renamed relative to
            // the validated destination directory handle. It has not been reopened
            // by path between verification and publication.
            if (OperatingSystem.IsWindows() && hasWindowsHandleInfo)
            {
                if (!TryGetWindowsHandleInfo(source.SafeFileHandle, out ByHandleFileInformation publishedInfo) ||
                    publishedInfo.VolumeSerialNumber != sourceHandleInfo.VolumeSerialNumber ||
                    publishedInfo.FileIndexHigh != sourceHandleInfo.FileIndexHigh ||
                    publishedInfo.FileIndexLow != sourceHandleInfo.FileIndexLow)
                {
                    throw new IOException("Destination file handle identity changed during atomic commit.");
                }
            }
            else if (OperatingSystem.IsLinux() && hasLinuxHandleInfo)
            {
                if (!TryGetLinuxFileIdentityByFd(source.SafeFileHandle.DangerousGetHandle().ToInt32(), out LinuxStatx publishedInfo, out _) ||
                    publishedInfo.StxDevMajor != sourceLinuxStatx.StxDevMajor ||
                    publishedInfo.StxDevMinor != sourceLinuxStatx.StxDevMinor ||
                    publishedInfo.StxIno != sourceLinuxStatx.StxIno)
                {
                    throw new IOException("Destination file descriptor identity changed during atomic commit.");
                }

                if (!TryGetLinuxFileIdentityByPath(committedPath, out LinuxStatx committedInfo, out _) ||
                    committedInfo.StxDevMajor != sourceLinuxStatx.StxDevMajor ||
                    committedInfo.StxDevMinor != sourceLinuxStatx.StxDevMinor ||
                    committedInfo.StxIno != sourceLinuxStatx.StxIno)
                {
                    throw new IOException("Published destination file identity does not match verified source file.");
                }

                TryFlushLinuxDirectory(linuxTargetDirHandle);
            }

            commitTicks = Stopwatch.GetTimestamp() - commitStart;
        }

        // The verification handle is now closed, so metadata operations can
        // reopen the published file without conflicting with FileShare.None.
        TryNormalizeCommittedFileMetadata(committedPath);
        return new QuarantineCommitResult(
            committedPath,
            QuarantineCommitStrategy.SameVolumeAtomicRename,
            verifyTicks,
            commitTicks);
    }

    private async Task<QuarantineCommitResult?> TryCommitInjectedFileSystemAtomicAsync(
        string quarantinePath,
        string rawRelativePath,
        string initialFinalPath,
        string targetDirectory,
        string baseFileName,
        Sha256Digest expectedDigest,
        long expectedSizeBytes,
        Action? onBeforePublish,
        Action<long>? onStorageProgress,
        CancellationToken cancellationToken)
    {
        IQuarantineFileSystem fs = _fileSystem!;
        uint euid = fs.GetEffectiveUserId();

        if (!fs.TryGetFileIdentityByPath(quarantinePath, out QuarantineFileIdentity qStat, out int qErr))
        {
            if (IsFatalStorageErrno(qErr))
            {
                throw CreateStorageExceptionFromErrno(qErr, "statx");
            }
            return null;
        }

        if (qStat.IsSymbolicLink || !qStat.IsRegularFile || qStat.OwnerUid != euid)
        {
            throw new IOException("The quarantine file is not a valid user-owned regular file.");
        }

        if (!fs.TryGetFileIdentityByPath(targetDirectory, out QuarantineFileIdentity tStat, out int tErr))
        {
            if (IsFatalStorageErrno(tErr))
            {
                throw CreateStorageExceptionFromErrno(tErr, "statx");
            }
            return null;
        }

        if (tStat.IsSymbolicLink || !tStat.IsDirectory)
        {
            throw new IOException("The destination directory is a symbolic link or invalid directory.");
        }

        if (qStat.DeviceMajor != tStat.DeviceMajor || qStat.DeviceMinor != tStat.DeviceMinor)
        {
            return null;
        }

        long verifyStart = Stopwatch.GetTimestamp();
        long verifyTicks;
        long commitTicks;
        string committedPath = initialFinalPath;

        await using (Stream source = fs.OpenVerificationReadWriteStream(quarantinePath, VerificationReadBufferBytes))
        {
            SafeFileHandle? safeHandle = (source as FileStream)?.SafeFileHandle;
            if (safeHandle != null)
            {
                if (!fs.TryGetFileIdentityByHandle(safeHandle, out QuarantineFileIdentity fdStat, out int fdErr))
                {
                    if (IsFatalStorageErrno(fdErr))
                    {
                        throw CreateStorageExceptionFromErrno(fdErr, "statx");
                    }
                    return null;
                }

                if (fdStat.IsSymbolicLink || !fdStat.IsRegularFile || fdStat.OwnerUid != euid ||
                    fdStat.DeviceMajor != qStat.DeviceMajor || fdStat.DeviceMinor != qStat.DeviceMinor ||
                    fdStat.Inode != qStat.Inode)
                {
                    throw new IOException("The quarantine file descriptor identity or ownership is invalid.");
                }
            }

            if (source is FileStream fsSource)
            {
                fsSource.Flush(flushToDisk: true);
            }
            else
            {
                await source.FlushAsync(cancellationToken).ConfigureAwait(false);
            }
            onStorageProgress?.Invoke(0);

            if (source.Length != expectedSizeBytes)
            {
                throw new IOException("The staged file changed before final commit.");
            }

            using var hasher = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            byte[] buffer = ArrayPool<byte>.Shared.Rent(VerificationReadBufferBytes);
            try
            {
                long totalRead = 0;
                int count;
                while ((count = await source.ReadAsync(buffer.AsMemory(0, VerificationReadBufferBytes), cancellationToken).ConfigureAwait(false)) > 0)
                {
                    totalRead = checked(totalRead + count);
                    if (totalRead > expectedSizeBytes)
                    {
                        throw new IOException("The staged file exceeded its verified length.");
                    }
                    hasher.AppendData(buffer.AsSpan(0, count));
                    onStorageProgress?.Invoke(count);
                }

                string verifiedDigest = Convert.ToHexStringLower(hasher.GetHashAndReset());
                if (totalRead != expectedSizeBytes ||
                    !string.Equals(verifiedDigest, expectedDigest.Value, StringComparison.Ordinal))
                {
                    throw new IOException("The staged file changed before final commit.");
                }
            }
            finally
            {
                CryptographicOperations.ZeroMemory(buffer.AsSpan(0, VerificationReadBufferBytes));
                ArrayPool<byte>.Shared.Return(buffer);
            }

            verifyTicks = Stopwatch.GetTimestamp() - verifyStart;
            onBeforePublish?.Invoke();
            long commitStart = Stopwatch.GetTimestamp();

            committedPath = ResolveSafeDestinationPath(rawRelativePath);
            string stagingDir = Path.GetDirectoryName(quarantinePath)!;
            string sourceFileName = Path.GetFileName(quarantinePath);

            for (int attempt = 0; ; attempt++)
            {
                try
                {
                    fs.AtomicRenameNoReplace(
                        safeHandle,
                        stagingDir,
                        sourceFileName,
                        targetDirectory,
                        Path.GetFileName(committedPath));
                    break;
                }
                catch (Win32Exception error) when (
                    attempt < 32 &&
                    error.NativeErrorCode is LinuxEexist or 80 or 183)
                {
                    committedPath = ResolveCollision(Path.Combine(targetDirectory, baseFileName));
                }
                catch (Win32Exception error) when (IsUnsupportedAtomicErrno(error.NativeErrorCode))
                {
                    return null;
                }
                catch (Win32Exception error) when (IsFatalStorageErrno(error.NativeErrorCode))
                {
                    throw CreateStorageExceptionFromErrno(error.NativeErrorCode, "renameat2", error);
                }
            }

            if (fs.TryGetFileIdentityByPath(committedPath, out QuarantineFileIdentity committedInfo, out int cErr))
            {
                if (committedInfo.IsSymbolicLink ||
                    committedInfo.DeviceMajor != qStat.DeviceMajor ||
                    committedInfo.DeviceMinor != qStat.DeviceMinor ||
                    committedInfo.Inode != qStat.Inode)
                {
                    throw new IOException("Published destination file identity does not match verified source file.");
                }
            }
            else if (IsFatalStorageErrno(cErr))
            {
                throw CreateStorageExceptionFromErrno(cErr, "statx");
            }

            fs.TryFlushDirectory(targetDirectory);
            commitTicks = Stopwatch.GetTimestamp() - commitStart;
        }

        TryNormalizeCommittedFileMetadata(committedPath);
        return new QuarantineCommitResult(
            committedPath,
            QuarantineCommitStrategy.SameVolumeAtomicRename,
            verifyTicks,
            commitTicks);
    }

    private async Task<QuarantineCommitResult> CommitViaVerifiedCopyFallbackAsync(
        string quarantinePath,
        string rawRelativePath,
        Sha256Digest expectedDigest,
        long expectedSizeBytes,
        Action<long>? onStorageProgress,
        CancellationToken cancellationToken)
    {
        if (GetDestinationAvailableFreeSpaceBytes() < expectedSizeBytes)
        {
            throw new IOException(
                "Insufficient disk space on destination volume for verified copy fallback (ENOSPC).",
                new Win32Exception(LinuxEnospc));
        }

        string finalPath = ResolveSafeDestinationPath(rawRelativePath);
        string targetDirectory = Path.GetDirectoryName(finalPath)!;
        string baseFileName = Path.GetFileName(WindowsPathSanitizer.SanitizeRelativePath(rawRelativePath).Split('/')[^1]);
        string temporaryPath = Path.Combine(
            targetDirectory,
            $".glide-commit-{Guid.NewGuid():N}.part");

        long verifyTicks = 0;
        long commitStart = Stopwatch.GetTimestamp();

        try
        {
            byte[] buffer = ArrayPool<byte>.Shared.Rent(VerificationReadBufferBytes);
            try
            {
                Stream OpenRead(string path) => _fileSystem != null
                    ? _fileSystem.OpenReadStream(path, VerificationReadBufferBytes)
                    : new FileStream(
                        path,
                        FileMode.Open,
                        FileAccess.Read,
                        FileShare.None,
                        VerificationReadBufferBytes,
                        FileOptions.Asynchronous | FileOptions.SequentialScan);

                Stream OpenWrite(string path) => _fileSystem != null
                    ? _fileSystem.OpenCreateNewWriteStream(path, VerificationReadBufferBytes)
                    : new FileStream(
                        path,
                        FileMode.CreateNew,
                        FileAccess.Write,
                        FileShare.None,
                        VerificationReadBufferBytes,
                        FileOptions.Asynchronous);

                await using (Stream source = OpenRead(quarantinePath))
                {
                    await using (Stream target = OpenWrite(temporaryPath))
                    {
                        if (!OperatingSystem.IsWindows() && File.Exists(temporaryPath))
                        {
                            File.SetUnixFileMode(
                                temporaryPath,
                                UnixFileMode.UserRead | UnixFileMode.UserWrite);
                        }

                        using var hasher = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
                        long copied = 0;
                        while (true)
                        {
                            long readStart = Stopwatch.GetTimestamp();
                            int count = await source.ReadAsync(
                                buffer.AsMemory(0, VerificationReadBufferBytes),
                                cancellationToken).ConfigureAwait(false);
                            if (count <= 0)
                            {
                                break;
                            }

                            copied = checked(copied + count);
                            if (copied > expectedSizeBytes)
                            {
                                throw new IOException("The staged file exceeded its verified length.");
                            }

                            hasher.AppendData(buffer.AsSpan(0, count));
                            verifyTicks += Stopwatch.GetTimestamp() - readStart;
                            onStorageProgress?.Invoke(count);

                            await target.WriteAsync(
                                buffer.AsMemory(0, count),
                                cancellationToken).ConfigureAwait(false);
                            onStorageProgress?.Invoke(count);
                        }

                        long hashFinalStart = Stopwatch.GetTimestamp();
                        string copiedDigest = Convert.ToHexStringLower(hasher.GetHashAndReset());
                        verifyTicks += Stopwatch.GetTimestamp() - hashFinalStart;

                        if (copied != expectedSizeBytes ||
                            !string.Equals(copiedDigest, expectedDigest.Value, StringComparison.Ordinal))
                        {
                            throw new IOException("The staged file changed before final commit.");
                        }

                        await target.FlushAsync(cancellationToken).ConfigureAwait(false);
                        if (target is FileStream fsTarget)
                        {
                            fsTarget.Flush(flushToDisk: true);
                        }
                        onStorageProgress?.Invoke(0);
                    }
                }

                // Independently re-read and verify destination .part file length and SHA-256 before publication
                RejectLink(temporaryPath);
                await using (Stream verifyDest = OpenRead(temporaryPath))
                {
                    if (verifyDest.CanSeek && verifyDest.Length != expectedSizeBytes)
                    {
                        throw new IOException("Destination copy verification failed: written temporary file length mismatch.");
                    }

                    using var destHasher = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
                    long destRead = 0;
                    while (true)
                    {
                        long readStart = Stopwatch.GetTimestamp();
                        int count = await verifyDest.ReadAsync(
                            buffer.AsMemory(0, VerificationReadBufferBytes),
                            cancellationToken).ConfigureAwait(false);
                        if (count <= 0)
                        {
                            break;
                        }

                        destRead = checked(destRead + count);
                        if (destRead > expectedSizeBytes)
                        {
                            throw new IOException("Destination copy verification failed: written temporary file exceeded expected length.");
                        }

                        destHasher.AppendData(buffer.AsSpan(0, count));
                        verifyTicks += Stopwatch.GetTimestamp() - readStart;
                        onStorageProgress?.Invoke(count);
                    }

                    long hashFinalStart = Stopwatch.GetTimestamp();
                    string destDigest = Convert.ToHexStringLower(destHasher.GetHashAndReset());
                    verifyTicks += Stopwatch.GetTimestamp() - hashFinalStart;

                    if (destRead != expectedSizeBytes ||
                        !string.Equals(destDigest, expectedDigest.Value, StringComparison.Ordinal))
                    {
                        throw new IOException("Destination copy verification failed: written temporary file SHA-256 mismatch.");
                    }
                }
            }
            catch (Win32Exception error) when (IsFatalStorageErrno(error.NativeErrorCode))
            {
                throw CreateStorageExceptionFromErrno(error.NativeErrorCode, "verified-copy", error);
            }
            finally
            {
                CryptographicOperations.ZeroMemory(buffer.AsSpan(0, VerificationReadBufferBytes));
                ArrayPool<byte>.Shared.Return(buffer);
            }

            finalPath = ResolveSafeDestinationPath(rawRelativePath);
            if (!string.Equals(
                    Path.GetDirectoryName(finalPath),
                    targetDirectory,
                    OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal))
            {
                throw new IOException("The destination directory changed before commit.");
            }

            for (int attempt = 0; ; attempt++)
            {
                try
                {
                    File.Move(temporaryPath, finalPath, overwrite: false);
                    break;
                }
                catch (IOException) when (attempt < 32 && File.Exists(finalPath) && File.Exists(temporaryPath))
                {
                    finalPath = ResolveCollision(Path.Combine(targetDirectory, baseFileName));
                }
            }

            if (_fileSystem != null)
            {
                _fileSystem.TryFlushDirectory(targetDirectory);
            }
            else if (OperatingSystem.IsLinux())
            {
                TryFlushLinuxDirectoryPath(targetDirectory);
            }

            TryNormalizeCommittedFileMetadata(finalPath);

            try { File.Delete(quarantinePath); }
            catch (IOException) { /* Verified public file is already committed. */ }
            catch (UnauthorizedAccessException) { /* Retry cleanup during maintenance. */ }

            long totalElapsedTicks = Stopwatch.GetTimestamp() - commitStart;
            long pureCommitTicks = Math.Max(0, totalElapsedTicks - verifyTicks);
            return new QuarantineCommitResult(
                finalPath,
                QuarantineCommitStrategy.VerifiedCopyFallback,
                verifyTicks,
                pureCommitTicks);
        }
        finally
        {
            try
            {
                if (File.Exists(temporaryPath)) File.Delete(temporaryPath);
            }
            catch
            {
                // Best-effort .part cleanup
            }
        }
    }

    private static void TryNormalizeCommittedFileMetadata(string committedPath)
    {
        try
        {
            FileAttributes attrs = File.GetAttributes(committedPath);
            if ((attrs & FileAttributes.Hidden) != 0)
            {
                File.SetAttributes(committedPath, attrs & ~FileAttributes.Hidden);
            }

            if (OperatingSystem.IsWindows())
            {
                var fileInfo = new FileInfo(committedPath);
                FileSecurity security = fileInfo.GetAccessControl();
                security.SetAccessRuleProtection(isProtected: false, preserveInheritance: true);
                fileInfo.SetAccessControl(security);
            }
        }
        catch
        {
            // Best-effort metadata normalization after atomic rename
        }
    }

    /// <summary>
    /// Best-effort restart maintenance. Only Glide's exact temporary filename
    /// is eligible, and recent files are left alone for another active process.
    /// Traversal is bounded so a large Downloads tree cannot stall receiving.
    /// </summary>
    public int PurgeAbandonedCommitFiles()
    {
        string root = Path.GetFullPath(_destinationRoot);
        if (!Directory.Exists(root)) return 0;
        RejectLinkedAncestors(root);

        DateTime cutoff = DateTime.UtcNow - AbandonedCommitAge;
        var pending = new Stack<(string Path, int Depth)>();
        pending.Push((root, 0));
        int examined = 0;
        int removed = 0;

        while (pending.Count > 0 && examined < MaxCommitCleanupEntries)
        {
            (string directory, int depth) = pending.Pop();
            try
            {
                foreach (string entry in Directory.EnumerateFileSystemEntries(directory))
                {
                    if (++examined > MaxCommitCleanupEntries) break;
                    try
                    {
                        FileAttributes attributes = File.GetAttributes(entry);
                        if ((attributes & FileAttributes.ReparsePoint) != 0) continue;
                        if ((attributes & FileAttributes.Directory) != 0)
                        {
                            if (string.Equals(Path.GetFileName(entry), SameVolumeStagingSubdirectoryName, StringComparison.OrdinalIgnoreCase))
                            {
                                continue;
                            }

                            if (depth < MaxCommitCleanupDepth)
                                pending.Push((entry, depth + 1));
                            continue;
                        }

                        if (!IsCommitTemporaryFile(Path.GetFileName(entry)) ||
                            File.GetLastWriteTimeUtc(entry) > cutoff)
                            continue;

                        File.Delete(entry);
                        removed++;
                    }
                    catch (IOException) { /* File changed or is in use. */ }
                    catch (UnauthorizedAccessException) { /* Skip inaccessible entry. */ }
                }
            }
            catch (IOException) { /* Directory changed during traversal. */ }
            catch (UnauthorizedAccessException) { /* Skip inaccessible directory. */ }
        }

        return removed;
    }

    /// <summary>
    /// Removes only old, protocol-shaped private session files that are not actively leased.
    /// A session with an active lease, or with recent, linked, nested, or unexpected contents, is left untouched.
    /// </summary>
    public int PurgeAbandonedQuarantineSessions()
    {
        string root = Path.GetFullPath(_stagingRoot);
        if (!Directory.Exists(root)) return 0;
        RejectLinkedAncestors(root);

        DateTime cutoff = DateTime.UtcNow - AbandonedQuarantineAge;
        int examined = 0;
        int removed = 0;
        foreach (string sessionDirectory in Directory.EnumerateDirectories(root))
        {
            if (++examined > MaxSessionCleanupEntries) break;
            if (!IdentifierValidation.IsValidUuidV4(Path.GetFileName(sessionDirectory)))
                continue;

            try
            {
                if ((File.GetAttributes(sessionDirectory) & FileAttributes.ReparsePoint) != 0)
                    continue;

                if (IsSessionActivelyLeased(_stagingRoot, sessionDirectory))
                    continue;

                var quarantineFiles = new List<string>();
                string? leaseFile = null;
                bool safe = true;
                foreach (string entry in Directory.EnumerateFileSystemEntries(sessionDirectory))
                {
                    if (++examined > MaxSessionCleanupEntries)
                    {
                        safe = false;
                        break;
                    }

                    FileAttributes attrs = File.GetAttributes(entry);
                    if ((attrs & (FileAttributes.Directory | FileAttributes.ReparsePoint)) != 0)
                    {
                        safe = false;
                        break;
                    }

                    string fileName = Path.GetFileName(entry);
                    if (string.Equals(fileName, SessionLeaseFileName, StringComparison.Ordinal))
                    {
                        leaseFile = entry;
                        continue;
                    }

                    if (!IsQuarantineFile(fileName) ||
                        File.GetLastWriteTimeUtc(entry) > cutoff)
                    {
                        safe = false;
                        break;
                    }
                    quarantineFiles.Add(entry);
                }

                if (!safe || quarantineFiles.Count == 0) continue;
                foreach (string file in quarantineFiles)
                {
                    File.Delete(file);
                    removed++;
                }
                if (leaseFile != null && File.Exists(leaseFile))
                {
                    File.Delete(leaseFile);
                }
                Directory.Delete(sessionDirectory, recursive: false);
            }
            catch (IOException) { /* An active or changed session is left alone. */ }
            catch (UnauthorizedAccessException) { /* Skip inaccessible session. */ }
        }
        return removed;
    }

    private static bool IsQuarantineFile(string name)
    {
        const string suffix = ".quarantine";
        return name.EndsWith(suffix, StringComparison.Ordinal) &&
            IdentifierValidation.IsValidUuidV4(name[..^suffix.Length]);
    }

    private static bool IsCommitTemporaryFile(string name)
    {
        const string prefix = ".glide-commit-";
        const string suffix = ".part";
        if (name.Length != prefix.Length + 32 + suffix.Length ||
            !name.StartsWith(prefix, StringComparison.Ordinal) ||
            !name.EndsWith(suffix, StringComparison.Ordinal))
            return false;
        foreach (char value in name.AsSpan(prefix.Length, 32))
            if (value is not (>= '0' and <= '9' or >= 'a' and <= 'f'))
                return false;
        return true;
    }

    private string ResolveSafeDestinationPath(string rawRelativePath)
    {
        string safeRelativePath = WindowsPathSanitizer.SanitizeRelativePath(rawRelativePath);
        string root = Path.GetFullPath(_destinationRoot);
        RejectLinkedAncestors(root);
        Directory.CreateDirectory(root);
        RejectLinkedAncestors(root);

        string[] segments = safeRelativePath.Split('/');
        string current = root;
        for (int i = 0; i < segments.Length - 1; i++)
        {
            current = Path.Combine(current, segments[i]);
            Directory.CreateDirectory(current);
            RejectLink(current);
        }

        return ResolveCollision(Path.Combine(current, segments[^1]));
    }

    private static void EnsurePrivateDirectory(string path, ref bool sameVolumeSecurityVerified)
    {
        RejectLinkedAncestors(path);
        if (OperatingSystem.IsWindows())
        {
            DirectoryInfo dirInfo = Directory.CreateDirectory(path);
            RejectLink(path);

            if (string.Equals(dirInfo.Name, SameVolumeStagingSubdirectoryName, StringComparison.OrdinalIgnoreCase))
            {
                try
                {
                    FileAttributes attrs = dirInfo.Attributes;
                    if ((attrs & FileAttributes.Hidden) == 0)
                    {
                        dirInfo.Attributes = attrs | FileAttributes.Hidden;
                    }
                }
                catch
                {
                    // Ignore attribute adjustment errors
                }
            }

            if (!TryConfigureAndVerifyWindowsDirectorySecurity(dirInfo))
            {
                sameVolumeSecurityVerified = false;
            }
        }
        else
        {
            Directory.CreateDirectory(path,
                UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
            RejectLink(path);
            File.SetUnixFileMode(path,
                UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);

            if (OperatingSystem.IsLinux() && !TryConfigureAndVerifyLinuxDirectorySecurity(path))
            {
                sameVolumeSecurityVerified = false;
            }
        }
        RejectLinkedAncestors(path);
    }

    [System.Runtime.Versioning.SupportedOSPlatform("windows")]
    private static bool TryConfigureAndVerifyWindowsDirectorySecurity(DirectoryInfo dirInfo)
    {
        try
        {
            using WindowsIdentity currentIdentity = WindowsIdentity.GetCurrent();
            SecurityIdentifier? userSid = currentIdentity.User;
            if (userSid == null)
            {
                return false;
            }

            var systemSid = new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null);
            var adminsSid = new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null);

            DirectorySecurity existingSecurity = dirInfo.GetAccessControl(AccessControlSections.Owner | AccessControlSections.Access);
            IdentityReference? owner = existingSecurity.GetOwner(typeof(SecurityIdentifier));
            if (owner is SecurityIdentifier ownerSid &&
                !ownerSid.Equals(userSid) &&
                !ownerSid.Equals(systemSid) &&
                !ownerSid.Equals(adminsSid))
            {
                throw new UnauthorizedAccessException($"Staging directory '{dirInfo.FullName}' has unexpected owner '{ownerSid}'.");
            }

            var restrictedSecurity = new DirectorySecurity();
            restrictedSecurity.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
            restrictedSecurity.AddAccessRule(new FileSystemAccessRule(
                userSid,
                FileSystemRights.FullControl,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
                PropagationFlags.None,
                AccessControlType.Allow));
            restrictedSecurity.AddAccessRule(new FileSystemAccessRule(
                systemSid,
                FileSystemRights.FullControl,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
                PropagationFlags.None,
                AccessControlType.Allow));

            dirInfo.SetAccessControl(restrictedSecurity);

            DirectorySecurity verifiedSecurity = dirInfo.GetAccessControl(AccessControlSections.Access | AccessControlSections.Owner);
            IdentityReference? verifiedOwner = verifiedSecurity.GetOwner(typeof(SecurityIdentifier));
            if (verifiedOwner is not SecurityIdentifier verifiedOwnerSid ||
                (!verifiedOwnerSid.Equals(userSid) &&
                 !verifiedOwnerSid.Equals(systemSid) &&
                 !verifiedOwnerSid.Equals(adminsSid)))
            {
                return false;
            }

            AuthorizationRuleCollection rules = verifiedSecurity.GetAccessRules(
                includeExplicit: true,
                includeInherited: true,
                typeof(SecurityIdentifier));

            bool hasCurrentUserAllow = false;
            foreach (FileSystemAccessRule rule in rules)
            {
                if (rule.AccessControlType != AccessControlType.Allow)
                {
                    continue;
                }

                if (rule.IdentityReference is not SecurityIdentifier ruleSid)
                {
                    return false;
                }

                if (ruleSid.Equals(userSid))
                {
                    hasCurrentUserAllow = true;
                }
                else if (!ruleSid.Equals(systemSid) && !ruleSid.Equals(adminsSid))
                {
                    return false;
                }
            }

            return hasCurrentUserAllow;
        }
        catch (UnauthorizedAccessException)
        {
            throw;
        }
        catch
        {
            return false;
        }
    }

    private static void RejectLinkedAncestors(string path)
    {
        string? current = Path.GetFullPath(path);
        while (current != null)
        {
            try
            {
                if ((File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0)
                    throw new IOException("A transfer path contains a symbolic link or reparse point.");
            }
            catch (FileNotFoundException) { /* Parent not created yet. */ }
            catch (DirectoryNotFoundException) { /* Parent not created yet. */ }
            string? parent = Path.GetDirectoryName(current);
            if (parent == current) break;
            current = parent;
        }
    }

    private static void RejectLink(string path)
    {
        if ((File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0)
            throw new IOException("A transfer path contains a symbolic link or reparse point.");
    }

    public static string ResolveCollision(string targetPath)
    {
        if (!File.Exists(targetPath))
        {
            return targetPath;
        }

        string directory = Path.GetDirectoryName(targetPath) ?? string.Empty;
        string fileNameWithoutExt = Path.GetFileNameWithoutExtension(targetPath);
        string extension = Path.GetExtension(targetPath);

        int counter = 1;
        while (true)
        {
            string candidate = Path.Combine(directory, $"{fileNameWithoutExt} ({counter}){extension}");
            if (!File.Exists(candidate))
            {
                return candidate;
            }
            counter++;
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct FileTime
    {
        public uint LowDateTime;
        public uint HighDateTime;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ByHandleFileInformation
    {
        public uint FileAttributes;
        public FileTime CreationTime;
        public FileTime LastAccessTime;
        public FileTime LastWriteTime;
        public uint VolumeSerialNumber;
        public uint FileSizeHigh;
        public uint FileSizeLow;
        public uint NumberOfLinks;
        public uint FileIndexHigh;
        public uint FileIndexLow;
    }

    private const uint FileReadAttributes = 0x0080;
    private const uint FileReadData = 0x0001;
    private const uint FileWriteData = 0x0002;
    private const uint FileTraverse = 0x0020;
    private const uint DeleteAccess = 0x00010000;
    private const uint FileShareReadWriteDelete = 0x00000001 | 0x00000002 | 0x00000004;
    private const uint OpenExisting = 3;
    private const uint FileFlagBackupSemantics = 0x02000000;
    private const uint FileFlagOpenReparsePoint = 0x00200000;
    private const uint FileFlagSequentialScan = 0x08000000;
    private const int FileRenameInformation = 10;

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetFileInformationByHandle(
        SafeFileHandle hFile,
        out ByHandleFileInformation lpFileInformation);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern SafeFileHandle CreateFileW(
        string lpFileName,
        uint dwDesiredAccess,
        uint dwShareMode,
        IntPtr lpSecurityAttributes,
        uint dwCreationDisposition,
        uint dwFlagsAndAttributes,
        IntPtr hTemplateFile);

    [DllImport("ntdll.dll", ExactSpelling = true)]
    private static extern int NtSetInformationFile(
        SafeFileHandle hFile,
        out IoStatusBlock ioStatusBlock,
        IntPtr fileInformation,
        uint bufferSize,
        int fileInformationClass);

    [DllImport("ntdll.dll", ExactSpelling = true)]
    private static extern uint RtlNtStatusToDosError(int status);

    [StructLayout(LayoutKind.Sequential)]
    private struct IoStatusBlock
    {
        public IntPtr Status;
        public IntPtr Information;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct FileRenameInfoHeader
    {
        public uint Flags;
        public IntPtr RootDirectory;
        public uint FileNameLength;
        public char FileName;
    }

    private static FileStream OpenExclusiveVerificationStream(string path)
    {
        if (!OperatingSystem.IsWindows())
        {
            return new FileStream(path, FileMode.Open, FileAccess.ReadWrite,
                FileShare.None, VerificationReadBufferBytes,
                FileOptions.Asynchronous | FileOptions.SequentialScan);
        }

        SafeFileHandle handle = CreateFileW(
            path,
            FileReadData | FileWriteData | FileReadAttributes | DeleteAccess,
            0,
            IntPtr.Zero,
            OpenExisting,
            FileFlagOpenReparsePoint | FileFlagSequentialScan,
            IntPtr.Zero);
        if (handle.IsInvalid)
        {
            int error = Marshal.GetLastWin32Error();
            handle.Dispose();
            throw new IOException("Cannot exclusively open the staged file for safe publication.",
                new Win32Exception(error));
        }

        try
        {
            return new FileStream(handle, FileAccess.ReadWrite,
                VerificationReadBufferBytes, isAsync: false);
        }
        catch
        {
            handle.Dispose();
            throw;
        }
    }

    private static void RenameVerifiedWindowsFile(
        SafeFileHandle sourceHandle,
        SafeFileHandle targetDirectoryHandle,
        string fileName)
    {
        if (string.IsNullOrWhiteSpace(fileName) ||
            fileName.IndexOfAny(['/', '\\', '\0']) >= 0 ||
            fileName is "." or "..")
        {
            throw new IOException("Invalid committed filename.");
        }

        byte[] nameBytes = Encoding.Unicode.GetBytes(fileName);
        int nameOffset = Marshal.OffsetOf<FileRenameInfoHeader>(
            nameof(FileRenameInfoHeader.FileName)).ToInt32();
        int bufferSize = checked(nameOffset + nameBytes.Length);
        IntPtr info = Marshal.AllocHGlobal(bufferSize);
        try
        {
            Marshal.WriteInt32(info, 0, 0); // ReplaceIfExists = false
            Marshal.WriteIntPtr(info,
                Marshal.OffsetOf<FileRenameInfoHeader>(
                    nameof(FileRenameInfoHeader.RootDirectory)).ToInt32(),
                targetDirectoryHandle.DangerousGetHandle());
            Marshal.WriteInt32(info,
                Marshal.OffsetOf<FileRenameInfoHeader>(
                    nameof(FileRenameInfoHeader.FileNameLength)).ToInt32(),
                nameBytes.Length);
            Marshal.Copy(nameBytes, 0, IntPtr.Add(info, nameOffset), nameBytes.Length);

            int status = NtSetInformationFile(
                sourceHandle, out _, info, (uint)bufferSize,
                FileRenameInformation);
            if (status < 0)
            {
                throw new Win32Exception((int)RtlNtStatusToDosError(status));
            }
        }
        finally
        {
            Marshal.FreeHGlobal(info);
        }
    }

    private static bool TryGetWindowsHandleInfo(SafeFileHandle handle, out ByHandleFileInformation info)
    {
        info = default;
        if (!OperatingSystem.IsWindows() || handle.IsInvalid || handle.IsClosed)
        {
            return false;
        }

        return GetFileInformationByHandle(handle, out info);
    }

    private static SafeFileHandle? OpenValidatedWindowsDirectoryHandle(string directoryPath, out ByHandleFileInformation info)
    {
        info = default;
        if (!OperatingSystem.IsWindows())
        {
            return null;
        }

        SafeFileHandle handle = CreateFileW(
            directoryPath,
            FileReadAttributes | FileTraverse,
            FileShareReadWriteDelete,
            IntPtr.Zero,
            OpenExisting,
            FileFlagBackupSemantics | FileFlagOpenReparsePoint,
            IntPtr.Zero);

        if (handle.IsInvalid)
        {
            handle.Dispose();
            return null;
        }

        if (!GetFileInformationByHandle(handle, out info))
        {
            handle.Dispose();
            return null;
        }

        return handle;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct LinuxStatxTimestamp
    {
        public long TvSec;
        public uint TvNsec;
        public int Pad;
    }

    [StructLayout(LayoutKind.Sequential, Size = 256)]
    private struct LinuxStatx
    {
        public uint StxMask;
        public uint StxBlksize;
        public ulong StxAttributes;
        public uint StxNlink;
        public uint StxUid;
        public uint StxGid;
        public ushort StxMode;
        public ushort Spare0;
        public ulong StxIno;
        public ulong StxSize;
        public ulong StxBlocks;
        public ulong StxAttributesMask;
        public LinuxStatxTimestamp StxAtime;
        public LinuxStatxTimestamp StxBtime;
        public LinuxStatxTimestamp StxCtime;
        public LinuxStatxTimestamp StxMtime;
        public uint StxRdevMajor;
        public uint StxRdevMinor;
        public uint StxDevMajor;
        public uint StxDevMinor;
        public ulong StxMntId;
        public uint StxDioMemAlign;
        public uint StxDioOffsetAlign;
        public ulong Spare3_0, Spare3_1, Spare3_2, Spare3_3;
        public ulong Spare3_4, Spare3_5, Spare3_6, Spare3_7;
        public ulong Spare3_8, Spare3_9, Spare3_10, Spare3_11;
    }

    private const int LinuxAtFdcwd = -100;
    private const int LinuxAtSymlinkNofollow = 0x100;
    private const int LinuxAtEmptyPath = 0x1000;
    private const uint LinuxStatxBasicStats = 0x000007ffU;
    private const int LinuxRenameNoreplace = 1;
    private const int LinuxORdOnly = 0;
    private static int LinuxODirectory => RuntimeInformation.ProcessArchitecture switch
    {
        Architecture.X86 or Architecture.X64 => 0x10000,
        _ => 0x4000
    };
    private static int LinuxONofollow => RuntimeInformation.ProcessArchitecture switch
    {
        Architecture.X86 or Architecture.X64 => 0x20000,
        _ => 0x8000
    };
    private const int LinuxOCloexec = 0x80000;
    private const int LinuxSIfmt = 0xF000;
    private const int LinuxSIfdir = 0x4000;
    private const int LinuxSIfreg = 0x8000;
    private const int LinuxS_Iflnk = 0xA000;

    [DllImport("libc", EntryPoint = "geteuid", SetLastError = true)]
    private static extern uint LinuxGeteuid();

    [DllImport("libc", EntryPoint = "open", SetLastError = true)]
    private static extern int LinuxOpen(
        [MarshalAs(UnmanagedType.LPUTF8Str)] string pathname,
        int flags,
        int mode = 0);

    [DllImport("libc", EntryPoint = "statx", SetLastError = true)]
    private static extern int LinuxStatxCall(
        int dirfd,
        [MarshalAs(UnmanagedType.LPUTF8Str)] string pathname,
        int flags,
        uint mask,
        out LinuxStatx statxbuf);

    [DllImport("libc", EntryPoint = "renameat2", SetLastError = true)]
    private static extern int LinuxRenameat2(
        int olddirfd,
        [MarshalAs(UnmanagedType.LPUTF8Str)] string oldpath,
        int newdirfd,
        [MarshalAs(UnmanagedType.LPUTF8Str)] string newpath,
        uint flags);

    [DllImport("libc", EntryPoint = "fsync", SetLastError = true)]
    private static extern int LinuxFsync(int fd);

    private static bool TryGetLinuxFileIdentityByFd(int fd, out LinuxStatx statx) =>
        TryGetLinuxFileIdentityByFd(fd, out statx, out _);

    private static bool TryGetLinuxFileIdentityByFd(int fd, out LinuxStatx statx, out int errno)
    {
        statx = default;
        errno = 0;
        if (!OperatingSystem.IsLinux() || fd < 0)
        {
            return false;
        }

        if (LinuxStatxCall(fd, string.Empty, LinuxAtEmptyPath, LinuxStatxBasicStats, out statx) == 0)
        {
            return true;
        }

        errno = Marshal.GetLastWin32Error();
        return false;
    }

    private static bool TryGetLinuxFileIdentityByPath(string path, out LinuxStatx statx) =>
        TryGetLinuxFileIdentityByPath(path, out statx, out _);

    private static bool TryGetLinuxFileIdentityByPath(string path, out LinuxStatx statx, out int errno)
    {
        statx = default;
        errno = 0;
        if (!OperatingSystem.IsLinux() || string.IsNullOrWhiteSpace(path))
        {
            return false;
        }

        if (LinuxStatxCall(LinuxAtFdcwd, path, LinuxAtSymlinkNofollow, LinuxStatxBasicStats, out statx) == 0)
        {
            return true;
        }

        errno = Marshal.GetLastWin32Error();
        return false;
    }

    private static void TryFlushLinuxDirectory(SafeFileHandle? dirHandle)
    {
        if (!OperatingSystem.IsLinux() || dirHandle == null || dirHandle.IsInvalid || dirHandle.IsClosed)
        {
            return;
        }

        try
        {
            _ = LinuxFsync(dirHandle.DangerousGetHandle().ToInt32());
        }
        catch
        {
            // Ignore directory fsync errors on filesystems that do not support it
        }
    }

    private static void TryFlushLinuxDirectoryPath(string directoryPath)
    {
        if (!OperatingSystem.IsLinux() || string.IsNullOrWhiteSpace(directoryPath))
        {
            return;
        }

        try
        {
            using SafeFileHandle? handle = OpenValidatedLinuxDirectoryHandle(directoryPath, out _);
            TryFlushLinuxDirectory(handle);
        }
        catch
        {
            // Best-effort directory flush
        }
    }

    private static SafeFileHandle? OpenValidatedLinuxDirectoryHandle(string directoryPath, out LinuxStatx dirStat)
    {
        dirStat = default;
        if (!OperatingSystem.IsLinux() || string.IsNullOrWhiteSpace(directoryPath))
        {
            return null;
        }

        int fd = LinuxOpen(directoryPath, LinuxORdOnly | LinuxODirectory | LinuxONofollow | LinuxOCloexec);
        if (fd < 0)
        {
            return null;
        }

        var handle = new SafeFileHandle((IntPtr)fd, ownsHandle: true);
        if (!TryGetLinuxFileIdentityByFd(fd, out dirStat))
        {
            handle.Dispose();
            return null;
        }

        if ((dirStat.StxMode & LinuxSIfmt) != LinuxSIfdir ||
            dirStat.StxUid != LinuxGeteuid())
        {
            handle.Dispose();
            return null;
        }

        return handle;
    }

    private static void RenameVerifiedLinuxFile(
        SafeFileHandle stagingDirectoryHandle,
        string sourceFileName,
        SafeFileHandle targetDirectoryHandle,
        string destinationFileName)
    {
        if (string.IsNullOrWhiteSpace(sourceFileName) ||
            sourceFileName.IndexOfAny(['/', '\0']) >= 0 ||
            sourceFileName is "." or "..")
        {
            throw new IOException("Invalid quarantine filename.");
        }

        if (string.IsNullOrWhiteSpace(destinationFileName) ||
            destinationFileName.IndexOfAny(['/', '\0']) >= 0 ||
            destinationFileName is "." or "..")
        {
            throw new IOException("Invalid committed filename.");
        }

        int oldDirFd = stagingDirectoryHandle.DangerousGetHandle().ToInt32();
        int newDirFd = targetDirectoryHandle.DangerousGetHandle().ToInt32();

        int res = LinuxRenameat2(
            oldDirFd,
            sourceFileName,
            newDirFd,
            destinationFileName,
            (uint)LinuxRenameNoreplace);

        if (res != 0)
        {
            int errno = Marshal.GetLastWin32Error();
            throw new Win32Exception(errno, $"Linux renameat2 failed with errno {errno}");
        }
    }

    private static bool TryConfigureAndVerifyLinuxDirectorySecurity(string path)
    {
        if (!OperatingSystem.IsLinux())
        {
            return false;
        }

        try
        {
            if (!TryGetLinuxFileIdentityByPath(path, out LinuxStatx stat))
            {
                return false;
            }

            uint euid = LinuxGeteuid();
            if (stat.StxUid != euid)
            {
                return false;
            }

            if ((stat.StxMode & LinuxSIfmt) != LinuxSIfdir)
            {
                return false;
            }

            return (stat.StxMode & 0x003F) == 0;
        }
        catch
        {
            return false;
        }
    }

    private static bool IsEligibleLinuxSameVolumeDestination(string path, out string? stagingCandidate)
    {
        stagingCandidate = null;
        if (!OperatingSystem.IsLinux() || string.IsNullOrWhiteSpace(path))
        {
            return false;
        }

        try
        {
            string fullPath = Path.GetFullPath(path);
            if (!Directory.Exists(fullPath))
            {
                return false;
            }

            RejectLinkedAncestors(fullPath);

            using SafeFileHandle? destHandle = OpenValidatedLinuxDirectoryHandle(fullPath, out LinuxStatx destStat);
            if (destHandle == null || destHandle.IsInvalid)
            {
                return false;
            }

            uint euid = LinuxGeteuid();
            if (destStat.StxUid != euid)
            {
                return false;
            }

            // Reject world-writable directories without sticky bit
            if ((destStat.StxMode & 0x0002) != 0 && (destStat.StxMode & 0x0200) == 0)
            {
                return false;
            }

            string candidate = Path.Combine(fullPath, SameVolumeStagingSubdirectoryName);
            bool stagingVerified = true;
            EnsurePrivateDirectory(candidate, ref stagingVerified);
            if (!stagingVerified)
            {
                return false;
            }

            if (!TryGetLinuxFileIdentityByPath(candidate, out LinuxStatx stagingStat))
            {
                return false;
            }

            if (stagingStat.StxDevMajor != destStat.StxDevMajor ||
                stagingStat.StxDevMinor != destStat.StxDevMinor ||
                stagingStat.StxUid != euid ||
                (stagingStat.StxMode & LinuxSIfmt) != LinuxSIfdir ||
                (stagingStat.StxMode & 0x003F) != 0)
            {
                return false;
            }

            stagingCandidate = candidate;
            return true;
        }
        catch
        {
            return false;
        }
    }
}
