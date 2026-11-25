using System.Buffers;
using System.Collections.Concurrent;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using Glide.Core.Models;
using Microsoft.Win32.SafeHandles;

namespace Glide.Core.Storage;

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

    private const string SameVolumeStagingSubdirectoryName = QuarantineLeaseManager.SameVolumeStagingSubdirectoryName;

    private readonly record struct PreVerifiedItemRecord(
        string DigestHex,
        long SizeBytes);

    private readonly string _stagingRoot;
    private readonly string _destinationRoot;
    private readonly Func<long>? _freeSpaceProvider;
    private readonly Func<long>? _destinationFreeSpaceProvider;
    private readonly bool _enableSameVolumeStaging;
    private readonly IStagingFileSystem? _fileSystem;
    private readonly QuarantineLeaseManager _leaseManager;
    private readonly AtomicStagingPublisher _publisher;
    private readonly IResumeStateStore _resumeStateStore;
    private readonly ConcurrentDictionary<string, PreVerifiedItemRecord> _preVerifiedItems = new(StringComparer.Ordinal);
    private bool _sameVolumeSecurityVerified = true;
    private int _commitCleanupAttempted;

    public string StagingRoot => _stagingRoot;
    public string DestinationRoot => _destinationRoot;
    public bool EnableSameVolumeStaging => _enableSameVolumeStaging;
    public QuarantineLeaseManager LeaseManager => _leaseManager;
    public AtomicStagingPublisher Publisher => _publisher;
    public IResumeStateStore ResumeStateStore => _resumeStateStore;
    public QuarantineCommitStrategy LastCommitStrategy => _publisher.LastCommitStrategy;

    public QuarantineStagingStore(
        string? stagingRoot = null,
        string? destinationRoot = null,
        Func<long>? freeSpaceProvider = null,
        Func<long>? destinationFreeSpaceProvider = null,
        bool enableSameVolumeStaging = true,
        IStagingFileSystem? fileSystem = null,
        IResumeStateStore? resumeStateStore = null)
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
        _leaseManager = new QuarantineLeaseManager(_stagingRoot, _destinationRoot);
        _publisher = new AtomicStagingPublisher(this, _fileSystem);
        _resumeStateStore = resumeStateStore ?? NullResumeStateStore.Instance;
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

    public static string GetDefaultStagingRoot()
    {
        if (OperatingSystem.IsLinux())
        {
            string? xdgData = Environment.GetEnvironmentVariable("XDG_DATA_HOME");
            string baseDir = !string.IsNullOrWhiteSpace(xdgData)
                ? xdgData
                : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".local", "share");

            string preferred = Path.Combine(baseDir, "glide", "staging");
            string legacy = Path.Combine(baseDir, "Glide", "staging");
            if (!Directory.Exists(preferred) && Directory.Exists(legacy))
            {
                return legacy;
            }
            return preferred;
        }

        return Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Glide", "staging");
    }

    public IDisposable AcquireSessionLease(SessionId sessionId) =>
        _leaseManager.AcquireSessionLease(sessionId);

    public void RecordVerifiedQuarantineDigest(
        SessionId sessionId,
        ItemId itemId,
        Sha256Digest verifiedDigest,
        long verifiedSizeBytes)
    {
        string key = GetItemKey(sessionId, itemId);
        _preVerifiedItems[key] = new PreVerifiedItemRecord(verifiedDigest.Value, verifiedSizeBytes);
    }

    private bool TryConsumePreVerifiedItem(
        SessionId sessionId,
        ItemId itemId,
        Sha256Digest expectedDigest,
        long expectedSizeBytes)
    {
        string key = GetItemKey(sessionId, itemId);
        if (_preVerifiedItems.TryRemove(key, out PreVerifiedItemRecord record))
        {
            return record.SizeBytes == expectedSizeBytes &&
                   string.Equals(record.DigestHex, expectedDigest.Value, StringComparison.OrdinalIgnoreCase);
        }
        return false;
    }

    private static string GetItemKey(SessionId sessionId, ItemId itemId) =>
        $"{sessionId.Value}::{itemId.Value}";

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
            if (TryGetLinuxFileIdentityByPath(_stagingRoot, out LinuxStatx stagingStat, out _) &&
                TryGetLinuxFileIdentityByPath(_destinationRoot, out LinuxStatx destStat, out _))
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

    internal static bool AreOnSameVolume(string pathA, string pathB)
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
            if (File.Exists(probe)) File.Delete(probe);
        }
    }

    public string GetQuarantinePath(SessionId sessionId, ItemId itemId)
    {
        return Path.Combine(_stagingRoot, sessionId.Value, $"{itemId.Value}.quarantine");
    }

    public string PrepareResumeQuarantinePath(SessionId sessionId, ItemId itemId)
    {
        EnsurePrivateDirectory(_stagingRoot, ref _sameVolumeSecurityVerified);
        EnsurePrivateDirectory(Path.Combine(_stagingRoot, sessionId.Value), ref _sameVolumeSecurityVerified);
        return GetQuarantinePath(sessionId, itemId);
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

        _preVerifiedItems.TryRemove(GetItemKey(sessionId, itemId), out _);
        _leaseManager.AcquireSessionLeaseReference(sessionId);

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
            _leaseManager.ReleaseSessionLeaseReference(sessionId);
            throw;
        }
    }

    private sealed class LeasedQuarantineStream(Stream inner, string stagingRoot, SessionId sessionId) : Stream
    {
        private int _leaseReleased;

        public override bool CanRead => inner.CanRead;
        public override bool CanSeek => inner.CanSeek;
        public override bool CanWrite => inner.CanWrite;
        public override long Length => inner.Length;
        public override long Position
        {
            get => inner.Position;
            set => inner.Position = value;
        }

        public override void Flush() => inner.Flush();
        public override Task FlushAsync(CancellationToken cancellationToken) =>
            inner.FlushAsync(cancellationToken);
        public override int Read(byte[] buffer, int offset, int count) =>
            inner.Read(buffer, offset, count);
        public override ValueTask<int> ReadAsync(
            Memory<byte> buffer, CancellationToken cancellationToken = default) =>
            inner.ReadAsync(buffer, cancellationToken);
        public override long Seek(long offset, SeekOrigin origin) =>
            inner.Seek(offset, origin);
        public override void SetLength(long value) => inner.SetLength(value);
        public override void Write(byte[] buffer, int offset, int count) =>
            inner.Write(buffer, offset, count);
        public override ValueTask WriteAsync(
            ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default) =>
            inner.WriteAsync(buffer, cancellationToken);

        protected override void Dispose(bool disposing)
        {
            try
            {
                if (disposing) inner.Dispose();
            }
            finally
            {
                if (Interlocked.Exchange(ref _leaseReleased, 1) == 0)
                {
                    QuarantineLeaseManager.ReleaseSessionLease(stagingRoot, sessionId);
                }
            }
        }

        public override async ValueTask DisposeAsync()
        {
            try
            {
                await inner.DisposeAsync().ConfigureAwait(false);
            }
            finally
            {
                if (Interlocked.Exchange(ref _leaseReleased, 1) == 0)
                {
                    QuarantineLeaseManager.ReleaseSessionLease(stagingRoot, sessionId);
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

            RecordVerifiedQuarantineDigest(sessionId, itemId, actualDigest, expectedSizeBytes);

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
        _preVerifiedItems.TryRemove(GetItemKey(sessionId, itemId), out _);
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
            _leaseManager.DeleteSessionLeaseFile(sessionId);
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

    public Task<QuarantineCommitResult> CommitVerifiedQuarantineFileWithMetricsAsync(
        SessionId sessionId,
        ItemId itemId,
        string rawRelativePath,
        Sha256Digest expectedDigest,
        long expectedSizeBytes,
        CancellationToken cancellationToken = default,
        Action? onBeforePublish = null,
        Action<long>? onStorageProgress = null,
        TimeSpan? storageProgressTimeout = null,
        bool preVerifiedByExclusiveStreamingHandle = false,
        Func<string, CancellationToken, Task>? onPublishCandidate = null)
    {
        bool skipRedundantRead = preVerifiedByExclusiveStreamingHandle ||
                                 TryConsumePreVerifiedItem(sessionId, itemId, expectedDigest, expectedSizeBytes);

        return _publisher.CommitVerifiedQuarantineFileWithMetricsAsync(
            sessionId,
            itemId,
            rawRelativePath,
            expectedDigest,
            expectedSizeBytes,
            skipRedundantVerificationRead: skipRedundantRead,
            cancellationToken: cancellationToken,
            onBeforePublish: onBeforePublish,
            onStorageProgress: onStorageProgress,
            storageProgressTimeout: storageProgressTimeout,
            onPublishCandidate: onPublishCandidate);
    }

    public int PurgeAbandonedCommitFiles() =>
        _leaseManager.PurgeAbandonedCommitFiles();

    public int PurgeAbandonedQuarantineSessions() =>
        _leaseManager.PurgeAbandonedQuarantineSessions();

    public static string ResolveCollision(string targetPath) =>
        AtomicStagingPublisher.ResolveCollision(targetPath);

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

    internal static void RejectLinkedAncestors(string path)
    {
        string? current = Path.GetFullPath(path);
        while (current != null)
        {
            try
            {
                if ((File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0)
                    throw new IOException("A transfer path contains a symbolic link or reparse point.");
            }
            catch (FileNotFoundException) { }
            catch (DirectoryNotFoundException) { }
            string? parent = Path.GetDirectoryName(current);
            if (parent == current) break;
            current = parent;
        }
    }

    internal static void RejectLink(string path)
    {
        if ((File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0)
            throw new IOException("A transfer path contains a symbolic link or reparse point.");
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct FileTime
    {
        public uint LowDateTime;
        public uint HighDateTime;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct ByHandleFileInformation
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
    private const uint SynchronizeAccess = 0x00100000;
    private const uint FileShareReadWriteDelete = 0x00000001 | 0x00000002 | 0x00000004;
    private const uint OpenExisting = 3;
    private const uint FileFlagBackupSemantics = 0x02000000;
    private const uint FileFlagOpenReparsePoint = 0x00200000;
    private const uint FileFlagSequentialScan = 0x08000000;
    private const uint FileFlagOverlapped = 0x40000000;
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

    internal static SafeFileHandle CreateWindowsVerificationHandle(string path)
    {
        SafeFileHandle handle = CreateFileW(
            path,
            FileReadData | FileWriteData | FileReadAttributes | DeleteAccess | SynchronizeAccess,
            0,
            IntPtr.Zero,
            OpenExisting,
            FileFlagOpenReparsePoint | FileFlagSequentialScan | FileFlagOverlapped,
            IntPtr.Zero);
        if (handle.IsInvalid)
        {
            int error = Marshal.GetLastWin32Error();
            handle.Dispose();
            throw new IOException("Cannot exclusively open the staged file for safe publication.",
                new Win32Exception(error));
        }

        return handle;
    }

    internal static void RenameVerifiedWindowsFile(
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

    internal static bool TryGetWindowsHandleInfo(SafeFileHandle handle, out ByHandleFileInformation info)
    {
        info = default;
        if (!OperatingSystem.IsWindows() || handle.IsInvalid || handle.IsClosed)
        {
            return false;
        }

        return GetFileInformationByHandle(handle, out info);
    }

    internal static SafeFileHandle? OpenValidatedWindowsDirectoryHandle(string directoryPath, out ByHandleFileInformation info)
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
    internal struct LinuxStatxTimestamp
    {
        public long TvSec;
        public uint TvNsec;
        public int Pad;
    }

    [StructLayout(LayoutKind.Sequential, Size = 256)]
    internal struct LinuxStatx
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
    internal const int LinuxSIfmt = 0xF000;
    internal const int LinuxSIfdir = 0x4000;
    internal const int LinuxSIfreg = 0x8000;
    internal const int LinuxS_Iflnk = 0xA000;

    [DllImport("libc", EntryPoint = "geteuid", SetLastError = true)]
    internal static extern uint LinuxGeteuid();

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

    internal static bool TryGetLinuxFileIdentityByFd(int fd, out LinuxStatx statx) =>
        TryGetLinuxFileIdentityByFd(fd, out statx, out _);

    internal static bool TryGetLinuxFileIdentityByFd(int fd, out LinuxStatx statx, out int errno)
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

    internal static bool TryGetLinuxFileIdentityByPath(string path, out LinuxStatx statx) =>
        TryGetLinuxFileIdentityByPath(path, out statx, out _);

    internal static bool TryGetLinuxFileIdentityByPath(string path, out LinuxStatx statx, out int errno)
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

    internal static void TryFlushLinuxDirectory(SafeFileHandle? dirHandle)
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

    internal static void TryFlushLinuxDirectoryPath(string directoryPath)
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

    internal static SafeFileHandle? OpenValidatedLinuxDirectoryHandle(string directoryPath, out LinuxStatx dirStat)
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

    internal static void RenameVerifiedLinuxFile(
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
