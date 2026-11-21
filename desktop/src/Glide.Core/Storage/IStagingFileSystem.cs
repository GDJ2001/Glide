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

/// <summary>
/// Operating-system filesystem abstraction for quarantine staging, file identity verification,
/// and no-replace atomic publication.
/// </summary>
public interface IStagingFileSystem
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

/// <summary>
/// Backwards-compatible alias interface for <see cref="IStagingFileSystem"/>.
/// </summary>
public interface IQuarantineFileSystem : IStagingFileSystem
{
}
