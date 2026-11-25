using System.Buffers;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Text;
using Glide.Core.Models;
using Microsoft.Win32.SafeHandles;

namespace Glide.Core.Storage;

/// <summary>
/// Performs verified, no-replace atomic publication from private quarantine staging into
/// the user's destination directory, with cross-volume verified-copy fallback and progress watchdog.
/// </summary>
public sealed class AtomicStagingPublisher
{
    public const int VerificationReadBufferBytes = 262_144; // 256 KiB sequential verification read buffer

    private readonly QuarantineStagingStore _store;
    private readonly IStagingFileSystem? _fileSystem;

    public QuarantineCommitStrategy LastCommitStrategy { get; private set; } =
        QuarantineCommitStrategy.SameVolumeAtomicRename;

    public AtomicStagingPublisher(QuarantineStagingStore store, IStagingFileSystem? fileSystem)
    {
        _store = store ?? throw new ArgumentNullException(nameof(store));
        _fileSystem = fileSystem;
    }

    public async Task<QuarantineCommitResult> CommitVerifiedQuarantineFileWithMetricsAsync(
        SessionId sessionId,
        ItemId itemId,
        string rawRelativePath,
        Sha256Digest expectedDigest,
        long expectedSizeBytes,
        bool skipRedundantVerificationRead,
        CancellationToken cancellationToken = default,
        Action? onBeforePublish = null,
        Action<long>? onStorageProgress = null,
        TimeSpan? storageProgressTimeout = null,
        Func<string, CancellationToken, Task>? onPublishCandidate = null)
    {
        string quarantinePath = _store.GetQuarantinePath(sessionId, itemId);
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
            if (_store.CanUseSameVolumeAtomicCommit())
            {
                QuarantineCommitResult? atomicResult = await TryCommitSameVolumeAtomicAsync(
                    quarantinePath,
                    rawRelativePath,
                    expectedDigest,
                    expectedSizeBytes,
                    skipRedundantVerificationRead,
                    onBeforePublish,
                    ReportProgress,
                    onPublishCandidate,
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
                skipRedundantVerificationRead,
                ReportProgress,
                onPublishCandidate,
                effectiveToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (watchdogCts != null && watchdogCts.IsCancellationRequested && !cancellationToken.IsCancellationRequested)
        {
            throw new TimeoutException(
                "Local storage verification or publication made no progress within the 60-second timeout.");
        }
    }

    private async Task<QuarantineCommitResult?> TryCommitSameVolumeAtomicAsync(
        string quarantinePath,
        string rawRelativePath,
        Sha256Digest expectedDigest,
        long expectedSizeBytes,
        bool skipRedundantVerificationRead,
        Action? onBeforePublish,
        Action<long>? onStorageProgress,
        Func<string, CancellationToken, Task>? onPublishCandidate,
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
                skipRedundantVerificationRead,
                onBeforePublish,
                onStorageProgress,
                onPublishCandidate,
                cancellationToken).ConfigureAwait(false);
        }

        if (OperatingSystem.IsWindows())
        {
            if (!QuarantineStagingStore.AreOnSameVolume(quarantinePath, targetDirectory))
            {
                return null;
            }
        }
        else if (OperatingSystem.IsLinux())
        {
            if (!QuarantineStagingStore.TryGetLinuxFileIdentityByPath(quarantinePath, out QuarantineStagingStore.LinuxStatx qStat, out int qErr))
            {
                if (QuarantineStagingStore.IsFatalStorageErrno(qErr))
                {
                    throw QuarantineStagingStore.CreateStorageExceptionFromErrno(qErr, "statx");
                }
                return null;
            }

            if ((qStat.StxMode & QuarantineStagingStore.LinuxSIfmt) == QuarantineStagingStore.LinuxS_Iflnk ||
                (qStat.StxMode & QuarantineStagingStore.LinuxSIfmt) != QuarantineStagingStore.LinuxSIfreg ||
                qStat.StxUid != QuarantineStagingStore.LinuxGeteuid())
            {
                throw new IOException("The quarantine file is not a valid user-owned regular file.");
            }

            if (!QuarantineStagingStore.TryGetLinuxFileIdentityByPath(targetDirectory, out QuarantineStagingStore.LinuxStatx tStat, out int tErr))
            {
                if (QuarantineStagingStore.IsFatalStorageErrno(tErr))
                {
                    throw QuarantineStagingStore.CreateStorageExceptionFromErrno(tErr, "statx");
                }
                return null;
            }

            if ((tStat.StxMode & QuarantineStagingStore.LinuxSIfmt) == QuarantineStagingStore.LinuxS_Iflnk ||
                (tStat.StxMode & QuarantineStagingStore.LinuxSIfmt) != QuarantineStagingStore.LinuxSIfdir)
            {
                throw new IOException("The destination directory is a symbolic link or invalid directory.");
            }

            if (qStat.StxDevMajor != tStat.StxDevMajor ||
                qStat.StxDevMinor != tStat.StxDevMinor)
            {
                return null;
            }
        }
        else if (!QuarantineStagingStore.AreOnSameVolume(quarantinePath, targetDirectory))
        {
            return null;
        }

        long verifyStart = Stopwatch.GetTimestamp();
        long verifyTicks;
        long commitTicks;
        string committedPath = initialFinalPath;
        QuarantineStagingStore.ByHandleFileInformation sourceHandleInfo = default;
        bool hasWindowsHandleInfo = false;
        QuarantineStagingStore.LinuxStatx sourceLinuxStatx = default;
        bool hasLinuxHandleInfo = false;

        await using (var source = OpenExclusiveVerificationStream(quarantinePath))
        {
            if (OperatingSystem.IsWindows())
            {
                if (!QuarantineStagingStore.TryGetWindowsHandleInfo(source.SafeFileHandle, out sourceHandleInfo))
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
                if (!QuarantineStagingStore.TryGetLinuxFileIdentityByFd(source.SafeFileHandle.DangerousGetHandle().ToInt32(), out sourceLinuxStatx, out int fdErr))
                {
                    if (QuarantineStagingStore.IsFatalStorageErrno(fdErr))
                    {
                        throw QuarantineStagingStore.CreateStorageExceptionFromErrno(fdErr, "statx");
                    }
                    return null;
                }

                if ((sourceLinuxStatx.StxMode & QuarantineStagingStore.LinuxSIfmt) != QuarantineStagingStore.LinuxSIfreg ||
                    sourceLinuxStatx.StxUid != QuarantineStagingStore.LinuxGeteuid())
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

            if (!skipRedundantVerificationRead)
            {
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
                    ArrayPool<byte>.Shared.Return(buffer);
                }
            }
            else
            {
                onStorageProgress?.Invoke(expectedSizeBytes);
            }

            verifyTicks = Stopwatch.GetTimestamp() - verifyStart;
            onBeforePublish?.Invoke();
            long commitStart = Stopwatch.GetTimestamp();

            using SafeFileHandle? targetDirHandle = OperatingSystem.IsWindows()
                ? QuarantineStagingStore.OpenValidatedWindowsDirectoryHandle(targetDirectory, out _)
                : null;

            if (OperatingSystem.IsWindows() && hasWindowsHandleInfo)
            {
                if (targetDirHandle == null || targetDirHandle.IsInvalid)
                {
                    return null;
                }

                if (!QuarantineStagingStore.TryGetWindowsHandleInfo(targetDirHandle, out QuarantineStagingStore.ByHandleFileInformation validatedDirInfo) ||
                    (validatedDirInfo.FileAttributes & (uint)FileAttributes.ReparsePoint) != 0)
                {
                    throw new IOException("The destination directory is a symbolic link or reparse point.");
                }

                if (validatedDirInfo.VolumeSerialNumber != sourceHandleInfo.VolumeSerialNumber)
                {
                    return null;
                }
            }

            QuarantineStagingStore.LinuxStatx validatedLinuxDirInfo = default;
            using SafeFileHandle? linuxTargetDirHandle = OperatingSystem.IsLinux() && hasLinuxHandleInfo
                ? QuarantineStagingStore.OpenValidatedLinuxDirectoryHandle(targetDirectory, out validatedLinuxDirInfo)
                : null;

            QuarantineStagingStore.LinuxStatx validatedLinuxStagingDirInfo = default;
            using SafeFileHandle? linuxStagingDirHandle = OperatingSystem.IsLinux() && hasLinuxHandleInfo
                ? QuarantineStagingStore.OpenValidatedLinuxDirectoryHandle(Path.GetDirectoryName(quarantinePath)!, out validatedLinuxStagingDirInfo)
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
                if (onPublishCandidate != null)
                    await onPublishCandidate(committedPath, cancellationToken).ConfigureAwait(false);
                try
                {
                    if (OperatingSystem.IsWindows())
                    {
                        QuarantineStagingStore.RenameVerifiedWindowsFile(
                            source.SafeFileHandle,
                            targetDirHandle!,
                            Path.GetFileName(committedPath));
                    }
                    else if (OperatingSystem.IsLinux() && hasLinuxHandleInfo)
                    {
                        QuarantineStagingStore.RenameVerifiedLinuxFile(
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
                    error.NativeErrorCode == QuarantineStagingStore.LinuxEexist)
                {
                    committedPath = ResolveCollision(Path.Combine(targetDirectory, baseFileName));
                }
                catch (Win32Exception error) when (
                    OperatingSystem.IsLinux() &&
                    QuarantineStagingStore.IsUnsupportedAtomicErrno(error.NativeErrorCode))
                {
                    return null;
                }
                catch (Win32Exception error) when (
                    OperatingSystem.IsLinux() &&
                    QuarantineStagingStore.IsFatalStorageErrno(error.NativeErrorCode))
                {
                    throw QuarantineStagingStore.CreateStorageExceptionFromErrno(error.NativeErrorCode, "renameat2", error);
                }
                catch (IOException) when (
                    !OperatingSystem.IsWindows() && !OperatingSystem.IsLinux() &&
                    attempt < 32 && File.Exists(committedPath) && File.Exists(quarantinePath))
                {
                    committedPath = ResolveCollision(Path.Combine(targetDirectory, baseFileName));
                }
            }

            if (OperatingSystem.IsWindows() && hasWindowsHandleInfo)
            {
                if (!QuarantineStagingStore.TryGetWindowsHandleInfo(source.SafeFileHandle, out QuarantineStagingStore.ByHandleFileInformation publishedInfo) ||
                    publishedInfo.VolumeSerialNumber != sourceHandleInfo.VolumeSerialNumber ||
                    publishedInfo.FileIndexHigh != sourceHandleInfo.FileIndexHigh ||
                    publishedInfo.FileIndexLow != sourceHandleInfo.FileIndexLow)
                {
                    throw new IOException("Destination file handle identity changed during atomic commit.");
                }
            }
            else if (OperatingSystem.IsLinux() && hasLinuxHandleInfo)
            {
                if (!QuarantineStagingStore.TryGetLinuxFileIdentityByFd(source.SafeFileHandle.DangerousGetHandle().ToInt32(), out QuarantineStagingStore.LinuxStatx publishedInfo, out _) ||
                    publishedInfo.StxDevMajor != sourceLinuxStatx.StxDevMajor ||
                    publishedInfo.StxDevMinor != sourceLinuxStatx.StxDevMinor ||
                    publishedInfo.StxIno != sourceLinuxStatx.StxIno)
                {
                    throw new IOException("Destination file descriptor identity changed during atomic commit.");
                }

                if (!QuarantineStagingStore.TryGetLinuxFileIdentityByPath(committedPath, out QuarantineStagingStore.LinuxStatx committedInfo, out _) ||
                    committedInfo.StxDevMajor != sourceLinuxStatx.StxDevMajor ||
                    committedInfo.StxDevMinor != sourceLinuxStatx.StxDevMinor ||
                    committedInfo.StxIno != sourceLinuxStatx.StxIno)
                {
                    throw new IOException("Published destination file identity does not match verified source file.");
                }

                QuarantineStagingStore.TryFlushLinuxDirectory(linuxTargetDirHandle);
            }

            commitTicks = Stopwatch.GetTimestamp() - commitStart;
        }

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
        bool skipRedundantVerificationRead,
        Action? onBeforePublish,
        Action<long>? onStorageProgress,
        Func<string, CancellationToken, Task>? onPublishCandidate,
        CancellationToken cancellationToken)
    {
        IStagingFileSystem fs = _fileSystem!;
        uint euid = fs.GetEffectiveUserId();

        if (!fs.TryGetFileIdentityByPath(quarantinePath, out QuarantineFileIdentity qStat, out int qErr))
        {
            if (QuarantineStagingStore.IsFatalStorageErrno(qErr))
            {
                throw QuarantineStagingStore.CreateStorageExceptionFromErrno(qErr, "statx");
            }
            return null;
        }

        if (qStat.IsSymbolicLink || !qStat.IsRegularFile || qStat.OwnerUid != euid)
        {
            throw new IOException("The quarantine file is not a valid user-owned regular file.");
        }

        if (!fs.TryGetFileIdentityByPath(targetDirectory, out QuarantineFileIdentity tStat, out int tErr))
        {
            if (QuarantineStagingStore.IsFatalStorageErrno(tErr))
            {
                throw QuarantineStagingStore.CreateStorageExceptionFromErrno(tErr, "statx");
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
                    if (QuarantineStagingStore.IsFatalStorageErrno(fdErr))
                    {
                        throw QuarantineStagingStore.CreateStorageExceptionFromErrno(fdErr, "statx");
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

            if (!skipRedundantVerificationRead)
            {
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
                    ArrayPool<byte>.Shared.Return(buffer);
                }
            }
            else
            {
                onStorageProgress?.Invoke(expectedSizeBytes);
            }

            verifyTicks = Stopwatch.GetTimestamp() - verifyStart;
            onBeforePublish?.Invoke();
            long commitStart = Stopwatch.GetTimestamp();

            committedPath = ResolveSafeDestinationPath(rawRelativePath);
            string stagingDir = Path.GetDirectoryName(quarantinePath)!;
            string sourceFileName = Path.GetFileName(quarantinePath);

            for (int attempt = 0; ; attempt++)
            {
                if (onPublishCandidate != null)
                    await onPublishCandidate(committedPath, cancellationToken).ConfigureAwait(false);
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
                    error.NativeErrorCode is QuarantineStagingStore.LinuxEexist or 80 or 183)
                {
                    committedPath = ResolveCollision(Path.Combine(targetDirectory, baseFileName));
                }
                catch (Win32Exception error) when (QuarantineStagingStore.IsUnsupportedAtomicErrno(error.NativeErrorCode))
                {
                    return null;
                }
                catch (Win32Exception error) when (QuarantineStagingStore.IsFatalStorageErrno(error.NativeErrorCode))
                {
                    throw QuarantineStagingStore.CreateStorageExceptionFromErrno(error.NativeErrorCode, "renameat2", error);
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
            else if (QuarantineStagingStore.IsFatalStorageErrno(cErr))
            {
                throw QuarantineStagingStore.CreateStorageExceptionFromErrno(cErr, "statx");
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
        bool skipRedundantVerificationRead,
        Action<long>? onStorageProgress,
        Func<string, CancellationToken, Task>? onPublishCandidate,
        CancellationToken cancellationToken)
    {
        if (_store.GetDestinationAvailableFreeSpaceBytes() < expectedSizeBytes)
        {
            throw new IOException(
                "Insufficient disk space on destination volume for verified copy fallback (ENOSPC).",
                new Win32Exception(QuarantineStagingStore.LinuxEnospc));
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

                QuarantineStagingStore.RejectLink(temporaryPath);
                if (!skipRedundantVerificationRead || _fileSystem != null)
                {
                    await using Stream verifyDest = OpenRead(temporaryPath);
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
            catch (Win32Exception error) when (QuarantineStagingStore.IsFatalStorageErrno(error.NativeErrorCode))
            {
                throw QuarantineStagingStore.CreateStorageExceptionFromErrno(error.NativeErrorCode, "verified-copy", error);
            }
            finally
            {
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
                if (onPublishCandidate != null)
                    await onPublishCandidate(finalPath, cancellationToken).ConfigureAwait(false);
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
                QuarantineStagingStore.TryFlushLinuxDirectoryPath(targetDirectory);
            }

            TryNormalizeCommittedFileMetadata(finalPath);

            try { File.Delete(quarantinePath); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }

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

    private string ResolveSafeDestinationPath(string rawRelativePath)
    {
        string safeRelativePath = WindowsPathSanitizer.SanitizeRelativePath(rawRelativePath);
        string root = Path.GetFullPath(_store.DestinationRoot);
        QuarantineStagingStore.RejectLinkedAncestors(root);
        Directory.CreateDirectory(root);
        QuarantineStagingStore.RejectLinkedAncestors(root);

        string[] segments = safeRelativePath.Split('/');
        string current = root;
        for (int i = 0; i < segments.Length - 1; i++)
        {
            current = Path.Combine(current, segments[i]);
            Directory.CreateDirectory(current);
            QuarantineStagingStore.RejectLink(current);
        }

        return ResolveCollision(Path.Combine(current, segments[^1]));
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

    private static FileStream OpenExclusiveVerificationStream(string path)
    {
        if (!OperatingSystem.IsWindows())
        {
            return new FileStream(path, FileMode.Open, FileAccess.ReadWrite,
                FileShare.None, VerificationReadBufferBytes,
                FileOptions.Asynchronous | FileOptions.SequentialScan);
        }

        SafeFileHandle handle = QuarantineStagingStore.CreateWindowsVerificationHandle(path);
        try
        {
            return new FileStream(handle, FileAccess.ReadWrite,
                VerificationReadBufferBytes, isAsync: true);
        }
        catch
        {
            handle.Dispose();
            throw;
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
}
